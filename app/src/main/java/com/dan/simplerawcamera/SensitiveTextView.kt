package com.dan.simplerawcamera

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs


class SensitiveTextView : AppCompatTextView {

    companion object {
        private fun dpToPx(dp: Int): Int {
            return (dp * Resources.getSystem().displayMetrics.density).toInt()
        }

        val PADDING = dpToPx(1).toFloat()

        val BG_COLOR_NORMAL = Color.argb(255, 0, 0, 0 )
        val BG_COLOR_PRESSED = Color.rgb(48, 48, 48 )

        val STEP_X = dpToPx(30)
        val STEP_Y = dpToPx(30)

        const val DIRECTION_NONE = 0
        const val DIRECTION_NOT_DEFINED = 1
        const val DIRECTION_X_AXIS = 2
        const val DIRECTION_Y_AXIS = 3
    }

    private var mStartX: Float = 0f
    private var mStartY: Float = 0f
    private var mDirection: Int = DIRECTION_NONE
    private var mOnMoveXAxis: ((Int)->Unit)? = null
    private var mOnMoveYAxis: ((Int)->Unit)? = null

    constructor(context: Context) : super(context, null) {}
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs, 0) {}
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {}

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)

        if (null == canvas) return

        var charLeft = "o"
        var charRight = "o"
        var charRightIsRotated = false
        var charOffset = 0

        when(mDirection) {
            DIRECTION_NOT_DEFINED -> {
                charLeft = "+"
                charRight = "+"
            }

            DIRECTION_X_AXIS -> {
                charLeft = "<"
                charRight = ">"
            }

            DIRECTION_Y_AXIS -> {
                charLeft = "^"
                charRight = "^"
                charRightIsRotated = true
                charOffset = dpToPx(3)
            }
        }

        val paint = this.paint

        val textRect = Rect()
        paint.getTextBounds( charLeft, 0, 1, textRect )

        canvas.drawText( charLeft, PADDING, (height + textRect.height()) / 2f + charOffset, paint )

        if (charRightIsRotated) {
            canvas.rotate(180f, width - (PADDING + textRect.width()) / 2, height / 2f)
        }
        canvas.drawText( charRight, width - PADDING - textRect.width(), (height + textRect.height()) / 2f + charOffset, paint )
   }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when(ev.action) {
            MotionEvent.ACTION_DOWN -> {
                handleActionDown(ev)
                return true
            }

            MotionEvent.ACTION_MOVE -> handleActionMove(ev)
            MotionEvent.ACTION_UP -> handleActionUp()
        }

        return super.onTouchEvent(ev)
    }

    private fun handleActionMove(ev: MotionEvent) {
        val deltaX = (ev.x - mStartX).toInt()
        val deltaY = (ev.y - mStartY).toInt()
        val absDeltaX = abs(deltaX)
        val absDeltaY = abs(deltaY)

        if (DIRECTION_NOT_DEFINED == mDirection || DIRECTION_NONE == mDirection) {
            if (absDeltaX >= STEP_X && null != mOnMoveXAxis) {
                mDirection = DIRECTION_X_AXIS
                invalidate()
            } else if (absDeltaY >= STEP_Y && null != mOnMoveYAxis) {
                mDirection = DIRECTION_Y_AXIS
                invalidate()
            } else {
                return
            }
        }

        when(mDirection) {
            DIRECTION_X_AXIS -> {
                if (absDeltaX >= STEP_X) {
                    val steps = deltaX / STEP_X
                    mStartX = ev.x
                    mStartY = ev.y
                    mOnMoveXAxis?.invoke(steps)
                }
            }

            DIRECTION_Y_AXIS -> {
                if (absDeltaY >= STEP_Y) {
                    val steps = deltaY / STEP_Y
                    mStartX = ev.x
                    mStartY = ev.y
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
        invalidate()
    }

    private fun handleActionUp() {
        mDirection = DIRECTION_NONE
        setBackgroundColor(BG_COLOR_NORMAL)
        invalidate()
    }

    fun setOnMoveXAxisListener( l: (Int)->Unit ) {
        mOnMoveXAxis = l
    }

    fun setOnMoveYAxisListener( l: (Int)->Unit ) {
        mOnMoveYAxis = l
    }
}