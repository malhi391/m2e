"""Minimal Tk front end: pick a file, press Flash."""

import queue
import threading

from . import device, formats, uf2
from .cli import describe


def run(initial_file=None):
    try:
        import tkinter as tk
        from tkinter import filedialog, ttk
    except ImportError:
        raise SystemExit("tkinter is not available; use `pico2flasher flash FILE` instead")

    root = tk.Tk()
    root.title("Pico 2 Flasher")
    root.minsize(560, 380)
    msgs = queue.Queue()

    file_var = tk.StringVar(value=initial_file or "")
    family_var = tk.StringVar(value="auto")
    base_var = tk.StringVar(value="0x%08x" % formats.FLASH_BASE)
    method_var = tk.StringVar(value="auto")
    status_var = tk.StringVar(value="")

    frm = ttk.Frame(root, padding=10)
    frm.pack(fill="both", expand=True)
    frm.columnconfigure(1, weight=1)

    def browse():
        path = filedialog.askopenfilename(filetypes=[
            ("Firmware", "*.uf2 *.elf *.hex *.ihex *.bin"), ("All files", "*")])
        if path:
            file_var.set(path)
            inspect()

    ttk.Label(frm, text="Firmware").grid(row=0, column=0, sticky="w")
    ttk.Entry(frm, textvariable=file_var).grid(row=0, column=1, sticky="ew", padx=4)
    ttk.Button(frm, text="Browse...", command=browse).grid(row=0, column=2)

    ttk.Label(frm, text="Family").grid(row=1, column=0, sticky="w")
    ttk.Combobox(frm, textvariable=family_var, state="readonly",
                 values=["auto"] + list(uf2.FAMILIES)).grid(row=1, column=1, sticky="w", padx=4)

    ttk.Label(frm, text=".bin address").grid(row=2, column=0, sticky="w")
    ttk.Entry(frm, textvariable=base_var, width=14).grid(row=2, column=1, sticky="w", padx=4)

    ttk.Label(frm, text="Method").grid(row=3, column=0, sticky="w")
    ttk.Combobox(frm, textvariable=method_var, state="readonly",
                 values=list(device.METHODS)).grid(row=3, column=1, sticky="w", padx=4)

    log_box = tk.Text(frm, height=14, state="disabled", wrap="word")
    log_box.grid(row=5, column=0, columnspan=3, sticky="nsew", pady=(8, 0))
    frm.rowconfigure(5, weight=1)
    ttk.Label(frm, textvariable=status_var).grid(row=6, column=0, columnspan=3, sticky="w")

    def log(msg):
        msgs.put(msg)

    def settings():
        # Read Tk variables on the UI thread only.
        return (file_var.get(), base_var.get(), family_var.get(), method_var.get())

    def load_image(path, base, family):
        return formats.load(path, base=int(base, 0), family=family)

    def inspect():
        try:
            img = load_image(*settings()[:3])
        except (formats.FirmwareError, OSError, ValueError) as e:
            log("error: %s" % e)
            return
        log(describe(img))
        for w in img.warnings:
            log("warning: " + w)

    buttons = ttk.Frame(frm)
    buttons.grid(row=4, column=0, columnspan=3, sticky="e", pady=(8, 0))

    def worker(path, base, family, method):
        try:
            img = load_image(path, base, family)
            for w in img.warnings:
                log("warning: " + w)
            how = device.flash(img.to_uf2(), method=method, log=log)
            log("done (via %s)" % how)
        except (formats.FirmwareError, device.DeviceError, OSError, ValueError) as e:
            log("error: %s" % e)
        finally:
            msgs.put(None)

    def do_flash():
        if not file_var.get():
            browse()
            if not file_var.get():
                return
        flash_btn.state(["disabled"])
        threading.Thread(target=worker, args=settings(), daemon=True).start()

    ttk.Button(buttons, text="Inspect", command=inspect).pack(side="left", padx=4)
    flash_btn = ttk.Button(buttons, text="Flash", command=do_flash)
    flash_btn.pack(side="left")

    def pump():
        while True:
            try:
                m = msgs.get_nowait()
            except queue.Empty:
                break
            if m is None:
                flash_btn.state(["!disabled"])
                continue
            if isinstance(m, tuple):
                status_var.set(m[1])
                continue
            log_box.configure(state="normal")
            log_box.insert("end", m + "\n")
            log_box.see("end")
            log_box.configure(state="disabled")
        root.after(100, pump)

    def poll_devices():
        # find_drives() touches the filesystem; keep it off the UI thread.
        def check():
            n = len(device.find_drives())
            p = len(device.find_serial_ports())
            msgs.put(("status", "Pico 2 in BOOTSEL: %d    running Pico boards: %d" % (n, p)))
        threading.Thread(target=check, daemon=True).start()
        root.after(1500, poll_devices)

    if initial_file:
        inspect()
    pump()
    poll_devices()
    root.mainloop()
