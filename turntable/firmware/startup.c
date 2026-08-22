/* Reset vector, vector table and C runtime bring-up for STM32F103RCT6.
 *
 * No CMSIS, no HAL, no libc startup. The whole firmware is one job -- step a
 * motor on a timer -- and pulling in a vendor framework for that would be more
 * code than the job itself. */
#include <stdint.h>

extern int main(void);

/* Filled in by the linker script. */
extern uint32_t _sidata, _sdata, _edata, _sbss, _ebss, _estack;
extern uint32_t _siramfunc, _sramfunc, _eramfunc, _vector_base;

void SysTick_Handler(void);
void USART1_IRQHandler(void);

static void default_handler(void)
{
    /* Nothing here can be reported anywhere, so stop rather than run on with a
     * fault that would silently keep the motor energised. */
    for (;;) {}
}

void Reset_Handler(void)
{
    /* The loader jumped here, so the vector table is wherever this slot was
     * linked -- tell the core before the first interrupt arrives. */
    *(volatile uint32_t *)0xE000ED08u = (uint32_t)&_vector_base;

    uint32_t *src = &_sidata;
    for (uint32_t *dst = &_sdata; dst < &_edata;) *dst++ = *src++;
    src = &_siramfunc;
    for (uint32_t *dst = &_sramfunc; dst < &_eramfunc;) *dst++ = *src++;
    for (uint32_t *dst = &_sbss; dst < &_ebss;) *dst++ = 0;
    main();
    for (;;) {}
}

/* Only the entries this firmware can actually take are named; the rest trap.
 * 16 core exceptions followed by the 60 STM32F1 interrupts. */
__attribute__((section(".isr_vector"), used))
void (*const vector_table[])(void) = {
    (void (*)(void)) &_estack,   /* 0  initial stack pointer  */
    Reset_Handler,               /* 1  reset                  */
    default_handler,             /* 2  NMI                    */
    default_handler,             /* 3  HardFault              */
    default_handler,             /* 4  MemManage              */
    default_handler,             /* 5  BusFault               */
    default_handler,             /* 6  UsageFault             */
    0, 0, 0, 0,                  /* 7-10  reserved            */
    default_handler,             /* 11 SVCall                 */
    default_handler,             /* 12 Debug monitor          */
    0,                           /* 13 reserved               */
    default_handler,             /* 14 PendSV                 */
    SysTick_Handler,             /* 15 SysTick                */
    /* Ranges split around the one interrupt this firmware uses: overriding a
     * designated range initialiser is a warning, so do not create one. */
    [16 ... 52] = default_handler,
    [53]        = USART1_IRQHandler,   /* IRQ 37, USART1 */
    [54 ... 75] = default_handler,
};
