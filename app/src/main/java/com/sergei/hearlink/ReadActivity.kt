package com.sergei.hearlink

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

/**
 * Shows the text found in a photo in big letters and reads it aloud through the headphones.
 * Recognition runs on the phone itself; nothing is sent anywhere.
 */
class ReadActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var textView: TextView
    private lateinit var playButton: TextView
    private lateinit var voiceButton: TextView
    private lateinit var rateView: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var text = ""
    private var speaking = false
    private var wantRead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        build()
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                setVoice()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = runOnUiThread { if (id == "last") done() }
                    @Deprecated("old API") override fun onError(id: String?) = runOnUiThread { done() }
                })
                if (wantRead) speak()
            } else {
                textView.post { status("This phone has no reading voice. Install \"Speech Services by Google\" from the Play Store.") }
            }
        }
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        status("Looking for text…")
        try {
            val image = InputImage.fromFilePath(this, uri)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                .addOnSuccessListener { result ->
                    text = result.text.trim()
                    if (text.isEmpty()) {
                        status("No text found. Try again closer, straight on and with more light.")
                    } else {
                        textView.text = text
                        textView.setTextColor(Ui.TEXT)
                        speak()
                    }
                }
                .addOnFailureListener { e -> status("Could not read the picture: ${e.message}") }
        } catch (e: Exception) {
            status("Could not open the picture: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopSpeaking()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun status(s: String) {
        textView.text = s
        textView.setTextColor(Ui.DIM)
    }

    private fun setVoice() {
        val t = tts ?: return
        val loc = if (prefs.voice.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(prefs.voice)
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.getDefault())
        t.setSpeechRate(prefs.readRate / 100f)
    }

    private fun speak() {
        if (text.isEmpty()) return
        if (!ttsReady) {
            wantRead = true
            return
        }
        wantRead = false
        // The microphone sound pauses while reading, so the voice is clear.
        Relay.settings.muted = true
        speaking = true
        setVoice()
        val t = tts!!
        t.stop()
        // Long texts go in paragraph-sized pieces; Android refuses very long single requests.
        val parts = text.split(Regex("\\n\\s*\\n")).flatMap { it.chunked(3000) }.filter { it.isNotBlank() }
        parts.forEachIndexed { i, p ->
            t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) "last" else "p$i")
        }
        playButton.text = "■  STOP"
    }

    private fun stopSpeaking() {
        tts?.stop()
        done()
    }

    private fun done() {
        if (tts?.isSpeaking == true) return
        speaking = false
        Relay.settings.muted = false
        if (::playButton.isInitialized) playButton.text = "▶  READ ALOUD"
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        textView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.readSize.toFloat())
            setTextColor(Ui.TEXT)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        playButton = big("▶  READ ALOUD", Ui.GREEN) { if (speaking) stopSpeaking() else speak() }
        root.addView(playButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(12) })

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(small("A−") { size(-4) }, weight())
        row1.addView(small("A+") { size(4) }, weight())
        row1.addView(small("Slower") { rate(-15) }, weight())
        row1.addView(small("Faster") { rate(15) }, weight())
        root.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        voiceButton = small(voiceLabel()) { nextVoice() }
        row2.addView(voiceButton, weight())
        rateView = TextView(this).apply {
            setTextColor(Ui.DIM)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        row2.addView(rateView, weight())
        row2.addView(small("Close") { finish() }, weight())
        root.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) })
        showRate()
        setContentView(root)
    }

    private fun size(d: Int) {
        prefs.readSize = prefs.readSize + d
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.readSize.toFloat())
    }

    private fun rate(d: Int) {
        prefs.readRate = prefs.readRate + d
        showRate()
        if (speaking) speak()
    }

    private fun showRate() {
        rateView.text = "Speed ${prefs.readRate}%"
    }

    private fun voiceLabel() = "Voice: " + (VOICES.firstOrNull { it.first == prefs.voice }?.second ?: "Phone")

    private fun nextVoice() {
        val i = VOICES.indexOfFirst { it.first == prefs.voice }
        prefs.voice = VOICES[(i + 1) % VOICES.size].first
        voiceButton.text = voiceLabel()
        if (speaking) speak()
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        leftMargin = dp(3); rightMargin = dp(3)
    }

    private fun big(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Ui.BG)
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun small(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Ui.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = GradientDrawable().apply { setColor(Ui.PANEL); cornerRadius = dp(12).toFloat() }
        setOnClickListener { onClick() }
        textAlignment = View.TEXT_ALIGNMENT_CENTER
    }

    private fun dp(v: Int) = Ui.dp(this, v.toFloat())

    companion object {
        private val VOICES = listOf("" to "Phone", "nl" to "Dutch", "en" to "English", "id" to "Indonesian", "de" to "German")
    }
}
