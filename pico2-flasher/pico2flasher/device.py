"""Find Pico 2 boards and get firmware onto them."""

import glob
import os
import shutil
import string
import subprocess
import sys
import tempfile
import time

RPI_VID = 0x2E8A
INFO_FILE = "INFO_UF2.TXT"


class DeviceError(Exception):
    pass


class BootselDrive:
    def __init__(self, path, info):
        self.path = path
        self.info = info
        fields = dict(l.split(":", 1) for l in info.splitlines() if ":" in l)
        self.board_id = fields.get("Board-ID", "").strip()
        self.model = fields.get("Model", "").strip()

    @property
    def is_rp2350(self):
        return "RP2350" in self.info

    def __repr__(self):
        return "%s (%s)" % (self.path, self.board_id or self.model or "unknown")


def _candidate_mounts():
    if sys.platform.startswith("win"):
        import ctypes
        mask = ctypes.windll.kernel32.GetLogicalDrives()
        return ["%s:\\" % c for i, c in enumerate(string.ascii_uppercase) if mask >> i & 1]
    if sys.platform == "darwin":
        return glob.glob("/Volumes/*")
    user = os.environ.get("USER") or os.environ.get("LOGNAME") or "*"
    pats = ["/media/%s/*" % user, "/media/*", "/run/media/%s/*" % user,
            "/run/media/*/*", "/mnt/*", "/mnt/*/*"]
    seen, out = set(), []
    for p in pats:
        for m in glob.glob(p):
            if m not in seen:
                seen.add(m)
                out.append(m)
    return out


def find_drives(all_boards=False):
    """Return mounted UF2 bootloader drives (RP2350 only unless all_boards)."""
    drives = []
    for mount in _candidate_mounts():
        info_path = os.path.join(mount, INFO_FILE)
        try:
            with open(info_path, "r", errors="replace") as f:
                info = f.read(4096)
        except OSError:
            continue
        d = BootselDrive(mount, info)
        if all_boards or d.is_rp2350:
            drives.append(d)
    return drives


def wait_for_drive(timeout, poll=0.25):
    deadline = time.monotonic() + timeout
    while True:
        drives = find_drives()
        if drives or time.monotonic() >= deadline:
            return drives
        time.sleep(poll)


def write_to_drive(drive, uf2_data, name="firmware.uf2"):
    """Copy a UF2 onto a BOOTSEL drive. The board reboots as soon as the last
    block lands, so an error while flushing/closing is expected and ignored."""
    dest = os.path.join(drive.path, name)
    f = open(dest, "wb")
    try:
        f.write(uf2_data)
        f.flush()
        os.fsync(f.fileno())
    except OSError:
        if os.path.exists(os.path.join(drive.path, INFO_FILE)):
            raise
    finally:
        try:
            f.close()
        except OSError:
            pass


# --- reboot a running board into BOOTSEL -------------------------------------

def find_serial_ports():
    """Serial ports belonging to Raspberry Pi USB devices (e.g. pico-sdk stdio)."""
    try:
        from serial.tools import list_ports
        return [p.device for p in list_ports.comports() if p.vid == RPI_VID]
    except ImportError:
        pass
    ports = []
    for tty in sorted(glob.glob("/sys/class/tty/ttyACM*")):
        dev = os.path.realpath(os.path.join(tty, "device"))
        for d in (dev, os.path.dirname(dev)):
            try:
                with open(os.path.join(d, "idVendor")) as f:
                    if int(f.read(), 16) == RPI_VID:
                        ports.append("/dev/" + os.path.basename(tty))
                        break
            except (OSError, ValueError):
                continue
    return ports


def touch_1200(port):
    """Open the port at 1200 baud and close it. pico-sdk apps with USB stdio
    treat this as a request to reboot into BOOTSEL."""
    try:
        import serial
        s = serial.Serial()
        s.port, s.baudrate = port, 1200
        s.open()
        s.close()
        return
    except ImportError:
        pass
    if sys.platform.startswith("win"):
        raise DeviceError("install pyserial to reset boards on Windows")
    import termios
    fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    try:
        attrs = termios.tcgetattr(fd)
        attrs[4] = attrs[5] = termios.B1200
        termios.tcsetattr(fd, termios.TCSANOW, attrs)
    finally:
        os.close(fd)


def reset_to_bootsel(log=print):
    ports = find_serial_ports()
    for p in ports:
        log("requesting BOOTSEL via 1200-baud touch on %s" % p)
        try:
            touch_1200(p)
        except (OSError, DeviceError) as e:
            log("  failed: %s" % e)
    return bool(ports)


# --- picotool ----------------------------------------------------------------

def picotool_path():
    return shutil.which("picotool")


def flash_with_picotool(uf2_data, log=print):
    tool = picotool_path()
    if not tool:
        raise DeviceError("picotool not found on PATH")
    fd, tmp = tempfile.mkstemp(suffix=".uf2")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(uf2_data)
        # -f forces a running board into BOOTSEL (if it exposes the reset
        # interface), -x runs the image once loaded.
        cmd = [tool, "load", "-v", "-x", tmp, "-t", "uf2", "-f"]
        log("running: " + " ".join(cmd))
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in p.stdout.splitlines():
            log("  " + line)
        if p.returncode:
            raise DeviceError("picotool exited with status %d" % p.returncode)
    finally:
        os.unlink(tmp)


# --- top-level ---------------------------------------------------------------

METHODS = ("auto", "drive", "picotool")


def flash(uf2_data, method="auto", reset=True, wait=10.0, log=print):
    """Flash a UF2 image to a Pico 2 using the best available method."""
    if method not in METHODS:
        raise DeviceError("unknown method %r" % method)

    if method == "picotool":
        flash_with_picotool(uf2_data, log)
        return "picotool"

    drives = find_drives()
    if not drives and reset and reset_to_bootsel(log):
        log("waiting up to %.0fs for the BOOTSEL drive..." % wait)
        drives = wait_for_drive(wait)

    if not drives:
        if method == "auto" and picotool_path():
            log("no BOOTSEL drive mounted; falling back to picotool")
            flash_with_picotool(uf2_data, log)
            return "picotool"
        others = find_drives(all_boards=True)
        hint = ""
        if others:
            hint = (" Found non-RP2350 UF2 drive(s): %s. A Pico 1 (RP2040) can't run "
                    "Pico 2 firmware." % ", ".join(map(repr, others)))
        raise DeviceError(
            "no Pico 2 found. Hold BOOTSEL while plugging it in so an 'RP2350' "
            "drive appears (and make sure it is mounted), then try again." + hint)

    if len(drives) > 1:
        log("multiple Pico 2 boards found, using %r" % drives[0])
    drive = drives[0]
    log("writing %d bytes to %r" % (len(uf2_data), drive))
    write_to_drive(drive, uf2_data)

    # The drive disappearing is the bootrom's signal that it rebooted.
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if not os.path.exists(os.path.join(drive.path, INFO_FILE)):
            log("board rebooted")
            break
        time.sleep(0.25)
    else:
        log("warning: drive still present; the board may not have accepted the image")
    return "drive"
