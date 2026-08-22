#include "motion.h"
#include "settings.h"
#include "sys.h"

/* --------------------------------------------------------------------------
 * registers
 * -------------------------------------------------------------------------- */

#define RCC_BASE    0x40021000u
#define RCC_APB2ENR (*(volatile uint32_t *)(RCC_BASE + 0x18))
#define RCC_APB1ENR (*(volatile uint32_t *)(RCC_BASE + 0x1C))

#define GPIOB_BASE  0x40010C00u
#define GPIOC_BASE  0x40011000u
#define GPIOD_BASE  0x40011400u
#define GPIO_CRL(b)  (*(volatile uint32_t *)((b) + 0x00))
#define GPIO_CRH(b)  (*(volatile uint32_t *)((b) + 0x04))
#define GPIO_BSRR(b) (*(volatile uint32_t *)((b) + 0x10))

#define TIM3_BASE   0x40000400u
#define TIM3_CR1    (*(volatile uint32_t *)(TIM3_BASE + 0x00))
#define TIM3_EGR    (*(volatile uint32_t *)(TIM3_BASE + 0x14))
#define TIM3_CCMR2  (*(volatile uint32_t *)(TIM3_BASE + 0x1C))
#define TIM3_CCER   (*(volatile uint32_t *)(TIM3_BASE + 0x20))
#define TIM3_PSC    (*(volatile uint32_t *)(TIM3_BASE + 0x28))
#define TIM3_ARR    (*(volatile uint32_t *)(TIM3_BASE + 0x2C))
#define TIM3_CCR3   (*(volatile uint32_t *)(TIM3_BASE + 0x3C))

/* BSRR sets on the low half and clears on the high half, so every pin change
 * is one atomic store and never a read-modify-write. */
#define PIN_HIGH(port, n) (GPIO_BSRR(port) = 1u << (n))
#define PIN_LOW(port, n)  (GPIO_BSRR(port) = 1u << ((n) + 16))

#define STEP_PORT GPIOC_BASE
#define STEP_PIN  6
#define DIR_PORT  GPIOB_BASE
#define DIR_PIN   12
#define EN_PORT   GPIOB_BASE
#define EN_PIN    10
#define BEEP_PORT GPIOD_BASE
#define BEEP_PIN  2

#define CFG_OUT_PP_50MHZ 0x3u
#define CFG_AF_PP_50MHZ  0xBu

#define VREF_PWM_HZ 250000u   /* 4 us period, as the stock printer config uses */

/* --------------------------------------------------------------------------
 * state
 * -------------------------------------------------------------------------- */

static int enabled;
static int running;
static uint32_t shot;         /* index within the current revolution */
static int32_t  pos;          /* microsteps from the zero mark       */
static uint32_t next_move_ms;

/* --------------------------------------------------------------------------
 * pins
 * -------------------------------------------------------------------------- */

static void pin_mode(uint32_t port, uint32_t pin, uint32_t mode)
{
    volatile uint32_t *reg = (pin < 8) ? &GPIO_CRL(port) : &GPIO_CRH(port);
    uint32_t shift = (pin & 7u) * 4u;
    *reg = (*reg & ~(0xFu << shift)) | (mode << shift);
}

void motion_apply_current(void)
{
    uint32_t arr = SYSCLK_HZ / VREF_PWM_HZ;
    uint32_t pct = cfg.current_pct > 100u ? 100u : cfg.current_pct;
    TIM3_CCR3 = arr * pct / 100u;
}

void motion_apply_dir(void)
{
    if (cfg.dir >= 0) PIN_HIGH(DIR_PORT, DIR_PIN);
    else              PIN_LOW(DIR_PORT, DIR_PIN);
    /* Drivers want the direction stable well before the next rising edge. */
    delay_us(10);
}

void motion_init(void)
{
    RCC_APB2ENR |= (1u << 0) | (1u << 3) | (1u << 4) | (1u << 5);  /* AFIO,B,C,D */
    RCC_APB1ENR |= (1u << 1);                                       /* TIM3 */

    /* Park the driver disabled before the pin becomes an output, so a floating
     * enable line cannot energise the motor during bring-up. Active low. */
    PIN_HIGH(EN_PORT, EN_PIN);
    pin_mode(EN_PORT, EN_PIN, CFG_OUT_PP_50MHZ);

    PIN_LOW(STEP_PORT, STEP_PIN);
    pin_mode(STEP_PORT, STEP_PIN, CFG_OUT_PP_50MHZ);

    PIN_LOW(DIR_PORT, DIR_PIN);
    pin_mode(DIR_PORT, DIR_PIN, CFG_OUT_PP_50MHZ);

    PIN_LOW(BEEP_PORT, BEEP_PIN);
    pin_mode(BEEP_PORT, BEEP_PIN, CFG_OUT_PP_50MHZ);

    /* PB0 feeds the X/Y Vref through an RC filter: a fast PWM read as DC. */
    pin_mode(GPIOB_BASE, 0, CFG_AF_PP_50MHZ);
    TIM3_PSC = 0;
    TIM3_ARR = SYSCLK_HZ / VREF_PWM_HZ - 1u;
    TIM3_CCMR2 = (6u << 4) | (1u << 3);   /* OC3 PWM mode 1, preload enabled */
    TIM3_CCER |= 1u << 8;                 /* CC3E */
    motion_apply_current();
    TIM3_EGR = 1u;                        /* latch the preloaded values */
    TIM3_CR1 = (1u << 7) | 1u;            /* ARPE, CEN */

    motion_apply_dir();
}

void motion_enable(int on)
{
    enabled = on ? 1 : 0;
    if (on) PIN_LOW(EN_PORT, EN_PIN);
    else    PIN_HIGH(EN_PORT, EN_PIN);
}

int motion_enabled(void) { return enabled; }

/* --------------------------------------------------------------------------
 * buzzer
 * -------------------------------------------------------------------------- */

void beep(uint32_t hz, uint32_t ms)
{
    if (!cfg.beep_on || !hz || !ms) return;
    uint32_t half_us = 500000u / hz;
    /* Above 500 kHz the half period rounds to zero, and the cycle count below
     * would then divide by it. Nothing audible lives up there anyway. */
    if (!half_us) return;
    uint32_t cycles = (ms * 1000u) / (half_us * 2u);
    for (uint32_t i = 0; i < cycles; i++) {
        PIN_HIGH(BEEP_PORT, BEEP_PIN);
        delay_us(half_us);
        PIN_LOW(BEEP_PORT, BEEP_PIN);
        delay_us(half_us);
    }
}

/* --------------------------------------------------------------------------
 * stepping
 * -------------------------------------------------------------------------- */

static void step_once(uint32_t gap_us)
{
    /* A4988-class drivers latch on the rising edge and need about 1 us of
     * pulse; 3 us is comfortable margin without eating the step period. */
    PIN_HIGH(STEP_PORT, STEP_PIN);
    delay_us(3);
    PIN_LOW(STEP_PORT, STEP_PIN);
    delay_us(gap_us > 3u ? gap_us - 3u : 1u);
}

/* Trapezoidal move: ease in, cruise, ease out.
 *
 * The ramp is linear in step delay rather than in velocity. Not the textbook
 * profile, but across a few dozen steps it lands within a millisecond of it and
 * costs no division per step. */
static void run_steps(uint32_t steps)
{
    if (!steps) return;
    if (!enabled) motion_enable(1);

    uint32_t ramp = cfg.ramp_steps;
    if (ramp * 2u > steps) ramp = steps / 2u;

    uint32_t d0 = cfg.d_start_us;
    uint32_t d1 = cfg.d_min_us;
    if (d1 > d0) d1 = d0;
    uint32_t span = d0 - d1;

    for (uint32_t i = 0; i < steps; i++) {
        uint32_t gap;
        if (ramp && i < ramp)                  gap = d0 - span * i / ramp;
        else if (ramp && i >= steps - ramp)    gap = d0 - span * (steps - 1u - i) / ramp;
        else                                   gap = d1;
        step_once(gap);
    }
}

/* Signed move. Direction is a setting rather than a per-move argument, so a
 * reversal here has to put it back before returning -- otherwise the next
 * timed index would silently run backwards. */
void motion_jog_steps(int32_t steps)
{
    if (!steps) return;
    int reversed = 0;
    /* The resting DIR level already encodes cfg.dir (motion_apply_dir), so the
     * only thing that flips it here is the sign of the move. Testing cfg.dir
     * again would cancel the setting out and leave it with no effect at all. */
    if (steps < 0) {
        if (cfg.dir >= 0) PIN_LOW(DIR_PORT, DIR_PIN);
        else              PIN_HIGH(DIR_PORT, DIR_PIN);
        delay_us(10);
        reversed = 1;
    }
    /* Negating INT32_MIN overflows; go through unsigned for the magnitude. */
    run_steps(steps < 0 ? (uint32_t)(0u - (uint32_t)steps) : (uint32_t)steps);
    pos += steps;
    if (reversed) motion_apply_dir();
}

/* --------------------------------------------------------------------------
 * index sequence
 * -------------------------------------------------------------------------- */

/* Target for a given index, recomputed from the index number rather than
 * accumulated. A shot count that does not divide the revolution evenly then
 * spreads the remainder across the turn instead of leaving one wide gap where
 * the circle closes. */
static int32_t target_for(uint32_t n)
{
    uint32_t spr = settings_steps_per_rev();
    uint32_t shots = cfg.shots ? cfg.shots : 1u;
    return (int32_t)((uint32_t)((uint64_t)n * spr / shots));
}

void motion_index(void)
{
    uint32_t next = shot + 1u;
    int32_t want = target_for(next);
    motion_jog_steps(want - pos);

    if (next >= cfg.shots) {
        /* Wrap at a full turn: the table is back where it started, so the
         * position counter goes back with it and cannot drift over sessions. */
        shot = 0;
        pos = 0;
    } else {
        shot = next;
    }
}

void motion_goto_shot(uint32_t n)
{
    if (cfg.shots && n >= cfg.shots) n %= cfg.shots;
    motion_jog_steps(target_for(n) - pos);
    shot = n;
}

void motion_zero(void)
{
    shot = 0;
    pos = 0;
}

void motion_run(void)
{
    motion_enable(1);
    running = 1;
    next_move_ms = millis() + cfg.interval_ms;
    beep(cfg.beep_hz, cfg.beep_ms);
}

void motion_stop(void)
{
    running = 0;
}

void motion_sync(void)
{
    /* Restart the still window from now without moving. This is what a camera
     * uses to line its own interval up with the table's. */
    next_move_ms = millis() + cfg.interval_ms;
    if (running) beep(cfg.beep_hz, cfg.beep_ms);
}

int motion_running(void) { return running; }
uint32_t motion_shot(void) { return shot; }
int32_t motion_position(void) { return pos; }

uint32_t motion_next_in(void)
{
    if (!running) return 0;
    uint32_t now = millis();
    /* Signed compare via subtraction keeps this correct across the 49-day
     * millisecond rollover. */
    return ((int32_t)(next_move_ms - now) > 0) ? (next_move_ms - now) : 0u;
}

void motion_poll(void)
{
    if (!running) return;
    if ((int32_t)(millis() - next_move_ms) < 0) return;

    motion_index();
    /* Advance the schedule from the previous deadline, not from now: adding to
     * "now" would fold each index's own duration into the period and let the
     * shots drift later and later. */
    next_move_ms += cfg.interval_ms;
    if ((int32_t)(millis() - next_move_ms) > 0) next_move_ms = millis() + cfg.interval_ms;
    beep(cfg.beep_hz, cfg.beep_ms);
}
