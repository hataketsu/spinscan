/* Line protocol on USART1. See command.c for the command list. */
#ifndef COMMAND_H
#define COMMAND_H

/* Runs one already-terminated command line. Modifies the buffer in place. */
void command_execute(char *line);
/* Drains the UART and runs whatever complete lines arrived. Never blocks. */
void command_poll(void);

#endif /* COMMAND_H */
