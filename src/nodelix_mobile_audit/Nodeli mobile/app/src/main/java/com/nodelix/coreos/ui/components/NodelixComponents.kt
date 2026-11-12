package com.nodelix.coreos.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nodelix.coreos.domain.model.DataFreshness
import com.nodelix.coreos.ui.theme.NodelixBorder
import com.nodelix.coreos.ui.theme.NodelixCard
import com.nodelix.coreos.ui.theme.NodelixCardSecondary
import com.nodelix.coreos.ui.theme.NodelixError
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixSecondary
import com.nodelix.coreos.ui.theme.NodelixSuccess
import com.nodelix.coreos.ui.theme.NodelixTextPrimary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.ui.theme.NodelixWarning
import com.nodelix.coreos.util.DateFormatter

enum class BadgeTone {
    SUCCESS,
    INFO,
    WARNING,
    ERROR,
    NEUTRAL
}

@Composable
fun NodelixCard(
    modifier: Modifier = Modifier,
    secondarySurface: Boolean = false,
    accentBorder: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val bg = if (secondarySurface) NodelixCardSecondary else NodelixCard
    val border = accentBorder ?: NodelixBorder
    val shape = RoundedCornerShape(16.dp)
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Surface(
        modifier = modifier
            .clip(shape)
            .then(clickMod),
        shape = shape,
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    tone: BadgeTone,
    modifier: Modifier = Modifier,
    showDot: Boolean = true
) {
    val fg = when (tone) {
        BadgeTone.SUCCESS -> NodelixSuccess
        BadgeTone.INFO -> NodelixSecondary
        BadgeTone.WARNING -> NodelixWarning
        BadgeTone.ERROR -> NodelixError
        BadgeTone.NEUTRAL -> NodelixTextSecondary
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = fg.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.36f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(fg)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = fg
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NodelixSecondary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NodelixTextSecondary
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun MetricStatTile(
    label: String,
    value: String,
    unit: String = "",
    badgeText: String? = null,
    badgeTone: BadgeTone = BadgeTone.NEUTRAL,
    progressFraction: Float? = null,
    modifier: Modifier = Modifier
) {
    NodelixCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NodelixTextSecondary
            )
            if (badgeText != null) {
                StatusPill(text = badgeText, tone = badgeTone, showDot = false)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = NodelixMonoStyles.metricValue
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NodelixTextSecondary,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
        if (progressFraction != null) {
            Spacer(Modifier.height(10.dp))
            CyberLinearBar(
                fraction = progressFraction,
                color = when (badgeTone) {
                    BadgeTone.ERROR -> NodelixError
                    BadgeTone.WARNING -> NodelixWarning
                    BadgeTone.INFO -> NodelixSecondary
                    else -> NodelixPrimary
                }
            )
        }
    }
}

@Composable
fun CyberLinearBar(
    fraction: Float,
    color: Color = NodelixPrimary,
    modifier: Modifier = Modifier
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(NodelixCardSecondary)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
    }
}

@Composable
fun FreshnessIndicatorRow(freshness: DataFreshness) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusPill(
            text = if (freshness.isFromCache) "Cached Snapshot" else "Live Stream",
            tone = if (freshness.isFromCache) BadgeTone.WARNING else BadgeTone.SUCCESS
        )
        Text(
            text = "Sync: ${DateFormatter.formatShortTime(freshness.lastSyncMillis)}",
            style = NodelixMonoStyles.technical,
            color = NodelixTextSecondary
        )
    }
}

@Composable
fun LoadingStateCard(message: String = "Synchronizing with NodelixcoreOS Server...") {
    NodelixCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = NodelixPrimary
            )
            Spacer(Modifier.width(14.dp))
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun EmptyOrStatusCard(
    icon: ImageVector,
    title: String,
    description: String,
    tone: BadgeTone = BadgeTone.NEUTRAL,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val accent = when (tone) {
        BadgeTone.ERROR -> NodelixError
        BadgeTone.WARNING -> NodelixWarning
        BadgeTone.SUCCESS -> NodelixPrimary
        BadgeTone.INFO -> NodelixSecondary
        BadgeTone.NEUTRAL -> NodelixBorder
    }
    NodelixCard(
        modifier = Modifier.fillMaxWidth(),
        accentBorder = accent.copy(alpha = 0.55f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (tone == BadgeTone.NEUTRAL) NodelixTextSecondary else accent,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onAction,
                border = BorderStroke(1.dp, NodelixPrimary.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun OfflineBannerCard(message: String, onRetry: () -> Unit, onConfigureServer: (() -> Unit)? = null) {
    NodelixCard(
        modifier = Modifier.fillMaxWidth(),
        accentBorder = NodelixWarning.copy(alpha = 0.6f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = NodelixWarning)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("SERVER OFFLINE / UNREACHABLE", style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = NodelixPrimary, contentColor = Color(0xFF002116))
            ) {
                Text("Retry", fontWeight = FontWeight.SemiBold)
            }
            if (onConfigureServer != null) {
                OutlinedButton(onClick = onConfigureServer) {
                    Text("Server Settings")
                }
            }
        }
    }
}

@Composable
fun AuthRequiredCard(reason: String, onSignIn: () -> Unit) {
    EmptyOrStatusCard(
        icon = Icons.Outlined.Lock,
        title = "Authentication Required",
        description = reason,
        tone = BadgeTone.WARNING,
        actionLabel = "Authenticate Now",
        onAction = onSignIn
    )
}

@Composable
fun ErrorStateCard(message: String, onRetry: (() -> Unit)? = null) {
    EmptyOrStatusCard(
        icon = Icons.Outlined.ErrorOutline,
        title = "Operation Error",
        description = message,
        tone = BadgeTone.ERROR,
        actionLabel = if (onRetry != null) "Retry" else null,
        onAction = onRetry
    )
}

@Composable
fun ConfirmSensitiveActionDialog(
    title: String,
    description: String,
    confirmLabel: String,
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NodelixCard,
        titleContentColor = NodelixTextPrimary,
        textContentColor = NodelixTextSecondary,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = if (isDestructive) NodelixError else NodelixWarning
            )
        },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(description, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDestructive) NodelixError else NodelixPrimary,
                    contentColor = if (isDestructive) Color.White else Color(0xFF002116)
                )
            ) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NodelixTextSecondary)
            }
        }
    )
}
