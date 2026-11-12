package com.nodelix.coreos.ui.notifications

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.domain.model.AppNotification
import com.nodelix.coreos.domain.model.NotificationCategory
import com.nodelix.coreos.ui.components.BadgeTone
import com.nodelix.coreos.ui.components.EmptyOrStatusCard
import com.nodelix.coreos.ui.components.NodelixCard
import com.nodelix.coreos.ui.components.StatusPill
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.util.DateFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificationsViewModel(
    private val container: AppContainer
) : ViewModel() {

    val notifications: StateFlow<List<AppNotification>> = container.notificationRepository.notifications

    private val _selectedCategory = MutableStateFlow<NotificationCategory?>(null)
    val selectedCategory: StateFlow<NotificationCategory?> = _selectedCategory.asStateFlow()

    fun selectCategory(category: NotificationCategory?) {
        _selectedCategory.value = category
    }

    fun markRead(id: String) {
        viewModelScope.launch { container.notificationRepository.markAsRead(id) }
    }

    fun markAllRead() {
        viewModelScope.launch { container.notificationRepository.markAllAsRead() }
    }

    fun clearFiltered() {
        viewModelScope.launch {
            container.notificationRepository.clearNotifications(_selectedCategory.value)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NotificationsViewModel(container) as T
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel,
    onNavigateBack: () -> Unit
) {
    val allItems by viewModel.notifications.collectAsStateWithLifecycle()
    val categoryFilter by viewModel.selectedCategory.collectAsStateWithLifecycle()
    var detailItem by remember { mutableStateOf<AppNotification?>(null) }

    val filtered = remember(allItems, categoryFilter) {
        if (categoryFilter == null) allItems else allItems.filter { it.category == categoryFilter }
    }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("Notification Center") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.markAllRead() }) {
                        Icon(Icons.Outlined.DoneAll, contentDescription = "Mark All Read")
                    }
                    IconButton(onClick = { viewModel.clearFiltered() }) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NodelixBackground)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = categoryFilter == null,
                        onClick = { viewModel.selectCategory(null) },
                        label = { Text("All") }
                    )
                    NotificationCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = categoryFilter == cat,
                            onClick = { viewModel.selectCategory(cat) },
                            label = { Text(cat.label) }
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    EmptyOrStatusCard(
                        icon = Icons.Outlined.NotificationsNone,
                        title = "No Activity Notifications",
                        description = "Live WebSocket alerts from Server, ESP32, Door Lock, Storage, Firmware, and Security appear here."
                    )
                }
            } else {
                items(filtered, key = { it.id }) { item ->
                    NodelixCard(
                        accentBorder = if (!item.isRead) NodelixPrimary else null,
                        onClick = {
                            viewModel.markRead(item.id)
                            detailItem = item
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatusPill(
                                text = item.category.label,
                                tone = if (item.isRead) BadgeTone.NEUTRAL else BadgeTone.SUCCESS,
                                showDot = !item.isRead
                            )
                            Text(
                                text = DateFormatter.formatEpochMillis(item.timestampMillis),
                                style = NodelixMonoStyles.technical,
                                color = NodelixTextSecondary
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(item.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    detailItem?.let { item ->
        AlertDialog(
            onDismissRequest = { detailItem = null },
            title = { Text(item.title) },
            text = {
                Column {
                    Text(
                        text = "${item.category.label} • ${DateFormatter.formatEpochMillis(item.timestampMillis)}",
                        style = NodelixMonoStyles.technical,
                        color = NodelixTextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(item.description, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {
                Button(onClick = { detailItem = null }) { Text("Close") }
            }
        )
    }
}
