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
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.location.LocationManager
import androidx.exifinterface.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
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

        const val PHOTO_MODE_PHOTO = 0
        const val PHOTO_MODE_SEQUENCE = 1
        const val PHOTO_MODE_MACRO = 2
        const val PHOTO_MODE_MAX = 3

        const val MEMORY_RETRY_TIMEOUT = 250L //ms

        const val SELECT_CAMERA_ASYNC_DELAY = 250L //ms

        private val FILE_NAME_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

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
    private var mCaptureModeIsPhoto = false

    private var mPhotoButtonPressed = false
    private var mPhotoTakeMask = 0
    private var mPhotoTimestamp = 0L
    private var mPhotoFileNameBase = ""
    private var mPhotoCounter = 0
    private var mPhotoInProgress = false

    private var mPhotoMode = PHOTO_MODE_PHOTO

    private val mImageReaderHisto = ImageReader.newInstance(100, 100, ImageFormat.YUV_420_888, 1)
    private var mImageReaderJpeg: ImageReader? = null
    private var mImageReaderDng: ImageReader? = null

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

    private val mThread = HandlerThread("Background handler")
    private lateinit var mHandler: Handler
    private val mExecutor =  Executor { command -> mHandler.post(command) }

    private val mImageReaderJpegListener = ImageReader.OnImageAvailableListener { imageReader ->
        imageReader?.let { saveImage(imageReader) }
    }

    /** Generate histogram */
    private val mImageReaderHistoListener = object: ImageReader.OnImageAvailableListener {
        private var mIsBusy = false
        private var yBytes = ByteArray(0)
        private val bmpWidth = HISTOGRAM_BITMAP_WIDTH + 2
        private val bmpHeight = HISTOGRAM_BITMAP_HEIGHT + 2
        private val color = Color.rgb(192, 192, 192)
        private val colorBorder = Color.rgb(64, 64, 64)
        private val colors = IntArray(bmpWidth * bmpHeight)
        private val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        private val values = IntArray(HISTOGRAM_BITMAP_WIDTH)

        private fun genHistogram(image: Image) {
            if (mIsBusy) return

            mIsBusy = true

            val imageW = image.width
            val imageH = image.height

            val yPlane = image.planes[0]
            val yPlaneBuffer = yPlane.buffer
            
            if (yBytes.size < yPlaneBuffer.capacity()) {
                yBytes = ByteArray(yPlaneBuffer.capacity())
            }
            
            yPlaneBuffer.get(yBytes)

            val rowStride = yPlane.rowStride

            GlobalScope.launch(Dispatchers.Default) {
                values.fill(0)

                for (line in 0 until imageH) {
                    var index = line * rowStride
                    for (column in 0 until imageW) {
                        var yValue = yBytes[index].toInt()
                        if (yValue < 0) yValue += 256
                        val valueIndex = HISTOGRAM_BITMAP_WIDTH * yValue / 256
                        values[valueIndex]++
                        index++
                    }
                }

                var maxHeight = 10
                for (value in values)
                    maxHeight = max(maxHeight, value)
                maxHeight++

                colors.fill(0)
                for (x in values.indices) {
                    val value = values[x]
                    var fill = HISTOGRAM_BITMAP_HEIGHT - 1 - (HISTOGRAM_BITMAP_HEIGHT - 1) * value / maxHeight
                    if (0 == fill && value > 0)
                        fill = 1

                    var y = fill + 1
                    while (y < HISTOGRAM_BITMAP_HEIGHT) {
                        colors[x + 1 + (y + 1) * bmpWidth] = color
                        y++
                    }
                }

                for( x in 0 until bmpWidth) {
                    colors[x] = colorBorder
                    colors[x + (bmpHeight-1) * bmpWidth] = colorBorder
                }

                for( y in 1 until bmpHeight-1) {
                    colors[y * bmpWidth] = colorBorder
                    colors[bmpWidth - 1 + y * bmpWidth] = colorBorder
                }

                runOnUiThread {
                    bitmap.setPixels(colors, 0, bmpWidth, 0, 0, bmpWidth, bmpHeight)
                    mBinding.imgHistogram.setImageBitmap(bitmap)
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
            mSurfaceIsCreated = false
        }
    }

    /** Camera state */
    private val mCameraCaptureSessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {}

        override fun onConfigured(session: CameraCaptureSession) {
            val cameraDevice = mCameraDevice ?: return

            mCameraCaptureSession = session

            callSafe {
                val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureRequestBuilder.addTarget(mBinding.surfaceView.holder.surface)
                captureRequestBuilder.addTarget(mImageReaderHisto.surface)

                setupCaptureInitRequest(captureRequestBuilder)
                mCaptureRequestBuilder = captureRequestBuilder
                setupCapturePreviewRequest(true)
            }
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

            mImageReaderDng?.let { saveImage( it, result ) }

            mPhotoTakeMask = mPhotoTakeMask and PHOTO_TAKE_COMPLETED.inv()
            checkTakePhotoFished()
        }

        override fun onCaptureSequenceCompleted(
            session: CameraCaptureSession,
            sequenceId: Int,
            frameNumber: Long
        ) {
            super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
            Log.i("TAKE_PHOTO", "onCaptureSequenceCompleted")
        }
    }

    /** Preview callback */
    private val mCameraCaptureSessionPreviewCaptureCallback = object: CameraCaptureSession.CaptureCallback() {
        @SuppressLint("SetTextI18n")
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)

            val isoMeasuredValue = result.get(CaptureResult.SENSOR_SENSITIVITY) as Int
            val speedMeasuredValue = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) as Long

            runOnUiThread {
                mBinding.frameView.setDebugInfo(
                    FrameView.DEBUG_INFO_MEASURED,
                    "Measured - ISO ${isoMeasuredValue}, Speed ${getSpeedStr(speedMeasuredValue)} (${speedMeasuredValue})"
                )
            }

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
            runOnUiThread {
                mBinding.txtExpDelta.visibility =
                    if (captureEA.third < -0.1 || captureEA.third > 0.1) View.VISIBLE else View.INVISIBLE
                @SuppressLint("SetTextI18n")
                mBinding.txtExpDelta.text = "%.2f".format(captureEA.third)

                mBinding.frameView.setDebugInfo(
                    FrameView.DEBUG_INFO_TARGET,
                    "Target - ISO ${captureEA.first}, Speed ${getSpeedStr(captureEA.second)} (${captureEA.second})"
                )

                if (Settings.ISO_MODE_AUTO == settings.isoMode) showIso(captureEA.first)
                if (Settings.SPEED_MODE_AUTO == settings.speedMode) showSpeed(captureEA.second)
            }
        }
    }

    /** Camera state */
    private val mCameraDeviceStateCallback = object: CameraDevice.StateCallback() {
        override fun onDisconnected(p0: CameraDevice) {}

        override fun onError(p0: CameraDevice, p1: Int) {}

        @Suppress("DEPRECATION")
        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice

            val sizes = mCameraInfo.streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)
            if (null == sizes || sizes.isEmpty()) throw Exception("No sizes available")
            val previewSize = getBestResolution(
                mBinding.surfaceView.width,
                mCameraInfo.rawSize.width.toFloat() / mCameraInfo.rawSize.height,
                sizes
            )

            mRotatedPreviewWidth = if (mCameraInfo.areDimensionsSwapped) previewSize.height else previewSize.width
            mRotatedPreviewHeight = if (mCameraInfo.areDimensionsSwapped) previewSize.width else previewSize.height

            runOnUiThread {
                mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_CAMERA_STATE, "Camera: open")
                mBinding.surfaceView.holder.setFixedSize(mRotatedPreviewWidth, mRotatedPreviewHeight)
            }

            try {
                val outputSurfaces = mutableListOf(
                        mBinding.surfaceView.holder.surface,
                        mImageReaderHisto.surface
                    )

                mImageReaderJpeg = createImageReader( mCameraInfo.jpegSize, ImageFormat.JPEG)
                mImageReaderDng = createImageReader(mCameraInfo.rawSize, ImageFormat.RAW_SENSOR)

                mImageReaderJpeg?.let { outputSurfaces.add(it.surface) }
                mImageReaderDng?.let { outputSurfaces.add(it.surface) }

                val outputConfigs = mutableListOf<OutputConfiguration>()

                outputSurfaces.forEach {
                    outputConfigs.add(OutputConfiguration(1, it))
                }

                val physicalId = mCameraInfo.physicalId
                if (null != physicalId) {
                    outputConfigs.forEach {
                        it.setPhysicalCameraId(physicalId)
                    }
                }

                val sessionConfiguration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    mExecutor,
                    mCameraCaptureSessionStateCallback
                )

                cameraDevice.createCaptureSession(sessionConfiguration)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun callSafe(f: ()->Unit) {
        try {
            f()
        } catch(e: Exception) {
            e.printStackTrace()
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
            speedValue,
            calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, suggestedIso, speedValue)
        )
    }

    private fun sequenceTakeNextPhotoNow() {
        mSequenceTimer?.cancel()
        mSequenceTimer = null
        runOnUiThread {
            if (mSequenceStarted) {
                takePhoto(false, true)
            }
        }
    }

    private fun sequenceTakeNextPhotoAfterDelay(delay: Int) {
        mSequencePhotoDelayCounter = delay
        mBinding.frameView.setSequencePhotoDelay(mSequencePhotoDelayCounter)

        val msDelay = if (delay <= 0) 10L else 1000L
        mSequenceTimer = timer(null, false, msDelay, msDelay) {
            if (mSequencePhotoDelayCounter > 0) mSequencePhotoDelayCounter--
            runOnUiThread {
                mBinding.frameView.setSequencePhotoDelay(mSequencePhotoDelayCounter)
            }
            if (mSequencePhotoDelayCounter <= 0) {
                sequenceTakeNextPhotoNow()
            }
        }
    }

    private fun sequencePhotoTaken() {
        Log.i("TAKE_PHOTO", "mSequenceStarted: ${mSequenceStarted}, mPhotoCounter: $mPhotoCounter")
        if (!mSequenceStarted) return

        if (PHOTO_MODE_SEQUENCE == mPhotoMode) {
            if (settings.sequenceNumberOfPhotos > 0 && mPhotoCounter >= settings.sequenceNumberOfPhotos ) {
                sequenceOrMacroStop()
            } else {
                sequenceTakeNextPhotoAfterDelay(settings.sequenceDelayBetween)
            }
            return
        }

        //PHOTO_MODE_MACRO
        if (mPhotoCounter >= settings.macroNumberOfPhotos) {
            sequenceOrMacroStop()
        } else {
            sequenceTakeNextPhotoAfterDelay(0)
        }
    }

    private fun sequenceOrMacroStop() {
        mSequenceTimer?.cancel()
        mSequenceTimer = null
        mSequenceStarted = false
        mBinding.frameView.setSequencePhotoDelay(0)
        mPhotoCounter = 0
        mBinding.frameView.showCounter(mPhotoCounter)
        updateSliders()
    }

    private fun sequenceOrMacroStart() {
        mSequenceStarted = true
        mPhotoCounter = 0
        mBinding.frameView.showCounter(mPhotoCounter)
        updateSliders()
        sequenceTakeNextPhotoAfterDelay(if (PHOTO_MODE_MACRO == mPhotoMode) settings.macroDelayStart else settings.sequenceDelayStart)
    }

    private fun sequenceOrMacroToggle() {
        if (mSequenceStarted) {
            sequenceOrMacroStop()
        } else {
            sequenceOrMacroStart()
        }
    }

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
        mSelectCameraTimer?.cancel()
        mSelectCameraTimer = null

        sequenceOrMacroStop()

        mOrientationEventListener?.disable()
        closeCamera()
        settings.saveProperties()

        mBinding.frameView.hideFocusZone()
        mBinding.frameView.showCounter(0)
        mBinding.frameView.showSavePhotosIcon(false)
        mBinding.frameView.showTakePhotoIcon(false)

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
                try {
                    contentResolver.takePersistableUriPermission(data,  Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch(e: Exception) {
                    e.printStackTrace()
                }

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
        if (grantResults.size < PERMISSIONS.size) {
            fatalError("Permissions are mandatory !")
            return
        }

        var allowedAll = true
        for ( result in grantResults ) {
            if (result != PackageManager.PERMISSION_GRANTED ) {
                allowedAll = false
                break
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

        mThread.start()
        mHandler = Handler(mThread.looper)
        mImageReaderHisto.setOnImageAvailableListener(mImageReaderHistoListener, mHandler)

        setContentView(mBinding.root)

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

        mBinding.txtSequenceKeepPhotos.setOnMoveXAxisListener { steps ->
            var newValue = settings.sequenceKeepPhotos + steps
            if (newValue < 0) {
                newValue = 0
            } else if (newValue > Settings.SEQUENCE_KEEP_PHOTOS_1_FOR_N_MAX) {
                newValue = Settings.SEQUENCE_KEEP_PHOTOS_1_FOR_N_MAX
            }
            if (newValue != settings.sequenceKeepPhotos) {
                settings.sequenceKeepPhotos = newValue
                giveHapticFeedback(mBinding.txtSequenceKeepPhotos)
                updateSequenceKeepPhotos()
            }
        }

        mBinding.txtMacroDelayStart.setOnMoveXAxisListener { steps ->
            val newValue = settings.getArrayValue( settings.macroDelayStart, steps, Settings.MACRO_DELAY_START_OPTIONS )
            if (newValue != settings.macroDelayStart) {
                giveHapticFeedback(mBinding.txtMacroDelayStart)
                settings.macroDelayStart = newValue
                updateMacroDelayStart()
            }
        }

        mBinding.txtMacroNumberOfPhotos.setOnMoveXAxisListener { steps ->
            val newValue = settings.getArrayValue( settings.macroNumberOfPhotos, steps, Settings.MACRO_NUMBER_OF_PHOTOS_OPTIONS )
            if (newValue != settings.macroNumberOfPhotos) {
                settings.macroNumberOfPhotos = newValue
                giveHapticFeedback(mBinding.txtMacroNumberOfPhotos)
                updateMacroNumberOfPhotos()
            }
        }

        mBinding.surfaceView.holder.addCallback(mSurfaceHolderCallback)

        mBinding.btnExit.setOnClickListener {
            moveTaskToBack(true)
        }

        mBinding.txtFlash.setOnMoveXAxisListener { steps ->
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

        mBinding.txtCamera.setOnMoveXAxisListener { steps ->
            val newCameraIndex =
                if (steps < 0) (settings.cameraIndex - 1 + mCameraList.size) % mCameraList.size
                else (settings.cameraIndex + 1) % mCameraList.size
            giveHapticFeedback(mBinding.txtCamera)
            selectCamera(newCameraIndex, true)
        }

        mBinding.txtPhotoMode.setOnMoveXAxisListener { steps ->
            var newValue = mPhotoMode + steps
            while (newValue < 0) newValue += PHOTO_MODE_MAX
            newValue %= PHOTO_MODE_MAX

            if (PHOTO_MODE_MACRO == newValue && !mCameraInfo.focusAllowManual) {
                newValue = if (steps < 0) PHOTO_MODE_SEQUENCE else PHOTO_MODE_PHOTO
            }

            if (newValue != mPhotoMode) {
                mPhotoMode = newValue
                giveHapticFeedback(mBinding.txtPhotoMode)
                sequenceOrMacroStop()
                updateSliders()
            }
        }

        mCameraInfo = mCameraList[0]

        mBinding.txtIso.setOnMoveYAxisListener {
            settings.isoMode = if (settings.isoMode > 0) 0 else 1
            giveHapticFeedback(mBinding.txtIso)
            updateSliders()
        }

        mBinding.txtIso.setOnMoveXAxisListener { trackIso(it) }

        mBinding.txtSpeed.setOnMoveYAxisListener {
            settings.speedMode = if (settings.speedMode > 0) 0 else 1
            giveHapticFeedback(mBinding.txtSpeed)
            updateSliders()
        }

        mBinding.txtSpeed.setOnMoveXAxisListener { trackSpeed(it) }

        mBinding.txtExpCompensation.setOnMoveXAxisListener { trackExpCompensation(it) }

        mBinding.txtFocus.setOnMoveXAxisListener { steps ->
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

        mBinding.switch4X.isChecked = false
        mBinding.switch4X.setOnCheckedChangeListener { _, _ ->
            giveHapticFeedback(mBinding.switch4X)
            setupCapturePreviewRequest()
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
        @SuppressLint("SetTextI18n")
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
        @SuppressLint("SetTextI18n")
        mBinding.txtSpeed.text = "${getSpeedStr(speed)} (${Settings.SPEED_MODE_TO_STRING[settings.speedMode]})"
    }

    private fun showFocus() {
        if (mCameraInfo.focusAllowManual) {
            when(settings.focusType) {
                Settings.FOCUS_TYPE_HYPERFOCAL -> {
                    @SuppressLint("SetTextI18n")
                    mBinding.txtFocus.text = "Focus: Hyperfocal"
                    mBinding.txtFocus.visibility = View.VISIBLE
                    mBinding.seekBarFocus.visibility = View.INVISIBLE
                }

                Settings.FOCUS_TYPE_MANUAL -> {
                    @SuppressLint("SetTextI18n")
                    mBinding.txtFocus.text = "Focus: Manual"
                    mBinding.txtFocus.visibility = View.VISIBLE
                    mBinding.seekBarFocus.visibility = View.VISIBLE
                }

                else -> {
                    @SuppressLint("SetTextI18n")
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
        else if (0 == value)
            "immediately"
        else
            "$value seconds"

    private fun getNumberOfPhotosText(value: Int) =
        when (value) {
            0 -> "infinite"
            1 -> "1 photo"
            else -> "$value photos"
        }

    private fun updateSequenceDelayStart() {
        @SuppressLint("SetTextI18n")
        mBinding.txtSequenceDelayStart.text = "Delay start: ${getSecondsText(settings.sequenceDelayStart)}"
    }

    private fun updateSequenceDelayBetween() {
        @SuppressLint("SetTextI18n")
        mBinding.txtSequenceDelayBetween.text = "Delay between: ${getSecondsText(settings.sequenceDelayBetween)}"
    }

    private fun updateSequenceNumberOfPhotos() {
        @SuppressLint("SetTextI18n")
        mBinding.txtSequenceNumberOfPhotos.text = "Number of photos: ${getNumberOfPhotosText(settings.sequenceNumberOfPhotos)}"
    }

    private fun updateSequenceKeepPhotos() {
        @SuppressLint("SetTextI18n")
        mBinding.txtSequenceKeepPhotos.text = if (0 == settings.sequenceKeepPhotos)
            "Keep all photos"
        else
            "Keep 1 photo every ${settings.sequenceKeepPhotos + 1} photos"
    }

    private fun updateMacroDelayStart() {
        @SuppressLint("SetTextI18n")
        mBinding.txtMacroDelayStart.text = "Delay start: ${getSecondsText(settings.macroDelayStart)}"
    }

    private fun updateMacroNumberOfPhotos() {
        @SuppressLint("SetTextI18n")
        mBinding.txtMacroNumberOfPhotos.text = "Number of photos: ${getNumberOfPhotosText(settings.macroNumberOfPhotos)}"
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

    private fun updatePhotoMode() {
        if (PHOTO_MODE_MACRO == mPhotoMode && !mCameraInfo.focusAllowManual) {
            mPhotoMode = PHOTO_MODE_PHOTO
        }

        mBinding.txtPhotoMode.text = when(mPhotoMode) {
            PHOTO_MODE_SEQUENCE -> "Sequence"
            PHOTO_MODE_MACRO -> "Macro"
            else -> "Photo"
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
        updateSequenceKeepPhotos()
        updateMacroDelayStart()
        updateMacroNumberOfPhotos()
        updateFlashMode()
        updatePhotoMode()

        if (PHOTO_MODE_SEQUENCE == mPhotoMode) {
            if (mSequenceStarted) {
                mBinding.btnPhoto.backgroundTintList = ColorStateList.valueOf(Color.GRAY)
                mBinding.txtSequenceDelayStart.isEnabled = false
                mBinding.txtSequenceDelayBetween.isEnabled = false
                mBinding.txtSequenceNumberOfPhotos.isEnabled = false
                mBinding.txtSequenceKeepPhotos.isEnabled = false
            } else {
                mBinding.btnPhoto.backgroundTintList = ColorStateList.valueOf(Color.BLUE)
                mBinding.txtSequenceDelayStart.isEnabled = true
                mBinding.txtSequenceDelayBetween.isEnabled = true
                mBinding.txtSequenceNumberOfPhotos.isEnabled = true
                mBinding.txtSequenceKeepPhotos.isEnabled = true
            }
        } else if (PHOTO_MODE_MACRO == mPhotoMode) {
            if (mSequenceStarted) {
                mBinding.btnPhoto.backgroundTintList = ColorStateList.valueOf(Color.GRAY)
                mBinding.txtMacroDelayStart.isEnabled = false
                mBinding.txtMacroNumberOfPhotos.isEnabled = false
            } else {
                mBinding.btnPhoto.backgroundTintList = ColorStateList.valueOf(Color.GREEN)
                mBinding.txtMacroDelayStart.isEnabled = true
                mBinding.txtMacroNumberOfPhotos.isEnabled = true
            }
        } else {
            mBinding.btnPhoto.backgroundTintList = ColorStateList.valueOf(Color.RED)
        }

        mBinding.layoutSequences.visibility = if (PHOTO_MODE_SEQUENCE == mPhotoMode) View.VISIBLE else View.GONE
        mBinding.layoutMacro.visibility = if (PHOTO_MODE_MACRO == mPhotoMode) View.VISIBLE else View.GONE
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

                runOnUiThread {
                    mSaveAsyncBusy = false
                    if (failed) mBinding.frameView.showSaveError()
                    saveAsyncNextItem()
                }
            }
        } else if(!mSaveAsyncBusy) {
            mBinding.frameView.showSavePhotosIcon(false)
        }
    }

    private fun saveAsync(fileName: String, mimeType: String, byteArray: ByteArray) {
        runOnUiThread {
            mSaveAsyncMQ.add(Triple(fileName, mimeType, byteArray))
            saveAsyncNextItem()
        }
    }

    private fun saveImage(imageReader: ImageReader, captureResult: TotalCaptureResult? = null) {
        var imageOrNull: Image? = null

        callSafe { imageOrNull = imageReader.acquireLatestImage() }
        val image = imageOrNull ?: return

        if (!mSequenceStarted || (mPhotoCounter % (settings.sequenceKeepPhotos + 1)) == 0) {
            callSafe {
                if (image.format == ImageFormat.JPEG) {
                    Log.i("TAKE_PHOTO", "JPEG Done")
                    saveJpeg(image)
                    mPhotoTakeMask = mPhotoTakeMask and PHOTO_TAKE_JPEG.inv()
                } else {
                    Log.i("TAKE_PHOTO", "DNG Done")
                    if (null != captureResult) saveDng(image, captureResult)
                    mPhotoTakeMask = mPhotoTakeMask and PHOTO_TAKE_DNG.inv()
                }
            }
        }

        callSafe{ image.close() }
        checkTakePhotoFished()
    }

    private fun checkTakePhotoFished() {
        if (0 == mPhotoTakeMask) takePhoto(true)
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
            if (PHOTO_MODE_SEQUENCE == mPhotoMode || PHOTO_MODE_MACRO == mPhotoMode) {
                sequenceOrMacroToggle()
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
                setupCapturePhotoRequest(PHOTO_MODE_MACRO == mPhotoMode)
            }

            var takeNewPhoto = start

            if (newFile) {
                mPhotoCounter++
                mBinding.frameView.showCounter(mPhotoCounter)

                takeNewPhoto = settings.continuousMode && mPhotoButtonPressed && !mSequenceStarted

                if (mSequenceStarted) {
                    sequencePhotoTaken()
                }
            }

            mBinding.frameView.showTakePhotoIcon(takeNewPhoto)

            val captureRequestBuilder = mCaptureRequestBuilder
            val cameraCaptureSession = mCameraCaptureSession

            if (takeNewPhoto && null != captureRequestBuilder && null != cameraCaptureSession) {
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
                        takePhoto(false)
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

                    callSafe {
                        cameraCaptureSession.capture(
                            captureRequestBuilder.build(),
                            mCameraCaptureSessionPhotoCaptureCallback,
                            mHandler
                        )
                    }
                }
            } else {
                mPhotoInProgress = false

                if (!mSequenceStarted || PHOTO_MODE_MACRO != mPhotoMode) {
                    setupCapturePreviewRequest()
                }
            }
        }
    }

    private fun closeImageReader( imageReader: ImageReader? ) {
        if (null == imageReader) return
        callSafe {
            imageReader.close()
        }
    }

    private fun createImageReader( size: Size, format: Int ): ImageReader {
        val imageReader = ImageReader.newInstance(size.width, size.height, format, 1)

        if (ImageFormat.JPEG == format) {
            imageReader.setOnImageAvailableListener(mImageReaderJpegListener, mHandler)
        }

        return imageReader
    }

    /** Select the camera */
    @SuppressLint("MissingPermission")
    private fun selectCamera(index: Int, async: Boolean = false) {
        mSelectCameraTimer?.cancel()
        mSelectCameraTimer = null

        @SuppressLint("SetTextI18n")
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

        closeCamera()

        mPhotoInProgress = false
        settings.cameraIndex = index
        mCameraInfo = mCameraList[index]
        mFocusState = FOCUS_STATE_MANUAL

        val set = ConstraintSet()
        set.clone(mBinding.layoutView)
        set.setDimensionRatio(mBinding.layoutWithRatio.id, "${mCameraInfo.rawSize.width}:${mCameraInfo.rawSize.height}")
        set.applyTo(mBinding.layoutView)

        closeImageReader(mImageReaderJpeg)
        closeImageReader(mImageReaderDng)

        mImageReaderJpeg = null
        mImageReaderDng = null

        updateSliders()

        mBinding.frameView.setDebugInfo(FrameView.DEBUG_INFO_CAMERA_STATE, "Camera: opening")

        callSafe {
            mCameraManager.openCamera(mCameraInfo.id, mExecutor, mCameraDeviceStateCallback)
        }
    }

    private fun closeCamera() {
        val cameraCaptureSession = mCameraCaptureSession
        if (null != cameraCaptureSession) {
            callSafe{ cameraCaptureSession.stopRepeating() }
            callSafe{ cameraCaptureSession.close() }
            mCameraCaptureSession = null
        }

        val cameraDevice = mCameraDevice
        if (null != cameraDevice) {
            callSafe{ cameraDevice.close() }
            mCameraDevice = null
        }

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
        if (mPhotoInProgress) return

        val imageReaderDng = mImageReaderDng ?: return
        val imageReaderJpeg = mImageReaderJpeg ?: return

        val captureRequestBuilder = mCaptureRequestBuilder ?: return
        val cameraCaptureSession = mCameraCaptureSession ?: return

        Log.i("TAKE_PHOTO", "setupCaptureRequest(${photoMode}, ${force})")

        if (photoMode != mCaptureModeIsPhoto || force) {
            mCaptureModeIsPhoto = photoMode

            callSafe{ cameraCaptureSession.stopRepeating() }

            captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

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
                    if (settings.noiseReduction) {
                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    } else {
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    }
                )

                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, Rect(0, 0, mCameraInfo.rawSize.width, mCameraInfo.rawSize.height))

                when( settings.takePhotoModes ) {
                    Settings.PHOTO_TYPE_DNG -> {
                        captureRequestBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                        captureRequestBuilder.addTarget(imageReaderDng.surface)
                    }
                    Settings.PHOTO_TYPE_JPEG -> {
                        captureRequestBuilder.addTarget(imageReaderJpeg.surface)
                    }
                    else -> {
                        captureRequestBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                        captureRequestBuilder.addTarget(imageReaderJpeg.surface)
                        captureRequestBuilder.addTarget(imageReaderDng.surface)
                    }
                }

                if (mCameraInfo.focusAllowManual) {
                    if( PHOTO_MODE_MACRO == mPhotoMode) {
                        val distance = mCameraInfo.focusRange.lower +
                                (mCameraInfo.focusRange.upper - mCameraInfo.focusRange.lower) * mPhotoCounter / (settings.macroNumberOfPhotos - 1)
                        Log.i("TAKE_PHOTO", "Macro focus - min: ${mCameraInfo.focusRange.lower}, max: ${mCameraInfo.focusRange.upper}, counter: $mPhotoCounter / ${settings.macroNumberOfPhotos}, distance: $distance")
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    } else if (Settings.FOCUS_TYPE_HYPERFOCAL == settings.focusType) {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, mCameraInfo.focusHyperfocalDistance)
                    } else if (Settings.FOCUS_TYPE_MANUAL == settings.focusType && FOCUS_STATE_MANUAL == mFocusState) {
                            val distance = mCameraInfo.focusRange.lower + (100 - mBinding.seekBarFocus.progress) * (mCameraInfo.focusRange.upper - mCameraInfo.focusRange.lower) / 100
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    } else {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    }
                }
            } else {
                mLocation = null

                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)

                captureRequestBuilder.removeTarget(imageReaderDng.surface)
                captureRequestBuilder.removeTarget(imageReaderJpeg.surface)

                captureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
                captureRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                captureRequestBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
                captureRequestBuilder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    if (settings.noiseReduction) {
                        CaptureRequest.NOISE_REDUCTION_MODE_FAST
                    } else {
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    }
                )
            }
        }

        if (photoMode) return

        if (mBinding.switch4X.isChecked) {
            val croppedWidth = mCameraInfo.rawSize.width / 4
            val croppedHeight = mCameraInfo.rawSize.height / 4
            val croppedLeft = (mCameraInfo.rawSize.width - croppedWidth) / 2
            val croppedTop = (mCameraInfo.rawSize.height - croppedHeight) / 2
            val croppedRect = Rect( croppedLeft, croppedTop, croppedLeft + croppedWidth - 1, croppedTop + croppedHeight - 1 )
            captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, croppedRect)
        } else {
            captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, Rect(0, 0, mCameraInfo.rawSize.width, mCameraInfo.rawSize.height))
        }

        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, getFlashModeValue(true))

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
                        val delta = mCameraInfo.rawSize.width * FOCUS_REGION_SIZE_PERCENT / 100
                        val x = mCameraInfo.rawSize.width * mFocusClickPosition.x / 100
                        val y = mCameraInfo.rawSize.height * mFocusClickPosition.y / 100
                        val x1 = max(0, x - delta)
                        val y1 = max(0, y - delta)
                        val x2 = min(mCameraInfo.rawSize.width, x + delta)
                        val y2 = min(mCameraInfo.rawSize.height, y + delta)

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
                mHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
