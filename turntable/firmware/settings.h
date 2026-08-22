/* Runtime settings: the values config.h seeds at build time, but which UART can
 * change afterwards and flash can remember. */
#ifndef SETTINGS_H
#define SETTINGS_H

#include <stdint.h>

typedef struct {
    uint32_t magic;
    uint16_t shots;         /* indexes per table revolution        */
    uint16_t micro;         /* microstepping set by the jumpers    */
    uint16_t motor_steps;   /* full steps per motor revolution     */
    uint16_t gear_num;      /* table : motor reduction             */
    uint16_t gear_den;
    uint32_t interval_ms;   /* still window between indexes        */
    int16_t  dir;           /* +1 or -1                            */
    uint16_t current_pct;   /* driver Vref duty, 0..100            */
    uint16_t d_start_us;    /* step delay at both ends of the ramp */
    uint16_t d_min_us;      /* step delay at cruise                */
    uint16_t ramp_steps;
    uint16_t beep_hz;
    uint16_t beep_ms;
    uint16_t beep_on;
    uint16_t pad;           /* keeps the struct half-word aligned  */
    uint32_t crc;
} settings_t;

extern settings_t cfg;

void settings_defaults(void);
/* 1 if a valid saved set was found in flash and adopted, 0 if defaults stand. */
int  settings_load(void);
/* 1 on success. Erases and rewrites the last flash page. */
int  settings_save(void);

/* Microsteps for one full turn of the table, from the current settings. */
uint32_t settings_steps_per_rev(void);

#endif /* SETTINGS_H */
