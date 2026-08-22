/* Flash map. 256 KiB total, and the firmware is about 6 KiB, so there is room
 * for two full copies with plenty to spare.
 *
 *   0x08000000   8 KiB   loader        picks a slot and jumps; never rewritten
 *                                      over the air
 *   0x08002000  56 KiB   slot A        application
 *   0x08010000  56 KiB   slot B        application
 *   0x0801E000   2 KiB   metadata      which slot is live, and its checksum
 *   0x0803F800   2 KiB   settings
 *
 * An update always writes the slot that is NOT running, then flips one word of
 * metadata. Power cut half way through a write leaves the live slot untouched,
 * so the worst case is "the update did not take", never "the board is dead".
 */
#ifndef LAYOUT_H
#define LAYOUT_H

#include <stdint.h>

#define FLASH_PAGE_SIZE 2048u

#define LOADER_BASE   0x08000000u
#define LOADER_SIZE   (8u * 1024u)

#define SLOT_A_BASE   0x08002000u
#define SLOT_B_BASE   0x08010000u
#define SLOT_SIZE     (56u * 1024u)

#define META_BASE     0x0801E000u
#define SETTINGS_BASE (0x08000000u + 256u * 1024u - FLASH_PAGE_SIZE)

#define META_MAGIC    0x4D4E5254u   /* "TRNM" */

typedef struct {
    uint32_t magic;
    uint32_t active;      /* 0 = slot A, 1 = slot B */
    uint32_t size[2];     /* image length per slot, bytes */
    uint32_t crc[2];      /* crc32 over that length      */
    uint32_t seq;         /* bumped on every successful update */
    uint32_t self_crc;    /* over everything above       */
} meta_t;

static inline uint32_t slot_base(uint32_t slot)
{
    return slot ? SLOT_B_BASE : SLOT_A_BASE;
}

/* Standard CRC-32. Table-less: a few milliseconds over 36 KiB, against a
 * kilobyte of table that both the loader and the app would have to carry. */
static inline uint32_t crc32_of(const uint8_t *p, uint32_t n)
{
    uint32_t crc = 0xFFFFFFFFu;
    for (uint32_t i = 0; i < n; i++) {
        crc ^= p[i];
        for (int b = 0; b < 8; b++) {
            crc = (crc >> 1) ^ (0xEDB88320u & (uint32_t)(-(int32_t)(crc & 1u)));
        }
    }
    return ~crc;
}

static inline int meta_valid(const meta_t *m)
{
    if (m->magic != META_MAGIC) return 0;
    uint32_t want = crc32_of((const uint8_t *)m, sizeof(*m) - sizeof(m->self_crc));
    return want == m->self_crc;
}

/* A slot is runnable if its recorded image checksums out and its reset vector
 * puts the stack pointer somewhere in SRAM. */
static inline int slot_valid(const meta_t *m, uint32_t slot)
{
    if (slot > 1u) return 0;
    uint32_t size = m->size[slot];
    if (size < 256u || size > SLOT_SIZE) return 0;
    const uint8_t *img = (const uint8_t *)slot_base(slot);
    uint32_t sp = *(const uint32_t *)img;
    if ((sp & 0xFFFF0000u) != 0x20000000u) return 0;
    return crc32_of(img, size) == m->crc[slot];
}

#endif /* LAYOUT_H */
