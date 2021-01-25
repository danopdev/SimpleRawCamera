package com.dan.simplerawcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import com.dan.simplerawcamera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.schedule
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

        const val PHOTO_BUTTON_SCREEN = 1
        const val PHOTO_BUTTON_VOLUMNE_UP = 2
        const val PHOTO_BUTTON_VOLUMNE_DOWN = 4
        const val PHOTO_BUTTON_SEQUENCE = 8

        const val PHOTO_TAKE_COMPLETED = 1
        const val PHOTO_TAKE_JPEG = 2
        const val PHOTO_TAKE_DNG = 4
        const val PHOTO_TAKE_OUT_OF_MEMORY = 8

        const val FOCUS_STATE_MANUAL = 0
        const val FOCUS_STATE_CLICK = 1
        const val FOCUS_STATE_SEARCHING = 2
        const val FOCUS_STATE_LOCKED = 3

        const val MEMORY_RETRY_TIMEOUT = 250L //ms

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

        /** Calculate the diffrence between the preview / histogram and the manual / semi-manual photo settings */
        fun calculateExpDeviation(
            previewIso: Int,
            previewSpeed: Long,
            expectedIso: Int,
            expectedSpeed: Long
        ): Float {

            var deltaExpIso: Float =
                if (previewIso >= expectedIso)
                    log2(previewIso.toFloat() / expectedIso)
                else
                    -log2(expectedIso.toFloat() / previewIso)

            var deltaExpSpeed: Float =
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

    private var mPhotoButtonMask = 0
    private var mPhotoTakeMask = 0
    private var mPhotoTimestamp = 0L
    private var mPhotoFileNameBase = ""
    private var mPhotoCounter = 0
    private var mPhotoInProgress = false
    private var mPhotoTakenCallback: (()->Unit)? = null

    private val mImageReaderHisto = ImageReader.newInstance(100, 100, ImageFormat.YUV_420_888, 1)
    private lateinit var mImageReaderJpeg: ImageReader
    private lateinit var mImageReaderDng: ImageReader
    private var mImageDng: Image? = null
    private var mImageJpeg: Image? = null

    private var mIsoMeasuredValue = 100
    private var mSpeedMeasuredValue = 1L

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

    /** Generate histogram */
    private val mImageReaderHistoListener = object: ImageReader.OnImageAvailableListener {
        private var isBusy = false

        override fun onImageAvailable(imageReader: ImageReader?) {
            if (null == imageReader) return
            val image = imageReader.acquireLatestImage() ?: return

            if (!isBusy) {
                isBusy = true
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

                            if (yValue < 5) yValue = 0
                            else if (yValue >= 250) yValue = 245
                            else yValue -= 5

                            values[(HISTOGRAM_BITMAP_WIDTH - 1) * yValue / 245]++
                            index++
                        }
                    }

                    var maxHeight = 10
                    for (value in values)
                        maxHeight = max(maxHeight, value)
                    maxHeight++

                    val color = Color.rgb(192, 192, 192)
                    val colors = IntArray(HISTOGRAM_BITMAP_WIDTH * HISTOGRAM_BITMAP_HEIGHT)

                    for (x in values.indices) {
                        val value = values[x]
                        val fill =
                            HISTOGRAM_BITMAP_HEIGHT - 1 - (HISTOGRAM_BITMAP_HEIGHT - 1) * value / maxHeight

                        var y = 0
                        while (y < fill) {
                            colors[x + y * HISTOGRAM_BITMAP_WIDTH] = 0
                            y++
                        }
                        while (y < HISTOGRAM_BITMAP_HEIGHT) {
                            colors[x + y * HISTOGRAM_BITMAP_WIDTH] = color
                            y++
                        }
                    }

                    val bitmap = Bitmap.createBitmap(
                        colors,
                        0,
                        HISTOGRAM_BITMAP_WIDTH,
                        HISTOGRAM_BITMAP_WIDTH,
                        HISTOGRAM_BITMAP_HEIGHT,
                        Bitmap.Config.ARGB_8888
                    )

                    runOnUiThread {
                        mBinding.imgHistogram.setImageBitmap(bitmap)
                        isBusy = false
                    }
                }
            }

            image.close()
        }
    }

    /** JPEG Reader Listener */
    private val mImageReaderJpegListener = object: ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(imageReader: ImageReader?) {
            Log.i("TAKE_PHOTO", "JPEG: Received")

            imageReader?.acquireLatestImage()?.let{
                mImageJpeg = it
            }

            mPhotoTakeMask = mPhotoTakeMask and PHOTO_TAKE_JPEG.inv()
            if (0 == mPhotoTakeMask)
                takePhoto(true)
        }
    }

    /** DNG Reader Listener */
    private val mImageReaderDngListener = object: ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(imageReader: ImageReader?) {
            Log.i("TAKE_PHOTO", "DNG: Received")

            imageReader?.acquireLatestImage()?.let{
                mImageDng = it
            }

            mPhotoTakeMask = mPhotoTakeMask and PHOTO_TAKE_DNG.inv()
            if (0 == mPhotoTakeMask)
                takePhoto(true)
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
            if (0 == mPhotoTakeMask)
                takePhoto(true)
        }
    }

    /** Preview callback */
    private val mCameraCaptureSessionPreviewCaptureCallback = object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)

            mIsoMeasuredValue = result.get(CaptureResult.SENSOR_SENSITIVITY) as Int
            mSpeedMeasuredValue = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) as Long

            when(mFocusState) {
                FOCUS_STATE_CLICK -> {
                    var focusState = result.get(CaptureResult.CONTROL_AF_STATE) as Int
                    if (CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN == focusState) {
                        mFocusState = FOCUS_STATE_SEARCHING
                    }
                }

                FOCUS_STATE_SEARCHING -> {
                    var focusState = result.get(CaptureResult.CONTROL_AF_STATE) as Int
                    if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == focusState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == focusState) {
                        mFocusState = FOCUS_STATE_LOCKED
                        runOnUiThread {
                            setupCapturePreviewRequest()
                        }
                    }
                }
            }

            val captureEA = getCaptureEA()
            mBinding.txtExpDelta.text = "%.2f".format(captureEA.third)

            if (settings.expIsoIsManual && settings.expSpeedIsManual) return

            if (!settings.expIsoIsManual) showIso(captureEA.first)
            if (!settings.expSpeedIsManual) showSpeed(captureEA.second)
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
            if (null == sizes || 0 == sizes.size) throw Exception("No sizes available")
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

    /** Returns exposure informations: ISO, Speed and the differece between this values and the preview options */
    private fun getCaptureEA() : Triple<Int, Long, Float> {
        if (!settings.expIsoIsManual && !settings.expSpeedIsManual)
            return Triple(mIsoMeasuredValue, mSpeedMeasuredValue, 0f)

        if (settings.expIsoIsManual && settings.expSpeedIsManual) {
            val speedValue = getSpeedValue()
            return Triple(
                settings.expIsoValue,
                speedValue,
                calculateExpDeviation(
                    mIsoMeasuredValue,
                    mSpeedMeasuredValue,
                    settings.expIsoValue,
                    speedValue
                )
            )
        }

        if (settings.expIsoIsManual) {
            val isoRatio = mIsoMeasuredValue.toFloat() / settings.expIsoValue

            var suggestedSpeed = (mSpeedMeasuredValue * isoRatio).toLong()
            if (suggestedSpeed < mCameraInfo.speedRange.lower)
                suggestedSpeed = mCameraInfo.speedRange.lower
            else if (suggestedSpeed > mCameraInfo.speedRange.upper)
                suggestedSpeed = mCameraInfo.speedRange.upper

            return Triple(
                settings.expIsoValue,
                suggestedSpeed,
                calculateExpDeviation(
                    mIsoMeasuredValue,
                    mSpeedMeasuredValue,
                    settings.expIsoValue,
                    suggestedSpeed
                )
            )
        }

        val speedValue = getSpeedValue()
        val speedRatio = mSpeedMeasuredValue / speedValue

        var suggestedIso = (mIsoMeasuredValue * speedRatio).toInt()
        if (suggestedIso < mCameraInfo.isoRange.lower)
            suggestedIso = mCameraInfo.isoRange.lower
        else if (suggestedIso > mCameraInfo.isoRange.upper)
            suggestedIso = mCameraInfo.isoRange.upper

        return Triple(
            suggestedIso,
            mSpeedMeasuredValue,
            calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, suggestedIso, speedValue)
        )
    }

    /** There is not specific thread for the camera (currently I have problems) but maybe one-day */
    private fun getWorkerHandler(): Handler? { return null }

    private fun getSpeedValue(div: Long): Long = Settings.SPEED_MAX_MANUAL / div
    private fun getSpeedValue(): Long = getSpeedValue(settings.expSpeedDivValue)

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

        if (!askPermissions())
            onPermissionsAllowed()
    }

    override fun onResume() {
        super.onResume()
        mOrientationEventListener?.enable()
        if (mSurfaceIsCreated)
            selectCamera(settings.cameraIndex)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
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

                if (mFirstCall)
                    onValidSaveFolder()
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
        if (settings.cameraIndex < 0 || settings.cameraIndex >= mCameraList.size)
            settings.cameraIndex = 0

        if (1 == mCameraList.size) {
            mBinding.txtCamera.isVisible = false
            mBinding.btnCamera.isVisible = false
        }

        mImageReaderHisto.setOnImageAvailableListener(mImageReaderHistoListener, getWorkerHandler())

        setContentView(mBinding.root)

        mBinding.surfaceView.holder.addCallback(mSurfaceHolderCallback)

        mBinding.btnCamera.setOnClickListener { selectCamera((settings.cameraIndex + 1) % mCameraList.size) }

        mCameraInfo = mCameraList[0]

        mBinding.txtIso.setOnMoveYAxisListener {
            settings.expIsoIsManual = !settings.expIsoIsManual
            updateSliders()
        }

        mBinding.txtIso.setOnMoveXAxisListener { trackIso(it) }

        mBinding.txtSpeed.setOnMoveYAxisListener {
            settings.expSpeedIsManual = !settings.expSpeedIsManual
            updateSliders()
        }

        mBinding.txtSpeed.setOnMoveXAxisListener { trackSpeed(it) }

        mBinding.txtExpComponsation.setOnMoveXAxisListener { trackExpCompensation(it) }

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
                    setupCapturePreviewRequest()
                }
            }

            false
        }

        mBinding.btnPhoto.setOnTouchListener { _, motionEvent ->
            when(motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> takePhotoButton(true, PHOTO_BUTTON_SCREEN)
                MotionEvent.ACTION_UP -> takePhotoButton(false, PHOTO_BUTTON_SCREEN)
            }

            false
        }

        mBinding.btnSettings.setOnClickListener {
            SettingsDialog.show(supportFragmentManager, this) {
                updateFrame()
            }
        }

        mBinding.btnSequences.setOnClickListener {
            SequencesDialog.show(supportFragmentManager, this)
        }

        mOrientationEventListener = object: OrientationEventListener(
            this,
            SensorManager.SENSOR_DELAY_NORMAL
        ) {
            override fun onOrientationChanged(orientation: Int) {
                var screenOrientation = (orientation + 45) / 90 * 90 //round to 90°
                if (screenOrientation != mScreenOrientation) {
                    mScreenOrientation = screenOrientation
                    Log.i("EXIF", "Orientation: ${screenOrientation}")
                }
            }
        }

        updateFrame()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when(keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                takePhotoButton(false, PHOTO_BUTTON_VOLUMNE_UP)
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                takePhotoButton(false, PHOTO_BUTTON_VOLUMNE_DOWN)
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when(keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                takePhotoButton(true, PHOTO_BUTTON_VOLUMNE_UP)
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                takePhotoButton(true, PHOTO_BUTTON_VOLUMNE_DOWN)
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    /** ISO slider/button is changed */
    private fun trackIso(delta: Int) {
        if (!settings.expIsoIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var value = settings.expIsoValue

        while (counter > 0) {
            if (increase) {
                if (value >= mCameraInfo.isoRange.upper) break
                value *= 2
            } else {
                if (value <= mCameraInfo.isoRange.lower) break
                value /= 2
            }
            counter -= 1
        }

        showIso(value)
        settings.expIsoValue = value
        setupCapturePreviewRequest()
    }

    private fun showIso(value: Int) {
        val extra = if (settings.expIsoIsManual) "M" else "A"
        mBinding.txtIso.text = "${value} ISO (${extra})"
    }

    /** Exposure compensation slider/button is changed */
    private fun trackExpCompensation(delta: Int) {
        if (settings.expIsoIsManual && settings.expSpeedIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var value = settings.expCompensationValue

        while (counter > 0) {
            if (increase) {
                if (value >= mCameraInfo.exposureCompensantionRange.upper) break
                value++
            } else {
                if (value <= mCameraInfo.exposureCompensantionRange.lower) break
                value--
            }
            counter -= 1
        }

        showExpComponsation(value)
        settings.expCompensationValue = value
        setupCapturePreviewRequest()
    }

    private fun showExpComponsation(value: Int) {
        var exp = "Exp: "

        if (value >= 0) {
            exp += "+${value}"
        } else {
            exp += value.toString()
        }

        mBinding.txtExpComponsation.text = exp
    }

    /** Speed slider/button is changed */
    private fun trackSpeed(delta: Int) {
        if (!settings.expSpeedIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var speedDiv = settings.expSpeedDivValue

        while (counter > 0) {
            if (increase) {
                if (speedDiv == 1L || getSpeedValue(speedDiv / 2) > mCameraInfo.speedRange.upper) break
                speedDiv /= 2
            } else {
                if (getSpeedValue(speedDiv * 2) < mCameraInfo.speedRange.lower) break
                speedDiv *= 2
            }
            counter -= 1
        }

        showSpeed(getSpeedValue(speedDiv))
        settings.expSpeedDivValue = speedDiv
        setupCapturePreviewRequest()
    }

    private fun getSpeedStr(speed: Long): String {
        if (speed >= 1000000000L) { // 1 second
            val speedSecondsWith1Decimal = (speed / 100000000).toInt()
            val speedSeconds = speedSecondsWith1Decimal / 10
            val speedDecimals = speedSecondsWith1Decimal % 10

            if (0 == speedDecimals)
                return "${speedSeconds}\""
            return "${speedSeconds}.${speedDecimals}\""
        }

        val denominator = (1000000000L / speed).toInt()
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

        return "1/${roundedDenominator}"
    }

    private fun showSpeed(speed: Long) {
        val extra = if (settings.expSpeedIsManual) " (M)" else " (A)"
        mBinding.txtSpeed.text = getSpeedStr(speed) + extra
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

    private fun updateSliders() {
        mBinding.txtExpComponsation.visibility = if (!settings.expIsoIsManual || !settings.expSpeedIsManual) View.VISIBLE else View.INVISIBLE

        if (settings.expIsoIsManual)
            showIso(settings.expIsoValue)

        if (settings.expSpeedIsManual)
            showSpeed(getSpeedValue())

        showFocus()
        showExpComponsation(settings.expCompensationValue)
        setupCapturePreviewRequest()
    }

    private fun takePhotoButton(pressed: Boolean, source: Int) {
        val mask =
            if (pressed) {
                mPhotoButtonMask or source
            } else {
                mPhotoButtonMask and source.inv()
            }

        updateTakePhotoButtonMask(mask)
    }

    /** Update take photo button pressed based on difference sources: screen button, volume up or volume down */
    private fun updateTakePhotoButtonMask(mask: Int) {
        if (mask != mPhotoButtonMask) {
            val oldMask = mPhotoButtonMask
            mPhotoButtonMask = mask
            Log.i("TAKE_PHOTO", "Mask: " + mask.toString())

            if (0 != mask && 0 == oldMask) {
                mPhotoCounter = 0
                mPhotoTakeMask = 0
                setupCapturePhotoRequest()
                takePhoto(false, true)
            }
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
                var failed = false

                try {
                    mSaveFolder?.let { saveFolder ->
                        saveFolder.createFile(mimeType, fileName)?.let { newFile ->
                            contentResolver.openOutputStream(newFile.uri)?.let { outputStream ->
                                outputStream.write(byteArray)
                                outputStream.close()
                            }
                        }
                    }
                } catch (e: Exception) {
                    failed = true
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
            saveAsync(mPhotoFileNameBase + ".dng", "image/x-adobe-dng", outputStream.toByteArray())
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
            saveAsync(mPhotoFileNameBase + ".jpg", "image/jpeg", outputStream.toByteArray())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Log.i("TAKE_PHOTO", "JPEG: Save ends")
    }

    fun takePhotoWithCallback(callback: () -> Unit) {
        mPhotoTakenCallback = callback
        takePhotoButton(true, PHOTO_BUTTON_SEQUENCE)
    }

    /** Start taking a photo */
    private fun takePhoto(newFile: Boolean = false, start: Boolean = false) {
        runOnUiThread {
            mBinding.frameView.updateDebugMemInfo()

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

                takeNewPhoto = settings.continuousMode && (0 != mPhotoButtonMask) && (null == mPhotoTakenCallback)

                val photoTakenCallback = mPhotoTakenCallback
                mPhotoTakenCallback = null
                mPhotoButtonMask = mPhotoButtonMask and PHOTO_BUTTON_SEQUENCE.inv()

                if (realNewFile && null != photoTakenCallback) {
                    photoTakenCallback.invoke()
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
                minMem = 1 + minMem / (1024 * 1024) //conver to MB

                if (mSaveAsyncMQ.isNotEmpty() && minMem > getFreeMemInfo()) {
                    Log.i("TAKE_PHOTO", "Not enougth memory")
                    mPhotoTakeMask = PHOTO_TAKE_OUT_OF_MEMORY
                    Timer("Out of memory", false).schedule(MEMORY_RETRY_TIMEOUT) {
                        mPhotoTakeMask = 0
                        takePhoto(true)
                    }
                } else {
                    Log.i("TAKE_PHOTO", "New photo")

                    mPhotoInProgress = true

                    when (settings.takePhotoModes) {
                        Settings.PHOTO_TYPE_DNG -> mPhotoTakeMask = PHOTO_TAKE_DNG or PHOTO_TAKE_COMPLETED
                        Settings.PHOTO_TYPE_JPEG -> mPhotoTakeMask = PHOTO_TAKE_JPEG or PHOTO_TAKE_COMPLETED
                        else -> mPhotoTakeMask = PHOTO_TAKE_JPEG or PHOTO_TAKE_DNG or PHOTO_TAKE_COMPLETED
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
    private fun selectCamera(index: Int) {
        mPhotoInProgress = false
        settings.cameraIndex = index
        mCameraInfo = mCameraList[index]
        mFocusState = FOCUS_STATE_MANUAL

        closeCamera()

        mBinding.txtCamera.text = index.toString()

        val set = ConstraintSet()
        set.clone(mBinding.layoutView)
        set.setDimensionRatio(
            mBinding.layoutWithRatio.getId(),
            "${mCameraInfo.resolutionWidth}:${mCameraInfo.resolutionHeight}"
        )
        set.applyTo(mBinding.layoutView)

        mImageReaderJpeg = ImageReader.newInstance(
            mCameraInfo.resolutionWidth,
            mCameraInfo.resolutionHeight,
            ImageFormat.JPEG,
            1
        )
        mImageReaderJpeg.setOnImageAvailableListener(mImageReaderJpegListener, getWorkerHandler())

        mImageReaderDng = ImageReader.newInstance(
            mCameraInfo.resolutionWidth,
            mCameraInfo.resolutionHeight,
            ImageFormat.RAW_SENSOR,
            1
        )
        mImageReaderDng.setOnImageAvailableListener(mImageReaderDngListener, getWorkerHandler())

        updateSliders()

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
        if (mCameraInfo.supportLensStabilisation)
            captureRequestBuilder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )

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
        if (mPhotoInProgress) return

        val captureRequestBuilder = mCaptureRequestBuilder ?: return
        val cameraCaptureSession = mCameraCaptureSession ?: return

        if (photoMode != mCaptureModeIsPhoto || force) {
            mCaptureModeIsPhoto = photoMode

            if (photoMode) {
                cameraCaptureSession.stopRepeating()

                captureRequestBuilder.set(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                )
                captureRequestBuilder.set(
                    CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_HIGH_QUALITY
                )
                captureRequestBuilder.set(
                    CaptureRequest.HOT_PIXEL_MODE,
                    CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
                )
                captureRequestBuilder.set(
                    CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                )

                if (settings.expIsoIsManual || settings.expSpeedIsManual) {
                    val ae = getCaptureEA()
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF
                    )
                    captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ae.second)
                    captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, ae.first)
                } else {
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

                mLocation = mLocationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                captureRequestBuilder.set(CaptureRequest.JPEG_GPS_LOCATION, mLocation)

                val photoOrientation = getPhotoOrientation()
                mPhotoExifOrientation = getPhotoExifOrientation(photoOrientation)
                captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, photoOrientation)

                captureRequestBuilder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    if (Settings.NOISE_REDUCTION_ENABLED == settings.noiseReduction ||
                        (Settings.NOISE_REDUCTION_JPEG_ONLY == settings.noiseReduction && Settings.PHOTO_TYPE_JPEG == settings.takePhotoModes)
                    )
                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    else
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                )

                mCaptureRequest = captureRequestBuilder.build()
            } else {
                mLocation = null
                mCaptureRequest = null

                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)

                captureRequestBuilder.removeTarget(mImageReaderDng.surface)
                captureRequestBuilder.removeTarget(mImageReaderJpeg.surface)

                captureRequestBuilder.set(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST
                )
                captureRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                captureRequestBuilder.set(
                    CaptureRequest.HOT_PIXEL_MODE,
                    CaptureRequest.HOT_PIXEL_MODE_FAST
                )
                captureRequestBuilder.set(
                    CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
                )
                captureRequestBuilder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    if (Settings.NOISE_REDUCTION_DISABLED == settings.noiseReduction)
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    else
                        CaptureRequest.NOISE_REDUCTION_MODE_FAST
                )
            }
        }

        if (photoMode) return

        captureRequestBuilder.set(
            CaptureRequest.CONTROL_CAPTURE_INTENT,
            CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
        )

        if (mCameraInfo.focusAllowManual) {
            when(settings.focusType) {
                Settings.FOCUS_TYPE_HYPERFOCAL -> {
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    captureRequestBuilder.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        mCameraInfo.focusHyperfocalDistance
                    )
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
                            val rectangle = MeteringRectangle(
                                x1,
                                y1,
                                x2 - x1,
                                y2 - y1,
                                MeteringRectangle.METERING_WEIGHT_MAX
                            )

                            mFocusState = FOCUS_STATE_CLICK
                            captureRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_REGIONS, arrayOf(
                                    rectangle
                                )
                            )
                            captureRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_AUTO
                            )
                            captureRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_START
                            )
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
                        captureRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                        )
                    } else if (FOCUS_STATE_MANUAL == mFocusState) {
                        val distance = mCameraInfo.focusRange.lower +
                                (100 - mBinding.seekBarFocus.progress) * (mCameraInfo.focusRange.upper - mCameraInfo.focusRange.lower) / 100
                        captureRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_OFF
                        )
                        captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                        mBinding.frameView.hideFocusZone()
                    }
                }

                else -> {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    mBinding.frameView.hideFocusZone()
                }
            }
        }

        //WORKAROUND: My camera block with click to focus in full manual mode
        if ((FOCUS_STATE_CLICK == mFocusState || FOCUS_STATE_SEARCHING == mFocusState) && settings.expIsoIsManual && settings.expSpeedIsManual) {
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        } else if (!settings.expIsoIsManual || !settings.expSpeedIsManual) {
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                settings.expCompensationValue * mCameraInfo.exposureCompensantionMulitplyFactor
            )
        } else {
            var manualSpeed = getSpeedValue()
            var manualISO = settings.expIsoValue

            if (manualSpeed > Settings.SPEED_MANUAL_MIN_PREVIEW) {
                while (manualSpeed > Settings.SPEED_MANUAL_MIN_PREVIEW) {
                    if ((2*manualISO) > mCameraInfo.isoRange.upper)
                        break

                    manualISO *= 2
                    manualSpeed /= 2
                }

                manualSpeed = min(Settings.SPEED_MANUAL_MIN_PREVIEW, manualSpeed)
            }

            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualSpeed)
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, manualISO)
        }

        cameraCaptureSession.setRepeatingRequest(
            captureRequestBuilder.build(),
            mCameraCaptureSessionPreviewCaptureCallback,
            getWorkerHandler()
        )
    }
}
