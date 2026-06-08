package com.example.bikeheartrateapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val DEFAULT_BROKER = "broker.hivemq.com"
private const val DEFAULT_PORT = "1883"
private const val DEFAULT_DEVICE_ID = "bike_001"
private const val DEFAULT_HEART_RATE_TOPIC = "anthony/bike_001/heart_rate"
private const val DEFAULT_SESSION_TOPIC = "anthony/bike_001/session"
private const val DEFAULT_COMMAND_TOPIC = "anthony/bike_001/commands"
private const val DEFAULT_TELEMETRY_TOPIC = "anthony/bike_001/merged_sensors"
private const val ATHLETES_FILE_NAME = "athletes.json"
private const val WAITING_TEXT = "Waiting\u2026"
private const val COACHING_WAITING_TEXT = "Waiting for coaching feedback\u2026"

private val DASHBOARD_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val SESSION_ID_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

private val WORKOUT_TYPE_OPTIONS = listOf("endurance", "interval", "recovery", "vo2_max", "free_ride")
private val VALID_WORKOUT_TYPES = WORKOUT_TYPE_OPTIONS.toSet()

private enum class AppPage(val label: String) {
    Settings("Settings"),
    Athletes("Athletes"),
    Workout("Workout"),
    Dashboard("Dashboard")
}

private data class AthleteProfile(
    val profileId: String,
    val name: String,
    val age: Int,
    val weight: Double,
    val height: Double,
    val email: String,
    val fitnessLevel: String
)

private data class TelemetryValues(
    val deviceId: String = "--",
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
    val decision: String = "--",
    val action: String = "--",
    val warning: String = "--",
    val lastUpdate: String = "--"
)

private enum class WorkoutDisplayState(val label: String) {
    Ready("Ready"),
    Waiting("Waiting"),
    Active("Active"),
    Stopped("Stopped"),
    Error("Error")
}

private data class DashboardTone(
    val accent: Color,
    val container: Color,
    val onContainer: Color
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
    private val athleteFitnessLevel = mutableStateOf("")
    private val selectedWorkoutType = mutableStateOf("endurance")
    private val athletes = mutableStateOf<List<AthleteProfile>>(emptyList())
    private val selectedAthleteProfileId = mutableStateOf("")
    private val activeAthlete = mutableStateOf<AthleteProfile?>(null)
    private val activeWorkoutType = mutableStateOf("")
    private val activeSessionId = mutableStateOf("")

    private val sessionId = mutableStateOf("Waiting for session...")
    private val syncedWorkoutType = mutableStateOf("--")
    private val sessionStatus = mutableStateOf("No active session")

    private val latestHeartRateBpm = mutableStateOf("132")

    private val telemetry = mutableStateOf(TelemetryValues())
    private val settingsStatus = mutableStateOf("Ready")
    private val athleteStatus = mutableStateOf("Ready")
    private val heartRateStatus = mutableStateOf("Ready")
    private val dashboardStatus = mutableStateOf("Ready")
    private val telemetryStatus = mutableStateOf("Waiting for telemetry...")

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var subscriberClient: MqttClient? = null
    private var subscriberClientKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val storedAthletes = loadAthletesFromJson()
        athletes.value = storedAthletes
        if (storedAthletes.size == 1) {
            selectedAthleteProfileId.value = storedAthletes.first().profileId
        }

        setContent {
            val selectedAthlete = athletes.value.firstOrNull {
                it.profileId == selectedAthleteProfileId.value
            }

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
                    athleteFitnessLevel = athleteFitnessLevel.value,
                    onAthleteFitnessLevelChange = { athleteFitnessLevel.value = it },
                    athletes = athletes.value,
                    onDeleteAthleteClick = { deleteAthlete(it) },
                    selectedAthleteProfileId = selectedAthleteProfileId.value,
                    onSelectedAthleteChange = { selectedAthleteProfileId.value = it },
                    selectedAthlete = selectedAthlete,
                    activeAthlete = activeAthlete.value,
                    activeWorkoutType = activeWorkoutType.value,
                    activeSessionId = activeSessionId.value,
                    selectedWorkoutType = selectedWorkoutType.value,
                    onWorkoutTypeChange = { selectedWorkoutType.value = it },
                    athleteStatus = athleteStatus.value,
                    onSaveAthleteClick = { addAthlete() },
                    telemetry = telemetry.value,
                    telemetryStatus = telemetryStatus.value,
                    dashboardStatus = dashboardStatus.value,
                    onStartWorkoutClick = { publishStartWorkout() },
                    onStopWorkoutClick = { publishEndWorkout() }
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
                heartRateStatus.value = "Invalid BPM from watch: $bpmText"
                return@launch
            }

            latestHeartRateBpm.value = bpm.toString()
            heartRateStatus.value = "Received BPM $bpm from watch. Publishing..."
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

    private fun addAthlete() {
        val validationError = validateAthleteInput()

        if (validationError != null) {
            athleteStatus.value = validationError
            return
        }

        val profile = AthleteProfile(
            profileId = nextProfileId(),
            name = athleteName.value.trim(),
            age = athleteAge.value.trim().toInt(),
            weight = athleteWeightKg.value.trim().toDouble(),
            height = athleteHeightCm.value.trim().toDouble(),
            email = athleteEmail.value.trim(),
            fitnessLevel = athleteFitnessLevel.value.trim()
        )
        val nextAthletes = athletes.value + profile

        if (!saveAthletesToJson(nextAthletes)) {
            athleteStatus.value = "Could not save athlete"
            return
        }

        athletes.value = nextAthletes
        selectedAthleteProfileId.value = profile.profileId
        athleteName.value = ""
        athleteAge.value = ""
        athleteWeightKg.value = ""
        athleteHeightCm.value = ""
        athleteEmail.value = ""
        athleteFitnessLevel.value = ""
        athleteStatus.value = "Saved ${profile.name}"
    }

    private fun deleteAthlete(profileId: String) {
        val profile = athletes.value.firstOrNull { it.profileId == profileId } ?: return
        val nextAthletes = athletes.value.filterNot { it.profileId == profileId }

        if (!saveAthletesToJson(nextAthletes)) {
            athleteStatus.value = "Could not delete ${profile.name}"
            return
        }

        athletes.value = nextAthletes
        if (selectedAthleteProfileId.value == profileId) {
            selectedAthleteProfileId.value = nextAthletes.firstOrNull()?.profileId.orEmpty()
        }
        athleteStatus.value = "Deleted ${profile.name}"
    }

    private fun publishStartWorkout() {
        val portValue = port.value.trim().toIntOrNull()
        val settingsError = validateSettings(requireCommandTopic = true)
        val selectedAthlete = athletes.value.firstOrNull {
            it.profileId == selectedAthleteProfileId.value
        }
        val workoutTypeValue = selectedWorkoutType.value.trim()
        val sessionAlreadyActive =
            activeAthlete.value != null ||
                classifyWorkoutState(sessionStatus.value) == WorkoutDisplayState.Active

        when {
            settingsError != null -> {
                dashboardStatus.value = settingsError
                return
            }
            portValue == null -> {
                dashboardStatus.value = "Invalid port"
                return
            }
            selectedAthlete == null -> {
                dashboardStatus.value = "Select an athlete before starting"
                return
            }
            workoutTypeValue !in VALID_WORKOUT_TYPES -> {
                dashboardStatus.value = "Select a valid workout type"
                return
            }
            sessionAlreadyActive -> {
                dashboardStatus.value = "A workout is already active"
                return
            }
        }

        val sessionValue = resolveStartSessionId()
        val payload = buildStartWorkoutPayload(
            deviceId = deviceId.value.trim(),
            sessionId = sessionValue,
            athlete = selectedAthlete,
            workoutType = workoutTypeValue
        ).toString()

        dashboardStatus.value = "Sending start command\u2026"
        publishMqttJson(
            broker = broker.value.trim(),
            port = portValue,
            topic = commandTopic.value.trim(),
            payload = payload
        ) { success, message ->
            if (success) {
                activeAthlete.value = selectedAthlete
                activeWorkoutType.value = workoutTypeValue
                activeSessionId.value = sessionValue
                sessionId.value = sessionValue
                syncedWorkoutType.value = workoutTypeValue
                sessionStatus.value = "Active session synced"
                telemetry.value = telemetry.value.copy(
                    sessionId = sessionValue,
                    workoutType = workoutTypeValue,
                    workoutStatus = "started"
                )
                dashboardStatus.value = "Start command sent"
            } else {
                dashboardStatus.value = "Failed to send start command: $message"
            }
        }
    }

    private fun publishEndWorkout() {
        val portValue = port.value.trim().toIntOrNull()
        val validationError = validateSettings(requireCommandTopic = true)
        val athlete = activeAthlete.value
        val workoutTypeValue = activeWorkoutType.value.ifBlank {
            selectedWorkoutType.value.trim()
        }
        val sessionValue = activeSessionId.value.ifBlank {
            sessionId.value.trim().takeUnless { it == "Waiting for session..." }.orEmpty()
        }

        when {
            validationError != null -> {
                dashboardStatus.value = validationError
                return
            }
            portValue == null -> {
                dashboardStatus.value = "Invalid port"
                return
            }
            athlete == null -> {
                dashboardStatus.value = "No active workout to end"
                return
            }
            sessionValue.isBlank() -> {
                dashboardStatus.value = "No active session to end"
                return
            }
            workoutTypeValue !in VALID_WORKOUT_TYPES -> {
                dashboardStatus.value = "No valid active workout type"
                return
            }
        }

        val payload = buildEndWorkoutPayload(
            deviceId = deviceId.value.trim(),
            sessionId = sessionValue,
            athlete = athlete,
            workoutType = workoutTypeValue
        ).toString()

        dashboardStatus.value = "Sending end command\u2026"
        publishMqttJson(
            broker = broker.value.trim(),
            port = portValue,
            topic = commandTopic.value.trim(),
            payload = payload
        ) { success, message ->
            if (success) {
                sessionStatus.value = "Session stopped"
                telemetry.value = telemetry.value.copy(
                    sessionId = sessionValue,
                    workoutType = workoutTypeValue,
                    workoutStatus = "ended"
                )
                activeAthlete.value = null
                activeWorkoutType.value = ""
                activeSessionId.value = ""
                dashboardStatus.value = "End command sent"
            } else {
                dashboardStatus.value = "Failed to send end command: $message"
            }
        }
    }

    private fun resolveStartSessionId(): String {
        val currentSessionId = sessionId.value.trim()
        val hasCurrentActiveSession =
            currentSessionId.isNotBlank() &&
                currentSessionId != "Waiting for session..." &&
                classifyWorkoutState(sessionStatus.value) == WorkoutDisplayState.Active

        return if (hasCurrentActiveSession) {
            currentSessionId
        } else {
            "session_${LocalDateTime.now().format(SESSION_ID_FORMATTER)}"
        }
    }

    private fun nextProfileId(): String {
        val nextNumber = athletes.value
            .mapNotNull { it.profileId.removePrefix("profile_").toIntOrNull() }
            .maxOrNull()
            ?.plus(1)
            ?: 1

        return "profile_${nextNumber.toString().padStart(3, '0')}"
    }

    private fun loadAthletesFromJson(): List<AthleteProfile> {
        val file = getFileStreamPath(ATHLETES_FILE_NAME)
        if (!file.exists()) return emptyList()

        return try {
            val text = openFileInput(ATHLETES_FILE_NAME)
                .bufferedReader()
                .use { it.readText() }
            val json = JSONArray(text)
            buildList {
                for (index in 0 until json.length()) {
                    json.optJSONObject(index)?.toAthleteProfile()?.let { add(it) }
                }
            }
        } catch (e: Exception) {
            athleteStatus.value = "Could not load athletes: ${e.message}"
            emptyList()
        }
    }

    private fun saveAthletesToJson(profiles: List<AthleteProfile>): Boolean {
        return try {
            val json = JSONArray()
            profiles.forEach { json.put(it.toStorageJson()) }
            openFileOutput(ATHLETES_FILE_NAME, MODE_PRIVATE)
                .bufferedWriter()
                .use { it.write(json.toString(2)) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun JSONObject.toAthleteProfile(): AthleteProfile? {
        val profileId = optDisplayString("profile_id", "profileId")
        val name = optDisplayString("name")
        val email = optDisplayString("email")
        val age = optInt("age", -1)
        val weight = optDoubleValue("weight", "weight_kg")
        val height = optDoubleValue("height", "height_cm")

        if (
            profileId == "--" ||
            name == "--" ||
            email == "--" ||
            age <= 0 ||
            weight == null ||
            height == null
        ) {
            return null
        }

        return AthleteProfile(
            profileId = profileId,
            name = name,
            age = age,
            weight = weight,
            height = height,
            email = email,
            fitnessLevel = optDisplayString("fitness_level", "fitnessLevel")
                .takeUnless { it == "--" }
                .orEmpty()
        )
    }

    private fun AthleteProfile.toStorageJson(): JSONObject {
        return JSONObject()
            .put("profile_id", profileId)
            .put("name", name)
            .put("age", age)
            .put("weight", weight)
            .put("height", height)
            .put("email", email)
            .put("fitness_level", fitnessLevel)
    }

    private fun AthleteProfile.toPayloadJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("age", age)
            .put("weight", weight)
            .put("height", height)
            .put("email", email)
            .put("fitness_level", fitnessLevel)
    }

    private fun JSONObject.optDoubleValue(vararg keys: String): Double? {
        keys.forEach { key ->
            if (has(key) && !isNull(key)) {
                return opt(key)?.toString()?.trim()?.toDoubleOrNull()
            }
        }

        return null
    }

    private fun publishCurrentHeartRate(source: String) {
        val brokerValue = broker.value.trim()
        val portValue = port.value.trim().toIntOrNull()
        val deviceValue = deviceId.value.trim()
        val heartRateTopicValue = heartRateTopic.value.trim()
        val sessionValue = activeSessionId.value.ifBlank { sessionId.value.trim() }
        val bpmValue = latestHeartRateBpm.value.trim().toIntOrNull() ?: 0

        if (portValue == null) {
            heartRateStatus.value = "Invalid port"
            return
        }

        if (sessionValue.isBlank() || sessionValue == "Waiting for session...") {
            heartRateStatus.value = "No synced session yet"
            return
        }

        if (
            activeAthlete.value == null &&
            classifyWorkoutState(sessionStatus.value) != WorkoutDisplayState.Active
        ) {
            heartRateStatus.value = "No active session. Current status: ${sessionStatus.value}"
            return
        }

        mainScope.launch {
            heartRateStatus.value = "Publishing BPM $bpmValue to $sessionValue..."
            heartRateStatus.value = publishHeartRate(
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
                syncedWorkoutType.value = incomingWorkoutType
                telemetry.value = telemetry.value.copy(
                    sessionId = incomingSessionId,
                    workoutType = incomingWorkoutType.valueOr(telemetry.value.workoutType),
                    workoutStatus = incomingStatus.ifBlank { telemetry.value.workoutStatus }
                )

                when (incomingStatus) {
                    "active", "started" -> {
                        sessionStatus.value = "Active session synced"
                        settingsStatus.value = "Synced $incomingSessionId ($incomingWorkoutType)"
                    }
                    "stopped", "ended" -> {
                        sessionStatus.value = "Session stopped"
                        settingsStatus.value = "Session stopped: $incomingSessionId"
                        activeAthlete.value = null
                        activeWorkoutType.value = ""
                        activeSessionId.value = ""
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
                val previous = telemetry.value
                val receivedAt = LocalDateTime.now().format(DASHBOARD_TIME_FORMATTER)

                val incomingDeviceId = json.optDisplayString("device_id")
                if (
                    incomingDeviceId != "--" &&
                    incomingDeviceId != deviceId.value.trim()
                ) {
                    telemetryStatus.value = "Ignored telemetry for $incomingDeviceId"
                    return@launch
                }

                val nextTelemetry = previous.copy(
                    deviceId = json.mergeText(previous.deviceId, "device_id"),
                    sessionId = json.mergeText(previous.sessionId, "session_id", "sessionId"),
                    workoutStatus = json.mergeText(
                        previous.workoutStatus,
                        "status",
                        "workout_status",
                        "state"
                    ),
                    workoutType = json.mergeText(previous.workoutType, "workout_type", "workoutType"),
                    speedKmh = json.mergeNumber(previous.speedKmh, "speed_kmh", "speed_km_h", "speed"),
                    cadenceRpm = json.mergeNumber(previous.cadenceRpm, "cadence_rpm", "cadence"),
                    heartRateBpm = json.mergeNumber(
                        previous.heartRateBpm,
                        "heart_rate_bpm",
                        "heart_rate",
                        "bpm"
                    ),
                    leftDistanceM = json.mergeNumber(
                        previous.leftDistanceM,
                        "left_distance_m",
                        "left_distance",
                        "distance_left_m"
                    ),
                    rightDistanceM = json.mergeNumber(
                        previous.rightDistanceM,
                        "right_distance_m",
                        "right_distance",
                        "distance_right_m"
                    ),
                    temperatureC = json.mergeNumber(
                        previous.temperatureC,
                        "temperature_c",
                        "temp_c",
                        "temperature"
                    ),
                    mode = json.mergeText(previous.mode, "mode"),
                    recommendation = json.mergeText(
                        previous.recommendation,
                        "recommendation",
                        "latest_recommendation"
                    ),
                    decision = json.mergeText(
                        previous.decision,
                        "decision",
                        "latest_decision"
                    ),
                    action = json.mergeText(previous.action, "action", "latest_action"),
                    warning = json.mergeText(previous.warning, "warning", "latest_warning"),
                    lastUpdate = json.optDisplayString(
                        "timestamp",
                        "last_update",
                        "updated_at"
                    ).valueOr(receivedAt)
                )

                telemetry.value = nextTelemetry
                telemetryStatus.value = "Last message received"

                nextTelemetry.sessionId.takeUnless { it == "--" }?.let {
                    sessionId.value = it
                }

                nextTelemetry.workoutType.takeUnless { it == "--" }?.let {
                    syncedWorkoutType.value = it
                }

                nextTelemetry.workoutStatus.takeUnless { it == "--" }?.let {
                    sessionStatus.value = it
                    if (classifyWorkoutState(it) == WorkoutDisplayState.Stopped) {
                        activeAthlete.value = null
                        activeWorkoutType.value = ""
                        activeSessionId.value = ""
                    }
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
        return validateAthleteInputValues(
            name = athleteName.value,
            age = athleteAge.value,
            weightKg = athleteWeightKg.value,
            heightCm = athleteHeightCm.value,
            email = athleteEmail.value
        )
    }

    private fun buildStartWorkoutPayload(
        deviceId: String,
        sessionId: String,
        athlete: AthleteProfile,
        workoutType: String
    ): JSONObject {
        return JSONObject()
            .put("command", "start_workout")
            .put("status", "started")
            .put("device_id", deviceId)
            .put("session_id", sessionId)
            .put("profile_id", athlete.profileId)
            .put("athlete", athlete.toPayloadJson())
            .put("workout_type", workoutType)
            .put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
    }

    private fun buildEndWorkoutPayload(
        deviceId: String,
        sessionId: String,
        athlete: AthleteProfile,
        workoutType: String
    ): JSONObject {
        return JSONObject()
            .put("command", "end_workout")
            .put("status", "ended")
            .put("device_id", deviceId)
            .put("session_id", sessionId)
            .put("profile_id", athlete.profileId)
            .put("workout_type", workoutType)
            .put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
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
    athleteFitnessLevel: String,
    onAthleteFitnessLevelChange: (String) -> Unit,
    athletes: List<AthleteProfile>,
    onDeleteAthleteClick: (String) -> Unit,
    selectedAthleteProfileId: String,
    onSelectedAthleteChange: (String) -> Unit,
    selectedAthlete: AthleteProfile?,
    activeAthlete: AthleteProfile?,
    activeWorkoutType: String,
    activeSessionId: String,
    selectedWorkoutType: String,
    onWorkoutTypeChange: (String) -> Unit,
    athleteStatus: String,
    onSaveAthleteClick: () -> Unit,
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

            AppPage.Athletes -> AthleteScreen(
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
                athleteFitnessLevel = athleteFitnessLevel,
                onAthleteFitnessLevelChange = onAthleteFitnessLevelChange,
                athletes = athletes,
                selectedAthleteProfileId = selectedAthleteProfileId,
                onSelectedAthleteChange = onSelectedAthleteChange,
                athleteStatus = athleteStatus,
                onSaveAthleteClick = onSaveAthleteClick,
                onDeleteAthleteClick = onDeleteAthleteClick,
                modifier = Modifier.padding(innerPadding)
            )

            AppPage.Workout -> WorkoutScreen(
                athletes = athletes,
                selectedAthleteProfileId = selectedAthleteProfileId,
                onSelectedAthleteChange = onSelectedAthleteChange,
                selectedWorkoutType = selectedWorkoutType,
                onWorkoutTypeChange = onWorkoutTypeChange,
                activeAthlete = activeAthlete,
                activeWorkoutType = activeWorkoutType,
                activeSessionId = activeSessionId,
                dashboardStatus = dashboardStatus,
                onStartWorkoutClick = onStartWorkoutClick,
                onStopWorkoutClick = onStopWorkoutClick,
                modifier = Modifier.padding(innerPadding)
            )

            AppPage.Dashboard -> DashboardScreen(
                broker = broker,
                port = port,
                sessionTopic = sessionTopic,
                telemetryTopic = telemetryTopic,
                sessionId = sessionId,
                sessionStatus = sessionStatus,
                syncedWorkoutType = syncedWorkoutType,
                selectedWorkoutType = selectedWorkoutType,
                selectedAthlete = selectedAthlete,
                activeAthlete = activeAthlete,
                activeWorkoutType = activeWorkoutType,
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
    athleteFitnessLevel: String,
    onAthleteFitnessLevelChange: (String) -> Unit,
    athletes: List<AthleteProfile>,
    selectedAthleteProfileId: String,
    onSelectedAthleteChange: (String) -> Unit,
    athleteStatus: String,
    onSaveAthleteClick: () -> Unit,
    onDeleteAthleteClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    ScreenColumn(modifier = modifier) {
        Text(
            text = "Athletes",
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

        TextFieldRow(
            value = athleteFitnessLevel,
            onValueChange = onAthleteFitnessLevelChange,
            label = "Fitness level (optional)"
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

        Text(
            text = "Saved athletes",
            style = MaterialTheme.typography.titleMedium
        )

        if (athletes.isEmpty()) {
            Text(
                text = "No athletes saved",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AthleteCarousel(
                athletes = athletes,
                selectedProfileId = selectedAthleteProfileId,
                onSelectAthlete = { onSelectedAthleteChange(it.profileId) },
                onDeleteAthlete = { onDeleteAthleteClick(it.profileId) }
            )
        }
    }
}

@Composable
private fun WorkoutScreen(
    athletes: List<AthleteProfile>,
    selectedAthleteProfileId: String,
    onSelectedAthleteChange: (String) -> Unit,
    selectedWorkoutType: String,
    onWorkoutTypeChange: (String) -> Unit,
    activeAthlete: AthleteProfile?,
    activeWorkoutType: String,
    activeSessionId: String,
    dashboardStatus: String,
    onStartWorkoutClick: () -> Unit,
    onStopWorkoutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedAthlete = athletes.firstOrNull { it.profileId == selectedAthleteProfileId }
    val commandInFlight = dashboardStatus.startsWith("Sending", ignoreCase = true)
    val hasValidWorkoutType = selectedWorkoutType in VALID_WORKOUT_TYPES
    val workoutActive = activeAthlete != null
    val startEnabled =
        selectedAthlete != null && hasValidWorkoutType && !workoutActive && !commandInFlight
    val endEnabled = workoutActive && !commandInFlight

    ScreenColumn(modifier = modifier) {
        Text(
            text = "Workout",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Select athlete",
            style = MaterialTheme.typography.titleMedium
        )

        if (athletes.isEmpty()) {
            Text(
                text = "No saved athletes yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AthleteCarousel(
                athletes = athletes,
                selectedProfileId = selectedAthleteProfileId,
                onSelectAthlete = { onSelectedAthleteChange(it.profileId) }
            )
        }

        Text(
            text = "Workout type",
            style = MaterialTheme.typography.titleMedium
        )

        WorkoutTypeSelector(
            selectedWorkoutType = selectedWorkoutType,
            onWorkoutTypeChange = onWorkoutTypeChange
        )

        activeAthlete?.let { athlete ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Active workout",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    InfoLine("Athlete", athlete.name)
                    InfoLine("Workout type", activeWorkoutType.displayText())
                    InfoLine("Session ID", activeSessionId.displayText())
                }
            }
        }

        Button(
            onClick = onStartWorkoutClick,
            enabled = startEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                imageVector = AppIcons.Play,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Workout")
        }

        OutlinedButton(
            onClick = onStopWorkoutClick,
            enabled = endEnabled,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                imageVector = AppIcons.Stop,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("End Workout")
        }

        Text(
            text = "Workout status: $dashboardStatus",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun AthleteCarousel(
    athletes: List<AthleteProfile>,
    selectedProfileId: String?,
    onSelectAthlete: (AthleteProfile) -> Unit,
    onDeleteAthlete: ((AthleteProfile) -> Unit)? = null
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(
            items = athletes,
            key = { it.profileId }
        ) { athlete ->
            AthleteCarouselCard(
                athlete = athlete,
                selected = athlete.profileId == selectedProfileId,
                onSelectClick = { onSelectAthlete(athlete) },
                onDeleteClick = onDeleteAthlete?.let { delete -> { delete(athlete) } }
            )
        }
    }
}

@Composable
private fun AthleteCarouselCard(
    athlete: AthleteProfile,
    selected: Boolean,
    onSelectClick: () -> Unit,
    onDeleteClick: (() -> Unit)?
) {
    val cardWidth = if (selected) 272.dp else 248.dp
    val cardColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (selected) 4.dp else 1.dp,
        shadowElevation = if (selected) 6.dp else 2.dp,
        border = border,
        modifier = Modifier
            .width(cardWidth)
            .defaultMinSize(minHeight = 190.dp)
            .clickable(onClick = onSelectClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = athlete.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            InfoLine("Email", athlete.email)
            InfoLine("Age", athlete.age.toString())
            if (athlete.fitnessLevel.isNotBlank()) {
                InfoLine("Fitness level", athlete.fitnessLevel)
            }

            if (selected) {
                StatusBadge(
                    label = "Selected",
                    tone = DashboardTone(
                        accent = MaterialTheme.colorScheme.primary,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        onContainer = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            if (onDeleteClick != null) {
                OutlinedButton(
                    onClick = onDeleteClick,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    broker: String,
    port: String,
    sessionTopic: String,
    telemetryTopic: String,
    sessionId: String,
    sessionStatus: String,
    syncedWorkoutType: String,
    selectedWorkoutType: String,
    selectedAthlete: AthleteProfile?,
    activeAthlete: AthleteProfile?,
    activeWorkoutType: String,
    telemetry: TelemetryValues,
    telemetryStatus: String,
    dashboardStatus: String,
    onStartWorkoutClick: () -> Unit,
    onStopWorkoutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displaySessionId = telemetry.sessionId.valueOr(sessionId)
    val displayWorkoutStatus = telemetry.workoutStatus.valueOr(sessionStatus)
    val displayWorkoutType = telemetry.workoutType
        .valueOr(activeWorkoutType)
        .valueOr(syncedWorkoutType)
        .valueOr(selectedWorkoutType)
    val displayMode = telemetry.mode.valueOr("real")
    val displayState = classifyWorkoutState(displayWorkoutStatus)
    val dashboardAthlete = activeAthlete ?: selectedAthlete
    val athleteValidationError = when {
        selectedAthlete == null -> "Select an athlete on the Workout page"
        selectedWorkoutType !in VALID_WORKOUT_TYPES -> "Select a valid workout type"
        else -> null
    }
    val commandInFlight = dashboardStatus.startsWith("Sending", ignoreCase = true)
    val startCommandSent = dashboardStatus.equals("Start command sent", ignoreCase = true)
    val isWorkoutActive = activeAthlete != null || displayState == WorkoutDisplayState.Active
    val startEnabled = athleteValidationError == null && !isWorkoutActive && !commandInFlight
    val stopEnabled = activeAthlete != null && !commandInFlight && (isWorkoutActive || startCommandSent)

    ScreenColumn(modifier = modifier) {
        Text(
            text = "Dashboard",
            style = MaterialTheme.typography.headlineSmall
        )

        WorkoutStatusCard(
            displayState = displayState,
            sessionId = displaySessionId,
            workoutType = displayWorkoutType,
            mode = displayMode,
            lastUpdate = telemetry.lastUpdate
        )

        WorkoutControlsCard(
            startEnabled = startEnabled,
            stopEnabled = stopEnabled,
            athleteValidationError = athleteValidationError,
            dashboardStatus = dashboardStatus,
            onStartWorkoutClick = onStartWorkoutClick,
            onStopWorkoutClick = onStopWorkoutClick
        )

        MetricsGrid(telemetry = telemetry)

        HeartRateIntensityCard(
            heartRateBpm = telemetry.heartRateBpm,
            athleteAge = dashboardAthlete?.age?.toString().orEmpty()
        )

        SafetyDistanceCard(
            leftDistanceM = telemetry.leftDistanceM,
            rightDistanceM = telemetry.rightDistanceM
        )

        CoachingCard(telemetry = telemetry)

        TelemetryConnectionCard(
            broker = broker,
            port = port,
            telemetryTopic = telemetryTopic,
            sessionTopic = sessionTopic,
            telemetryStatus = telemetryStatus
        )
    }
}

@Composable
private fun WorkoutStatusCard(
    displayState: WorkoutDisplayState,
    sessionId: String,
    workoutType: String,
    mode: String,
    lastUpdate: String
) {
    val tone = workoutTone(displayState)

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = tone.container),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconBadge(
                        icon = AppIcons.Activity,
                        accent = tone.accent
                    )

                    Column {
                        Text(
                            text = "Workout Status",
                            style = MaterialTheme.typography.labelLarge,
                            color = tone.onContainer
                        )
                        Text(
                            text = displayState.label,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = tone.onContainer
                        )
                    }
                }

                StatusBadge(
                    label = displayState.label,
                    tone = tone
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("Session ID", sessionId.displayText())
                InfoLine("Workout type", workoutType.displayText())
                InfoLine("Mode", mode.displayText())
                InfoLine("Last update", lastUpdate.displayText())
            }
        }
    }
}

@Composable
private fun WorkoutControlsCard(
    startEnabled: Boolean,
    stopEnabled: Boolean,
    athleteValidationError: String?,
    dashboardStatus: String,
    onStartWorkoutClick: () -> Unit,
    onStopWorkoutClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onStartWorkoutClick,
                    enabled = startEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Play,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Workout")
                }

                OutlinedButton(
                    onClick = onStopWorkoutClick,
                    enabled = stopEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Stop,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("End Workout")
                }
            }

            InfoLine("Command status", dashboardStatus.ifBlank { "Ready" })

            if (athleteValidationError != null) {
                Text(
                    text = athleteValidationError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun MetricsGrid(telemetry: TelemetryValues) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            MetricCard(
                title = "Speed",
                value = telemetry.speedKmh.formatMetric(decimals = 1),
                unit = "km/h",
                icon = AppIcons.Speed,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Cadence",
                value = telemetry.cadenceRpm.formatMetric(decimals = 0),
                unit = "rpm",
                icon = AppIcons.Cadence,
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            MetricCard(
                title = "Heart Rate",
                value = telemetry.heartRateBpm.formatMetric(decimals = 0),
                unit = "bpm",
                icon = AppIcons.Heart,
                accent = Color(0xFFD32F2F),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Temperature",
                value = telemetry.temperatureC.formatMetric(decimals = 1),
                unit = "\u00B0C",
                icon = AppIcons.Thermometer,
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.defaultMinSize(minHeight = 148.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(icon = icon, accent = accent)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HeartRateIntensityCard(
    heartRateBpm: String,
    athleteAge: String
) {
    val heartRate = heartRateBpm.toDoubleOrNull()
    val age = athleteAge.trim().toIntOrNull()
    val maxHr = age?.let { 220 - it }
    val canShowZone = heartRate != null && heartRate > 0.0 && maxHr != null && maxHr > 0

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                icon = AppIcons.Heart,
                title = "Heart-rate Intensity",
                accent = Color(0xFFD32F2F)
            )

            if (!canShowZone) {
                Text(
                    text = "HR zone unavailable",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val percent = heartRate / maxHr.toDouble()
                val progress = percent.coerceIn(0.0, 1.0).toFloat()
                val zoneLabel = heartRateZoneLabel(percent)
                val tone = heartRateZoneTone(percent)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactValue("Current HR", heartRateBpm.formatMetric(decimals = 0), "bpm")
                    CompactValue("Estimated max", maxHr.toString(), "bpm")
                    CompactValue("Intensity", "${(percent * 100).roundToInt()}%", zoneLabel)
                }

                LinearProgressIndicator(
                    progress = { progress },
                    color = tone.accent,
                    trackColor = tone.accent.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                )

                StatusBadge(label = zoneLabel, tone = tone)
            }
        }
    }
}

@Composable
private fun SafetyDistanceCard(
    leftDistanceM: String,
    rightDistanceM: String
) {
    val left = leftDistanceM.toDoubleOrNull()
    val right = rightDistanceM.toDoubleOrNull()
    val hasDistance = left != null || right != null
    val isNearby = listOfNotNull(left, right).any { it < 0.5 }
    val status = when {
        !hasDistance -> WAITING_TEXT
        isNearby -> "Object nearby"
        else -> "Clear"
    }
    val tone = safetyTone(hasDistance = hasDistance, isNearby = isNearby)

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                icon = AppIcons.Shield,
                title = "Safety Distance",
                accent = tone.accent
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CompactValue("Left", leftDistanceM.formatMetric(decimals = 2), "m")
                CompactValue("Right", rightDistanceM.formatMetric(decimals = 2), "m")
            }

            StatusBadge(label = status, tone = tone)
        }
    }
}

@Composable
private fun CoachingCard(telemetry: TelemetryValues) {
    val recommendation = telemetry.recommendation.valueOr(telemetry.decision)
    val hasRecommendation = recommendation != "--"
    val hasAction = telemetry.action != "--"
    val hasWarning = telemetry.warning != "--"
    val tone = if (hasWarning) {
        warningTone()
    } else {
        DashboardTone(
            accent = MaterialTheme.colorScheme.primary,
            container = MaterialTheme.colorScheme.surface,
            onContainer = MaterialTheme.colorScheme.onSurface
        )
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                icon = AppIcons.Coach,
                title = "Coaching",
                accent = tone.accent
            )

            Text(
                text = if (hasRecommendation) recommendation else COACHING_WAITING_TEXT,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (telemetry.recommendation != "--" && telemetry.decision != "--") {
                InfoLine("Decision", telemetry.decision)
            }

            if (hasAction) {
                InfoLine("Action", telemetry.action)
            }

            if (hasWarning) {
                StatusBadge(label = telemetry.warning, tone = warningTone())
            }
        }
    }
}

@Composable
private fun TelemetryConnectionCard(
    broker: String,
    port: String,
    telemetryTopic: String,
    sessionTopic: String,
    telemetryStatus: String
) {
    val tone = connectionTone(telemetryStatus)
    val brokerDisplay = listOf(broker.trim(), port.trim())
        .filter { it.isNotBlank() }
        .joinToString(":")
        .ifBlank { WAITING_TEXT }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                icon = AppIcons.Signal,
                title = "Telemetry",
                accent = tone.accent
            )

            StatusBadge(label = telemetryStatus.ifBlank { WAITING_TEXT }, tone = tone)
            InfoLine("MQTT broker", brokerDisplay)
            InfoLine("Telemetry topic", telemetryTopic.displayText())
            InfoLine("Session sync topic", sessionTopic.displayText())
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    accent: Color
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon = icon, accent = accent)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun RowScope.CompactValue(
    label: String,
    value: String,
    unit: String
) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InfoLine(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value.ifBlank { "--" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
private fun StatusBadge(
    label: String,
    tone: DashboardTone
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = tone.accent.copy(alpha = 0.14f),
        contentColor = tone.accent
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun IconBadge(
    icon: ImageVector,
    accent: Color
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.13f),
        contentColor = accent
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun workoutTone(state: WorkoutDisplayState): DashboardTone {
    return when (state) {
        WorkoutDisplayState.Ready -> DashboardTone(
            accent = MaterialTheme.colorScheme.primary,
            container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            onContainer = MaterialTheme.colorScheme.onPrimaryContainer
        )
        WorkoutDisplayState.Waiting -> DashboardTone(
            accent = MaterialTheme.colorScheme.secondary,
            container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            onContainer = MaterialTheme.colorScheme.onSecondaryContainer
        )
        WorkoutDisplayState.Active -> {
            val green = Color(0xFF2E7D32)
            DashboardTone(
                accent = green,
                container = green.copy(alpha = 0.13f),
                onContainer = MaterialTheme.colorScheme.onSurface
            )
        }
        WorkoutDisplayState.Stopped -> DashboardTone(
            accent = MaterialTheme.colorScheme.outline,
            container = MaterialTheme.colorScheme.surfaceVariant,
            onContainer = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WorkoutDisplayState.Error -> DashboardTone(
            accent = MaterialTheme.colorScheme.error,
            container = MaterialTheme.colorScheme.errorContainer,
            onContainer = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun heartRateZoneTone(percent: Double): DashboardTone {
    return when {
        percent < 0.5 -> {
            val blue = MaterialTheme.colorScheme.primary
            DashboardTone(
                accent = blue,
                container = blue.copy(alpha = 0.12f),
                onContainer = MaterialTheme.colorScheme.onSurface
            )
        }
        percent < 0.7 -> {
            val green = Color(0xFF2E7D32)
            DashboardTone(
                accent = green,
                container = green.copy(alpha = 0.12f),
                onContainer = MaterialTheme.colorScheme.onSurface
            )
        }
        percent < 0.85 -> {
            val amber = Color(0xFFF59E0B)
            DashboardTone(
                accent = amber,
                container = amber.copy(alpha = 0.14f),
                onContainer = MaterialTheme.colorScheme.onSurface
            )
        }
        percent < 0.9 -> warningTone()
        else -> DashboardTone(
            accent = MaterialTheme.colorScheme.error,
            container = MaterialTheme.colorScheme.errorContainer,
            onContainer = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun safetyTone(
    hasDistance: Boolean,
    isNearby: Boolean
): DashboardTone {
    if (!hasDistance) {
        return DashboardTone(
            accent = MaterialTheme.colorScheme.secondary,
            container = MaterialTheme.colorScheme.secondaryContainer,
            onContainer = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }

    if (isNearby) {
        return warningTone()
    }

    val green = Color(0xFF2E7D32)
    return DashboardTone(
        accent = green,
        container = green.copy(alpha = 0.12f),
        onContainer = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun connectionTone(status: String): DashboardTone {
    val normalized = status.lowercase(Locale.US)
    return when {
        normalized.contains("error") || normalized.contains("disconnected") -> {
            DashboardTone(
                accent = MaterialTheme.colorScheme.error,
                container = MaterialTheme.colorScheme.errorContainer,
                onContainer = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        normalized.contains("last message") || normalized.contains("connected") -> {
            val green = Color(0xFF2E7D32)
            DashboardTone(
                accent = green,
                container = green.copy(alpha = 0.12f),
                onContainer = MaterialTheme.colorScheme.onSurface
            )
        }
        else -> DashboardTone(
            accent = MaterialTheme.colorScheme.secondary,
            container = MaterialTheme.colorScheme.secondaryContainer,
            onContainer = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun warningTone(): DashboardTone {
    val orange = Color(0xFFC2410C)
    return DashboardTone(
        accent = orange,
        container = orange.copy(alpha = 0.14f),
        onContainer = MaterialTheme.colorScheme.onSurface
    )
}

private fun classifyWorkoutState(status: String): WorkoutDisplayState {
    val normalized = status.trim().lowercase(Locale.US)

    return when {
        normalized.isBlank() || normalized == "--" -> WorkoutDisplayState.Waiting
        normalized.contains("error") || normalized.contains("fail") -> WorkoutDisplayState.Error
        normalized.contains("no active") || normalized == "ready" -> WorkoutDisplayState.Ready
        normalized.contains("waiting") || normalized.contains("connecting") -> {
            WorkoutDisplayState.Waiting
        }
        normalized.contains("stopped") ||
            normalized.contains("ended") ||
            normalized.contains("complete") -> {
            WorkoutDisplayState.Stopped
        }
        normalized.contains("active") ||
            normalized.contains("started") ||
            normalized.contains("running") ||
            normalized.contains("in_progress") ||
            normalized.contains("live") -> {
            WorkoutDisplayState.Active
        }
        else -> WorkoutDisplayState.Ready
    }
}

private fun heartRateZoneLabel(percent: Double): String {
    return when {
        percent < 0.5 -> "Warm-up / Low"
        percent < 0.7 -> "Moderate"
        percent < 0.85 -> "High"
        percent < 0.9 -> "Warning"
        else -> "Recover"
    }
}

private fun validateAthleteInputValues(
    name: String,
    age: String,
    weightKg: String,
    heightCm: String,
    email: String
): String? {
    val ageValue = age.trim().toIntOrNull()
    val weightValue = weightKg.trim().toDoubleOrNull()
    val heightValue = heightCm.trim().toDoubleOrNull()

    return when {
        name.trim().isBlank() -> "Name cannot be empty"
        ageValue == null || ageValue <= 0 -> "Age must be a valid positive integer"
        weightValue == null || weightValue <= 0.0 -> {
            "Weight must be a valid number"
        }
        heightValue == null || heightValue <= 0.0 -> {
            "Height must be a valid number"
        }
        email.trim().isBlank() -> "Email cannot be empty"
        else -> null
    }
}

private fun String.formatMetric(decimals: Int): String {
    val number = trim().toDoubleOrNull() ?: return "--"
    if (number.isNaN() || number.isInfinite()) return "--"

    return String.format(Locale.US, "%.${decimals.coerceAtLeast(0)}f", number)
}

private fun String.displayText(): String {
    return if (this == "--" || isBlank()) WAITING_TEXT else this
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WORKOUT_TYPE_OPTIONS.chunked(2).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowItems.forEach { workoutType ->
                    WorkoutTypeButton(
                        label = workoutType,
                        selected = selectedWorkoutType == workoutType,
                        onClick = { onWorkoutTypeChange(workoutType) },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
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
        AppPage.Athletes -> AppIcons.Person
        AppPage.Workout -> AppIcons.Activity
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

    val Play: ImageVector = ImageVector.Builder(
        name = "Play",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 5f)
            lineTo(19f, 12f)
            lineTo(8f, 19f)
            close()
        }
    }.build()

    val Stop: ImageVector = ImageVector.Builder(
        name = "Stop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 7f)
            horizontalLineTo(17f)
            verticalLineTo(17f)
            horizontalLineTo(7f)
            close()
        }
    }.build()

    val Activity: ImageVector = ImageVector.Builder(
        name = "Activity",
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
            moveTo(4f, 12f)
            horizontalLineTo(8f)
            lineTo(10f, 7f)
            lineTo(14f, 17f)
            lineTo(16f, 12f)
            horizontalLineTo(20f)
        }
    }.build()

    val Speed: ImageVector = ImageVector.Builder(
        name = "Speed",
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
            moveTo(5f, 16f)
            curveTo(5f, 11f, 8f, 7f, 12f, 7f)
            curveTo(16f, 7f, 19f, 11f, 19f, 16f)
            moveTo(7f, 16f)
            horizontalLineTo(17f)
            moveTo(12f, 16f)
            lineTo(16f, 11f)
        }
    }.build()

    val Cadence: ImageVector = ImageVector.Builder(
        name = "Cadence",
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
            moveTo(12f, 7f)
            verticalLineTo(12f)
            lineTo(16f, 15f)
            moveTo(6f, 10f)
            curveTo(7f, 6.5f, 10.3f, 4.5f, 14f, 5f)
            lineTo(16f, 5.3f)
            moveTo(18f, 14f)
            curveTo(17f, 17.5f, 13.7f, 19.5f, 10f, 19f)
            lineTo(8f, 18.7f)
        }
    }.build()

    val Thermometer: ImageVector = ImageVector.Builder(
        name = "Thermometer",
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
            moveTo(12f, 4f)
            verticalLineTo(14f)
            moveTo(9f, 17f)
            curveTo(9f, 18.7f, 10.3f, 20f, 12f, 20f)
            curveTo(13.7f, 20f, 15f, 18.7f, 15f, 17f)
            curveTo(15f, 15.9f, 14.4f, 14.9f, 13f, 14.2f)
            verticalLineTo(6f)
            curveTo(13f, 5f, 12.5f, 4f, 12f, 4f)
            curveTo(11.5f, 4f, 11f, 5f, 11f, 6f)
            verticalLineTo(14.2f)
            curveTo(9.6f, 14.9f, 9f, 15.9f, 9f, 17f)
        }
    }.build()

    val Shield: ImageVector = ImageVector.Builder(
        name = "Shield",
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
            moveTo(12f, 3f)
            lineTo(19f, 6f)
            verticalLineTo(11f)
            curveTo(19f, 15.5f, 16.2f, 19f, 12f, 21f)
            curveTo(7.8f, 19f, 5f, 15.5f, 5f, 11f)
            verticalLineTo(6f)
            close()
        }
    }.build()

    val Coach: ImageVector = ImageVector.Builder(
        name = "Coach",
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
            moveTo(5f, 5f)
            horizontalLineTo(19f)
            verticalLineTo(15f)
            horizontalLineTo(12f)
            lineTo(8f, 19f)
            verticalLineTo(15f)
            horizontalLineTo(5f)
            close()
            moveTo(8f, 9f)
            horizontalLineTo(16f)
            moveTo(8f, 12f)
            horizontalLineTo(13f)
        }
    }.build()

    val Signal: ImageVector = ImageVector.Builder(
        name = "Signal",
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
            moveTo(4f, 17f)
            curveTo(8.5f, 13f, 15.5f, 13f, 20f, 17f)
            moveTo(7f, 14f)
            curveTo(10f, 11.5f, 14f, 11.5f, 17f, 14f)
            moveTo(10f, 11f)
            curveTo(11.2f, 10.2f, 12.8f, 10.2f, 14f, 11f)
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 19f)
            arcTo(1.3f, 1.3f, 0f, true, true, 12f, 16.4f)
            arcTo(1.3f, 1.3f, 0f, true, true, 12f, 19f)
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

private fun JSONObject.mergeText(current: String, vararg keys: String): String {
    val incoming = optDisplayString(*keys)
    return incoming.valueOr(current)
}

private fun JSONObject.mergeNumber(current: String, vararg keys: String): String {
    keys.forEach { key ->
        if (has(key) && !isNull(key)) {
            val incoming = optDisplayString(key)
            val number = incoming.toDoubleOrNull()
            if (number != null && !number.isNaN() && !number.isInfinite()) {
                return incoming
            }
        }
    }

    return current
}

private fun String.valueOr(fallback: String): String {
    return if (this == "--" || isBlank()) fallback.ifBlank { "--" } else this
}
