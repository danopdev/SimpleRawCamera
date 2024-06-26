package com.dan.simplerawcamera

import android.app.Activity
import android.content.Context
import kotlin.reflect.*
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties

/**
 Settings: all public var fields will be saved
 */
class Settings( private val activity: Activity) {

    companion object {
        const val EXP_STEPS_PER_1EV = 2

        const val SPEED_MANUAL_MIN_PREVIEW = 15625000L // 1/64 sec
        const val SPEED_MAX_MANUAL = 64000000000L // 64 sec
        const val SPEED_DEFAULT_MANUAL = 7812500L // 1/128

        const val ISO_MODE_AUTO = 0
        const val ISO_MODE_MANUAL = 1
        val ISO_MODE_TO_STRING = arrayOf("A", "M")

        const val SPEED_MODE_AUTO = 0
        const val SPEED_MODE_MANUAL = 1
        val SPEED_MODE_TO_STRING = arrayOf("A", "M")

        const val FOCUS_TYPE_CONTINUOUS = 0
        const val FOCUS_TYPE_HYPERFOCAL = 1
        const val FOCUS_TYPE_MANUAL = 2
        const val FOCUS_TYPE_MAX = 3

        const val PHOTO_TYPE_JPEG = 0
        const val PHOTO_TYPE_DNG = 1
        const val PHOTO_TYPE_JPEG_DNG = 2

        const val FRAME_TYPE_NONE = 0
        const val FRAME_TYPE_1_1 = 1
        const val FRAME_TYPE_4_3 = 2
        const val FRAME_TYPE_3_2 = 3
        const val FRAME_TYPE_16_9 = 4

        const val FLASH_MODE_OFF = 0
        const val FLASH_MODE_ON = 1
        const val FLASH_MODE_TORCH = 2

        val SEQUENCE_DELAY_START_OPTIONS = arrayOf(2, 5, 10)
        val SEQUENCE_DELAY_BETWEEN_OPTIONS = arrayOf(0, 1, 5, 10, 15, 20, 25, 30, 40, 50, 60, 90, 120, 150, 300, 600)
        val SEQUENCE_NUMBER_OF_PHOTOS_OPTIONS = arrayOf(1, 3, 5, 10, 0)
        val SEQUENCE_KEEP_PHOTOS_1_FOR_N_MAX = 9 //9 = every 10 photos

        val MACRO_DELAY_START_OPTIONS = arrayOf(0, 1, 2, 3)
        val MACRO_NUMBER_OF_PHOTOS_OPTIONS = arrayOf(3, 4, 5, 6, 7, 8, 9, 10)

        val FLASH_MODES = arrayOf("OFF", "ON", "Torch")

        val WB_MODES = arrayOf(
            android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE_AUTO,
            android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE_INCANDESCENT,
            android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE_FLUORESCENT,
            android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE_WARM_FLUORESCENT,
            android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE_DAYLIGHT,
            android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE_TWILIGHT,
            android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE_SHADE
        )
    }

    var saveUri: String = ""
    var cameraIndex: Int = 0
    var isoMode: Int = ISO_MODE_AUTO
    var isoValue: Int = 100
    var speedMode: Int = SPEED_MODE_AUTO
    var speedValue: Long = 7812500L // 1/125 seconds
    var expCompensationValue: Int = 0
    var focusType: Int = FOCUS_TYPE_CONTINUOUS
    var focusManualProgress: Int = 0
    var showGrid: Boolean = true
    var frameType: Int = FRAME_TYPE_NONE
    var continuousMode: Boolean = true
    var takePhotoModes: Int = PHOTO_TYPE_JPEG_DNG
    var noiseReduction: Boolean = true
    var sequenceDelayStart: Int = 2
    var sequenceDelayBetween: Int = 0
    var sequenceNumberOfPhotos: Int = 0
    var sequenceKeepPhotos: Int = 0
    var macroDelayStart: Int = 1
    var macroNumberOfPhotos: Int = MACRO_NUMBER_OF_PHOTOS_OPTIONS[0]
    var showDebugInfo: Boolean = false
    var flashMode: Int = FLASH_MODE_OFF
    var enableHapticFeedback = true
    var useLocation = true
    var whiteBalance = 0

    init {
        loadProperties()
    }

    private fun forEachSettingProperty( listener: (KMutableProperty<*>)->Unit ) {
        for( member in this::class.declaredMemberProperties ) {
            if (member.visibility == KVisibility.PUBLIC && member is KMutableProperty<*>) {
                listener.invoke(member)
            }
        }
    }

    private fun fixProperties() {
        if (!CameraInfo.supportDng) {
            takePhotoModes = PHOTO_TYPE_JPEG
        } else if (!CameraInfo.supportJpeg) {
            takePhotoModes = PHOTO_TYPE_DNG
        }
    }

    private fun callSafe(f: ()->Unit ) {
        try {
            f()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadProperties() {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)

        forEachSettingProperty { property ->
            callSafe {
                when (property.returnType) {
                    Boolean::class.createType() -> property.setter.call(
                        this,
                        preferences.getBoolean(property.name, property.getter.call(this) as Boolean)
                    )
                    Int::class.createType() -> property.setter.call(
                        this,
                        preferences.getInt(property.name, property.getter.call(this) as Int)
                    )
                    Long::class.createType() -> property.setter.call(
                        this,
                        preferences.getLong(property.name, property.getter.call(this) as Long)
                    )
                    Float::class.createType() -> property.setter.call(
                        this,
                        preferences.getFloat(property.name, property.getter.call(this) as Float)
                    )
                    String::class.createType() -> property.setter.call(
                        this,
                        preferences.getString(property.name, property.getter.call(this) as String)
                    )
                }
            }
        }

        fixProperties()
   }

    fun saveProperties() {
        fixProperties()

        val preferences = activity.getPreferences(Context.MODE_PRIVATE)
        val editor = preferences.edit()

        forEachSettingProperty { property ->
            when( property.returnType ) {
                Boolean::class.createType() -> editor.putBoolean( property.name, property.getter.call(this) as Boolean )
                Int::class.createType() -> editor.putInt( property.name, property.getter.call(this) as Int )
                Long::class.createType() -> editor.putLong( property.name, property.getter.call(this) as Long )
                Float::class.createType() -> editor.putFloat( property.name, property.getter.call(this) as Float )
                String::class.createType() -> editor.putString( property.name, property.getter.call(this) as String )
            }
        }

        editor.apply()
    }

    fun getArrayValue( value: Int, delta: Int, array: Array<Int> ): Int {
        var index = array.indexOf(value)
        if (index < 0) index = 0

        index += delta

        if (index < 0) index = 0
        else if (index >= array.size) index = array.size-1

        return array[index]
    }

    fun getWBMode(availableModes: IntArray): Int {
        val whiteBalance = this.whiteBalance;
        val mode = if (whiteBalance < WB_MODES.size) WB_MODES[whiteBalance] else WB_MODES[0]
        return if (availableModes.contains(mode)) mode else WB_MODES[0]
    }
}