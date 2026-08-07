package com.shotscorer.app

import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCParam
import com.shotscorer.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShotScorer"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
    }

    private lateinit var binding: ActivityMainBinding
    private var cameraHelper: ICameraHelper? = null
    private var currentDevice: UsbDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.preview.setAspectRatio(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        binding.preview.holder.addCallback(surfaceCallback)
    }

    override fun onStart() {
        super.onStart()
        initCameraHelper()
    }

    override fun onStop() {
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
            cameraHelper?.openCamera(
                UVCParam(DEFAULT_WIDTH, DEFAULT_HEIGHT, null),
                null
            )
        }

        override fun onCameraOpen(device: UsbDevice) {
            Log.d(TAG, "onCameraOpen ${device.deviceName}")
            val size: Size? = cameraHelper?.previewSize
            val w = size?.width ?: DEFAULT_WIDTH
            val h = size?.height ?: DEFAULT_HEIGHT
            runOnUiThread { binding.preview.setAspectRatio(w, h) }
            cameraHelper?.addSurface(binding.preview.holder.surface, false)
            cameraHelper?.startPreview()
            setStatus(getString(R.string.status_opened, w, h))
        }

        override fun onCameraClose(device: UsbDevice) {
            Log.d(TAG, "onCameraClose ${device.deviceName}")
            cameraHelper?.removeSurface(binding.preview.holder.surface)
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
