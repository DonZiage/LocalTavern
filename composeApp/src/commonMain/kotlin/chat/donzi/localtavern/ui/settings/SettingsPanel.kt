package chat.donzi.localtavern.ui.settings
import chat.donzi.localtavern.ui.sync.SyncSettingsSection
import chat.donzi.localtavern.ui.layout.CollapsibleSettingsSection

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.sync.SyncDiscovery
import chat.donzi.localtavern.data.sync.SyncRepository
import chat.donzi.localtavern.data.sync.SyncService
import chat.donzi.localtavern.isDesktop
import kotlinx.coroutines.launch

@Composable
fun SettingsPanelContent(
    apiSettingsRepository: ApiSettingsRepository,
    apiKeyCipher: ApiKeyCipher,
    syncService: SyncService,
    syncRepository: SyncRepository,
    syncDiscovery: SyncDiscovery,
    chatClient: ChatClient,
    onEnsureSyncRunning: () -> Unit,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    onApiChanged: () -> Unit,
    autoSyncOnLaunch: Boolean,
    onAutoSyncOnLaunchChange: (Boolean) -> Unit,
    sendWithCtrlEnter: Boolean,
    onSendWithCtrlEnterChange: (Boolean) -> Unit,
    confirmBeforeDelete: Boolean,
    onConfirmBeforeDeleteChange: (Boolean) -> Unit,
    autoLockIdleMinutes: Int = 10,
    onAutoLockIdleMinutesChange: (Int) -> Unit = {},
    apiSectionExpanded: Boolean,
    onApiSectionExpandedChange: (Boolean) -> Unit,
    syncSectionExpanded: Boolean = false,
    onSyncSectionExpandedChange: (Boolean) -> Unit = {},
    appSettingsSectionExpanded: Boolean = false,
    onAppSettingsSectionExpandedChange: (Boolean) -> Unit = {},
    securitySectionExpanded: Boolean = false,
    onSecuritySectionExpandedChange: (Boolean) -> Unit = {},
    scrollState: ScrollState = rememberScrollState()
) {
    val scope = rememberCoroutineScope()
    val deviceName by syncService.deviceName.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                CollapsibleSettingsSection(
                    title = "API Connection",
                    expanded = apiSectionExpanded,
                    onExpandedChange = onApiSectionExpandedChange
                ) {
                    ApiConnectionSettings(
                        apiSettingsRepository = apiSettingsRepository,
                        chatClient = chatClient,
                        apiKeyCipher = apiKeyCipher,
                        onApiChanged = onApiChanged
                    )
                }

                CollapsibleSettingsSection(
                    title = "Device Sync",
                    expanded = syncSectionExpanded,
                    onExpandedChange = onSyncSectionExpandedChange
                ) {
                    SyncSettingsSection(
                        syncService = syncService,
                        syncRepository = syncRepository,
                        syncDiscovery = syncDiscovery,
                        onEnsureSyncRunning = onEnsureSyncRunning
                    )
                }

                CollapsibleSettingsSection(
                    title = "App Settings",
                    expanded = appSettingsSectionExpanded,
                    onExpandedChange = onAppSettingsSectionExpandedChange
                ) {
                    AppSettingsSection(
                        isDarkMode = isDarkMode,
                        onToggleDarkMode = onToggleDarkMode,
                        autoSyncOnLaunch = autoSyncOnLaunch,
                        onAutoSyncOnLaunchChange = onAutoSyncOnLaunchChange,
                        sendWithCtrlEnter = sendWithCtrlEnter,
                        onSendWithCtrlEnterChange = onSendWithCtrlEnterChange,
                        confirmBeforeDelete = confirmBeforeDelete,
                        onConfirmBeforeDeleteChange = onConfirmBeforeDeleteChange,
                        deviceName = deviceName,
                        onRenameDevice = { newName -> scope.launch { syncService.renameDevice(newName) } }
                    )
                }

                // Desktop-only: mobile keys live in the OS keystore/keychain
                // (no passphrase, no auto-lock UI), so this section is purely
                // informational there — device security is handled by the
                // unlock gate and the one-time lock recommendation instead.
                if (isDesktop) {
                    CollapsibleSettingsSection(
                        title = "API Key Security",
                        expanded = securitySectionExpanded,
                        onExpandedChange = onSecuritySectionExpandedChange
                    ) {
                        SecuritySettingsSection(
                            apiKeyCipher = apiKeyCipher,
                            apiSettingsRepository = apiSettingsRepository,
                            onKeysChanged = onApiChanged,
                            autoLockIdleMinutes = autoLockIdleMinutes,
                            onAutoLockIdleMinutesChange = onAutoLockIdleMinutesChange
                        )
                    }
                }
            }
        }
    }
}
