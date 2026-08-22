/* USART1 on PA9/PA10 -- the pins that go to the board's CH340 USB-serial chip,
 * so a host (or an Android phone over OTG) sees a plain COM port. */
#ifndef UART_H
#define UART_H

#include <stdint.h>

void uart_init(uint32_t baud);

void uart_putc(char c);
void uart_puts(const char *s);
void uart_putdec(int32_t v);
/* "name=value\n", the shape every status line takes. */
void uart_kv(const char *key, int32_t value);

/* Copies one received line (without its terminator) into buf and returns 1.
 * Returns 0 when no complete line has arrived yet. Never blocks. */
int uart_getline(char *buf, uint32_t size);

/* One raw byte, or -1 if nothing has arrived. Bypasses the line assembler --
 * a firmware image is binary and contains plenty of 0x0A. */
int uart_getbyte(void);

#endif /* UART_H */
