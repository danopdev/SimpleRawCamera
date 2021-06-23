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
import com.dan.simplerawcamera.databinding.SettingsBinding


class SettingsDialog(private val cameraActivity: CameraActivity, private val listenerOK: ()->Unit ) : DialogFragment() {

    companion object {
        private const val DIALOG_TAG = "SETTINGS_DIALOG"

        fun show(fragmentManager: FragmentManager, cameraActivity: CameraActivity, listenerOK: () -> Unit ) {
            with( SettingsDialog( cameraActivity, listenerOK ) ) {
                isCancelable = false
                show(fragmentManager, DIALOG_TAG)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = SettingsBinding.inflate( inflater )

        binding.spinnerPhotoModes.setSelection( cameraActivity.settings.takePhotoModes )
        binding.spinnerNoiseReductionModes.setSelection( cameraActivity.settings.noiseReduction )
        binding.switchContinuousMode.isChecked = cameraActivity.settings.continuousMode
        binding.switchShowGrid.isChecked = cameraActivity.settings.showGrid
        binding.spinnerShowFraming.setSelection( cameraActivity.settings.frameType )
        binding.switchShowDebugInfo.isChecked = cameraActivity.settings.showDebugInfo
        binding.switchLocation.isChecked = cameraActivity.settings.useLocation
        binding.switchHapticFeedback.isChecked = cameraActivity.settings.enableHapticFeedback
        binding.switchEdgeEnhancement.isChecked = cameraActivity.settings.edgeEnhancement

        binding.btnSelectFolder.setOnClickListener {
            cameraActivity.startSelectFolder()
        }

        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnOK.setOnClickListener {
            cameraActivity.settings.takePhotoModes = binding.spinnerPhotoModes.selectedItemPosition
            cameraActivity.settings.noiseReduction = binding.spinnerNoiseReductionModes.selectedItemPosition
            cameraActivity.settings.continuousMode = binding.switchContinuousMode.isChecked
            cameraActivity.settings.showGrid = binding.switchShowGrid.isChecked
            cameraActivity.settings.frameType = binding.spinnerShowFraming.selectedItemPosition
            cameraActivity.settings.showDebugInfo = binding.switchShowDebugInfo.isChecked
            cameraActivity.settings.useLocation = binding.switchLocation.isChecked
            cameraActivity.settings.enableHapticFeedback = binding.switchHapticFeedback.isChecked
            cameraActivity.settings.edgeEnhancement = binding.switchEdgeEnhancement.isChecked

            cameraActivity.settings.saveProperties()

            listenerOK.invoke()
            dismiss()
        }

        binding.txtVersion.text =
            binding.txtVersion.text.toString()
                .replace("{app}", getString(R.string.app_name))
                .replace("{version}", BuildConfig.VERSION_NAME)

        return binding.root
    }
}