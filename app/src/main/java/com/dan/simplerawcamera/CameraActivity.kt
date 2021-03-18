package com.dan.simplerawcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.location.Location
import android.location.LocationManager
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.dan.simplerawcamera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.schedule
import kotlin.concurrent.timer
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.system.exitProcess


/**
 Main camera view
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        const val REQUEST_PERMISSIONS = 1
        const val INTENT_SELECT_FOLDER = 2

        const val HISTOGRAM_BITMAP_WIDTH = 64
        const val HISTOGRAM_BITMAP_HEIGHT = 50

        const val FOCUS_REGION_SIZE_PERCENT = 5

        const val PHOTO_TAKE_COMPLETED = 1
        const val PHOTO_TAKE_JPEG = 2
        const val PHOTO_TAKE_DNG = 4
        const val PHOTO_TAKE_OUT_OF_MEMORY = 8

        const val FOCUS_STATE_MANUAL = 0
        const val FOCUS_STATE_CLICK = 1
        const val FOCUS_STATE_SEARCHING = 2
        const val FOCUS_STATE_LOCKED = 3

        const val MEMORY_RETRY_TIMEOUT = 250L //ms

        const val SELECT_CAMERA_ASYNC_DELAY = 250L //ms

        const val HIGHLIGHTS_STATUS_OK = 0
        const val HIGHLIGHTS_STATUS_OVER_EXPOSED = 1
        const val HIGHLIGHTS_STATUS_UNDER_EXPOSED = 2

        const val CAMERA_WAIT_CONFIGURATION_COUNTER = 2

        val FILE_NAME_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

        fun getMemInfo(): Pair<Long, Long> {
            val info = Runtime.getRuntime()
            val usedSize = (info.totalMemory() - info.freeMemory()) / (1024L * 1024L)
            val maxSize = info.maxMemory() / (1024L * 1024L)
            val freeSize = maxSize - usedSize
            return Pair(freeSize, maxSize)
        }

        fun getFreeMemInfo(): Long = getMemInfo().first

        /** Get photo name */
        fun getPhotoBaseFileName(timestamp: Long): String = FILE_NAME_DATE_FORMAT.format(Date(timestamp))

        /** Get best resolution for preview */
        fun getBestResolution(targetWidth: Int, targetRatio: Float, sizes: Array<Size>): Size {
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

        /** Calculate the difference between the preview / histogram and the manual / semi-manual photo settings */
        fun calculateExpDeviation(previewIso: Int, previewSpeed: Long, expectedIso: Int, expectedSpeed: Long): Float {

            val deltaExpIso: Float =
                if (previewIso >= expectedIso)
                    log2(previewIso.toFloat() / expectedIso)
                else
                    -log2(expectedIso.toFloat() / previewIso)

            val deltaExpSpeed: Float =
                if (previewSpeed >= expectedSpeed)
                    log2(previewSpeed.toFloat() / expectedSpeed)
                else
                    -log2(expectedSpeed.toFloat() / previewSpeed)

            return deltaExpIso + deltaExpSpeed
        }
    }

    val settings: Settings by lazy { Settings(this) }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mLocationManager: LocationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val mCameraManager: CameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val mCameraList: ArrayList<CameraInfo> by lazy { CameraInfo.getValidCameras(mCameraManager) }

    private lateinit var mCameraInfo: CameraInfo
    private var mCameraDevice: CameraDevice? = null
    private var mCameraCaptureSession: CameraCaptureSession? = null
    private var mCaptureRequestBuilder: CaptureRequest.Builder? = null
    private var mCaptureRequest: CaptureRequest? = null
    private var mCaptureModeIsPhoto = false
    private var mCurrentPhotoCaptureResult: TotalCaptureResult? = null

    private var mCameraWaitConfigurationCounter = CAMERA_WAIT_CONFIGURATION_COUNTER

    private var mPhotoButtonPressed = false
    private var mPhotoTakeMask = 0
    private var mPhotoTimestamp = 0L
    private var mPhotoFileNameBase = ""
    private var mPhotoCounter = 0
    private var mPhotoInProgress = false

    private val mImageReaderHisto = ImageReader.newInstance(100, 100, ImageFormat.YUV_420_888, 1)
    private lateinit var mImageReaderJpeg: ImageReader
    private lateinit var mImageReaderDng: ImageReader
    private var mImageDng: Image? = null
    private var mImageJpeg: Image? = null

    private var mIsoMeasuredValue = 100
    private var mSpeedMeasuredValue = 7812500L // 1/128
    private var mIsoManualPreviewValue = 100
    private var mSpeedManualPreviewValue = 7812500L // 1/128

    private var mFocusState = FOCUS_STATE_MANUAL
    private var mFocusClick = false
    private var mFocusClickPosition = Point(0, 0)

    private var mRotatedPreviewWidth = 4
    private var mRotatedPreviewHeight = 3

    private var mFirstCall = true
    private var mSurfaceIsCreated = false

    private var mSaveFolder: DocumentFile? = null

    private var mLocation: Location? = null

    private val mSaveAsyncMQ = mutableListOf<Triple<String, String, ByteArray>>()
    private var mSaveAsyncBusy = false

    private var mOrientationEventListener: OrientationEventListener? = null
    private var mScreenOrientation: Int = 0
    private var mPhotoExifOrientation: Int = 0

    private var mSelectCameraTimer: Timer? = null

    private var mSequenceStarted = false
    private var mSequenceTimer: Timer? = null
    private var mSequencePhotoDelayCounter = 0

    /** Generate histogram */
    private val mImageReaderHistoListener = object: ImageReader.OnImageAvailableListener {
        private var mIsBusy = false

        private fun genHistogram(image: Image) {
            if (mCameraWaitConfigurationCounter > 0) mCameraWaitConfigurationCounter--

            if (mIsBusy && mCameraWaitConfigurationCounter > 0) return

            mIsBusy = true

            val imageW = image.width
            val imageH = image.height

            val yPlane = image.planes[0]
            val yPlaneBuffer = yPlane.buffer
            val yBytes = ByteArray(yPlaneBuffer.capacity())
            yPlaneBuffer.get(yBytes)

            val rowStride = yPlane.rowStride

            GlobalScope.launch(Dispatchers.Main) {
                val values = IntArray(HISTOGRAM_BITMAP_WIDTH)
                for (line in 0 until imageH) {
                    var index = line * rowStride
                    for (column in 0 until imageW) {
                        var yValue = yBytes[index].toInt()

                        if (yValue < 0) yValue += 256

                        when {
                            yValue < 5 -> yValue = 0
                            yValue >= 250 -> yValue = 245
                            else -> yValue -= 5
                        }

                        values[(HISTOGRAM_BITMAP_WIDTH - 1) * yValue / 245]++
                        index++
                    }
                }

                var maxHeight = 10
                for (value in values)
                    maxHeight = max(maxHeight, value)
                maxHeight++

                val bmpWidth = HISTOGRAM_BITMAP_WIDTH + 2
                val bmpHeight = HISTOGRAM_BITMAP_HEIGHT + 2
                val color = Color.rgb(192, 192, 192)
                val colors = IntArray(bmpWidth * bmpHeight)

                for (x in values.indices) {
                    val value = values[x]
                    val fill = HISTOGRAM_BITMAP_HEIGHT - 1 - (HISTOGRAM_BITMAP_HEIGHT - 1) * value / maxHeight

                    var y = 0
                    while (y <= fill) {
                        colors[x + 1 + (y + 1) * bmpWidth] = 0
                        y++
                    }
                    while (y < HISTOGRAM_BITMAP_HEIGHT) {
                        colors[x + 1 + (y + 1) * bmpWidth] = color
                        y++
                    }
                }

                val colorBorder = Color.rgb(64, 64, 64)

                for( x in 0 until bmpWidth) {
                    colors[x] = colorBorder
                    colors[x + (bmpHeight-1) * bmpWidth] = colorBorder
                }

                for( y in 1 until bmpHeight-1) {
                    colors[y * bmpWidth] = colorBorder
                    colors[bmpWidth - 1 + y * bmpWidth] = colorBorder
                }

                val bitmap = Bitmap.createBitmap(colors, 0, bmpWidth, bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)

                var highlightsStatus = HIGHLIGHTS_STATUS_OK

                var valuesCounter = 0
                var index = values.size - 1

                //check for over exposed
                while (index >= Settings.PROTECT_HIGHLIGHTS_OVER_EXPOSED_START_INDEX) {
                    valuesCounter += values[index]
                    index--
                }

                if (valuesCounter >= Settings.PROTECT_HIGHLIGHTS_OVER_EXPOSED_THRESHOLD) {
                    highlightsStatus = HIGHLIGHTS_STATUS_OVER_EXPOSED
                    Log.i("HIGH_LIGHTS", "Over exposed, counter=${valuesCounter}")
                } else {
                    //check for under exposed
                    while (index >= Settings.PROTECT_HIGHLIGHTS_UNDER_EXPOSED_START_INDEX) {
                        valuesCounter += values[index]
                        index--
                    }

                    if (valuesCounter <= Settings.PROTECT_HIGHLIGHTS_UNDER_EXPOSED_THRESHOLD) {
                        highlightsStatus = HIGHLIGHTS_STATUS_UNDER_EXPOSED
                        Log.i("HIGH_LIGHTS", "Under exposed, counter=${valuesCounter}")
                    }
                }

                runOnUiThread {
                    if (0 == mCameraWaitConfigurationCounter) {
                        mBinding.imgHistogram.setImageBitmap(bitmap)

                        if (Settings.SPEED_MODE_PROTECT_HIGHLIGHTS == settings.speedMode && HIGHLIGHTS_STATUS_OK != highlightsStatus) {
                            val newSpeedValue = mCameraInfo.getSpeed(
                                settings.speedValue,
                                if (HIGHLIGHTS_STATUS_OVER_EXPOSED == highlightsStatus) -1 else 1
                            )

                            if (newSpeedValue != settings.speedValue && newSpeedValue <= Settings.PROTECT_HIGHLIGHTS_MAX_SPEED) {
                                settings.speedValue = newSpeedValue
                                showSpeed(newSpeedValue)
                                setupCapturePreviewRequest()
                            }
                        }
                    }

                    mIsBusy = false
                }
            }
        }

        override fun onImageAvailable(imageReader: ImageReader?) {
            if (null == imageReader) return
            val image = imageReader.acquireLatestImage() ?: return
            genHistogram(image)
            image.close()
        }
    }

    /** JPEG Reader Listener */
    private val mImageReaderJpegListener =
        ImageReader.OnImageAvailableListener { imageReader ->
            Log.i("TAKE_PHOTO", "JPEG: Received")

            imageReader?.acquireLatestImage()?.let{
                mImageJpeg = it
            }

            mPhotoTakeMask = mPhotoTakeMask and PHOTO_TAKE_JPEG.inv()
            if (0 == mPhotoTakeMask) takePhoto(true)
        }

    /** DNG Reader Listener */
    private val mImageReaderDngListener =
        ImageReader.OnImageAvailableListener { imageReader ->
            Log.i("TAKE_PHOTO", "DNG: Received")

            imageReader?.acquireLatestImage()?.let{
                mImageDng = it
            }

            mPhotoTakeMask = mPhotoTakeMask and PHOTO_TAKE_DNG.inv()
            if (0 == mPhotoTakeMask) takePhoto(true)
        }

    /** Surface for preview */
    private val mSurfaceHolderCallback = object: SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            mSurfaceIsCreated = true
            selectCamera(settings.cameraIndex)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            selectCamera(settings.cameraIndex)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
        }
    }

    /** Camera state */
    private val mCameraCaptureSessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {}

        override fun onConfigured(session: CameraCaptureSession) {
            val cameraDevice = mCameraDevice ?: return

            mCameraCaptureSession = session

            val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(mBinding.surfaceView.holder.surface)
            captureRequestBuilder.addTarget(mImageReaderHisto.surface)

            setupCaptureInitRequest(captureRequestBuilder)

            mCaptureRequestBuilder = captureRequestBuilder

            setupCapturePreviewRequest(true)
        }
    }

    /** Take photo callback */
    private val mCameraCaptureSessionPhotoCaptureCallback = object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            Log.i("TAKE_PHOTO", "onCaptureCompleted")
            mCurrentPhotoCaptureResult = result
            mPhotoTakeMask = mPhotoTakeMask and PHOTO_TAKE_COMPLETED.inv()
            if (0 == mPhotoTakeMask) takePhoto(true)
        }
    }

    /** Preview callback */
    private val mCameraCaptureSessionPreviewCaptureCallback = object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)

            val isoMeasuredValue = result.get(CaptureResult.SENSOR_SENSITIVITY) as Int
            val speedMeasuredValue = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) as Long

            mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_MEASURED, "Measured - ISO ${isoMeasuredValue}, Speed ${getSpeedStr(speedMeasuredValue)} (${speedMeasuredValue})")

            mIsoMeasuredValue = isoMeasuredValue
            mSpeedMeasuredValue = speedMeasuredValue

            when(mFocusState) {
                FOCUS_STATE_CLICK -> {
                    val focusState = result.get(CaptureResult.CONTROL_AF_STATE) as Int
                    if (CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN == focusState) {
                        mFocusState = FOCUS_STATE_SEARCHING
                    }
                }

                FOCUS_STATE_SEARCHING -> {
                    val focusState = result.get(CaptureResult.CONTROL_AF_STATE) as Int
                    if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == focusState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == focusState) {
                        mFocusState = FOCUS_STATE_LOCKED
                        runOnUiThread {
                            setupCapturePreviewRequest()
                        }
                    }
                }
            }

            val captureEA = getCaptureEA()
            mBinding.txtExpDelta.visibility = if (captureEA.third < -0.1 || captureEA.third > 0.1) View.VISIBLE else View.INVISIBLE
            mBinding.txtExpDelta.text = "%.2f".format(captureEA.third)

            if (Settings.ISO_MODE_AUTO != settings.isoMode && Settings.SPEED_MODE_AUTO != settings.speedMode) return

            if (Settings.ISO_MODE_AUTO == settings.isoMode) showIso(captureEA.first)
            if (Settings.SPEED_MODE_AUTO == settings.speedMode) showSpeed(captureEA.second)
        }
    }

    /** Camera state */
    private val mCameraDeviceStateCallback = object: CameraDevice.StateCallback() {
        override fun onDisconnected(p0: CameraDevice) {}

        override fun onError(p0: CameraDevice, p1: Int) {}

        @Suppress("DEPRECATION")
        override fun onOpened(cameraDevice: CameraDevice) {
            mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_CAMERA_STATE, "Camera: open")

            mCameraDevice = cameraDevice

            val sizes = mCameraInfo.streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)
            if (null == sizes || sizes.isEmpty()) throw Exception("No sizes available")
            val previewSize = getBestResolution(
                mBinding.surfaceView.width,
                mCameraInfo.resolutionWidth.toFloat() / mCameraInfo.resolutionHeight,
                sizes
            )

            mRotatedPreviewWidth = if (mCameraInfo.areDimensionsSwapped) previewSize.height else previewSize.width
            mRotatedPreviewHeight = if (mCameraInfo.areDimensionsSwapped) previewSize.width else previewSize.height

            mBinding.surfaceView.holder.setFixedSize(mRotatedPreviewWidth, mRotatedPreviewHeight)

            try {
                cameraDevice.createCaptureSession(
                    mutableListOf(
                        mBinding.surfaceView.holder.surface,
                        mImageReaderHisto.surface,
                        mImageReaderJpeg.surface,
                        mImageReaderDng.surface,
                    ),
                    mCameraCaptureSessionStateCallback,
                    getWorkerHandler()
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun giveHapticFeedback(view: View) {
        if (settings.enableHapticFeedback) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
    }

    /** Returns exposure information: ISO, Speed and the difference between this values and the preview options */
    private fun getCaptureEA() : Triple<Int, Long, Float> {
        if (Settings.ISO_MODE_AUTO == settings.isoMode && Settings.SPEED_MODE_AUTO == settings.speedMode) {
            return Triple(mIsoMeasuredValue, mSpeedMeasuredValue, 0f)
        }

        if (Settings.ISO_MODE_AUTO != settings.isoMode && Settings.SPEED_MODE_AUTO != settings.speedMode) {
            return Triple(
                settings.isoValue,
                settings.speedValue,
                calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, settings.isoValue, settings.speedValue)
            )
        }

        if (Settings.ISO_MODE_AUTO != settings.isoMode) {
            val isoRatio = mIsoMeasuredValue.toFloat() / settings.isoValue

            var suggestedSpeed = (mSpeedMeasuredValue * isoRatio).toLong()
            if (suggestedSpeed < mCameraInfo.speedRange.lower) suggestedSpeed = mCameraInfo.speedRange.lower
            else if (suggestedSpeed > mCameraInfo.speedRange.upper) suggestedSpeed = mCameraInfo.speedRange.upper

            return Triple(
                settings.isoValue,
                suggestedSpeed,
                calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, settings.isoValue, suggestedSpeed)
            )
        }

        val speedValue = settings.speedValue
        val speedRatio = mSpeedMeasuredValue / speedValue

        var suggestedIso = (mIsoMeasuredValue * speedRatio).toInt()
        if (suggestedIso < mCameraInfo.isoRange.lower) suggestedIso = mCameraInfo.isoRange.lower
        else if (suggestedIso > mCameraInfo.isoRange.upper) suggestedIso = mCameraInfo.isoRange.upper

        return Triple(
            suggestedIso,
            mSpeedMeasuredValue,
            calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, suggestedIso, speedValue)
        )
    }

    private fun sequenceTakeNextPhotoAfterDelay(delay: Int) {
        val msDelay = if (delay <= 0) 100L else 1000L
        mSequencePhotoDelayCounter = delay
        mBinding.frameView.setSequencePhotoDelay(mSequencePhotoDelayCounter)

        mSequenceTimer = timer(null, false, msDelay, msDelay) {
            mSequencePhotoDelayCounter--
            mBinding.frameView.setSequencePhotoDelay(mSequencePhotoDelayCounter)
            if (mSequencePhotoDelayCounter <= 0) {
                mSequenceTimer?.cancel()
                mSequenceTimer = null
                runOnUiThread {
                    if (mSequenceStarted) {
                        takePhoto(false, true)
                    }
                }
            }
        }
    }

    private fun sequencePhotoTaken() {
        Log.i("TAKE_PHOTO", "mSequenceStarted: ${mSequenceStarted}, mPhotoCounter: $mPhotoCounter")
        if (mSequenceStarted) {
            if (settings.sequenceNumberOfPhotos in 1..mPhotoCounter) {
                sequenceStop()
            } else {
                sequenceTakeNextPhotoAfterDelay(settings.sequenceDelayBetween)
            }
        }
    }

    private fun sequenceStop() {
        mSequenceTimer?.cancel()
        mSequenceTimer = null
        mSequenceStarted = false
        mBinding.frameView.setSequencePhotoDelay(0)
        mPhotoCounter = 0
        mBinding.frameView.showCounter(mPhotoCounter)
        updateSliders()
    }

    private fun sequenceStart() {
        mSequenceStarted = true
        mPhotoCounter = 0
        mBinding.frameView.showCounter(mPhotoCounter)
        updateSliders()
        sequenceTakeNextPhotoAfterDelay(settings.sequenceDelayStart)
    }

    private fun sequenceToggle() {
        if (mSequenceStarted) {
            sequenceStop()
        } else {
            sequenceStart()
        }
    }

    /** There is not specific thread for the camera (currently I have problems) but maybe one-day */
    private fun getWorkerHandler(): Handler? { return null }

    private fun getPhotoOrientation(): Int = (mScreenOrientation + mCameraInfo.sensorOrientation) % 360

    private fun getPhotoExifOrientation(orientation: Int): Int =
        when(orientation) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!askPermissions()) onPermissionsAllowed()
    }

    override fun onResume() {
        super.onResume()
        mOrientationEventListener?.enable()
        if (mSurfaceIsCreated) selectCamera(settings.cameraIndex)
    }

    override fun onPause() {
        mOrientationEventListener?.disable()
        closeCamera()
        settings.saveProperties()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    @Suppress("DEPRECATION")
    private fun showSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> handleRequestPermissions(grantResults)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        if (INTENT_SELECT_FOLDER == requestCode && RESULT_OK == resultCode && null != intent) {
            val data = intent.data
            if (data is Uri) {
                mSaveFolder = DocumentFile.fromTreeUri(applicationContext, data)
                settings.saveUri = data.toString()
                settings.saveProperties()

                if (mFirstCall) onValidSaveFolder()
            }
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

    private fun handleRequestPermissions(grantResults: IntArray) {
        if (grantResults.size < PERMISSIONS.size)
            return

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

    @SuppressLint("ClickableViewAccessibility")
    private fun onPermissionsAllowed() {
        if (mCameraList.size <= 0) {
            fatalError("No valid camera found !")
            return
        }

        try {
            mSaveFolder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(settings.saveUri))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (null == mSaveFolder) {
            startSelectFolder()
        } else {
            onValidSaveFolder()
        }
    }

    private fun getFlashModeValue(previewMode: Boolean): Int {
        if (!mCameraInfo.hasFlash || Settings.FLASH_MODE_OFF == settings.flashMode) return CaptureRequest.FLASH_MODE_OFF
        if (Settings.FLASH_MODE_TORCH == settings.flashMode) return CaptureRequest.FLASH_MODE_TORCH
        if (previewMode) return CaptureRequest.FLASH_MODE_OFF
        return CaptureRequest.FLASH_MODE_SINGLE
    }

    fun startSelectFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true)
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        intent.addFlags(
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(intent, INTENT_SELECT_FOLDER)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onValidSaveFolder() {
        if (settings.cameraIndex < 0 || settings.cameraIndex >= mCameraList.size) {
            settings.cameraIndex = 0
        }

        if (1 == mCameraList.size) {
            mBinding.txtCameraLabel.visibility = View.INVISIBLE
            mBinding.txtCamera.visibility = View.INVISIBLE
        }

        mImageReaderHisto.setOnImageAvailableListener(mImageReaderHistoListener, getWorkerHandler())

        setContentView(mBinding.root)

        mBinding.switchSequences.isChecked = settings.showSequence

        mBinding.txtSequenceDelayStart.setOnMoveXAxisListener { steps ->
            val newValue = settings.getArrayValue( settings.sequenceDelayStart, steps, Settings.SEQUENCE_DELAY_START_OPTIONS )
            if (newValue != settings.sequenceDelayStart) {
                giveHapticFeedback(mBinding.txtSequenceDelayStart)
                settings.sequenceDelayStart = newValue
                updateSequenceDelayStart()
            }
        }

        mBinding.txtSequenceDelayBetween.setOnMoveXAxisListener { steps ->
            val newValue = settings.getArrayValue( settings.sequenceDelayBetween, steps, Settings.SEQUENCE_DELAY_BETWEEN_OPTIONS )
            if (newValue != settings.sequenceDelayBetween) {
                settings.sequenceDelayBetween = newValue
                giveHapticFeedback(mBinding.txtSequenceDelayBetween)
                updateSequenceDelayBetween()
            }
        }

        mBinding.txtSequenceNumberOfPhotos.setOnMoveXAxisListener { steps ->
            val newValue = settings.getArrayValue( settings.sequenceNumberOfPhotos, steps, Settings.SEQUENCE_NUMBER_OF_PHOTOS_OPTIONS )
            if (newValue != settings.sequenceNumberOfPhotos) {
                settings.sequenceNumberOfPhotos = newValue
                giveHapticFeedback(mBinding.txtSequenceNumberOfPhotos)
                updateSequenceNumberOfPhotos()
            }
        }

        mBinding.surfaceView.holder.addCallback(mSurfaceHolderCallback)

        mBinding.txtFlash.setOnMoveYAxisListener { steps ->
            if (mCameraInfo.hasFlash) {
                var newValue = settings.flashMode + steps
                while (newValue < 0) newValue += Settings.FLASH_MODES.size
                newValue %= Settings.FLASH_MODES.size

                if (newValue != settings.flashMode) {
                    settings.flashMode = newValue
                    updateFlashMode()
                    setupCapturePreviewRequest()
                }
            }
        }

        mBinding.txtCamera.setOnMoveYAxisListener { steps ->
            val newCameraIndex =
                if (steps < 0) (settings.cameraIndex - 1 + mCameraList.size) % mCameraList.size
                else (settings.cameraIndex + 1) % mCameraList.size
            giveHapticFeedback(mBinding.txtCamera)
            selectCamera(newCameraIndex, true)
        }

        mCameraInfo = mCameraList[0]

        mBinding.txtIso.setOnMoveYAxisListener {
            settings.isoMode = 1 - settings.isoMode
            giveHapticFeedback(mBinding.txtIso)

            if (Settings.ISO_MODE_MANUAL != settings.isoMode && Settings.SPEED_MODE_PROTECT_HIGHLIGHTS == settings.speedMode) {
                settings.speedMode = Settings.SPEED_MODE_MANUAL
            }

            updateSliders()
        }

        mBinding.txtIso.setOnMoveXAxisListener { trackIso(it) }

        mBinding.txtSpeed.setOnMoveYAxisListener {
            if (it < 0) {
                settings.speedMode--
                if (settings.speedMode < Settings.SPEED_MODE_AUTO) {
                    settings.speedMode =
                        if (Settings.ISO_MODE_MANUAL == settings.isoMode) Settings.SPEED_MODE_PROTECT_HIGHLIGHTS
                        else Settings.SPEED_MODE_MANUAL
                }
            } else {
                settings.speedMode++
                if (settings.speedMode > Settings.SPEED_MODE_PROTECT_HIGHLIGHTS ||
                    (Settings.ISO_MODE_MANUAL != settings.isoMode && Settings.SPEED_MODE_PROTECT_HIGHLIGHTS == settings.speedMode) ) {
                    settings.speedMode = Settings.SPEED_MODE_AUTO
                }
            }

            giveHapticFeedback(mBinding.txtSpeed)
            updateSliders()
        }

        mBinding.txtSpeed.setOnMoveXAxisListener { trackSpeed(it) }

        mBinding.txtExpCompensation.setOnMoveXAxisListener { trackExpCompensation(it) }

        mBinding.txtFocus.setOnMoveYAxisListener { steps ->
            if (mCameraInfo.focusAllowManual) {
                var newFocusType = settings.focusType + (if (steps < 0) -1 else 1)
                if (newFocusType < 0) {
                    newFocusType = Settings.FOCUS_TYPE_MAX-1
                } else if (newFocusType >= Settings.FOCUS_TYPE_MAX) {
                    newFocusType = 0
                }

                if (newFocusType != settings.focusType) {
                    settings.focusType = newFocusType
                    mFocusClick = false
                    giveHapticFeedback(mBinding.txtFocus)
                    showFocus()
                    setupCapturePreviewRequest()
                }
            }
        }

        mBinding.seekBarFocus.progress = settings.focusManualProgress

        mBinding.seekBarFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, user: Boolean) {
                if (Settings.FOCUS_TYPE_MANUAL == settings.focusType) {
                    mFocusState = FOCUS_STATE_MANUAL
                    settings.focusManualProgress = progress
                    setupCapturePreviewRequest()
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
            }
        })

        mBinding.surfaceView.setOnTouchListener { view, motionEvent ->
            if (mCameraInfo.focusAllowManual) {
                if (MotionEvent.ACTION_DOWN == motionEvent.actionMasked) {
                    if (Settings.FOCUS_TYPE_MANUAL != settings.focusType) {
                        settings.focusType = Settings.FOCUS_TYPE_MANUAL
                        showFocus()
                    }

                    mFocusClickPosition.x = (100 * motionEvent.x / view.width).toInt()
                    mFocusClickPosition.y = (100 * motionEvent.y / view.height).toInt()
                    mFocusClick = true
                    giveHapticFeedback(mBinding.surfaceView)
                    setupCapturePreviewRequest()
                }
            }

            false
        }

        mBinding.btnPhoto.setOnTouchListener { _, motionEvent ->
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> takePhotoButton(true)
                MotionEvent.ACTION_UP -> takePhotoButton(false)
            }

            false
        }

        mBinding.btnSettings.setOnClickListener {
            SettingsDialog.show(supportFragmentManager, this) {
                updateFrame()
            }
        }

        mBinding.switchSequences.setOnCheckedChangeListener { _, isChecked ->
            settings.showSequence = isChecked
            giveHapticFeedback(mBinding.switchSequences)
            sequenceStop()
            updateSliders()
        }

        mOrientationEventListener = object: OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                val screenOrientation = (orientation + 45) / 90 * 90 //round to 90°
                if (screenOrientation != mScreenOrientation) {
                    mScreenOrientation = screenOrientation
                    Log.i("EXIF", "Orientation: $screenOrientation")
                }
            }
        }

        updateFrame()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when(keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                takePhotoButton(false)
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                takePhotoButton(false)
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when(keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                takePhotoButton(true)
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                takePhotoButton(true)
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    /** ISO slider/button is changed */
    private fun trackIso(delta: Int) {
        if (Settings.ISO_MODE_MANUAL != settings.isoMode) return

        val newValue = mCameraInfo.getIso(settings.isoValue, delta)

        if (newValue != settings.isoValue) {
            settings.isoValue = newValue
            giveHapticFeedback(mBinding.txtIso)
            showIso(newValue)
            setupCapturePreviewRequest()
        }
    }

    private fun showIso(value: Int) {
        mBinding.txtIso.text = "$value ISO (${Settings.ISO_MODE_TO_STRING[settings.isoMode]})"
    }

    /** Exposure compensation slider/button is changed */
    private fun trackExpCompensation(delta: Int) {
        if (Settings.ISO_MODE_AUTO != settings.isoMode && Settings.SPEED_MODE_AUTO != settings.speedMode) return

        var newValue = settings.expCompensationValue + delta

        if (newValue < mCameraInfo.exposureCompensationRange.lower) {
            newValue = mCameraInfo.exposureCompensationRange.lower
        } else if (newValue > mCameraInfo.exposureCompensationRange.upper) {
            newValue = mCameraInfo.exposureCompensationRange.upper
        }

        if (newValue != settings.expCompensationValue) {
            settings.expCompensationValue = newValue
            giveHapticFeedback(mBinding.txtExpCompensation)
            showExpCompensation(newValue)
            setupCapturePreviewRequest()
        }
    }

    private fun showExpCompensation(value: Int) {
        var exp = "Exp: "
        val expFloat = value.toFloat() / Settings.EXP_STEPS_PER_1EV

        exp += if (value >= 0) {
            "+%.1f".format(expFloat)
        } else {
            "%.1f".format(expFloat)
        }

        mBinding.txtExpCompensation.text = exp
    }

    /** Speed slider/button is changed */
    private fun trackSpeed(delta: Int) {
        if (Settings.SPEED_MODE_MANUAL != settings.speedMode) return

        val newSpeed = mCameraInfo.getSpeed(settings.speedValue, delta)

        if (newSpeed != settings.speedValue) {
            settings.speedValue = newSpeed
            giveHapticFeedback(mBinding.txtExpCompensation)
            showSpeed(newSpeed)
            setupCapturePreviewRequest()
        }
    }

    private fun getSpeedStr(speed: Long): String {
        if (speed >= 300000000L) { // 300ms
            val speedSecondsWith1Decimal = (speed / 10000000).toInt()
            val speedSeconds = speedSecondsWith1Decimal / 100
            val speedDecimals = ((speedSecondsWith1Decimal + 5) / 10) % 10

            if (0 == speedDecimals)
                return "${speedSeconds}\""
            return "${speedSeconds}.${speedDecimals}\""
        }

        val denominator = (1000000000L / speed).toInt()
        val roundedDenominator =
            when {
                denominator >= 1900 -> (denominator / 1000) * 1000
                denominator >= 500 -> (denominator / 100) * 100
                128 == denominator -> 125
                denominator >= 20 -> (denominator / 10) * 10
                16 == denominator -> 15
                else -> denominator
            }

        return "1/${roundedDenominator}"
    }

    private fun showSpeed(speed: Long) {
        mBinding.txtSpeed.text = "${getSpeedStr(speed)} (${Settings.SPEED_MODE_TO_STRING[settings.speedMode]})"
    }

    private fun showFocus() {
        if (mCameraInfo.focusAllowManual) {
            when(settings.focusType) {
                Settings.FOCUS_TYPE_HYPERFOCAL -> {
                    mBinding.txtFocus.text = "Focus: Hyperfocal"
                    mBinding.txtFocus.visibility = View.VISIBLE
                    mBinding.seekBarFocus.visibility = View.INVISIBLE
                }

                Settings.FOCUS_TYPE_MANUAL -> {
                    mBinding.txtFocus.text = "Focus: Manual"
                    mBinding.txtFocus.visibility = View.VISIBLE
                    mBinding.seekBarFocus.visibility = View.VISIBLE
                }

                else -> {
                    mBinding.txtFocus.text = "Focus: Auto"
                    mBinding.txtFocus.visibility = View.VISIBLE
                    mBinding.seekBarFocus.visibility = View.INVISIBLE
                }
            }

        } else {
            mBinding.txtFocus.visibility = View.INVISIBLE
            mBinding.seekBarFocus.visibility = View.INVISIBLE
        }
    }

    private fun getSecondsText(value: Int) =
        if (1 == value)
            "1 second"
        else
            "$value seconds"

    private fun getNumberOfPhotosText(value: Int) =
        when (value) {
            0 -> "infinite"
            1 -> "1 photo"
            else -> "$value photos"
        }

    private fun updateSequenceDelayStart() {
        mBinding.txtSequenceDelayStart.text = "Delay start: ${getSecondsText(settings.sequenceDelayStart)}"
    }

    private fun updateSequenceDelayBetween() {
        mBinding.txtSequenceDelayBetween.text = "Delay between: ${getSecondsText(settings.sequenceDelayBetween)}"
    }

    private fun updateSequenceNumberOfPhotos() {
        mBinding.txtSequenceNumberOfPhotos.text = "Number of photos: ${getNumberOfPhotosText(settings.sequenceNumberOfPhotos)}"
    }

    private fun updateFlashMode() {
        if (mCameraInfo.hasFlash) {
            mBinding.txtFlash.text = Settings.FLASH_MODES[settings.flashMode]
            mBinding.txtFlash.visibility = View.VISIBLE
            mBinding.txtFlashLabel.visibility = View.VISIBLE
        } else {
            mBinding.txtFlash.visibility = View.INVISIBLE
            mBinding.txtFlashLabel.visibility = View.INVISIBLE
        }
    }

    private fun updateSliders() {
        mBinding.txtExpCompensation.visibility = if (Settings.ISO_MODE_AUTO == settings.isoMode || Settings.SPEED_MODE_AUTO == settings.speedMode) View.VISIBLE else View.INVISIBLE

        if (Settings.ISO_MODE_MANUAL == settings.isoMode) showIso(settings.isoValue)
        if (Settings.SPEED_MODE_AUTO != settings.speedMode) showSpeed(settings.speedValue)

        showFocus()
        showExpCompensation(settings.expCompensationValue)
        setupCapturePreviewRequest()

        updateSequenceDelayStart()
        updateSequenceDelayBetween()
        updateSequenceNumberOfPhotos()
        updateFlashMode()

        if (settings.showSequence) {
            mBinding.layoutSequences.visibility = View.VISIBLE

            if (mSequenceStarted) {
                mBinding.btnPhoto.backgroundTintList = ColorStateList.valueOf(Color.GRAY)
                mBinding.txtSequenceDelayStart.isEnabled = false
                mBinding.txtSequenceDelayBetween.isEnabled = false
                mBinding.txtSequenceNumberOfPhotos.isEnabled = false
            } else {
                mBinding.btnPhoto.backgroundTintList = ColorStateList.valueOf(Color.BLUE)
                mBinding.txtSequenceDelayStart.isEnabled = true
                mBinding.txtSequenceDelayBetween.isEnabled = true
                mBinding.txtSequenceNumberOfPhotos.isEnabled = true
            }
        } else {
            mBinding.layoutSequences.visibility = View.GONE
            mBinding.btnPhoto.backgroundTintList = ColorStateList.valueOf(Color.RED)
        }
    }

    private fun saveAsyncNextItem() {
        mBinding.frameView.updateDebugMemInfo()

        if(!mSaveAsyncBusy && mSaveAsyncMQ.isNotEmpty()) {
            mSaveAsyncBusy = true
            mBinding.frameView.showSavePhotosIcon(true)
            val item = mSaveAsyncMQ.get(0)
            mSaveAsyncMQ.removeAt(0)

            GlobalScope.launch(Dispatchers.IO) {
                val fileName = item.first
                val mimeType = item.second
                val byteArray = item.third
                var failed = true

                try {
                    mSaveFolder?.let { saveFolder ->
                        saveFolder.createFile(mimeType, fileName)?.let { newFile ->
                            contentResolver.openOutputStream(newFile.uri)?.let { outputStream ->
                                outputStream.write(byteArray)
                                outputStream.close()
                                failed = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                mSaveAsyncBusy = false
                runOnUiThread {
                    if (failed) mBinding.frameView.showSaveError()
                    saveAsyncNextItem()
                }
            }
        } else if(!mSaveAsyncBusy) {
            mBinding.frameView.showSavePhotosIcon(false)
        }
    }

    private fun saveAsync(fileName: String, mimeType: String, byteArray: ByteArray) {
        mSaveAsyncMQ.add(Triple(fileName, mimeType, byteArray))
        saveAsyncNextItem()
    }

    private fun saveDng(image: Image, captureResult: TotalCaptureResult) {
        Log.i("TAKE_PHOTO", "DNG: Save starts")
        try {
            val outputStream = ByteArrayOutputStream()
            val dngCreator = DngCreator(mCameraInfo.cameraCharacteristics, captureResult)
            mLocation?.let { dngCreator.setLocation(it) }
            dngCreator.setOrientation(mPhotoExifOrientation)
            dngCreator.writeImage(outputStream, image)
            saveAsync("$mPhotoFileNameBase.dng", "image/x-adobe-dng", outputStream.toByteArray())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Log.i("TAKE_PHOTO", "DNG: Save ends")
    }

    private fun saveJpeg(image: Image) {
        Log.i("TAKE_PHOTO", "JPEG: Save starts")
        try {
            val outputStream = ByteArrayOutputStream()
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            outputStream.write(bytes)
            saveAsync("$mPhotoFileNameBase.jpg", "image/jpeg", outputStream.toByteArray())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Log.i("TAKE_PHOTO", "JPEG: Save ends")
    }

    /** Update take photo button pressed */
    private fun takePhotoButton(pressed: Boolean) {
        if (mPhotoButtonPressed == pressed) return

        mPhotoButtonPressed = pressed
        Log.i("TAKE_PHOTO", "Button pressed: $mPhotoButtonPressed")

        if (pressed) {
            if (settings.showSequence) {
                sequenceToggle()
            } else {
                takePhoto(false, true)
            }
        }
    }

    /** Start taking a photo */
    private fun takePhoto(newFile: Boolean = false, start: Boolean = false) {
        runOnUiThread {
            mBinding.frameView.updateDebugMemInfo()

            if (start) {
                if (!mSequenceStarted) {
                    mPhotoCounter = 0
                }
                setupCapturePhotoRequest()
            }

            var takeNewPhoto = start

            if (newFile) {
                val realNewFile = null != mImageJpeg || null != mImageDng

                if (realNewFile) {
                    mPhotoCounter++
                    mBinding.frameView.showCounter(mPhotoCounter)
                }

                mImageJpeg?.let { image ->
                    saveJpeg(image)
                    image.close()
                    mImageJpeg = null
                }

                mImageDng?.let { image ->
                    mCurrentPhotoCaptureResult?.let{ captureResult ->
                        saveDng(image, captureResult)
                    }
                    image.close()
                    mImageDng = null
                }

                mCurrentPhotoCaptureResult = null

                takeNewPhoto = settings.continuousMode && mPhotoButtonPressed && !mSequenceStarted

                if (realNewFile && mSequenceStarted) {
                    sequencePhotoTaken()
                }
            }

            mBinding.frameView.showTakePhotoIcon(takeNewPhoto)

            val captureRequestPhoto = mCaptureRequest
            val cameraCaptureSession = mCameraCaptureSession

            if (takeNewPhoto && null != captureRequestPhoto && null != cameraCaptureSession) {
                var minMem =
                    when (settings.takePhotoModes) {
                        Settings.PHOTO_TYPE_DNG -> mCameraInfo.estimatedDngSize * 2
                        Settings.PHOTO_TYPE_JPEG -> mCameraInfo.estimatedJpegSize
                        else -> mCameraInfo.estimatedDngSize * 2 + mCameraInfo.estimatedJpegSize
                    }
                minMem = 1 + minMem / (1024 * 1024) //convert to MB

                if (mSaveAsyncMQ.isNotEmpty() && minMem > getFreeMemInfo()) {
                    Log.i("TAKE_PHOTO", "Not enough memory")
                    mPhotoTakeMask = PHOTO_TAKE_OUT_OF_MEMORY
                    Timer("Out of memory", false).schedule(MEMORY_RETRY_TIMEOUT) {
                        mPhotoTakeMask = 0
                        takePhoto(true)
                    }
                } else {
                    Log.i("TAKE_PHOTO", "New photo")

                    mPhotoInProgress = true

                    mPhotoTakeMask = when (settings.takePhotoModes) {
                        Settings.PHOTO_TYPE_DNG -> PHOTO_TAKE_DNG or PHOTO_TAKE_COMPLETED
                        Settings.PHOTO_TYPE_JPEG -> PHOTO_TAKE_JPEG or PHOTO_TAKE_COMPLETED
                        else -> PHOTO_TAKE_JPEG or PHOTO_TAKE_DNG or PHOTO_TAKE_COMPLETED
                    }

                    mPhotoTimestamp = System.currentTimeMillis()
                    mPhotoFileNameBase = getPhotoBaseFileName(mPhotoTimestamp)

                    cameraCaptureSession.capture(
                        captureRequestPhoto,
                        mCameraCaptureSessionPhotoCaptureCallback,
                        getWorkerHandler()
                    )
                }
            } else {
                mPhotoInProgress = false
                setupCapturePreviewRequest()
            }
        }
    }

    /** Select the camera */
    @SuppressLint("MissingPermission")
    private fun selectCamera(index: Int, async: Boolean = false) {
        mSelectCameraTimer?.cancel()
        mSelectCameraTimer = null

        mBinding.txtCamera.text = "${index+1}"

        if (async) {
            mSelectCameraTimer = timer(null, false, SELECT_CAMERA_ASYNC_DELAY, SELECT_CAMERA_ASYNC_DELAY) {
                mSelectCameraTimer?.cancel()
                mSelectCameraTimer = null
                runOnUiThread {
                    selectCamera(index)
                }
            }
            return
        }

        mPhotoInProgress = false
        settings.cameraIndex = index
        mCameraInfo = mCameraList[index]
        mFocusState = FOCUS_STATE_MANUAL

        closeCamera()

        val set = ConstraintSet()
        set.clone(mBinding.layoutView)
        set.setDimensionRatio(mBinding.layoutWithRatio.id, "${mCameraInfo.resolutionWidth}:${mCameraInfo.resolutionHeight}")
        set.applyTo(mBinding.layoutView)

        mImageReaderJpeg = ImageReader.newInstance(mCameraInfo.resolutionWidth, mCameraInfo.resolutionHeight, ImageFormat.JPEG, 1)
        mImageReaderJpeg.setOnImageAvailableListener(mImageReaderJpegListener, getWorkerHandler())

        mImageReaderDng = ImageReader.newInstance(mCameraInfo.resolutionWidth, mCameraInfo.resolutionHeight, ImageFormat.RAW_SENSOR, 1)
        mImageReaderDng.setOnImageAvailableListener(mImageReaderDngListener, getWorkerHandler())

        updateSliders()

        mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_CAMERA_STATE, "Camera: opening")
        mCameraManager.openCamera(mCameraInfo.id, mCameraDeviceStateCallback, getWorkerHandler())
    }

    private fun closeCamera() {
        val cameraCaptureSession = mCameraCaptureSession
        if (null != cameraCaptureSession) {
            cameraCaptureSession.stopRepeating()
            cameraCaptureSession.close()
            mCameraCaptureSession = null
        }

        val cameraDevice = mCameraDevice
        if (null != cameraDevice) {
            cameraDevice.close()
            mCameraDevice = null
        }

        mCaptureRequestBuilder = null
        mCaptureRequestBuilder = null

        mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_CAMERA_STATE, "Camera: closed")
    }

    private fun updateFrame() {
        mBinding.frameView.showGrid(settings.showGrid)
        mBinding.frameView.showDebugInfo(settings.showDebugInfo)

        when(settings.frameType) {
            Settings.FRAME_TYPE_1_1 -> mBinding.frameView.showRatio(true, 1, 1)
            Settings.FRAME_TYPE_4_3 -> mBinding.frameView.showRatio(true, 4, 3)
            Settings.FRAME_TYPE_3_2 -> mBinding.frameView.showRatio(true, 3, 2)
            Settings.FRAME_TYPE_16_9 -> mBinding.frameView.showRatio(true, 16, 9)
            else -> mBinding.frameView.showRatio(false)
        }
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

    /** Called once when the camera is selected (common to preview & take photo) */
    private fun setupCaptureInitRequest(captureRequestBuilder: CaptureRequest.Builder) {
        if (mCameraInfo.supportLensStabilisation) {
            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }

        captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 90)
        captureRequestBuilder.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, null)
    }

    private fun setupCapturePhotoRequest(force: Boolean = false) {
        setupCaptureRequest(true, force)
    }

    private fun setupCapturePreviewRequest(force: Boolean = false) {
        setupCaptureRequest(false, force)
    }

    /** Specific preview or take photo options */
    @SuppressLint("MissingPermission")
    private fun setupCaptureRequest(photoMode: Boolean, force: Boolean) {
        mCameraWaitConfigurationCounter = CAMERA_WAIT_CONFIGURATION_COUNTER + 100

        if (mPhotoInProgress) return

        val captureRequestBuilder = mCaptureRequestBuilder ?: return
        val cameraCaptureSession = mCameraCaptureSession ?: return

        Log.i("TAKE_PHOTO", "setupCaptureRequest(${photoMode}, ${force})")

        if (photoMode != mCaptureModeIsPhoto || force) {
            mCaptureModeIsPhoto = photoMode

            cameraCaptureSession.stopRepeating()

            if (photoMode) {
                captureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                captureRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                captureRequestBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, getFlashModeValue(false))

                if (Settings.ISO_MODE_AUTO != settings.isoMode || Settings.SPEED_MODE_AUTO != settings.speedMode) {
                    val ae = getCaptureEA()
                    mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_PREVIEW, "Preview - ISO: ${ae.first}, Speed: ${getSpeedStr(ae.second)}")
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ae.second)
                    captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, ae.first)
                } else {
                    mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_PREVIEW, "Preview - Auto")
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                }

                when( settings.takePhotoModes ) {
                    Settings.PHOTO_TYPE_DNG -> {
                        captureRequestBuilder.addTarget(mImageReaderDng.surface)
                    }
                    Settings.PHOTO_TYPE_JPEG -> {
                        captureRequestBuilder.addTarget(mImageReaderJpeg.surface)
                    }
                    else -> {
                        captureRequestBuilder.addTarget(mImageReaderDng.surface)
                        captureRequestBuilder.addTarget(mImageReaderJpeg.surface)
                    }
                }

                mLocation = if (settings.useLocation) {
                    mLocationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                } else {
                    null
                }
                captureRequestBuilder.set(CaptureRequest.JPEG_GPS_LOCATION, mLocation)

                val photoOrientation = getPhotoOrientation()
                mPhotoExifOrientation = getPhotoExifOrientation(photoOrientation)
                captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, photoOrientation)

                captureRequestBuilder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    if (Settings.NOISE_REDUCTION_ENABLED == settings.noiseReduction ||
                        (Settings.NOISE_REDUCTION_JPEG_ONLY == settings.noiseReduction && Settings.PHOTO_TYPE_JPEG == settings.takePhotoModes)
                    ) {
                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    } else {
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    }
                )

                mCaptureRequest = captureRequestBuilder.build()
            } else {
                mLocation = null
                mCaptureRequest = null

                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)

                captureRequestBuilder.removeTarget(mImageReaderDng.surface)
                captureRequestBuilder.removeTarget(mImageReaderJpeg.surface)

                captureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
                captureRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                captureRequestBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
                captureRequestBuilder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    if (Settings.NOISE_REDUCTION_DISABLED == settings.noiseReduction) {
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    } else {
                        CaptureRequest.NOISE_REDUCTION_MODE_FAST
                    }
                )
            }
        }

        if (photoMode) return

        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, getFlashModeValue(true))
        captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)

        if (mCameraInfo.focusAllowManual) {
            when(settings.focusType) {
                Settings.FOCUS_TYPE_HYPERFOCAL -> {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, mCameraInfo.focusHyperfocalDistance)
                    mBinding.frameView.hideFocusZone()
                }
                Settings.FOCUS_TYPE_MANUAL -> {
                    if (mFocusClick) {
                        mFocusClick = false
                        val delta = mCameraInfo.resolutionWidth * FOCUS_REGION_SIZE_PERCENT / 100
                        val x = mCameraInfo.resolutionWidth * mFocusClickPosition.x / 100
                        val y = mCameraInfo.resolutionWidth * mFocusClickPosition.y / 100
                        val x1 = max(0, x - delta)
                        val y1 = max(0, y - delta)
                        val x2 = min(mCameraInfo.resolutionWidth, x + delta)
                        val y2 = min(mCameraInfo.resolutionHeight, y + delta)

                        if (y2 > y1 && x2 > x1) {
                            mFocusState = FOCUS_STATE_CLICK
                            val rectangle = MeteringRectangle(
                                x1,
                                y1,
                                x2 - x1,
                                y2 - y1,
                                MeteringRectangle.METERING_WEIGHT_MAX
                            )
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rectangle))
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                        }

                        mBinding.frameView.showFocusZone(
                            Rect(
                                mFocusClickPosition.x - FOCUS_REGION_SIZE_PERCENT,
                                mFocusClickPosition.y - FOCUS_REGION_SIZE_PERCENT,
                                mFocusClickPosition.x + FOCUS_REGION_SIZE_PERCENT,
                                mFocusClickPosition.y + FOCUS_REGION_SIZE_PERCENT
                            )
                        )
                    } else if (FOCUS_STATE_LOCKED == mFocusState) {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    } else if (FOCUS_STATE_MANUAL == mFocusState) {
                        val distance = mCameraInfo.focusRange.lower +
                                (100 - mBinding.seekBarFocus.progress) * (mCameraInfo.focusRange.upper - mCameraInfo.focusRange.lower) / 100
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                        mBinding.frameView.hideFocusZone()
                    }
                }

                else -> {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    mBinding.frameView.hideFocusZone()
                }
            }
        }

        //WORKAROUND: My camera block with click to focus in full manual mode
        if ((FOCUS_STATE_CLICK == mFocusState || FOCUS_STATE_SEARCHING == mFocusState) && Settings.ISO_MODE_AUTO != settings.isoMode && Settings.SPEED_MODE_AUTO != settings.speedMode) {
            mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_PREVIEW, "Preview - Auto (FOCUS)")
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        } else if (Settings.ISO_MODE_AUTO == settings.isoMode || Settings.SPEED_MODE_AUTO == settings.speedMode) {
            mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_PREVIEW, "Preview - Auto")
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.expCompensationValue * mCameraInfo.exposureCompensationMultiplyFactor)
        } else {
            mSpeedManualPreviewValue = settings.speedValue
            mIsoManualPreviewValue = settings.isoValue

            if (mSpeedManualPreviewValue > Settings.SPEED_MANUAL_MIN_PREVIEW) {
                while (mSpeedManualPreviewValue > Settings.SPEED_MANUAL_MIN_PREVIEW) {
                    if ((2*mIsoManualPreviewValue) > mCameraInfo.isoRange.upper) break
                    mIsoManualPreviewValue *= 2
                    mSpeedManualPreviewValue /= 2
                }

                mSpeedManualPreviewValue = min(Settings.SPEED_MANUAL_MIN_PREVIEW, mSpeedManualPreviewValue)
            }

            mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_PREVIEW, "Preview - ISO: ${mIsoManualPreviewValue}, Speed: ${getSpeedStr(mSpeedManualPreviewValue)}")

            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mSpeedManualPreviewValue)
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, mIsoManualPreviewValue)
        }

        try {
            cameraCaptureSession.setRepeatingRequest(
                captureRequestBuilder.build(),
                mCameraCaptureSessionPreviewCaptureCallback,
                getWorkerHandler()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mCameraWaitConfigurationCounter = CAMERA_WAIT_CONFIGURATION_COUNTER
    }
}
