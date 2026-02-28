package com.lgsextractor.processing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lgsextractor.R
import com.lgsextractor.domain.model.DetectionConfig
import com.lgsextractor.domain.model.PdfDocument
import com.lgsextractor.domain.model.ProcessingPhase
import com.lgsextractor.domain.usecase.ExtractQuestionsUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PdfProcessingService : Service() {

    @Inject lateinit var extractQuestionsUseCase: ExtractQuestionsUseCase

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var processingJob: Job? = null

    companion object {
        const val ACTION_START = "com.lgsextractor.ACTION_START_PROCESSING"
        const val ACTION_CANCEL = "com.lgsextractor.ACTION_CANCEL_PROCESSING"
        const val EXTRA_DOCUMENT_ID = "document_id"
        const val CHANNEL_ID = "lgs_processing_channel"
        const val NOTIFICATION_ID = 1001

        const val BROADCAST_PROGRESS = "com.lgsextractor.PROGRESS"
        const val BROADCAST_COMPLETE = "com.lgsextractor.COMPLETE"
        const val BROADCAST_ERROR = "com.lgsextractor.ERROR"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                processingJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val documentId = intent?.getStringExtra(EXTRA_DOCUMENT_ID) ?: run {
            stopSelf(); return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("PDF yükleniyor...", 0))

        processingJob = serviceScope.launch {
            // Document will be loaded by the use case
            val document = PdfDocument(
                id = documentId, filePath = "", fileName = "",
                pageCount = 0, fileSizeBytes = 0
            )
            extractQuestionsUseCase.execute(document, DetectionConfig())
                .collect { progress ->
                    // Update notification
                    val message = progress.message
                    updateNotification(message, progress.percentage)

                    // Broadcast to UI
                    val broadcastIntent = Intent(BROADCAST_PROGRESS).apply {
                        putExtra("percentage", progress.percentage)
                        putExtra("message", message)
                        putExtra("questions_found", progress.questionsFound)
                        putExtra("phase", progress.phase.name)
                    }
                    sendBroadcast(broadcastIntent)

                    if (progress.phase == ProcessingPhase.COMPLETE) {
                        sendBroadcast(Intent(BROADCAST_COMPLETE).apply {
                            putExtra("questions_found", progress.questionsFound)
                        })
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
        }

        processingJob?.invokeOnCompletion { throwable ->
            if (throwable != null && throwable !is kotlinx.coroutines.CancellationException) {
                sendBroadcast(Intent(BROADCAST_ERROR).apply {
                    putExtra("error", throwable.message)
                })
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(message: String, progress: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LGS Soru Çıkarıcı")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(message: String, progress: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(message, progress))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PDF İşleme",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PDF soru çıkarma işlemi bildirimleri"
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
