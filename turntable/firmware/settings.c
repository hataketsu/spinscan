#include "settings.h"
#include "config.h"
#include "layout.h"

settings_t cfg;

#define SETTINGS_MAGIC 0x54524E31u   /* "TRN1" */

/* Settings live in the last flash page, outside both application slots, so an
 * over-the-air update never disturbs them. Address comes from layout.h. */
#define SETTINGS_ADDR SETTINGS_BASE

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

void settings_defaults(void)
{
    cfg.magic       = SETTINGS_MAGIC;
    cfg.shots       = SHOTS_PER_REV;
    cfg.micro       = MICROSTEPS;
    cfg.motor_steps = MOTOR_FULL_STEPS;
    cfg.gear_num    = GEAR_NUM;
    cfg.gear_den    = GEAR_DEN;
    cfg.interval_ms = INTERVAL_MS;
    cfg.dir         = DIRECTION;
    cfg.current_pct = (uint16_t)(CURRENT_DUTY * 100.0f);
    cfg.d_start_us  = STEP_DELAY_START_US;
    cfg.d_min_us    = STEP_DELAY_MIN_US;
    cfg.ramp_steps  = RAMP_STEPS;
    cfg.beep_hz     = BEEP_HZ;
    cfg.beep_ms     = BEEP_MS;
    cfg.beep_on     = BEEP_ENABLE;
    cfg.pad         = 0;
    cfg.crc         = 0;
}

/* Not a real CRC. It only has to catch a half-written page, and a page that was
 * interrupted mid-program reads back as 0xFFFF runs, which this rejects. */
static uint32_t checksum(const settings_t *s)
{
    const uint8_t *p = (const uint8_t *)s;
    uint32_t sum = 0x9E3779B9u;
    for (uint32_t i = 0; i < sizeof(*s) - sizeof(s->crc); i++) {
        sum = (sum << 5) - sum + p[i];
    }
    return sum;
}

uint32_t settings_steps_per_rev(void)
{
    uint32_t den = cfg.gear_den ? cfg.gear_den : 1u;
    return (uint32_t)cfg.motor_steps * cfg.micro * cfg.gear_num / den;
}

int settings_load(void)
{
    const settings_t *saved = (const settings_t *)SETTINGS_ADDR;
    if (saved->magic != SETTINGS_MAGIC) return 0;
    if (saved->crc != checksum(saved)) return 0;
    cfg = *saved;
    return 1;
}

static void flash_wait(void)
{
    while (FLASH_SR & FLASH_SR_BSY) {}
}

int settings_save(void)
{
    settings_t out = cfg;
    out.magic = SETTINGS_MAGIC;
    out.pad = 0;
    out.crc = checksum(&out);

    /* Interrupts off for the duration: an ISR that runs from flash while the
     * flash controller is mid-erase stalls the core, and on this part that is
     * the documented way to get a bus fault instead of a stall. */
    __asm__ volatile("cpsid i");

    FLASH_KEYR = 0x45670123u;
    FLASH_KEYR = 0xCDEF89ABu;

    flash_wait();
    FLASH_CR |= FLASH_CR_PER;
    FLASH_AR = SETTINGS_ADDR;
    FLASH_CR |= FLASH_CR_STRT;
    flash_wait();
    FLASH_CR &= ~FLASH_CR_PER;

    /* This part programs 16 bits at a time; nothing wider is legal. */
    const uint16_t *src = (const uint16_t *)&out;
    volatile uint16_t *dst = (volatile uint16_t *)SETTINGS_ADDR;
    FLASH_CR |= FLASH_CR_PG;
    for (uint32_t i = 0; i < sizeof(out) / 2u; i++) {
        dst[i] = src[i];
        flash_wait();
    }
    FLASH_CR &= ~FLASH_CR_PG;
    FLASH_CR |= FLASH_CR_LOCK;

    __asm__ volatile("cpsie i");

    const settings_t *check = (const settings_t *)SETTINGS_ADDR;
    return check->magic == SETTINGS_MAGIC && check->crc == out.crc;
}
