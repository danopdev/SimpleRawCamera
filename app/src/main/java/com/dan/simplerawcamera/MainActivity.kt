package com.dan.simplerawcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Camera
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Range
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.dan.simplerawcamera.databinding.ActivityMainBinding
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {
    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
        )

        const val REQUEST_PERMISSIONS = 1
    }

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val cameraManager: CameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val cameraList: ArrayList<CameraHandler> by lazy { CameraHandler.getValidCameras(cameraManager) }
    private lateinit var camera: CameraHandler

    private var isoValue = 100
    private var isoAuto = false

    private var speedValue = 1/125f
    private var speedAuto = false

    private var exposureCompensationValue = 0

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

    private fun exitApp() {
        setResult(0)
        finish()
        exitProcess(0)
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
            exitApp()
        }
    }

    private fun onPermissionsAllowed() {
        if (cameraList.size <= 0) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage("No valid camara found !")
                .setIcon(android.R.drawable.stat_notify_error)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> exitApp() }
                .show()

            return
        }

        if (1 == cameraList.size) {
            binding.txtCamera.isVisible = false
            binding.btnCamera.isVisible = false
        }

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)

        setContentView(binding.root)

        selectCamera(0)
    }

    private fun selectCamera(index: Int) {
        camera = cameraList[index]

        binding.txtCamera.text = index.toString()
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