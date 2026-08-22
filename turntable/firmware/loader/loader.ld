/* The loader owns the reset vector and the first 8 KiB. */
MEMORY
{
  FLASH (rx)  : ORIGIN = 0x08000000, LENGTH = 8K
  RAM   (rwx) : ORIGIN = 0x20000000, LENGTH = 48K
}

_estack = ORIGIN(RAM) + LENGTH(RAM);

ENTRY(Reset_Handler)

SECTIONS
{
  .isr_vector : { KEEP(*(.isr_vector)) } > FLASH
  .text : { *(.text*) *(.rodata*) . = ALIGN(4); } > FLASH
  /DISCARD/ : { *(.ARM.exidx*) *(.note*) *(.comment) *(.data*) *(.bss*) }
}
