package com.shotscorer.app

import android.content.ContentValues
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceHolder
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.VideoCapture
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCControl
import com.shotscorer.app.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect as CvRect
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min as kmin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShotScorer"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
    }

    private lateinit var binding: ActivityMainBinding
    private var cameraHelper: ICameraHelper? = null
    private var currentDevice: UsbDevice? = null

    private var isRecording = false
    private var recordingStartMs = 0L
    private var lastFocusWrite = -1
    private var debugMode = false
    private var openCvOk = false

    private val detectionExecutor = Executors.newSingleThreadExecutor()
    private val detecting = AtomicBoolean(false)
    private var frameCallbackRegistered = false
    private var detectionByteBuffer = ByteArray(0)
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = (SystemClock.elapsedRealtime() - recordingStartMs) / 1000
                val text = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
                binding.recStatus.text = getString(R.string.rec_recording, text)
                timerHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        openCvOk = try {
            OpenCVLoader.initDebug()
        } catch (t: Throwable) {
            Log.e(TAG, "OpenCV init threw", t); false
        }
        Log.i(TAG, "OpenCV init ok=$openCvOk version=${runCatching { Core.getVersionString() }.getOrElse { "n/a" }}")

        binding.preview.setAspectRatio(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        binding.preview.holder.addCallback(surfaceCallback)

        binding.recordButton.setOnClickListener { toggleRecording() }

        // Long-press the bottom status bar to toggle debug UI (focus row).
        binding.status.setOnLongClickListener {
            debugMode = !debugMode
            Toast.makeText(this, if (debugMode) "Debug UI on" else "Debug UI off", Toast.LENGTH_SHORT).show()
            applyFocusUi()
            true
        }

        binding.afSwitch.setOnCheckedChangeListener { _, checked ->
            val ctrl = cameraHelper?.getUVCControl() ?: return@setOnCheckedChangeListener
            try {
                if (ctrl.isFocusAutoEnable) ctrl.focusAuto = checked
            } catch (t: Throwable) {
                Log.w(TAG, "AF toggle failed", t)
            }
            binding.focusSlider.isEnabled = !checked && ctrl.isFocusAbsoluteEnable
            refreshFocusDebug()
        }

        binding.focusSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                binding.focusValue.text = getString(R.string.focus_manual_pct, progress)
                if (!fromUser) return
                val ctrl = cameraHelper?.getUVCControl() ?: return
                try {
                    if (ctrl.isFocusAutoEnable && ctrl.focusAuto) {
                        ctrl.focusAuto = false
                        binding.afSwitch.isChecked = false
                    }
                    if (ctrl.isFocusAbsoluteEnable) {
                        ctrl.setFocusAbsolutePercent(progress)
                        lastFocusWrite = progress
                        Log.d(TAG, "focus write %=$progress → read %=${runCatching { ctrl.focusAbsolutePercent }.getOrElse { -1 }} absVal=${runCatching { ctrl.focusAbsolute }.getOrElse { -1 }}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Focus set failed", t)
                }
                refreshFocusDebug()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    override fun onStart() {
        super.onStart()
        initCameraHelper()
    }

    override fun onStop() {
        if (isRecording) stopRecordingSafely()
        releaseCameraHelper()
        super.onStop()
    }

    private fun initCameraHelper() {
        cameraHelper = CameraHelper().apply {
            setStateCallback(stateCallback)
        }
    }

    private fun releaseCameraHelper() {
        cameraHelper?.release()
        cameraHelper = null
        currentDevice = null
        setStatus(getString(R.string.status_waiting))
        runOnUiThread {
            binding.recordButton.isEnabled = false
            binding.focusRow.visibility = android.view.View.GONE
            binding.focusDebug.visibility = android.view.View.GONE
        }
    }

    private fun toggleRecording() {
        val helper = cameraHelper ?: return
        if (helper.isRecording) stopRecordingSafely() else startRecordingSafely(helper)
    }

    private fun startRecordingSafely(helper: ICameraHelper) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val tempFile = File(dir, "ShotScorer-$stamp.mp4")

        val options = VideoCapture.OutputFileOptions.Builder(tempFile).build()
        try {
            helper.startRecording(options, object : VideoCapture.OnVideoCaptureCallback {
                override fun onStart() {
                    runOnUiThread {
                        isRecording = true
                        recordingStartMs = SystemClock.elapsedRealtime()
                        binding.recordButton.text = getString(R.string.btn_stop)
                        timerHandler.post(timerRunnable)
                    }
                }
                override fun onVideoSaved(results: VideoCapture.OutputFileResults) {
                    Log.i(TAG, "Recording saved to temp: ${tempFile.absolutePath}")
                    val publicPath = publishToMovies(tempFile)
                    runOnUiThread {
                        isRecording = false
                        binding.recordButton.text = getString(R.string.btn_record)
                        binding.recStatus.text = getString(R.string.rec_saved, publicPath ?: tempFile.name)
                        Toast.makeText(this@MainActivity, "Saved to $publicPath", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onError(code: Int, message: String, cause: Throwable?) {
                    Log.e(TAG, "Recording error $code: $message", cause)
                    runOnUiThread {
                        isRecording = false
                        binding.recordButton.text = getString(R.string.btn_record)
                        binding.recStatus.text = getString(R.string.rec_error, message)
                    }
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording threw", t)
            binding.recStatus.text = getString(R.string.rec_error, t.message ?: "unknown")
        }
    }

    private fun publishToMovies(source: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return source.absolutePath  // pre-scoped-storage: leave in place
        }
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ShotScorer")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri: Uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return source.absolutePath
            contentResolver.openOutputStream(uri).use { out ->
                if (out == null) return source.absolutePath
                source.inputStream().use { input -> input.copyTo(out) }
            }
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            source.delete()
            "Movies/ShotScorer/${source.name}"
        } catch (t: Throwable) {
            Log.e(TAG, "publishToMovies failed", t)
            source.absolutePath
        }
    }

    private fun stopRecordingSafely() {
        try {
            cameraHelper?.stopRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "stopRecording threw", t)
        }
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun applyFocusUi() {
        val ctrl: UVCControl? = cameraHelper?.getUVCControl()
        if (ctrl == null || !debugMode) {
            binding.focusRow.visibility = android.view.View.GONE
            binding.focusDebug.visibility = android.view.View.GONE
            return
        }
        val afSupported = try { ctrl.isFocusAutoEnable } catch (_: Throwable) { false }
        val manualSupported = try { ctrl.isFocusAbsoluteEnable } catch (_: Throwable) { false }

        if (!afSupported && !manualSupported) {
            binding.focusRow.visibility = android.view.View.GONE
            binding.focusDebug.visibility = android.view.View.GONE
            binding.recStatus.text = getString(R.string.focus_unsupported)
            return
        }
        binding.focusRow.visibility = android.view.View.VISIBLE
        binding.focusDebug.visibility = android.view.View.VISIBLE

        binding.afSwitch.isEnabled = afSupported
        val afOn = if (afSupported) {
            try { ctrl.focusAuto } catch (_: Throwable) { true }
        } else false
        binding.afSwitch.isChecked = afOn

        binding.focusSlider.isEnabled = manualSupported && !afOn
        if (manualSupported) {
            val pct = try { ctrl.focusAbsolutePercent } catch (_: Throwable) { 50 }
            binding.focusSlider.progress = pct
            binding.focusValue.text = getString(R.string.focus_manual_pct, pct)
        } else {
            binding.focusValue.text = ""
        }
        refreshFocusDebug()
    }

    private fun refreshFocusDebug() {
        val ctrl = cameraHelper?.getUVCControl() ?: return
        val afSup = runCatching { ctrl.isFocusAutoEnable }.getOrElse { false }
        val manSup = runCatching { ctrl.isFocusAbsoluteEnable }.getOrElse { false }
        val af = runCatching { ctrl.focusAuto }.getOrElse { false }
        val limits = runCatching { ctrl.updateFocusAbsoluteLimit() }.getOrElse { intArrayOf(0, 0) }
        val lo = if (limits.isNotEmpty()) limits[0] else 0
        val hi = if (limits.size >= 2) limits[1] else 0
        val curr = runCatching { ctrl.focusAbsolutePercent }.getOrElse { -1 }
        binding.focusDebug.text = getString(R.string.focus_debug, afSup, manSup, af, lo, hi, curr, lastFocusWrite)
        Log.d(TAG, "focus dbg: AFsup=$afSup manSup=$manSup AF=$af range=$lo..$hi curr%=$curr wrote%=$lastFocusWrite absVal=${runCatching { ctrl.focusAbsolute }.getOrElse { -1 }}")
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            Log.d(TAG, "onAttach ${device.deviceName}")
            setStatus(getString(R.string.status_attached, device.productName ?: device.deviceName))
            if (currentDevice == null) {
                currentDevice = device
                cameraHelper?.selectDevice(device)
            }
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            Log.d(TAG, "onDeviceOpen ${device.deviceName} first=$isFirstOpen")
            cameraHelper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            Log.d(TAG, "onCameraOpen ${device.deviceName}")
            val size = cameraHelper?.previewSize
            val w = size?.width ?: DEFAULT_WIDTH
            val h = size?.height ?: DEFAULT_HEIGHT
            runOnUiThread { binding.preview.setAspectRatio(w, h) }
            cameraHelper?.addSurface(binding.preview.holder.surface, false)
            cameraHelper?.startPreview()
            setStatus(getString(R.string.status_opened, w, h))

            try {
                val cfg = cameraHelper?.videoCaptureConfig
                if (cfg != null) {
                    val targetBitrate = ((w * h) * 0.15).toInt().coerceAtLeast(8_000_000)
                    cfg.setBitRate(targetBitrate)
                        .setIFrameInterval(1)
                        .setAudioCaptureEnable(false)
                    cameraHelper?.videoCaptureConfig = cfg
                    Log.i(TAG, "VideoCaptureConfig: bitrate=${cfg.bitRate}, iFrame=${cfg.iFrameInterval}, audio=${cfg.audioCaptureEnable}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to tune VideoCaptureConfig", t)
            }

            if (openCvOk && !frameCallbackRegistered) {
                try {
                    cameraHelper?.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
                    frameCallbackRegistered = true
                    Log.i(TAG, "Frame callback registered (NV21)")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to register frame callback", t)
                }
            }

            runOnUiThread {
                binding.recordButton.isEnabled = true
                binding.recStatus.text = getString(R.string.rec_idle)
                applyFocusUi()
            }
        }

        override fun onCameraClose(device: UsbDevice) {
            Log.d(TAG, "onCameraClose ${device.deviceName}")
            if (isRecording) stopRecordingSafely()
            cameraHelper?.removeSurface(binding.preview.holder.surface)
            frameCallbackRegistered = false
            runOnUiThread {
                binding.recordButton.isEnabled = false
                binding.focusRow.visibility = android.view.View.GONE
                binding.focusDebug.visibility = android.view.View.GONE
                binding.overlay.clearBull()
            }
        }

        override fun onDeviceClose(device: UsbDevice) {
            Log.d(TAG, "onDeviceClose ${device.deviceName}")
        }

        override fun onDetach(device: UsbDevice) {
            Log.d(TAG, "onDetach ${device.deviceName}")
            if (device == currentDevice) {
                currentDevice = null
                setStatus(getString(R.string.status_waiting))
            }
        }

        override fun onCancel(device: UsbDevice) {
            Log.d(TAG, "onCancel — user denied USB permission")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "USB permission denied", Toast.LENGTH_SHORT).show()
            }
            if (device == currentDevice) currentDevice = null
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            currentDevice?.let { cameraHelper?.addSurface(holder.surface, false) }
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            cameraHelper?.removeSurface(holder.surface)
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.status.text = text }
    }

    // --- Bull detection ---

    private val clahe by lazy { Imgproc.createCLAHE(2.5, CvSize(8.0, 8.0)) }

    private val frameCallback = IFrameCallback { frame: ByteBuffer ->
        if (!openCvOk) return@IFrameCallback
        val size = cameraHelper?.previewSize ?: return@IFrameCallback
        val w = size.width
        val h = size.height
        val ySize = w * h
        if (frame.remaining() < ySize) return@IFrameCallback
        if (!detecting.compareAndSet(false, true)) return@IFrameCallback  // busy

        if (detectionByteBuffer.size < ySize) detectionByteBuffer = ByteArray(ySize)
        frame.get(detectionByteBuffer, 0, ySize)
        val yBytes = detectionByteBuffer

        detectionExecutor.execute {
            try {
                val result = detectFrame(yBytes, w, h)
                runOnUiThread {
                    if (result.bulls.isEmpty() && result.card == null) {
                        binding.overlay.clearBull()
                    } else {
                        val cxFrame = w / 2f
                        val cyFrame = h / 2f
                        val activeIdx = result.bulls.indices.minByOrNull { i ->
                            val b = result.bulls[i]
                            val dx = b.cx - cxFrame
                            val dy = b.cy - cyFrame
                            dx * dx + dy * dy
                        } ?: -1
                        binding.overlay.updateFrame(result.bulls, activeIdx, result.card, w, h)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "detection failed", t)
            } finally {
                detecting.set(false)
            }
        }
    }

    private data class DetectResult(val bulls: List<OverlayView.Bull>, val card: OverlayView.CardRect?)

    /**
     * Two-stage pipeline:
     *   1. Find the card — largest bright rectangular region in the frame.
     *   2. Run HoughCircles restricted to that region, then filter by dark-disk contrast.
     *
     * Rationale: at rifle-range setups the target CARD is the dominant bright
     * object in view. Non-card regions (garage wall, tire, shelving) produce
     * plenty of dark-on-light gradients that fool a whole-frame Hough sweep.
     * Constraining to the card eliminates those and lets us tighten per-bull
     * thresholds because we know we're on target paper.
     */
    private fun detectFrame(y: ByteArray, w: Int, h: Int): DetectResult {
        val targetW = 960
        val scale = targetW.toDouble() / w
        val smallW = targetW
        val smallH = (h * scale).toInt()

        val full = Mat(h, w, CvType.CV_8UC1)
        full.put(0, 0, y)
        val small = Mat()
        Imgproc.resize(full, small, CvSize(smallW.toDouble(), smallH.toDouble()))
        val raw = small.clone()

        try {
            // --- Stage 1: locate the card ---
            val cardRectSmall = findCardBounds(raw, smallW, smallH)
            if (cardRectSmall == null) {
                Log.d(TAG, "no card found")
                return DetectResult(emptyList(), null)
            }

            // --- Stage 2: bull detection inside the card ---
            val cardMat = raw.submat(cardRectSmall).clone()
            val cardEnhanced = cardMat.clone()
            clahe.apply(cardEnhanced, cardEnhanced)
            Imgproc.medianBlur(cardEnhanced, cardEnhanced, 3)

            val circles = Mat()
            val bulls: List<OverlayView.Bull> = try {
                val minR = 4
                val maxR = (kmin(cardRectSmall.width, cardRectSmall.height) * 0.25).toInt().coerceAtLeast(20)
                Imgproc.HoughCircles(
                    cardEnhanced, circles, Imgproc.HOUGH_GRADIENT,
                    1.2,
                    (minR * 3).toDouble(),
                    100.0,
                    18.0,   // looser now that we're on-card
                    minR,
                    maxR
                )
                if (circles.empty()) {
                    Log.d(TAG, "card found (${cardRectSmall.width}x${cardRectSmall.height}) but no circles")
                    return DetectResult(emptyList(), toCardOverlay(cardRectSmall, scale))
                }
                val n = circles.cols()
                data class Cand(val cx: Float, val cy: Float, val r: Float, val score: Double)
                val filtered = ArrayList<Cand>(n)
                val d = FloatArray(3)
                for (i in 0 until n) {
                    circles.get(0, i, d)
                    val score = bullContrastScore(cardMat, d[0].toInt(), d[1].toInt(), d[2].toInt())
                    if (score >= 25.0) {  // relaxed from 40 — on-card false positives are rarer
                        filtered.add(Cand(d[0], d[1], d[2], score))
                    }
                }
                filtered.sortByDescending { it.score }
                Log.d(TAG, "card ${cardRectSmall.width}x${cardRectSmall.height}: $n raw circles, ${filtered.size} pass contrast")
                filtered.take(15).map {
                    // Offset from card-local coords back to small-frame, then to full-res
                    OverlayView.Bull(
                        cx = ((it.cx + cardRectSmall.x) / scale).toFloat(),
                        cy = ((it.cy + cardRectSmall.y) / scale).toFloat(),
                        r = (it.r / scale).toFloat(),
                    )
                }
            } finally {
                circles.release()
                cardMat.release()
                cardEnhanced.release()
            }

            return DetectResult(bulls, toCardOverlay(cardRectSmall, scale))
        } finally {
            full.release()
            small.release()
            raw.release()
        }
    }

    private fun toCardOverlay(r: CvRect, scale: Double): OverlayView.CardRect {
        return OverlayView.CardRect(
            x = (r.x / scale).toFloat(),
            y = (r.y / scale).toFloat(),
            w = (r.width / scale).toFloat(),
            h = (r.height / scale).toFloat(),
        )
    }

    /**
     * Find the target card: the largest bright rectangular region in the frame.
     * Uses Otsu threshold + morphological close (to fill in the dark bulls so
     * they don't fragment the card into multiple contours) + largest contour.
     * Returns null if nothing above min-area threshold looks card-like.
     */
    private fun findCardBounds(gray: Mat, w: Int, h: Int): CvRect? {
        val thresh = Mat()
        val closed = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.threshold(gray, thresh, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            // Close radius must be > half the largest bull diameter so bull holes fill.
            val kernelSize = (kmin(w, h) * 0.08).toInt().coerceAtLeast(9)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, CvSize(kernelSize.toDouble(), kernelSize.toDouble()))
            Imgproc.morphologyEx(thresh, closed, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val frameArea = (w * h).toDouble()
            val minArea = frameArea * 0.03   // card must be ≥3% of frame
            val maxArea = frameArea * 0.75   // reject if it's basically the whole frame (over-bright scene)
            var bestArea = 0.0
            var bestRect: CvRect? = null
            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area < minArea || area > maxArea) continue
                if (area > bestArea) {
                    val rect = Imgproc.boundingRect(c)
                    // Sanity: aspect ratio between 1:3 and 3:1 (cards are roughly rectangular, not slivers)
                    val ar = rect.width.toDouble() / rect.height
                    if (ar in 0.3..3.5) {
                        bestArea = area
                        bestRect = rect
                    }
                }
            }
            return bestRect
        } finally {
            thresh.release()
            closed.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /**
     * Score how "bull-like" a candidate circle is: mean brightness of a small
     * outer annulus minus mean brightness of the disk interior. Positive =
     * dark-on-light (a real bull). Uses bounding-box means for speed; a mask
     * would be more accurate but ~5× slower and this is fine for filtering.
     */
    private fun bullContrastScore(gray: Mat, cx: Int, cy: Int, r: Int): Double {
        val w = gray.cols()
        val h = gray.rows()
        if (r < 3) return 0.0

        val innerR = (r * 0.7).toInt().coerceAtLeast(2)
        val innerX = (cx - innerR).coerceAtLeast(0)
        val innerY = (cy - innerR).coerceAtLeast(0)
        val innerW = kmin(2 * innerR, w - innerX)
        val innerH = kmin(2 * innerR, h - innerY)
        if (innerW <= 0 || innerH <= 0) return 0.0

        val outerR = (r * 1.6).toInt()
        val outerX = (cx - outerR).coerceAtLeast(0)
        val outerY = (cy - outerR).coerceAtLeast(0)
        val outerW = kmin(2 * outerR, w - outerX)
        val outerH = kmin(2 * outerR, h - outerY)
        if (outerW <= 0 || outerH <= 0) return 0.0

        val inner = gray.submat(CvRect(innerX, innerY, innerW, innerH))
        val outer = gray.submat(CvRect(outerX, outerY, outerW, outerH))
        return try {
            val innerMean = Core.mean(inner).`val`[0]
            val outerMean = Core.mean(outer).`val`[0]
            // outerMean is a mix of the disk and its surround; the SURROUND-only
            // mean is approximated by weighting up: outerMean = a*inner + (1-a)*surround
            // where a = (innerArea / outerArea). Solve for surround.
            val innerArea = (innerW * innerH).toDouble()
            val outerArea = (outerW * outerH).toDouble()
            val a = innerArea / outerArea
            val surroundMean = if (a < 1.0) (outerMean - a * innerMean) / (1.0 - a) else outerMean
            surroundMean - innerMean
        } finally {
            inner.release()
            outer.release()
        }
    }
}
