package com.sergei.hearlink.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun dbToLin(db: Float): Float = 10f.pow(db / 20f)
fun linToDb(x: Float): Float = if (x <= 1e-9f) -180f else 20f * log10(x)

/** One-pole smoothing coefficient for a time constant of [ms] at [fs]. */
fun coef(fs: Int, ms: Float): Float = exp(-1.0 / (fs * ms / 1000.0)).toFloat()

/** A standard second-order filter (RBJ cookbook), transposed direct form II. */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0f; z2 = 0f }

    private fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        this.b0 = (b0 / a0).toFloat(); this.b1 = (b1 / a0).toFloat(); this.b2 = (b2 / a0).toFloat()
        this.a1 = (a1 / a0).toFloat(); this.a2 = (a2 / a0).toFloat()
    }

    fun peaking(fs: Int, f: Double, q: Double, db: Double) {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
        set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
    }

    fun lowShelf(fs: Int, f: Double, db: Double) {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val s = 2 * sqrt(a) * al
        set(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
            (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s)
    }

    fun highShelf(fs: Int, f: Double, db: Double) {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val s = 2 * sqrt(a) * al
        set(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
            (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
    }

    fun highPass(fs: Int, f: Double, q: Double) {
        val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
    }
}

/** Five tone controls: a low shelf, three peaks and a high shelf. */
class Equalizer(private val fs: Int) {
    private val f = Array(BANDS.size) { Biquad() }

    fun set(db: FloatArray) {
        f[0].lowShelf(fs, BANDS[0], db[0].toDouble())
        for (i in 1..3) f[i].peaking(fs, BANDS[i], 1.0, db[i].toDouble())
        f[4].highShelf(fs, min(BANDS[4], fs * 0.45), db[4].toDouble())
    }

    fun process(x: Float): Float {
        var y = x
        for (b in f) y = b.process(y)
        return y
    }

    companion object {
        val BANDS = doubleArrayOf(250.0, 500.0, 1000.0, 2000.0, 4000.0)
        val NAMES = arrayOf("Low", "500 Hz", "1 kHz", "2 kHz", "High")
    }
}

/**
 * Turns down what sits near the room's own noise level: the hum and the echo tail between
 * words. It learns the noise level by itself, so there is nothing to set.
 */
class NoiseGate(fs: Int) {
    private val envUp = coef(fs, 1f)
    private val envDown = coef(fs, 60f)
    private val open = coef(fs, 3f)
    private val close = coef(fs, 80f)
    private val floorRise = 6f / fs // dB per sample, so the noise estimate creeps up 6 dB a second
    private val floorFall = 40f / fs
    private var env = 0f
    private var floorDb = -50f
    private var g = 1f

    /** The level it currently treats as background noise. */
    val noiseDb: Float get() = floorDb

    fun process(x: Float): Float {
        val a = abs(x)
        env = if (a > env) envUp * env + (1 - envUp) * a else envDown * env + (1 - envDown) * a
        val e = linToDb(env)
        // Falls quickly (a pause in talking), rises slowly (talking is not noise).
        floorDb = if (e < floorDb) max(e, floorDb - floorFall) else min(floorDb + floorRise, e)
        floorDb = floorDb.coerceIn(-80f, -20f)
        val over = e - (floorDb + 8f)
        val targetDb = if (over >= 0f) 0f else max(-18f, over * 1.5f)
        val t = dbToLin(targetDb)
        g = if (t > g) open * g + (1 - open) * t else close * g + (1 - close) * t
        return x * g
    }
}

/** Brings soft sounds up and keeps loud ones from jumping out: easier to follow speech. */
class Compressor(fs: Int, private val thresholdDb: Float = -38f, private val ratio: Float = 3f, private val makeupDb: Float = 12f) {
    private val att = coef(fs, 5f)
    private val rel = coef(fs, 150f)
    private var env = 0f

    fun process(x: Float): Float {
        val p = x * x
        env = if (p > env) att * env + (1 - att) * p else rel * env + (1 - rel) * p
        val e = 10f * log10(max(env, 1e-12f))
        val cut = if (e > thresholdDb) (e - thresholdDb) * (1 - 1 / ratio) else 0f
        return x * dbToLin(makeupDb - cut)
    }
}

/** Never lets the output past [ceilingDb]: protects her ears from bangs and clatter. */
class Limiter(fs: Int, ceilingDb: Float = -3f) {
    private val ceiling = dbToLin(ceilingDb)
    private val rel = coef(fs, 60f)
    private var env = 0f

    /** Current gain, 1 = not limiting. */
    var gain = 1f
        private set

    fun process(x: Float): Float {
        val a = abs(x)
        env = if (a > env) a else rel * env + (1 - rel) * a
        gain = if (env > ceiling) ceiling / env else 1f
        val y = x * gain
        return y.coerceIn(-ceiling, ceiling)
    }
}

/**
 * Feedback manager. Feedback (the squeal when the headphones' sound reaches the microphone) is
 * stopped by turning the volume down 6 dB at once, at the first sign of it: a whistle found by
 * [HowlSuppressor], or sound that keeps hitting the limiter. The volume then creeps back only
 * very slowly (1 dB every few seconds of calm), so it settles just below the point where it squeals
 * instead of squealing again and again.
 */
class FeedbackGuard(private val fs: Int) {
    private var held = 0
    private val holdLimit = fs / 4
    private var calm = 0
    private val calmBefore = fs * 6
    private val calmStep = fs * 4
    /** Where it squealed last time, minus a safety margin: the volume stays below this for a while. */
    private var ceilingDb = 0f
    private var ceilingAge = 0
    private val ceilingRelax = fs * 60

    /** dB taken off right now (0 or negative). */
    var cutDb = 0f
        private set

    /** How often it had to step in since starting. */
    var events = 0
        private set

    /** Why it last stepped in (for testing). */
    var reasons = ""
    fun event(why: String = "") {
        reasons += why + " "
        ceilingDb = min(ceilingDb, cutDb - 3f)
        ceilingAge = 0
        cutDb = max(-40f, cutDb - 6f)
        calm = 0
        held = 0
        events++
    }

    /** Called once per sample with the limiter's gain. */
    fun update(limiterGain: Float) {
        // Speech only touches the limit now and then; feedback leans on it without a break.
        if (limiterGain < 0.8f) {
            if (++held > holdLimit) event("limit")
        } else {
            held = 0
        }
        if (++ceilingAge >= ceilingRelax && ceilingDb < 0f) {
            ceilingDb = min(0f, ceilingDb + 1f) // the room may have changed: allow a little more
            ceilingAge = 0
        }
        if (++calm >= calmBefore && cutDb < ceilingDb) {
            cutDb = min(ceilingDb, cutDb + 1f)
            calm = calmBefore - calmStep
        }
    }

    // ---- no pauses: speech and TV always dip between words, feedback doesn't ----
    private val envAtt = coef(fs, 5f)
    private val envRel = coef(fs, 30f)
    private var env = 0f
    private val blockLen = fs / 20 // 50 ms
    private var blockPos = 0
    private var blockMin = Float.MAX_VALUE
    private var blockMax = 0f
    private val mins = FloatArray(40) // 2 seconds of blocks
    private val maxs = FloatArray(40)
    private var filled = 0
    private var slot = 0

    /** Called once per sample with the outgoing sound. */
    fun level(x: Float) {
        val a = kotlin.math.abs(x)
        env = if (a > env) envAtt * env + (1 - envAtt) * a else envRel * env + (1 - envRel) * a
        if (env < blockMin) blockMin = env
        if (env > blockMax) blockMax = env
        if (++blockPos < blockLen) return
        mins[slot] = blockMin
        maxs[slot] = blockMax
        slot = (slot + 1) % mins.size
        if (filled < mins.size) filled++
        blockPos = 0
        blockMin = Float.MAX_VALUE
        blockMax = 0f
        if (filled < mins.size) return
        var lo = Float.MAX_VALUE
        var hiMin = Float.MAX_VALUE
        var hi = 0f
        for (i in mins.indices) {
            lo = min(lo, mins[i])
            hi = max(hi, maxs[i])
            hiMin = min(hiMin, maxs[i])
        }
        // Loud the whole time (every 50 ms above -30 dBFS) and never more than 12 dB quieter.
        if (linToDb(hiMin) > -30f && linToDb(lo) > linToDb(hi) - 12f) {
            event("nopause")
            filled = 0
        }
    }

    fun reset() { held = 0; calm = 0; cutDb = 0f; ceilingDb = 0f; ceilingAge = 0; events = 0; filled = 0 }
}

/** What the screen can change while sound is running. */
class Settings {
    @Volatile var volumeDb = 6f
    @Volatile var eqDb = FloatArray(5)
    @Volatile var gate = true
    @Volatile var compress = true
    @Volatile var guard = true
    /** Bumped by the "forget squeal tones" button. */
    @Volatile var resetHowl = 0
    /** -1 = left only … 0 = middle … 1 = right only. */
    @Volatile var balance = 0f
    /** Muted while the phone is reading text aloud. */
    @Volatile var muted = false
    @Volatile var version = 0

    fun changed() { version++ }
}

/** The whole chain for one mono stream in, stereo out. */
class Processor(private val fs: Int, private val s: Settings) {
    private val rumble = Biquad().apply { highPass(fs, 120.0, 0.707) }
    private val eq = Equalizer(fs)
    private val gate = NoiseGate(fs)
    private val comp = Compressor(fs)
    private val guard = FeedbackGuard(fs)
    private val howl = HowlSuppressor(fs)
    private val shifter = FrequencyShifter(fs)
    private var howlReset = s.resetHowl
    private var seenHowls = 0
    private val echo = EchoDetector(fs)
    /** Measured delay of the loop through the headphones and back, in ms (0 = not measured). */
    val loopDelayMs: Int get() = echo.delayMs
    /** For testing without the frequency shift. */
    var shiftOn = true
    private val limiter = Limiter(fs)
    private var seen = -1
    private var gain = 0f
    private var target = 1f
    private var left = 1f
    private var right = 1f
    private val smooth = coef(fs, 20f)

    @Volatile var inPeak = 0f
    @Volatile var outPeak = 0f
    val guardCutDb: Float get() = guard.cutDb
    val guardReasons: String get() = guard.reasons
    /** Squeal tones found and cut, in Hz. */
    val howlTones: List<Int> get() = howl.found

    private fun refresh() {
        seen = s.version
        eq.set(s.eqDb)
        if (!s.guard) { guard.reset(); howl.reset(); echo.reset() }
        if (s.resetHowl != howlReset) { howlReset = s.resetHowl; howl.reset(); guard.reset() }
        val b = s.balance.coerceIn(-1f, 1f)
        left = if (b > 0f) 1f - b else 1f
        right = if (b < 0f) 1f + b else 1f
    }

    /** Reads [n] mono samples from [input], writes [n] stereo frames to [out]. */
    fun process(input: FloatArray, out: FloatArray, n: Int) {
        if (s.version != seen) refresh()
        val gateOn = s.gate
        val compOn = s.compress
        val guardOn = s.guard
        val vol = s.volumeDb
        var ip = 0f
        var op = 0f
        for (i in 0 until n) {
            var x = input[i]
            val ax = abs(x); if (ax > ip) ip = ax
            x = rumble.process(x)
            x = eq.process(x)
            if (gateOn) x = gate.process(x)
            if (compOn) x = comp.process(x)
            target = if (s.muted) 0f else dbToLin(vol + (if (guardOn) guard.cutDb else 0f))
            gain = smooth * gain + (1 - smooth) * target
            x *= gain
            if (guardOn) {
                // Anti-squeal: shift the pitch a few Hz, cut any tone that still builds up,
                // and turn the whole sound down at once when that happens.
                if (shiftOn) x = shifter.process(x)
                x = howl.process(x)
                if (howl.detections != seenHowls) {
                    seenHowls = howl.detections
                    guard.event("tone")
                }
            }
            x = limiter.process(x)
            if (guardOn) {
                guard.update(limiter.gain)
                guard.level(x)
                if (echo.push(input[i], x)) guard.event("echo")
            }
            val ay = abs(x); if (ay > op) op = ay
            out[2 * i] = x * left
            out[2 * i + 1] = x * right
        }
        inPeak = max(inPeak, ip)
        outPeak = max(outPeak, op)
    }
}
