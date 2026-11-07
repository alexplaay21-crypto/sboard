package org.sboard.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Key click sounds played by sBoard itself. The system "touch sounds" effect is silent on many
 * phones (Samsung keeps its keyboard sound separate), so short, soft clicks are synthesised once
 * into small WAV files and played through a low-latency [SoundPool].
 */
object SBoardKeySound {
    enum class Kind { STANDARD, DELETE, SPACE, ENTER }

    /** Sound styles offered in the settings. */
    val STYLES = listOf("soft", "click", "wood", "bubble")

    private var pool: SoundPool? = null
    private var loadedStyle: String? = null
    private val ids = IntArray(Kind.entries.size)
    @Volatile private var ready = BooleanArray(Kind.entries.size)

    private fun prefs(context: Context) = context.getSharedPreferences("sboard_keyboard_setup", Context.MODE_PRIVATE)
    fun style(context: Context): String = prefs(context).getString("sound_style", "soft")?.takeIf { it in STYLES } ?: "soft"
    fun setStyle(context: Context, value: String) {
        prefs(context).edit().putString("sound_style", value).apply()
        release()
    }

    fun release() {
        pool?.release(); pool = null; loadedStyle = null
        ready = BooleanArray(Kind.entries.size)
    }

    private fun ensure(context: Context) {
        val style = style(context)
        if (pool != null && loadedStyle == style) return
        release()
        val sp = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
        val flags = BooleanArray(Kind.entries.size)
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            val index = ids.indexOf(sampleId)
            if (index >= 0 && status == 0) flags[index] = true
        }
        Kind.entries.forEach { kind ->
            val file = File(context.cacheDir, "sboard_click_${style}_${kind.name.lowercase()}.wav")
            if (!file.exists() || file.length() < 64) FileOutputStream(file).use { it.write(wav(synth(style, kind))) }
            ids[kind.ordinal] = sp.load(file.path, 1)
        }
        ready = flags
        pool = sp
        loadedStyle = style
    }

    /** Plays a click; [volume] is 0..1. Silent while the phone is in silent or vibrate mode. */
    fun play(context: Context, kind: Kind, volume: Float) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        runCatching {
            ensure(context)
            val sp = pool ?: return
            if (!ready[kind.ordinal]) return
            val v = volume.coerceIn(0f, 1f)
            sp.play(ids[kind.ordinal], v, v, 1, 0, 1f)
        }
    }

    private const val RATE = 44_100

    /** Short percussive tone: decaying sine with a little noise for the "tap" transient. */
    internal fun synth(style: String, kind: Kind): ShortArray {
        val (baseFreq, durationMs, decay, noise) = when (style) {
            "click" -> Quad(2400.0, 22, 260.0, 0.35)
            "wood" -> Quad(820.0, 45, 110.0, 0.18)
            "bubble" -> Quad(1100.0, 55, 75.0, 0.0)
            else -> Quad(1500.0, 32, 170.0, 0.12) // soft
        }
        val pitch = when (kind) {
            Kind.STANDARD -> 1.0
            Kind.DELETE -> 0.86
            Kind.SPACE -> 0.72
            Kind.ENTER -> 0.62
        }
        val n = RATE * durationMs / 1000
        val out = ShortArray(n)
        var seed = 0x2545F491L
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val env = exp(-t * decay) * (if (i < 40) i / 40.0 else 1.0) // tiny attack avoids a pop
            val f = if (style == "bubble") baseFreq * pitch * (1.0 + 0.6 * exp(-t * 60)) else baseFreq * pitch
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val rnd = ((seed ushr 33).toInt() and 0xFFFF) / 32768.0 - 1.0
            val sample = (sin(2 * PI * f * t) * (1 - noise) + rnd * noise * exp(-t * decay * 3)) * env
            out[i] = (sample * 0.55 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private data class Quad(val a: Double, val b: Int, val c: Double, val d: Double)

    internal fun wav(samples: ShortArray): ByteArray {
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataSize); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(RATE); buf.putInt(RATE * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataSize)
        samples.forEach { buf.putShort(it) }
        return buf.array()
    }
}
