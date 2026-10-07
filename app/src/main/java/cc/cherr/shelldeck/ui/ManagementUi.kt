package cc.cherr.shelldeck.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import cc.cherr.shelldeck.SessionConnection

@Composable
fun ManagementHeading(title: String, summary: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ManagementEmpty(@DrawableRes icon: Int, title: String, message: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ConnectionBadge(session: SessionConnection) {
    val label = when {
        session.ended -> "已断开"
        session.challenge != null -> "待确认"
        session.passphraseIdentity != null -> "待认证"
        session.connected -> "已连接"
        else -> "连接中"
    }
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(8.dp),
        color = when { session.ended -> colors.surfaceContainerHighest; session.connected -> colors.secondaryContainer; else -> colors.tertiaryContainer },
        contentColor = when { session.ended -> colors.onSurfaceVariant; session.connected -> colors.onSecondaryContainer; else -> colors.onTertiaryContainer }) {
        Text(label, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}
