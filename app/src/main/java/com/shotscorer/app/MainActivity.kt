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
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

    private data class BullResult(val cx: Float, val cy: Float, val r: Float, val quality: Float)

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
                val result = detectBull(yBytes, w, h)
                runOnUiThread {
                    if (result != null) {
                        binding.overlay.updateBull(result.cx, result.cy, result.r, result.quality, w, h)
                    } else {
                        binding.overlay.clearBull()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "detection failed", t)
            } finally {
                detecting.set(false)
            }
        }
    }

    private fun detectBull(y: ByteArray, w: Int, h: Int): BullResult? {
        // Downscale to ~640 wide for speed
        val targetW = 640
        val scale = targetW.toDouble() / w
        val smallW = targetW
        val smallH = (h * scale).toInt()

        val full = Mat(h, w, CvType.CV_8UC1)
        full.put(0, 0, y)
        val small = Mat()
        Imgproc.resize(full, small, CvSize(smallW.toDouble(), smallH.toDouble()))

        // Restrict search to central 60% ROI — rejects drill/box/shelf clutter
        val roiW = (smallW * 0.6).toInt()
        val roiH = (smallH * 0.6).toInt()
        val roiX = (smallW - roiW) / 2
        val roiY = (smallH - roiH) / 2
        val roi = small.submat(roiY, roiY + roiH, roiX, roiX + roiW)

        Imgproc.medianBlur(roi, roi, 5)

        val circles = Mat()
        try {
            // Radius bounds tuned for target that fills ~200-500 px of native 4K.
            // In the 640-wide small image that's ~30-80 px card, black ring ~10-40 px.
            val minR = (smallH * 0.02).toInt().coerceAtLeast(5)   // ~4 px @ 360h -> ~24 px @ 4K
            val maxR = (smallH * 0.20).toInt().coerceAtLeast(30)  // ~72 px @ 360h -> ~430 px @ 4K
            Imgproc.HoughCircles(
                roi, circles, Imgproc.HOUGH_GRADIENT,
                1.2,
                (smallH / 4).toDouble(),  // minDist between circle centers
                120.0,                    // Canny edge threshold
                50.0,                     // accumulator threshold — higher = fewer, stricter
                minR,
                maxR
            )
            if (circles.empty()) {
                Log.d(TAG, "no circles found")
                return null
            }
            Log.d(TAG, "circles found: ${circles.cols()} (roi ${roiW}x${roiH}, r=$minR..$maxR)")
            val data = FloatArray(3)
            circles.get(0, 0, data)
            // Data is in ROI coords -> shift back to small frame, then upscale to full
            val smallCx = data[0] + roiX
            val smallCy = data[1] + roiY
            return BullResult(
                cx = (smallCx / scale).toFloat(),
                cy = (smallCy / scale).toFloat(),
                r = (data[2] / scale).toFloat(),
                quality = 1.0f,
            )
        } finally {
            full.release()
            small.release()
            roi.release()
            circles.release()
        }
    }
}
