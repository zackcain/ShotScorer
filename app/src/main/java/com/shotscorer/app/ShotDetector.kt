package com.shotscorer.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * Audio-based shot detector.
 *
 * Reads from the tablet mic in small chunks, computes RMS per chunk, keeps a
 * rolling median as the "quiet baseline", and fires `onShot` when a chunk
 * exceeds `baseline × TRIGGER_RATIO` and an absolute floor. A refractory
 * period suppresses double-triggers on the echo tail.
 *
 * Deliberately simple — enough for a rifle shot indoors at the firing point.
 * Later this can pick up sub-sample timing like ShotTrainer's detector.
 *
 * @param onShot invoked (from the recording thread) whenever a shot is heard.
 *               The consumer must marshal to the UI thread itself.
 */
class ShotDetector(private val onShot: (timestampMs: Long) -> Unit) {

    companion object {
        private const val TAG = "ShotDetector"
        private const val SAMPLE_RATE = 48_000
        private const val CHUNK_MS = 20
        private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000  // 960
        private const val BASELINE_WINDOW = 100    // ~2 s of history
        private const val TRIGGER_RATIO = 8.0f     // rms > baseline × this
        private const val MIN_ABS_RMS = 800.0f     // reject quiet-scene false positives
        private const val REFRACTORY_MS = 400L     // no re-trigger inside this
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(CHUNK_SAMPLES * 4)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
            )
        } catch (t: SecurityException) {
            Log.w(TAG, "RECORD_AUDIO permission denied", t)
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord construction failed", t)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialise")
            record.release()
            return false
        }
        record.startRecording()
        running = true
        thread = Thread({ loop(record) }, "ShotDetector").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    private fun loop(record: AudioRecord) {
        val chunk = ShortArray(CHUNK_SAMPLES)
        val baseline = ArrayDeque<Float>()
        var lastTriggerMs = 0L
        try {
            while (running) {
                val read = record.read(chunk, 0, CHUNK_SAMPLES)
                if (read <= 0) continue
                var sumSq = 0.0
                for (i in 0 until read) {
                    val s = chunk[i].toInt()
                    sumSq += (s * s).toDouble()
                }
                val rms = sqrt(sumSq / read).toFloat()

                val baseVal = if (baseline.isEmpty()) 100f else {
                    val sorted = baseline.sorted()
                    sorted[sorted.size / 2]
                }
                val now = System.currentTimeMillis()
                val enoughHistory = baseline.size >= BASELINE_WINDOW / 4
                if (enoughHistory
                    && (now - lastTriggerMs) > REFRACTORY_MS
                    && rms > MIN_ABS_RMS
                    && rms > baseVal * TRIGGER_RATIO
                ) {
                    lastTriggerMs = now
                    Log.d(TAG, "shot: rms=${rms.toInt()} baseline=${baseVal.toInt()} ratio=${rms / baseVal}")
                    try {
                        onShot(now)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onShot handler threw", t)
                    }
                }
                baseline.addLast(rms)
                while (baseline.size > BASELINE_WINDOW) baseline.removeFirst()
            }
        } finally {
            try {
                record.stop()
            } catch (_: Throwable) {
            }
            record.release()
        }
    }
}
