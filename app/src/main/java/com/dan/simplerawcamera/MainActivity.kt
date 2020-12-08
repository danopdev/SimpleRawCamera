package com.dan.simplerawcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Range
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dan.simplerawcamera.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
        )

        const val REQUEST_PERMISSIONS = 1
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!askPermissions())
            onPermissionsAllowed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    private fun showSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onRequestPermissionsResult( requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> handleRequestPermissions( requestCode, permissions, grantResults )
        }
    }

    private fun handleRequestPermissions( requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        var allowedAll = grantResults.size >= PERMISSIONS.size

        if (grantResults.size >= PERMISSIONS.size) {
            for ( result in grantResults ) {
                if (result != PackageManager.PERMISSION_GRANTED ) {
                    allowedAll = false
                    break
                }
            }
        }

        if( allowedAll ) {
            onPermissionsAllowed()
        } else {
            setResult(0);
            finish();
        }
    }

    private fun onPermissionsAllowed() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        binding = ActivityMainBinding.inflate(layoutInflater)

        var str = ""
        val cameras = cameraManager.cameraIdList
        for (camera in cameras) {
            str += camera + "\n"

            val characteristics = cameraManager.getCameraCharacteristics(camera)
            val keys = characteristics.keys

            if (CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES in keys) {
                str += "AE: "
                val values = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) as IntArray
                for (i in values)
                    str += i.toString() + " "
                str += "\n"
            }

            if (CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE in keys) {
                val values = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) as Range<Int>
                str += "AE Range: ${values.lower} - ${values.upper}\n"
            }

            if (CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES in keys) {
                str += "AF: "
                val values = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) as IntArray
                for (i in values)
                    str += i.toString() + " "
                str += "\n"
            }

            if (CameraCharacteristics.FLASH_INFO_AVAILABLE in keys) {
                val value = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) as Boolean
                str += "Flash: ${value}\n"
            }

            if (CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL in keys) {
                val value = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) as Int
                str += "Support HW: ${value}\n"
            }

            if (CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE in keys) {
                val values = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) as Range<Int>
                str += "ISO: ${values.lower} - ${values.upper}\n"
            }

            if (CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE in keys) {
                val values = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) as Range<Long>
                str += "Speed: ${values.lower} - ${values.upper}\n"
            }

            if (CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE in keys) {
                val value = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) as Rect
                val w = value.width()
                val h = value.height()
                str += "Resolution: ${w} x ${h}\n"
            }

            if (CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES in keys) {
                val values = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) as IntArray
                str += "Face detections: "
                for (i in values)
                    str += i.toString() + " "
                str += "\n"
            }

            str += "\n"
        }

        //binding.txt.text = str

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        //setSystemUiVisibility()

        setContentView(binding.root)
    }

    private fun askPermissions(): Boolean {
        for (permission in PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS)
                return true
            }
        }

        return false
    }
}