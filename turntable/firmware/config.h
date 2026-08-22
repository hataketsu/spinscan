/* Everything you would want to change lives here.
 *
 * Rebuild after editing: `make` in this directory. */
#ifndef CONFIG_H
#define CONFIG_H

/* ---- motion ------------------------------------------------------------- */

/* Full steps of the motor itself. NEMA17 1.8 deg/step -> 200. */
#define MOTOR_FULL_STEPS 200

/* Microstepping is set by the jumpers under the driver on this board, not by
 * software. Whatever is jumpered has to be repeated here. */
#define MICROSTEPS 16

/* Reduction between motor shaft and table, as numerator:denominator.
 * Table straight on the shaft -> 1 : 1. A 60T table pulley on a 20T motor
 * pulley -> GEAR_NUM 3, GEAR_DEN 1. */
#define GEAR_NUM 1
#define GEAR_DEN 1

/* Indexes per full revolution of the TABLE. 120 shots -> 3 degrees each. */
#define SHOTS_PER_REV 120

/* Still window between indexes, milliseconds. Match the camera's interval. */
#define INTERVAL_MS 3000

/* 1 or -1. Only changes which way the table turns. */
#define DIRECTION 1

/* ---- index speed -------------------------------------------------------- */

/* Microseconds between step pulses at the start and end of an index (slow) and
 * at cruise (fast), plus how many steps the ramp is spread over.
 *
 * These are deliberately gentle. The index is a few tens of milliseconds either
 * way; what costs a capture is the object rocking afterwards, and a soft ramp
 * settles far sooner than a fast one. */
#define STEP_DELAY_START_US 3000
#define STEP_DELAY_MIN_US   1200
#define RAMP_STEPS          12

/* ---- feedback ----------------------------------------------------------- */

/* Short beep at the start of every still window: that is the moment to shoot.
 *
 * Off by default: the buzzer lives on the LCD's EXP1 header, not on the board
 * itself, so a bare ET4000+ has nothing to make a sound with and PD2 just
 * toggles into thin air. Turn it on with `SET beep 1` if a panel is attached. */
#define BEEP_ENABLE 0
#define BEEP_HZ     2200
#define BEEP_MS     40

/* ---- driver current ----------------------------------------------------- */

/* Duty on the X/Y Vref rail (PB0), 0.0 to 1.0. The stock printer config runs
 * 0.40; a turntable carrying a small object holds fine on 0.30 and the motor
 * stays cool, which matters over a six-minute run. */
#define CURRENT_DUTY 0.30f

/* Shown by the app and written into the update manifest. */
#define FW_VERSION "1.0"

#endif /* CONFIG_H */
