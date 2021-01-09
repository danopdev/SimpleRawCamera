package com.dan.simplerawcamera

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.dan.simplerawcamera.databinding.SequencesBinding
import java.util.*
import kotlin.concurrent.timer


class SequencesDialog( private val cameraActivity: CameraActivity ) : DialogFragment() {

    companion object {
        const val DIALOG_TAG = "SEQUENCES_DIALOG"

        fun show(fragmentManager: FragmentManager, cameraActivity: CameraActivity ) {
            with( SequencesDialog( cameraActivity ) ) {
                isCancelable = false
                show(fragmentManager, DIALOG_TAG)
            }
        }
    }

    private var mTimer: Timer? = null
    private var mDelayBetween = 1000
    private var mNumberOfPhotos = -1
    private var mPhotoCounter = 0
    private var mIsBusy = false
    private lateinit var mBinding: SequencesBinding

    private fun updateView() {
        mBinding.btnStop.text = if (mIsBusy) "Stop" else "Exit"
        mBinding.btnStart.isEnabled = !mIsBusy
        mBinding.spinnerDelayStart.isEnabled = !mIsBusy
        mBinding.spinnerDelayBetween.isEnabled = !mIsBusy
        mBinding.spinnerNumberOfPhotos.isEnabled = !mIsBusy
    }

    private fun takeNextPhotoAfterDelay(delay: Int) {
        if (delay <= 0) {
            cameraActivity.runOnUiThread {
                if (mIsBusy) takeNextPhoto()
            }
        } else {
            val msDelay = delay * 1000L
            mTimer = timer(null, false, msDelay, msDelay) {
                mTimer?.cancel()
                mTimer = null
                cameraActivity.runOnUiThread {
                    if (mIsBusy) takeNextPhoto()
                }
            }
        }
    }

    private fun takeNextPhoto() {
        cameraActivity.takePhotoWithCallback {
            mPhotoCounter++

            if (mIsBusy) {
                if (mNumberOfPhotos > 0 && mPhotoCounter >= mNumberOfPhotos) {
                    mIsBusy = false
                    updateView()
                } else {
                    takeNextPhotoAfterDelay(mDelayBetween)
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        mBinding = SequencesBinding.inflate( inflater )

        mBinding.spinnerDelayStart.setSelection( cameraActivity.settings.sequenceDelayStart )
        mBinding.spinnerDelayBetween.setSelection( cameraActivity.settings.sequenceDelayBetween )
        mBinding.spinnerNumberOfPhotos.setSelection( cameraActivity.settings.sequenceNumberOfPhotos )

        updateView()

        mBinding.btnStart.setOnClickListener {
            cameraActivity.settings.sequenceDelayStart = mBinding.spinnerDelayStart.selectedItemPosition
            cameraActivity.settings.sequenceDelayBetween = mBinding.spinnerDelayBetween.selectedItemPosition
            cameraActivity.settings.sequenceNumberOfPhotos = mBinding.spinnerNumberOfPhotos.selectedItemPosition

            mIsBusy = true
            updateView()

            mDelayBetween = (mBinding.spinnerDelayBetween.selectedItem as String).toInt()
            mPhotoCounter = 0

            mNumberOfPhotos = -1
            try {
                mNumberOfPhotos = (mBinding.spinnerNumberOfPhotos.selectedItem as String).toInt()
            } catch (e: Exception) {
            }

            val a = mBinding.spinnerDelayStart.selectedItem
            val delayStart = (mBinding.spinnerDelayStart.selectedItem as String).toInt()
            takeNextPhotoAfterDelay( delayStart )
        }

        mBinding.btnStop.setOnClickListener {
            if (mIsBusy) {
                mTimer?.cancel()
                mTimer = null
                mIsBusy = false
                updateView()
            } else {
                dismiss()
            }
        }

        return mBinding.root
    }
}