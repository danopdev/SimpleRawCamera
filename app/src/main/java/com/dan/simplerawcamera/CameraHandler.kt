package com.dan.simplerawcamera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range

class CameraHandler(
    val cameraManager: CameraManager,
    val id: String,
    val isoRange: Range<Int>,
    val speedRange: Range<Long>,
    val exposureCompensantionRange: Range<Int>,
    val focusRange: Range<Float>,
    val hasFlash: Boolean
) {

    companion object {
        fun getValidCameras(cameraManager: CameraManager): ArrayList<CameraHandler> {
            val validCameras = ArrayList<CameraHandler>()
            //return validCameras

            val cameraIds = cameraManager.cameraIdList

            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val keys = characteristics.keys

                //camera must be minimum level 3
                if (CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL !in keys) continue
                if ((characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) as Int) < CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) continue
                if (CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE !in keys) continue
                if (CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE !in keys) continue
                if (CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE !in keys) continue
                if (CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE !in keys) continue
                if (CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE !in keys) continue

                val isoRange =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) as Range<Int>
                val exposureCompensantionRange =
                    characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) as Range<Int>
                val speedRange =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) as Range<Long>
                val focusRange = Range(
                    characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) as Float,
                    characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) as Float
                )
                val hasFlash =
                    if (CameraCharacteristics.FLASH_INFO_AVAILABLE in keys)
                        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) as Boolean
                    else
                        false

                validCameras.add(
                    CameraHandler(
                        cameraManager,
                        cameraId,
                        isoRange,
                        speedRange,
                        exposureCompensantionRange,
                        focusRange,
                        hasFlash
                    )
                )
            }

            return validCameras
        }
    }
}