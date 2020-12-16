package com.dan.simplerawcamera

import android.app.Activity
import android.content.Context

class Settings( val activity: Activity) {

    companion object {
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