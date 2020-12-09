package com.dan.simplerawcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.util.Size
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
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

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mCameraManager: CameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val mCameraList: ArrayList<CameraHandler> by lazy { CameraHandler.getValidCameras(mCameraManager) }
    private var mCameraIndex = 0
    private lateinit var mCameraHandler: CameraHandler
    private var mCameraDevice: CameraDevice? = null
    private var mCameraCaptureSession: CameraCaptureSession? = null
    private var mCaptureRequestBuilder: CaptureRequest.Builder? = null
    private var mCaptureRequest: CaptureRequest? = null

    private var mIsoValue = 100
    private var mIsoIsManual = false

    private var mSpeedValueNumerator = 10
    private var mSpeedValueDenominator = 128
    private var mSpeedIsManual = false

    private var mExposureCompensationValue = 0

    private var mRotatedPreviewWidth = 4
    private var mRotatedPreviewHeight = 3

    private var mFirstCall = true

    private val mSurfaceHolderCallback = object: SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            selectCamera(mCameraIndex)

            if (mFirstCall) {
                mFirstCall = false
                Timer().schedule(500) {
                    runOnUiThread {
                        selectCamera(mCameraIndex)
                    }
                }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
        }
    }

    private val mCameraCaptureSessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {}

        override fun onConfigured(session: CameraCaptureSession) {
            val cameraDevice = mCameraDevice ?: return

            mCameraCaptureSession = session

            val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(mBinding.surfaceView.holder.surface)
            mCaptureRequestBuilder = captureRequestBuilder

            setupCaptureRequest()
        }
    }

    private val mCameraCaptureSessionCaptureCallback = object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)

            if (!mIsoIsManual)
                showIso(result.get(CaptureResult.SENSOR_SENSITIVITY) as Int)

            if (!mSpeedIsManual) {
                val speedInNanoseconds = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) as Long
                if (speedInNanoseconds >= 1000000000L)
                    showSpeed((speedInNanoseconds/100000000L).toInt(), 1)
                else
                    showSpeed(1, (1000000000L / speedInNanoseconds).toInt())
            }
        }
    }

    private val mCameraDeviceStateCallback = object: CameraDevice.StateCallback() {
        override fun onDisconnected(p0: CameraDevice) {}

        override fun onError(p0: CameraDevice, p1: Int) {}

        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice

            val sizes = mCameraHandler.streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)
            if (null == sizes || 0 == sizes.size) throw Exception("No sizes available")
            val previewSize = getBestResolution(
                mBinding.surfaceView.width,
                mCameraHandler.resolutionWidth.toFloat() / mCameraHandler.resolutionHeight,
                sizes )

            mRotatedPreviewWidth = if (mCameraHandler.areDimensionsSwapped) previewSize.height else previewSize.width
            mRotatedPreviewHeight = if (mCameraHandler.areDimensionsSwapped) previewSize.width else previewSize.height

            mBinding.surfaceView.holder.setFixedSize(mRotatedPreviewWidth, mRotatedPreviewHeight)
            cameraDevice.createCaptureSession(
                mutableListOf(mBinding.surfaceView.holder.surface),
                mCameraCaptureSessionStateCallback,
                Handler { true }
            )
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
        if (mCameraList.size <= 0) {
            fatalError("No valid camera found !")
            return
        }

        if (1 == mCameraList.size) {
            mBinding.txtCamera.isVisible = false
            mBinding.btnCamera.isVisible = false
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)

        setContentView(mBinding.root)

        mBinding.surfaceView.holder.addCallback( mSurfaceHolderCallback )

        mBinding.btnCamera.setOnClickListener {
            selectCamera((mCameraIndex + 1) % mCameraList.size)
        }

        showIso(mIsoValue)
        showSpeed(mSpeedValueNumerator, mSpeedValueDenominator)
        showExpComponsation(mExposureCompensationValue)

        SeekBarDirectionTracker.track( mBinding.seekBarIso ) { delta, isFinal -> trackIso( delta, isFinal ) }
        SeekBarDirectionTracker.track( mBinding.seekBarSpeed ) { delta, isFinal -> trackSpeed( delta, isFinal ) }
        SeekBarDirectionTracker.track( mBinding.seekBarExpComponsation ) { delta, isFinal -> trackExpComponsation( delta, isFinal ) }

        mBinding.txtIso.setOnClickListener {
            mIsoIsManual = !mIsoIsManual
            updateSliders()
        }

        mBinding.txtSpeed.setOnClickListener {
            mSpeedIsManual = !mSpeedIsManual
            updateSliders()
        }
    }

    private fun trackIso( delta: Int, isFinal: Boolean) {
        if (!mIsoIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var value = mIsoValue

        while (counter > 0) {
            if (increase) {
                if (value >= mCameraHandler.isoRange.upper) break
                value *= 2
            } else {
                if (value <= mCameraHandler.isoRange.lower) break
                value /= 2
            }
            counter -= 1
        }

        showIso(value)

        if (isFinal)
            mIsoValue = value
    }

    private fun showIso( value: Int ) {
        mBinding.txtIso.text = "${value} ISO"
    }

    private fun trackExpComponsation( delta: Int, isFinal: Boolean) {
        if (mIsoIsManual && mSpeedIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var value = mExposureCompensationValue

        while (counter > 0) {
            if (increase) {
                if (value >= mCameraHandler.exposureCompensantionRange.upper) break
                value++
            } else {
                if (value <= mCameraHandler.exposureCompensantionRange.lower) break
                value--
            }
            counter -= 1
        }

        showExpComponsation(value)

        if (isFinal) {
            mExposureCompensationValue = value
            setupCaptureRequest()
        }
    }

    private fun showExpComponsation( value: Int ) {
        mBinding.txtExpComponsation.text = "Exp: " +
            if (value >= 0)
                "+${value}"
            else
                value.toString()
    }

    private fun speedToNanoseconds( numerator: Int, denominator: Int ): Long = 1000000000L * numerator / denominator

    private fun trackSpeed( delta: Int, isFinal: Boolean) {
        if (!mSpeedIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var numerator = mSpeedValueNumerator
        var denominator = mSpeedValueDenominator

        while (counter > 0) {
            val speed = speedToNanoseconds(numerator, denominator)

            if (increase) {
                if ((2*speed) > mCameraHandler.speedRange.upper || numerator >= 4) break
                if (denominator > 1) {
                    denominator /= 2
                } else {
                    numerator += 10
                }
            } else {
                if ((speed/2) < mCameraHandler.speedRange.lower || denominator >= 32768) break
                if (numerator > 10) {
                    numerator -= 10
                } else {
                    denominator *= 2
                }
            }
            counter -= 1
        }

        showSpeed(numerator, denominator)

        if (isFinal) {
            mSpeedValueNumerator = numerator
            mSpeedValueDenominator = denominator
        }
    }

    private fun showSpeed( numerator: Int, denominator: Int ) {
        if (1 == denominator) {
            val rest = numerator % 10
            if (0 == rest)
                mBinding.txtSpeed.text = "${numerator/10}\""
            else
                mBinding.txtSpeed.text = "${numerator/10}.${rest}\""
        } else {
            val roundedDenominator =
                if (denominator >= 1000)
                    (denominator / 1000) * 1000
                else if (denominator >= 500)
                    (denominator / 100) * 100
                else if (128 == denominator)
                    125
                else if (denominator >= 30)
                    (denominator / 10) * 10
                else if (16 == denominator)
                    15
                else
                    denominator

            mBinding.txtSpeed.text = "1/${roundedDenominator}"
        }
    }

    private fun updateSliders() {
        mBinding.seekBarIso.visibility = if (mIsoIsManual) View.VISIBLE else View.INVISIBLE
        mBinding.seekBarSpeed.visibility = if (mSpeedIsManual) View.VISIBLE else View.INVISIBLE
        mBinding.txtExpComponsation.visibility = if (!mIsoIsManual || !mSpeedIsManual) View.VISIBLE else View.INVISIBLE
        mBinding.seekBarExpComponsation.visibility = mBinding.txtExpComponsation.visibility
    }

    @SuppressLint("MissingPermission")
    private fun selectCamera(index: Int) {
        mCameraIndex = index
        mCameraHandler = mCameraList[index]

        val cameraCaptureSession = mCameraCaptureSession
        if (null != cameraCaptureSession) {
            cameraCaptureSession.close()
            mCameraCaptureSession = null
        }

        val cameraDevice = mCameraDevice
        if (null != cameraDevice) {
            cameraDevice.close()
            mCameraDevice = null
        }

        mCaptureRequest = null

        mBinding.txtCamera.text = index.toString()

        val set = ConstraintSet()
        set.clone(mBinding.layoutView)
        set.setDimensionRatio(
            mBinding.frameView.getId(),
            "${mCameraHandler.resolutionWidth}:${mCameraHandler.resolutionHeight}"
        )
        set.applyTo(mBinding.layoutView)

        mCameraManager.openCamera(mCameraHandler.id, mCameraDeviceStateCallback, Handler { true } )

        updateSliders()
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

    private fun setupCaptureRequest() {
        val captureRequestBuilder = mCaptureRequestBuilder ?: return
        val cameraCaptureSession = mCameraCaptureSession ?: return

        if (mCameraHandler.supportLensStabilisation)
            captureRequestBuilder.set( CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON )

        captureRequestBuilder.set( CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY )
        captureRequestBuilder.set( CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO )
        captureRequestBuilder.set( CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO )

        if (!mIsoIsManual && !mSpeedIsManual) {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, mExposureCompensationValue)
        }

        //captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)

        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val captureRequest = captureRequestBuilder.build()
        mCaptureRequest = captureRequest

        cameraCaptureSession.setRepeatingRequest(
            captureRequest,
            mCameraCaptureSessionCaptureCallback /*object : CameraCaptureSession.CaptureCallback() {}*/,
            Handler { true }
        )
    }
}