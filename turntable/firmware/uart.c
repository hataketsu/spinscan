#include "uart.h"

#define RCC_BASE    0x40021000u
#define RCC_APB2ENR (*(volatile uint32_t *)(RCC_BASE + 0x18))

#define GPIOA_BASE  0x40010800u
#define GPIOA_CRH   (*(volatile uint32_t *)(GPIOA_BASE + 0x04))

#define USART1_BASE 0x40013800u
#define USART1_SR   (*(volatile uint32_t *)(USART1_BASE + 0x00))
#define USART1_DR   (*(volatile uint32_t *)(USART1_BASE + 0x04))
#define USART1_BRR  (*(volatile uint32_t *)(USART1_BASE + 0x08))
#define USART1_CR1  (*(volatile uint32_t *)(USART1_BASE + 0x0C))

#define SR_RXNE (1u << 5)
#define SR_TXE  (1u << 7)

#define NVIC_ISER1 (*(volatile uint32_t *)0xE000E104u)   /* IRQ 32..63 */
#define USART1_IRQ 37u

#define PCLK2_HZ 72000000u

/* Interrupt-filled so a command can arrive while the motor is mid-index. The
 * ring only has to outlive one blocking move, which is tens of milliseconds --
 * 128 bytes is many lines' worth at 115200 baud. */
#define RX_SIZE 128
static volatile uint8_t rx_buf[RX_SIZE];
static volatile uint32_t rx_head, rx_tail;

/* Partially received line, assembled as bytes are drained from the ring. */
#define LINE_MAX 80
static char line[LINE_MAX];
static uint32_t line_len;
static uint8_t line_overflow;

void USART1_IRQHandler(void)
{
    if (USART1_SR & SR_RXNE) {
        uint8_t c = (uint8_t)USART1_DR;
        uint32_t next = (rx_head + 1u) % RX_SIZE;
        /* Drop rather than overwrite: losing the newest byte corrupts one
         * command, overwriting the tail corrupts two. */
        if (next != rx_tail) {
            rx_buf[rx_head] = c;
            rx_head = next;
        }
    }
}

void uart_init(uint32_t baud)
{
    RCC_APB2ENR |= (1u << 2)     /* GPIOA  */
                 | (1u << 14);   /* USART1 */

    /* PA9 TX: alternate function push-pull 50 MHz. PA10 RX: floating input. */
    GPIOA_CRH = (GPIOA_CRH & ~0x00000FF0u) | 0x000004B0u;

    USART1_BRR = PCLK2_HZ / baud;
    USART1_CR1 = (1u << 13)      /* UE     */
               | (1u << 5)       /* RXNEIE */
               | (1u << 3)       /* TE     */
               | (1u << 2);      /* RE     */

    NVIC_ISER1 = 1u << (USART1_IRQ - 32u);
}

void uart_putc(char c)
{
    while (!(USART1_SR & SR_TXE)) {}
    USART1_DR = (uint8_t)c;
}

void uart_puts(const char *s)
{
    while (*s) {
        if (*s == '\n') uart_putc('\r');
        uart_putc(*s++);
    }
}

void uart_putdec(int32_t v)
{
    char tmp[12];
    uint32_t n = 0;
    uint32_t u;
    /* Via unsigned: -INT32_MIN does not fit in an int32_t, and the signed
     * remainder of a negative value would print punctuation, not digits. */
    if (v < 0) {
        uart_putc('-');
        u = 0u - (uint32_t)v;
    } else {
        u = (uint32_t)v;
    }
    do {
        tmp[n++] = (char)('0' + (u % 10u));
        u /= 10u;
    } while (u && n < sizeof(tmp));
    while (n) uart_putc(tmp[--n]);
}

void uart_kv(const char *key, int32_t value)
{
    uart_puts(key);
    uart_putc('=');
    uart_putdec(value);
    uart_puts("\n");
}

int uart_getbyte(void)
{
    if (rx_tail == rx_head) return -1;
    uint8_t c = rx_buf[rx_tail];
    rx_tail = (rx_tail + 1u) % RX_SIZE;
    return (int)c;
}

int uart_getline(char *buf, uint32_t size)
{
    while (rx_tail != rx_head) {
        char c = (char)rx_buf[rx_tail];
        rx_tail = (rx_tail + 1u) % RX_SIZE;

        if (c == '\n' || c == '\r') {
            if (line_len == 0 && !line_overflow) continue;   /* blank line */
            uint32_t n = line_len;
            uint8_t bad = line_overflow;
            line_len = 0;
            line_overflow = 0;
            if (bad) {
                uart_puts("err line too long\n");
                continue;
            }
            if (n >= size) n = size - 1u;
            for (uint32_t i = 0; i < n; i++) buf[i] = line[i];
            buf[n] = '\0';
            return 1;
        }
        if (line_len < LINE_MAX) {
            line[line_len++] = c;
        } else {
            /* Remember the overrun and report it when the line ends, rather
             * than silently acting on a truncated command. */
            line_overflow = 1;
        }
    }
    return 0;
}
