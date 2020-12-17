package com.dan.simplerawcamera

import android.app.Activity
import android.content.Context
import kotlin.reflect.full.declaredMemberProperties

class Settings( private val activity: Activity) {

    companion object {
    }

    var cameraId: String = "0"
    var expIsoIsManual: Boolean = false
    var expIsoValue: Int = 100
    var expSpeedIsManual: Boolean = false
    var expSpeedValue: Long = 7812500L // 1/128

    private fun forEachProperty() {
        for( member in this::class.declaredMemberProperties ) {

        }
    }

    fun create() {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)
        //_backupServer = preferences.getString(BACKUP_SERVER_KEY, _backupServer) ?: _backupServer
    }

    private fun save() {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)
        val editor = preferences.edit()

        //editor.putString( BACKUP_SERVER_KEY, _backupServer )

        editor.commit()
    }

    /*
    var backupServer: String
        get() = _backupServer
        set(value) {
            if (!_backupServer.equals(value)) {
                _backupServer = value
                _backupServerChanged = true
                save()
            }
        }
     */

}