package com.lgsextractor

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class LGSApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: Provider<androidx.work.WorkerFactory>

    override fun onCreate() {
        super.onCreate()
        initOpenCV()
        initWorkManager()
    }

    private fun initOpenCV() {
        if (!OpenCVLoader.initLocal()) {
            // OpenCV init failed - will fallback to pure ML Kit OCR
            android.util.Log.e("LGSApp", "OpenCV initialization failed")
        } else {
            android.util.Log.i("LGSApp", "OpenCV initialized: ${OpenCVLoader.OPENCV_VERSION}")
        }
    }

    private fun initWorkManager() {
        WorkManager.initialize(this, workManagerConfiguration)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
