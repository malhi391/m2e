package io.github.malhi391.pico2flasher

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The two USB pipes (and interface reset) of the Pico's PICOBOOT interface. */
interface UsbLink {
    /** Returns bytes sent, or a negative number on error. */
    fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int): Int

    /** Returns bytes received, or a negative number on error. */
    fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int

    fun resetInterface()
}

/**
 * Talks the bootrom's PICOBOOT protocol (the same one picotool uses) to a
 * Pico or Pico 2 in BOOTSEL mode. No drive mounting needed.
 */
class Picoboot(private val link: UsbLink, private val chip: Chip) {
    private var token = 1

    fun flash(plan: FlashPlan, progress: (done: Int, total: Int) -> Unit) {
        link.resetInterface()
        command(CMD_EXCLUSIVE_ACCESS, byteArrayOf(1))
        if (chip == Chip.RP2040) command(CMD_EXIT_XIP, ByteArray(0))
        val total = plan.sectors.size
        progress(0, total)
        for ((i, sector) in plan.sectors.withIndex()) {
            val (addr, data) = sector
            command(CMD_FLASH_ERASE, le(addr.toInt(), data.size), timeoutMs = SLOW_TIMEOUT)
            command(CMD_WRITE, le(addr.toInt(), data.size), data.size, data, SLOW_TIMEOUT)
            val back = command(CMD_READ, le(addr.toInt(), data.size), data.size)
            if (!back!!.contentEquals(data)) {
                throw FlashException("The Pico didn't save the program correctly. Try a different cable and flash again.")
            }
            progress(i + 1, total)
        }
        reboot()
    }

    private fun reboot() {
        try {
            if (chip == Chip.RP2040) {
                command(CMD_REBOOT, le(0, 0, REBOOT_DELAY_MS)) // pc=0, sp=0: normal boot
            } else {
                command(CMD_REBOOT2, le(0, REBOOT_DELAY_MS, 0, 0)) // type NORMAL
            }
        } catch (e: FlashException) {
            // The Pico may drop off the bus as it restarts; the program is already saved.
        }
    }

    private fun command(
        id: Int,
        args: ByteArray,
        transferLength: Int = 0,
        dataOut: ByteArray? = null,
        timeoutMs: Int = TIMEOUT,
    ): ByteArray? {
        val cmd = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        cmd.putInt(MAGIC)
        cmd.putInt(token++)
        cmd.put(id.toByte())
        cmd.put(args.size.toByte())
        cmd.putShort(0)
        cmd.putInt(transferLength)
        cmd.put(args)
        send(cmd.array(), timeoutMs)

        val toHost = id and 0x80 != 0
        var result: ByteArray? = null
        if (transferLength > 0) {
            if (toHost) result = receive(transferLength, timeoutMs) else send(dataOut!!, timeoutMs)
        }
        // Handshake: a zero-length packet in the opposite direction to the data.
        if (toHost) {
            if (link.bulkOut(ByteArray(0), 0, timeoutMs) < 0) throw lost()
        } else {
            if (link.bulkIn(ByteArray(64), 64, timeoutMs) != 0) throw lost()
        }
        return result
    }

    private fun send(data: ByteArray, timeoutMs: Int) {
        var off = 0
        while (off < data.size) {
            val n = minOf(data.size - off, MAX_CHUNK)
            val buf = if (off == 0 && n == data.size) data else data.copyOfRange(off, off + n)
            val sent = link.bulkOut(buf, n, timeoutMs)
            if (sent <= 0) throw lost()
            off += sent
        }
    }

    private fun receive(length: Int, timeoutMs: Int): ByteArray {
        val out = ByteArray(length)
        val buf = ByteArray(minOf(length, MAX_CHUNK))
        var off = 0
        while (off < length) {
            val want = minOf(length - off, buf.size)
            val got = link.bulkIn(buf, want, timeoutMs)
            if (got <= 0) throw lost()
            System.arraycopy(buf, 0, out, off, got)
            off += got
        }
        return out
    }

    private fun lost() = FlashException("I lost contact with the Pico. Check the cable is pushed in all the way and try again.")

    companion object {
        const val MAGIC = 0x431FD10B
        const val CMD_EXCLUSIVE_ACCESS = 0x01
        const val CMD_REBOOT = 0x02
        const val CMD_FLASH_ERASE = 0x03
        const val CMD_READ = 0x84
        const val CMD_WRITE = 0x05
        const val CMD_EXIT_XIP = 0x06
        const val CMD_REBOOT2 = 0x0A

        const val TIMEOUT = 3000
        const val SLOW_TIMEOUT = 10000
        const val MAX_CHUNK = 16384
        const val REBOOT_DELAY_MS = 500

        fun le(vararg words: Int): ByteArray {
            val b = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            words.forEach { b.putInt(it) }
            return b.array()
        }
    }
}
