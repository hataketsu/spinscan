/* Self-update over the serial port, into the inactive A/B slot. */
#ifndef UPDATE_H
#define UPDATE_H

#include <stdint.h>

/* Largest image accepted, bounded by SRAM rather than by the slot: the whole
 * image is buffered and checksummed before a single page is erased. */
#define UPDATE_MAX (36u * 1024u)

uint32_t update_running_slot(void);
uint32_t update_target_slot(void);

/* Receives `size` bytes, checks them against `crc`, writes them to the inactive
 * slot, marks that slot live, and resets. Returns only on failure. */
void update_receive(uint32_t size, uint32_t crc);

#endif /* UPDATE_H */
