import os
import struct
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pico2flasher import cli, device, formats, uf2  # noqa: E402

# Minimal picobin IMAGE_DEF-ish block so the bootability check is satisfied.
IMAGE_DEF = struct.pack("<I", formats.PICOBIN_BLOCK_MARKER_START) + bytes(12) + \
    struct.pack("<I", formats.PICOBIN_BLOCK_MARKER_END)


def make_elf(segments, machine=formats.EM_ARM):
    """Build a 32-bit LE ELF with PT_LOAD segments [(paddr, data)]."""
    phoff = 52
    data_off = phoff + 32 * len(segments)
    phdrs, blob = b"", b""
    for paddr, data in segments:
        phdrs += struct.pack("<8I", 1, data_off + len(blob), paddr, paddr,
                             len(data), len(data), 5, 4)
        blob += data
    ident = b"\x7fELF\x01\x01\x01" + bytes(9)
    hdr = ident + struct.pack("<HHIIIIIHHHHHH", 2, machine, 1, segments[0][0],
                              phoff, 0, 0, 52, 32, len(segments), 40, 0, 0)
    return hdr + phdrs + blob


def hex_record(addr, rtype, payload):
    rec = bytes([len(payload), addr >> 8, addr & 0xFF, rtype]) + payload
    return ":" + (rec + bytes([-sum(rec) & 0xFF])).hex().upper()


class TmpFiles(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def write(self, name, data):
        path = os.path.join(self.dir.name, name)
        with open(path, "wb" if isinstance(data, bytes) else "w") as f:
            f.write(data)
        return path


class TestUF2(unittest.TestCase):
    def test_roundtrip(self):
        pages = {0x10000000: bytes(range(256)), 0x10000100: b"\xaa" * 256}
        data = uf2.encode(pages, uf2.FAMILIES["rp2350-arm-s"])
        self.assertEqual(len(data), 2 * 512)
        blocks = uf2.decode(data)
        self.assertEqual([b.addr for b in blocks], sorted(pages))
        self.assertEqual(blocks[0].data, pages[0x10000000])
        self.assertTrue(all(b.num_blocks == 2 for b in blocks))
        self.assertEqual(blocks[1].family_id, 0xE48BFF59)

    def test_bad_magic(self):
        with self.assertRaises(ValueError):
            uf2.decode(bytes(512))


class TestFormats(TmpFiles):
    def test_bin_pages_padded(self):
        path = self.write("fw.bin", IMAGE_DEF + b"\x01" * 300)
        img = formats.load(path)
        self.assertEqual(img.format, "bin")
        self.assertEqual(img.family_id, uf2.FAMILIES["rp2350-arm-s"])
        self.assertEqual(img.warnings, [])
        pages = img.pages()
        self.assertEqual(sorted(pages), [0x10000000, 0x10000100])
        self.assertEqual(pages[0x10000100][:320 - 256], b"\x01" * 64)
        self.assertEqual(pages[0x10000100][64:], bytes(192))

    def test_bin_missing_image_def_warns(self):
        img = formats.load(self.write("fw.bin", b"\x00" * 1024))
        self.assertTrue(any("IMAGE_DEF" in w for w in img.warnings))

    def test_bin_out_of_range(self):
        with self.assertRaises(formats.FirmwareError):
            formats.load(self.write("fw.bin", b"x"), base=0x30000000)

    def test_elf_arm_and_riscv(self):
        segs = [(0x10000000, IMAGE_DEF + b"code"), (0x10001000, b"data")]
        img = formats.load(self.write("a.elf", make_elf(segs)))
        self.assertEqual(img.family_id, uf2.FAMILIES["rp2350-arm-s"])
        self.assertEqual(img.segments, segs)
        img = formats.load(self.write("r.elf", make_elf(segs, formats.EM_RISCV)))
        self.assertEqual(img.family_id, uf2.FAMILIES["rp2350-riscv"])

    def test_elf_ram_only(self):
        img = formats.load(self.write("r.elf", make_elf([(0x20000000, b"ram")])))
        self.assertEqual(formats.region(img.start), "sram")
        self.assertEqual(img.warnings, [])

    def test_hex(self):
        payload = IMAGE_DEF + b"hi"
        lines = [hex_record(0, 0x04, b"\x10\x00"),
                 hex_record(0, 0x00, payload[:16]),
                 hex_record(16, 0x00, payload[16:]),
                 hex_record(0, 0x01, b"")]
        img = formats.load(self.write("fw.hex", "\n".join(lines) + "\n"))
        self.assertEqual(img.segments, [(0x10000000, payload)])

    def test_hex_bad_checksum(self):
        line = hex_record(0, 0x00, b"ab")[:-2] + "00"
        with self.assertRaises(formats.FirmwareError):
            formats.load(self.write("fw.hex", line))

    def test_rp2040_uf2_rejected(self):
        data = uf2.encode({0x10000000: bytes(256)}, uf2.FAMILIES["rp2040"])
        with self.assertRaises(formats.FirmwareError) as cm:
            formats.load(self.write("pico1.uf2", data))
        self.assertIn("rp2040", str(cm.exception))

    def test_uf2_passthrough(self):
        data = uf2.encode({0x10000000: IMAGE_DEF.ljust(256, b"\0")},
                          uf2.FAMILIES["rp2350-riscv"])
        img = formats.load(self.write("fw.uf2", data))
        self.assertEqual(img.to_uf2(), data)
        self.assertEqual(img.family_id, uf2.FAMILIES["rp2350-riscv"])

    def test_multi_family_uf2_accepted(self):
        data = uf2.encode({0x10000000: bytes(256)}, uf2.FAMILIES["rp2040"]) + \
            uf2.encode({0x10000000: IMAGE_DEF.ljust(256, b"\0")}, uf2.FAMILIES["rp2350-arm-s"])
        img = formats.load(self.write("both.uf2", data))
        self.assertEqual(img.family_id, uf2.FAMILIES["rp2350-arm-s"])
        self.assertEqual(img.warnings, [])

    def test_family_override(self):
        img = formats.load(self.write("fw.bin", IMAGE_DEF), family="rp2350-riscv")
        blocks = uf2.decode(img.to_uf2())
        self.assertEqual(blocks[0].family_id, uf2.FAMILIES["rp2350-riscv"])


class TestDevice(TmpFiles):
    def fake_drive(self, board="RP2350"):
        mount = os.path.join(self.dir.name, "RP2350")
        os.mkdir(mount)
        with open(os.path.join(mount, device.INFO_FILE), "w") as f:
            f.write("UF2 Bootloader v1.0\nModel: Raspberry Pi %s\nBoard-ID: %s\n" % (board, board))
        return mount

    def test_find_and_flash_drive(self):
        mount = self.fake_drive()
        data = uf2.encode({0x10000000: bytes(256)}, uf2.FAMILIES["rp2350-arm-s"])

        # Simulate the bootrom rebooting (drive disappears) after the write.
        def reboot():
            target = os.path.join(mount, "firmware.uf2")
            while not os.path.exists(target):
                time.sleep(0.05)
            os.unlink(os.path.join(mount, device.INFO_FILE))

        t = threading.Thread(target=reboot)
        t.start()
        with mock.patch.object(device, "_candidate_mounts", return_value=[mount]):
            logs = []
            self.assertEqual(device.flash(data, reset=False, log=logs.append), "drive")
        t.join()
        with open(os.path.join(mount, "firmware.uf2"), "rb") as f:
            self.assertEqual(f.read(), data)
        self.assertIn("board rebooted", logs)

    def test_ignores_rp2040_drive(self):
        mount = self.fake_drive("RPI-RP2")
        with mock.patch.object(device, "_candidate_mounts", return_value=[mount]), \
                mock.patch.object(device, "picotool_path", return_value=None):
            self.assertEqual(device.find_drives(), [])
            with self.assertRaises(device.DeviceError) as cm:
                device.flash(b"", reset=False, log=lambda m: None)
        self.assertIn("RP2040", str(cm.exception))


class TestCLI(TmpFiles):
    def test_convert(self):
        src = self.write("fw.bin", IMAGE_DEF + bytes(600))
        out = os.path.join(self.dir.name, "out.uf2")
        self.assertEqual(cli.main(["convert", src, "-o", out]), 0)
        with open(out, "rb") as f:
            self.assertEqual(len(uf2.decode(f.read())), 3)

    def test_info_error_exit(self):
        self.assertEqual(cli.main(["info", os.path.join(self.dir.name, "missing.bin")]), 1)


if __name__ == "__main__":
    unittest.main()
