package com.dan.simplerawcamera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.dan.simplerawcamera.databinding.SettingsBinding


class SettingsDialog(private val cameraActivity: CameraActivity, private val listenerOK: ()->Unit ) : DialogFragment() {

    companion object {
        const val DIALOG_TAG = "SETTINGS_DIALOG"

        fun show(fragmentManager: FragmentManager, cameraActivity: CameraActivity, listenerOK: () -> Unit ) {
            with( SettingsDialog( cameraActivity, listenerOK ) ) {
                isCancelable = false
                show(fragmentManager, DIALOG_TAG)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = SettingsBinding.inflate( inflater )

        binding.spinnerPhotoModes.setSelection( cameraActivity.settings.takePhotoModes )
        binding.spinnerNoiseReductionModes.setSelection( cameraActivity.settings.noiseReduction )
        binding.switchContinuousMode.isChecked = cameraActivity.settings.continuousMode
        binding.switchShowGrid.isChecked = cameraActivity.settings.showGrid
        binding.spinnerShowFraming.setSelection( cameraActivity.settings.frameType )

        binding.btnSelectFolder.setOnClickListener {
            cameraActivity.startSelectFolder()
        }

        binding.bntCancel.setOnClickListener { dismiss() }

        binding.bntOK.setOnClickListener {
            cameraActivity.settings.takePhotoModes = binding.spinnerPhotoModes.selectedItemPosition
            cameraActivity.settings.noiseReduction = binding.spinnerNoiseReductionModes.selectedItemPosition
            cameraActivity.settings.continuousMode = binding.switchContinuousMode.isChecked
            cameraActivity.settings.showGrid = binding.switchShowGrid.isChecked
            cameraActivity.settings.frameType = binding.spinnerShowFraming.selectedItemPosition

            cameraActivity.settings.saveProperties()

            listenerOK.invoke()
            dismiss()
        }

        return binding.root
    }
}