package com.dan.simplerawcamera

import android.app.Activity
import android.content.Context
import kotlin.reflect.*
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties

/**
 Settings: all public var fields will be save / restaured
 */
class Settings( private val activity: Activity) {

    companion object {
        const val EXP_STEPS_PER_1EV = 2

        const val SPEED_MANUAL_MIN_PREVIEW = 15625000L // 1/64 sec
        const val SPEED_MAX_MANUAL = 8000000000L // 8 sec
        const val SPEED_DEFAULT_MANUAL = 7812500L // 1/128

        const val FOCUS_TYPE_CONTINOUS = 0
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

        const val NOISE_REDUCTION_DISABLED = 0
        const val NOISE_REDUCTION_JPEG_ONLY = 1
        const val NOISE_REDUCTION_ENABLED = 2

        val SEQUENCE_DELAY_START_OPTIONS = arrayOf(2, 5, 10)
        val SEQUENCE_DELAY_BETWEEN_OPTIONS = arrayOf(0, 1, 5, 10, 30, 60, 120, 300, 600)
        val SEQUENCE_NUMBER_OF_PHOTOS_OPTIONS = arrayOf(1, 3, 5, 10, 0)

        val FLASH_MODES = arrayOf("OFF", "ON", "Torch")
    }

    var saveUri: String = ""
    var cameraIndex: Int = 0
    var expIsoIsManual: Boolean = false
    var expIsoValue: Int = 100
    var expSpeedIsManual: Boolean = false
    var expSpeedValue: Long = 7812500L // 1/125 seconds
    var expCompensationValue: Int = 0
    var focusType: Int = FOCUS_TYPE_CONTINOUS
    var focusManualProgress: Int = 0
    var showGrid: Boolean = true
    var frameType: Int = FRAME_TYPE_NONE
    var continuousMode: Boolean = true
    var takePhotoModes: Int = PHOTO_TYPE_JPEG_DNG
    var noiseReduction: Int = NOISE_REDUCTION_JPEG_ONLY
    var sequenceDelayStart: Int = 2
    var sequenceDelayBetween: Int = 0
    var sequenceNumberOfPhotos: Int = 0
    var showSequence: Boolean = false
    var showDebugInfo: Boolean = false
    var flashMode: Int = FLASH_MODE_OFF
    var enableHapticFeedback = true
    var useLocation = true

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

    private fun loadProperties() {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)

        forEachSettingProperty { property ->
            when( property.returnType ) {
                Boolean::class.createType() -> property.setter.call( this, preferences.getBoolean( property.name, property.getter.call(this) as Boolean ) )
                Int::class.createType() -> property.setter.call( this, preferences.getInt( property.name, property.getter.call(this) as Int ) )
                Long::class.createType() -> property.setter.call( this, preferences.getLong( property.name, property.getter.call(this) as Long ) )
                Float::class.createType() -> property.setter.call( this, preferences.getFloat( property.name, property.getter.call(this) as Float ) )
                String::class.createType() -> property.setter.call( this, preferences.getString( property.name, property.getter.call(this) as String ) )
            }
        }
    }

    fun saveProperties() {
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

        editor.commit()
    }

    fun getArrayValue( value: Int, delta: Int, array: Array<Int> ): Int {
        var index = array.indexOf(value)
        if (index < 0) index = 0

        index += delta

        if (index < 0) index = 0
        else if (index >= array.size) index = array.size-1

        return array[index]
    }
}