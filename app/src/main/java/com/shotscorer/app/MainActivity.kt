package com.shotscorer.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

        // Detector params — tuned locally against real range footage in
        // tools/detect.py. Keep in sync with that script's constants.
        private const val BULL_MIN_R = 4
        private const val BULL_MAX_R_ABS = 18
        private const val BULL_MAX_R_FRAC = 0.10
        private const val CONTRAST_THRESHOLD = 40.0
        private const val NMS_OVERLAP_R_MUL = 1.0f
        private const val MAX_BULLS_KEPT = 200
        private const val CLUSTER_MIN_BULLS = 3
        private const val CLUSTER_EPS_R_MUL = 4.0f

        // Aim trace: 90 samples ≈ last 3-6 s at 15-30 fps detection rate.
        private const val TRACE_MAX_SAMPLES = 90
        // If active bull jumps more than this many bull-radii between frames,
        // treat as a new aim (different bull entirely) and reset the trace.
        private const val TRACE_RESET_JUMP_MUL = 3.0f

        // Keep the last N shot markers on screen.
        private const val MAX_SHOTS_SHOWN = 15
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

    /** Ring buffer of recent aim-vector samples, in full-res frame pixels.
     *  Oldest first, newest last. Cleared when active bull identity changes. */
    private val aimTrace: ArrayDeque<OverlayView.AimSample> = ArrayDeque()
    private var lastBullCx = 0f
    private var lastBullCy = 0f

    /** Recent shot markers, positioned at where the aim was when the sound
     *  fired. Numbered 1..N in the order they landed. */
    private val shots: ArrayDeque<OverlayView.ShotMarker> = ArrayDeque()
    private var shotCounter = 0

    private var shotDetector: ShotDetector? = null
    private var audioPermissionAsked = false
    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.i(TAG, "audio permission granted")
                if (cameraHelper?.isCameraOpened == true) tryStartShotDetector()
            } else {
                Log.i(TAG, "audio permission denied — shot detection off")
                Toast.makeText(this, "Mic denied — shot detection disabled", Toast.LENGTH_SHORT).show()
            }
        }
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

        // Request mic permission early so shot detection is ready when camera opens.
        ensureAudioPermission()

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

            tryStartShotDetector()

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
            aimTrace.clear()
            shots.clear()
            shotCounter = 0
            stopShotDetector()
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

    // --- Shot detection ---

    private fun ensureAudioPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        if (audioPermissionAsked) return
        audioPermissionAsked = true
        requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun tryStartShotDetector() {
        if (shotDetector != null) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ensureAudioPermission()
            return
        }
        val detector = ShotDetector { onShotHeard() }
        if (detector.start()) {
            shotDetector = detector
            Log.i(TAG, "shot detector running")
        } else {
            Log.w(TAG, "shot detector failed to start")
        }
    }

    private fun stopShotDetector() {
        shotDetector?.stop()
        shotDetector = null
    }

    /** Called from the audio thread when a shot is heard. Marshals to UI
     *  and captures the current aim as a shot marker. */
    private fun onShotHeard() {
        runOnUiThread {
            val current = aimTrace.lastOrNull() ?: return@runOnUiThread
            shotCounter += 1
            shots.addLast(OverlayView.ShotMarker(current.dx, current.dy, shotCounter))
            while (shots.size > MAX_SHOTS_SHOWN) shots.removeFirst()
            Toast.makeText(this, "Shot #$shotCounter", Toast.LENGTH_SHORT).show()
        }
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
                        aimTrace.clear()
                    } else {
                        val cxFrame = w / 2f
                        val cyFrame = h / 2f
                        val activeIdx = result.bulls.indices.minByOrNull { i ->
                            val b = result.bulls[i]
                            val dx = b.cx - cxFrame
                            val dy = b.cy - cyFrame
                            dx * dx + dy * dy
                        } ?: -1

                        if (activeIdx in result.bulls.indices) {
                            val ab = result.bulls[activeIdx]
                            // Reset trace on aim switch to a different bull.
                            val jumped = aimTrace.isNotEmpty() && run {
                                val dx = ab.cx - lastBullCx
                                val dy = ab.cy - lastBullCy
                                (dx * dx + dy * dy) > (ab.r * TRACE_RESET_JUMP_MUL).let { it * it }
                            }
                            if (jumped) aimTrace.clear()
                            lastBullCx = ab.cx
                            lastBullCy = ab.cy
                            // Aim sample = vector from active bull to frame centre.
                            val sample = OverlayView.AimSample(cxFrame - ab.cx, cyFrame - ab.cy)
                            aimTrace.addLast(sample)
                            while (aimTrace.size > TRACE_MAX_SAMPLES) aimTrace.removeFirst()
                        }

                        binding.overlay.updateFrame(
                            result.bulls, activeIdx, result.card,
                            aimTrace.toList(), shots.toList(), w, h,
                        )
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
    private data class Cand(val cx: Float, val cy: Float, val r: Float, val score: Double)

    /**
     * Cluster-based detection (tuned against ShotScorer/tools/detect.py).
     *   1. Hough on the whole downscaled frame with wide radius net.
     *   2. Filter candidates by dark-inside/light-outside contrast (>= 40).
     *   3. Non-max suppression: drop lower-scoring overlaps.
     *   4. Cluster survivors by spatial proximity (union-find).
     *   5. Pick the largest cluster (tiebreak: closest to frame centre) as
     *      the "active card". Its bounding box becomes the card overlay.
     *
     * Rationale: at real ranges, brightness-based card detection fails (the
     * whole wall is bright). Real bulls score 55-90 on contrast; scene
     * clutter (bullet holes, wood grain, shadows) scores < 40. That gap
     * eliminates false positives before clustering runs.
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
            val enhanced = raw.clone()
            clahe.apply(enhanced, enhanced)
            Imgproc.medianBlur(enhanced, enhanced, 3)

            val circles = Mat()
            val cands: List<Cand> = try {
                val minR = BULL_MIN_R
                val maxR = kmin(BULL_MAX_R_ABS,
                    (kmin(smallW, smallH) * BULL_MAX_R_FRAC).toInt().coerceAtLeast(20))
                Imgproc.HoughCircles(
                    enhanced, circles, Imgproc.HOUGH_GRADIENT,
                    1.2,
                    (minR * 3).toDouble(),
                    100.0,
                    13.0,
                    minR,
                    maxR
                )
                if (circles.empty()) {
                    Log.d(TAG, "no circles (proc ${smallW}x${smallH})")
                    return DetectResult(emptyList(), null)
                }
                val n = circles.cols()
                val list = ArrayList<Cand>(n)
                val d = FloatArray(3)
                for (i in 0 until n) {
                    circles.get(0, i, d)
                    val score = bullContrastScore(raw, d[0].toInt(), d[1].toInt(), d[2].toInt())
                    if (score >= CONTRAST_THRESHOLD) list.add(Cand(d[0], d[1], d[2], score))
                }
                list.sortByDescending { it.score }
                nonMaxSuppress(list)
            } finally {
                circles.release()
                enhanced.release()
            }

            Log.d(TAG, "circles after contrast+NMS: ${cands.size}")
            if (cands.isEmpty()) return DetectResult(emptyList(), null)

            val clusters = clusterByProximity(cands, CLUSTER_EPS_R_MUL)
            val valid = clusters.filter { it.size >= CLUSTER_MIN_BULLS }
            if (valid.isEmpty()) {
                // No confident card — show survivors amber, no card box.
                val all = cands.map { toBull(it, scale) }
                return DetectResult(all.take(15), null)
            }

            val fcx = smallW / 2f
            val fcy = smallH / 2f
            val aim = valid.maxByOrNull { g ->
                val meanX = g.map { it.cx }.average().toFloat()
                val meanY = g.map { it.cy }.average().toFloat()
                // Primary: count. Tiebreak: closeness to centre (negate distance).
                g.size.toDouble() -
                    (((meanX - fcx) * (meanX - fcx) + (meanY - fcy) * (meanY - fcy)) / 1e9)
            }!!

            val bulls = aim.map { toBull(it, scale) }
            val xs = aim.map { it.cx }
            val ys = aim.map { it.cy }
            val rs = aim.map { it.r }
            val pad = (rs.max()) * 1.2f
            val x0 = (xs.min() - pad).coerceAtLeast(0f)
            val y0 = (ys.min() - pad).coerceAtLeast(0f)
            val x1 = (xs.max() + pad).coerceAtMost(smallW.toFloat())
            val y1 = (ys.max() + pad).coerceAtMost(smallH.toFloat())
            val card = OverlayView.CardRect(
                x = (x0 / scale).toFloat(),
                y = (y0 / scale).toFloat(),
                w = ((x1 - x0) / scale).toFloat(),
                h = ((y1 - y0) / scale).toFloat(),
            )
            return DetectResult(bulls, card)
        } finally {
            full.release()
            small.release()
            raw.release()
        }
    }

    private fun toBull(c: Cand, scale: Double) = OverlayView.Bull(
        cx = (c.cx / scale).toFloat(),
        cy = (c.cy / scale).toFloat(),
        r = (c.r / scale).toFloat(),
    )

    /** Drop lower-scoring circles whose centre falls within max(r) of a kept one. */
    private fun nonMaxSuppress(sorted: List<Cand>): List<Cand> {
        val kept = ArrayList<Cand>(sorted.size)
        for (c in sorted) {
            var conflict = false
            for (k in kept) {
                val dx = c.cx - k.cx
                val dy = c.cy - k.cy
                val thresh = kotlin.math.max(c.r, k.r) * NMS_OVERLAP_R_MUL
                if (dx * dx + dy * dy < thresh * thresh) { conflict = true; break }
            }
            if (!conflict) kept.add(c)
            if (kept.size >= MAX_BULLS_KEPT) break
        }
        return kept
    }

    /** Union-find clustering: two bulls linked if distance ≤ eps × mean radius. */
    private fun clusterByProximity(bulls: List<Cand>, eps: Float): List<List<Cand>> {
        val n = bulls.size
        val parent = IntArray(n) { it }
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = bulls[i]; val b = bulls[j]
            val e = ((a.r + b.r) / 2f) * eps
            val dx = a.cx - b.cx; val dy = a.cy - b.cy
            if (dx * dx + dy * dy <= e * e) {
                val ra = find(i); val rb = find(j)
                if (ra != rb) parent[ra] = rb
            }
        }
        val groups = HashMap<Int, ArrayList<Cand>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { ArrayList() }.add(bulls[i])
        return groups.values.sortedByDescending { it.size }
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
