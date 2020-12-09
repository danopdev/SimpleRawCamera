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
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.dan.simplerawcamera.databinding.ActivityMainBinding
import java.lang.Exception
import java.util.*
import kotlin.collections.ArrayList
import kotlin.concurrent.schedule
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

        fun getBestResolution( targetWidth: Int, targetRatio: Float, sizes: Array<Size> ): Size {
            var bestSize = sizes.last()

            for (size in sizes) {
                if (size.width > targetWidth) continue
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
    private var cameraIndex = 0
    private lateinit var cameraHandler: CameraHandler
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null

    private var isoValue = 100
    private var isoManual = false

    private var speedValueNumerator = 1
    private var speedValueDenominator = 128
    private var speedManual = false

    private var exposureCompensationValue = 0

    private var rotatedPreviewWidth = 4
    private var rotatedPreviewHeight = 3

    private var firstCall = true

    private val surfaceHolderCallback = object: SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            selectCamera(cameraIndex)

            if (firstCall) {
                Timer().schedule(500) {
                    runOnUiThread {
                        selectCamera(cameraIndex)
                    }
                }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
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
            val previewSize = getBestResolution(
                binding.surfaceView.width,
                cameraHandler.resolutionWidth.toFloat() / cameraHandler.resolutionHeight,
                sizes )

            rotatedPreviewWidth = if (cameraHandler.areDimensionsSwapped) previewSize.height else previewSize.width
            rotatedPreviewHeight = if (cameraHandler.areDimensionsSwapped) previewSize.width else previewSize.height

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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)

        setContentView(binding.root)

        binding.surfaceView.holder.addCallback( surfaceHolderCallback )

        binding.btnCamera.setOnClickListener {
            selectCamera((cameraIndex + 1) % cameraList.size)
        }

        showIso(isoValue)
        showSpeed(speedValueNumerator, speedValueDenominator)
        showExpComponsation(exposureCompensationValue)

        SeekBarDirectionTracker.track( binding.seekBarIso ) { delta, isFinal -> trackIso( delta, isFinal ) }
        SeekBarDirectionTracker.track( binding.seekBarSpeed ) { delta, isFinal -> trackSpeed( delta, isFinal ) }
        SeekBarDirectionTracker.track( binding.seekBarExpComponsation ) { delta, isFinal -> trackExpComponsation( delta, isFinal ) }
    }

    private fun trackIso( delta: Int, isFinal: Boolean) {
        if (!isoManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var value = isoValue

        while (counter > 0) {
            if (increase) {
                if (value >= cameraHandler.isoRange.upper) break
                value *= 2
            } else {
                if (value <= cameraHandler.isoRange.lower) break
                value /= 2
            }
            counter -= 1
        }

        showIso(value)

        if (isFinal)
            isoValue = value
    }

    private fun showIso( value: Int ) {
        binding.txtIso.text = "ISO: ${value}"
    }

    private fun trackExpComponsation( delta: Int, isFinal: Boolean) {
        if (isoManual && speedManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var value = exposureCompensationValue

        while (counter > 0) {
            if (increase) {
                if (value >= cameraHandler.exposureCompensantionRange.upper) break
                value++
            } else {
                if (value <= cameraHandler.exposureCompensantionRange.lower) break
                value--
            }
            counter -= 1
        }

        showExpComponsation(value)

        if (isFinal)
            exposureCompensationValue = value
    }

    private fun showExpComponsation( value: Int ) {
        binding.txtExpComponsation.text = "Exp: ${value}"
    }

    private fun speedToNanoseconds( numerator: Int, denominator: Int ): Long = 1000000000L * numerator / denominator

    private fun trackSpeed( delta: Int, isFinal: Boolean) {
        if (!speedManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var numerator = speedValueNumerator
        var denominator = speedValueDenominator

        while (counter > 0) {
            val speed = speedToNanoseconds(numerator, denominator)

            if (increase) {
                if ((2*speed) > cameraHandler.speedRange.upper || numerator >= 4) break
                if (denominator > 1) {
                    denominator /= 2
                } else {
                    numerator += 1
                }
            } else {
                if ((speed/2) < cameraHandler.speedRange.lower || denominator >= 32768) break
                if (numerator > 1) {
                    numerator -= 1
                } else {
                    denominator *= 2
                }
            }
            counter -= 1
        }

        showSpeed(numerator, denominator)

        if (isFinal) {
            speedValueNumerator = numerator
            speedValueDenominator = denominator
        }
    }

    private fun showSpeed( numerator: Int, denominator: Int ) {
        val roundedDenominator =
            if (denominator >= 1000)
                (denominator / 1000) * 1000
            else if (denominator >= 500 )
                (denominator / 100) * 100
            else if (128 == denominator)
                125
            else if (denominator >= 30 )
                (denominator / 10) * 10
            else if (16 == denominator)
                15
            else
                denominator

        binding.txtSpeed.text = "Speed: " +
            if (1 == denominator)
                "${numerator}\""
            else
                "1/${roundedDenominator}"
    }

    @SuppressLint("MissingPermission")
    private fun selectCamera(index: Int) {
        cameraIndex = index
        cameraHandler = cameraList[index]

        val cameraCaptureSession = this.cameraCaptureSession
        if (null != cameraCaptureSession) {
            cameraCaptureSession.close()
            this.cameraCaptureSession = null
        }

        val cameraDevice = this.cameraDevice
        if (null != cameraDevice) {
            cameraDevice.close()
            this.cameraDevice = null
        }

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