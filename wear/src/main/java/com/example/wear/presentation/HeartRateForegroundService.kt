package com.example.bikeheartrateapp.wear.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import com.example.bikeheartrateapp.wear.R
import com.google.android.gms.wearable.Wearable

class HeartRateForegroundService : Service() {
    private lateinit var measureClient: MeasureClient

    private var shouldCollectHeartRate = false
    private var isCallbackRegistered = false

    private val heartRatePermission: String
        get() = if (Build.VERSION.SDK_INT >= 36) {
            "android.permission.health.READ_HEART_RATE"
        } else {
            Manifest.permission.BODY_SENSORS
        }

    private val heartRateCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability
        ) {
            if (dataType != DataType.HEART_RATE_BPM) return

            val message = when (availability) {
                DataTypeAvailability.AVAILABLE -> "HR sensor available"
                DataTypeAvailability.UNAVAILABLE -> "HR unavailable. Wear watch tightly."
                DataTypeAvailability.ACQUIRING -> "Acquiring HR..."
                else -> "HR availability: $availability"
            }

            publishStatus(message)
        }

        override fun onDataReceived(data: DataPointContainer) {
            data.getData(DataType.HEART_RATE_BPM).forEach { point ->
                val bpm = point.value.toInt()
                if (bpm <= 0) return@forEach

                publishStatus("Live HR received", bpm = bpm)
                updateNotification("HR: $bpm bpm")
                sendBpmToPhone(bpm)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        measureClient = HealthServices.getClient(this).measureClient
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopHeartRateCollection()
                stopSelf()
                START_NOT_STICKY
            }

            else -> {
                startInForeground()
                startHeartRateCollection()
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopHeartRateCollection()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        isRunning = true

        val notification = buildNotification("Waiting for heart rate...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        publishStatus("Waiting for heart rate...", running = true)
    }

    private fun startHeartRateCollection() {
        if (shouldCollectHeartRate || isCallbackRegistered) {
            publishStatus("Heart-rate sending already running")
            return
        }

        if (!hasHeartRatePermission()) {
            publishStatus("Heart-rate permission missing", running = false)
            stopHeartRateCollection()
            stopSelf()
            return
        }

        shouldCollectHeartRate = true
        publishStatus("Checking HR support...")

        val capabilitiesFuture = measureClient.getCapabilitiesAsync()
        capabilitiesFuture.addListener(
            {
                try {
                    if (!shouldCollectHeartRate) return@addListener

                    val capabilities = capabilitiesFuture.get()
                    val supportsHeartRate =
                        DataType.HEART_RATE_BPM in capabilities.supportedDataTypesMeasure

                    if (!supportsHeartRate) {
                        publishStatus("HR not supported", running = false)
                        stopHeartRateCollection()
                        stopSelf()
                        return@addListener
                    }

                    registerHeartRateCallback()
                } catch (e: Exception) {
                    publishStatus("HR support error: ${e.message}", running = false)
                    stopHeartRateCollection()
                    stopSelf()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun registerHeartRateCallback() {
        if (!shouldCollectHeartRate || isCallbackRegistered) return

        try {
            measureClient.registerMeasureCallback(
                DataType.HEART_RATE_BPM,
                heartRateCallback
            )

            isCallbackRegistered = true
            publishStatus("Live HR started")
        } catch (e: Exception) {
            publishStatus("Start failed: ${e.message}", running = false)
            stopHeartRateCollection()
            stopSelf()
        }
    }

    private fun stopHeartRateCollection() {
        shouldCollectHeartRate = false

        if (isCallbackRegistered) {
            try {
                measureClient.unregisterMeasureCallbackAsync(
                    DataType.HEART_RATE_BPM,
                    heartRateCallback
                )
            } catch (e: Exception) {
                publishStatus("Stop failed: ${e.message}", running = false)
            } finally {
                isCallbackRegistered = false
            }
        }

        isRunning = false
        publishStatus("Live HR stopped", running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun sendBpmToPhone(bpm: Int) {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    publishStatus("No phone connected", bpm = bpm)
                    return@addOnSuccessListener
                }

                nodes.forEach { node ->
                    Wearable.getMessageClient(this)
                        .sendMessage(
                            node.id,
                            MESSAGE_PATH,
                            bpm.toString().toByteArray()
                        )
                        .addOnSuccessListener {
                            publishStatus("Sent HR $bpm", bpm = bpm)
                        }
                        .addOnFailureListener { error ->
                            publishStatus("Send failed: ${error.message}", bpm = bpm)
                        }
                }
            }
            .addOnFailureListener { error ->
                publishStatus("Node error: ${error.message}", bpm = bpm)
            }
    }

    private fun publishStatus(
        message: String,
        bpm: Int? = null,
        running: Boolean = isRunning
    ) {
        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, message)
            .putExtra(EXTRA_RUNNING, running)

        if (bpm != null) {
            intent.putExtra(EXTRA_BPM, bpm)
        }

        sendBroadcast(intent)
    }

    private fun hasHeartRatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            heartRatePermission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heart rate telemetry",
            NotificationManager.IMPORTANCE_LOW
        )

        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification(contentText: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Heart-rate sending")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.bikeheartrateapp.wear.action.START_HEART_RATE"
        const val ACTION_STOP = "com.example.bikeheartrateapp.wear.action.STOP_HEART_RATE"
        const val ACTION_STATUS = "com.example.bikeheartrateapp.wear.action.HEART_RATE_STATUS"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_BPM = "extra_bpm"
        const val EXTRA_RUNNING = "extra_running"
        const val MESSAGE_PATH = "/heart_rate"

        private const val CHANNEL_ID = "heart_rate_foreground"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        var isRunning = false
            private set
    }
}
