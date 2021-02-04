package com.dan.simplerawcamera


import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.drawable.DrawableCompat
import java.util.*
import kotlin.concurrent.timer


/**
 Allow to display information over the camera preview:
  * Rule of 3rd grid
  * Ratio frame
  * Click to focus area
  * Photo counter
 */
class FrameView : View {

    companion object {
        fun dpToPx(dp: Int): Int {
            return (dp * Resources.getSystem().getDisplayMetrics().density).toInt()
        }

        const val SHOW_COUNTER_TIMEOUT = 2000L
        const val SHOW_FOCUS_TIMEOUT = 1000L
        const val SHOW_SAVE_ERROR_TIMEOUT = 10000L

        val LINE_WIDTH = dpToPx(1)

        val TEXT_SHADOW_PADDING = dpToPx(1)
        val TEXT_COLOR = Color.rgb(192, 192, 192)
        val TEXT_COLOR_SHADOW = Color.BLACK

        val PHOTO_ICON_X = dpToPx(5)
        val PHOTO_ICON_Y = PHOTO_ICON_X
        val PHOTO_ICON_WIDTH = dpToPx(60)
        val PHOTO_ICON_HEIGHT = PHOTO_ICON_WIDTH

        val DEBUG_INFO_MEM = 0
        val DEBUG_INFO_PREVIEW = 1
        val DEBUG_INFO_CAMERA_STATE = 2
        val DEBUG_INFO_MEASURED = 3
    }

    private val mPaintDark = Paint()
    private val mPaintLight = Paint()
    private val mPaintText = Paint()
    private val mPaintDebugText = Paint()

    private var mShowRatio = false
    private var mRatioWidth = 3
    private var mRatioHeight = 2

    private var mShowGrid = true

    private var mShowFocusZone = false
    private var mShowFocusZoneRect = Rect(0, 0, 0, 0)
    private val mShowFocusLight = Paint()
    private var mShowFocusTimer: Timer? = null

    private var mCounterTimer: Timer? = null
    private var mCounter = 0

    private var mShowTakePhotoIcon = false
    private var mShowSavePhotosIcon = false
    private var mShowSavePhotosErrorIcon = false
    private var mShowSavePhotosErrorIconTimer: Timer? = null
    private var mShowDebugInfo = true

    private var mSequencePhotoDelay = 0

    private var mDebugInfoHeight = 0
    private val mDebugInfo = arrayListOf<String>("", "", "", "")

    @Suppress("DEPRECATION")
    private val mSavePhotoIcon: Drawable = resources.getDrawable( android.R.drawable.ic_menu_save )
    @Suppress("DEPRECATION")
    private val mSavePhotoErrorIcon: Drawable = resources.getDrawable( android.R.drawable.stat_notify_error )
    @Suppress("DEPRECATION")
    private val mTakePhotoIcon: Drawable = resources.getDrawable( android.R.drawable.ic_menu_camera )

    constructor(context: Context) : super(context, null) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs, 0) { init() }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { init() }

    private fun init() {
        with(mPaintDark) {
            strokeWidth = LINE_WIDTH.toFloat()
            color = Color.rgb(0, 0, 0)
            style = Paint.Style.STROKE
        }

        with(mPaintLight) {
            strokeWidth = LINE_WIDTH.toFloat()
            color = Color.rgb(192, 192, 192)
            style = Paint.Style.STROKE
        }

        with(mShowFocusLight) {
            strokeWidth = LINE_WIDTH.toFloat()
            color = Color.rgb(255, 255, 0)
            style = Paint.Style.STROKE
        }

        with(mPaintText) {
            style = Paint.Style.FILL_AND_STROKE
            textSize = dpToPx(32).toFloat()
        }

        with(mPaintDebugText) {
            style = Paint.Style.FILL_AND_STROKE
            textSize = dpToPx(10).toFloat()
        }

        var textRect = Rect()
        mPaintDebugText.getTextBounds( "Xj", 0, 2, textRect )
        mDebugInfoHeight = textRect.height() + dpToPx(4)

        mTakePhotoIcon.bounds = Rect(PHOTO_ICON_X, PHOTO_ICON_X, PHOTO_ICON_X + PHOTO_ICON_WIDTH, PHOTO_ICON_Y + PHOTO_ICON_HEIGHT)
        mSavePhotoIcon.bounds = Rect(PHOTO_ICON_X + PHOTO_ICON_WIDTH, PHOTO_ICON_X, PHOTO_ICON_X + 2 * PHOTO_ICON_WIDTH, PHOTO_ICON_Y + PHOTO_ICON_HEIGHT)
        mSavePhotoErrorIcon.bounds = Rect(PHOTO_ICON_X + PHOTO_ICON_WIDTH, PHOTO_ICON_X, PHOTO_ICON_X + 2 * PHOTO_ICON_WIDTH, PHOTO_ICON_Y + PHOTO_ICON_HEIGHT)

        DrawableCompat.setTint(mSavePhotoErrorIcon, Color.RED);

        timer(null, false, 1000, 1000) {
            updateDebugMemInfo()
        }
    }

    /** Sequence: show the delay until the next sequence photo */
    fun setSequencePhotoDelay(value: Int) {
        if (value != mSequencePhotoDelay) {
            mSequencePhotoDelay = value
            invalidate()
        }
    }

    /** Show photo counter */
    fun showCounter(counter: Int) {
        if (counter != mCounter) {
            mCounterTimer?.cancel()
            mCounterTimer = null

            mCounter = counter
            invalidate()

            if (counter > 0) {
                mCounterTimer = timer(null, false, SHOW_COUNTER_TIMEOUT, SHOW_COUNTER_TIMEOUT) {
                    mCounterTimer?.cancel()
                    mCounterTimer = null
                    mCounter = 0
                    invalidate()
                }
            }
        }
    }

    /** Show debug info */
    fun showDebugInfo(show: Boolean) {
        if (mShowDebugInfo != show) {
            mShowDebugInfo = show
            invalidate()
        }
    }

    /** Set debug info */
    fun setDebugInfo( type: Int, info: String ) {
        if (type < 0 || type >= mDebugInfo.size) return

        mDebugInfo[type] = info
        if (mShowDebugInfo) invalidate()
    }

    /** Show take photo icon (use before the counter is displayed) */
    fun showTakePhotoIcon(show: Boolean) {
        if (mShowTakePhotoIcon != show) {
            mShowTakePhotoIcon = show
            invalidate()
        }
    }

    /** Show save photos icon */
    fun showSavePhotosIcon(show: Boolean) {
        if (mShowSavePhotosIcon != show) {
            mShowSavePhotosIcon = show
            invalidate()
        }
    }

    /** Show ratio frame */
    fun showRatio(show: Boolean, ratioWidth: Int = 4, ratioHeight: Int = 3) {
        if (mShowRatio != show || mRatioWidth != ratioWidth || mRatioHeight != ratioHeight) {
            mShowRatio = show
            mRatioWidth = ratioWidth
            mRatioHeight = ratioHeight
            invalidate()
        }
    }

    /** Show rule of 3rd grid */
    fun showGrid(show: Boolean) {
        if (mShowGrid != show) {
            mShowGrid = show
            invalidate()
        }
    }

    /** Show click to focus zone */
    fun showFocusZone(rect: Rect) {
        if (!mShowFocusZone || !mShowFocusZoneRect.equals(rect)) {
            hideFocusZone()

            mShowFocusZoneRect = rect
            mShowFocusZone = true
            invalidate()

            mShowFocusTimer = timer(null, false, SHOW_FOCUS_TIMEOUT, SHOW_FOCUS_TIMEOUT) {
                hideFocusZone()
            }
        }
    }

    /** Show save error */
    fun showSaveError() {
        mShowSavePhotosErrorIconTimer?.cancel()
        mShowSavePhotosErrorIconTimer = null

        if (!mShowSavePhotosErrorIcon) {
            mShowSavePhotosErrorIcon = true
            invalidate()
        }

        mShowSavePhotosErrorIconTimer = timer(null, false, SHOW_SAVE_ERROR_TIMEOUT, SHOW_SAVE_ERROR_TIMEOUT) {
            mShowSavePhotosErrorIconTimer?.cancel()
            mShowSavePhotosErrorIconTimer = null
            mShowSavePhotosErrorIcon = false
            invalidate()
        }
    }

    /** Manually hide click to focus zone */
    fun hideFocusZone() {
        if (mShowFocusZone) {
            mShowFocusTimer?.cancel()
            mShowFocusTimer = null

            mShowFocusZone = false
            invalidate()
        }
    }

    fun updateDebugMemInfo() {
        val memInfo = CameraActivity.getMemInfo()
        setDebugInfo(DEBUG_INFO_MEM, "Mem: ${memInfo.first} MB / ${memInfo.second} MB")
    }

    @SuppressLint("DrawAllocation", "UseCompatLoadingForDrawables")
    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if(null == canvas) return

        val windowWidth = width
        val windowHeight = height
        var gridWidth = windowWidth
        var gridHeight = windowHeight
        var gridX = 0
        var gridY = 0

        //dark frame
        canvas.drawRect(
            Rect(
                LINE_WIDTH / 2,
                LINE_WIDTH / 2,
                windowWidth - LINE_WIDTH / 2,
                windowHeight - LINE_WIDTH / 2),
            mPaintDark)

        //dark ratio frame
        if (mShowRatio) {
            gridWidth = windowWidth
            gridHeight = gridWidth * mRatioHeight / mRatioWidth

            if (gridWidth > windowWidth || gridHeight > windowHeight) {
                gridHeight = windowHeight
                gridWidth = gridHeight * mRatioWidth / mRatioHeight
            }

            gridX = (windowWidth - gridWidth) / 2
            gridY = (windowHeight - gridHeight) / 2

            canvas.drawRect(
                Rect(
                    gridX + LINE_WIDTH / 2,
                    gridY + LINE_WIDTH / 2,
                    gridX + gridWidth - LINE_WIDTH / 2,
                    gridY + gridHeight - LINE_WIDTH / 2),
                mPaintDark)
        }

        //dark grid
        if (mShowGrid) {
            canvas.drawRect(
                Rect(
                    gridX + gridWidth / 3  + LINE_WIDTH / 2,
                    gridY + LINE_WIDTH / 2,
                    gridX + 2 * gridWidth / 3 - LINE_WIDTH / 2,
                    gridY + gridHeight - LINE_WIDTH / 2),
                mPaintDark)

            canvas.drawRect(
                Rect(
                    gridX + LINE_WIDTH / 2,
                    gridY + gridHeight / 3 + LINE_WIDTH / 2,
                    gridX + gridWidth - LINE_WIDTH / 2,
                    gridY + 2 * gridHeight / 3 - LINE_WIDTH / 2),
                mPaintDark)
        }

        //light frame
        canvas.drawRect(
            Rect(
                LINE_WIDTH + LINE_WIDTH / 2,
                LINE_WIDTH + LINE_WIDTH / 2,
                windowWidth - LINE_WIDTH - LINE_WIDTH / 2,
                windowHeight - LINE_WIDTH - LINE_WIDTH / 2),
            mPaintLight)

        //light ratio frame
        if (mShowRatio) {
            canvas.drawRect(
                Rect(
                    gridX + LINE_WIDTH + LINE_WIDTH / 2,
                    gridY + LINE_WIDTH + LINE_WIDTH / 2,
                    gridX + gridWidth - LINE_WIDTH - LINE_WIDTH / 2,
                    gridY + gridHeight - LINE_WIDTH - LINE_WIDTH / 2),
                mPaintLight)
        }

        //light grid
        if (mShowGrid) {
            canvas.drawRect(
                Rect(
                    gridX + gridWidth / 3 + LINE_WIDTH + LINE_WIDTH / 2,
                    gridY + LINE_WIDTH + LINE_WIDTH / 2,
                    gridX + 2 * gridWidth / 3 - LINE_WIDTH - LINE_WIDTH / 2,
                    gridY + gridHeight - LINE_WIDTH - LINE_WIDTH / 2),
                mPaintLight)

            canvas.drawRect(
                Rect(
                    gridX + LINE_WIDTH + LINE_WIDTH / 2,
                    gridY + gridHeight / 3 + LINE_WIDTH + LINE_WIDTH / 2,
                    gridX + gridWidth - LINE_WIDTH - LINE_WIDTH / 2,
                    gridY + 2 * gridHeight / 3 - LINE_WIDTH - LINE_WIDTH / 2),
                mPaintLight)
        }

        if (mShowFocusZone) {
            val x1 = mShowFocusZoneRect.left * windowWidth / 100
            val y1 = mShowFocusZoneRect.top * windowHeight / 100
            val x2 = mShowFocusZoneRect.right * windowWidth / 100
            val y2 = mShowFocusZoneRect.bottom * windowHeight / 100
            canvas.drawRect(Rect(x1 - LINE_WIDTH / 2, y1 - LINE_WIDTH / 2, x2 + LINE_WIDTH / 2, y2 + LINE_WIDTH / 2), mPaintDark)
            canvas.drawRect(Rect(x1 + LINE_WIDTH / 2, y1 + LINE_WIDTH / 2, x2 - LINE_WIDTH / 2, y2 - LINE_WIDTH / 2), mShowFocusLight)
        }

        if (mShowSavePhotosIcon) {
            mSavePhotoIcon.draw(canvas)
        }

        if (mShowSavePhotosErrorIcon) {
            mSavePhotoErrorIcon.draw(canvas)
        }

        if (mShowTakePhotoIcon) {
            mTakePhotoIcon.draw(canvas)
        }

        if (mCounter > 0) {
            val str = mCounter.toString()
            var textRect = Rect()
            mPaintText.getTextBounds( str, 0, str.length, textRect )
            val textX = (PHOTO_ICON_X + (PHOTO_ICON_WIDTH - textRect.width()) / 2).toFloat()
            val textY = (PHOTO_ICON_Y + (PHOTO_ICON_HEIGHT + textRect.height()) / 2).toFloat()

            mPaintText.color = TEXT_COLOR_SHADOW
            canvas.drawText( str, textX + TEXT_SHADOW_PADDING, textY + TEXT_SHADOW_PADDING, mPaintText )
            mPaintText.color = TEXT_COLOR
            canvas.drawText( str, textX, textY, mPaintText )
        }

        if (mSequencePhotoDelay > 0) {
            val str = "(${mSequencePhotoDelay})"
            var textRect = Rect()
            mPaintText.getTextBounds( str, 0, str.length, textRect )
            val textX = (width - PHOTO_ICON_X - textRect.width()).toFloat()
            val textY = (PHOTO_ICON_Y + (PHOTO_ICON_HEIGHT + textRect.height()) / 2).toFloat()

            mPaintText.color = TEXT_COLOR_SHADOW
            canvas.drawText( str, textX + TEXT_SHADOW_PADDING, textY + TEXT_SHADOW_PADDING, mPaintText )
            mPaintText.color = TEXT_COLOR
            canvas.drawText( str, textX, textY, mPaintText )
        }

        if (mShowDebugInfo) {
            var textY = height - PHOTO_ICON_Y
            for ( debugInfoLine in mDebugInfo ) {
                mPaintDebugText.color = TEXT_COLOR_SHADOW
                canvas.drawText( debugInfoLine, (PHOTO_ICON_X + TEXT_SHADOW_PADDING).toFloat(), (textY  + TEXT_SHADOW_PADDING).toFloat(), mPaintDebugText)
                mPaintDebugText.color = TEXT_COLOR
                canvas.drawText( debugInfoLine, PHOTO_ICON_X.toFloat(), textY.toFloat(), mPaintDebugText)
                textY -= mDebugInfoHeight
            }
        }
    }
}