# pico2flasher

A universal firmware flasher for the Raspberry Pi Pico 2 (RP2350). It needs
only the Python standard library and runs on Linux, macOS and Windows.

- **Any firmware format:** `.uf2`, `.elf`, Intel `.hex` and raw `.bin`. The
  tool converts files to UF2 as needed, with the right RP2350 family ID. For
  ELF files it picks Arm or RISC-V from the file itself.
- **Any flashing route:**
  1. Copy to the `RP2350` BOOTSEL drive, if one is mounted.
  2. Otherwise, send a 1200-baud "touch" to a running pico-sdk app with USB
     stdio. This reboots it into BOOTSEL, then the tool waits for the drive.
  3. Otherwise, use `picotool` if it is installed.
- **Catches common mistakes:**
  - It refuses RP2040 (Pico 1) UF2 files, which a Pico 2 silently ignores.
  - It ignores Pico 1 BOOTSEL drives.
  - It warns when an image has no `IMAGE_DEF` block, because the RP2350
    bootrom will not boot it.
- **CLI and GUI:** run with no arguments to open a small Tk window.

## Usage

```
python3 -m pico2flasher                     # GUI
python3 -m pico2flasher flash blink.elf     # flash (auto method)
python3 -m pico2flasher flash app.bin --base 0x10000000 --family rp2350-riscv
python3 -m pico2flasher flash app.uf2 --method picotool
python3 -m pico2flasher convert app.hex -o app.uf2
python3 -m pico2flasher info app.uf2
python3 -m pico2flasher devices
```

You can also install it with `pip install ./pico2-flasher` (add `[serial]`
for pyserial). This provides a `pico2flasher` command.

### Notes

- `--family` accepts `auto`, `rp2350-arm-s`, `rp2350-riscv`, `rp2350-arm-ns`,
  `absolute`, `data`, `rp2040`, or a number. `auto` uses the ELF machine type
  or the UF2's own family, and falls back to `rp2350-arm-s`.
- A `.bin` file loads at `0x10000000` (the start of flash) by default. SRAM
  addresses (`0x20000000`–`0x20082000`) also work, for RAM-only images.
- The BOOTSEL reset needs pyserial on Windows. On Linux and macOS, the tool
  falls back to `termios`.
- On Linux, the BOOTSEL drive must be mounted. Most desktops mount it
  automatically under `/media/$USER/RP2350`.

## Tests

```
python3 -m unittest discover -s tests
```
