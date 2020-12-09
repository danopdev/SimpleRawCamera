package com.dan.simplerawcamera

import android.widget.SeekBar
import java.util.*

class SeekBarDirectionTracker( val l: (Int, Boolean)->Unit ) : SeekBar.OnSeekBarChangeListener {

    companion object {
        fun track( seekBar: SeekBar, l: (Int, Boolean)->Unit ) {
            seekBar.setOnSeekBarChangeListener( SeekBarDirectionTracker(l) )
        }
    }

    private var tracking = false
    private var firstValue = true
    private var value = 0
    private var startTime = 0L
    private var delta = 0

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (null == seekBar) return
        if (!fromUser) return
        if (!tracking) return

        if (firstValue) {
            firstValue = false
            var deltaTime = Date().time - startTime
            if (deltaTime < 100) {
                value = seekBar.progress
                return
            }
        }

        delta = progress - value
        l.invoke(delta, false)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {
        if (null != seekBar) {
            startTime = Date().time
            tracking = true
            value = seekBar.progress
            firstValue = true
            delta = 0
        }
    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        tracking = false
        if (null != seekBar)
            seekBar.progress = seekBar.max / 2
        l.invoke(delta, true)
    }
}