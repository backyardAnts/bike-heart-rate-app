package com.example.bikeheartrateapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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

private const val DEFAULT_BROKER = "broker.hivemq.com"
private const val DEFAULT_PORT = "1883"
private const val DEFAULT_DEVICE_ID = "bike_001"
private const val DEFAULT_HEART_RATE_TOPIC = "anthony/bike_001/heart_rate"
private const val DEFAULT_SESSION_TOPIC = "anthony/bike_001/session"
private const val DEFAULT_COMMAND_TOPIC = "anthony/bike_001/commands"
private const val DEFAULT_TELEMETRY_TOPIC = "anthony/bike_001/merged_sensors"

private val VALID_WORKOUT_TYPES = setOf("speed", "cadence", "endurance", "vo2_max")

private enum class AppPage(val label: String) {
    Settings("Settings"),
    Athlete("Athlete"),
    HrTest("HR Test"),
    Dashboard("Dashboard")
}

private data class TelemetryValues(
    val sessionId: String = "--",
    val workoutStatus: String = "--",
    val workoutType: String = "--",
    val speedKmh: String = "--",
    val cadenceRpm: String = "--",
    val heartRateBpm: String = "--",
    val leftDistanceM: String = "--",
    val rightDistanceM: String = "--",
    val temperatureC: String = "--",
    val mode: String = "--",
    val recommendation: String = "--",
    val lastUpdate: String = "--"
)

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private val currentPage = mutableStateOf(AppPage.Dashboard)

    private val broker = mutableStateOf(DEFAULT_BROKER)
    private val port = mutableStateOf(DEFAULT_PORT)
    private val deviceId = mutableStateOf(DEFAULT_DEVICE_ID)
    private val sessionTopic = mutableStateOf(DEFAULT_SESSION_TOPIC)
    private val commandTopic = mutableStateOf(DEFAULT_COMMAND_TOPIC)
    private val telemetryTopic = mutableStateOf(DEFAULT_TELEMETRY_TOPIC)
    private val heartRateTopic = mutableStateOf(DEFAULT_HEART_RATE_TOPIC)

    private val athleteName = mutableStateOf("")
    private val athleteAge = mutableStateOf("")
    private val athleteWeightKg = mutableStateOf("")
    private val athleteHeightCm = mutableStateOf("")
    private val athleteEmail = mutableStateOf("")
    private val selectedWorkoutType = mutableStateOf("endurance")

    private val sessionId = mutableStateOf("Waiting for session...")
    private val syncedWorkoutType = mutableStateOf("--")
    private val sessionStatus = mutableStateOf("No active session")

    private val manualHeartRate = mutableStateOf("132")
    private val manualHrSessionId = mutableStateOf("")

    private val telemetry = mutableStateOf(TelemetryValues())
    private val settingsStatus = mutableStateOf("Ready")
    private val athleteStatus = mutableStateOf("Ready")
    private val hrTestStatus = mutableStateOf("Ready")
    private val dashboardStatus = mutableStateOf("Ready")
    private val telemetryStatus = mutableStateOf("Waiting for telemetry...")

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var subscriberClient: MqttClient? = null
    private var subscriberClientKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            BikeHeartRateAppTheme {
                BikeTrainerApp(
                    currentPage = currentPage.value,
                    onPageSelected = { currentPage.value = it },
                    broker = broker.value,
                    onBrokerChange = { broker.value = it },
                    port = port.value,
                    onPortChange = { port.value = it },
                    deviceId = deviceId.value,
                    onDeviceIdChange = { deviceId.value = it },
                    sessionTopic = sessionTopic.value,
                    onSessionTopicChange = { sessionTopic.value = it },
                    commandTopic = commandTopic.value,
                    onCommandTopicChange = { commandTopic.value = it },
                    telemetryTopic = telemetryTopic.value,
                    onTelemetryTopicChange = { telemetryTopic.value = it },
                    heartRateTopic = heartRateTopic.value,
                    onHeartRateTopicChange = { heartRateTopic.value = it },
                    sessionId = sessionId.value,
                    syncedWorkoutType = syncedWorkoutType.value,
                    sessionStatus = sessionStatus.value,
                    settingsStatus = settingsStatus.value,
                    onSaveSettingsClick = { saveSettings() },
                    onReconnectSessionClick = { startMqttSubscriptions(forceReconnect = true) },
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
                    athleteStatus = athleteStatus.value,
                    onSaveAthleteClick = { saveAthlete() },
                    manualHeartRate = manualHeartRate.value,
                    onManualHeartRateChange = { manualHeartRate.value = it },
                    manualHrSessionId = manualHrSessionId.value,
                    onManualHrSessionIdChange = { manualHrSessionId.value = it },
                    hrTestStatus = hrTestStatus.value,
                    onPublishTestHrClick = { publishManualHeartRate() },
                    telemetry = telemetry.value,
                    telemetryStatus = telemetryStatus.value,
                    dashboardStatus = dashboardStatus.value,
                    onStartWorkoutClick = { sendStartWorkoutCommand() },
                    onStopWorkoutClick = { sendStopWorkoutCommand() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
        startMqttSubscriptions(forceReconnect = false)
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        stopMqttSubscriptions()
        mainScope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != "/heart_rate") return

        val bpmText = String(messageEvent.data).trim()
        val bpm = bpmText.toIntOrNull()

        mainScope.launch {
            if (bpm == null || bpm <= 0) {
                hrTestStatus.value = "Invalid BPM from watch: $bpmText"
                return@launch
            }

            manualHeartRate.value = bpm.toString()
            hrTestStatus.value = "Received BPM $bpm from watch. Publishing..."
            publishCurrentHeartRate("samsung_watch_5_pro")
        }
    }

    private fun saveSettings() {
        val validationError = validateSettings(requireCommandTopic = false)

        if (validationError != null) {
            settingsStatus.value = validationError
            return
        }

        settingsStatus.value = "Settings saved"
        startMqttSubscriptions(forceReconnect = true)
    }

    private fun saveAthlete() {
        athleteStatus.value = validateAthleteInput() ?: "Athlete saved"
    }

    private fun sendStartWorkoutCommand() {
        val portValue = port.value.trim().toIntOrNull()
        val validationError = validateSettings(requireCommandTopic = true)
            ?: validateAthleteInput()

        if (validationError != null || portValue == null) {
            dashboardStatus.value = validationError ?: "Invalid port"
            return
        }

        val payload = buildStartWorkoutPayload(
            deviceId = deviceId.value.trim(),
            workoutType = selectedWorkoutType.value.trim(),
            name = athleteName.value.trim(),
            age = athleteAge.value.trim().toInt(),
            weightKg = athleteWeightKg.value.trim().toDouble(),
            heightCm = athleteHeightCm.value.trim().toDouble(),
            email = athleteEmail.value.trim()
        ).toString()

        dashboardStatus.value = "Sending start command..."
        publishMqttJson(
            broker = broker.value.trim(),
            port = portValue,
            topic = commandTopic.value.trim(),
            payload = payload
        ) { success, message ->
            dashboardStatus.value = if (success) {
                "Start command sent"
            } else {
                "Failed to send start command: $message"
            }
        }
    }

    private fun sendStopWorkoutCommand() {
        val portValue = port.value.trim().toIntOrNull()
        val validationError = validateSettings(requireCommandTopic = true)

        if (validationError != null || portValue == null) {
            dashboardStatus.value = validationError ?: "Invalid port"
            return
        }

        val payload = buildStopWorkoutPayload(deviceId.value.trim()).toString()

        dashboardStatus.value = "Sending stop command..."
        publishMqttJson(
            broker = broker.value.trim(),
            port = portValue,
            topic = commandTopic.value.trim(),
            payload = payload
        ) { success, message ->
            dashboardStatus.value = if (success) {
                "Stop command sent"
            } else {
                "Failed to send stop command: $message"
            }
        }
    }

    private fun publishManualHeartRate() {
        val brokerValue = broker.value.trim()
        val portValue = port.value.trim().toIntOrNull()
        val deviceValue = deviceId.value.trim()
        val heartRateTopicValue = heartRateTopic.value.trim()
        val bpmValue = manualHeartRate.value.trim().toIntOrNull()
        val sessionValue = manualHrSessionId.value.trim().ifBlank {
            sessionId.value.trim().takeUnless { it == "Waiting for session..." } ?: ""
        }

        when {
            brokerValue.isBlank() -> {
                hrTestStatus.value = "Broker is required"
                return
            }
            portValue == null -> {
                hrTestStatus.value = "Invalid port"
                return
            }
            deviceValue.isBlank() -> {
                hrTestStatus.value = "Device ID is required"
                return
            }
            heartRateTopicValue.isBlank() -> {
                hrTestStatus.value = "Heart-rate topic is required"
                return
            }
            sessionValue.isBlank() -> {
                hrTestStatus.value = "Session ID is required for HR test"
                return
            }
            bpmValue == null || bpmValue <= 0 -> {
                hrTestStatus.value = "Heart rate must be a valid positive integer"
                return
            }
        }

        mainScope.launch {
            hrTestStatus.value = "Publishing test HR $bpmValue..."
            hrTestStatus.value = publishHeartRate(
                broker = brokerValue,
                port = portValue,
                topic = heartRateTopicValue,
                deviceId = deviceValue,
                sessionId = sessionValue,
                heartRateBpm = bpmValue,
                source = "android_phone_manual"
            )
        }
    }

    private fun publishCurrentHeartRate(source: String) {
        val brokerValue = broker.value.trim()
        val portValue = port.value.trim().toIntOrNull()
        val deviceValue = deviceId.value.trim()
        val heartRateTopicValue = heartRateTopic.value.trim()
        val sessionValue = sessionId.value.trim()
        val bpmValue = manualHeartRate.value.trim().toIntOrNull() ?: 0

        if (portValue == null) {
            hrTestStatus.value = "Invalid port"
            return
        }

        if (sessionValue.isBlank() || sessionValue == "Waiting for session...") {
            hrTestStatus.value = "No synced session yet"
            return
        }

        if (sessionStatus.value != "Active session synced") {
            hrTestStatus.value = "No active session. Current status: ${sessionStatus.value}"
            return
        }

        mainScope.launch {
            hrTestStatus.value = "Publishing BPM $bpmValue to $sessionValue..."
            hrTestStatus.value = publishHeartRate(
                broker = brokerValue,
                port = portValue,
                topic = heartRateTopicValue,
                deviceId = deviceValue,
                sessionId = sessionValue,
                heartRateBpm = bpmValue,
                source = source
            )
        }
    }

    private fun startMqttSubscriptions(forceReconnect: Boolean) {
        val brokerValue = broker.value.trim()
        val portValue = port.value.trim().toIntOrNull()
        val sessionTopicValue = sessionTopic.value.trim()
        val telemetryTopicValue = telemetryTopic.value.trim()

        if (brokerValue.isBlank()) {
            telemetryStatus.value = "Broker is required"
            return
        }

        if (portValue == null) {
            telemetryStatus.value = "Invalid port"
            return
        }

        if (sessionTopicValue.isBlank() && telemetryTopicValue.isBlank()) {
            telemetryStatus.value = "No MQTT topics configured"
            return
        }

        val key = "$brokerValue:$portValue:$sessionTopicValue:$telemetryTopicValue"

        if (!forceReconnect && subscriberClient?.isConnected == true && subscriberClientKey == key) {
            return
        }

        mainScope.launch {
            telemetryStatus.value = "Connecting telemetry..."

            val result = withContext(Dispatchers.IO) {
                try {
                    stopMqttSubscriptionsInternal()

                    val serverUri = "tcp://$brokerValue:$portValue"
                    val clientId = "bike_phone_subscriber_" + System.currentTimeMillis()
                    val client = MqttClient(serverUri, clientId, MemoryPersistence())

                    client.setCallback(object : MqttCallback {
                        override fun connectionLost(cause: Throwable?) {
                            mainScope.launch {
                                telemetryStatus.value =
                                    "Telemetry disconnected: ${cause?.message ?: "unknown"}"
                            }
                        }

                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            if (topic == null || message == null) return

                            val payload = String(message.payload)

                            when (topic) {
                                sessionTopicValue -> handleSessionPayload(payload)
                                telemetryTopicValue -> handleTelemetryPayload(payload)
                            }
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {
                            // This client only subscribes.
                        }
                    })

                    client.connect()

                    if (sessionTopicValue.isNotBlank()) {
                        client.subscribe(sessionTopicValue, 0)
                    }

                    if (telemetryTopicValue.isNotBlank()) {
                        client.subscribe(telemetryTopicValue, 0)
                    }

                    subscriberClient = client
                    subscriberClientKey = key

                    "Telemetry connected"
                } catch (e: Exception) {
                    "Telemetry error: ${e.message}"
                }
            }

            telemetryStatus.value = result
        }
    }

    private fun stopMqttSubscriptions() {
        mainScope.launch(Dispatchers.IO) {
            stopMqttSubscriptionsInternal()
        }
    }

    private fun stopMqttSubscriptionsInternal() {
        try {
            subscriberClient?.let { client ->
                if (client.isConnected) {
                    client.disconnect()
                }
                client.close()
            }
        } catch (_: Exception) {
            // Ignore cleanup errors.
        } finally {
            subscriberClient = null
            subscriberClientKey = null
        }
    }

    private fun handleSessionPayload(payload: String) {
        mainScope.launch {
            try {
                val json = JSONObject(payload)

                val incomingDeviceId = json.optCleanString("device_id")
                val incomingSessionId = json.optCleanString("session_id")
                val incomingWorkoutType = json.optDisplayString("workout_type")
                val incomingStatus = json.optCleanString("status")

                if (incomingDeviceId.isNotBlank() && incomingDeviceId != deviceId.value.trim()) {
                    settingsStatus.value = "Ignored session for $incomingDeviceId"
                    return@launch
                }

                if (incomingSessionId.isBlank()) {
                    settingsStatus.value = "Invalid session payload: missing session_id"
                    return@launch
                }

                sessionId.value = incomingSessionId
                manualHrSessionId.value = manualHrSessionId.value.ifBlank { incomingSessionId }
                syncedWorkoutType.value = incomingWorkoutType

                when (incomingStatus) {
                    "active", "started" -> {
                        sessionStatus.value = "Active session synced"
                        settingsStatus.value = "Synced $incomingSessionId ($incomingWorkoutType)"
                    }
                    "stopped" -> {
                        sessionStatus.value = "Session stopped"
                        settingsStatus.value = "Session stopped: $incomingSessionId"
                    }
                    else -> {
                        sessionStatus.value = incomingStatus.ifBlank { "Session updated" }
                        settingsStatus.value = "Session updated: $incomingSessionId"
                    }
                }
            } catch (e: Exception) {
                settingsStatus.value = "Session parse error: ${e.message}"
            }
        }
    }

    private fun handleTelemetryPayload(payload: String) {
        mainScope.launch {
            try {
                val root = JSONObject(payload)
                val json = root.optJSONObject("telemetry")
                    ?: root.optJSONObject("data")
                    ?: root

                val nextTelemetry = TelemetryValues(
                    sessionId = json.optDisplayString("session_id", "sessionId"),
                    workoutStatus = json.optDisplayString("status", "workout_status", "state"),
                    workoutType = json.optDisplayString("workout_type", "workoutType"),
                    speedKmh = json.optDisplayString("speed_kmh", "speed_km_h", "speed"),
                    cadenceRpm = json.optDisplayString("cadence_rpm", "cadence"),
                    heartRateBpm = json.optDisplayString("heart_rate_bpm", "heart_rate", "bpm"),
                    leftDistanceM = json.optDisplayString(
                        "left_distance_m",
                        "left_distance",
                        "distance_left_m"
                    ),
                    rightDistanceM = json.optDisplayString(
                        "right_distance_m",
                        "right_distance",
                        "distance_right_m"
                    ),
                    temperatureC = json.optDisplayString("temperature_c", "temp_c", "temperature"),
                    mode = json.optDisplayString("mode"),
                    recommendation = json.optDisplayString(
                        "recommendation",
                        "decision",
                        "latest_recommendation",
                        "latest_decision"
                    ),
                    lastUpdate = json.optDisplayString("timestamp", "last_update", "updated_at")
                )

                telemetry.value = nextTelemetry
                telemetryStatus.value = "Telemetry connected"

                nextTelemetry.sessionId.takeUnless { it == "--" }?.let {
                    sessionId.value = it
                    manualHrSessionId.value = manualHrSessionId.value.ifBlank { it }
                }

                nextTelemetry.workoutType.takeUnless { it == "--" }?.let {
                    syncedWorkoutType.value = it
                }

                nextTelemetry.workoutStatus.takeUnless { it == "--" }?.let {
                    sessionStatus.value = it
                }
            } catch (e: Exception) {
                telemetryStatus.value = "Telemetry parse error: ${e.message}"
            }
        }
    }

    private fun validateSettings(requireCommandTopic: Boolean): String? {
        return when {
            broker.value.trim().isBlank() -> "Broker is required"
            port.value.trim().toIntOrNull() == null -> "Invalid port"
            deviceId.value.trim().isBlank() -> "Device ID is required"
            sessionTopic.value.trim().isBlank() -> "Session sync topic is required"
            telemetryTopic.value.trim().isBlank() -> "Telemetry topic is required"
            heartRateTopic.value.trim().isBlank() -> "Heart-rate topic is required"
            requireCommandTopic && commandTopic.value.trim().isBlank() -> {
                "Commands topic is required"
            }
            else -> null
        }
    }

    private fun validateAthleteInput(): String? {
        return when {
            athleteName.value.trim().isBlank() -> "Name cannot be empty"
            athleteAge.value.trim().toIntOrNull() == null -> "Age must be a valid integer"
            athleteWeightKg.value.trim().toDoubleOrNull() == null -> {
                "Weight must be a valid number"
            }
            athleteHeightCm.value.trim().toDoubleOrNull() == null -> {
                "Height must be a valid number"
            }
            athleteEmail.value.trim().isBlank() -> "Email cannot be empty"
            selectedWorkoutType.value.trim() !in VALID_WORKOUT_TYPES -> {
                "Workout type must be speed, cadence, endurance, or vo2_max"
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

    private fun publishMqttJson(
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
                    val clientId = "bike_phone_publish_" + System.currentTimeMillis()
                    client = MqttClient(serverUri, clientId, MemoryPersistence())

                    client.connect()

                    val message = MqttMessage(payload.toByteArray())
                    message.qos = 0
                    message.isRetained = false

                    client.publish(topic, message)

                    true to "Message sent"
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
private fun BikeTrainerApp(
    currentPage: AppPage,
    onPageSelected: (AppPage) -> Unit,
    broker: String,
    onBrokerChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    deviceId: String,
    onDeviceIdChange: (String) -> Unit,
    sessionTopic: String,
    onSessionTopicChange: (String) -> Unit,
    commandTopic: String,
    onCommandTopicChange: (String) -> Unit,
    telemetryTopic: String,
    onTelemetryTopicChange: (String) -> Unit,
    heartRateTopic: String,
    onHeartRateTopicChange: (String) -> Unit,
    sessionId: String,
    syncedWorkoutType: String,
    sessionStatus: String,
    settingsStatus: String,
    onSaveSettingsClick: () -> Unit,
    onReconnectSessionClick: () -> Unit,
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
    athleteStatus: String,
    onSaveAthleteClick: () -> Unit,
    manualHeartRate: String,
    onManualHeartRateChange: (String) -> Unit,
    manualHrSessionId: String,
    onManualHrSessionIdChange: (String) -> Unit,
    hrTestStatus: String,
    onPublishTestHrClick: () -> Unit,
    telemetry: TelemetryValues,
    telemetryStatus: String,
    dashboardStatus: String,
    onStartWorkoutClick: () -> Unit,
    onStopWorkoutClick: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomNavigationBar(
                currentPage = currentPage,
                onPageSelected = onPageSelected
            )
        }
    ) { innerPadding ->
        when (currentPage) {
            AppPage.Settings -> SettingsScreen(
                broker = broker,
                onBrokerChange = onBrokerChange,
                port = port,
                onPortChange = onPortChange,
                deviceId = deviceId,
                onDeviceIdChange = onDeviceIdChange,
                sessionTopic = sessionTopic,
                onSessionTopicChange = onSessionTopicChange,
                commandTopic = commandTopic,
                onCommandTopicChange = onCommandTopicChange,
                telemetryTopic = telemetryTopic,
                onTelemetryTopicChange = onTelemetryTopicChange,
                heartRateTopic = heartRateTopic,
                onHeartRateTopicChange = onHeartRateTopicChange,
                sessionId = sessionId,
                workoutType = syncedWorkoutType,
                sessionStatus = sessionStatus,
                settingsStatus = settingsStatus,
                onSaveSettingsClick = onSaveSettingsClick,
                onReconnectSessionClick = onReconnectSessionClick,
                modifier = Modifier.padding(innerPadding)
            )

            AppPage.Athlete -> AthleteScreen(
                athleteName = athleteName,
                onAthleteNameChange = onAthleteNameChange,
                athleteAge = athleteAge,
                onAthleteAgeChange = onAthleteAgeChange,
                athleteWeightKg = athleteWeightKg,
                onAthleteWeightKgChange = onAthleteWeightKgChange,
                athleteHeightCm = athleteHeightCm,
                onAthleteHeightCmChange = onAthleteHeightCmChange,
                athleteEmail = athleteEmail,
                onAthleteEmailChange = onAthleteEmailChange,
                selectedWorkoutType = selectedWorkoutType,
                onWorkoutTypeChange = onWorkoutTypeChange,
                athleteStatus = athleteStatus,
                onSaveAthleteClick = onSaveAthleteClick,
                modifier = Modifier.padding(innerPadding)
            )

            AppPage.HrTest -> HrTestScreen(
                manualHeartRate = manualHeartRate,
                onManualHeartRateChange = onManualHeartRateChange,
                manualHrSessionId = manualHrSessionId,
                onManualHrSessionIdChange = onManualHrSessionIdChange,
                syncedSessionId = sessionId,
                heartRateTopic = heartRateTopic,
                hrTestStatus = hrTestStatus,
                onPublishTestHrClick = onPublishTestHrClick,
                modifier = Modifier.padding(innerPadding)
            )

            AppPage.Dashboard -> DashboardScreen(
                sessionId = sessionId,
                sessionStatus = sessionStatus,
                syncedWorkoutType = syncedWorkoutType,
                telemetry = telemetry,
                telemetryStatus = telemetryStatus,
                dashboardStatus = dashboardStatus,
                onStartWorkoutClick = onStartWorkoutClick,
                onStopWorkoutClick = onStopWorkoutClick,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@Composable
private fun BottomNavigationBar(
    currentPage: AppPage,
    onPageSelected: (AppPage) -> Unit
) {
    NavigationBar {
        AppPage.values().forEach { page ->
            NavigationBarItem(
                selected = currentPage == page,
                onClick = { onPageSelected(page) },
                icon = {
                    Icon(
                        imageVector = page.icon(),
                        contentDescription = page.label
                    )
                },
                label = { Text(page.label) }
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    broker: String,
    onBrokerChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    deviceId: String,
    onDeviceIdChange: (String) -> Unit,
    sessionTopic: String,
    onSessionTopicChange: (String) -> Unit,
    commandTopic: String,
    onCommandTopicChange: (String) -> Unit,
    telemetryTopic: String,
    onTelemetryTopicChange: (String) -> Unit,
    heartRateTopic: String,
    onHeartRateTopicChange: (String) -> Unit,
    sessionId: String,
    workoutType: String,
    sessionStatus: String,
    settingsStatus: String,
    onSaveSettingsClick: () -> Unit,
    onReconnectSessionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ScreenColumn(modifier = modifier) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall
        )

        TextFieldRow(
            value = broker,
            onValueChange = onBrokerChange,
            label = "Broker"
        )

        TextFieldRow(
            value = port,
            onValueChange = onPortChange,
            label = "Port",
            keyboardType = KeyboardType.Number
        )

        TextFieldRow(
            value = deviceId,
            onValueChange = onDeviceIdChange,
            label = "Device ID"
        )

        TextFieldRow(
            value = sessionTopic,
            onValueChange = onSessionTopicChange,
            label = "Session sync topic"
        )

        TextFieldRow(
            value = commandTopic,
            onValueChange = onCommandTopicChange,
            label = "Commands topic"
        )

        TextFieldRow(
            value = telemetryTopic,
            onValueChange = onTelemetryTopicChange,
            label = "Telemetry topic"
        )

        TextFieldRow(
            value = heartRateTopic,
            onValueChange = onHeartRateTopicChange,
            label = "Heart-rate topic"
        )

        Button(
            onClick = onSaveSettingsClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Settings")
        }

        OutlinedButton(
            onClick = onReconnectSessionClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reconnect Session Sync")
        }

        Text(
            text = "Session ID: $sessionId",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "Workout type: $workoutType",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "Status: $sessionStatus",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "Settings status: $settingsStatus",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun AthleteScreen(
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
    athleteStatus: String,
    onSaveAthleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ScreenColumn(modifier = modifier) {
        Text(
            text = "Athlete",
            style = MaterialTheme.typography.headlineSmall
        )

        TextFieldRow(
            value = athleteName,
            onValueChange = onAthleteNameChange,
            label = "Name"
        )

        TextFieldRow(
            value = athleteAge,
            onValueChange = onAthleteAgeChange,
            label = "Age",
            keyboardType = KeyboardType.Number
        )

        TextFieldRow(
            value = athleteWeightKg,
            onValueChange = onAthleteWeightKgChange,
            label = "Weight in kg",
            keyboardType = KeyboardType.Decimal
        )

        TextFieldRow(
            value = athleteHeightCm,
            onValueChange = onAthleteHeightCmChange,
            label = "Height in cm",
            keyboardType = KeyboardType.Decimal
        )

        TextFieldRow(
            value = athleteEmail,
            onValueChange = onAthleteEmailChange,
            label = "Email",
            keyboardType = KeyboardType.Email
        )

        Text(
            text = "Workout type",
            style = MaterialTheme.typography.titleMedium
        )

        WorkoutTypeSelector(
            selectedWorkoutType = selectedWorkoutType,
            onWorkoutTypeChange = onWorkoutTypeChange
        )

        Button(
            onClick = onSaveAthleteClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Athlete")
        }

        Text(
            text = "Athlete status: $athleteStatus",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun HrTestScreen(
    manualHeartRate: String,
    onManualHeartRateChange: (String) -> Unit,
    manualHrSessionId: String,
    onManualHrSessionIdChange: (String) -> Unit,
    syncedSessionId: String,
    heartRateTopic: String,
    hrTestStatus: String,
    onPublishTestHrClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ScreenColumn(modifier = modifier) {
        Text(
            text = "HR Test",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Heart-rate topic: $heartRateTopic",
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Synced session: $syncedSessionId",
            style = MaterialTheme.typography.bodyMedium
        )

        TextFieldRow(
            value = manualHeartRate,
            onValueChange = onManualHeartRateChange,
            label = "Manual heart rate BPM",
            keyboardType = KeyboardType.Number
        )

        TextFieldRow(
            value = manualHrSessionId,
            onValueChange = onManualHrSessionIdChange,
            label = "Session ID"
        )

        Button(
            onClick = onPublishTestHrClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Publish Test HR")
        }

        Text(
            text = "HR test status: $hrTestStatus",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun DashboardScreen(
    sessionId: String,
    sessionStatus: String,
    syncedWorkoutType: String,
    telemetry: TelemetryValues,
    telemetryStatus: String,
    dashboardStatus: String,
    onStartWorkoutClick: () -> Unit,
    onStopWorkoutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displaySessionId = telemetry.sessionId.valueOr(sessionId)
    val displayWorkoutStatus = telemetry.workoutStatus.valueOr(sessionStatus)
    val displayWorkoutType = telemetry.workoutType.valueOr(syncedWorkoutType)

    ScreenColumn(modifier = modifier) {
        Text(
            text = "Dashboard",
            style = MaterialTheme.typography.headlineSmall
        )

        TelemetryRow("Session ID", displaySessionId)
        TelemetryRow("Workout status", displayWorkoutStatus)
        TelemetryRow("Workout type", displayWorkoutType)
        TelemetryRow("Speed km/h", telemetry.speedKmh)
        TelemetryRow("Cadence rpm", telemetry.cadenceRpm)
        TelemetryRow("Heart rate bpm", telemetry.heartRateBpm)
        TelemetryRow("Left distance m", telemetry.leftDistanceM)
        TelemetryRow("Right distance m", telemetry.rightDistanceM)
        TelemetryRow("Temperature C", telemetry.temperatureC)
        TelemetryRow("Mode", telemetry.mode)
        TelemetryRow("Recommendation", telemetry.recommendation)
        TelemetryRow("Last update", telemetry.lastUpdate)

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

        Text(
            text = "Telemetry status: $telemetryStatus",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "Dashboard status: $dashboardStatus",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun ScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun TextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun WorkoutTypeSelector(
    selectedWorkoutType: String,
    onWorkoutTypeChange: (String) -> Unit
) {
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

    Spacer(modifier = Modifier.height(8.dp))

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

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.ifBlank { "--" },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun AppPage.icon(): ImageVector {
    return when (this) {
        AppPage.Settings -> AppIcons.Settings
        AppPage.Athlete -> AppIcons.Person
        AppPage.HrTest -> AppIcons.Heart
        AppPage.Dashboard -> AppIcons.Dashboard
    }
}

private object AppIcons {
    val Settings: ImageVector = ImageVector.Builder(
        name = "Settings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 7f)
            horizontalLineTo(20f)
            moveTo(4f, 12f)
            horizontalLineTo(20f)
            moveTo(4f, 17f)
            horizontalLineTo(20f)
            moveTo(8f, 5f)
            verticalLineTo(9f)
            moveTo(15f, 10f)
            verticalLineTo(14f)
            moveTo(11f, 15f)
            verticalLineTo(19f)
        }
    }.build()

    val Person: ImageVector = ImageVector.Builder(
        name = "Person",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 12f)
            arcTo(4f, 4f, 0f, true, true, 12f, 4f)
            arcTo(4f, 4f, 0f, true, true, 12f, 12f)
            moveTo(4f, 21f)
            curveTo(5.5f, 16.5f, 8.5f, 14f, 12f, 14f)
            curveTo(15.5f, 14f, 18.5f, 16.5f, 20f, 21f)
        }
    }.build()

    val Heart: ImageVector = ImageVector.Builder(
        name = "Heart",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20.5f, 8.5f)
            curveTo(20.5f, 5.9f, 18.4f, 4f, 16f, 4f)
            curveTo(14.2f, 4f, 13f, 5f, 12f, 6.3f)
            curveTo(11f, 5f, 9.8f, 4f, 8f, 4f)
            curveTo(5.6f, 4f, 3.5f, 5.9f, 3.5f, 8.5f)
            curveTo(3.5f, 13f, 12f, 19.5f, 12f, 19.5f)
            curveTo(12f, 19.5f, 20.5f, 13f, 20.5f, 8.5f)
            close()
            moveTo(7f, 12f)
            horizontalLineTo(9.5f)
            lineTo(10.8f, 9.5f)
            lineTo(13.2f, 15f)
            lineTo(14.5f, 12f)
            horizontalLineTo(17f)
        }
    }.build()

    val Dashboard: ImageVector = ImageVector.Builder(
        name = "Dashboard",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 4f)
            horizontalLineTo(10f)
            verticalLineTo(10f)
            horizontalLineTo(4f)
            close()
            moveTo(14f, 4f)
            horizontalLineTo(20f)
            verticalLineTo(10f)
            horizontalLineTo(14f)
            close()
            moveTo(4f, 14f)
            horizontalLineTo(10f)
            verticalLineTo(20f)
            horizontalLineTo(4f)
            close()
            moveTo(14f, 14f)
            horizontalLineTo(20f)
            verticalLineTo(20f)
            horizontalLineTo(14f)
            close()
        }
    }.build()
}

suspend fun publishHeartRate(
    broker: String,
    port: Int,
    topic: String,
    deviceId: String,
    sessionId: String,
    heartRateBpm: Int,
    source: String
): String {
    return withContext(Dispatchers.IO) {
        var client: MqttClient? = null

        try {
            val serverUri = "tcp://$broker:$port"
            val clientId = "bike_hr_android_" + System.currentTimeMillis()
            client = MqttClient(serverUri, clientId, MemoryPersistence())

            client.connect()

            val timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            val payload = JSONObject()
                .put("device_id", deviceId)
                .put("session_id", sessionId)
                .put("timestamp", timestamp)
                .put("heart_rate_bpm", heartRateBpm)
                .put("source", source)
                .toString()

            val message = MqttMessage(payload.toByteArray())
            message.qos = 0
            message.isRetained = false

            client.publish(topic, message)

            "Published HR $heartRateBpm bpm to MQTT for $sessionId"
        } catch (e: Exception) {
            "Error: ${e.message}"
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
}

private fun JSONObject.optCleanString(key: String): String {
    return if (has(key) && !isNull(key)) {
        optString(key, "").trim()
    } else {
        ""
    }
}

private fun JSONObject.optDisplayString(vararg keys: String): String {
    keys.forEach { key ->
        if (has(key) && !isNull(key)) {
            val value = opt(key)
            val text = when (value) {
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> value?.toString().orEmpty()
            }.trim()

            return text.ifBlank { "--" }
        }
    }

    return "--"
}

private fun String.valueOr(fallback: String): String {
    return if (this == "--" || isBlank()) fallback.ifBlank { "--" } else this
}
