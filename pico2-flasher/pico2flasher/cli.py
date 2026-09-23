"""Command-line interface. Run with no arguments to open the GUI."""

import argparse
import sys

from . import __version__, device, formats, uf2


def _int(s):
    return int(s, 0)


def _add_image_args(p):
    p.add_argument("file", help="firmware (.uf2, .elf, .hex or .bin)")
    p.add_argument("--format", choices=("uf2", "elf", "hex", "bin"),
                   help="override format detection")
    p.add_argument("--family", default="auto",
                   help="UF2 family: auto, %s, or a number" % ", ".join(uf2.FAMILIES))
    p.add_argument("--base", type=_int, default=formats.FLASH_BASE,
                   help="load address for .bin files (default 0x%08x)" % formats.FLASH_BASE)


def _load(args, log):
    img = formats.load(args.file, fmt=args.format, base=args.base, family=args.family)
    for w in img.warnings:
        log("warning: " + w)
    return img


def describe(img):
    lines = [
        "format:   %s" % img.format,
        "family:   %s" % (uf2.family_name(img.family_id) if img.family_id else "mixed"),
        "size:     %d bytes in %d segment(s)" % (img.size, len(img.segments)),
    ]
    if img.segments:
        lines.append("range:    0x%08x-0x%08x (%s)"
                     % (img.start, img.end, formats.region(img.start)))
    for a, d in img.segments[:16]:
        lines.append("  0x%08x  %8d bytes" % (a, len(d)))
    if len(img.segments) > 16:
        lines.append("  ... %d more" % (len(img.segments) - 16))
    fams = getattr(img, "uf2_families", None)
    if fams and len(fams) > 1:
        lines.append("uf2 families: " + ", ".join(sorted(
            uf2.family_name(f) if f is not None else "none" for f in fams)))
    return "\n".join(lines)


def cmd_info(args, log):
    img = _load(args, log)
    log(describe(img))


def cmd_convert(args, log):
    img = _load(args, log)
    out = args.output or args.file.rsplit(".", 1)[0] + ".uf2"
    if out == args.file:
        raise formats.FirmwareError("refusing to overwrite the input file")
    data = img.to_uf2()
    with open(out, "wb") as f:
        f.write(data)
    log("wrote %s (%d blocks, family %s)"
        % (out, len(data) // uf2.BLOCK_SIZE, uf2.family_name(img.family_id)))


def cmd_flash(args, log):
    img = _load(args, log)
    log(describe(img))
    data = img.to_uf2()
    how = device.flash(data, method=args.method, reset=not args.no_reset,
                       wait=args.wait, log=log)
    log("done (via %s)" % how)


def cmd_devices(args, log):
    drives = device.find_drives(all_boards=True)
    ports = device.find_serial_ports()
    if not drives and not ports:
        log("no boards found")
    for d in drives:
        tag = "Pico 2 / RP2350" if d.is_rp2350 else "not RP2350"
        log("BOOTSEL drive: %s  [%s] %s" % (d.path, tag, d.board_id))
    for p in ports:
        log("running board: %s (can be reset to BOOTSEL)" % p)
    log("picotool: %s" % (device.picotool_path() or "not installed"))


def cmd_gui(args, log):
    from . import gui
    gui.run(getattr(args, "file", None))


def build_parser():
    ap = argparse.ArgumentParser(prog="pico2flasher",
                                 description="Universal firmware flasher for the Raspberry Pi Pico 2.")
    ap.add_argument("--version", action="version", version=__version__)
    sub = ap.add_subparsers(dest="cmd")

    p = sub.add_parser("flash", help="flash firmware to a Pico 2")
    _add_image_args(p)
    p.add_argument("--method", choices=device.METHODS, default="auto")
    p.add_argument("--no-reset", action="store_true",
                   help="don't try to reboot a running board into BOOTSEL")
    p.add_argument("--wait", type=float, default=10.0,
                   help="seconds to wait for the BOOTSEL drive after a reset")
    p.set_defaults(func=cmd_flash)

    p = sub.add_parser("convert", help="convert firmware to a UF2 file")
    _add_image_args(p)
    p.add_argument("-o", "--output")
    p.set_defaults(func=cmd_convert)

    p = sub.add_parser("info", help="inspect a firmware file")
    _add_image_args(p)
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("devices", help="list connected boards")
    p.set_defaults(func=cmd_devices)

    p = sub.add_parser("gui", help="open the graphical flasher")
    p.add_argument("file", nargs="?")
    p.set_defaults(func=cmd_gui)
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.cmd is None:
        args.func = cmd_gui
    try:
        args.func(args, print)
    except (formats.FirmwareError, device.DeviceError, OSError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0
