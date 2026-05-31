package com.example.bikeheartrateapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val HEART_RATE_TOPIC = "anthony/bike_001/heart_rate"
private const val SESSION_TOPIC = "anthony/bike_001/session"
private const val COMMAND_TOPIC = "anthony/bike_001/commands"

private val VALID_WORKOUT_TYPES = setOf("speed", "cadence", "endurance", "vo2_max")

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private val broker = mutableStateOf("broker.hivemq.com")
    private val port = mutableStateOf("1883")
    private val deviceId = mutableStateOf("bike_001")

    private val athleteName = mutableStateOf("")
    private val athleteAge = mutableStateOf("")
    private val athleteWeightKg = mutableStateOf("")
    private val athleteHeightCm = mutableStateOf("")
    private val athleteEmail = mutableStateOf("")
    private val selectedWorkoutType = mutableStateOf("endurance")

    private val sessionId = mutableStateOf("Waiting for session...")
    private val workoutType = mutableStateOf("--")
    private val sessionStatus = mutableStateOf("No active session")

    private val heartRate = mutableStateOf("132")
    private val status = mutableStateOf("Ready")

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var sessionMqttClient: MqttClient? = null
    private var sessionClientKey: String? = null

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
                        athleteName = athleteName.value,
                        onAthleteNameChange = { athleteName.value = it },
                        athleteAge = athleteAge.value,
                        onAthleteAgeChange = { athleteAge.value = it },
                        athleteWeightKg = athleteWeightKg.value,
                        onAthleteWeightKgChange = { athleteWeightKg.value = it },
                        athleteHeightCm = athleteHeightCm.value,
                        onAthleteHeightCmChange = { athleteHeightCm.value = it },
                        athleteEmail = athleteEmail.value,
                        onAthleteEmailChange = { athleteEmail.value = it },
                        selectedWorkoutType = selectedWorkoutType.value,
                        onWorkoutTypeChange = { selectedWorkoutType.value = it },
                        sessionId = sessionId.value,
                        onSessionIdChange = { sessionId.value = it },
                        workoutType = workoutType.value,
                        sessionStatus = sessionStatus.value,
                        heartRate = heartRate.value,
                        onHeartRateChange = { heartRate.value = it },
                        status = status.value,
                        onStartWorkoutClick = { sendStartWorkoutCommand() },
                        onStopWorkoutClick = { sendStopWorkoutCommand() },
                        onPublishClick = { publishCurrentHeartRate("android_phone_manual") },
                        onReconnectSessionClick = { startSessionSync(forceReconnect = true) },
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
        startSessionSync(forceReconnect = false)
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        stopSessionSync()
        mainScope.cancel()
        super.onDestroy()
    }

    private fun sendStartWorkoutCommand() {
        val brokerValue = broker.value.trim()
        val portValue = port.value.trim().toIntOrNull()
        val deviceValue = deviceId.value.trim()
        val workoutTypeValue = selectedWorkoutType.value.trim()
        val nameValue = athleteName.value.trim()
        val ageValue = athleteAge.value.trim().toIntOrNull()
        val weightValue = athleteWeightKg.value.trim().toDoubleOrNull()
        val heightValue = athleteHeightCm.value.trim().toDoubleOrNull()
        val emailValue = athleteEmail.value.trim()

        val validationError = validateStartWorkoutInput(
            broker = brokerValue,
            port = portValue,
            deviceId = deviceValue,
            name = nameValue,
            age = ageValue,
            weightKg = weightValue,
            heightCm = heightValue,
            email = emailValue,
            workoutType = workoutTypeValue
        )

        if (validationError != null) {
            status.value = validationError
            return
        }

        val payload = buildStartWorkoutPayload(
            deviceId = deviceValue,
            workoutType = workoutTypeValue,
            name = nameValue,
            age = ageValue ?: return,
            weightKg = weightValue ?: return,
            heightCm = heightValue ?: return,
            email = emailValue
        ).toString()

        status.value = "Sending start command..."
        publishMqttCommand(
            broker = brokerValue,
            port = portValue ?: 1883,
            topic = COMMAND_TOPIC,
            payload = payload
        ) { success, message ->
            status.value = if (success) {
                "Start command sent"
            } else {
                "Failed to send start command: $message"
            }
        }
    }

    private fun sendStopWorkoutCommand() {
        val brokerValue = broker.value.trim()
        val portValue = port.value.trim().toIntOrNull()
        val deviceValue = deviceId.value.trim()

        when {
            brokerValue.isBlank() -> {
                status.value = "Validation failed: Broker is required"
                return
            }
            portValue == null -> {
                status.value = "Validation failed: Port must be a valid integer"
                return
            }
            deviceValue.isBlank() -> {
                status.value = "Validation failed: Device ID is required"
                return
            }
        }

        val payload = buildStopWorkoutPayload(deviceValue).toString()

        status.value = "Sending stop command..."
        publishMqttCommand(
            broker = brokerValue,
            port = portValue,
            topic = COMMAND_TOPIC,
            payload = payload
        ) { success, message ->
            status.value = if (success) {
                "Stop command sent"
            } else {
                "Failed to send stop command: $message"
            }
        }
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

    private fun startSessionSync(forceReconnect: Boolean) {
        val brokerValue = broker.value.trim()
        val portValue = port.value.trim().toIntOrNull() ?: 1883
        val key = "$brokerValue:$portValue"

        if (!forceReconnect && sessionMqttClient?.isConnected == true && sessionClientKey == key) {
            return
        }

        mainScope.launch {
            status.value = "Connecting session sync..."

            val result = withContext(Dispatchers.IO) {
                try {
                    stopSessionSyncInternal()

                    val serverUri = "tcp://$brokerValue:$portValue"
                    val clientId = "bike_hr_session_android_" + System.currentTimeMillis()
                    val client = MqttClient(serverUri, clientId, MemoryPersistence())

                    client.setCallback(object : MqttCallback {
                        override fun connectionLost(cause: Throwable?) {
                            mainScope.launch {
                                sessionStatus.value = "Session sync disconnected"
                                status.value = "Session MQTT lost: ${cause?.message ?: "unknown"}"
                            }
                        }

                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            if (topic != SESSION_TOPIC || message == null) return

                            val payload = String(message.payload)
                            handleSessionPayload(payload)
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {
                            // Not used for session subscription.
                        }
                    })

                    client.connect()
                    client.subscribe(SESSION_TOPIC, 0)

                    sessionMqttClient = client
                    sessionClientKey = key

                    "Session sync connected"
                } catch (e: Exception) {
                    "Session sync error: ${e.message}"
                }
            }

            status.value = result
        }
    }

    private fun handleSessionPayload(payload: String) {
        mainScope.launch {
            try {
                val json = JSONObject(payload)

                val incomingDeviceId = json.optString("device_id", "")
                val incomingSessionId = json.optString("session_id", "")
                val incomingWorkoutType = json.optString("workout_type", "--")
                val incomingStatus = json.optString("status", "")

                if (incomingDeviceId != deviceId.value.trim()) {
                    status.value = "Ignored session for $incomingDeviceId"
                    return@launch
                }

                if (incomingSessionId.isBlank()) {
                    status.value = "Invalid session payload: missing session_id"
                    return@launch
                }

                sessionId.value = incomingSessionId
                workoutType.value = incomingWorkoutType.ifBlank { "--" }

                if (incomingStatus == "active" || incomingStatus == "started") {
                    sessionStatus.value = "Active session synced"
                    status.value = "Synced $incomingSessionId ($incomingWorkoutType)"
                } else if (incomingStatus == "stopped") {
                    sessionStatus.value = "Session stopped"
                    status.value = "Session stopped: $incomingSessionId"
                } else {
                    sessionStatus.value = "Session status: $incomingStatus"
                    status.value = "Session updated: $incomingSessionId"
                }
            } catch (e: Exception) {
                status.value = "Session parse error: ${e.message}"
            }
        }
    }

    private fun stopSessionSync() {
        mainScope.launch(Dispatchers.IO) {
            stopSessionSyncInternal()
        }
    }

    private fun stopSessionSyncInternal() {
        try {
            sessionMqttClient?.let { client ->
                if (client.isConnected) {
                    client.unsubscribe(SESSION_TOPIC)
                    client.disconnect()
                }
                client.close()
            }
        } catch (_: Exception) {
            // Ignore cleanup errors.
        } finally {
            sessionMqttClient = null
            sessionClientKey = null
        }
    }

    private fun publishCurrentHeartRate(source: String) {
        val brokerValue = broker.value.trim()
        val portValue = port.value.trim().toIntOrNull() ?: 1883
        val deviceValue = deviceId.value.trim()
        val sessionValue = sessionId.value.trim()
        val bpmValue = heartRate.value.trim().toIntOrNull() ?: 0

        if (sessionValue.isBlank() || sessionValue == "Waiting for session...") {
            status.value = "No synced session yet"
            return
        }

        if (sessionStatus.value != "Active session synced") {
            status.value = "No active session. Current status: ${sessionStatus.value}"
            return
        }

        mainScope.launch {
            status.value = "Publishing BPM $bpmValue to $sessionValue..."
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

    private fun validateStartWorkoutInput(
        broker: String,
        port: Int?,
        deviceId: String,
        name: String,
        age: Int?,
        weightKg: Double?,
        heightCm: Double?,
        email: String,
        workoutType: String
    ): String? {
        return when {
            broker.isBlank() -> "Validation failed: Broker is required"
            port == null -> "Validation failed: Port must be a valid integer"
            deviceId.isBlank() -> "Validation failed: Device ID is required"
            name.isBlank() -> "Validation failed: Name is required"
            age == null -> "Validation failed: Age must be a valid integer"
            weightKg == null -> "Validation failed: Weight must be a valid number"
            heightCm == null -> "Validation failed: Height must be a valid number"
            email.isBlank() -> "Validation failed: Email is required"
            workoutType !in VALID_WORKOUT_TYPES -> {
                "Validation failed: Workout type must be speed, cadence, endurance, or vo2_max"
            }
            else -> null
        }
    }

    private fun buildStartWorkoutPayload(
        deviceId: String,
        workoutType: String,
        name: String,
        age: Int,
        weightKg: Double,
        heightCm: Double,
        email: String
    ): JSONObject {
        val athlete = JSONObject()
            .put("name", name)
            .put("age", age)
            .put("weight_kg", weightKg)
            .put("height_cm", heightCm)
            .put("email", email)

        return JSONObject()
            .put("command", "start_workout")
            .put("device_id", deviceId)
            .put("workout_type", workoutType)
            .put("mode", "real")
            .put("athlete", athlete)
    }

    private fun buildStopWorkoutPayload(deviceId: String): JSONObject {
        return JSONObject()
            .put("command", "stop_workout")
            .put("device_id", deviceId)
    }

    private fun publishMqttCommand(
        broker: String,
        port: Int,
        topic: String,
        payload: String,
        onResult: (Boolean, String) -> Unit
    ) {
        mainScope.launch {
            val result = withContext(Dispatchers.IO) {
                var client: MqttClient? = null

                try {
                    val serverUri = "tcp://$broker:$port"
                    val clientId = "bike_command_android_" + System.currentTimeMillis()
                    client = MqttClient(serverUri, clientId, MemoryPersistence())

                    client.connect()

                    val message = MqttMessage(payload.toByteArray())
                    message.qos = 0
                    message.isRetained = false

                    client.publish(topic, message)

                    true to "Command sent"
                } catch (e: Exception) {
                    false to (e.message ?: "unknown error")
                } finally {
                    try {
                        client?.let {
                            if (it.isConnected) {
                                it.disconnect()
                            }
                            it.close()
                        }
                    } catch (_: Exception) {
                        // Ignore cleanup errors.
                    }
                }
            }

            onResult(result.first, result.second)
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
    athleteName: String,
    onAthleteNameChange: (String) -> Unit,
    athleteAge: String,
    onAthleteAgeChange: (String) -> Unit,
    athleteWeightKg: String,
    onAthleteWeightKgChange: (String) -> Unit,
    athleteHeightCm: String,
    onAthleteHeightCmChange: (String) -> Unit,
    athleteEmail: String,
    onAthleteEmailChange: (String) -> Unit,
    selectedWorkoutType: String,
    onWorkoutTypeChange: (String) -> Unit,
    sessionId: String,
    onSessionIdChange: (String) -> Unit,
    workoutType: String,
    sessionStatus: String,
    heartRate: String,
    onHeartRateChange: (String) -> Unit,
    status: String,
    onStartWorkoutClick: () -> Unit,
    onStopWorkoutClick: () -> Unit,
    onPublishClick: () -> Unit,
    onReconnectSessionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Bike Trainer Workout",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "MQTT Settings",
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = broker,
            onValueChange = onBrokerChange,
            label = { Text("Broker") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = port,
            onValueChange = onPortChange,
            label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = deviceId,
            onValueChange = onDeviceIdChange,
            label = { Text("Device ID") },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Athlete Info",
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = athleteName,
            onValueChange = onAthleteNameChange,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = athleteAge,
            onValueChange = onAthleteAgeChange,
            label = { Text("Age") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = athleteWeightKg,
            onValueChange = onAthleteWeightKgChange,
            label = { Text("Weight in kg") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = athleteHeightCm,
            onValueChange = onAthleteHeightCmChange,
            label = { Text("Height in cm") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = athleteEmail,
            onValueChange = onAthleteEmailChange,
            label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Workout Type",
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            WorkoutTypeButton(
                label = "speed",
                selected = selectedWorkoutType == "speed",
                onClick = { onWorkoutTypeChange("speed") },
                modifier = Modifier.weight(1f)
            )
            WorkoutTypeButton(
                label = "cadence",
                selected = selectedWorkoutType == "cadence",
                onClick = { onWorkoutTypeChange("cadence") },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            WorkoutTypeButton(
                label = "endurance",
                selected = selectedWorkoutType == "endurance",
                onClick = { onWorkoutTypeChange("endurance") },
                modifier = Modifier.weight(1f)
            )
            WorkoutTypeButton(
                label = "vo2_max",
                selected = selectedWorkoutType == "vo2_max",
                onClick = { onWorkoutTypeChange("vo2_max") },
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = onStartWorkoutClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Workout")
        }

        Button(
            onClick = onStopWorkoutClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("End Workout")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Session Sync",
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = sessionId,
            onValueChange = onSessionIdChange,
            label = { Text("Session ID - auto synced") },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Workout: $workoutType",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "Session: $sessionStatus",
            style = MaterialTheme.typography.bodyLarge
        )

        Button(
            onClick = onReconnectSessionClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reconnect Session Sync")
        }

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

@Composable
private fun WorkoutTypeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier
        ) {
            Text(label)
        }
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

            client.publish(HEART_RATE_TOPIC, message)
            client.disconnect()
            client.close()

            "Published HR $heartRateBpm bpm to MQTT for $sessionId"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
