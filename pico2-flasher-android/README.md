# Pico Flasher for Android

An easy app for putting programs on a **Raspberry Pi Pico 2** (and the original
Pico) straight from an Android phone, such as a Samsung Galaxy S25 Ultra. No
computer needed.

## Get the app

1. On your phone, open the repository's **Releases** page, then open
   **Pico Flasher for Android**.
2. Tap **PicoFlasher.apk** to download it.
3. Open the downloaded file and tap **Install**. If the phone says it can't
   install apps from there, tap **Settings** and turn on
   **Allow from this source**.

## What you need

- A cable from the phone to the Pico. A Pico 2 has a **micro-USB** socket, so
  use a **USB-C to micro-USB** cable, or a USB-C OTG adapter plus a normal
  micro-USB cable.
- A program file, usually one ending in **.uf2**.

## How to flash

1. **Pick a file**: tap 📂 and choose your program.
2. **Plug in your Pico**: hold the white **BOOTSEL** button while you plug it
   in, then let go. If the phone asks “Open Pico Flasher?”, tick **Always**
   and tap **OK**.
3. Tap **⚡ FLASH IT!** and wait for 🎉.

When **“Flash by itself”** is on, step 3 happens automatically each time you
plug in a Pico while holding BOOTSEL.

If the Pico is already running a program built with the Pico SDK (with USB
stdio), Arduino or MicroPython, you don't need to press BOOTSEL. The app asks
the Pico to restart into flashing mode by itself.

## How it works

- The app doesn't copy files onto the Pico's “RP2350” drive, because phones
  don't always mount it. Instead it talks to the Pico's bootrom over USB using
  **PICOBOOT**, the same protocol `picotool` uses. For each 4 KiB block it
  erases, writes, and reads back to check, then restarts the Pico.
- **File types:** `.uf2`, `.elf`, `.hex` and `.bin` (a `.bin` loads at
  `0x10000000`). A UF2 that contains both Pico 1 and Pico 2 builds works; the
  app picks the part that matches the board.
- **Safety checks:** the app refuses Pico 1 files on a Pico 2 (and the other
  way round), RAM-only programs, and non-Pico files. It asks before flashing a
  file that doesn't look bootable on a Pico 2.

## Building

GitHub Actions builds the APK on every push (see
`.github/workflows/android-apk.yml`). To build it locally, you need JDK 17 and
the Android SDK:

```
cd pico2-flasher-android
gradle testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It's signed
with the debug key in `app/debug.keystore`, which lets each new version
install over the previous one.
