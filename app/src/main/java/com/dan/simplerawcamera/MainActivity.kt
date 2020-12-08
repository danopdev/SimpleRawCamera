package com.dan.simplerawcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.util.Size
import android.view.SurfaceHolder
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.dan.simplerawcamera.databinding.ActivityMainBinding
import java.lang.Exception
import kotlin.math.abs
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        const val REQUEST_PERMISSIONS = 1

        fun getBestResolution( targetWidth: Int, targetHeight: Int, sizes: Array<Size> ): Size {
            var bestSize = sizes.last()
            val targetRatio = targetWidth.toFloat() / targetHeight

            for (size in sizes) {
                if (size.width > targetWidth || size.height > targetHeight) continue
                val ratio = size.width.toFloat() / size.height
                if (abs(ratio - targetRatio) < 0.2) {
                    if (bestSize.width < size.width)
                        bestSize = size
                }
            }

            return bestSize
        }
    }

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val cameraManager: CameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val cameraList: ArrayList<CameraHandler> by lazy { CameraHandler.getValidCameras(cameraManager) }
    private lateinit var cameraHandler: CameraHandler
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null

    private var isoValue = 100
    private var isoAuto = false

    private var speedValue = 1/125f
    private var speedAuto = false

    private var exposureCompensationValue = 0

    private val surfaceHolderCallback = object: SurfaceHolder.Callback {
        override fun surfaceCreated(p0: SurfaceHolder) {
            selectCamera(0)
        }

        override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        }

        override fun surfaceDestroyed(p0: SurfaceHolder) {
        }
    }

    val cameraCaptureSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {}

        override fun onConfigured(session: CameraCaptureSession) {
            val cameraDevice_ = cameraDevice ?: return

            cameraCaptureSession = session

            val previewRequestBuilder =
                cameraDevice_.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(binding.surfaceView.holder.surface)
                }

            session.setRepeatingRequest(
                previewRequestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {},
                Handler { true }
            )
        }
    }

    private val cameraStateCallback = object: CameraDevice.StateCallback() {
        override fun onDisconnected(p0: CameraDevice) {}

        override fun onError(p0: CameraDevice, p1: Int) {}

        override fun onOpened(cameraDevice_: CameraDevice) {
            cameraDevice = cameraDevice_

            val sizes = cameraHandler.streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)
            if (null == sizes || 0 == sizes.size) throw Exception("No sizes available")
            val previewSize = getBestResolution( binding.surfaceView.width, binding.surfaceView.height, sizes )

            val rotatedPreviewWidth = if (cameraHandler.areDimensionsSwapped) previewSize.height else previewSize.width
            val rotatedPreviewHeight = if (cameraHandler.areDimensionsSwapped) previewSize.width else previewSize.height

            binding.surfaceView.holder.setFixedSize(rotatedPreviewWidth, rotatedPreviewHeight)
            cameraDevice_.createCaptureSession(mutableListOf(binding.surfaceView.holder.surface), cameraCaptureSessionCallback, Handler { true })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!askPermissions())
            onPermissionsAllowed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    private fun showSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> handleRequestPermissions(requestCode, permissions, grantResults)
        }
    }

    private fun exitApp() {
        setResult(0)
        finish()
        exitProcess(0)
    }

    private fun fatalError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(msg)
            .setIcon(android.R.drawable.stat_notify_error)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> exitApp() }
            .show()
    }

    private fun handleRequestPermissions(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        var allowedAll = grantResults.size >= PERMISSIONS.size

        if (grantResults.size >= PERMISSIONS.size) {
            for ( result in grantResults ) {
                if (result != PackageManager.PERMISSION_GRANTED ) {
                    allowedAll = false
                    break
                }
            }
        }

        if( allowedAll ) {
            onPermissionsAllowed()
        } else {
            fatalError("Permissions are mandatory !")
        }
    }

    private fun onPermissionsAllowed() {
        if (cameraList.size <= 0) {
            fatalError("No valid camera found !")
            return
        }

        if (1 == cameraList.size) {
            binding.txtCamera.isVisible = false
            binding.btnCamera.isVisible = false
        }

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)

        setContentView(binding.root)

        binding.surfaceView.holder.addCallback( surfaceHolderCallback )
    }

    @SuppressLint("MissingPermission")
    private fun selectCamera(index: Int) {
        cameraHandler = cameraList[index]

        binding.txtCamera.text = index.toString()

        val set = ConstraintSet()
        set.clone(binding.layoutView)
        set.setDimensionRatio(
            binding.frameView.getId(),
            "${cameraHandler.resolutionWidth}:${cameraHandler.resolutionHeight}"
        )
        set.applyTo(binding.layoutView)

        cameraManager.openCamera(cameraHandler.id, cameraStateCallback, Handler { true } )
    }

    private fun askPermissions(): Boolean {
        for (permission in PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS)
                return true
            }
        }

        return false
    }
}