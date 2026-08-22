/* Boot picker. 8 KiB at the reset vector, and the only thing on this board that
 * an over-the-air update never touches.
 *
 * It reads the metadata page, checks the slot marked live, and jumps to it. If
 * that slot does not check out it tries the other one; if neither does, it
 * hands over to the STM32's factory bootloader in ROM, so a board with two bad
 * images is still recoverable down the same USB cable that broke it -- no
 * jumper, no programmer.
 *
 * No globals, so there is no .data or .bss to initialise: the whole program is
 * one function that never returns.
 */
#include <stdint.h>
#include "../layout.h"

#define SCB_VTOR      (*(volatile uint32_t *)0xE000ED08u)
#define SYSTEM_MEMORY 0x1FFFF000u

extern uint32_t _estack;

__attribute__((noreturn))
static void jump_to(uint32_t base)
{
    uint32_t sp = *(volatile uint32_t *)base;
    uint32_t pc = *(volatile uint32_t *)(base + 4u);
    SCB_VTOR = base;
    __asm__ volatile("msr msp, %0" :: "r"(sp));
    ((void (*)(void))pc)();
    for (;;) {}
}

/* Enough of a sanity check to run a freshly programmed board that has never
 * been updated and so has no metadata yet. */
static int looks_like_code(uint32_t base)
{
    uint32_t sp = *(volatile uint32_t *)base;
    uint32_t pc = *(volatile uint32_t *)(base + 4u);
    if ((sp & 0xFFFF0000u) != 0x20000000u) return 0;
    return pc >= base && pc < base + SLOT_SIZE;
}

__attribute__((noreturn))
void Reset_Handler(void)
{
    const meta_t *m = (const meta_t *)META_BASE;

    if (meta_valid(m)) {
        uint32_t first = m->active & 1u;
        if (slot_valid(m, first)) jump_to(slot_base(first));
        /* The live slot is gone, but the previous one is still sitting there
         * untouched -- that is the entire point of keeping two. */
        uint32_t other = first ^ 1u;
        if (slot_valid(m, other)) jump_to(slot_base(other));
        /* A board flashed over SWD and then updated once has a slot whose image
         * predates any metadata, so its length was never recorded and the
         * checksum above cannot pass. Refusing to run it would throw away a
         * perfectly good fallback and send a working board to the bootloader. */
        if (m->size[other] == 0u && looks_like_code(slot_base(other))) {
            jump_to(slot_base(other));
        }
    } else {
        /* Fresh board: programmed over SWD, never updated, no metadata written
         * yet. Run whatever is in slot A if it looks like an image at all. */
        if (looks_like_code(SLOT_A_BASE)) jump_to(SLOT_A_BASE);
        if (looks_like_code(SLOT_B_BASE)) jump_to(SLOT_B_BASE);
    }

    jump_to(SYSTEM_MEMORY);
}

__attribute__((section(".isr_vector"), used))
void (*const vector_table[])(void) = {
    (void (*)(void)) &_estack,
    Reset_Handler,
};
