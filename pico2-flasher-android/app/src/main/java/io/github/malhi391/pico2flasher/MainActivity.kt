package io.github.malhi391.pico2flasher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var usb: UsbManager
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    private var fileUri: Uri? = null
    private var fileName: String? = null
    private var fileOk = false

    private var busy = false
    private var flashAfterPermission = false
    private var waitingForBootsel = false
    private var lastFlashEnded = 0L

    private lateinit var fileText: TextView
    private lateinit var picoText: TextView
    private lateinit var flashButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var autoSwitch: Switch

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val wanted = flashAfterPermission
                    flashAfterPermission = false
                    if (!granted) {
                        say("🙈 You need to tap “OK” / “Allow” so I can talk to the Pico. Try again!")
                    } else if (wanted) {
                        startFlash()
                    }
                }
                // Give Android a moment to finish setting the new device up.
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> ui.postDelayed({ onPicoMaybeArrived() }, 400)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> ui.postDelayed({ refreshPico() }, 200)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        prefs = getSharedPreferences("pico", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        prefs.getString(PREF_URI, null)?.let { setFile(Uri.parse(it), remember = false) }
        handleIntent(intent)
        refreshPico()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshPico()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { setFile(it, remember = true) }
            Intent.ACTION_SEND -> streamExtra(intent)?.let { setFile(it, remember = true) }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> ui.postDelayed({ onPicoMaybeArrived() }, 400)
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
        }

    // ---- Step 1: the file ----------------------------------------------------

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK)
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            data?.data?.let { setFile(it, remember = true) }
        }
    }

    private fun setFile(uri: Uri, remember: Boolean) {
        if (remember) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // Not every app lets us keep the file; it still works for now.
            }
            prefs.edit().putString(PREF_URI, uri.toString()).apply()
        }
        fileUri = uri
        fileOk = false
        fileText.text = "🔍 Looking at your file…"
        updateButton()
        thread {
            val result = try {
                val name = displayName(uri)
                val fw = Firmware.parse(name, readFile(uri))
                val forWhat = fw.chips.joinToString(" or ") { it.nickname }
                Triple(true, name, "✅ $name\nReady! (for $forWhat)")
            } catch (e: FlashException) {
                Triple(false, null, "😕 ${e.message}")
            } catch (e: Exception) {
                Triple(false, null, "😕 I can't open that file any more. Pick it again!")
            }
            ui.post {
                if (fileUri != uri) return@post
                fileOk = result.first
                fileName = result.second
                fileText.text = result.third
                if (!fileOk && !remember) {
                    fileText.text = "📂 Pick the program you want to put on your Pico."
                    fileUri = null
                }
                updateButton()
            }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (e: Exception) {
            // fall through
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "program"
    }

    private fun readFile(uri: Uri): ByteArray =
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FlashException("I can't open that file. Try picking it again.")

    // ---- Step 2: the Pico ----------------------------------------------------

    private fun refreshPico() {
        val device = PicoUsb.find(usb)
        val chip = device?.let { PicoUsb.bootselChip(it) }
        picoText.text = when {
            device == null ->
                "🔌 Plug your Pico into the phone.\n\n" +
                    "Tip: hold down the white BOOTSEL button on the Pico while you plug it in, then let go."
            chip != null -> "✅ Found your ${chip.nickname}! It's ready to flash."
            else -> "🟢 Found your Pico! It's running a program right now. " +
                "When you flash, I'll wake it up for you."
        }
        updateButton()
    }

    private fun onPicoMaybeArrived() {
        refreshPico()
        val device = PicoUsb.find(usb) ?: return
        if (PicoUsb.bootselChip(device) == null || busy) return
        if (waitingForBootsel) {
            waitingForBootsel = false
            startFlash()
            return
        }
        // Auto-flash, but not when the Pico just restarted from our own flash.
        val recentlyFlashed = SystemClock.elapsedRealtime() - lastFlashEnded < 15_000
        if (autoSwitch.isChecked && fileOk && !recentlyFlashed) startFlash()
    }

    private fun askPermission(device: UsbDevice) {
        flashAfterPermission = true
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
        say("👉 Tap “OK” so I can talk to your Pico.")
    }

    // ---- Step 3: flash! ------------------------------------------------------

    private fun startFlash() {
        if (busy) return
        val uri = fileUri
        if (uri == null || !fileOk) {
            say("📂 First, pick a program file (step 1).")
            return
        }
        val device = PicoUsb.find(usb)
        if (device == null) {
            say("🔌 Plug in your Pico first (step 2).")
            return
        }
        if (!usb.hasPermission(device)) {
            askPermission(device)
            return
        }
        setBusy(true)
        progress.progress = 0
        say("🤔 Getting ready…")
        val chip = PicoUsb.bootselChip(device)
        thread {
            try {
                val fw = Firmware.parse(fileName ?: displayName(uri), readFile(uri))
                if (chip == null) {
                    if (!PicoUsb.wakeToBootsel(usb, device)) {
                        throw FlashException(
                            "I couldn't wake up your Pico. Unplug it, hold the BOOTSEL button, " +
                                "and plug it back in.",
                        )
                    }
                    ui.post { waitForBootsel() }
                    return@thread
                }
                val plan = fw.planFor(chip)
                ui.post {
                    if (plan.warnings.isEmpty()) {
                        doFlash(device, plan)
                    } else {
                        confirm(plan.warnings.joinToString("\n\n")) { go ->
                            if (go) doFlash(device, plan) else finishFlash("👍 OK, I didn't change anything.")
                        }
                    }
                }
            } catch (e: FlashException) {
                ui.post { finishFlash("😕 ${e.message}") }
            } catch (e: Exception) {
                ui.post { finishFlash("😕 Something went wrong. Unplug the Pico, plug it back in, and try again.") }
            }
        }
    }

    private fun waitForBootsel() {
        setBusy(false)
        waitingForBootsel = true
        say("⏰ Waking up your Pico… one moment!")
        ui.postDelayed({
            if (waitingForBootsel) {
                waitingForBootsel = false
                say(
                    "😴 Your Pico didn't wake up. Unplug it, hold the BOOTSEL button, " +
                        "plug it back in, and press FLASH again.",
                )
            }
        }, 10_000)
    }

    private fun doFlash(device: UsbDevice, plan: FlashPlan) {
        setBusy(true)
        say("⚡ Flashing… don't unplug the Pico!")
        thread {
            try {
                val session = PicoUsb.open(usb, device, plan.chip)
                try {
                    session.picoboot.flash(plan) { done, total ->
                        ui.post {
                            progress.max = total
                            progress.progress = done
                            val pct = if (total == 0) 100 else done * 100 / total
                            message.text = "⚡ Flashing… $pct%\nDon't unplug the Pico!"
                        }
                    }
                } finally {
                    session.close()
                }
                ui.post {
                    progress.progress = progress.max
                    finishFlash("🎉 All done! Your ${plan.chip.nickname} is running your program now!")
                }
            } catch (e: FlashException) {
                ui.post { finishFlash("😕 ${e.message}") }
            } catch (e: Exception) {
                ui.post { finishFlash("😕 Something went wrong. Unplug the Pico, plug it back in, and try again.") }
            }
        }
    }

    private fun finishFlash(text: String) {
        lastFlashEnded = SystemClock.elapsedRealtime()
        setBusy(false)
        say(text)
        refreshPico()
    }

    // ---- Screen --------------------------------------------------------------

    private fun say(text: String) {
        message.text = text
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
        updateButton()
    }

    private fun updateButton() {
        val ready = !busy && fileOk
        flashButton.isEnabled = !busy
        flashButton.alpha = if (ready) 1f else 0.5f
        flashButton.text = if (busy) "⏳ Working…" else "⚡ FLASH IT! ⚡"
    }

    private fun confirm(text: String, answer: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("🤔 Hmm, are you sure?")
            .setMessage(text)
            .setPositiveButton("Flash anyway") { _, _ -> answer(true) }
            .setNegativeButton("Stop") { _, _ -> answer(false) }
            .setOnCancelListener { answer(false) }
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("❓ How to use Pico Flasher")
            .setMessage(
                "1️⃣  Tap “Pick a file” and choose your program. " +
                    "UF2 files work best (they end in .uf2).\n\n" +
                    "2️⃣  Plug the Pico into your phone. You need a cable that fits both. " +
                    "The Pico 2 has a small “micro USB” plug, so use a USB-C to micro-USB cable, " +
                    "or an adapter.\n\n" +
                    "     Hold the white BOOTSEL button on the Pico while you plug it in, then let go.\n\n" +
                    "3️⃣  If your phone asks “Open Pico Flasher?”, tap OK. " +
                    "(Tick “Always” so it doesn't ask again.)\n\n" +
                    "4️⃣  Press ⚡ FLASH IT! and wait for 🎉.\n\n" +
                    "✨ With “Flash by itself” turned on, just plug in the Pico while holding BOOTSEL. " +
                    "The program goes on by itself!\n\n" +
                    "Works with Pico 2, Pico 2 W, Pico and Pico W.",
            )
            .setPositiveButton("Got it! 👍", null)
            .show()
    }

    private fun buildUi() {
        val dark = Color.parseColor("#212121")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FFF8E1"))
            addView(root)
        }
        window.statusBarColor = Color.parseColor("#2E7D32")

        root.addView(TextView(this).apply {
            text = "🚀 Pico Flasher"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#2E7D32"))
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Put programs on your Raspberry Pi Pico 2!"
            textSize = 17f
            setTextColor(dark)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        })

        // Step 1
        val c1 = card("#E3F2FD")
        c1.addView(heading("1️⃣  Choose a program"))
        fileText = body("📂 Pick the program you want to put on your Pico.")
        c1.addView(fileText)
        c1.addView(bigButton("📂 Pick a file", "#1E88E5", 22f) { pickFile() })
        root.addView(c1)

        // Step 2
        val c2 = card("#E8F5E9")
        c2.addView(heading("2️⃣  Plug in your Pico"))
        picoText = body("")
        c2.addView(picoText)
        root.addView(c2)

        // Step 3
        val c3 = card("#FFF3E0")
        c3.addView(heading("3️⃣  Flash it!"))
        flashButton = bigButton("⚡ FLASH IT! ⚡", "#43A047", 30f) { startFlash() }
        c3.addView(flashButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
            minimumHeight = dp(16)
        }
        c3.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)).apply {
            topMargin = dp(12)
        })
        message = body("").apply { textSize = 20f }
        c3.addView(message)
        root.addView(c3)

        autoSwitch = Switch(this).apply {
            text = "✨ Flash by itself when I plug in the Pico"
            textSize = 18f
            setTextColor(dark)
            isChecked = prefs.getBoolean(PREF_AUTO, true)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(PREF_AUTO, on).apply() }
        }
        root.addView(autoSwitch)
        root.addView(bigButton("❓ Help", "#757575", 20f) { showHelp() })

        setContentView(scroll)
        updateButton()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: String) = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(Color.parseColor(color))
    }

    private fun card(color: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(16) }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#212121"))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.parseColor("#212121"))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun bigButton(text: String, color: String, size: Float, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = size
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(color)
        setPadding(dp(16), dp(18), dp(16), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "io.github.malhi391.pico2flasher.USB_PERMISSION"
        private const val REQUEST_PICK = 1
        private const val PREF_URI = "file"
        private const val PREF_AUTO = "auto"
    }
}
