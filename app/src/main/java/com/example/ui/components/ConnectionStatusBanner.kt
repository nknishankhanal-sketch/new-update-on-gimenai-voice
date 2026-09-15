package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.ConnectionManager
import com.example.ui.theme.CoralWarning
import com.example.ui.theme.DeepDarkBg
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark

@Composable
fun ConnectionStatusBanner(
    state: ConnectionManager.ConnectionState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (color, icon, message, actionText, showAction, actionTarget) = when (state) {
        is ConnectionManager.ConnectionState.Connecting -> {
            val strategy = (state as ConnectionManager.ConnectionState.Connecting).strategy
            NeonCyan to Icons.Default.Sync to "Connecting via ${strategy.name}..." to null to false to null
        }
        is ConnectionManager.ConnectionState.Degraded -> {
            val (strategy, reason) = (state as ConnectionManager.ConnectionState.Degraded).component1() to (state as ConnectionManager.ConnectionState.Degraded).component2()
            CoralWarning to Icons.Default.Warning to "Degraded: $reason" to "Retry Live" to true to "retry"
        }
        is ConnectionManager.ConnectionState.Error -> {
            val msg = (state as ConnectionManager.ConnectionState.Error).message
            CoralWarning to Icons.Default.Error to "Error: $msg" to "Open Settings" to true to "settings"
        }
        else -> NeonCyan to Icons.Default.CheckCircle to "Connected" to null to false to null
    }

    if (!state.shouldShowBanner) return

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .testTag("connection_status_banner")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = message, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            if (showAction) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = if (actionTarget == "settings") onOpenSettings else onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = color),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("banner_action_button")
                ) {
                    Text(
                        text = actionText!!,
                        color = if (color == CoralWarning) TextPrimaryDark else DeepDarkBg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}