package com.sergei.hearlink

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import com.sergei.hearlink.audio.Processor
import com.sergei.hearlink.audio.Settings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Microphone in, headphones out, as fast as Android allows. Nothing is stored.
 * Refuses to play through the phone's own speaker, since that would squeal.
 */
object Relay {
    interface Listener {
        fun onRelayChanged()
    }

    val settings = Settings()

    @Volatile var running = false
        private set
    @Volatile var outputName: String? = null
        private set
    /** Why it stopped by itself, shown on screen until the next start. */
    @Volatile var message: String? = null
        private set
    @Volatile var processor: Processor? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var thread: Thread? = null
    private var am: AudioManager? = null
    @Volatile private var stopping = false

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun changed() = main.post { for (l in listeners) l.onRelayChanged() }

    /** Headphones of any kind that are connected now, Bluetooth first. */
    fun headphones(am: AudioManager): AudioDeviceInfo? {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (want in HEADPHONE_TYPES) outs.firstOrNull { it.type == want }?.let { return it }
        return null
    }

    fun start(context: Context, cleanMic: Boolean): Boolean {
        if (running) return true
        val a = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am = a
        val hp = headphones(a)
        if (hp == null) {
            message = "Connect the headphones first. Without them the sound would come out of the phone and squeal."
            changed()
            return false
        }
        message = null
        outputName = hp.productName?.toString()?.takeIf { it.isNotBlank() } ?: "headphones"
        running = true
        stopping = false
        a.registerAudioDeviceCallback(deviceWatch, main)
        thread = Thread({ run(hp, cleanMic) }, "HearLink-relay").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        changed()
        return true
    }

    fun stop(reason: String? = null) {
        if (!running) return
        if (reason != null) message = reason
        stopping = true
        try { am?.unregisterAudioDeviceCallback(deviceWatch) } catch (e: Exception) { }
        val t = thread
        thread = null
        if (t != null && t != Thread.currentThread()) try { t.join(1500) } catch (e: InterruptedException) { }
        running = false
        processor = null
        changed()
    }

    private val deviceWatch = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            val a = am ?: return
            if (running && headphones(a) == null) stop("The headphones were disconnected, so the sound was stopped.")
        }
    }

    private fun run(hp: AudioDeviceInfo, cleanMic: Boolean) {
        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        var ns: NoiseSuppressor? = null
        var problem: String? = null
        try {
            var fs = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            if (fs <= 0) fs = 48000
            val recMin = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (recMin <= 0) throw IllegalStateException("The microphone can't record at $fs Hz")
            rec = AudioRecord.Builder()
                .setAudioSource(if (cleanMic) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(fs).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                )
                .setBufferSizeInBytes(maxOf(recMin, BLOCK * 4 * 4))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("The microphone is in use by another app")
            if (cleanMic && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
            }

            val trMin = AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(fs).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                )
                .setBufferSizeInBytes(maxOf(trMin, BLOCK * 2 * 4 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track.setPreferredDevice(hp)

            val p = Processor(fs, settings)
            processor = p
            val inBuf = FloatArray(BLOCK)
            val outBuf = FloatArray(BLOCK * 2)
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("The microphone is in use by another app")
            track.play()
            var failures = 0
            while (!stopping) {
                val n = rec.read(inBuf, 0, BLOCK, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (++failures > 50) throw IllegalStateException("The microphone stopped (a phone call?)")
                    Thread.sleep(10)
                    continue
                }
                failures = 0
                p.process(inBuf, outBuf, n)
                // Never wait for the headphones: if their buffer is full this block is dropped,
                // so the delay can't keep growing.
                track.write(outBuf, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING)
            }
        } catch (e: SecurityException) {
            problem = "HearLink needs permission to use the microphone."
        } catch (e: Exception) {
            problem = e.message ?: e.javaClass.simpleName
        } finally {
            try { ns?.release() } catch (e: Exception) { }
            try { rec?.stop() } catch (e: Exception) { }
            try { rec?.release() } catch (e: Exception) { }
            try { track?.pause(); track?.flush() } catch (e: Exception) { }
            try { track?.release() } catch (e: Exception) { }
        }
        if (!stopping) main.post { stop(problem ?: "The sound stopped.") }
    }

    private const val BLOCK = 192

    private val HEADPHONE_TYPES = intArrayOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        26, // TYPE_BLE_HEADSET (Android 12+)
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )
}
