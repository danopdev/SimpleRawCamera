package com.dan.simplerawcamera


import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.util.*
import kotlin.concurrent.timer


class FrameView : View {

    companion object {
        const val SHOW_FOCUS_TIMEOUT = 1000L
    }

    private val mPaintDark = Paint()
    private val mPaintLight = Paint()

    private var mShowRatio = false
    private var mRatioWidth = 3
    private var mRatioHeight = 2

    private var mShowGrid = true

    private var mShowFocusZone = false
    private var mShowFocusZoneRect = Rect(0,0,0,0)
    private val mShowFocusLight = Paint()
    private var mShowFocusTimer: Timer? = null

    private var mShowExpZone = false
    private var mShowExpZoneRect = Rect(0,0,0,0)
    private val mShowExpLight = Paint()

    constructor(context: Context) : super(context, null) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs, 0) { init() }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { init() }

    private fun init() {
        with(mPaintDark) {
            strokeWidth = 1f
            color = Color.rgb(0, 0, 0)
            style = Paint.Style.STROKE
        }

        with(mPaintLight) {
            strokeWidth = 1f
            color = Color.rgb(192, 192, 192)
            style = Paint.Style.STROKE
        }

        with(mShowFocusLight) {
            strokeWidth = 1f
            color = Color.rgb(255, 255, 0)
            style = Paint.Style.STROKE
        }

        with(mShowExpLight) {
            strokeWidth = 1f
            color = Color.rgb(128, 255, 255)
            style = Paint.Style.STROKE
        }
    }

    fun showRatio(show: Boolean, ratioWidth: Int = 4, ratioHeight: Int = 3) {
        mShowRatio = show
        mRatioWidth = ratioWidth
        mRatioHeight = ratioHeight
        invalidate()
    }

    fun showGrid(show: Boolean) {
        mShowGrid = show
        invalidate()
    }

    fun showFocusZone( rect: Rect ) {
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

    fun hideFocusZone() {
        if (mShowFocusZone) {
            mShowFocusTimer?.cancel()
            mShowFocusTimer = null

            mShowFocusZone = false
            invalidate()
        }
    }

    fun showExpZone( rect: Rect ) {
        if (!mShowExpZone || !mShowExpZoneRect.equals(rect)) {
            mShowExpZoneRect = rect
            mShowExpZone = true
            invalidate()
        }
    }

    fun hideExpZone() {
        if (mShowExpZone) {
            mShowExpZone = false
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if(null == canvas) return

        val windowWidth = width
        val windowHeight = height
        var gridWidth = windowWidth
        var gridHeight = windowHeight
        var gridX = 0
        var gridY = 0

        canvas.drawRect( Rect(1, 1, windowWidth-1, windowHeight-1), mPaintLight )

        if (mShowRatio) {
            gridHeight = windowHeight * mRatioHeight / mRatioWidth
            if (gridHeight > windowHeight) {
                gridHeight = windowHeight
                gridWidth = windowWidth * mRatioWidth / mRatioHeight
            } else {
                gridWidth = windowWidth
            }

            gridX = (windowWidth - gridWidth) / 2
            gridY = (windowHeight - gridHeight) / 2

            canvas.drawRect( Rect(gridX + 1, gridY + 1, gridX + gridWidth - 1, gridY + gridHeight - 1), mPaintLight )
        }

        if (mShowGrid) {
            canvas.drawRect( Rect(gridX + 1 + gridWidth / 3, gridY + 1, gridX + 2 * gridWidth / 3 - 1, gridY + gridHeight - 1), mPaintLight )
            canvas.drawRect( Rect(gridX + 1, gridY + 1 + gridHeight / 3, gridX + gridWidth - 1, gridY + 1 + 2 * gridHeight / 3), mPaintLight )
        }

        canvas.drawRect( Rect(0, 0, windowWidth, windowHeight), mPaintDark )

        if (mShowRatio) {
            canvas.drawRect( Rect(gridX, gridY, gridX + gridWidth, gridY + gridHeight), mPaintDark )
        }

        if (mShowGrid) {
            canvas.drawRect( Rect(gridX + gridWidth / 3, gridY, gridX + 2 * gridWidth / 3, gridY + gridHeight), mPaintDark )
            canvas.drawRect( Rect(gridX, gridY + gridHeight / 3, gridX + gridWidth, gridY + 2 * gridHeight / 3), mPaintDark )
        }

        if (mShowFocusZone) {
            val x1 = mShowFocusZoneRect.left * windowWidth / 100
            val y1 = mShowFocusZoneRect.top * windowHeight / 100
            val x2 = mShowFocusZoneRect.right * windowWidth / 100
            val y2 = mShowFocusZoneRect.bottom * windowHeight / 100
            canvas.drawRect( Rect(x1, y1, x2, y2), mPaintDark )
            canvas.drawRect( Rect(x1+1, y1+1, x2-1, y2-1), mShowFocusLight )
        }

        if (mShowExpZone) {
            val x1 = mShowExpZoneRect.left * windowWidth / 100
            val y1 = mShowExpZoneRect.top * windowHeight / 100
            val x2 = mShowExpZoneRect.right * windowWidth / 100
            val y2 = mShowExpZoneRect.bottom * windowHeight / 100
            canvas.drawRect( Rect(x1, y1, x2, y2), mPaintDark )
            canvas.drawRect( Rect(x1+1, y1+1, x2-1, y2-1), mShowExpLight )
        }
    }
}