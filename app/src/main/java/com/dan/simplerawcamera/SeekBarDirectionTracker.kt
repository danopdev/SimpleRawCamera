package com.dan.simplerawcamera

import android.widget.SeekBar
import java.util.*

class SeekBarDirectionTracker( private val mListener: (Int, Boolean)->Unit ) : SeekBar.OnSeekBarChangeListener {

    companion object {
        fun track( seekBar: SeekBar, l: (Int, Boolean)->Unit ) {
            seekBar.setOnSeekBarChangeListener( SeekBarDirectionTracker(l) )
        }
    }

    private var mTracking = false
    private var mFirstValue = true
    private var mValue = 0
    private var mStartTime = 0L
    private var mDelta = 0

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (null == seekBar) return
        if (!fromUser) return
        if (!mTracking) return

        if (mFirstValue) {
            mFirstValue = false
            var deltaTime = Date().time - mStartTime
            if (deltaTime < 100) {
                mValue = seekBar.progress
                return
            }
        }

        mDelta = progress - mValue
        mListener.invoke(mDelta, false)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {
        if (null != seekBar) {
            mStartTime = Date().time
            mTracking = true
            mValue = seekBar.progress
            mFirstValue = true
            mDelta = 0
        }
    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        mTracking = false
        if (null != seekBar)
            seekBar.progress = seekBar.max / 2
        mListener.invoke(mDelta, true)
    }
}