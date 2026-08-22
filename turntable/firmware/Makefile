# Bare-metal turntable firmware for the ET4000+ (STM32F103RCT6).
# Flashed over SWD with an ST-Link V2 -- no bootloader involved.

# Two application images, identical code linked at two different flash
# addresses, plus the loader that chooses between them. An update writes the
# slot that is not running, so there is never a moment where the board has no
# working firmware.
TARGET  := turntable
SRC     := main.c startup.c sys.c uart.c motion.c settings.c command.c update.c
SLOT_A  := 0x08002000
SLOT_B  := 0x08010000
SLOT_SZ := 56K

CROSS   := arm-none-eabi-
CC      := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
SIZE    := $(CROSS)size

CFLAGS  := -mcpu=cortex-m3 -mthumb -std=c11 -Os -g3 \
           -Wall -Wextra -Werror -Wno-error=unused-parameter \
           -ffunction-sections -fdata-sections \
           -fno-common -fno-builtin -ffreestanding
# -nostdlib drops libc and the C runtime, but libgcc still has to come along:
# the Cortex-M3 has no 64-bit divide instruction, and the shot-position maths
# uses one to stay exact for large shot counts.
LDFLAGS := -T$(LDSCRIPT) -nostdlib -Wl,--gc-sections -Wl,-Map=$(TARGET).map
LDLIBS  := -lgcc

all: combined.bin manifest

# --- loader ---------------------------------------------------------------

loader.elf: loader/loader.c loader/loader.ld layout.h
	$(CC) $(CFLAGS) loader/loader.c -Tloader/loader.ld -nostdlib \
	  -Wl,--gc-sections -o $@ $(LDLIBS)
	$(SIZE) $@

loader.bin: loader.elf
	$(OBJCOPY) -O binary $< $@

# --- application, once per slot -------------------------------------------

define SLOT_RULES
slot-$(1).ld: app.ld.in
	sed -e 's|@SLOT_BASE@|$(2)|' -e 's|@SLOT_SIZE@|$$(SLOT_SZ)|' $$< > $$@

$(TARGET)-$(1).elf: $$(SRC) slot-$(1).ld config.h layout.h
	$$(CC) $$(CFLAGS) $$(SRC) -Tslot-$(1).ld -nostdlib -Wl,--gc-sections \
	  -Wl,-Map=$(TARGET)-$(1).map -o $$@ $$(LDLIBS)
	$$(SIZE) $$@

$(TARGET)-$(1).bin: $(TARGET)-$(1).elf
	$$(OBJCOPY) -O binary $$< $$@
endef

$(eval $(call SLOT_RULES,a,$(SLOT_A)))
$(eval $(call SLOT_RULES,b,$(SLOT_B)))

# --- one image for the initial SWD flash ----------------------------------
# Loader at 0x08000000 padded out to 8 KiB, then the slot A image. Slot B and
# the metadata page are left erased; the loader falls back to running slot A
# when there is no metadata yet, which is exactly the fresh-board case.

combined.bin: loader.bin $(TARGET)-a.bin
	$(OBJCOPY) -I binary -O binary --pad-to 8192 --gap-fill 0xFF loader.bin loader-pad.bin
	cat loader-pad.bin $(TARGET)-a.bin > $@
	@echo "combined.bin = loader + slot A, flash at 0x08000000"

manifest: $(TARGET)-a.bin $(TARGET)-b.bin
	@python3 mkmanifest.py $(TARGET)-a.bin $(TARGET)-b.bin

# --- flashing -------------------------------------------------------------
# The chip ships from the printer vendor with read protection and write
# protection set on some boards. `unlock` clears it; the chip mass-erases
# itself as a side effect, which is the point.

flash: combined.bin
	st-flash --reset write combined.bin 0x08000000

unlock:
	st-flash --connect-under-reset --reset erase || true
	st-flash --debug --connect-under-reset --reset write /dev/null 0x08000000 || true

flash-openocd: combined.bin
	openocd -f interface/stlink.cfg -f target/stm32f1x.cfg \
	  -c "program combined.bin 0x08000000 verify reset exit"

erase-openocd:
	openocd -f interface/stlink.cfg -f target/stm32f1x.cfg \
	  -c "init; reset halt; stm32f1x unlock 0; reset halt; flash erase_sector 0 0 last; exit"

clean:
	rm -f *.o *.elf *.bin *.map slot-*.ld

.PHONY: all manifest flash unlock flash-openocd erase-openocd clean
