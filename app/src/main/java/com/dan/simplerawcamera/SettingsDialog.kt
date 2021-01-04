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

    private lateinit var mBinding: SettingsBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        mBinding = SettingsBinding.inflate( inflater )

        mBinding.spinnerPhotoModes.setSelection( settings.takePhotoModes )
        mBinding.switchContinuousMode.isChecked = settings.continuousMode
        mBinding.switchShowGrid.isChecked = settings.showGrid
        mBinding.spinnerShowFraming.setSelection( settings.frameType )

        mBinding.btnSelectFolder.setOnClickListener {
            mainActivity.startSelectFolder()
        }

        mBinding.bntCancel.setOnClickListener { dismiss() }

        mBinding.bntOK.setOnClickListener {
            settings.takePhotoModes = mBinding.spinnerPhotoModes.selectedItemPosition
            settings.continuousMode = mBinding.switchContinuousMode.isChecked
            settings.showGrid = mBinding.switchShowGrid.isChecked
            settings.frameType = mBinding.spinnerShowFraming.selectedItemPosition

            settings.saveProperties()

            listenerOK.invoke()
            dismiss()
        }

        return mBinding.root
    }
}