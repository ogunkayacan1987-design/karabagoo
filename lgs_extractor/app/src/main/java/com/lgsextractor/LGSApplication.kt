package com.lgsextractor

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class LGSApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        setupCrashLogger()
        super.onCreate()
        initOpenCV()
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

    private fun initOpenCV() {
        try {
            if (!OpenCVLoader.initLocal()) {
                android.util.Log.e("LGSApp", "OpenCV initialization failed")
            } else {
                android.util.Log.i("LGSApp", "OpenCV initialized: ${OpenCVLoader.OPENCV_VERSION}")
            }
        } catch (e: Throwable) {
            android.util.Log.e("LGSApp", "OpenCV init error", e)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}

