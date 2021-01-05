package com.dan.simplerawcamera

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlin.reflect.*
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

/**
 Settings: all public var fields will be save / restaured
 */
class Settings( private val activity: Activity) {

    companion object {
        const val SPEED_MANUAL_MIN_PREVIEW = 62500000L // 1/16 sec
        const val SPEED_MAX_MANUAL = 4000000000L // 4 sec
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
    }

    var saveUri: String = ""
    var cameraIndex: Int = 0
    var expIsoIsManual: Boolean = false
    var expIsoValue: Int = 100
    var expSpeedIsManual: Boolean = false
    var expSpeedDivValue: Long = SPEED_MAX_MANUAL / SPEED_DEFAULT_MANUAL
    var expCompensationValue: Int = 0
    var focusType: Int = FOCUS_TYPE_CONTINOUS
    var focusManualProgress: Int = 0
    var showGrid: Boolean = true
    var frameType: Int = FRAME_TYPE_NONE
    var continuousMode: Boolean = true
    var takePhotoModes: Int = PHOTO_TYPE_JPEG_DNG

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
}