package com.dan.simplerawcamera

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class SensitiveTextView : AppCompatTextView {

    companion object {
        private fun dpToPx(dp: Int): Int {
            return (dp * Resources.getSystem().displayMetrics.density).toInt()
        }

        val BG_COLOR_NORMAL = Color.argb(255, 0, 0, 0 )
        val BG_COLOR_PRESSED = Color.rgb(48, 48, 48 )

        val STEP_X = dpToPx(30)
        val STEP_Y = dpToPx(20)

        const val DIRECTION_NOT_DEFINED = 0
        const val DIRECTION_X_AXIS = 1
        const val DIRECTION_Y_AXIS = 2
    }

    private var mStartX: Float = 0f
    private var mStartY: Float = 0f
    private var mDirection: Int = DIRECTION_NOT_DEFINED
    private var mOnMoveXAxis: ((Int)->Unit)? = null
    private var mOnMoveYAxis: ((Int)->Unit)? = null

    constructor(context: Context) : super(context, null) {}
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs, 0) {}
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {}

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)

        if (null != canvas) {
            val size = min(width, height)
            val drawableUp = resources.getDrawable(android.R.drawable.arrow_up_float, null)
            drawableUp.setBounds(0,0,size,size)
            drawableUp.draw(canvas)
            drawableUp.setBounds(width-size,0,width,size)
            drawableUp.draw(canvas)
        }
   }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when(ev.action) {
            MotionEvent.ACTION_DOWN -> {
                handleActionDown(ev)
                return true
            }

            MotionEvent.ACTION_MOVE -> handleActionMove(ev)
            MotionEvent.ACTION_UP -> handleActionUp(ev)
        }

        return super.onTouchEvent(ev)
    }

    private fun handleActionUp(ev: MotionEvent) {
        val deltaX = (ev.x - mStartX).toInt()
        val deltaY = (ev.y - mStartY).toInt()
        val absDeltaX = abs(deltaX)
        val absDeltaY = abs(deltaY)

        if (DIRECTION_NOT_DEFINED == mDirection) {
            if (absDeltaX < STEP_X && absDeltaY < STEP_Y) return
            mDirection = if (absDeltaX >= STEP_X) DIRECTION_X_AXIS else DIRECTION_Y_AXIS
        }

        when(mDirection) {
            DIRECTION_X_AXIS -> {
                if (absDeltaX >= STEP_X) {
                    val steps = deltaX / STEP_X
                    mStartX = ev.x + steps * STEP_X
                    mStartY = ev.y
                    mOnMoveXAxis?.invoke(steps)
                }
            }

            DIRECTION_Y_AXIS -> {
                if (absDeltaY >= STEP_Y) {
                    val steps = deltaY / STEP_Y
                    mStartX = ev.x
                    mStartY = ev.y + steps * STEP_Y
                    mOnMoveYAxis?.invoke(steps)
                }
            }
        }
    }

    private fun handleActionDown(ev: MotionEvent) {
        mStartX = ev.x
        mStartY = ev.y
        mDirection = DIRECTION_NOT_DEFINED

        setBackgroundColor(BG_COLOR_PRESSED)
    }

    private fun handleActionMove(ev: MotionEvent) {
        setBackgroundColor(BG_COLOR_NORMAL)
    }

    fun setOnMoveXAxisListener( l: (Int)->Unit ) {
        mOnMoveXAxis = l
    }

    fun setOnMoveYAxisListener( l: (Int)->Unit ) {
        mOnMoveYAxis = l
    }
}