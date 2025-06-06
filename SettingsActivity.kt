package com.example.echonum

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                SettingsScreen(
                    onBackPress = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackPress: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
    }
    var vpnOnStartup by remember { mutableStateOf(false) }
    var showSystemApps by remember { mutableStateOf(true) }
    var enableNotifications by remember { mutableStateOf(false) }
    var dataUsageLimit by remember { mutableFloatStateOf(50f) }

    // Load saved values on first composition
    LaunchedEffect(Unit) {
        vpnOnStartup = prefs.getBoolean("vpn_on_boot", false)
        showSystemApps = prefs.getBoolean("show_system_apps", true)
        enableNotifications = prefs.getBoolean("enable_notifications", false)
        dataUsageLimit = prefs.getFloat("data_usage_limit", 50f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // VPN Auto-start setting
            SettingCard(
                title = "Start VPN on boot",
                description = "Automatically start the VPN service when your device boots up",
                switchValue = vpnOnStartup,
                onSwitchChange = {
                    vpnOnStartup = it
                    prefs.edit { putBoolean("vpn_on_boot", it) }
                }
            )

            // Show system apps setting
            SettingCard(
                title = "Show system apps",
                description = "Display system applications in the app list",
                switchValue = showSystemApps,
                onSwitchChange = {
                    showSystemApps = it
                    prefs.edit { putBoolean("show_system_apps", it) }
                }
            )

            // Enable notifications setting
            SettingCard(
                title = "Enable Notifications",
                description = "Receive notifications for important updates",
                switchValue = enableNotifications,
                onSwitchChange = {
                    enableNotifications = it
                    prefs.edit { putBoolean("enable_notifications", it) }
                }
            )

            // Data usage limit setting
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Data Usage Limit",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Set a limit for data usage (in GB)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = dataUsageLimit,
                        onValueChange = {
                            dataUsageLimit = it
                            prefs.edit { putFloat("data_usage_limit", it) }
                        },
                        valueRange = 1f..100f,
                        steps = 99
                    )
                    Text(
                        text = "Limit: ${dataUsageLimit.toInt()} GB",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun SettingCard(
    title: String,
    description: String,
    switchValue: Boolean,
    onSwitchChange: (Boolean) -> Unit
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = switchValue,
                onCheckedChange = onSwitchChange
            )
        }
    }
}