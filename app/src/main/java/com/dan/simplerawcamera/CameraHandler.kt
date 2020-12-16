package com.dan.simplerawcamera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.util.Range
import android.util.Rational
import kotlin.math.max
import kotlin.math.min

class CameraHandler(
    val cameraManager: CameraManager,
    val cameraCharacteristics: CameraCharacteristics,
    val id: String,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val isoRange: Range<Int>,
    val speedRange: Range<Long>,
    val exposureCompensantionRange: Range<Int>,
    val exposureCompensantionMulitplyFactor: Int,
    val focusRange: Range<Float>,
    val focusHyperfocalDistance: Float,
    val focusAllowManual: Boolean,
    val focusAllowFaceDetection: Boolean,
    val hasFlash: Boolean,
    val sensorOrientation: Int,
    val streamConfigurationMap: StreamConfigurationMap,
    val supportLensStabilisation: Boolean
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

                        val isoRealRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) as Range<Int>
                        val isoBoostRange = characteristics.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE) as Range<Int>

                        Log.i("CAM ${cameraId}", "ISO Range: ${isoRealRange.lower} - ${isoRealRange.upper}")
                        Log.i("CAM ${cameraId}", "ISO Boost Range: ${isoBoostRange.lower} - ${isoBoostRange.upper}")
                        val isoRange = Range(min(isoRealRange.lower, isoBoostRange.lower), max(isoRealRange.upper, isoBoostRange.upper))

                        val speedRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) as Range<Long>

                        val aeMaxRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) as Int
                        Log.i("CAM ${cameraId}", "aeMaxRegions: ${aeMaxRegions}")

                        val faceMax = characteristics.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) as Int
                        val faceModes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) as IntArray
                        var str = ""
                        faceModes.forEach { str += " " + it.toString() }
                        Log.i("CAM ${cameraId}", "faceMax: ${faceMax}")
                        Log.i("CAM ${cameraId}", "faceModes: ${str}")

                        val exposureCompensantionRangeFull = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) as Range<Int>
                        val exposureCompensantionStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) as Rational
                        val exposureCompensantionMulitplyFactor = exposureCompensantionStep.denominator
                        val exposureCompensantionRange = Range(
                            exposureCompensantionRangeFull.lower / exposureCompensantionMulitplyFactor,
                            exposureCompensantionRangeFull.upper / exposureCompensantionMulitplyFactor
                        )

                        val focusMinDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) as Float
                        val focusHyperfocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) as Float
                        val focusRange = Range(0f, focusMinDistance)
                        val focusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) as IntArray
                        val focusMaxRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) as Int

                        val hasFlash =
                            if (CameraCharacteristics.FLASH_INFO_AVAILABLE in keys)
                                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) as Boolean
                            else
                                false

                        val resolutionRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) as Rect
                        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) as Int
                        val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap

                        val supportLensStabilisation =
                            (characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) as IntArray)
                                .contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

                        validCameras.add(
                            CameraHandler(
                                cameraManager,
                                characteristics,
                                cameraId,
                                resolutionRect.width(),
                                resolutionRect.height(),
                                isoRange,
                                speedRange,
                                exposureCompensantionRange,
                                exposureCompensantionMulitplyFactor,
                                focusRange,
                                focusHyperfocalDistance,
                                focusMaxRegions >= 1,
                                faceMax >= 1,
                                hasFlash,
                                sensorOrientation,
                                streamConfigurationMap,
                                supportLensStabilisation
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

    val areDimensionsSwapped = sensorOrientation == 0 || sensorOrientation == 180
}