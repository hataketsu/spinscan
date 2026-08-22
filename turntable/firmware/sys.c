#include "sys.h"

#define RCC_BASE   0x40021000u
#define RCC_CR     (*(volatile uint32_t *)(RCC_BASE + 0x00))
#define RCC_CFGR   (*(volatile uint32_t *)(RCC_BASE + 0x04))
#define FLASH_ACR  (*(volatile uint32_t *)0x40022000u)

#define SYST_CSR   (*(volatile uint32_t *)0xE000E010u)
#define SYST_RVR   (*(volatile uint32_t *)0xE000E014u)
#define SYST_CVR   (*(volatile uint32_t *)0xE000E018u)

#define RCC_APB1RSTR (*(volatile uint32_t *)(RCC_BASE + 0x10))
#define RCC_APB2RSTR (*(volatile uint32_t *)(RCC_BASE + 0x0C))
#define SCB_VTOR   (*(volatile uint32_t *)0xE000ED08u)
#define NVIC_ICER  ((volatile uint32_t *)0xE000E180u)
#define NVIC_ICPR  ((volatile uint32_t *)0xE000E280u)

/* Where the factory bootloader lives on an F103. */
#define SYSTEM_MEMORY 0x1FFFF000u

#define DEMCR      (*(volatile uint32_t *)0xE000EDFCu)
#define DWT_CTRL   (*(volatile uint32_t *)0xE0001000u)
#define DWT_CYCCNT (*(volatile uint32_t *)0xE0001004u)

static volatile uint32_t ms_ticks;

void SysTick_Handler(void) { ms_ticks++; }

uint32_t millis(void) { return ms_ticks; }

void sys_clock_init(void)
{
    /* Two wait states are required above 48 MHz, and the prefetch buffer keeps
     * the core from stalling on them. Both must be set BEFORE the switch. */
    FLASH_ACR = (1u << 4) | 2u;

    RCC_CR |= 1u << 16;                        /* HSEON  */
    while (!(RCC_CR & (1u << 17))) {}          /* HSERDY */

    /* PLL = HSE 8 MHz x 9 = 72 MHz. AHB /1, APB2 /1, APB1 /2 (36 MHz ceiling). */
    RCC_CFGR = (0u << 4) | (4u << 8) | (0u << 11) | (1u << 16) | (7u << 18);

    RCC_CR |= 1u << 24;                        /* PLLON  */
    while (!(RCC_CR & (1u << 25))) {}          /* PLLRDY */

    RCC_CFGR |= 2u;                            /* SW = PLL */
    while (((RCC_CFGR >> 2) & 3u) != 2u) {}

    /* Cycle counter drives the microsecond step pulses; SysTick's millisecond
     * granularity is orders of magnitude too coarse for those. */
    DEMCR |= 1u << 24;
    DWT_CYCCNT = 0;
    DWT_CTRL |= 1u;

    SYST_RVR = SYSCLK_HZ / 1000u - 1u;
    SYST_CVR = 0;
    SYST_CSR = 7u;
}

void delay_us(uint32_t us)
{
    uint32_t start = DWT_CYCCNT;
    uint32_t cycles = us * (SYSCLK_HZ / 1000000u);
    /* Unsigned wrap makes the counter rollover a non-event. */
    while ((DWT_CYCCNT - start) < cycles) {}
}

void delay_ms(uint32_t ms)
{
    uint32_t start = ms_ticks;
    while ((millis() - start) < ms) { __asm__ volatile("wfi"); }
}

void sys_enter_bootloader(void)
{
    __asm__ volatile("cpsid i");

    /* The bootloader starts from a cold-boot assumption: no timers running, no
     * interrupts pending, peripherals at reset values, and the core on the HSI
     * with zero flash wait states. Hand it that, or its own USART setup lands
     * on top of ours and the baud detection fails. */
    SYST_CSR = 0;
    SYST_RVR = 0;
    SYST_CVR = 0;
    for (int i = 0; i < 3; i++) {
        NVIC_ICER[i] = 0xFFFFFFFFu;
        NVIC_ICPR[i] = 0xFFFFFFFFu;
    }

    RCC_APB2RSTR = 0xFFFFFFFFu; RCC_APB2RSTR = 0;
    RCC_APB1RSTR = 0xFFFFFFFFu; RCC_APB1RSTR = 0;

    RCC_CFGR &= ~3u;                              /* SYSCLK back to HSI */
    while (((RCC_CFGR >> 2) & 3u) != 0u) {}
    RCC_CR &= ~(1u << 24);                        /* PLL off  */
    RCC_CFGR = 0;
    FLASH_ACR = 0;                                /* 0 wait states at 8 MHz */

    uint32_t sp  = *(volatile uint32_t *)(SYSTEM_MEMORY);
    uint32_t pc  = *(volatile uint32_t *)(SYSTEM_MEMORY + 4u);
    SCB_VTOR = SYSTEM_MEMORY;

    __asm__ volatile("msr msp, %0" :: "r"(sp));
    __asm__ volatile("cpsie i");
    ((void (*)(void))pc)();

    for (;;) {}                                   /* unreachable */
}
