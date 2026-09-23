package io.github.malhi391.pico2flasher

import java.util.Locale
import java.util.TreeMap

/** Something went wrong, with a message a kid can understand. */
class FlashException(message: String) : Exception(message)

enum class Chip(val nickname: String) {
    RP2040("Pico 1"),
    RP2350("Pico 2"),
}

object Uf2 {
    const val MAGIC_START0 = 0x0A324655L
    const val MAGIC_START1 = 0x9E5D5157L
    const val MAGIC_END = 0x0AB16F30L
    const val FLAG_NOT_MAIN_FLASH = 0x1L
    const val FLAG_FAMILY_ID_PRESENT = 0x2000L
    const val BLOCK_SIZE = 512

    const val FAMILY_RP2040 = 0xE48BFF56L
    const val FAMILY_ABSOLUTE = 0xE48BFF57L
    const val FAMILY_DATA = 0xE48BFF58L
    const val FAMILY_RP2350_ARM_S = 0xE48BFF59L
    const val FAMILY_RP2350_RISCV = 0xE48BFF5AL
    const val FAMILY_RP2350_ARM_NS = 0xE48BFF5BL

    // picotool adds this lone block to RP2350 UF2s as a workaround for
    // erratum RP2350-E10. It must not be written to flash.
    const val E10_WORKAROUND_ADDR = 0x10FFFF00L

    fun familiesFor(chip: Chip): Set<Long> = when (chip) {
        Chip.RP2040 -> setOf(FAMILY_RP2040)
        Chip.RP2350 -> setOf(
            FAMILY_ABSOLUTE, FAMILY_DATA,
            FAMILY_RP2350_ARM_S, FAMILY_RP2350_RISCV, FAMILY_RP2350_ARM_NS,
        )
    }

    fun chipFor(family: Long): Chip? = Chip.values().firstOrNull { family in familiesFor(it) }
}

internal fun u32(b: ByteArray, off: Int): Long =
    (b[off].toLong() and 0xFF) or
        ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or
        ((b[off + 3].toLong() and 0xFF) shl 24)

internal fun u16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

internal fun hex(v: Long) = "0x%08x".format(Locale.US, v)

/** A piece of the program: where it goes, and which chip family it's for (null = any). */
class Chunk(val addr: Long, val data: ByteArray, val family: Long? = null)

/** Exactly what to write: whole 4 KiB flash sectors, in address order. */
class FlashPlan(val chip: Chip, val sectors: List<Pair<Long, ByteArray>>, val warnings: List<String>) {
    val totalBytes: Int get() = sectors.size * Firmware.SECTOR
}

class Firmware(val name: String, val format: String, val chunks: List<Chunk>, val elfMachine: Int? = null) {

    /** Which chips this file has something for. */
    val chips: Set<Chip>
        get() {
            val fams = chunks.mapNotNull { it.family }
            if (fams.size < chunks.size) {
                return if (elfMachine == EM_RISCV) setOf(Chip.RP2350) else Chip.values().toSet()
            }
            return fams.mapNotNull { Uf2.chipFor(it) }.toSet()
        }

    fun planFor(chip: Chip): FlashPlan {
        val families = Uf2.familiesFor(chip)
        var mine = chunks.filter { it.family == null || it.family in families }
        if (mine.isEmpty()) {
            val other = chips.firstOrNull()?.nickname ?: "different board"
            throw FlashException(
                "This file is made for a $other, but you plugged in a ${chip.nickname}. " +
                    "Find the ${chip.nickname} version of this file and try again!",
            )
        }
        if (elfMachine == EM_RISCV && chip == Chip.RP2040) {
            throw FlashException("This program needs a Pico 2. A Pico 1 can't run it.")
        }
        if (mine.size > 1) {
            mine = mine.filterNot { it.family == Uf2.FAMILY_ABSOLUTE && it.addr == Uf2.E10_WORKAROUND_ADDR }
        }

        val sectors = TreeMap<Long, ByteArray>()
        for (c in mine) {
            val end = c.addr + c.data.size
            if (c.addr !in FLASH_START until FLASH_END || end > FLASH_END) {
                if (c.addr in SRAM_START until SRAM_END) {
                    throw FlashException(
                        "This program only runs from the Pico's short-term memory (RAM), " +
                            "so it can't be saved. Ask for a normal version of it.",
                    )
                }
                throw FlashException(
                    "This file wants to go somewhere strange (${hex(c.addr)}). " +
                        "It doesn't look like a Pico program.",
                )
            }
            var off = 0
            while (off < c.data.size) {
                val addr = c.addr + off
                val sector = addr - (addr % SECTOR)
                val buf = sectors.getOrPut(sector) { ByteArray(SECTOR) { 0xFF.toByte() } }
                val n = minOf(c.data.size - off, (sector + SECTOR - addr).toInt())
                System.arraycopy(c.data, off, buf, (addr - sector).toInt(), n)
                off += n
            }
        }

        val warnings = mutableListOf<String>()
        val first = sectors[FLASH_START]
        if (first == null) {
            warnings += "This program doesn't start at the very beginning of the Pico's memory, " +
                "so it might not run by itself."
        } else if (chip == Chip.RP2350 && !hasImageDef(first)) {
            warnings += "This file looks like it was made for a Pico 1. " +
                "A Pico 2 probably won't start it."
        }
        return FlashPlan(chip, sectors.entries.map { it.key to it.value }, warnings)
    }

    companion object {
        const val FLASH_START = 0x10000000L
        const val FLASH_END = 0x11000000L
        const val SRAM_START = 0x20000000L
        const val SRAM_END = 0x20082000L
        const val SECTOR = 4096
        const val EM_ARM = 40
        const val EM_RISCV = 243
        const val MAX_SIZE = 32 * 1024 * 1024

        private const val PICOBIN_START = 0xFFFFDED3L
        private const val PICOBIN_END = 0xAB123579L

        /** RP2350 only boots images with a picobin IMAGE_DEF block in the first 4 KiB. */
        fun hasImageDef(sector: ByteArray): Boolean {
            var start = false
            var end = false
            for (off in 0 until minOf(sector.size, SECTOR) - 3 step 4) {
                when (u32(sector, off)) {
                    PICOBIN_START -> start = true
                    PICOBIN_END -> end = true
                }
            }
            return start && end
        }

        fun parse(name: String, bytes: ByteArray): Firmware {
            if (bytes.isEmpty()) throw FlashException("This file is empty. Try a different one.")
            if (bytes.size > MAX_SIZE) throw FlashException("This file is too big to be a Pico program.")
            val lower = name.lowercase(Locale.US)
            return when {
                bytes.size >= 8 && u32(bytes, 0) == Uf2.MAGIC_START0 && u32(bytes, 4) == Uf2.MAGIC_START1 ->
                    parseUf2(name, bytes)
                bytes.size >= 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
                    bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte() -> parseElf(name, bytes)
                bytes[0] == ':'.code.toByte() && (lower.endsWith(".hex") || lower.endsWith(".ihex") || lower.endsWith(".ihx")) ->
                    parseHex(name, bytes)
                lower.endsWith(".bin") -> Firmware(name, "BIN", listOf(Chunk(FLASH_START, bytes)))
                else -> throw FlashException(
                    "I don't know what kind of file this is. Pick a file that ends in " +
                        ".uf2 (best!), .elf, .hex or .bin.",
                )
            }
        }

        private fun parseUf2(name: String, b: ByteArray): Firmware {
            if (b.size % Uf2.BLOCK_SIZE != 0) throw FlashException("This UF2 file is broken (wrong size). Download it again.")
            val chunks = mutableListOf<Chunk>()
            for (off in 0 until b.size step Uf2.BLOCK_SIZE) {
                if (u32(b, off) != Uf2.MAGIC_START0 || u32(b, off + 4) != Uf2.MAGIC_START1 ||
                    u32(b, off + Uf2.BLOCK_SIZE - 4) != Uf2.MAGIC_END
                ) {
                    throw FlashException("This UF2 file is broken. Download it again.")
                }
                val flags = u32(b, off + 8)
                val addr = u32(b, off + 12)
                val size = u32(b, off + 16).toInt()
                if (size < 0 || size > 476) throw FlashException("This UF2 file is broken. Download it again.")
                if (flags and Uf2.FLAG_NOT_MAIN_FLASH != 0L) continue
                val family = if (flags and Uf2.FLAG_FAMILY_ID_PRESENT != 0L) u32(b, off + 28) else null
                chunks += Chunk(addr, b.copyOfRange(off + 32, off + 32 + size), family)
            }
            if (chunks.isEmpty()) throw FlashException("This UF2 file has nothing in it to flash.")
            return Firmware(name, "UF2", chunks)
        }

        private fun parseElf(name: String, b: ByteArray): Firmware {
            if (b.size < 52 || b[4].toInt() != 1 || b[5].toInt() != 1) {
                throw FlashException("This ELF file isn't for a Pico (it's not 32-bit).")
            }
            val machine = u16(b, 18)
            if (machine != EM_ARM && machine != EM_RISCV) {
                throw FlashException("This ELF file is for a different kind of computer, not a Pico.")
            }
            val phoff = u32(b, 28)
            val phentsize = u16(b, 42)
            val phnum = u16(b, 44)
            val chunks = mutableListOf<Chunk>()
            for (i in 0 until phnum) {
                val off = phoff + i.toLong() * phentsize
                if (off + 32 > b.size) throw FlashException("This ELF file is broken. Build it again.")
                val o = off.toInt()
                val type = u32(b, o)
                val fileOff = u32(b, o + 4)
                val paddr = u32(b, o + 12)
                val fileSize = u32(b, o + 16)
                if (type != 1L || fileSize == 0L) continue // PT_LOAD with bytes in the file
                if (fileOff + fileSize > b.size) throw FlashException("This ELF file is broken. Build it again.")
                chunks += Chunk(paddr, b.copyOfRange(fileOff.toInt(), (fileOff + fileSize).toInt()))
            }
            // An image that lives in flash also has RAM segments filled in at
            // startup; only the flash ones get written.
            val flash = chunks.filter { it.addr in FLASH_START until FLASH_END }
            val use = flash.ifEmpty { chunks }
            if (use.isEmpty()) throw FlashException("This ELF file has nothing in it to flash.")
            return Firmware(name, "ELF", use, machine)
        }

        private fun parseHex(name: String, b: ByteArray): Firmware {
            val chunks = mutableListOf<Chunk>()
            var upper = 0L
            var curAddr = -1L
            val cur = java.io.ByteArrayOutputStream()
            fun flush() {
                if (cur.size() > 0) chunks += Chunk(curAddr, cur.toByteArray())
                cur.reset()
            }
            val lines = String(b, Charsets.US_ASCII).lines()
            for ((i, raw) in lines.withIndex()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val bad = FlashException("This HEX file is broken on line ${i + 1}. Download it again.")
                if (!line.startsWith(":") || line.length % 2 != 1) throw bad
                val rec = try {
                    ByteArray((line.length - 1) / 2) { j -> line.substring(1 + 2 * j, 3 + 2 * j).toInt(16).toByte() }
                } catch (e: NumberFormatException) {
                    throw bad
                }
                if (rec.size < 5 || rec.size != (rec[0].toInt() and 0xFF) + 5) throw bad
                if (rec.sumOf { it.toInt() and 0xFF } and 0xFF != 0) throw bad
                val n = rec[0].toInt() and 0xFF
                val addr = ((rec[1].toInt() and 0xFF) shl 8 or (rec[2].toInt() and 0xFF)).toLong()
                val payload = rec.copyOfRange(4, 4 + n)
                if ((rec[3].toInt() == 0x02 || rec[3].toInt() == 0x04) && n < 2) throw bad
                when (rec[3].toInt()) {
                    0x00 -> {
                        val a = upper + addr
                        if (cur.size() == 0 || a != curAddr + cur.size()) {
                            flush()
                            curAddr = a
                        }
                        cur.write(payload)
                    }
                    0x01 -> break
                    0x02 -> upper = (((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)).toLong() shl 4
                    0x04 -> upper = (((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)).toLong() shl 16
                    else -> Unit // start address records don't matter here
                }
            }
            flush()
            if (chunks.isEmpty()) throw FlashException("This HEX file has nothing in it to flash.")
            return Firmware(name, "HEX", chunks)
        }
    }
}
