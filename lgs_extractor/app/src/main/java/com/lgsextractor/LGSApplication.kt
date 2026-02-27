package com.lgsextractor

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class LGSApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        setupCrashLogger()
        super.onCreate()
        // OpenCV initialized lazily inside OpenCVProcessor on first use.
        // Calling OpenCVLoader.initLocal() here risks a native SIGSEGV crash
        // that bypasses the Java exception handler and kills the process.
    }

    private fun setupCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = File(getExternalFilesDir(null), "crash_log.txt")
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val text = buildString {
                    appendLine("=== CRASH $time ===")
                    appendLine("Thread: ${thread.name}")
                    appendLine(throwable.stackTraceToString())
                }
                logFile.writeText(text)
                android.util.Log.e("LGSCrash", text)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
