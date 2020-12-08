package com.dan.simplerawcamera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.RecommendedStreamConfigurationMap
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range

class CameraHandler(
    val cameraManager: CameraManager,
    val id: String,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val isoRange: Range<Int>,
    val speedRange: Range<Long>,
    val exposureCompensantionRange: Range<Int>,
    val focusRange: Range<Float>,
    val focusHyperfocalDistance: Float,
    val hasFlash: Boolean,
    val sensorOrientation: Int,
    val streamConfigurationMap: StreamConfigurationMap
) {

    companion object {
        fun getValidCameras(cameraManager: CameraManager): ArrayList<CameraHandler> {
            val validCameras = ArrayList<CameraHandler>()

            try {
                val cameraIds = cameraManager.cameraIdList

                for (cameraId in cameraIds) {

                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                        val keys = characteristics.keys

                        if ((characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) as Int) < CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) continue

                        val isoRange =
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) as Range<Int>
                        val exposureCompensantionRange =
                            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) as Range<Int>
                        val speedRange =
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) as Range<Long>

                        val focusMinDistance =
                            characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) as Float
                        val focusHyperfocalDistance =
                            characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) as Float
                        val focusRange = Range(0f, focusMinDistance)

                        val hasFlash =
                            if (CameraCharacteristics.FLASH_INFO_AVAILABLE in keys)
                                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) as Boolean
                            else
                                false

                        val resolutionRect =
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) as Rect

                        val sensorOrientation =
                            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) as Int

                        val streamConfigurationMap =
                            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap
                        validCameras.add(
                            CameraHandler(
                                cameraManager,
                                cameraId,
                                resolutionRect.width(),
                                resolutionRect.height(),
                                isoRange,
                                speedRange,
                                exposureCompensantionRange,
                                focusRange,
                                focusHyperfocalDistance,
                                hasFlash,
                                sensorOrientation,
                                streamConfigurationMap
                            )
                        )
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            }

            return validCameras
        }
    }

    val areDimensionsSwapped = sensorOrientation == 90 || sensorOrientation == 270
}