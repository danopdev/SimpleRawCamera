package com.dan.simplerawcamera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.dan.simplerawcamera.databinding.SettingsBinding


class SettingsDialog( private val settings: Settings, private val mainActivity: MainActivity, private val listenerOK: ()->Unit ) : DialogFragment() {

    companion object {
        const val DIALOG_TAG = "SETTINGS_DIALOG"

        fun show( fragmentManager: FragmentManager, mainActivity: MainActivity, settings: Settings, listenerOK: () -> Unit ) {
            with( SettingsDialog( settings, mainActivity, listenerOK ) ) {
                isCancelable = false
                show(fragmentManager, DIALOG_TAG)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = SettingsBinding.inflate( inflater )

        binding.spinnerPhotoModes.setSelection( settings.takePhotoModes )
        binding.spinnerNoiseReductionModes.setSelection( settings.noiseReduction )
        binding.switchContinuousMode.isChecked = settings.continuousMode
        binding.switchShowGrid.isChecked = settings.showGrid
        binding.spinnerShowFraming.setSelection( settings.frameType )

        binding.btnSelectFolder.setOnClickListener {
            mainActivity.startSelectFolder()
        }

        binding.bntCancel.setOnClickListener { dismiss() }

        binding.bntOK.setOnClickListener {
            settings.takePhotoModes = binding.spinnerPhotoModes.selectedItemPosition
            settings.noiseReduction = binding.spinnerNoiseReductionModes.selectedItemPosition
            settings.continuousMode = binding.switchContinuousMode.isChecked
            settings.showGrid = binding.switchShowGrid.isChecked
            settings.frameType = binding.spinnerShowFraming.selectedItemPosition

            settings.saveProperties()

            listenerOK.invoke()
            dismiss()
        }

        return binding.root
    }
}