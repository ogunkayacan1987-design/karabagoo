package com.lgsextractor

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class LGSApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        initOpenCV()
        // WorkManager is NOT manually initialized here.
        // This class implements Configuration.Provider so WorkManager
        // lazily uses workManagerConfiguration on first access.
    }

    private fun initOpenCV() {
        try {
            if (!OpenCVLoader.initLocal()) {
                android.util.Log.e("LGSApp", "OpenCV initialization failed")
            } else {
                android.util.Log.i("LGSApp", "OpenCV initialized: ${OpenCVLoader.OPENCV_VERSION}")
            }
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("LGSApp", "OpenCV native library not found, OCR will use ML Kit only", e)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
