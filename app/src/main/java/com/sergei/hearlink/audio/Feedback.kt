package com.sergei.hearlink.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place radix-2 FFT of [re]/[im]; the size must be a power of two. */
fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) {
            var t = re[i]; re[i] = re[j]; re[j] = t
            t = im[i]; im[i] = im[j]; im[j] = t
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -2 * PI / len
        val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
        var i = 0
        while (i < n) {
            var cr = 1f; var ci = 0f
            for (k in 0 until len / 2) {
                val a = i + k; val b = a + len / 2
                val xr = re[b] * cr - im[b] * ci
                val xi = re[b] * ci + im[b] * cr
                re[b] = re[a] - xr; im[b] = im[a] - xi
                re[a] += xr; im[a] += xi
                val nr = cr * wr - ci * wi
                ci = cr * wi + ci * wr
                cr = nr
            }
            i += len
        }
        len = len shl 1
    }
}

/**
 * Moves every frequency up by a few hertz. Feedback needs the same tone to come round the loop
 * again and again; with a small shift it never lines up, so it can't build up. Barely audible on
 * speech. Uses a pair of allpass chains that are 90° apart (Niemitalo's Hilbert transformer).
 */
class FrequencyShifter(fs: Int, hz: Double = 6.0) {
    private val ca = floatArrayOf(0.6923878f, 0.9360654322959f, 0.9882295226860f, 0.9987488452737f).map { it * it }.toFloatArray()
    private val cb = floatArrayOf(0.4021921162426f, 0.8561710882420f, 0.9722909545651f, 0.9952884791278f).map { it * it }.toFloatArray()
    private val ax1 = FloatArray(4); private val ax2 = FloatArray(4); private val ay1 = FloatArray(4); private val ay2 = FloatArray(4)
    private val bx1 = FloatArray(4); private val bx2 = FloatArray(4); private val by1 = FloatArray(4); private val by2 = FloatArray(4)
    private var bDelay = 0f
    private val step = 2 * PI * hz / fs
    private var phase = 0.0

    private fun chain(x0: Float, c: FloatArray, x1: FloatArray, x2: FloatArray, y1: FloatArray, y2: FloatArray): Float {
        var x = x0
        for (k in 0 until 4) {
            val y = c[k] * (x + y2[k]) - x2[k]
            x2[k] = x1[k]; x1[k] = x
            y2[k] = y1[k]; y1[k] = y
            x = y
        }
        return x
    }

    fun process(x: Float): Float {
        val iNow = chain(x, ca, ax1, ax2, ay1, ay2)
        val q = chain(x, cb, bx1, bx2, by1, by2)
        val i = bDelay
        bDelay = iNow
        phase += step
        if (phase > 2 * PI) phase -= 2 * PI
        return (i * cos(phase) + q * sin(phase)).toFloat()
    }
}

/**
 * Listens for a tone that stands out far above everything around it and stays put: that is
 * feedback starting. It then puts a very narrow cut exactly on that tone, so the rest of the
 * sound is untouched. Up to [MAX] cuts; each gets deeper if the tone keeps coming back.
 */
class HowlSuppressor(private val fs: Int) {
    private val n = 2048
    private val hop = 512
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val ring = FloatArray(n)
    private var pos = 0
    private var sinceHop = 0
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val pow = FloatArray(n / 2)
    /** Tones that stand out and are being watched: frequency bin, frames seen, level when first seen. */
    private class Cand(var bin: Int, var count: Int, var startDb: Double, var seen: Boolean)
    private val cands = ArrayList<Cand>()

    private class Notch(val hz: Double, var depthDb: Double, val f: Biquad)

    private val notches = ArrayList<Notch>()

    /** Counts every time a whistle was found (also one it already had a cut for). */
    @Volatile var detections = 0
        private set

    /** The tones it has cut so far, in Hz. */
    @Volatile var found: List<Int> = emptyList()
        private set

    fun reset() {
        notches.clear()
        found = emptyList()
        cands.clear()
    }

    fun process(x: Float): Float {
        var y = x
        for (k in notches.indices) y = notches[k].f.process(y)
        ring[pos] = y
        pos = (pos + 1) and (n - 1)
        if (++sinceHop >= hop) {
            sinceHop = 0
            analyse()
        }
        return y
    }

    private fun analyse() {
        for (i in 0 until n) {
            re[i] = ring[(pos + i) and (n - 1)] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        var total = 0.0
        val lo = (150.0 * n / fs).toInt()
        val hi = minOf(n / 2 - 8, (9000.0 * n / fs).toInt())
        for (k in 0 until n / 2) {
            pow[k] = re[k] * re[k] + im[k] * im[k]
            if (k in lo..hi) total += pow[k]
        }
        val mean = total / (hi - lo + 1)
        if (mean <= 0.0) { cands.clear(); return }

        // The (up to) three strongest peaks that are loud enough (about -45 dBFS), far above the
        // average and far above their neighbours.
        val peaks = ArrayList<Int>()
        for (k in maxOf(lo, 2)..minOf(hi, n / 2 - 3)) {
            val p = pow[k]
            if (p <= pow[k - 1] || p < pow[k + 1] || p <= pow[k - 2] || p < pow[k + 2]) continue
            val levelDb = 10 * log10(p.toDouble() / (n * n / 16.0) + 1e-20)
            if (levelDb <= -45 || 10 * log10(p / mean) <= 15) continue
            // With Bluetooth delay a whistle is often a narrow band of tones, so compare the band
            // around the peak with the sound a few hundred hertz away.
            var band = 0.0
            for (d in -2..2) band += pow[k + d]
            var near = 0.0
            var cnt = 0
            for (d in 12..30) {
                if (k - d >= 0) { near += pow[k - d]; cnt++ }
                if (k + d < n / 2) { near += pow[k + d]; cnt++ }
            }
            if (10 * log10(band / 5 / (near / cnt + 1e-20)) <= 14) continue
            peaks.add(k)
        }
        peaks.sortByDescending { pow[it] }
        for (c in cands) c.seen = false
        for (k in peaks.take(3)) {
            val levelDb = 10 * log10(pow[k].toDouble() / (n * n / 16.0) + 1e-20)
            var c = cands.firstOrNull { !it.seen && kotlin.math.abs(it.bin - k) <= 4 }
            if (c == null) {
                c = Cand(k, 0, levelDb, false)
                cands.add(c)
            }
            c.seen = true
            c.bin = k
            c.count++
            // Feedback is a tone that keeps growing, or one that stays loud. A beep or a sung note
            // in the room does neither, and a voice glides, so those are left alone.
            val growing = c.count >= 4 && levelDb - c.startDb >= 4.0
            val loudAndStuck = c.count >= 12 && levelDb > -32
            if (c.count > 60) { c.count = 1; c.startDb = levelDb } // a long steady tone: start over
            if (growing || loudAndStuck) {
                c.count = 0
                c.startDb = levelDb
                // Refine the frequency between bins.
                val a = pow[k - 1].toDouble(); val b = pow[k].toDouble(); val cc = pow[k + 1].toDouble()
                val off = 0.5 * (a - cc) / (a - 2 * b + cc).let { if (it == 0.0) 1e-12 else it }
                addNotch((k + off.coerceIn(-0.5, 0.5)) * fs / n)
            }
        }
        cands.removeAll { !it.seen }
    }

    private fun addNotch(hz: Double) {
        detections++
        val same = notches.firstOrNull { kotlin.math.abs(it.hz - hz) < hz * 0.05 + 20 }
        if (same != null) {
            same.depthDb = maxOf(-40.0, same.depthDb - 10)
            same.f.peaking(fs, same.hz, Q, same.depthDb)
        } else {
            if (notches.size >= MAX) notches.removeAt(0)
            notches.add(Notch(hz, -18.0, Biquad().apply { peaking(fs, hz, Q, -18.0) }))
        }
        found = notches.map { it.hz.toInt() }
    }

    companion object {
        const val MAX = 10
        private const val Q = 8.0
    }
}

/** Plain magnitude in dB of [x] at [hz], for the tests. */
fun toneLevelDb(x: FloatArray, from: Int, hz: Double, fs: Int): Double {
    var c = 0.0; var s = 0.0
    for (i in from until x.size) {
        val w = 2 * PI * hz * i / fs
        c += x[i] * cos(w); s += x[i] * sin(w)
    }
    val m = 2 * sqrt(c * c + s * s) / (x.size - from)
    return 20 * log10(m + 1e-20)
}

/**
 * Hears whether the microphone is picking up our own output again: the echo (galm) that keeps
 * coming back, and the start of feedback. It compares the actual sound wave from the microphone
 * with the sound we sent out 50–450 ms earlier (the Bluetooth delay). Speech in the room never
 * repeats itself exactly; our own sound coming back does, every time with the same delay.
 */
class EchoDetector(private val fs: Int) {
    private val dec = maxOf(1, Math.round(fs / 8000.0).toInt())
    private val rate = fs / dec
    private val n = 8192                    // about 1 s compared at a time
    private val minLag = rate * 50 / 1000
    private val maxLag = rate * 450 / 1000
    private val m = 16384                   // FFT size, > n + maxLag
    private val cap = n + maxLag + 16
    private val inD = FloatArray(cap)
    private val outD = FloatArray(cap)
    private var w = 0
    private var filled = 0
    private var accIn = 0f
    private var accOut = 0f
    private var accN = 0
    private var sinceCheck = 0
    private val every = rate / 4            // check 4 times a second
    private var cooldown = 0
    private var lastLag = -1
    private var hits = 0
    private val xr = FloatArray(m); private val xi = FloatArray(m)
    private val yr = FloatArray(m); private val yi = FloatArray(m)

    /** Last measured delay of the loop in ms (0 = not measured yet). */
    @Volatile var delayMs = 0
        private set
    @Volatile var lastR = 0f
        private set

    /** Feed one sample of microphone input and the matching output; true when an echo loop is found. */
    fun push(mic: Float, out: Float): Boolean {
        accIn += mic
        accOut += out
        if (++accN < dec) return false
        inD[w] = accIn / dec
        outD[w] = accOut / dec
        w = (w + 1) % cap
        accIn = 0f; accOut = 0f; accN = 0
        if (filled < cap) filled++
        if (cooldown > 0) { cooldown--; return false }
        if (++sinceCheck < every || filled < cap) return false
        sinceCheck = 0
        return check()
    }

    fun reset() { filled = 0; cooldown = 0; delayMs = 0; hits = 0; lastLag = -1 }

    private fun check(): Boolean {
        // x = the last n microphone samples; y = our output from maxLag earlier up to now.
        var ex = 0.0
        var ey = 0.0
        for (i in 0 until m) { xr[i] = 0f; xi[i] = 0f; yr[i] = 0f; yi[i] = 0f }
        val yLen = n + maxLag
        for (j in 0 until yLen) {
            val v = outD[(w - yLen + j + cap * 2) % cap]
            yr[j] = v
            ey += (v * v).toDouble()
        }
        for (i in 0 until n) {
            val v = inD[(w - n + i + cap * 2) % cap]
            xr[i] = v
            ex += (v * v).toDouble()
        }
        if (ey / yLen < 1e-5 || ex < 1e-12) { hits = 0; return false } // too quiet to matter (about -50 dBFS)
        fft(xr, xi)
        fft(yr, yi)
        // conj(X) * Y, then inverse FFT (via conjugation) gives the cross-correlation.
        for (k in 0 until m) {
            val ar = xr[k]; val ai = -xi[k]
            val br = yr[k]; val bi = yi[k]
            xr[k] = ar * br - ai * bi
            xi[k] = -(ar * bi + ai * br)
        }
        fft(xr, xi)
        // offset k in y corresponds to delay maxLag - k.
        val norm = kotlin.math.sqrt(ex * ey * n / yLen)
        var best = 0.0
        var bestLag = 0
        for (lag in minLag..maxLag) {
            val c = xr[maxLag - lag] / m / norm
            if (c > best) { best = c; bestLag = lag }
        }
        lastR = best.toFloat()
        // A best match right at the edge of the range is usually chance (a held vowel); only trust it when strong.
        if (bestLag <= minLag + 3 || bestLag >= maxLag - 3) { if (best < 0.5) best = 0.0 }
        if (best > 0.35 && (lastLag < 0 || kotlin.math.abs(bestLag - lastLag) <= 2)) hits++ else hits = 0
        lastLag = if (best > 0.35) bestLag else -1
        // The same delay three times in a row: that is our own sound.
        if (hits >= 3) {
            delayMs = bestLag * 1000 / rate
            hits = 0
            lastLag = -1
            cooldown = rate * 3 / 2 // give the lower volume 1.5 s to take effect
            return true
        }
        return false
    }
}
