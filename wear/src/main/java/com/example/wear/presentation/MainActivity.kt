package com.example.bikeheartrateapp.wear.presentation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import com.google.android.gms.wearable.Wearable

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var bpmText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var measureClient: MeasureClient

    private var isMeasuring = false
    private var lastSentBpm = 0

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

            updateStatus(message)
        }

        override fun onDataReceived(data: DataPointContainer) {
            val heartRatePoints = data.getData(DataType.HEART_RATE_BPM)
            val latestPoint = heartRatePoints.lastOrNull() ?: return

            val bpm = latestPoint.value.toInt()
            if (bpm <= 0) return

            updateBpm("HR: $bpm bpm")
            updateStatus("Live HR received")

            if (bpm != lastSentBpm) {
                lastSentBpm = bpm
                sendBpmToPhone(bpm)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        measureClient = HealthServices.getClient(this).measureClient

        statusText = TextView(this).apply {
            text = "Ready"
            textSize = 15f
            gravity = Gravity.CENTER
        }

        bpmText = TextView(this).apply {
            text = "HR: -- bpm"
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val fakeButton = Button(this).apply {
            text = "Send Test BPM 132"
            setOnClickListener {
                sendBpmToPhone(132)
            }
        }

        startButton = Button(this).apply {
            text = "Start Live HR"
            setOnClickListener {
                startLiveHeartRate()
            }
        }

        stopButton = Button(this).apply {
            text = "Stop HR"
            setOnClickListener {
                stopLiveHeartRate()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)

            addView(statusText)
            addView(bpmText)
            addView(fakeButton)
            addView(startButton)
            addView(stopButton)
        }

        setContentView(layout)
    }

    override fun onDestroy() {
        stopLiveHeartRate()
        super.onDestroy()
    }

    private fun startLiveHeartRate() {
        if (!hasHeartRatePermission()) {
            updateStatus("Requesting HR permission...")

            requestPermissions(
                arrayOf(heartRatePermission),
                HEART_RATE_PERMISSION_REQUEST
            )

            return
        }

        checkHeartRateSupportAndStart()
    }

    private fun checkHeartRateSupportAndStart() {
        updateStatus("Checking HR support...")

        val capabilitiesFuture = measureClient.getCapabilitiesAsync()

        capabilitiesFuture.addListener(
            {
                try {
                    val capabilities = capabilitiesFuture.get()
                    val supportsHeartRate =
                        DataType.HEART_RATE_BPM in capabilities.supportedDataTypesMeasure

                    if (!supportsHeartRate) {
                        updateStatus("HR not supported")
                        return@addListener
                    }

                    registerHeartRateCallback()
                } catch (e: Exception) {
                    updateStatus("HR support error: ${e.message}")
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun registerHeartRateCallback() {
        if (isMeasuring) {
            updateStatus("Already measuring HR")
            return
        }

        try {
            updateStatus("Starting HR...")
            lastSentBpm = 0

            measureClient.registerMeasureCallback(
                DataType.HEART_RATE_BPM,
                heartRateCallback
            )

            isMeasuring = true
            updateStatus("Live HR started")
        } catch (e: Exception) {
            updateStatus("Start failed: ${e.message}")
        }
    }

    private fun stopLiveHeartRate() {
        if (!isMeasuring) return

        try {
            measureClient.unregisterMeasureCallbackAsync(
                DataType.HEART_RATE_BPM,
                heartRateCallback
            )

            isMeasuring = false
            updateStatus("Live HR stopped")
        } catch (e: Exception) {
            updateStatus("Stop failed: ${e.message}")
        }
    }

    private fun hasHeartRatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            heartRatePermission
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == HEART_RATE_PERMISSION_REQUEST) {
            val granted =
                grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED

            if (granted) {
                updateStatus("Permission granted")
                checkHeartRateSupportAndStart()
            } else {
                updateStatus("HR permission denied")
            }
        }
    }

    private fun sendBpmToPhone(bpm: Int) {
        updateStatus("Sending HR $bpm...")

        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    updateStatus("No phone connected")
                    return@addOnSuccessListener
                }

                nodes.forEach { node ->
                    Wearable.getMessageClient(this)
                        .sendMessage(
                            node.id,
                            "/heart_rate",
                            bpm.toString().toByteArray()
                        )
                        .addOnSuccessListener {
                            updateStatus("Sent HR $bpm")
                        }
                        .addOnFailureListener { error ->
                            updateStatus("Send failed: ${error.message}")
                        }
                }
            }
            .addOnFailureListener { error ->
                updateStatus("Node error: ${error.message}")
            }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            if (::statusText.isInitialized) {
                statusText.text = message
            }
        }
    }

    private fun updateBpm(message: String) {
        runOnUiThread {
            if (::bpmText.isInitialized) {
                bpmText.text = message
            }
        }
    }

    companion object {
        private const val HEART_RATE_PERMISSION_REQUEST = 1001
    }
}