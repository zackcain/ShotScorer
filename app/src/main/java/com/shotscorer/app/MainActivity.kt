package com.shotscorer.app

import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.VideoCapture
import com.shotscorer.app.databinding.ActivityMainBinding
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

        binding.preview.setAspectRatio(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        binding.preview.holder.addCallback(surfaceCallback)

        binding.recordButton.setOnClickListener { toggleRecording() }

        binding.afSwitch.setOnCheckedChangeListener { _, checked ->
            val helper = cameraHelper ?: return@setOnCheckedChangeListener
            val ctrl = helper.getUVCControl() ?: return@setOnCheckedChangeListener
            try {
                if (ctrl.isFocusAutoEnable) {
                    ctrl.focusAuto = checked
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AF toggle failed", t)
            }
            binding.focusSlider.isEnabled = !checked && (ctrl.isFocusAbsoluteEnable)
        }

        binding.focusSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                binding.focusValue.text = getString(R.string.focus_manual_pct, progress)
                if (!fromUser) return
                val ctrl = cameraHelper?.getUVCControl() ?: return
                try {
                    if (ctrl.isFocusAbsoluteEnable) {
                        ctrl.setFocusAbsolutePercent(progress)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Focus set failed", t)
                }
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
        }
    }

    private fun toggleRecording() {
        val helper = cameraHelper ?: return
        if (helper.isRecording) {
            stopRecordingSafely()
        } else {
            startRecordingSafely(helper)
        }
    }

    private fun startRecordingSafely(helper: ICameraHelper) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "ShotScorer-$stamp.mp4")

        val options = VideoCapture.OutputFileOptions.Builder(outFile).build()
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
                    val uri: Uri? = results.savedUri
                    Log.i(TAG, "Video saved: $uri (path=${outFile.absolutePath})")
                    runOnUiThread {
                        isRecording = false
                        binding.recordButton.text = getString(R.string.btn_record)
                        binding.recStatus.text = getString(R.string.rec_saved, outFile.name)
                        Toast.makeText(this@MainActivity, outFile.absolutePath, Toast.LENGTH_LONG).show()
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

    private fun stopRecordingSafely() {
        try {
            cameraHelper?.stopRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "stopRecording threw", t)
        }
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun applyFocusUi() {
        val helper = cameraHelper ?: return
        val ctrl = helper.getUVCControl()
        if (ctrl == null) {
            binding.focusRow.visibility = android.view.View.GONE
            return
        }
        val afSupported = try { ctrl.isFocusAutoEnable } catch (_: Throwable) { false }
        val manualSupported = try { ctrl.isFocusAbsoluteEnable } catch (_: Throwable) { false }

        if (!afSupported && !manualSupported) {
            binding.focusRow.visibility = android.view.View.GONE
            binding.recStatus.text = getString(R.string.focus_unsupported)
            return
        }
        binding.focusRow.visibility = android.view.View.VISIBLE

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
            runOnUiThread {
                binding.recordButton.isEnabled = false
                binding.focusRow.visibility = android.view.View.GONE
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
}
