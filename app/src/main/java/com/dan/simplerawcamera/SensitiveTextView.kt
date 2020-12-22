package com.dan.simplerawcamera

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs
import kotlin.math.max


class SensitiveTextView : AppCompatTextView {

    companion object {
        const val DEFAULT_STEPS_X = 2
        const val DEFAULT_STEPS_Y = 2
    }

    private var mStepsX: Int = DEFAULT_STEPS_X
    private var mStepsY: Int = DEFAULT_STEPS_Y
    private var mStartX: Float = 0f
    private var mStartY: Float = 0f

    constructor(context: Context) : super(context, null) {}
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs, 0) {}
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {}

    var stepsX: Int
        get() = mStepsX
        set(value) { mStepsX = value }

    var stepsY: Int
        get() = mStepsY
        set(value) { mStepsY = value }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when(ev.action) {
            MotionEvent.ACTION_DOWN -> {
                //Log.i("SensitiveTextView", "DOWN: ${ev.x.toInt()}, ${ev.y.toInt()}")
                mStartX = ev.x
                mStartY = ev.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = (ev.x - mStartX).toInt()
                val deltaY = (ev.y - mStartY).toInt()
                //Log.i("SensitiveTextView", "UP: ${ev.x.toInt()}, ${ev.y.toInt()}, Delta: ${deltaX}, ${deltaY}")
                val absDeltaX = abs(deltaX)
                val absDeltaY = abs(deltaY)

                if (absDeltaX >= (2*absDeltaY)) { //take it as x-axis movement
                    if (mStepsX > 0) {
                        val stepSize = (max(width, height) / mStepsX)
                        val steps = deltaX / stepSize
                        if (0 != steps) {
                            Log.i("SensitiveTextView", "X-Axis: ${steps}")
                            mStartX = ev.x
                            mStartY = ev.y
                        }
                    }
                } else if (absDeltaY >= (2*absDeltaX)) { //take it as y-axis movement
                    if (mStepsY > 0) {
                        val stepSize = (max(width, height) / mStepsY)
                        var steps = deltaY / stepSize
                        if (0 != steps) {
                            Log.i("SensitiveTextView", "Y-Axis: ${steps}")
                            mStartX = ev.x
                            mStartY = ev.y
                        }
                    }
                }
            }
        }

        return super.onTouchEvent(ev)
    }
}