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
    private var candBin = -1
    private var candCount = 0

    private class Notch(val hz: Double, var depthDb: Double, val f: Biquad)

    private val notches = ArrayList<Notch>()

    /** The tones it has cut so far, in Hz. */
    @Volatile var found: List<Int> = emptyList()
        private set

    fun reset() {
        notches.clear()
        found = emptyList()
        candBin = -1
        candCount = 0
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
        var best = -1
        var bestP = 0f
        for (k in lo..hi) if (pow[k] > bestP) { bestP = pow[k]; best = k }
        if (best < 0 || mean <= 0.0) { candCount = 0; return }

        // Loud enough (≈ −50 dBFS), far above the average, and far above its neighbours.
        val levelDb = 10 * log10(bestP.toDouble() / (n * n / 16.0) + 1e-20)
        val papr = 10 * log10(bestP / mean)
        var near = 0.0
        var cnt = 0
        for (d in 4..10) {
            if (best - d >= 0) { near += pow[best - d]; cnt++ }
            if (best + d < n / 2) { near += pow[best + d]; cnt++ }
        }
        val pnpr = 10 * log10(bestP / (near / cnt + 1e-20))
        val howling = levelDb > -50 && papr > 18 && pnpr > 20

        if (howling && candBin >= 0 && kotlin.math.abs(best - candBin) <= 1) candCount++
        else { candBin = if (howling) best else -1; candCount = if (howling) 1 else 0 }
        // About 0.1 s in the same place: not a voice (that glides), so cut it.
        if (candCount >= 5) {
            candCount = 0
            // Refine the frequency between bins.
            val a = pow[best - 1].toDouble(); val b = pow[best].toDouble(); val c = pow[best + 1].toDouble()
            val off = 0.5 * (a - c) / (a - 2 * b + c).let { if (it == 0.0) 1e-12 else it }
            addNotch((best + off.coerceIn(-0.5, 0.5)) * fs / n)
        }
    }

    private fun addNotch(hz: Double) {
        val same = notches.firstOrNull { kotlin.math.abs(it.hz - hz) < hz * 0.02 + 10 }
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
        private const val Q = 18.0
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
