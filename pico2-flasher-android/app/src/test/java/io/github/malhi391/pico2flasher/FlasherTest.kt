package io.github.malhi391.pico2flasher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val IMAGE_DEF: ByteArray = Picoboot.le(0xFFFFDED3.toInt(), 0, 0, 0xAB123579.toInt())

private fun uf2(blocks: List<Triple<Long, ByteArray, Long>>): ByteArray {
    val out = ByteBuffer.allocate(blocks.size * 512).order(ByteOrder.LITTLE_ENDIAN)
    blocks.forEachIndexed { i, (addr, data, family) ->
        val start = out.position()
        out.putInt(Uf2.MAGIC_START0.toInt()).putInt(Uf2.MAGIC_START1.toInt())
        out.putInt(Uf2.FLAG_FAMILY_ID_PRESENT.toInt()).putInt(addr.toInt())
        out.putInt(data.size).putInt(i).putInt(blocks.size).putInt(family.toInt())
        out.put(data)
        out.position(start + 508)
        out.putInt(Uf2.MAGIC_END.toInt())
    }
    return out.array()
}

private fun page(vararg prefix: ByteArray): ByteArray {
    val p = ByteArray(256)
    var off = 0
    for (b in prefix) {
        System.arraycopy(b, 0, p, off, b.size)
        off += b.size
    }
    return p
}

private fun elf(machine: Int, segments: List<Pair<Long, ByteArray>>): ByteArray {
    val phoff = 52
    val dataOff = phoff + 32 * segments.size
    val total = dataOff + segments.sumOf { it.second.size }
    val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    b.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 1, 1))
    b.position(16)
    b.putShort(2).putShort(machine.toShort()).putInt(1).putInt(segments[0].first.toInt())
    b.putInt(phoff).putInt(0).putInt(0).putShort(52).putShort(32).putShort(segments.size.toShort())
    b.putShort(40).putShort(0).putShort(0)
    var off = dataOff
    for ((addr, data) in segments) {
        b.putInt(1).putInt(off).putInt(addr.toInt()).putInt(addr.toInt())
        b.putInt(data.size).putInt(data.size).putInt(5).putInt(4)
        off += data.size
    }
    for ((_, data) in segments) b.put(data)
    return b.array()
}

private fun hexRecord(addr: Int, type: Int, payload: ByteArray): String {
    val rec = byteArrayOf(payload.size.toByte(), (addr shr 8).toByte(), addr.toByte(), type.toByte()) + payload
    val sum = (-rec.sumOf { it.toInt() and 0xFF }) and 0xFF
    return ":" + (rec + sum.toByte()).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}

/** Behaves like the bootrom's PICOBOOT interface, with 1 MiB of NOR flash. */
private class FakePico : UsbLink {
    val flash = ByteArray(1 shl 20) { 0xFF.toByte() }
    val commands = mutableListOf<Int>()
    var resets = 0

    private enum class State { CMD, DATA_OUT, DATA_IN, ACK_IN, ACK_OUT }

    private var state = State.CMD
    private var id = 0
    private var addr = 0
    private var size = 0
    private var args = ByteArray(16)
    private val incoming = java.io.ByteArrayOutputStream()
    private var outgoing = ByteArray(0)
    private var outPos = 0

    override fun resetInterface() {
        resets++
        state = State.CMD
    }

    override fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int): Int {
        when (state) {
            State.CMD -> {
                assertEquals(32, length)
                val b = ByteBuffer.wrap(data, 0, 32).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(Picoboot.MAGIC, b.int)
                b.int // token
                id = b.get().toInt() and 0xFF
                val argSize = b.get().toInt()
                b.short
                val transfer = b.int
                args = ByteArray(16).also { b.get(it) }
                val a = ByteBuffer.wrap(args).order(ByteOrder.LITTLE_ENDIAN)
                addr = a.int
                size = a.int
                commands += id
                val expectedArgs = mapOf(0x01 to 1, 0x02 to 12, 0x03 to 8, 0x84 to 8, 0x05 to 8, 0x06 to 0, 0x0A to 16)
                assertEquals("arg size for cmd $id", expectedArgs[id], argSize)
                when {
                    transfer == 0 -> {
                        execute()
                        state = State.ACK_IN
                    }
                    id and 0x80 != 0 -> {
                        assertEquals(size, transfer)
                        outgoing = flash.copyOfRange(addr - 0x10000000, addr - 0x10000000 + size)
                        outPos = 0
                        state = State.DATA_IN
                    }
                    else -> {
                        incoming.reset()
                        state = State.DATA_OUT
                    }
                }
            }
            State.DATA_OUT -> {
                incoming.write(data, 0, length)
                if (incoming.size() == size) {
                    execute()
                    state = State.ACK_IN
                }
            }
            State.ACK_OUT -> {
                assertEquals(0, length)
                state = State.CMD
            }
            else -> fail("unexpected OUT in $state")
        }
        return length
    }

    override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        return when (state) {
            State.DATA_IN -> {
                val n = minOf(length, outgoing.size - outPos)
                System.arraycopy(outgoing, outPos, buffer, 0, n)
                outPos += n
                if (outPos == outgoing.size) state = State.ACK_OUT
                n
            }
            State.ACK_IN -> {
                state = State.CMD
                0
            }
            else -> {
                fail("unexpected IN in $state")
                -1
            }
        }
    }

    private fun execute() {
        val base = 0x10000000
        when (id) {
            0x03 -> {
                assertEquals(0, addr % 4096)
                assertEquals(0, size % 4096)
                java.util.Arrays.fill(flash, addr - base, addr - base + size, 0xFF.toByte())
            }
            0x05 -> {
                assertEquals(0, addr % 256)
                val data = incoming.toByteArray()
                // NOR flash can only clear bits, so a missing erase shows up.
                for (i in data.indices) {
                    val o = addr - base + i
                    flash[o] = (flash[o].toInt() and data[i].toInt()).toByte()
                }
            }
        }
    }
}

class FirmwareTest {
    @Test
    fun uf2ForPico2() {
        val data = uf2(listOf(Triple(0x10000000L, page(IMAGE_DEF), Uf2.FAMILY_RP2350_ARM_S)))
        val fw = Firmware.parse("blink.uf2", data)
        assertEquals(setOf(Chip.RP2350), fw.chips)
        val plan = fw.planFor(Chip.RP2350)
        assertEquals(1, plan.sectors.size)
        assertEquals(0x10000000L, plan.sectors[0].first)
        assertTrue(plan.warnings.isEmpty())
        assertEquals(0xFF.toByte(), plan.sectors[0].second[300])
    }

    @Test
    fun pico1FileOnPico2IsRefused() {
        val fw = Firmware.parse("old.uf2", uf2(listOf(Triple(0x10000000L, page(), Uf2.FAMILY_RP2040))))
        try {
            fw.planFor(Chip.RP2350)
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("Pico 1"))
        }
    }

    @Test
    fun universalUf2PicksTheRightHalf() {
        val data = uf2(
            listOf(
                Triple(0x10000000L, page(byteArrayOf(0x40)), Uf2.FAMILY_RP2040),
                Triple(0x10000000L, page(IMAGE_DEF, byteArrayOf(0x50)), Uf2.FAMILY_RP2350_RISCV),
            ),
        )
        val fw = Firmware.parse("both.uf2", data)
        assertEquals(setOf(Chip.RP2040, Chip.RP2350), fw.chips)
        assertEquals(0x40.toByte(), fw.planFor(Chip.RP2040).sectors[0].second[0])
        assertEquals(0x50.toByte(), fw.planFor(Chip.RP2350).sectors[0].second[16])
    }

    @Test
    fun e10WorkaroundBlockIsSkipped() {
        val data = uf2(
            listOf(
                Triple(Uf2.E10_WORKAROUND_ADDR, page(), Uf2.FAMILY_ABSOLUTE),
                Triple(0x10000000L, page(IMAGE_DEF), Uf2.FAMILY_RP2350_ARM_S),
            ),
        )
        val plan = Firmware.parse("sdk21.uf2", data).planFor(Chip.RP2350)
        assertEquals(listOf(0x10000000L), plan.sectors.map { it.first })
    }

    @Test
    fun ramOnlyUf2IsRefused() {
        val fw = Firmware.parse("ram.uf2", uf2(listOf(Triple(0x20000000L, page(), Uf2.FAMILY_RP2350_ARM_S))))
        try {
            fw.planFor(Chip.RP2350)
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("RAM"))
        }
    }

    @Test
    fun binWithoutImageDefWarnsOnPico2() {
        val fw = Firmware.parse("thing.bin", ByteArray(5000) { 1 })
        val plan = fw.planFor(Chip.RP2350)
        assertEquals(2, plan.sectors.size)
        assertEquals(1, plan.warnings.size)
        assertTrue(fw.planFor(Chip.RP2040).warnings.isEmpty())
    }

    @Test
    fun elfSegmentsPlacedByPhysicalAddress() {
        val code = IMAGE_DEF + byteArrayOf(1, 2, 3)
        val data = byteArrayOf(9, 9)
        val fw = Firmware.parse("app.elf", elf(Firmware.EM_ARM, listOf(0x10000000L to code, 0x10002000L to data)))
        val plan = fw.planFor(Chip.RP2350)
        assertEquals(listOf(0x10000000L, 0x10002000L), plan.sectors.map { it.first })
        assertEquals(9.toByte(), plan.sectors[1].second[0])
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun riscvElfNeedsPico2() {
        val fw = Firmware.parse("rv.elf", elf(Firmware.EM_RISCV, listOf(0x10000000L to IMAGE_DEF)))
        assertEquals(setOf(Chip.RP2350), fw.chips)
        try {
            fw.planFor(Chip.RP2040)
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("Pico 2"))
        }
    }

    @Test
    fun intelHex() {
        val payload = IMAGE_DEF + byteArrayOf(7, 8)
        val text = listOf(
            hexRecord(0, 0x04, byteArrayOf(0x10, 0x00)),
            hexRecord(0, 0x00, payload.copyOfRange(0, 16)),
            hexRecord(16, 0x00, payload.copyOfRange(16, payload.size)),
            hexRecord(0, 0x01, ByteArray(0)),
        ).joinToString("\n")
        val fw = Firmware.parse("app.hex", text.toByteArray())
        assertEquals(1, fw.chunks.size)
        assertEquals(0x10000000L, fw.chunks[0].addr)
        assertArrayEquals(payload, fw.chunks[0].data)
    }

    @Test
    fun badHexChecksum() {
        val line = hexRecord(0, 0x00, byteArrayOf(1, 2)).dropLast(2) + "00"
        try {
            Firmware.parse("bad.hex", line.toByteArray())
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("line 1"))
        }
    }

    @Test
    fun unknownFileIsRefused() {
        try {
            Firmware.parse("cat.jpg", byteArrayOf(1, 2, 3))
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains(".uf2"))
        }
    }
}

class PicobootTest {
    @Test
    fun flashesPico2AndVerifies() {
        val image = ByteArray(5000) { (it * 7).toByte() }
        System.arraycopy(IMAGE_DEF, 0, image, 0, IMAGE_DEF.size)
        val plan = Firmware.parse("app.bin", image).planFor(Chip.RP2350)
        val pico = FakePico()
        // Leftover junk in flash must be erased first.
        java.util.Arrays.fill(pico.flash, 0, 8192, 0)
        val seen = mutableListOf<Pair<Int, Int>>()
        Picoboot(pico, Chip.RP2350).flash(plan) { d, t -> seen += d to t }

        assertArrayEquals(image, pico.flash.copyOfRange(0, image.size))
        assertEquals(0xFF.toByte(), pico.flash[image.size])
        assertEquals(1, pico.resets)
        assertEquals(listOf(0x01, 0x03, 0x05, 0x84, 0x03, 0x05, 0x84, 0x0A), pico.commands)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
    }

    @Test
    fun pico1ExitsXipAndUsesOldReboot() {
        val plan = Firmware.parse("app.bin", ByteArray(100) { 3 }).planFor(Chip.RP2040)
        val pico = FakePico()
        Picoboot(pico, Chip.RP2040).flash(plan) { _, _ -> }
        assertEquals(listOf(0x01, 0x06, 0x03, 0x05, 0x84, 0x02), pico.commands)
        assertEquals(3.toByte(), pico.flash[99])
    }

    @Test
    fun verifyFailureIsReported() {
        val plan = Firmware.parse("app.bin", ByteArray(100) { 3 }).planFor(Chip.RP2040)
        val pico = FakePico()
        // Corrupt the read-back by making every read return zeros.
        val corrupting = object : UsbLink {
            override fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int) = pico.bulkOut(data, length, timeoutMs)
            override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
                val n = pico.bulkIn(buffer, length, timeoutMs)
                if (n > 0) java.util.Arrays.fill(buffer, 0, n, 0)
                return n
            }
            override fun resetInterface() = pico.resetInterface()
        }
        try {
            Picoboot(corrupting, Chip.RP2040).flash(plan) { _, _ -> }
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("cable"))
        }
    }

    @Test
    fun lostDeviceIsReported() {
        val plan = Firmware.parse("app.bin", ByteArray(100)).planFor(Chip.RP2350)
        val gone = object : UsbLink {
            override fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int) = -1
            override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int) = -1
            override fun resetInterface() {}
        }
        try {
            Picoboot(gone, Chip.RP2350).flash(plan) { _, _ -> }
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("lost contact"))
        }
    }
}
