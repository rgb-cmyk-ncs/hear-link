package com.sergei.hearlink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class DspTests {
    private val fs = 48000

    private fun sine(f: Double, amp: Float, n: Int) = FloatArray(n) { i -> amp * sin(2 * PI * f * i / fs).toFloat() }

    /** Peak of the last half, after filters have settled. */
    private fun peakTail(x: FloatArray, step: Int = 1, offset: Int = 0): Float {
        var p = 0f
        var i = x.size / 2 / step * step + offset
        while (i < x.size) { p = max(p, abs(x[i])); i += step }
        return p
    }

    @Test fun peakingFilterGivesItsGainAtItsFrequency() {
        val b = Biquad().apply { peaking(fs, 1000.0, 1.0, 9.0) }
        val y = sine(1000.0, 0.1f, 48000).map { b.process(it) }.toFloatArray()
        assertEquals(9f, linToDb(peakTail(y) / 0.1f), 0.2f)
    }

    @Test fun flatEqualizerChangesNothing() {
        val eq = Equalizer(fs).apply { set(FloatArray(5)) }
        val x = sine(700.0, 0.3f, 24000)
        val y = x.map { eq.process(it) }
        for (i in x.indices) assertEquals(x[i], y[i], 1e-4f)
    }

    @Test fun trebleBoostLiftsHighsNotLows() {
        val eq = Equalizer(fs).apply { set(floatArrayOf(0f, 0f, 0f, 0f, 12f)) }
        val hi = sine(8000.0, 0.05f, 48000).map { eq.process(it) }.toFloatArray()
        val eq2 = Equalizer(fs).apply { set(floatArrayOf(0f, 0f, 0f, 0f, 12f)) }
        val lo = sine(100.0, 0.05f, 48000).map { eq2.process(it) }.toFloatArray()
        assertTrue(linToDb(peakTail(hi) / 0.05f) > 10f)
        assertTrue(abs(linToDb(peakTail(lo) / 0.05f)) < 1f)
    }

    @Test fun limiterNeverGoesPastTheCeiling() {
        val l = Limiter(fs, -3f)
        val y = sine(300.0, 4f, 48000).map { l.process(it) }
        assertTrue(y.maxOf { abs(it) } <= dbToLin(-3f) + 1e-6f)
    }

    @Test fun gateTurnsDownSteadyHum() {
        val g = NoiseGate(fs)
        val y = sine(200.0, 0.01f, fs * 6).map { g.process(it) }.toFloatArray()
        assertTrue(linToDb(peakTail(y) / 0.01f) < -8f)
    }

    @Test fun gateLetsSpeechThroughAboveTheHum() {
        val g = NoiseGate(fs)
        val hum = sine(200.0, 0.005f, fs * 4)
        hum.forEach { g.process(it) }
        val voice = sine(1000.0, 0.2f, fs / 2).map { g.process(it) }.toFloatArray()
        assertTrue(linToDb(peakTail(voice) / 0.2f) > -1f)
    }

    @Test fun compressorLiftsSoftMoreThanLoud() {
        val c1 = Compressor(fs); val c2 = Compressor(fs)
        val soft = sine(1000.0, 0.005f, 24000).map { c1.process(it) }.toFloatArray()
        val loud = sine(1000.0, 0.5f, 24000).map { c2.process(it) }.toFloatArray()
        val softGain = linToDb(peakTail(soft) / 0.005f)
        val loudGain = linToDb(peakTail(loud) / 0.5f)
        assertTrue(softGain > loudGain + 15f)
    }

    @Test fun squealGuardTurnsDownSustainedOverload() {
        val s = Settings().apply { volumeDb = 30f; gate = false; compress = false }
        val p = Processor(fs, s)
        val input = sine(2500.0, 0.3f, fs * 2)
        val out = FloatArray(input.size * 2)
        p.process(input, out, input.size)
        // Either the tone gets its own cut, or the whole sound is turned down.
        assertTrue(p.guardCutDb <= -6f || p.howlTones.isNotEmpty())
    }

    @Test fun guardStaysQuietForNormalSpeech() {
        val s = Settings().apply { volumeDb = 12f }
        val p = Processor(fs, s)
        val input = voice(fs * 20, 0.01, java.util.Random(3))
        val out = FloatArray(input.size * 2)
        p.process(input, out, input.size)
        assertTrue("cut ${p.guardCutDb} ${p.guardReasons}", p.guardCutDb >= -6f)
    }

    @Test fun balanceSendsToOneSide() {
        val s = Settings().apply { balance = -1f; gate = false; compress = false; guard = false; volumeDb = 0f; changed() }
        val p = Processor(fs, s)
        val input = sine(1000.0, 0.1f, fs)
        val out = FloatArray(input.size * 2)
        p.process(input, out, input.size)
        assertTrue(peakTail(out, 2, 0) > 0.05f)
        assertEquals(0f, peakTail(out, 2, 1), 0f)
    }

    @Test fun mutedIsSilentAndOutputNeverClips() {
        val s = Settings().apply { volumeDb = 36f; guard = false }
        val p = Processor(fs, s)
        val input = sine(1000.0, 0.9f, fs)
        val out = FloatArray(input.size * 2)
        p.process(input, out, input.size)
        assertTrue(out.maxOf { abs(it) } <= dbToLin(-3f) + 1e-6f)
        s.muted = true
        p.process(input, out, input.size)
        assertTrue(peakTail(out) < 1e-3f)
    }
}

/** Speech-like test signal: a gliding voice with harmonics, in syllables of random length. */
fun voice(len: Int, amp: Double, rnd: java.util.Random, fs: Int = 48000): FloatArray {
    var ph = 0.0
    var start = 0
    var end = 0
    var a = 1.0
    return FloatArray(len) { n ->
        if (n >= end) {
            start = n + (rnd.nextDouble() * 0.15 * fs).toInt()
            end = start + ((0.12 + rnd.nextDouble() * 0.23) * fs).toInt()
            a = 0.4 + rnd.nextDouble() * 0.8
        }
        val f0 = 160 + 50 * kotlin.math.sin(2 * kotlin.math.PI * 1.3 * n / fs) + 20 * kotlin.math.sin(2 * kotlin.math.PI * 3.1 * n / fs)
        ph += 2 * kotlin.math.PI * f0 / fs
        var v = 0.0
        for (k in 1..15) v += kotlin.math.sin(k * ph) / k
        val env = if (n < start) 0.0 else a * kotlin.math.sin(kotlin.math.PI * (n - start).toDouble() / (end - start))
        (amp * v * env).toFloat()
    }
}

class FeedbackTests {
    private val fs = 48000
    private fun sine(f: Double, amp: Float, n: Int, start: Int = 0) = FloatArray(n) { i -> amp * kotlin.math.sin(2 * kotlin.math.PI * f * (i + start) / fs).toFloat() }

    @Test fun fftFindsTheTone() {
        val n = 1024
        val re = FloatArray(n) { kotlin.math.sin(2 * kotlin.math.PI * 64 * it / n).toFloat() }
        val im = FloatArray(n)
        fft(re, im)
        val mags = FloatArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(64, mags.indices.maxByOrNull { mags[it] })
    }

    @Test fun shifterMovesTheToneUp() {
        val sh = FrequencyShifter(fs, 50.0)
        val y = sine(1000.0, 0.5f, fs).map { sh.process(it) }.toFloatArray()
        val up = toneLevelDb(y, fs / 4, 1050.0, fs)
        val orig = toneLevelDb(y, fs / 4, 1000.0, fs)
        val down = toneLevelDb(y, fs / 4, 950.0, fs)
        assertEquals(20 * kotlin.math.log10(0.5), up, 1.0)
        assertTrue(up - orig > 30)
        assertTrue(up - down > 25)
    }

    @Test fun howlIsFoundAndCut() {
        val h = HowlSuppressor(fs)
        val x = sine(2345.0, 0.3f, fs * 2)
        val y = x.map { h.process(it) }.toFloatArray()
        assertEquals(1, h.found.size)
        assertEquals(2345.0, h.found[0].toDouble(), 15.0)
        val before = toneLevelDb(x, fs + fs / 2, 2345.0, fs)
        val after = toneLevelDb(y, fs + fs / 2, 2345.0, fs)
        assertTrue("cut ${before - after}", before - after > 15)
    }

    @Test fun glidingVoiceIsLeftAlone() {
        val h = HowlSuppressor(fs)
        // A voice-like glide with harmonics, moving 150→250 Hz pitch.
        val n = fs * 3
        var ph = 0.0
        val x = FloatArray(n) { i ->
            val f0 = 150 + 100 * kotlin.math.sin(2 * kotlin.math.PI * 1.5 * i / fs)
            ph += 2 * kotlin.math.PI * f0 / fs
            var v = 0.0
            for (k in 1..12) v += kotlin.math.sin(k * ph) / k
            (0.1 * v).toFloat()
        }
        x.forEach { h.process(it) }
        assertEquals(0, h.found.size)
    }

    /** Bluetooth: our sound comes back into the microphone 200 ms later through a resonant leak. */
    private fun bluetoothLoop(volume: Float, pathGain: Float, secs: Int, guard: Boolean, talk: Boolean): Pair<Double, Float> {
        val s = Settings().apply { volumeDb = volume; this.guard = guard; eqDb = floatArrayOf(-6f, -2f, 3f, 6f, 6f); changed() }
        val p = Processor(fs, s)
        val delay = fs / 5
        val back = FloatArray(delay)
        var bi = 0
        val r1 = Biquad().apply { peaking(fs, 2300.0, 4.0, 12.0) }
        val r2 = Biquad().apply { peaking(fs, 900.0, 3.0, 6.0) }
        val rnd = java.util.Random(7)
        val speech = voice(fs * secs, 0.01, java.util.Random(5))
        val block = 192
        val inp = FloatArray(block)
        val out = FloatArray(block * 2)
        var quietPeak = 0.0
        var t = 0
        while (t < fs * secs) {
            for (i in 0 until block) {
                val n = t + i
                val say = if (talk && (n / fs) % 6 < 3) speech[n] else 0f
                inp[i] = back[(bi + i) % delay] * pathGain + (rnd.nextGaussian() * 0.0005).toFloat() + say
            }
            p.process(inp, out, block)
            for (i in 0 until block) {
                back[(bi + i) % delay] = r2.process(r1.process(out[2 * i]))
                // Loudness while nobody talks in the last 10 seconds: that is pure echo/squeal.
                val n = t + i
                if (n > fs * (secs - 10) && (n / fs) % 6 >= 4) quietPeak = maxOf(quietPeak, kotlin.math.abs(out[2 * i]).toDouble())
            }
            bi = (bi + block) % delay
            t += block
        }
        return Pair(20 * kotlin.math.log10(quietPeak + 1e-9), p.guardCutDb)
    }

    @Test fun bluetoothEchoIsStopped() {
        val (without, _) = bluetoothLoop(24f, 0.05f, 30, guard = false, talk = true)
        val (with, cut) = bluetoothLoop(24f, 0.05f, 30, guard = true, talk = true)
        assertTrue("without guard it should squeal: $without", without > -10)
        assertTrue("with guard it must stay quiet in the pauses: $with (cut $cut)", with < -35)
    }

    @Test fun simulatedFeedbackLoopDiesOut() {
        // Room: output comes back into the mic 25 ms later; loop gain 0.6 overall but 3x (+9.5 dB) at a 1800 Hz resonance.
        fun run(guard: Boolean): Double {
            val s = Settings().apply { volumeDb = 20f; gate = false; compress = false; this.guard = guard; changed() }
            val p = Processor(fs, s)
            val delay = fs * 25 / 1000
            val back = FloatArray(delay)
            var bi = 0
            val room = Biquad().apply { peaking(fs, 1800.0, 8.0, 14.0) }
            val block = 192
            val inp = FloatArray(block); val out = FloatArray(block * 2)
            val tail = ArrayList<Float>()
            val rnd = java.util.Random(1)
            val total = fs * 8
            var t = 0
            while (t < total) {
                for (i in 0 until block) {
                    inp[i] = back[(bi + i) % delay] * 0.06f + (rnd.nextGaussian() * 0.001).toFloat()
                }
                p.process(inp, out, block)
                for (i in 0 until block) back[(bi + i) % delay] = room.process(out[2 * i])
                bi = (bi + block) % delay
                if (t > total - fs) for (i in 0 until block) tail.add(out[2 * i])
                t += block
            }
            return 20 * kotlin.math.log10(tail.maxOf { kotlin.math.abs(it) }.toDouble())
        }
        val without = run(false)
        val with = run(true)
        assertTrue("without $without", without > -6)   // squeals up to the limiter
        assertTrue("with $with", with < without - 15)
    }
}
