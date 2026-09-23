"""UF2 encoding/decoding (https://github.com/microsoft/uf2)."""

import struct

MAGIC_START0 = 0x0A324655
MAGIC_START1 = 0x9E5D5157
MAGIC_END = 0x0AB16F30

FLAG_NOT_MAIN_FLASH = 0x00000001
FLAG_FAMILY_ID_PRESENT = 0x00002000

BLOCK_SIZE = 512
PAYLOAD_SIZE = 256
DATA_AREA = 476

# Family IDs understood by the RP2040/RP2350 bootroms.
FAMILIES = {
    "rp2040": 0xE48BFF56,
    "absolute": 0xE48BFF57,
    "data": 0xE48BFF58,
    "rp2350-arm-s": 0xE48BFF59,
    "rp2350-riscv": 0xE48BFF5A,
    "rp2350-arm-ns": 0xE48BFF5B,
}
FAMILY_NAMES = {v: k for k, v in FAMILIES.items()}

# Families the RP2350 bootrom will accept. An RP2040 UF2 is silently ignored.
RP2350_FAMILIES = {
    FAMILIES["absolute"],
    FAMILIES["data"],
    FAMILIES["rp2350-arm-s"],
    FAMILIES["rp2350-riscv"],
    FAMILIES["rp2350-arm-ns"],
}

_HEADER = struct.Struct("<8I")


def family_name(family_id):
    return FAMILY_NAMES.get(family_id, "0x%08x" % family_id)


def encode(pages, family_id):
    """Encode {page_addr: 256-byte bytes} into a UF2 file."""
    addrs = sorted(pages)
    out = bytearray()
    for i, addr in enumerate(addrs):
        data = pages[addr]
        if len(data) != PAYLOAD_SIZE or addr % PAYLOAD_SIZE:
            raise ValueError("bad page at 0x%08x" % addr)
        out += _HEADER.pack(MAGIC_START0, MAGIC_START1, FLAG_FAMILY_ID_PRESENT,
                            addr, PAYLOAD_SIZE, i, len(addrs), family_id)
        out += data
        out += bytes(DATA_AREA - PAYLOAD_SIZE)
        out += struct.pack("<I", MAGIC_END)
    return bytes(out)


class Block:
    __slots__ = ("flags", "addr", "data", "block_no", "num_blocks", "family_id")

    def __init__(self, flags, addr, data, block_no, num_blocks, family_id):
        self.flags = flags
        self.addr = addr
        self.data = data
        self.block_no = block_no
        self.num_blocks = num_blocks
        self.family_id = family_id


def decode(buf):
    """Decode a UF2 file into a list of Blocks. Raises ValueError if malformed."""
    if len(buf) % BLOCK_SIZE:
        raise ValueError("UF2 size %d is not a multiple of %d" % (len(buf), BLOCK_SIZE))
    blocks = []
    for off in range(0, len(buf), BLOCK_SIZE):
        blk = buf[off:off + BLOCK_SIZE]
        m0, m1, flags, addr, size, no, num, fam = _HEADER.unpack_from(blk)
        (end,) = struct.unpack_from("<I", blk, BLOCK_SIZE - 4)
        if m0 != MAGIC_START0 or m1 != MAGIC_START1 or end != MAGIC_END:
            raise ValueError("bad UF2 magic in block at offset 0x%x" % off)
        if size > DATA_AREA:
            raise ValueError("bad payload size %d in block %d" % (size, no))
        if not flags & FLAG_FAMILY_ID_PRESENT:
            fam = None
        blocks.append(Block(flags, addr, bytes(blk[32:32 + size]), no, num, fam))
    return blocks


def is_uf2(buf):
    if len(buf) < BLOCK_SIZE:
        return False
    m0, m1 = struct.unpack_from("<2I", buf)
    return m0 == MAGIC_START0 and m1 == MAGIC_START1
