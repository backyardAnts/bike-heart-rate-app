package com.example.bikeheartrateapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bikeheartrateapp.ui.theme.BikeHeartRateAppTheme
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private val broker = mutableStateOf("broker.hivemq.com")
    private val port = mutableStateOf("1883")
    private val deviceId = mutableStateOf("bike_001")
    private val sessionId = mutableStateOf("session_070")
    private val heartRate = mutableStateOf("132")
    private val status = mutableStateOf("Ready")

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            BikeHeartRateAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HeartRatePublisherScreen(
                        broker = broker.value,
                        onBrokerChange = { broker.value = it },
                        port = port.value,
                        onPortChange = { port.value = it },
                        deviceId = deviceId.value,
                        onDeviceIdChange = { deviceId.value = it },
                        sessionId = sessionId.value,
                        onSessionIdChange = { sessionId.value = it },
                        heartRate = heartRate.value,
                        onHeartRateChange = { heartRate.value = it },
                        status = status.value,
                        onPublishClick = { publishCurrentHeartRate("android_phone_manual") },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
        status.value = "Ready - listening for watch BPM"
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != "/heart_rate") return

        val bpmText = String(messageEvent.data).trim()
        val bpm = bpmText.toIntOrNull()

        mainScope.launch {
            if (bpm == null || bpm <= 0) {
                status.value = "Invalid BPM from watch: $bpmText"
                return@launch
            }

            heartRate.value = bpm.toString()
            status.value = "Received BPM $bpm from watch. Publishing..."
            publishCurrentHeartRate("samsung_watch_5_pro")
        }
    }

    private fun publishCurrentHeartRate(source: String) {
        val brokerValue = broker.value.trim()
        val portValue = port.value.trim().toIntOrNull() ?: 1883
        val deviceValue = deviceId.value.trim()
        val sessionValue = sessionId.value.trim()
        val bpmValue = heartRate.value.trim().toIntOrNull() ?: 0

        mainScope.launch {
            status.value = "Publishing BPM $bpmValue..."
            status.value = publishHeartRate(
                broker = brokerValue,
                port = portValue,
                deviceId = deviceValue,
                sessionId = sessionValue,
                heartRateBpm = bpmValue,
                source = source
            )
        }
    }
}

@Composable
fun HeartRatePublisherScreen(
    broker: String,
    onBrokerChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    deviceId: String,
    onDeviceIdChange: (String) -> Unit,
    sessionId: String,
    onSessionIdChange: (String) -> Unit,
    heartRate: String,
    onHeartRateChange: (String) -> Unit,
    status: String,
    onPublishClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Bike Heart Rate MQTT",
            style = MaterialTheme.typography.headlineSmall
        )

        OutlinedTextField(
            value = broker,
            onValueChange = onBrokerChange,
            label = { Text("MQTT Broker") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = port,
            onValueChange = onPortChange,
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = deviceId,
            onValueChange = onDeviceIdChange,
            label = { Text("Device ID") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = sessionId,
            onValueChange = onSessionIdChange,
            label = { Text("Session ID") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = heartRate,
            onValueChange = onHeartRateChange,
            label = { Text("Heart Rate BPM") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onPublishClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Publish Test Heart Rate")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Status: $status",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

suspend fun publishHeartRate(
    broker: String,
    port: Int,
    deviceId: String,
    sessionId: String,
    heartRateBpm: Int,
    source: String
): String {
    return withContext(Dispatchers.IO) {
        try {
            val serverUri = "tcp://$broker:$port"
            val clientId = "bike_hr_android_" + System.currentTimeMillis()
            val client = MqttClient(serverUri, clientId, MemoryPersistence())

            client.connect()

            val timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            val payload = """
                {
                  "device_id": "$deviceId",
                  "session_id": "$sessionId",
                  "timestamp": "$timestamp",
                  "heart_rate_bpm": $heartRateBpm,
                  "source": "$source"
                }
            """.trimIndent()

            val message = MqttMessage(payload.toByteArray())
            message.qos = 0
            message.isRetained = false

            client.publish("anthony/bike_001/heart_rate", message)
            client.disconnect()
            client.close()

            "Published HR $heartRateBpm bpm to MQTT"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}