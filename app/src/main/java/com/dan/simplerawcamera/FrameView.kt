package com.dan.simplerawcamera


import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View


class FrameView : View {

    private val mPaintDark = Paint()
    private val mPaintLight = Paint()
    private var mShowRatio = false
    private var mRatioWidth = 3
    private var mRatioHeight = 2
    private var mShowGrid = true

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

    }
}