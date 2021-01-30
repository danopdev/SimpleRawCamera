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

/**
 Contains camera characteristics (don't need to query again)
 */
class CameraInfo(
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

        /**
         List all available and valid (for this application) cameras.
         */
        fun getValidCameras(cameraManager: CameraManager): ArrayList<CameraInfo> {
            val validCameras = ArrayList<CameraInfo>()

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
                        val exposureCompensantionMulitplyFactor = exposureCompensantionStep.denominator / Settings.EXP_STEPS_PER_1EV
                        val exposureCompensantionRange = Range(
                            exposureCompensantionRangeFull.lower / exposureCompensantionMulitplyFactor,
                            exposureCompensantionRangeFull.upper / exposureCompensantionMulitplyFactor
                        )

                        val focusMinDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) as Float
                        val focusHyperfocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) as Float
                        val focusRange = Range(0f, focusMinDistance)
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
                            CameraInfo(
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
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return validCameras
        }

        fun getSpeedStepsArray( speedRange: Range<Long>): ArrayList<Long> {
            val speedList = arrayListOf(1000000000L) //start with 1 second
            var speed = 1000000000L;
            var speed1EVLess: Long;

            /*
             * Speeds bigger then 1s:
             *    * 1-4 seconds: step is 0.5s
             *    * >= 4 seconds: step is 1s
             */
            while(true) {
                if (speed >= 4000000000L) {
                    speed += 1000000000L
                } else {
                    speed += 500000000L;
                }

                if (speed > speedRange.upper || speed > Settings.SPEED_MAX_MANUAL) break
                speedList.add(speed)
            }

            //insert in front speeds less then 1s by Settings.EXP_STEPS_PER_1EV
            speed = 1000000000L;

            while(true) {
                speed1EVLess = speed / 2

                for (i in Settings.SPEED_MAX_MANUAL-1 downTo 0) {
                    speed = speed1EVLess * i / Settings.SPEED_MAX_MANUAL
                    if (speed < speedRange.lower) break
                    speedList.add(0, speed)
                }

                speed = speed1EVLess
                if (speed < speedRange.lower) break
            }

            if (speedList[0] > speedRange.lower) {
                speedList.add(0, speedRange.lower)
            }

            return speedList;
        }

        fun getIsoStepsArray( isoRange: Range<Int>): ArrayList<Int> {
            val isoList = arrayListOf(isoRange.lower)
            var nextIso: Int
            var currentIso = isoList.last()

            while(true) {
                nextIso = currentIso * 2
                if (nextIso > isoRange.upper) break

                for (i in 1..Settings.EXP_STEPS_PER_1EV) {
                    isoList.add(currentIso * i / Settings.EXP_STEPS_PER_1EV)
                }

                currentIso = nextIso
            }

            return isoList
        }
    }

    val areDimensionsSwapped = sensorOrientation == 0 || sensorOrientation == 180
    val estimatedDngSize = resolutionWidth *  resolutionWidth * 2 + 100000
    val estimatedJpegSize = estimatedDngSize / 3
    val speedSteps = getSpeedStepsArray(speedRange)
    val isoSteps = getIsoStepsArray(isoRange)

    init {
        Log.i("CAMERA_INFO", "Speeds: ${speedSteps}")
        Log.i("CAMERA_INFO", "ISOs: ${isoSteps}")
    }

    private fun getClosestIsoIndex(iso: Int): Int {
        //TODO: switch to binary search
        var index = 1
        while (index < isoSteps.size) {
            if (isoSteps[index] > iso) break
            index++
        }
        return index-1
    }

    private fun getClosestSpeedIndex(speed: Long): Int {
        //TODO: switch to binary search
        var index = 1
        while (index < speedSteps.size) {
            if (speedSteps[index] > speed) break
            index++
        }
        return index-1
    }

    private fun getArrayIndex(currentIndex: Int, direction: Int, arraySize: Int): Int {
        var newIndex = currentIndex
        if (direction < 0) newIndex--
        else if (direction > 0) newIndex++

        if (newIndex < 0) return 0
        if (newIndex >= arraySize) return arraySize-1
        return newIndex
    }

    fun getSpeed( realSpeed: Long, direction: Int): Long =
        speedSteps[getArrayIndex(getClosestSpeedIndex(realSpeed), direction, speedSteps.size)]

    fun getIso( realIso: Int, direction: Int): Int =
        isoSteps[getArrayIndex(getClosestIsoIndex(realIso), direction, isoSteps.size)]
}