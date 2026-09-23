"""Load firmware in any supported format into a flat, addressed Image."""

import os
import struct

from . import uf2

FLASH_BASE = 0x10000000
FLASH_END = 0x12000000      # two 16 MiB XIP windows (CS0 + CS1)
SRAM_BASE = 0x20000000
SRAM_END = 0x20082000       # 520 KiB

EM_ARM = 40
EM_RISCV = 243

PICOBIN_BLOCK_MARKER_START = 0xFFFFDED3
PICOBIN_BLOCK_MARKER_END = 0xAB123579


class FirmwareError(Exception):
    pass


class Image:
    """A set of (address, bytes) segments plus the UF2 family they target."""

    def __init__(self, fmt, segments, family_id=None, raw_uf2=None):
        self.format = fmt
        self.segments = sorted((a, bytes(d)) for a, d in segments if d)
        self.family_id = family_id
        # For UF2 input we keep the original file and send it untouched.
        self.raw_uf2 = raw_uf2
        self.warnings = []

    @property
    def size(self):
        return sum(len(d) for _, d in self.segments)

    @property
    def start(self):
        return self.segments[0][0] if self.segments else None

    @property
    def end(self):
        return max(a + len(d) for a, d in self.segments) if self.segments else None

    def pages(self):
        """Split into 256-byte aligned pages, zero-filling gaps inside a page."""
        pages = {}
        for addr, data in self.segments:
            off = 0
            while off < len(data):
                cur = addr + off
                page = cur & ~(uf2.PAYLOAD_SIZE - 1)
                n = min(len(data) - off, page + uf2.PAYLOAD_SIZE - cur)
                buf = pages.setdefault(page, bytearray(uf2.PAYLOAD_SIZE))
                buf[cur - page:cur - page + n] = data[off:off + n]
                off += n
        return {a: bytes(b) for a, b in pages.items()}

    def to_uf2(self, family_id=None):
        if self.raw_uf2 is not None and family_id is None:
            return self.raw_uf2
        fam = family_id or self.family_id
        if fam is None:
            raise FirmwareError("no UF2 family specified")
        return uf2.encode(self.pages(), fam)

    def read(self, addr, length):
        """Return bytes at addr (0xff where nothing is loaded)."""
        out = bytearray(b"\xff" * length)
        for a, d in self.segments:
            lo, hi = max(a, addr), min(a + len(d), addr + length)
            if lo < hi:
                out[lo - addr:hi - addr] = d[lo - a:hi - a]
        return bytes(out)


def region(addr):
    if FLASH_BASE <= addr < FLASH_END:
        return "flash"
    if SRAM_BASE <= addr < SRAM_END:
        return "sram"
    return None


# --- detection ---------------------------------------------------------------

def detect_format(path, data):
    if uf2.is_uf2(data):
        return "uf2"
    if data[:4] == b"\x7fELF":
        return "elf"
    ext = os.path.splitext(path)[1].lower()
    if ext in (".hex", ".ihex", ".ihx") or data[:1] == b":":
        try:
            data[:64].decode("ascii")
            return "hex"
        except UnicodeDecodeError:
            pass
    return "bin"


def load(path, fmt=None, base=FLASH_BASE, family=None):
    """Load a firmware file. `family` is a uf2.FAMILIES key or numeric ID."""
    with open(path, "rb") as f:
        data = f.read()
    if not data:
        raise FirmwareError("%s is empty" % path)
    fmt = fmt or detect_format(path, data)
    loader = {"uf2": load_uf2, "elf": load_elf, "hex": load_hex, "bin": load_bin}.get(fmt)
    if loader is None:
        raise FirmwareError("unknown format %r" % fmt)
    img = loader(data, base=base)

    fam = _parse_family(family)
    if fam is not None:
        if img.raw_uf2 is not None:
            img.raw_uf2 = None  # re-encode with the requested family
        img.family_id = fam
    elif img.family_id is None:
        img.family_id = uf2.FAMILIES["rp2350-arm-s"]

    _check(img)
    return img


def _parse_family(family):
    if family is None or family == "auto":
        return None
    if isinstance(family, int):
        return family
    if family in uf2.FAMILIES:
        return uf2.FAMILIES[family]
    try:
        return int(family, 0)
    except ValueError:
        raise FirmwareError("unknown family %r (choose from %s)"
                            % (family, ", ".join(uf2.FAMILIES)))


# --- loaders -----------------------------------------------------------------

def load_uf2(data, base=None):
    try:
        blocks = uf2.decode(data)
    except ValueError as e:
        raise FirmwareError(str(e))
    blocks_main = [b for b in blocks if not b.flags & uf2.FLAG_NOT_MAIN_FLASH]
    # Multi-target UF2s (e.g. RP2040 + RP2350) are fine; only the blocks the
    # RP2350 bootrom will act on matter for analysis.
    ours = [b for b in blocks_main if b.family_id in uf2.RP2350_FAMILIES] or blocks_main
    fams = {b.family_id for b in ours}
    segs = [(b.addr, b.data) for b in ours]
    fam = fams.pop() if len(fams) == 1 else None
    img = Image("uf2", segs, fam, raw_uf2=data)
    img.uf2_families = {b.family_id for b in blocks}
    return img


def load_elf(data, base=None):
    if len(data) < 52 or data[4] != 1 or data[5] != 1:
        raise FirmwareError("only 32-bit little-endian ELF files are supported")
    e_machine, = struct.unpack_from("<H", data, 18)
    e_phoff, = struct.unpack_from("<I", data, 28)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 42)

    flash, ram, other = [], [], []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if off + 32 > len(data):
            raise FirmwareError("truncated ELF program header table")
        p_type, p_offset, _vaddr, p_paddr, p_filesz = struct.unpack_from("<5I", data, off)
        if p_type != 1 or p_filesz == 0:  # PT_LOAD with file contents only
            continue
        if p_offset + p_filesz > len(data):
            raise FirmwareError("ELF segment %d extends past end of file" % i)
        seg = (p_paddr, data[p_offset:p_offset + p_filesz])
        {"flash": flash, "sram": ram}.get(region(p_paddr), other).append(seg)

    if other:
        raise FirmwareError("ELF has loadable data outside flash/SRAM (at 0x%08x); "
                            "is it built for RP2350?" % other[0][0])
    # A flash image also carries .data initialisers with an SRAM VMA but a
    # flash LMA, so p_paddr already lands them in flash. RAM-only segments in
    # an otherwise-flash image are left for the runtime to set up.
    segs = flash or ram
    if not segs:
        raise FirmwareError("ELF contains no loadable segments")

    fam = {EM_ARM: uf2.FAMILIES["rp2350-arm-s"],
           EM_RISCV: uf2.FAMILIES["rp2350-riscv"]}.get(e_machine)
    img = Image("elf", segs, fam)
    if fam is None:
        img.warnings.append("unrecognised ELF machine %d; assuming Arm" % e_machine)
    return img


def load_hex(data, base=None):
    segs = []
    upper = 0
    cur_addr, cur = None, bytearray()
    for lineno, line in enumerate(data.decode("ascii", "replace").splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        if not line.startswith(":"):
            raise FirmwareError("line %d: not an Intel HEX record" % lineno)
        try:
            rec = bytes.fromhex(line[1:])
        except ValueError:
            raise FirmwareError("line %d: bad hex digits" % lineno)
        if len(rec) < 5 or len(rec) != rec[0] + 5:
            raise FirmwareError("line %d: bad record length" % lineno)
        if sum(rec) & 0xFF:
            raise FirmwareError("line %d: checksum mismatch" % lineno)
        n, addr, rtype, payload = rec[0], (rec[1] << 8) | rec[2], rec[3], rec[4:4 + rec[0]]
        if rtype == 0x00:
            a = upper + addr
            if cur_addr is not None and a == cur_addr + len(cur):
                cur += payload
            else:
                if cur:
                    segs.append((cur_addr, cur))
                cur_addr, cur = a, bytearray(payload)
        elif rtype == 0x01:
            break
        elif rtype == 0x02:
            upper = int.from_bytes(payload, "big") << 4
        elif rtype == 0x04:
            upper = int.from_bytes(payload, "big") << 16
        # 0x03/0x05 (start address) are irrelevant for a UF2 download.
    if cur:
        segs.append((cur_addr, cur))
    if not segs:
        raise FirmwareError("HEX file contains no data")
    for a, d in segs:
        if region(a) is None or region(a + len(d) - 1) != region(a):
            raise FirmwareError("HEX data at 0x%08x is outside RP2350 flash/SRAM" % a)
    return Image("hex", segs)


def load_bin(data, base=FLASH_BASE):
    base = FLASH_BASE if base is None else base
    if region(base) is None or region(base + len(data) - 1) != region(base):
        raise FirmwareError("binary at 0x%08x (%d bytes) does not fit in flash or SRAM"
                            % (base, len(data)))
    return Image("bin", [(base, data)])


# --- sanity checks -----------------------------------------------------------

def _check(img):
    fams = getattr(img, "uf2_families", {img.family_id})
    if img.raw_uf2 is not None and fams and not fams & uf2.RP2350_FAMILIES:
        names = ", ".join(uf2.family_name(f) for f in fams if f is not None) or "none"
        raise FirmwareError(
            "this UF2 only targets family %s, which the Pico 2 bootrom ignores. "
            "Rebuild it for RP2350 (e.g. -DPICO_BOARD=pico2)." % names)
    if img.family_id is not None and img.family_id not in uf2.RP2350_FAMILIES:
        img.warnings.append("family %s is not accepted by the RP2350 bootrom"
                            % uf2.family_name(img.family_id))

    # RP2350 only boots an image carrying a picobin IMAGE_DEF block within
    # the first 4 KiB. Pico 1 (RP2040) builds don't have one.
    if img.start is not None and region(img.start) == "flash" and img.family_id in (
            uf2.FAMILIES["rp2350-arm-s"], uf2.FAMILIES["rp2350-riscv"],
            uf2.FAMILIES["rp2350-arm-ns"]):
        head = img.read(img.start, 4096)
        words = struct.unpack("<1024I", head)
        if PICOBIN_BLOCK_MARKER_START not in words or PICOBIN_BLOCK_MARKER_END not in words:
            img.warnings.append(
                "no IMAGE_DEF block in the first 4 KiB; the RP2350 bootrom will "
                "not boot this image (was it built for RP2040 / Pico 1?)")
