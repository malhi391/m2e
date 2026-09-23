package io.github.malhi391.pico2flasher

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/** Finding Picos on the USB port and getting them ready to flash. */
object PicoUsb {
    const val VENDOR_RASPBERRY_PI = 0x2E8A
    const val PRODUCT_RP2040_BOOTSEL = 0x0003
    const val PRODUCT_RP2350_BOOTSEL = 0x000F

    fun isPico(d: UsbDevice) = d.vendorId == VENDOR_RASPBERRY_PI

    /** The chip, if this Pico is in BOOTSEL (flashing) mode; null otherwise. */
    fun bootselChip(d: UsbDevice): Chip? = when {
        !isPico(d) -> null
        d.productId == PRODUCT_RP2350_BOOTSEL -> Chip.RP2350
        d.productId == PRODUCT_RP2040_BOOTSEL -> Chip.RP2040
        else -> null
    }

    /** Prefer a Pico that's already in BOOTSEL mode. */
    fun find(manager: UsbManager): UsbDevice? {
        val picos = manager.deviceList.values.filter { isPico(it) }
        return picos.firstOrNull { bootselChip(it) != null } ?: picos.firstOrNull()
    }

    class Session(val picoboot: Picoboot, private val conn: UsbDeviceConnection, private val intf: UsbInterface) {
        fun close() {
            try {
                conn.releaseInterface(intf)
            } catch (e: Exception) {
                // already gone
            }
            conn.close()
        }
    }

    fun open(manager: UsbManager, device: UsbDevice, chip: Chip): Session {
        var intf: UsbInterface? = null
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val candidate = device.getInterface(i)
            if (candidate.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) continue
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until candidate.endpointCount) {
                val ep = candidate.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp != null && outEp != null) {
                intf = candidate
                epIn = inEp
                epOut = outEp
                break
            }
        }
        if (intf == null || epIn == null || epOut == null) {
            throw FlashException("This Pico isn't ready. Unplug it, hold the BOOTSEL button, and plug it in again.")
        }
        val pbIntf: UsbInterface = intf
        val pbIn: UsbEndpoint = epIn
        val pbOut: UsbEndpoint = epOut
        val conn = manager.openDevice(device)
            ?: throw FlashException("Android wouldn't let me talk to the Pico. Unplug it and plug it in again.")
        if (!conn.claimInterface(pbIntf, true)) {
            conn.close()
            throw FlashException("Something else is using the Pico. Close other apps and try again.")
        }
        val link = object : UsbLink {
            override fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int) =
                conn.bulkTransfer(pbOut, data, length, timeoutMs)

            override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int) =
                conn.bulkTransfer(pbIn, buffer, length, timeoutMs)

            override fun resetInterface() {
                // PICOBOOT_IF_RESET: clears any half-finished command.
                conn.controlTransfer(0x41, 0x41, 0, pbIntf.id, null, 0, 1000)
            }
        }
        return Session(Picoboot(link, chip), conn, pbIntf)
    }

    /**
     * Asks a Pico that's running a program to restart into BOOTSEL mode.
     * Works with pico-sdk programs that use USB (including the reset
     * interface picotool uses), Arduino and MicroPython.
     * Returns false if there was no way to ask.
     */
    fun wakeToBootsel(manager: UsbManager, device: UsbDevice): Boolean {
        val conn = manager.openDevice(device) ?: return false
        try {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    intf.interfaceSubclass == 0x00 && intf.interfaceProtocol == 0x01
                ) {
                    conn.claimInterface(intf, true)
                    // RESET_REQUEST_BOOTSEL. The Pico restarts, so the result doesn't matter.
                    conn.controlTransfer(0x41, 0x01, 0, intf.id, null, 0, 1000)
                    return true
                }
            }
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                    conn.claimInterface(intf, true)
                    // The "1200 baud touch": set the serial speed to 1200, then drop DTR.
                    val lineCoding = byteArrayOf(0xB0.toByte(), 0x04, 0, 0, 0, 0, 8)
                    conn.controlTransfer(0x21, 0x20, 0, intf.id, lineCoding, lineCoding.size, 1000)
                    conn.controlTransfer(0x21, 0x22, 0, intf.id, null, 0, 1000)
                    return true
                }
            }
            return false
        } finally {
            conn.close()
        }
    }
}
