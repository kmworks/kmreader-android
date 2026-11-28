package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.network.ServerInstance
import com.kmworks.kmreader.ui.RootViewModel
import com.kmworks.kmreader.ui.components.KmDropdownMenu
import com.kmworks.kmreader.ui.login.LoginScreen
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Server account list screen (mirrors iOS ServerListView management mode + ServerRowView rich cards):
 * saved instances sorted by lastUsedAt descending; cards show avatar/name/URL/detail rows/Active badge.
 * Tapping a card switches to it (the current instance is not tappable); long-press shows a delete menu;
 * Add Server and Logout sit at the bottom.
 * Logout only clears the active session config; instance credentials are kept for one-tap switching back
 * (mirrors the default iOS logout).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onBack: () -> Unit,
    viewModel: ServerListViewModel = hiltViewModel(),
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val activeConfig by viewModel.activeConfig.collectAsStateWithLifecycle()
    val switchingId by viewModel.switchingInstanceId.collectAsStateWithLifecycle()
    var showAddServer by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ServerInstance?>(null) }
    var deleteTarget by remember { mutableStateOf<ServerInstance?>(null) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    if (showAddServer) {
        // Reuse the login form to add a server; on success it writes the active session and appears in the list (mirrors iOS presenting LoginView as a sheet)
        LoginScreen(onLoggedIn = { showAddServer = false })
        return
    }

    editTarget?.let { target ->
        // Editing always operates on the latest instance value (renames/validation may have updated the list)
        val current = instances.firstOrNull { it.id == target.id } ?: target
        ServerEditScreen(
            instance = current,
            onBack = { editTarget = null },
            onSaved = { editTarget = null },
        )
        return
    }

    SystemScaffold(title = stringResource(R.string.server_list_title), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            if (instances.isEmpty()) {
                item {
                    // Empty state (mirrors iOS "No servers added yet")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.server_list_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(instances, key = { it.id }) { instance ->
                val active = activeConfig?.let {
                    it.normalizedBaseUrl == instance.normalizedBaseUrl && it.username == instance.username
                } == true
                ServerCard(
                    instance = instance,
                    active = active,
                    switching = switchingId == instance.id,
                    onClick = {
                        if (!active && switchingId == null) viewModel.switchTo(instance)
                    },
                    onEdit = { editTarget = instance },
                    onDelete = { deleteTarget = instance },
                )
                Spacer(Modifier.height(10.dp))
            }
            item {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { showAddServer = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.server_add))
                }
                TextButton(
                    onClick = { showLogoutConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.server_logout))
                }
            }
        }
    }

    deleteTarget?.let { target ->
        val isActive = activeConfig?.let {
            it.normalizedBaseUrl == target.normalizedBaseUrl && it.username == target.username
        } == true
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.server_delete_title)) },
            text = {
                Text(
                    if (isActive) {
                        stringResource(R.string.server_delete_active_message, target.displayName)
                    } else {
                        stringResource(R.string.server_delete_message, target.displayName)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        if (isActive) rootViewModel.logout()
                        viewModel.delete(target)
                    },
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.server_logout)) },
            text = { Text(stringResource(R.string.server_logout_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        rootViewModel.logout()
                    },
                ) {
                    Text(stringResource(R.string.server_logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * Server rich card (mirrors iOS ServerRowView): gradient card, circular avatar (admin crown / server glyph),
 * name/URL, Active capsule, detail rows (username/role/auth method/last used) and a switching spinner.
 * The current instance is highlighted with the green tint and border; long-press shows the edit/delete menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    instance: ServerInstance,
    active: Boolean,
    switching: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    val accent = Color(0xFF34C759)
    val cardBrush = if (active) {
        Brush.linearGradient(listOf(accent.copy(alpha = 0.10f), accent.copy(alpha = 0.04f)))
    } else {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
            ),
        )
    }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(cardBrush)
                .border(
                    width = if (active) 1.5.dp else 1.dp,
                    color = if (active) accent.copy(alpha = 0.30f) else MaterialTheme.colorScheme.outlineVariant,
                    shape = shape,
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Circular avatar (iOS serverAvatar): green gradient for the active server.
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) {
                                Brush.linearGradient(listOf(accent, Color(0xFF30D158)))
                            } else {
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    ),
                                )
                            },
                            CircleShape,
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (instance.isAdmin) {
                            Icons.Filled.AdminPanelSettings
                        } else {
                            Icons.Outlined.Dns
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = instance.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = instance.normalizedBaseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                when {
                    switching -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    active -> Row(
                        modifier = Modifier
                            .background(accent.copy(alpha = 0.16f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = accent,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.server_active_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                    else -> Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            Spacer(Modifier.height(10.dp))
            InfoRow(icon = { InfoIcon(Icons.Outlined.Person) }, text = instance.username)
            Spacer(Modifier.height(6.dp))
            InfoRow(
                icon = { InfoIcon(Icons.Outlined.VerifiedUser) },
                text = if (instance.isAdmin) {
                    stringResource(R.string.server_role_admin)
                } else {
                    stringResource(R.string.server_role_user)
                },
                emphasized = instance.isAdmin,
            )
            Spacer(Modifier.height(6.dp))
            InfoRow(
                icon = { InfoIcon(Icons.Outlined.Key) },
                text = if (instance.useApiKey) "API Key" else stringResource(R.string.server_auth_username_password),
            )
            Spacer(Modifier.height(6.dp))
            InfoRow(
                icon = { InfoIcon(Icons.Outlined.Schedule) },
                text = stringResource(R.string.server_last_used_format, relativeTime(instance.lastUsedAt)),
            )
        }
        KmDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_edit)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** Small icon for detail rows (16dp slot, mirrors iOS infoDetailRow). */
@Composable
private fun InfoIcon(imageVector: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoRow(
    icon: @Composable () -> Unit,
    text: String,
    emphasized: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.CenterStart) {
            icon()
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (emphasized) {
                Color(0xFF34C759)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Relative time (mirrors the .relative format of iOS lastUsedDescription). */
@Composable
private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> stringResource(R.string.settings_time_just_now)
        minutes < 60 -> stringResource(R.string.settings_time_minutes_ago, minutes)
        hours < 24 -> stringResource(R.string.settings_time_hours_ago, hours)
        days < 7 -> stringResource(R.string.settings_time_days_ago, days)
        // LocalConfiguration.current recomposes on locale change; Locale.getDefault() is not observable.
        else -> SimpleDateFormat("yyyy-MM-dd", LocalConfiguration.current.locales[0]).format(Date(epochMs))
    }
}
