/* The table itself: driver pins, step pulses, and the index-and-hold loop. */
#ifndef MOTION_H
#define MOTION_H

#include <stdint.h>

void motion_init(void);

/* Re-read cfg after a SET changed something the hardware holds. */
void motion_apply_current(void);
void motion_apply_dir(void);

void motion_enable(int on);
int  motion_enabled(void);

/* Start / stop the timed sequence. sync restarts the current still window
 * without moving, which is how a camera re-aligns itself to the table. */
void motion_run(void);
void motion_stop(void);
void motion_sync(void);
int  motion_running(void);

/* Advance one index right now, wherever the timer was. */
void motion_index(void);
void motion_jog_steps(int32_t steps);
void motion_goto_shot(uint32_t shot);
void motion_zero(void);

uint32_t motion_shot(void);
int32_t  motion_position(void);
/* Milliseconds until the next index, or 0 when not running. */
uint32_t motion_next_in(void);

void motion_poll(void);

void beep(uint32_t hz, uint32_t ms);

#endif /* MOTION_H */
