package com.dan.simplerawcamera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size

/**
 Contains camera characteristics (don't need to query again)
 */
class CameraInfo(
    val id: String,
    val physicalId: String?,
    val cameraCharacteristics: CameraCharacteristics,
    val rawSize: Size,
    val jpegSize: Size,
    val isoRange: Range<Int>,
    val speedRange: Range<Long>,
    val exposureCompensationRange: Range<Int>,
    val exposureCompensationMultiplyFactor: Int,
    val focusRange: Range<Float>,
    val focusHyperfocalDistance: Float,
    val focusAllowManual: Boolean,
    val hasFlash: Boolean,
    val sensorOrientation: Int,
    val streamConfigurationMap: StreamConfigurationMap,
    val supportLensStabilisation: Boolean,
    val wbModes: IntArray
) {

    companion object {

        private val BLACKLIST_JPEG = listOf<Pair<String,String>>(
            //Pair("Google", "Pixel 6a")
        )

        private val BLACKLIST_DNG = listOf<Pair<String,String>>(
        )

        private val device = Pair(Build.MANUFACTURER, Build.MODEL)

        val supportJpeg: Boolean = !BLACKLIST_JPEG.contains(device)
        val supportDng: Boolean = !BLACKLIST_DNG.contains(device)

        private fun getCameraInfo(cameraId: String, physicalId: String?, characteristics: CameraCharacteristics): CameraInfo? {
            val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) as Int
            if (level != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL && level != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) return null

            val keys = characteristics.keys

            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) as Range<Int>
            val speedRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) as Range<Long>
            val exposureCompensationRangeFull = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) as Range<Int>
            val exposureCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) as Rational
            val exposureCompensationMultiplyFactor = exposureCompensationStep.denominator / Settings.EXP_STEPS_PER_1EV
            val exposureCompensationRange = Range(
                exposureCompensationRangeFull.lower / exposureCompensationMultiplyFactor,
                exposureCompensationRangeFull.upper / exposureCompensationMultiplyFactor
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

            val jpegSize = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)[0]
            val rawSize = Size(resolutionRect.width(), resolutionRect.height())

            val wbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) as IntArray

            return CameraInfo(
                cameraId,
                physicalId,
                characteristics,
                rawSize,
                jpegSize,
                isoRange,
                speedRange,
                exposureCompensationRange,
                exposureCompensationMultiplyFactor,
                focusRange,
                focusHyperfocalDistance,
                focusMaxRegions >= 1,
                hasFlash,
                sensorOrientation,
                streamConfigurationMap,
                supportLensStabilisation,
                wbModes
            )
        }

        /**
         List all available and valid (for this application) cameras.
         */
        fun getValidCameras(cameraManager: CameraManager): ArrayList<CameraInfo> {
            val validCameras = ArrayList<CameraInfo>()

            if (!supportJpeg && !supportDng) return validCameras

            try {
                val cameraIds = cameraManager.cameraIdList

                for (cameraId in cameraIds) {
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                        val physicalCameraIds = characteristics.physicalCameraIds
                        if (physicalCameraIds.size >= 1) {
                            physicalCameraIds.forEach { physicalCameraId ->
                                if (null != physicalCameraId ) {
                                    getCameraInfo(
                                        cameraId,
                                        physicalCameraId,
                                        cameraManager.getCameraCharacteristics(physicalCameraId)
                                    )?.apply { validCameras.add(this) }
                                }
                            }
                            continue
                        }

                        getCameraInfo(cameraId, null, characteristics)?.apply { validCameras.add(this) }
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
            var speed = 1000000000L

            /*
             * Speeds bigger then 1s:
             *    * 1-4 seconds: step is 0.5s
             *    * >= 4 seconds: step is 1s
             */
            while(true) {
                speed += if (speed >= 4000000000L) {
                    1000000000L
                } else {
                    500000000L
                }

                if (speed > speedRange.upper || speed > Settings.SPEED_MAX_MANUAL) break
                speedList.add(speed)
            }

            //insert in front speeds less then 1s by Settings.EXP_STEPS_PER_1EV
            var div = 1L
            var divNext: Long
            while(true) {
                divNext = div * 2L

                speed = (1000000000.0 / ((divNext + div) / 2.0)).toLong()
                if (speed < speedRange.lower) break
                speedList.add(0, speed)

                speed = 1000000000L / divNext
                if (speed < speedRange.lower) break
                speedList.add(0, speed)

                div = divNext
            }

            if (speedList[0] > speedRange.lower) {
                speedList.add(0, speedRange.lower)
            }

            return speedList
        }

        fun getIsoStepsArray( isoRange: Range<Int>): ArrayList<Int> {
            val isoList = arrayListOf(isoRange.lower)
            var nextIso: Int
            var currentIso = isoList.last()

            while(true) {
                nextIso = currentIso * 2
                if (nextIso > (isoRange.upper+1)) break

                for (i in 1..Settings.EXP_STEPS_PER_1EV) {
                    isoList.add(currentIso + currentIso * i / Settings.EXP_STEPS_PER_1EV)
                }

                currentIso = nextIso
            }

            return isoList
        }
    }

    val areDimensionsSwapped = sensorOrientation == 0 || sensorOrientation == 180
    val estimatedDngSize = rawSize.width *  rawSize.height * 2 + 100000
    val estimatedJpegSize = estimatedDngSize / 3
    val speedSteps = getSpeedStepsArray(speedRange)
    val isoSteps = getIsoStepsArray(isoRange)

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
        val newIndex = currentIndex + direction
        if (newIndex < 0) return 0
        if (newIndex >= arraySize) return arraySize-1
        return newIndex
    }

    fun getSpeed( realSpeed: Long, direction: Int): Long =
        speedSteps[getArrayIndex(getClosestSpeedIndex(realSpeed), direction, speedSteps.size)]

    fun getIso( realIso: Int, direction: Int): Int =
        isoSteps[getArrayIndex(getClosestIsoIndex(realIso), direction, isoSteps.size)]
}