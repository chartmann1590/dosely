package com.dosely.app.ui.doses

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dosely.sync.WatchContract

@Composable
fun SiteSelector(selected: String, choose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.AccessibilityNew, null, Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Rotate your injection sites. Select where this shot was given.", style = MaterialTheme.typography.bodySmall)
    }
    WatchContract.sites.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pair.forEach { site ->
                FilterChip(selected = selected == site, onClick = { choose(site) }, label = { Text(site, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.weight(1f))
            }
        }
    }
}
