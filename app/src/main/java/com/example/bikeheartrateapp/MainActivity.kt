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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bikeheartrateapp.ui.theme.BikeHeartRateAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BikeHeartRateAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HeartRatePublisherScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun HeartRatePublisherScreen(modifier: Modifier = Modifier) {
    var broker by remember { mutableStateOf("broker.hivemq.com") }
    var port by remember { mutableStateOf("1883") }
    var deviceId by remember { mutableStateOf("bike_001") }
    var sessionId by remember { mutableStateOf("session_070") }
    var heartRate by remember { mutableStateOf("132") }
    var status by remember { mutableStateOf("Ready") }

    val scope = rememberCoroutineScope()

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
            onValueChange = { broker = it },
            label = { Text("MQTT Broker") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = deviceId,
            onValueChange = { deviceId = it },
            label = { Text("Device ID") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = sessionId,
            onValueChange = { sessionId = it },
            label = { Text("Session ID") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = heartRate,
            onValueChange = { heartRate = it },
            label = { Text("Heart Rate BPM") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                scope.launch {
                    status = "Publishing..."
                    status = publishHeartRate(
                        broker = broker.trim(),
                        port = port.trim().toIntOrNull() ?: 1883,
                        deviceId = deviceId.trim(),
                        sessionId = sessionId.trim(),
                        heartRateBpm = heartRate.trim().toIntOrNull() ?: 0
                    )
                }
            },
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
    heartRateBpm: Int
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
                  "source": "android_phone_test"
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