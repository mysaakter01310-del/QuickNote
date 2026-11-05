package com.quicknote.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the last crash to a file so it can be read in Menu > Diagnostics. */
class QuickNoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText("${java.util.Date()}\nthread: ${t.name}\n$sw")
            } catch (x: Exception) {
            }
            old?.uncaughtException(t, e)
        }
    }
}
