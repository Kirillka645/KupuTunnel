package com.kuputunnel.app

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Без прогрева Xray при старте — иначе UnsatisfiedLinkError/native crash
 * валит процесс ещё до MainActivity (Error не ловится catch Exception).
 */
class KupuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = "Thread=${t.name}\n$sw"
                Log.e("KupuCrash", text)
                File(filesDir, "last_crash.txt").writeText(text)
            } catch (_: Throwable) {
            }
            defaultHandler?.uncaughtException(t, e)
        }
    }
}
