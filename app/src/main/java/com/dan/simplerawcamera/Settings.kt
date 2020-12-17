package com.dan.simplerawcamera

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlin.reflect.*
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

class Settings( private val activity: Activity) {

    companion object {
        const val SPEED_MANUAL_MIN_PREVIEW = 62500000L // 1/16 sec
        const val SPEED_MAX_MANUAL = 4000000000L // 4 sec
        const val SPEED_DEFAULT_MANUAL = 7812500L // 1/128

        const val FOCUS_TYPE_CONTINOUS = 0
        const val FOCUS_TYPE_CLICK = 1
        const val FOCUS_TYPE_HYPERFOCAL = 2
        const val FOCUS_TYPE_MANUAL = 3
        const val FOCUS_TYPE_MAX = 4
    }

    var cameraId: String = "0"
    var expIsoIsManual: Boolean = false
    var expIsoValue: Int = 100
    var expSpeedIsManual: Boolean = false
    var expSpeedDivValue: Long = SPEED_MAX_MANUAL / SPEED_DEFAULT_MANUAL
    var expCompensationValue: Int = 0
    var focusType: Int = FOCUS_TYPE_CONTINOUS
    var focusManualDistance: Float = 0F

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
                Int::class.createType() -> editor.putInt( property.name, property.getter.call(this) as Int )
                Long::class.createType() -> editor.putLong( property.name, property.getter.call(this) as Long )
                Float::class.createType() -> editor.putFloat( property.name, property.getter.call(this) as Float )
                String::class.createType() -> editor.putString( property.name, property.getter.call(this) as String )
            }
        }

        editor.commit()
    }
}