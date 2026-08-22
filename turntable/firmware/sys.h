/* Clock bring-up and the two time bases everything else uses. */
#ifndef SYS_H
#define SYS_H

#include <stdint.h>

#define SYSCLK_HZ 72000000u

void     sys_clock_init(void);
uint32_t millis(void);
void     delay_us(uint32_t us);
/* Blocking; only used where there is nothing else to do (power-on settle). */
void     delay_ms(uint32_t ms);

/* Hands control to the STM32's built-in serial bootloader in system memory.
 * Never returns. This is what makes firmware updates possible over the same
 * USB cable, with no BOOT0 jumper and no programmer. */
void     sys_enter_bootloader(void);

#endif /* SYS_H */
