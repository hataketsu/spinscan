/* Photogrammetry turntable on an EasyThreeD ET4000+ (MKS Robin Lite compatible,
 * STM32F103RCT6). Flashed over SWD; no bootloader in front of it.
 *
 * The board does one thing: hold the table still for a fixed window while the
 * camera fires, then index it by one shot's worth of angle. Everything about
 * that -- how many shots make a revolution, how long the window is, how hard
 * the ramp is -- is settable over the serial port and can be saved to flash.
 *
 * Why bare registers rather than a HAL: the only property that matters to the
 * reconstruction is that the still window really is still, and really is the
 * same length every time. That is easier to guarantee in a few hundred lines
 * than underneath a framework.
 *
 * Pins, from the board's Klipper definition:
 *   PC6  X step        PB12 X direction     PB10 X/Y/Z enable (active low)
 *   PB0  X/Y Vref PWM  PD2  buzzer          PA9/PA10 USART1 -> CH340 -> USB
 */
#include "command.h"
#include "motion.h"
#include "settings.h"
#include "sys.h"
#include "uart.h"

#define BAUD 115200u

int main(void)
{
    sys_clock_init();

    settings_defaults();
    int restored = settings_load();

    uart_init(BAUD);
    motion_init();

    /* Hold the table from the start: an unpowered stepper lets whatever is
     * bolted to it settle somewhere else before the first frame. */
    motion_enable(1);
    delay_ms(200);              /* let Vref settle before the first move */

    uart_puts("\nturntable ready\n");
    uart_kv("saved_settings", restored);
    uart_puts("type HELP for commands\n");
    beep(1500, 150);

    /* RUN is not automatic. The table waking up and turning by itself the
     * moment someone plugs it in is exactly what you do not want while the
     * object is still being placed. */
    for (;;) {
        command_poll();
        motion_poll();
        /* No wfi here: motion_poll's deadline is checked against a millisecond
         * counter, and the loop is idle enough that spinning costs nothing but
         * a little power. */
    }
}
