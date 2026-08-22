/* Self-update: take a firmware image in over the serial port and write it to
 * the slot that is not running.
 *
 * This exists alongside the DFU command rather than instead of it. DFU hands
 * over to the factory bootloader in ROM, which is the recovery path of last
 * resort; this is the everyday path, and because it writes the spare slot and
 * only then flips the metadata, a failure at any point -- bad checksum, dropped
 * connection, power cut mid-erase -- leaves the running firmware exactly where
 * it was.
 */
#include "update.h"
#include "layout.h"
#include "sys.h"
#include "uart.h"

#define FLASH_BASE  0x40022000u
#define FLASH_KEYR  (*(volatile uint32_t *)(FLASH_BASE + 0x04))
#define FLASH_SR    (*(volatile uint32_t *)(FLASH_BASE + 0x0C))
#define FLASH_CR    (*(volatile uint32_t *)(FLASH_BASE + 0x10))
#define FLASH_AR    (*(volatile uint32_t *)(FLASH_BASE + 0x14))

#define FLASH_SR_BSY  (1u << 0)
#define FLASH_CR_PG   (1u << 0)
#define FLASH_CR_PER  (1u << 1)
#define FLASH_CR_STRT (1u << 6)
#define FLASH_CR_LOCK (1u << 7)

#define SCB_AIRCR   (*(volatile uint32_t *)0xE000ED0Cu)

extern uint32_t _vector_base;   /* where this slot was linked */

/* The received image. Static: 36 KiB has no business on the stack. */
static uint8_t image[UPDATE_MAX];
static meta_t staged;

uint32_t update_running_slot(void)
{
    return ((uint32_t)&_vector_base == SLOT_B_BASE) ? 1u : 0u;
}

uint32_t update_target_slot(void)
{
    return update_running_slot() ^ 1u;
}

/* Runs from RAM. While the flash controller is busy the core must not fetch
 * anything from flash -- not code, not constants -- so this takes everything as
 * arguments and calls nothing. GCC keeps a function's literal pool in the
 * function's own section, which is why that section is linked into RAM too.
 */
__attribute__((section(".ramfunc"), noinline, used, noreturn))
static void flash_commit(uint32_t dst_base, const uint16_t *src, uint32_t halfwords,
                         uint32_t pages, const uint16_t *meta_src, uint32_t meta_halfwords)
{
    FLASH_KEYR = 0x45670123u;
    FLASH_KEYR = 0xCDEF89ABu;

    for (uint32_t p = 0; p < pages; p++) {
        while (FLASH_SR & FLASH_SR_BSY) {}
        FLASH_CR |= FLASH_CR_PER;
        FLASH_AR = dst_base + p * FLASH_PAGE_SIZE;
        FLASH_CR |= FLASH_CR_STRT;
        while (FLASH_SR & FLASH_SR_BSY) {}
        FLASH_CR &= ~FLASH_CR_PER;
    }

    volatile uint16_t *dst = (volatile uint16_t *)dst_base;
    FLASH_CR |= FLASH_CR_PG;
    for (uint32_t i = 0; i < halfwords; i++) {
        dst[i] = src[i];
        while (FLASH_SR & FLASH_SR_BSY) {}
    }
    FLASH_CR &= ~FLASH_CR_PG;

    /* Only now, with a complete and verified image on the other side, does the
     * board change its mind about which slot to boot. */
    while (FLASH_SR & FLASH_SR_BSY) {}
    FLASH_CR |= FLASH_CR_PER;
    FLASH_AR = META_BASE;
    FLASH_CR |= FLASH_CR_STRT;
    while (FLASH_SR & FLASH_SR_BSY) {}
    FLASH_CR &= ~FLASH_CR_PER;

    volatile uint16_t *mdst = (volatile uint16_t *)META_BASE;
    FLASH_CR |= FLASH_CR_PG;
    for (uint32_t i = 0; i < meta_halfwords; i++) {
        mdst[i] = meta_src[i];
        while (FLASH_SR & FLASH_SR_BSY) {}
    }
    FLASH_CR &= ~FLASH_CR_PG;
    FLASH_CR |= FLASH_CR_LOCK;

    SCB_AIRCR = 0x05FA0004u;        /* system reset; the loader takes it from here */
    for (;;) {}
}

void update_receive(uint32_t size, uint32_t crc)
{
    if (size < 256u || size > UPDATE_MAX || size > SLOT_SIZE) {
        uart_puts("err size out of range\n");
        return;
    }

    uart_puts("ready\n");

    uint32_t got = 0;
    uint32_t last = millis();
    while (got < size) {
        int c = uart_getbyte();
        if (c < 0) {
            /* Five seconds of silence means the sender is gone. Nothing has
             * been erased at this point, so returning costs nothing. */
            if ((millis() - last) > 5000u) {
                uart_puts("err timeout after ");
                uart_putdec((int32_t)got);
                uart_puts(" bytes\n");
                return;
            }
            continue;
        }
        image[got++] = (uint8_t)c;
        last = millis();
    }

    uint32_t got_crc = crc32_of(image, size);
    if (got_crc != crc) {
        uart_puts("err crc mismatch\n");
        return;
    }

    uint32_t target = update_target_slot();

    /* An image linked for the wrong slot would run its first absolute branch
     * straight into the other slot's code, so check where it thinks it lives. */
    uint32_t sp = (uint32_t)image[0] | ((uint32_t)image[1] << 8)
                | ((uint32_t)image[2] << 16) | ((uint32_t)image[3] << 24);
    uint32_t pc = (uint32_t)image[4] | ((uint32_t)image[5] << 8)
                | ((uint32_t)image[6] << 16) | ((uint32_t)image[7] << 24);
    if ((sp & 0xFFFF0000u) != 0x20000000u) {
        uart_puts("err not an stm32 image\n");
        return;
    }
    if (pc < slot_base(target) || pc >= slot_base(target) + SLOT_SIZE) {
        uart_puts("err image linked for the wrong slot\n");
        return;
    }

    const meta_t *old = (const meta_t *)META_BASE;
    if (meta_valid(old)) {
        staged = *old;
    } else {
        /* Zeroed by hand: a compound literal here compiles to a memset call,
         * and there is no libc in this build. */
        uint8_t *p = (uint8_t *)&staged;
        for (uint32_t i = 0; i < sizeof(staged); i++) p[i] = 0;
    }
    staged.magic = META_MAGIC;
    staged.active = target;
    staged.size[target] = size;
    staged.crc[target] = crc;
    staged.seq++;
    /* Keep whatever the other slot's record said: it is still the fallback. */
    staged.self_crc = crc32_of((const uint8_t *)&staged,
                               sizeof(staged) - sizeof(staged.self_crc));

    uart_puts("ok writing slot ");
    uart_putdec((int32_t)target);
    uart_puts(", do not cut power\n");
    for (volatile uint32_t i = 0; i < 400000u; i++) {}   /* let that clock out */

    __asm__ volatile("cpsid i");
    flash_commit(slot_base(target), (const uint16_t *)image, (size + 1u) / 2u,
                 (size + FLASH_PAGE_SIZE - 1u) / FLASH_PAGE_SIZE,
                 (const uint16_t *)&staged, sizeof(staged) / 2u);
}
