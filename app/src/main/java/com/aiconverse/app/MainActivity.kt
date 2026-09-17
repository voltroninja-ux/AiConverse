package com.aiconverse.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aiconverse.app.ai.AiParticipant
import com.aiconverse.app.ai.GeminiParticipant
import com.aiconverse.app.ai.OpenRouterParticipant
import com.aiconverse.app.ai.TranscriptEntry
import com.aiconverse.app.voice.SpeechInputManager
import com.aiconverse.app.voice.VoiceOutputManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore
    private var voiceOutput: VoiceOutputManager? = null
    private var speechInput: SpeechInputManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(settings = settings, activity = this)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceOutput?.shutdown()
        speechInput?.stop()
    }
}

private enum class Screen { SETTINGS, CONVERSATION }

@Composable
private fun AppRoot(settings: SettingsStore, activity: ComponentActivity) {
    var screen by remember {
        mutableStateOf(if (settings.hasRequiredKeys()) Screen.CONVERSATION else Screen.SETTINGS)
    }

    when (screen) {
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            onDone = { screen = Screen.CONVERSATION }
        )
        Screen.CONVERSATION -> ConversationScreen(
            settings = settings,
            activity = activity,
            onOpenSettings = { screen = Screen.SETTINGS }
        )
    }
}

@Composable
private fun SettingsScreen(settings: SettingsStore, onDone: () -> Unit) {
    var geminiKey by remember { mutableStateOf(settings.geminiApiKey) }
    var openRouterKey by remember { mutableStateOf(settings.openRouterApiKey) }
    var openRouterModel by remember { mutableStateOf(settings.openRouterModel) }
    var name1 by remember { mutableStateOf(settings.aiOneName) }
    var name2 by remember { mutableStateOf(settings.aiTwoName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("AiConverse Setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Free-tier keys only — no paid API required.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(20.dp))

        Text("Gemini API key (aistudio.google.com)", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = geminiKey,
            onValueChange = { geminiKey = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(16.dp))

        Text("OpenRouter API key (openrouter.ai)", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = openRouterKey,
            onValueChange = { openRouterKey = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(16.dp))

        Text("OpenRouter free model id", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = openRouterModel,
            onValueChange = { openRouterModel = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("e.g. meta-llama/llama-3.1-8b-instruct:free — check openrouter.ai/models for current free options") }
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("AI #1 name", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = name1, onValueChange = { name1 = it }, singleLine = true)
            }
            Column(Modifier.weight(1f)) {
                Text("AI #2 name", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = name2, onValueChange = { name2 = it }, singleLine = true)
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                settings.geminiApiKey = geminiKey.trim()
                settings.openRouterApiKey = openRouterKey.trim()
                settings.openRouterModel = openRouterModel.trim().ifBlank { SettingsStore.DEFAULT_OPENROUTER_MODEL }
                settings.aiOneName = name1.trim().ifBlank { "Nova" }
                settings.aiTwoName = name2.trim().ifBlank { "Echo" }
                onDone()
            },
            enabled = geminiKey.isNotBlank() && openRouterKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save & Start")
        }
    }
}

@Composable
private fun ConversationScreen(
    settings: SettingsStore,
    activity: ComponentActivity,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val transcript = remember { mutableStateListOf<TranscriptEntry>() }
    val listState = rememberLazyListState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    var isUserListening by remember { mutableStateOf(false) }
    var isAiBusy by remember { mutableStateOf(false) } // generating or speaking
    var bargeInEnabled by remember { mutableStateOf(true) }
    var interrupted by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Tap the mic and say something to the group.") }

    val voiceOutput = remember {
        VoiceOutputManager(activity) { speaking ->
            // reflect aggregate speaking state; queue draining also implies "busy"
            if (!speaking) { /* leave isAiBusy control to the round loop below */ }
        }
    }

    val participants = remember(
        settings.geminiApiKey, settings.openRouterApiKey, settings.openRouterModel,
        settings.aiOneName, settings.aiTwoName
    ) {
        listOf<AiParticipant>(
            GeminiParticipant(
                name = settings.aiOneName,
                personaHint = "You tend to be warm, curious, and quick to build on ideas.",
                apiKey = settings.geminiApiKey
            ),
            OpenRouterParticipant(
                name = settings.aiTwoName,
                personaHint = "You tend to be a bit more analytical and dry-witted, and like to gently push back or add nuance.",
                model = settings.openRouterModel,
                apiKey = settings.openRouterApiKey
            )
        )
    }
    val conversationManager = remember(participants) { ConversationManager(participants) }

    val speechInput = remember {
        SpeechInputManager(
            context = activity,
            onFinalResult = { text ->
                if (isAiBusy) {
                    // Barge-in: user spoke while the AIs were talking.
                    interrupted = true
                    voiceOutput.stopSpeaking()
                }
                scope.launch {
                    isAiBusy = true
                    interrupted = false
                    statusText = "…"
                    conversationManager.onUserSpeech(
                        text = text,
                        onEntry = { entry ->
                            transcript.add(entry)
                            voiceOutput.speak(entry.speaker, entry.text)
                        },
                        isInterrupted = { interrupted }
                    )
                    isAiBusy = false
                    statusText = "Tap the mic to talk."
                }
            },
            onListeningStateChanged = { listening -> isUserListening = listening },
            onError = { err -> statusText = err }
        )
    }

    // While the AIs are generating/speaking, keep a lightweight recognizer armed so the
    // user can interrupt by voice. See SpeechInputManager's barge-in caveat re: echo pickup.
    LaunchedEffect(isAiBusy, bargeInEnabled) {
        if (isAiBusy && bargeInEnabled) {
            speechInput.startBargeInWatch()
        }
    }

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AiConverse", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = bargeInEnabled, onCheckedChange = { bargeInEnabled = it })
            Spacer(Modifier.width(8.dp))
            Text("Allow interrupting the AIs by voice (works best with headphones)", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(transcript) { entry ->
                TranscriptBubble(entry)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(statusText, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            FloatingActionButton(
                onClick = {
                    if (!hasMicPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@FloatingActionButton
                    }
                    if (isUserListening) {
                        speechInput.stop()
                    } else {
                        voiceOutput.stopSpeaking()
                        interrupted = true
                        speechInput.startListening()
                    }
                }
            ) {
                Icon(
                    if (isUserListening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = "Talk"
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TranscriptBubble(entry: TranscriptEntry) {
    val isUser = entry.speaker == "User"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            entry.speaker,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                entry.text,
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
