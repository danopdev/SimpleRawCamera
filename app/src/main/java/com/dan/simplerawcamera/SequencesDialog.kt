package com.dan.simplerawcamera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.dan.simplerawcamera.databinding.SequencesBinding


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

    private fun updateView(binding: SequencesBinding, isBusy: Boolean) {
        binding.btnStop.text = if (isBusy) "Stop" else "Exit"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = SequencesBinding.inflate( inflater )
        var isBusy = false

        binding.spinnerDelayStart.setSelection( cameraActivity.settings.sequenceDelayStart )
        binding.spinnerDelayBetween.setSelection( cameraActivity.settings.sequenceDelayBetween )
        binding.spinnerNumberOfPhotos.setSelection( cameraActivity.settings.sequenceNumberOfPhotos )

        updateView(binding, isBusy)

        binding.btnStart.setOnClickListener {
            cameraActivity.settings.sequenceDelayStart = binding.spinnerDelayStart.selectedItemPosition
            cameraActivity.settings.sequenceDelayBetween = binding.spinnerDelayBetween.selectedItemPosition
            cameraActivity.settings.sequenceNumberOfPhotos = binding.spinnerNumberOfPhotos.selectedItemPosition

            isBusy = true
            updateView(binding, isBusy)
        }

        binding.btnStop.setOnClickListener {
            if (isBusy) {
                isBusy = false
                updateView(binding, isBusy)
            } else {
                dismiss()
            }
        }

        return binding.root
    }
}