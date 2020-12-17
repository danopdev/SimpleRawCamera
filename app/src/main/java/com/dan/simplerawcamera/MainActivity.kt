package com.dan.simplerawcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.location.LocationManager
import android.media.ImageReader
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
import com.dan.simplerawcamera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

        const val HISTOGRAM_BITMAP_WIDTH = 64
        const val HISTOGRAM_BITMAP_HEIGHT = 50

        const val FOCUS_REGION_SIZE_PERCENT = 5

        const val PHOTO_BUTTON_SCREEN = 1
        const val PHOTO_BUTTON_VOLUMNE_UP = 2
        const val PHOTO_BUTTON_VOLUMNE_DOWN = 4

        const val PHOTO_TAKE_SINGLE_SHOT = 1
        const val PHOTO_TAKE_JPEG = 2
        const val PHOTO_TAKE_DNG = 4

        val FILE_NAME_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

        fun getPhotoBaseFileName( timestamp: Long ): String = FILE_NAME_DATE_FORMAT.format(Date(timestamp))

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

        fun calculateExpDeviation(visibleIso: Int, visibleSpeed: Long, expectedIso: Int, expectedSpeed: Long): Float {
            var deltaExpIso: Float = (expectedIso - visibleIso).toFloat() / expectedIso
            var deltaExpSpeed: Float = (expectedSpeed - visibleSpeed).toFloat() / expectedSpeed
            return deltaExpIso + deltaExpSpeed
        }
    }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mSettings: Settings by lazy { Settings(this) }
    private val mLocationManager: LocationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val mCameraManager: CameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val mCameraList: ArrayList<CameraHandler> by lazy { CameraHandler.getValidCameras(mCameraManager) }

    private lateinit var mCameraHandler: CameraHandler
    private var mCameraDevice: CameraDevice? = null
    private var mCameraCaptureSession: CameraCaptureSession? = null
    private var mCaptureRequestBuilder: CaptureRequest.Builder? = null
    private var mCaptureRequest: CaptureRequest? = null
    private var mCaptureModeIsPhoto = true
    private var mCaptureLastPhotoResult: TotalCaptureResult? = null

    private var mPhotoButtonMask = 0
    private var mPhotoTakeMask = 0
    private var mPhotoTimestamp = 0L
    private var mPhotoFileNameBase = ""

    private val mImageReaderHisto = ImageReader.newInstance(100, 100, ImageFormat.YUV_420_888, 1)
    private lateinit var mImageReaderJpeg: ImageReader
    private lateinit var mImageReaderDng: ImageReader

    private var mIsoMeasuredValue = 100
    private var mSpeedMeasuredValue = 1L

    private var mFocusClick = false
    private var mFocusClickPosition = Point(0, 0)

    private var mRotatedPreviewWidth = 4
    private var mRotatedPreviewHeight = 3

    private var mFirstCall = true
    private var mSurfaceIsCreated = false

    private var mDestFolder = File("/storage/emulated/0/SimpleRawCamera")

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
                            values[(HISTOGRAM_BITMAP_WIDTH - 1) * yValue / 255]++
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

                    val bitmap = Bitmap.createBitmap(colors, 0, HISTOGRAM_BITMAP_WIDTH, HISTOGRAM_BITMAP_WIDTH, HISTOGRAM_BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)

                    runOnUiThread {
                        mBinding.imgHistogram.setImageBitmap(bitmap)
                        isBusy = false
                    }
                }
            }

            image.close()
        }
    }

    private val mImageReaderJpegListener = object: ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(imageReader: ImageReader?) {
            Log.i("TAKE_PHOTO", "JPEG: ${mPhotoTimestamp}")

            if (null != imageReader) {
                val image = imageReader.acquireLatestImage()
                if (null != image) {
                    try {
                        val jpegFile = getPhotoJpegFile()
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val fos = jpegFile.outputStream()
                        fos.write(bytes)
                        fos.close()
                    } catch (e: Exception) {
                    }

                    image.close()
                }
            }

            mPhotoTakeMask = mPhotoButtonMask and PHOTO_TAKE_JPEG.inv()

            runOnUiThread {
                takePhoto()
            }
        }
    }

    private val mImageReaderDngListener = object: ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(imageReader: ImageReader?) {
            Log.i("TAKE_PHOTO", "DNG ${mPhotoTimestamp}")

            if (null != imageReader) {
                val image = imageReader.acquireLatestImage()
                if (null != image) {
                    val captureLastPhotoResult = mCaptureLastPhotoResult
                    if (null != captureLastPhotoResult) {
                        try {
                            val dngCreator = DngCreator(mCameraHandler.cameraCharacteristics, captureLastPhotoResult)

                            val dngFile = getPhotoDngFile()
                            val fos = dngFile.outputStream()
                            dngCreator.writeImage(fos, image)
                            fos.close()

                        } catch (e: Exception) {
                        }
                    }

                    image.close()
                }
            }

            mPhotoTakeMask = mPhotoButtonMask and PHOTO_TAKE_DNG.inv()

            runOnUiThread {
                takePhoto()
            }
        }
    }

    private val mSurfaceHolderCallback = object: SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            selectCamera(mSettings.cameraIndex)
            mSurfaceIsCreated = true

            if (mFirstCall) { //workaround ratio issue
                mFirstCall = false
                Timer().schedule(500) {
                    runOnUiThread {
                        selectCamera(mSettings.cameraIndex)
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

            val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(mBinding.surfaceView.holder.surface)
            captureRequestBuilder.addTarget(mImageReaderHisto.surface)

            setupCaptureInitRequest(captureRequestBuilder)

            mCaptureRequestBuilder = captureRequestBuilder

            setupCapturePreviewRequest()
        }
    }

    private fun getCaptureEA() : Triple<Int, Long, Float> {
        if (!mSettings.expIsoIsManual && !mSettings.expSpeedIsManual)
            return Triple(mIsoMeasuredValue, mSpeedMeasuredValue, 0f)

        if (mSettings.expIsoIsManual && mSettings.expSpeedIsManual) {
            val speedValue = getSpeedValue()
            return Triple(
                mSettings.expIsoValue,
                Settings.SPEED_MAX_MANUAL / speedValue,
                calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, mSettings.expIsoValue, speedValue)
            )
        }

        if (mSettings.expIsoIsManual) {
            val isoRatio = mIsoMeasuredValue.toFloat() / mSettings.expIsoValue

            var suggestedSpeed = (mSpeedMeasuredValue * isoRatio).toLong()
            if (suggestedSpeed < mCameraHandler.speedRange.lower)
                suggestedSpeed = mCameraHandler.speedRange.lower
            else if (suggestedSpeed > mCameraHandler.speedRange.upper)
                suggestedSpeed = mCameraHandler.speedRange.upper

            return Triple(
                mSettings.expIsoValue,
                suggestedSpeed,
                calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, mSettings.expIsoValue, suggestedSpeed)
            )
        }

        val speedValue = getSpeedValue()
        val speedRatio = mSpeedMeasuredValue / speedValue

        var suggestedIso = (mIsoMeasuredValue * speedRatio).toInt()
        if (suggestedIso < mCameraHandler.isoRange.lower)
            suggestedIso = mCameraHandler.isoRange.lower
        else if (suggestedIso > mCameraHandler.isoRange.upper)
            suggestedIso = mCameraHandler.isoRange.upper

        return Triple(
            suggestedIso,
            mSpeedMeasuredValue,
            calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, suggestedIso, speedValue)
        )
    }

    private val mCameraCaptureSessionPreviewCaptureCallback = object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)

            mIsoMeasuredValue = result.get(CaptureResult.SENSOR_SENSITIVITY) as Int
            mSpeedMeasuredValue = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) as Long

            val captureEA = getCaptureEA()
            mBinding.txtExpDelta.text = "%.2f".format(captureEA.third)

            if (mSettings.expIsoIsManual && mSettings.expSpeedIsManual) return

            if (!mSettings.expIsoIsManual) showIso(captureEA.first)
            if (!mSettings.expSpeedIsManual) showSpeed(captureEA.second)
        }
    }

    private val mCameraCaptureSessionPhotoCaptureCallback = object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
            Log.i("TAKE_PHOTO", "onCaptureCompleted")
            mCaptureLastPhotoResult = result
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
                sizes
            )

            mRotatedPreviewWidth = if (mCameraHandler.areDimensionsSwapped) previewSize.height else previewSize.width
            mRotatedPreviewHeight = if (mCameraHandler.areDimensionsSwapped) previewSize.width else previewSize.height

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
                    Handler { true }
                )
            } catch(e: Exception) {
            }
        }
    }

    private fun getPhotoJpegFile(): File = File(mDestFolder.absolutePath + "/" + mPhotoFileNameBase + ".jpeg")
    private fun getPhotoDngFile(): File = File(mDestFolder.absolutePath + "/" + mPhotoFileNameBase + ".dng")

    private fun getSpeedValue( div: Long ): Long = Settings.SPEED_MAX_MANUAL / div
    private fun getSpeedValue(): Long = getSpeedValue(mSettings.expSpeedDivValue)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!askPermissions())
            onPermissionsAllowed()
    }

    override fun onResume() {
        super.onResume()
        if (mSurfaceIsCreated)
            selectCamera(mSettings.cameraIndex)
    }

    override fun onPause() {
        closeCamera()
        mSettings.saveProperties()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun onPermissionsAllowed() {
        if (mCameraList.size <= 0) {
            fatalError("No valid camera found !")
            return
        }

        if (mSettings.cameraIndex < 0 || mSettings.cameraIndex >= mCameraList.size)
            mSettings.cameraIndex = 0

        if (!mDestFolder.exists())
            mDestFolder.mkdirs()

        if (1 == mCameraList.size) {
            mBinding.txtCamera.isVisible = false
            mBinding.btnCamera.isVisible = false
        }

        mImageReaderHisto.setOnImageAvailableListener(mImageReaderHistoListener, Handler { true })

        setContentView(mBinding.root)

        mBinding.surfaceView.holder.addCallback(mSurfaceHolderCallback)

        mBinding.btnCamera.setOnClickListener { selectCamera((mSettings.cameraIndex + 1) % mCameraList.size) }

        mCameraHandler = mCameraList[0]

        SeekBarDirectionTracker.track(mBinding.seekBarIso) { delta, isFinal -> trackIso(delta, isFinal) }
        SeekBarDirectionTracker.track(mBinding.seekBarSpeed) { delta, isFinal -> trackSpeed(delta, isFinal) }
        SeekBarDirectionTracker.track(mBinding.seekBarExpComponsation) { delta, isFinal -> trackExpComponsation(delta, isFinal) }

        mBinding.seekBarFocus.progress = mSettings.focusManualProgress

        mBinding.seekBarFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, user: Boolean) {
                if (Settings.FOCUS_TYPE_MANUAL == mSettings.focusType) {
                    mSettings.focusManualProgress = progress
                    setupCapturePreviewRequest()
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
            }
        })

        mBinding.txtIso.setOnClickListener {
            mSettings.expIsoIsManual = !mSettings.expIsoIsManual
            updateSliders()
        }

        mBinding.txtSpeed.setOnClickListener {
            mSettings.expSpeedIsManual = !mSettings.expSpeedIsManual
            updateSliders()
        }

        mBinding.txtFocus.setOnClickListener {
            if (mCameraHandler.focusAllowManual) {
                mSettings.focusType = (mSettings.focusType + 1) % Settings.FOCUS_TYPE_MAX
                mFocusClick = false
                showFocus()
                setupCapturePreviewRequest()
            }
        }

        mBinding.surfaceView.setOnTouchListener { view, motionEvent ->
            if (mCameraHandler.focusAllowManual && Settings.FOCUS_TYPE_CLICK == mSettings.focusType) {
                if (MotionEvent.ACTION_DOWN == motionEvent.actionMasked) {
                    mFocusClickPosition.x = (100 * motionEvent.x / view.width).toInt()
                    mFocusClickPosition.y = (100 * motionEvent.y / view.height).toInt()
                    mFocusClick = true
                    setupCapturePreviewRequest()
                }
            }

            false
        }

        mBinding.btnPhoto.setOnTouchListener { view, motionEvent ->
            when(motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> takePhoto(true, PHOTO_BUTTON_SCREEN)
                MotionEvent.ACTION_UP -> takePhoto(false, PHOTO_BUTTON_SCREEN)
            }

            false
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when(keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                takePhoto(false, PHOTO_BUTTON_VOLUMNE_UP)
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                takePhoto(false, PHOTO_BUTTON_VOLUMNE_DOWN)
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when(keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                takePhoto(true, PHOTO_BUTTON_VOLUMNE_UP)
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                takePhoto(true, PHOTO_BUTTON_VOLUMNE_DOWN)
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    private fun trackIso(delta: Int, isFinal: Boolean) {
        if (!mSettings.expIsoIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var value = mSettings.expIsoValue

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

        if (isFinal) {
            mSettings.expIsoValue = value
            setupCapturePreviewRequest()
        }
    }

    private fun showIso(value: Int) {
        mBinding.txtIso.text = "${value} ISO"
    }

    private fun trackExpComponsation(delta: Int, isFinal: Boolean) {
        if (mSettings.expIsoIsManual && mSettings.expSpeedIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var value = mSettings.expCompensationValue

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
            mSettings.expCompensationValue = value
            setupCapturePreviewRequest()
        }
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

    private fun trackSpeed(delta: Int, isFinal: Boolean) {
        if (!mSettings.expSpeedIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var speedDiv = mSettings.expSpeedDivValue

        while (counter > 0) {
            if (increase) {
                if (speedDiv == 1L || getSpeedValue(speedDiv / 2) > mCameraHandler.speedRange.upper) break
                speedDiv /= 2
            } else {
                if (getSpeedValue(speedDiv * 2) < mCameraHandler.speedRange.lower) break
                speedDiv *= 2
            }
            counter -= 1
        }

        showSpeed(getSpeedValue(speedDiv))

        if (isFinal) {
            mSettings.expSpeedDivValue = speedDiv
            setupCapturePreviewRequest()
        }
    }

    private fun getSpeedStr( speed: Long ): String {
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
        mBinding.txtSpeed.text = getSpeedStr( speed )
    }

    private fun showFocus() {
        if (mCameraHandler.focusAllowManual) {
            when(mSettings.focusType) {
                Settings.FOCUS_TYPE_CLICK -> {
                    mBinding.txtFocus.text = "Focus: Click"
                    mBinding.txtFocus.visibility = View.VISIBLE
                    mBinding.seekBarFocus.visibility = View.INVISIBLE
                }

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
        mBinding.seekBarIso.visibility = if (mSettings.expIsoIsManual) View.VISIBLE else View.INVISIBLE
        mBinding.seekBarSpeed.visibility = if (mSettings.expSpeedIsManual) View.VISIBLE else View.INVISIBLE
        mBinding.txtExpComponsation.visibility = if (!mSettings.expIsoIsManual || !mSettings.expSpeedIsManual) View.VISIBLE else View.INVISIBLE
        mBinding.seekBarExpComponsation.visibility = mBinding.txtExpComponsation.visibility

        if (mSettings.expIsoIsManual)
            showIso(mSettings.expIsoValue)

        if (mSettings.expSpeedIsManual)
            showSpeed(getSpeedValue())

        showFocus()
        showExpComponsation(mSettings.expCompensationValue)
        setupCapturePreviewRequest()
    }

    private fun takePhoto(addSource: Boolean, source: Int) {
        val mask =
            if (addSource) {
                mPhotoButtonMask or source
            } else {
                mPhotoButtonMask and source.inv()
            }

        updateTakePhoto(mask)
    }

    private fun updateTakePhoto(mask: Int) {
        if (mask != mPhotoButtonMask) {
            val oldMask = mPhotoButtonMask
            mPhotoButtonMask = mask
            Log.i("TAKE_PHOTO", "Mask: " + mask.toString())

            if (0 == mask) {
                mCaptureLastPhotoResult = null
                setupCapturePreviewRequest()
            } else {
                if (0 == oldMask) {
                    mPhotoTakeMask = 0
                    setupCapturePhotoRequest()
                }

                takePhoto()
            }
        }
    }

    private fun takePhoto() {
        if (0 != mPhotoTakeMask) return
        if (0 == mPhotoButtonMask) return

        val captureRequestPhoto = mCaptureRequest ?: return
        val cameraCaptureSession = mCameraCaptureSession ?: return

        Log.i("TAKE_PHOTO", "New photo")

        mCaptureLastPhotoResult = null
        mPhotoTakeMask = PHOTO_TAKE_JPEG or PHOTO_TAKE_DNG or PHOTO_TAKE_SINGLE_SHOT
        mPhotoTimestamp = System.currentTimeMillis()
        mPhotoFileNameBase = getPhotoBaseFileName(mPhotoTimestamp)

        cameraCaptureSession.capture(
            captureRequestPhoto,
            mCameraCaptureSessionPhotoCaptureCallback,
            Handler { true }
        )
    }

    @SuppressLint("MissingPermission")
    private fun selectCamera(index: Int) {
        mSettings.cameraIndex = index
        mCameraHandler = mCameraList[index]

        closeCamera()

        mBinding.txtCamera.text = index.toString()

        val set = ConstraintSet()
        set.clone(mBinding.layoutView)
        set.setDimensionRatio(
            mBinding.layoutWithRatio.getId(),
            "${mCameraHandler.resolutionWidth}:${mCameraHandler.resolutionHeight}"
        )
        set.applyTo(mBinding.layoutView)

        mImageReaderJpeg = ImageReader.newInstance(mCameraHandler.resolutionWidth, mCameraHandler.resolutionHeight, ImageFormat.JPEG, 1)
        mImageReaderJpeg.setOnImageAvailableListener(mImageReaderJpegListener, Handler { true })

        mImageReaderDng = ImageReader.newInstance(mCameraHandler.resolutionWidth, mCameraHandler.resolutionHeight, ImageFormat.RAW_SENSOR, 1)
        mImageReaderDng.setOnImageAvailableListener(mImageReaderDngListener, Handler { true })

        updateSliders()

        mCameraManager.openCamera(mCameraHandler.id, mCameraDeviceStateCallback, Handler { true })
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

    private fun askPermissions(): Boolean {
        for (permission in PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS)
                return true
            }
        }

        return false
    }

    private fun setupCaptureInitRequest(captureRequestBuilder: CaptureRequest.Builder) {
        mCaptureModeIsPhoto = true //force preview update

        if (mCameraHandler.supportLensStabilisation)
            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)

        captureRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
   }

    private fun setupCapturePhotoRequest() {
        setupCaptureRequest(true)
    }

    private fun setupCapturePreviewRequest() {
        setupCaptureRequest(false)
    }

    private fun setupCaptureRequest(photoMode: Boolean = false) {
        val captureRequestBuilder = mCaptureRequestBuilder ?: return
        val cameraCaptureSession = mCameraCaptureSession ?: return

        if (photoMode != mCaptureModeIsPhoto) {
            mCaptureModeIsPhoto = photoMode

            if (photoMode) {
                cameraCaptureSession.stopRepeating()

                captureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                captureRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                captureRequestBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                //captureRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)

                if (mSettings.expIsoIsManual || mSettings.expSpeedIsManual) {
                    val ae = getCaptureEA()
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ae.second)
                    captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, ae.first)
                }

                //mLocationManager.
                captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 90)
                captureRequestBuilder.set(CaptureRequest.JPEG_THUMBNAIL_QUALITY, 70)
                captureRequestBuilder.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, Size(256, 256 * mCameraHandler.resolutionHeight / mCameraHandler.resolutionWidth))

                captureRequestBuilder.addTarget(mImageReaderDng.surface)
                captureRequestBuilder.addTarget(mImageReaderJpeg.surface)

                mCaptureRequest = captureRequestBuilder.build()
            } else {
                mCaptureRequest = null

                captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 70)
                captureRequestBuilder.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, null)

                captureRequestBuilder.removeTarget(mImageReaderDng.surface)
                captureRequestBuilder.removeTarget(mImageReaderJpeg.surface)

                captureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
                captureRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                captureRequestBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
                //captureRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
            }
        }

        if (photoMode) return

        if (!mSettings.expIsoIsManual || !mSettings.expSpeedIsManual) {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, mSettings.expCompensationValue * mCameraHandler.exposureCompensantionMulitplyFactor)
        } else {
            var manualSpeed = getSpeedValue()
            var manualISO = mSettings.expIsoValue

            if (manualSpeed > Settings.SPEED_MANUAL_MIN_PREVIEW) {
                while (manualSpeed > Settings.SPEED_MANUAL_MIN_PREVIEW) {
                    if ((2*manualISO) > mCameraHandler.isoRange.upper)
                        break

                    manualISO *= 2
                    manualSpeed /= 2
                }

                manualSpeed = min(Settings.SPEED_MANUAL_MIN_PREVIEW, manualSpeed)
            }

            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualSpeed)
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, manualISO)
        }

        if (mCameraHandler.focusAllowManual) {
            when(mSettings.focusType) {
                Settings.FOCUS_TYPE_HYPERFOCAL -> {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, mCameraHandler.focusHyperfocalDistance)
                    mBinding.frameView.hideFocusZone()
                }

                Settings.FOCUS_TYPE_CLICK -> {
                    if (mFocusClick) {
                        mFocusClick = false
                        val delta = mCameraHandler.resolutionWidth * FOCUS_REGION_SIZE_PERCENT / 100
                        val x = mCameraHandler.resolutionWidth * mFocusClickPosition.x / 100
                        val y = mCameraHandler.resolutionWidth * mFocusClickPosition.y / 100
                        val x1 = max(0, x - delta)
                        val y1 = max(0, y - delta)
                        val x2 = min(mCameraHandler.resolutionWidth, x + delta)
                        val y2 = min(mCameraHandler.resolutionHeight, y + delta)

                        if (y2 > y1 && x2 > x1) {
                            val rectangle = MeteringRectangle(x1, y1, x2 - x1, y2 - y1, MeteringRectangle.METERING_WEIGHT_MAX)
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
                    }
                }

                Settings.FOCUS_TYPE_MANUAL -> {
                    val distance = mCameraHandler.focusRange.lower +
                            (100 - mBinding.seekBarFocus.progress) * (mCameraHandler.focusRange.upper - mCameraHandler.focusRange.lower) / 100
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    mBinding.frameView.hideFocusZone()
                }

                else -> {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    mBinding.frameView.hideFocusZone()
                }
            }
        }

        cameraCaptureSession.setRepeatingRequest(
            captureRequestBuilder.build(),
            mCameraCaptureSessionPreviewCaptureCallback,
            Handler { true }
        )
    }
}
