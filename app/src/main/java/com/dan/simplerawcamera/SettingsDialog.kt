package com.dan.simplerawcamera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.dan.simplerawcamera.databinding.SettingsBinding


class SettingsDialog( private val mainActivity: MainActivity, private val listenerOK: ()->Unit ) : DialogFragment() {

    companion object {
        const val DIALOG_TAG = "SETTINGS_DIALOG"

        fun show( fragmentManager: FragmentManager, mainActivity: MainActivity, listenerOK: () -> Unit ) {
            with( SettingsDialog( mainActivity, listenerOK ) ) {
                isCancelable = false
                show(fragmentManager, DIALOG_TAG)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = SettingsBinding.inflate( inflater )

        binding.spinnerPhotoModes.setSelection( mainActivity.settings.takePhotoModes )
        binding.spinnerNoiseReductionModes.setSelection( mainActivity.settings.noiseReduction )
        binding.switchContinuousMode.isChecked = mainActivity.settings.continuousMode
        binding.switchShowGrid.isChecked = mainActivity.settings.showGrid
        binding.spinnerShowFraming.setSelection( mainActivity.settings.frameType )

        binding.btnSelectFolder.setOnClickListener {
            mainActivity.startSelectFolder()
        }

        binding.bntCancel.setOnClickListener { dismiss() }

        binding.bntOK.setOnClickListener {
            mainActivity.settings.takePhotoModes = binding.spinnerPhotoModes.selectedItemPosition
            mainActivity.settings.noiseReduction = binding.spinnerNoiseReductionModes.selectedItemPosition
            mainActivity.settings.continuousMode = binding.switchContinuousMode.isChecked
            mainActivity.settings.showGrid = binding.switchShowGrid.isChecked
            mainActivity.settings.frameType = binding.spinnerShowFraming.selectedItemPosition

            mainActivity.settings.saveProperties()

            listenerOK.invoke()
            dismiss()
        }

        return binding.root
    }
}