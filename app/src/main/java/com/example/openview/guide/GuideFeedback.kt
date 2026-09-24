package com.example.openview.guide

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ¿Caminas o estás quieto? Desviación de |aceleración| en la última ~1 s, con histéresis.
 * Sin acelerómetro, [moving] queda en null y el motor de guía lo trata como «desconocido».
 */
class MotionDetector(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val window = ArrayDeque<Pair<Long, Double>>()

    @Volatile var moving: Boolean? = null; private set
    @Volatile var stdDev: Double = 0.0; private set

    val available: Boolean get() = sensor != null

    fun start() { sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm?.unregisterListener(this); window.clear(); moving = null }

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.timestamp / 1_000_000L
        val m = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
        window.addLast(t to m)
        while (window.isNotEmpty() && t - window.first().first > 1_000L) window.removeFirst()
        if (window.size < 10) return
        val mean = window.sumOf { it.second } / window.size
        stdDev = sqrt(window.sumOf { (it.second - mean) * (it.second - mean) } / window.size)
        moving = when {
            stdDev > 0.60 -> true
            stdDev < 0.35 -> false
            else -> moving ?: false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * Pitidos del modo «frío/caliente»: tono más agudo cuanto más cerca (8 niveles pregenerados) y uno grave para
 * «te alejas». Usa el canal de guía de navegación para que suene aunque el móvil esté en modo multimedia bajo.
 */
class SonarPlayer {
    private val rate = 22_050
    private val tracks: List<AudioTrack>
    private val low: AudioTrack

    init {
        tracks = (0 until LEVELS).map { i -> build(440.0 * 2.0.pow(i / 3.5), 70) }
        low = build(220.0, 220)
    }

    private fun build(freq: Double, ms: Int): AudioTrack {
        val n = rate * ms / 1000
        val buf = ShortArray(n) { i ->
            val fade = minOf(1.0, i / (rate * 0.005), (n - i) / (rate * 0.005))
            (sin(2 * PI * freq * i / rate) * fade * Short.MAX_VALUE * 0.6).toInt().toShort()
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val fmt = AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val t = AudioTrack(attrs, fmt, n * 2, AudioTrack.MODE_STATIC, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
        t.write(buf, 0, n)
        return t
    }

    private fun play(t: AudioTrack) = runCatching {
        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
        t.reloadStaticData(); t.play()
    }

    /** [temperature] 0..1 → tono. */
    fun beep(temperature: Double) = play(tracks[(temperature * (LEVELS - 1)).toInt().coerceIn(0, LEVELS - 1)])
    fun warn() = play(low)
    fun release() { tracks.forEach { runCatching { it.release() } }; runCatching { low.release() } }

    companion object {
        const val LEVELS = 8
        /** Intervalo entre pitidos: 1,2 s en frío … 0,15 s tocando. */
        fun intervalMs(temperature: Double): Long = (1_200 - 1_050 * temperature.coerceIn(0.0, 1.0)).toLong()
    }
}

/** Vibraciones con significado: corta = hito, triple = llegaste, larga = corrige. */
class Haptics(context: Context) {
    private val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    private fun pattern(vararg ms: Long) = runCatching {
        val v = vib ?: return@runCatching
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, *ms), -1))
        else @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, *ms), -1)
    }

    fun milestone() = pattern(60)
    fun arrived() = pattern(120, 90, 120, 90, 300)
    fun correct() = pattern(450)
}
