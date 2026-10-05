package com.dosely.app.wear

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dosely.app.ui.components.SectionCard
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun WatchConnectionCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Checking paired watches…") }
    suspend fun check() {
        status = runCatching {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) "No watch connected right now. Offline logs will sync when it reconnects."
            else "Connected: " + nodes.joinToString { it.displayName }
        }.getOrDefault("Wear OS connection unavailable. Check Google Play services and watch pairing.")
        PhoneWatchSync.enqueue(context)
    }
    LaunchedEffect(Unit) { check() }
    SectionCard {
        Text("Your wrist. Your routine.", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text("Log shots, weight, and water in Dosely for Wear OS. Install the watch companion and pair your watch with this phone.")
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { scope.launch { check() } }) { Text("Refresh watch connection") }
    }
}
