package com.sergei.hearlink

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.sergei.hearlink.audio.Equalizer
import com.sergei.hearlink.audio.linToDb

/** One screen: on/off, volume, tone, clear-sound options and reading text from a photo. */
class MainActivity : Activity(), Relay.Listener {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var startWhenAllowed = false

    private lateinit var statusView: TextView
    private lateinit var button: TextView
    private lateinit var messageView: TextView
    private lateinit var micBar: ProgressBar
    private lateinit var outBar: ProgressBar
    private lateinit var guardView: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var balanceBar: SeekBar
    private lateinit var balanceValue: TextView
    private val eqBars = ArrayList<SeekBar>()
    private val eqValues = ArrayList<TextView>()

    private val tick = object : Runnable {
        override fun run() {
            val p = Relay.processor
            if (p != null) {
                micBar.progress = level(p.inPeak)
                outBar.progress = level(p.outPeak)
                p.inPeak = 0f
                p.outPeak = 0f
                val cut = p.guardCutDb
                val tones = p.howlTones
                val parts = ArrayList<String>()
                if (tones.isNotEmpty()) parts.add("Squeal stopped at " + tones.joinToString(", ") { if (it >= 1000) String.format(java.util.Locale.US, "%.1f kHz", it / 1000f) else "$it Hz" } + ".")
                if (cut < -0.5f) parts.add(String.format(java.util.Locale.US, "Volume held back %.0f dB to stop echo and squeal; it comes back slowly. Keep the phone further from the headphones.", -cut))
                if (p.loopDelayMs > 0) parts.add("Own sound came back after ${p.loopDelayMs} ms (Bluetooth delay).")
                guardView.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
                guardView.text = parts.joinToString(" ")
            } else {
                micBar.progress = 0
                outBar.progress = 0
                guardView.visibility = View.GONE
            }
            handler.postDelayed(this, 60)
        }
    }

    /** 0..100 for −60..0 dB. */
    private fun level(peak: Float) = ((linToDb(peak) + 60f) / 60f * 100f).toInt().coerceIn(0, 100)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.applyTo(Relay.settings)
        build()
        Relay.addListener(this)
    }

    override fun onDestroy() {
        Relay.removeListener(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onRelayChanged() = refresh()

    // ---- on / off ----

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun onButton() {
        if (Relay.running) {
            Relay.stop()
            return
        }
        if (!hasMic()) {
            startWhenAllowed = true
            val wanted = arrayListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) || !asked) {
                asked = true
                requestPermissions(wanted.toTypedArray(), 1)
            } else {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (e: Exception) { }
            }
            return
        }
        start()
    }

    private var asked = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (startWhenAllowed && hasMic()) start()
        startWhenAllowed = false
    }

    private fun start() {
        prefs.applyTo(Relay.settings)
        Relay.settings.muted = false
        if (Relay.start(this, prefs.cleanMic)) RelayService.start(this)
    }

    private fun refresh() {
        val on = Relay.running
        button.text = if (on) "ON\ntap to stop" else "OFF\ntap to start"
        (button.background as GradientDrawable).setColor(if (on) Ui.GREEN else Ui.PANEL)
        button.setTextColor(if (on) Ui.BG else Ui.TEXT)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val hp = Relay.headphones(am)
        statusView.text = when {
            on -> "Listening → ${Relay.outputName}"
            hp != null -> "Ready · ${hp.productName}"
            else -> "No headphones connected"
        }
        statusView.setTextColor(if (on) Ui.GREEN else if (hp != null) Ui.TEXT else Ui.AMBER)
        val m = Relay.message
        messageView.visibility = if (m != null && !on) View.VISIBLE else View.GONE
        messageView.text = m ?: ""
    }

    // ---- reading text ----

    private fun takePhoto() {
        ShotProvider.shotFile(cacheDir).apply { parentFile?.mkdirs(); delete() }
        val uri = ShotProvider.URI
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("photo", uri)
        try {
            startActivityForResult(i, REQ_CAMERA)
        } catch (e: Exception) {
            toast("No camera app found.")
        }
    }

    private fun pickPicture() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK)
        } catch (e: Exception) {
            toast("No app found to choose a picture.")
        }
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = when (requestCode) {
            REQ_CAMERA -> if (ShotProvider.shotFile(cacheDir).length() > 0) ShotProvider.URI else null
            REQ_PICK -> data?.data
            else -> null
        } ?: return
        startActivity(Intent(this, ReadActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---- the screen ----

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        root.addView(label("HearLink", 26f, Ui.TEXT).apply { typeface = Typeface.DEFAULT_BOLD })
        statusView = label("", 16f, Ui.TEXT)
        root.addView(statusView, matchWrap().apply { topMargin = dp(4) })

        button = label("", 22f, Ui.TEXT).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.PANEL) }
            setOnClickListener { onButton() }
        }
        root.addView(button, LinearLayout.LayoutParams(dp(180), dp(180)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
        })
        messageView = label("", 15f, Ui.AMBER).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(messageView, matchWrap().apply { topMargin = dp(10) })

        micBar = meter(); outBar = meter()
        root.addView(label("Microphone", 12f, Ui.DIM), matchWrap().apply { topMargin = dp(16) })
        root.addView(micBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        root.addView(label("To headphones", 12f, Ui.DIM), matchWrap().apply { topMargin = dp(6) })
        root.addView(outBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        guardView = label("", 14f, Ui.AMBER).apply { visibility = View.GONE }
        root.addView(guardView, matchWrap().apply { topMargin = dp(6) })

        // Volume and balance.
        root.addView(heading("VOLUME"), matchWrap().apply { topMargin = dp(24) })
        volumeValue = label("", 15f, Ui.TEXT)
        volumeBar = slider(Prefs.VOLUME_MAX, prefs.volumeDb) { v ->
            prefs.volumeDb = v
            prefs.applyTo(Relay.settings)
            showValues()
        }
        root.addView(sliderRow(volumeBar, volumeValue))
        root.addView(label("The phone's volume buttons also change the headphone volume.", 13f, Ui.DIM))

        root.addView(heading("LEFT / RIGHT"), matchWrap().apply { topMargin = dp(18) })
        balanceValue = label("", 15f, Ui.TEXT)
        balanceBar = slider(20, prefs.balance + 10) { v ->
            prefs.balance = v - 10
            prefs.applyTo(Relay.settings)
            showValues()
        }
        root.addView(sliderRow(balanceBar, balanceValue))

        // Tone.
        root.addView(heading("TONE"), matchWrap().apply { topMargin = dp(24) })
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, values) in Prefs.PRESETS) {
            presets.addView(chip(name) { setEq(values) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        }
        root.addView(presets, matchWrap().apply { bottomMargin = dp(6) })
        for (i in 0 until 5) {
            val value = label("", 15f, Ui.TEXT)
            val bar = slider(Prefs.EQ_RANGE * 2, prefs.eq[i] + Prefs.EQ_RANGE) { v ->
                val e = prefs.eq
                e[i] = v - Prefs.EQ_RANGE
                prefs.eq = e
                prefs.applyTo(Relay.settings)
                showValues()
            }
            eqBars.add(bar)
            eqValues.add(value)
            root.addView(label(Equalizer.NAMES[i], 13f, Ui.DIM), matchWrap().apply { topMargin = dp(4) })
            root.addView(sliderRow(bar, value))
        }
        root.addView(label("Voices are clearest with 1–4 kHz up and Low down. Try \"Speech\" first.", 13f, Ui.DIM))

        // Her own settings.
        root.addView(heading("MY SETTINGS"), matchWrap().apply { topMargin = dp(24) })
        val mine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (slot in 1..2) {
            mine.addView(chip("Load $slot") {
                if (prefs.loadMine(slot)) { syncBars(); prefs.applyTo(Relay.settings); toast("Settings $slot loaded") }
                else toast("Nothing saved in $slot yet. Tap Save $slot first.")
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
            mine.addView(chip("Save $slot") { prefs.saveMine(slot); toast("Volume, left/right and tone saved as $slot") },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        }
        root.addView(mine, matchWrap())
        root.addView(label("For example 1 for talking, 2 for TV.", 13f, Ui.DIM), matchWrap().apply { topMargin = dp(4) })

        // Clear sound.
        root.addView(heading("CLEAR SOUND"), matchWrap().apply { topMargin = dp(24) })
        root.addView(switch("Less room echo and background hum", prefs.gate) { prefs.gate = it; prefs.applyTo(Relay.settings) })
        root.addView(switch("Lift soft voices", prefs.compress) { prefs.compress = it; prefs.applyTo(Relay.settings) })
        root.addView(switch("Anti-squeal (stops the rising whistle)", prefs.guard) { prefs.guard = it; prefs.applyTo(Relay.settings) })
        root.addView(label("Finds a whistling tone as soon as it starts and cuts just that tone. " +
            "After moving to another room, tap below so it starts fresh.", 13f, Ui.DIM))
        root.addView(chip("Forget squeal tones") {
            Relay.settings.resetHowl++
            toast("Squeal tones forgotten")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6); bottomMargin = dp(6) })
        root.addView(switch("Phone's own noise filter", prefs.cleanMic) {
            prefs.cleanMic = it
            if (Relay.running) { Relay.stop(); start() }
        })
        root.addView(label("Bangs and clatter are always held back to protect her ears.", 13f, Ui.DIM))

        // Read text.
        root.addView(heading("READ TEXT"), matchWrap().apply { topMargin = dp(24) })
        val read = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        read.addView(chip("📷  Take photo") { takePhoto() }, LinearLayout.LayoutParams(0, dp(60), 1f).apply { rightMargin = dp(6) })
        read.addView(chip("🖼  Choose picture") { pickPicture() }, LinearLayout.LayoutParams(0, dp(60), 1f))
        root.addView(read, matchWrap())
        root.addView(label("Letters, signs, menus: the text is shown big and read aloud in the headphones.", 13f, Ui.DIM),
            matchWrap().apply { topMargin = dp(4) })

        root.addView(heading("TIPS"), matchWrap().apply { topMargin = dp(24) })
        root.addView(label(
            "• Point the bottom of the phone (the microphone) at the person talking, or lay it on the table between you.\n" +
                "• Bluetooth always adds a small delay. That is the headphones, not the app.\n" +
                "• Squealing comes from the headphones' sound reaching the microphone. Keep the phone at least an arm's length from her head, " +
                "microphone pointing away from her.\n" +
                "• \"Extra clear\" and a lot of High make squealing more likely; use a little less if it keeps happening.",
            14f, Ui.DIM))

        showValues()
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun setEq(values: IntArray) {
        prefs.eq = values.copyOf()
        prefs.applyTo(Relay.settings)
        syncBars()
    }

    private fun syncBars() {
        volumeBar.progress = prefs.volumeDb
        balanceBar.progress = prefs.balance + 10
        val e = prefs.eq
        for (i in 0 until 5) eqBars[i].progress = e[i] + Prefs.EQ_RANGE
        showValues()
    }

    private fun showValues() {
        volumeValue.text = "+${prefs.volumeDb} dB"
        val b = prefs.balance
        balanceValue.text = when { b == 0 -> "Middle"; b < 0 -> "Left ${-b * 10}%"; else -> "Right ${b * 10}%" }
        val e = prefs.eq
        for (i in 0 until 5) eqValues[i].text = (if (e[i] > 0) "+" else "") + "${e[i]} dB"
    }

    // ---- small builders ----

    private fun slider(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun sliderRow(bar: SeekBar, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        value.gravity = Gravity.END
        addView(value, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun meter() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }

    private fun chip(text: String, onClick: () -> Unit) = label(text, 15f, Ui.TEXT).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Ui.PANEL); cornerRadius = dp(12).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun switch(text: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        this.text = text
        setTextColor(Ui.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        minimumHeight = dp(52)
        isChecked = on
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun heading(text: String) = label(text, 12f, Ui.DIM).apply {
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.12f
        setPadding(0, 0, 0, dp(4))
    }

    private fun label(text: String, sizeSp: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int) = Ui.dp(this, v.toFloat())

    companion object {
        private const val REQ_CAMERA = 10
        private const val REQ_PICK = 11
    }
}
