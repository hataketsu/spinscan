/* Line-oriented control protocol on USART1.
 *
 * Deliberately plain ASCII, one command per line, one "ok" or "err ..." back.
 * It has to be equally comfortable from a terminal, from a Python script, and
 * from an Android phone doing raw bulk transfers over OTG -- none of which want
 * to implement framing.
 *
 *   ?                     status dump, key=value lines then "ok"
 *   HELP                  list commands
 *   SET <key> <value>     change one setting (see keys[] below)
 *   GET <key>             read one setting
 *   SAVE / LOAD / DEFAULTS
 *   RUN / STOP / SYNC     timed sequence: start, halt, realign the still window
 *   STEP                  index once, right now
 *   JOG <steps>           signed raw microsteps
 *   TURN <deg>            signed degrees of table rotation
 *   GOTO <shot>           move to an index number
 *   ZERO                  call this position index 0
 *   ON / OFF              driver holding torque
 *   BEEP [hz] [ms]
 */
#include "command.h"
#include "layout.h"
#include "motion.h"
#include "settings.h"
#include "sys.h"
#include "uart.h"
#include "update.h"

/* --------------------------------------------------------------------------
 * tiny string helpers -- no libc is linked
 * -------------------------------------------------------------------------- */

static int str_eq_ci(const char *a, const char *b)
{
    while (*a && *b) {
        char ca = (*a >= 'a' && *a <= 'z') ? (char)(*a - 32) : *a;
        char cb = (*b >= 'a' && *b <= 'z') ? (char)(*b - 32) : *b;
        if (ca != cb) return 0;
        a++; b++;
    }
    return *a == '\0' && *b == '\0';
}

/* Splits in place on runs of whitespace; returns how many pieces were found. */
static uint32_t tokenize(char *s, char *tok[], uint32_t max)
{
    uint32_t n = 0;
    while (*s && n < max) {
        while (*s == ' ' || *s == '\t') *s++ = '\0';
        if (!*s) break;
        tok[n++] = s;
        while (*s && *s != ' ' && *s != '\t') s++;
    }
    return n;
}

/* Returns 0 on a malformed number rather than guessing, so "SET shots twelve"
 * is rejected instead of quietly setting zero. */
static int parse_int(const char *s, int32_t *out)
{
    uint32_t v = 0;
    int neg = 0;
    if (*s == '-') { neg = 1; s++; }
    else if (*s == '+') s++;
    if (!*s) return 0;
    /* Accumulate unsigned and refuse anything that would not fit. Wrapping
     * instead would let "SET shots 4294967298" arrive as a perfectly in-range
     * 2, which is exactly the silent guess this parser exists to avoid. */
    uint32_t limit = neg ? 2147483648u : 2147483647u;
    while (*s) {
        if (*s < '0' || *s > '9') return 0;
        uint32_t d = (uint32_t)(*s - '0');
        if (v > (limit - d) / 10u) return 0;
        v = v * 10u + d;
        s++;
    }
    *out = neg ? (int32_t)(0u - v) : (int32_t)v;
    return 1;
}

/* CRC-32 values run past what an int32_t holds, so they get their own parser
 * rather than being squeezed through the signed one. */
static int parse_u32(const char *s, uint32_t *out)
{
    uint32_t v = 0;
    if (!*s) return 0;
    while (*s) {
        if (*s < '0' || *s > '9') return 0;
        uint32_t d = (uint32_t)(*s - '0');
        /* Check before multiplying: "5000000000" wraps to 705032704, which is
         * larger than what it came from, so an after-the-fact compare misses
         * it entirely. */
        if (v > (0xFFFFFFFFu - d) / 10u) return 0;
        v = v * 10u + d;
        s++;
    }
    *out = v;
    return 1;
}

/* --------------------------------------------------------------------------
 * settings table
 * -------------------------------------------------------------------------- */

typedef enum { T_U16, T_U32, T_I16 } kind_t;

typedef struct {
    const char *name;
    void *field;
    kind_t kind;
    int32_t lo, hi;
    /* What the hardware has to be told after this changes; 0 for nothing. */
    void (*apply)(void);
} entry_t;

static const entry_t keys[] = {
    { "shots",    &cfg.shots,       T_U16, 2,    20000, 0 },
    { "interval", &cfg.interval_ms, T_U32, 50,   600000, 0 },
    { "micro",    &cfg.micro,       T_U16, 1,    256,   0 },
    { "motor",    &cfg.motor_steps, T_U16, 4,    1000,  0 },
    { "gearnum",  &cfg.gear_num,    T_U16, 1,    1000,  0 },
    { "gearden",  &cfg.gear_den,    T_U16, 1,    1000,  0 },
    { "dir",      &cfg.dir,         T_I16, -1,   1,     motion_apply_dir },
    { "current",  &cfg.current_pct, T_U16, 0,    100,   motion_apply_current },
    { "dstart",   &cfg.d_start_us,  T_U16, 100,  60000, 0 },
    { "dmin",     &cfg.d_min_us,    T_U16, 100,  60000, 0 },
    { "ramp",     &cfg.ramp_steps,  T_U16, 0,    2000,  0 },
    { "beephz",   &cfg.beep_hz,     T_U16, 100,  10000, 0 },
    { "beepms",   &cfg.beep_ms,     T_U16, 0,    2000,  0 },
    { "beep",     &cfg.beep_on,     T_U16, 0,    1,     0 },
};

#define KEY_COUNT (sizeof(keys) / sizeof(keys[0]))

static int32_t entry_get(const entry_t *e)
{
    switch (e->kind) {
    case T_U16: return *(uint16_t *)e->field;
    case T_I16: return *(int16_t *)e->field;
    default:    return (int32_t)*(uint32_t *)e->field;
    }
}

static void entry_set(const entry_t *e, int32_t v)
{
    switch (e->kind) {
    case T_U16: *(uint16_t *)e->field = (uint16_t)v; break;
    case T_I16: *(int16_t *)e->field = (int16_t)v; break;
    default:    *(uint32_t *)e->field = (uint32_t)v; break;
    }
    if (e->apply) e->apply();
}

static const entry_t *find_key(const char *name)
{
    for (uint32_t i = 0; i < KEY_COUNT; i++) {
        if (str_eq_ci(keys[i].name, name)) return &keys[i];
    }
    return 0;
}

/* --------------------------------------------------------------------------
 * replies
 * -------------------------------------------------------------------------- */

static void ok(void)  { uart_puts("ok\n"); }

static void err(const char *msg)
{
    uart_puts("err ");
    uart_puts(msg);
    uart_puts("\n");
}

static void status(void)
{
    for (uint32_t i = 0; i < KEY_COUNT; i++) {
        uart_kv(keys[i].name, entry_get(&keys[i]));
    }
    uart_kv("steps_per_rev", (int32_t)settings_steps_per_rev());
    /* Degrees per index, in hundredths -- no float formatting in this build. */
    uart_kv("deg_per_shot_x100",
            (int32_t)(36000 / (cfg.shots ? cfg.shots : 1)));
    uart_kv("running", motion_running());
    uart_kv("enabled", motion_enabled());
    uart_kv("shot", (int32_t)motion_shot());
    uart_kv("pos", motion_position());
    uart_kv("next_ms", (int32_t)motion_next_in());
    uart_kv("slot", (int32_t)update_running_slot());
    uart_kv("next_slot", (int32_t)update_target_slot());
    ok();
}

static void help(void)
{
    uart_puts("cmds: ? HELP STATUS SET GET SAVE LOAD DEFAULTS\n");
    uart_puts("      RUN STOP SYNC STEP JOG TURN GOTO ZERO ON OFF BEEP FLASH DFU\n");
    uart_puts("keys:");
    for (uint32_t i = 0; i < KEY_COUNT; i++) {
        uart_putc(' ');
        uart_puts(keys[i].name);
    }
    uart_puts("\n");
    ok();
}

/* --------------------------------------------------------------------------
 * dispatch
 * -------------------------------------------------------------------------- */

void command_execute(char *line)
{
    char *tok[4];
    uint32_t n = tokenize(line, tok, 4);
    if (!n) return;

    const char *c = tok[0];

    if (str_eq_ci(c, "?") || str_eq_ci(c, "STATUS")) { status(); return; }
    if (str_eq_ci(c, "HELP")) { help(); return; }

    if (str_eq_ci(c, "SET")) {
        if (n < 3) { err("usage: SET <key> <value>"); return; }
        const entry_t *e = find_key(tok[1]);
        if (!e) { err("unknown key"); return; }
        int32_t v;
        if (!parse_int(tok[2], &v)) { err("value not a number"); return; }
        if (v < e->lo || v > e->hi) { err("value out of range"); return; }
        entry_set(e, v);
        uart_kv(e->name, entry_get(e));
        ok();
        return;
    }

    if (str_eq_ci(c, "GET")) {
        if (n < 2) { err("usage: GET <key>"); return; }
        const entry_t *e = find_key(tok[1]);
        if (!e) { err("unknown key"); return; }
        uart_kv(e->name, entry_get(e));
        ok();
        return;
    }

    if (str_eq_ci(c, "SAVE")) {
        if (settings_save()) ok();
        else err("flash write failed");
        return;
    }

    if (str_eq_ci(c, "LOAD")) {
        if (settings_load()) {
            motion_apply_current();
            motion_apply_dir();
            ok();
        } else {
            err("no saved settings");
        }
        return;
    }

    if (str_eq_ci(c, "DEFAULTS")) {
        settings_defaults();
        motion_apply_current();
        motion_apply_dir();
        ok();
        return;
    }

    if (str_eq_ci(c, "RUN"))  { motion_run(); ok(); return; }
    if (str_eq_ci(c, "STOP")) { motion_stop(); ok(); return; }
    if (str_eq_ci(c, "SYNC")) { motion_sync(); ok(); return; }
    if (str_eq_ci(c, "STEP")) { motion_index(); uart_kv("shot", (int32_t)motion_shot()); ok(); return; }
    if (str_eq_ci(c, "ZERO")) { motion_zero(); ok(); return; }
    if (str_eq_ci(c, "ON"))   { motion_enable(1); ok(); return; }

    if (str_eq_ci(c, "OFF")) {
        /* Cutting holding torque while a sequence is running would leave the
         * table free to creep between indexes, so stop it too. */
        motion_stop();
        motion_enable(0);
        ok();
        return;
    }

    if (str_eq_ci(c, "JOG")) {
        int32_t v;
        if (n < 2 || !parse_int(tok[1], &v)) { err("usage: JOG <steps>"); return; }
        motion_jog_steps(v);
        uart_kv("pos", motion_position());
        ok();
        return;
    }

    if (str_eq_ci(c, "TURN")) {
        int32_t deg;
        if (n < 2 || !parse_int(tok[1], &deg)) { err("usage: TURN <degrees>"); return; }
        int32_t steps = (int32_t)((int64_t)deg * settings_steps_per_rev() / 360);
        motion_jog_steps(steps);
        uart_kv("pos", motion_position());
        ok();
        return;
    }

    if (str_eq_ci(c, "GOTO")) {
        int32_t v;
        if (n < 2 || !parse_int(tok[1], &v) || v < 0) { err("usage: GOTO <shot>"); return; }
        motion_goto_shot((uint32_t)v);
        uart_kv("shot", (int32_t)motion_shot());
        ok();
        return;
    }

    if (str_eq_ci(c, "FLASH")) {
        if (n < 3) { err("usage: FLASH <size> <crc32>"); return; }
        uint32_t size, crc;
        if (!parse_u32(tok[1], &size) || !size) { err("bad size"); return; }
        if (!parse_u32(tok[2], &crc)) { err("bad crc"); return; }
        /* Nothing else may touch the motor while the image is streaming in:
         * the receive loop is the only thing running for the next few seconds. */
        motion_stop();
        motion_enable(0);
        update_receive(size, crc);
        return;
    }

    if (str_eq_ci(c, "DFU")) {
        /* Stop the table first: the bootloader will not be servicing anything,
         * and a driver left energised with no firmware watching it is a motor
         * quietly cooking. */
        motion_stop();
        motion_enable(0);
        uart_puts("ok entering bootloader, 8E1\n");
        /* Let the last characters clock out before the USART is reset. */
        for (volatile uint32_t i = 0; i < 400000u; i++) {}
        sys_enter_bootloader();
        return;
    }

    if (str_eq_ci(c, "BEEP")) {
        int32_t hz = cfg.beep_hz, ms = cfg.beep_ms;
        if (n >= 2) parse_int(tok[1], &hz);
        if (n >= 3) parse_int(tok[2], &ms);
        /* beep() busy-waits, so an unclamped duration freezes the whole board:
         * the sequence stops being polled while the driver stays energised.
         * Clamped to the same range the beephz/beepms settings allow. */
        if (hz < 100) hz = 100;
        if (hz > 10000) hz = 10000;
        if (ms < 0) ms = 0;
        if (ms > 2000) ms = 2000;
        beep((uint32_t)hz, (uint32_t)ms);
        ok();
        return;
    }

    err("unknown command, try HELP");
}

void command_poll(void)
{
    char line[80];
    if (uart_getline(line, sizeof(line))) command_execute(line);
}
