package com.sergei.hearlink

import android.content.Context
import android.content.SharedPreferences
import com.sergei.hearlink.audio.Settings

/** Everything she sets is remembered, and copied into the running sound. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("hearlink", Context.MODE_PRIVATE)

    var volumeDb: Int
        get() = sp.getInt("volume", 12).coerceIn(0, VOLUME_MAX)
        set(v) = sp.edit().putInt("volume", v).apply()

    /** -10 (left) … 10 (right). */
    var balance: Int
        get() = sp.getInt("balance", 0).coerceIn(-10, 10)
        set(v) = sp.edit().putInt("balance", v).apply()

    var eq: IntArray
        get() = load("eq") ?: PRESETS[1].second.copyOf()
        set(v) = sp.edit().putString("eq", v.joinToString(",")).apply()

    var gate: Boolean
        get() = sp.getBoolean("gate", true)
        set(v) = sp.edit().putBoolean("gate", v).apply()
    var compress: Boolean
        get() = sp.getBoolean("compress", true)
        set(v) = sp.edit().putBoolean("compress", v).apply()
    var guard: Boolean
        get() = sp.getBoolean("guard", true)
        set(v) = sp.edit().putBoolean("guard", v).apply()
    var cleanMic: Boolean
        get() = sp.getBoolean("cleanMic", false)
        set(v) = sp.edit().putBoolean("cleanMic", v).apply()

    /** Reading voice: "" = phone language, otherwise a language tag such as "nl". */
    var voice: String
        get() = sp.getString("voice", "") ?: ""
        set(v) = sp.edit().putString("voice", v).apply()
    var readRate: Int
        get() = sp.getInt("readRate", 100).coerceIn(50, 200)
        set(v) = sp.edit().putInt("readRate", v).apply()
    var readSize: Int
        get() = sp.getInt("readSize", 30).coerceIn(18, 60)
        set(v) = sp.edit().putInt("readSize", v).apply()

    /** Her own saved settings, slot 1 or 2: volume, balance, then the five tone values. */
    fun saveMine(slot: Int) {
        val all = intArrayOf(volumeDb, balance) + eq
        sp.edit().putString("mine$slot", all.joinToString(",")).apply()
    }

    fun loadMine(slot: Int): Boolean {
        val all = load("mine$slot") ?: return false
        if (all.size != 7) return false
        volumeDb = all[0]
        balance = all[1]
        eq = all.copyOfRange(2, 7)
        return true
    }

    fun hasMine(slot: Int) = sp.contains("mine$slot")

    private fun load(key: String): IntArray? = try {
        sp.getString(key, null)?.split(',')?.map { it.trim().toInt() }?.toIntArray()
            ?.takeIf { key != "eq" || it.size == 5 }
    } catch (e: Exception) {
        null
    }

    /** Copies the sound settings into what the relay uses. */
    fun applyTo(s: Settings) {
        s.volumeDb = volumeDb.toFloat()
        s.balance = balance / 10f
        s.eqDb = eq.map { it.toFloat() }.toFloatArray()
        s.gate = gate
        s.compress = compress
        s.guard = guard
        s.changed()
    }

    companion object {
        const val VOLUME_MAX = 36
        const val EQ_RANGE = 15
        val PRESETS = listOf(
            "Flat" to intArrayOf(0, 0, 0, 0, 0),
            "Speech" to intArrayOf(-6, -2, 3, 6, 6),
            "Extra clear" to intArrayOf(-10, -5, 3, 9, 10),
            "Warm" to intArrayOf(4, 2, 0, -2, -4),
        )
    }
}
