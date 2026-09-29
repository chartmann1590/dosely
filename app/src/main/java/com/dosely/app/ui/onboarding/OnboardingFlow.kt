@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dosely.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.ai.DownloadState
import com.dosely.app.ai.GemmaModelCatalog
import com.dosely.app.domain.Units
import com.dosely.app.translate.AppLanguages
import com.dosely.app.translate.S
import com.dosely.app.ui.components.Chip
import com.dosely.app.ui.theme.MintStrong
import org.koin.androidx.compose.koinViewModel

@Composable
fun OnboardingFlow(viewModel: OnboardingViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        AnimatedContent(
            targetState = ui.stage,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(tween(320)) { it / 6 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(320)) { -it / 6 } + fadeOut(tween(200)))
                } else {
                    (slideInHorizontally(tween(320)) { -it / 6 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(320)) { it / 6 } + fadeOut(tween(200)))
                }
            },
            label = "onb",
        ) { stage ->
            when (stage) {
                0 -> WelcomeStage { viewModel.update { it.copy(stage = 1) } }
                1 -> MedicationStage(viewModel)
                2 -> ScheduleStage(viewModel)
                3 -> WeightStage(viewModel)
                4 -> LanguageStage(viewModel)
                5 -> AiStage(viewModel)
                6 -> NotificationsStage(viewModel)
                else -> DoneStage(viewModel)
            }
        }
    }
}

@Composable
private fun OnbScaffold(
    step: Int,
    title: String,
    subtitle: String,
    cta: String,
    enabled: Boolean = true,
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(7) { i ->
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (i <= step) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onBack != null) {
                    Button(
                        onClick = onBack,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(S("onb_back")) }
                }
                Button(
                    onClick = onNext,
                    enabled = enabled,
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(18.dp),
                ) { Text(cta, Modifier.padding(vertical = 6.dp)) }
            }
        }
    }
}

@Composable
private fun WelcomeStage(onNext: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(40.dp))
        Column {
            Text("💉", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(18.dp))
            Text(S("onb_welcome_title"), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                S("onb_welcome_sub"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                S("onb_privacy_sub"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) { Text(S("onb_welcome_cta"), Modifier.padding(vertical = 8.dp)) }
    }
}

@Composable
private fun MedicationStage(viewModel: OnboardingViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OnbScaffold(
        step = 1,
        title = S("onb_med_title"),
        subtitle = S("onb_med_sub"),
        cta = S("onb_next"),
        onBack = { viewModel.update { it.copy(stage = 0) } },
        onNext = { viewModel.update { it.copy(stage = 2) } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            viewModel.meds.forEachIndexed { index, med ->
                val selected = ui.medId == med.id
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        )
                        .clickable { viewModel.update { it.copy(medId = med.id, intervalDays = med.intervalDays) } }
                        .padding(16.dp),
                ) {
                    Column(Modifier.fillMaxWidth(0.85f)) {
                        Text(med.brand, style = MaterialTheme.typography.titleMedium)
                        Text(
                            med.active + " · " + S("onb_dose_every") + " " + med.intervalDays + " " + S("onb_dose_days"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Filled.Check, contentDescription = null,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleStage(viewModel: OnboardingViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val timeState = rememberTimePickerState(
        initialHour = ui.reminderHour,
        initialMinute = ui.reminderMinute,
        is24Hour = true,
    )
    OnbScaffold(
        step = 2,
        title = S("onb_dose_title"),
        subtitle = S("onb_dose_sub", ui.intervalDays),
        cta = S("onb_next"),
        onBack = { viewModel.update { it.copy(stage = 1) } },
        onNext = {
            viewModel.update {
                it.copy(
                    reminderHour = timeState.hour,
                    reminderMinute = timeState.minute,
                    firstDoseDay = parseDateText(ui.firstDoseText) ?: it.firstDoseDay,
                    stage = 3,
                )
            }
        },
    ) {
        Text(S("onb_dose_every"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 28).forEach { d ->
                Chip(
                    text = "$d " + S("onb_dose_days"),
                    selected = ui.intervalDays == d,
                    onClick = { viewModel.update { it.copy(intervalDays = d) } },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(S("onb_dose_time"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        TimePicker(timeState)
        Spacer(Modifier.height(20.dp))
        Text(S("onb_dose_first"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ui.firstDoseText,
            onValueChange = { text -> viewModel.update { it.copy(firstDoseText = text) } },
            placeholder = { Text("YYYY-MM-DD") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

private fun parseDateText(text: String): Long? = try {
    java.time.LocalDate.parse(text.trim()).toEpochDay()
} catch (_: Exception) {
    null
}

@Composable
private fun WeightStage(viewModel: OnboardingViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OnbScaffold(
        step = 3,
        title = S("onb_weight_title"),
        subtitle = S("onb_weight_sub"),
        cta = S("onb_next"),
        enabled = (ui.startWeightDisplay.replace(',', '.').toDoubleOrNull() ?: 0.0) > 0,
        onBack = { viewModel.update { it.copy(stage = 2) } },
        onNext = { viewModel.update { it.copy(stage = 4) } },
    ) {
        Text(S("onb_weight_units"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(
                text = S("settings_units_metric"),
                selected = !ui.useImperial,
                onClick = { viewModel.update { it.copy(useImperial = false) } },
            )
            Chip(
                text = S("settings_units_imperial"),
                selected = ui.useImperial,
                onClick = { viewModel.update { it.copy(useImperial = true) } },
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = ui.startWeightDisplay,
            onValueChange = { v ->
                viewModel.update {
                    it.copy(startWeightDisplay = v.filter { c -> c.isDigit() || c == '.' || c == ',' })
                }
            },
            label = { Text(S("onb_weight_kg") + " (" + Units.label(ui.useImperial) + ")") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = ui.goalWeightDisplay,
            onValueChange = { v ->
                viewModel.update {
                    it.copy(goalWeightDisplay = v.filter { c -> c.isDigit() || c == '.' || c == ',' })
                }
            },
            label = { Text(S("onb_weight_goal") + " (" + Units.label(ui.useImperial) + ")") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
private fun LanguageStage(viewModel: OnboardingViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OnbScaffold(
        step = 4,
        title = S("onb_lang_title"),
        subtitle = S("onb_lang_sub"),
        cta = S("onb_next"),
        enabled = !ui.langBusy,
        onBack = { viewModel.update { it.copy(stage = 3) } },
        onNext = { viewModel.update { it.copy(stage = 5) } },
    ) {
        if (ui.langError != null) {
            Text(
                S("common_error") + ": " + ui.langError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppLanguages.all.forEach { lang ->
                val selected = ui.langTag == lang.tag
                val busy = ui.langBusy && selected
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        )
                        .clickable(enabled = !ui.langBusy) { viewModel.selectLanguage(lang.tag) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(lang.englishName, Modifier.fillMaxWidth(0.9f))
                    when {
                        busy -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).align(Alignment.CenterEnd),
                            strokeWidth = 2.dp,
                        )
                        selected -> Icon(
                            Icons.Filled.Check, contentDescription = null,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiStage(viewModel: OnboardingViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OnbScaffold(
        step = 5,
        title = S("onb_ai_title"),
        subtitle = S("onb_ai_sub"),
        cta = S("onb_next"),
        onBack = { viewModel.update { it.copy(stage = 4) } },
        onNext = { viewModel.update { it.copy(stage = 6) } },
    ) {
        val size = GemmaModelCatalog.humanSize(GemmaModelCatalog.default.sizeBytes)
        Text(S("onb_ai_uses", size), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
        when (val dl = ui.aiDownload) {
            is DownloadState.Downloading -> {
                LinearProgressIndicator(
                    progress = { dl.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    S("onb_ai_downloading", GemmaModelCatalog.humanSize(dl.downloadedBytes)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            DownloadState.Done -> Text(
                S("onb_ai_ready"),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
            else -> Button(onClick = { viewModel.startModelDownload() }) {
                Text(S("onb_ai_download", size))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            S("onb_ai_skip"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NotificationsStage(viewModel: OnboardingViewModel) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.update { it.copy(stage = 7) } }
    OnbScaffold(
        step = 6,
        title = S("onb_rem_title"),
        subtitle = S("onb_rem_sub"),
        cta = S("onb_rem_allow"),
        onBack = { viewModel.update { it.copy(stage = 5) } },
        onNext = {
            if (Build.VERSION.SDK_INT >= 33) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.update { it.copy(stage = 7) }
            }
        },
    ) {
        Text(
            S("onb_rem_later"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneStage(viewModel: OnboardingViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(60.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.CheckCircle, contentDescription = null,
                tint = MintStrong, modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                S("onb_done_title"),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                S("onb_done_sub"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = { viewModel.complete() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) { Text(S("onb_done_cta"), Modifier.padding(vertical = 8.dp)) }
    }
}
