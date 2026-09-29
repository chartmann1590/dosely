package com.dosely.app.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.translate.L
import com.dosely.app.translate.S
import com.dosely.app.ui.components.SectionCard
import org.koin.androidx.compose.koinViewModel

@Composable
fun CoachScreen(viewModel: CoachViewModel = koinViewModel()) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showDisclaimer by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Disclaimer gate on first visit
    LaunchedEffect(Unit) {
        val acked = viewModel.acknowledged()
        if (!acked) showDisclaimer = true
        viewModel.prepare()
    }

    LaunchedEffect(messages.size, ui.partial) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    S("coach_title"),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.clearChat() }) {
                    Icon(Icons.Filled.Delete, contentDescription = S("coach_clear"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    S("coach_context_on"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showDisclaimer = true }) {
                    Text(S("settings_disclaimer"), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (ui.generating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                S("home_disclaimer_short"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (ui.engineState is com.dosely.app.ai.CoachEngine.EngineState.NoModel) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                SectionCard {
                    Text(S("coach_not_ready"), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        S("coach_download"),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SuggestionChip(S("coach_suggestion_1")) { input = L(context, "coach_suggestion_1") }
                            SuggestionChip(S("coach_suggestion_2")) { input = L(context, "coach_suggestion_2") }
                            SuggestionChip(S("coach_suggestion_3")) { input = L(context, "coach_suggestion_3") }
                            SuggestionChip(S("coach_suggestion_4")) { input = L(context, "coach_suggestion_4") }
                        }
                    }
                }
                items(messages, key = { it.id }) { bubble ->
                    var showReport by remember { mutableStateOf(false) }
                    Column {
                        Bubble(isUser = bubble.isUser, text = bubble.text)
                        if (!bubble.isUser) {
                            androidx.compose.material3.TextButton(
                                onClick = { showReport = true },
                                modifier = Modifier.padding(start = 4.dp),
                            ) {
                                Text(
                                    S("report_flag"),
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    if (showReport) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showReport = false },
                            title = { Text(S("report_title")) },
                            text = { Text(S("report_body")) },
                            confirmButton = {
                                Button(onClick = {
                                    viewModel.reportMessage(bubble)
                                    showReport = false
                                }) { Text(S("report_flag")) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showReport = false }) { Text(S("doses_cancel")) }
                            },
                        )
                    }
                }
                if (ui.generating && ui.partial.isNotEmpty()) {
                    item {
                        Bubble(isUser = false, text = ui.partial + " ▌")
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        // Input bar
        if (ui.error != null) {
            Text(
                S("coach_error") + " (" + ui.error + ")",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(S("coach_input_hint")) },
                modifier = Modifier.weight(1f),
                maxLines = 3,
            )
            Spacer(Modifier.padding(4.dp))
            IconButton(
                onClick = {
                    viewModel.send(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !ui.generating,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = S("coach_send"),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showDisclaimer) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(S("coach_disclaimer_title")) },
            text = { Text(S("coach_disclaimer_body")) },
            confirmButton = {
                Button(onClick = {
                    viewModel.acknowledgeDisclaimer()
                    showDisclaimer = false
                }) { Text(S("coach_disclaimer_ack")) }
            },
        )
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun Bubble(isUser: Boolean, text: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp,
                    ),
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(14.dp),
        ) {
            Text(
                text,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
