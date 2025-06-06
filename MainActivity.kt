package com.example.echonum

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Track the VPN service state
    private var serviceRunning by mutableStateOf(false)
    // Register for VPN permission result
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check for required permissions
        if (!hasUsageStatsPermission()) {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        }

        setContent {
            AppTheme {
                val hasPermission = remember { hasUsageStatsPermission() }

                // Main screen UI
                MainScreen(
                    isServiceRunning = serviceRunning,
                    hasPermission = hasPermission,
                    onToggleService = { toggleVpnService() },
                    onSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if service is already running
        serviceRunning = isVpnServiceRunning()
        Log.d("MainActivity", "onResume: VPN service running status: $serviceRunning")

    }

    private fun toggleVpnService() {
        if (serviceRunning) {
            stopVpnService()
        } else {
            prepareVpnService()
        }
    }

    private fun prepareVpnService() {
        try {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                // Launch the intent to prompt the user for VPN permission
                vpnPermissionLauncher.launch(vpnIntent)
                Log.d("VPN", "Prompting user for VPN permission")
            } else {
                // VPN permission already granted. Start service as a foreground service.
                Log.d("VPN", "VPN permission already granted")
                startVpnService()
            }
        } catch (e: Exception) {
            Log.e("VPN", "Error preparing VPN service", e)
            e.printStackTrace()
            // Optionally notify the user
            Toast.makeText(this, "Error starting VPN service: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpnService() {
        try {
            Log.d("VPN", "Starting VPN service from MainActivity")
            val serviceIntent = Intent(this, ForegroundVpnService::class.java)
            startForegroundService(serviceIntent)
            // Update UI state immediately, then check actual service status after delay
            serviceRunning = true
            // Double-check service status after a short delay
            Handler(Looper.getMainLooper()).postDelayed({
                serviceRunning = isVpnServiceRunning()
                Log.d("VPN", "Service running status after delay: $serviceRunning")
            }, 500)
        } catch (e: Exception) {
            Log.e("VPN", "Error starting VPN service", e)
            Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_SHORT).show()
            serviceRunning = false
        }
    }

    @SuppressLint("ImplicitSamInstance")
    private fun stopVpnService() {
        try {
            Log.d("VPN", "Stopping VPN service from MainActivity")
            // Send explicit STOP action to ensure proper cleanup
            val intent = Intent(this, ForegroundVpnService::class.java).apply {
                action = "STOP_VPN_SERVICE"
            }
            startService(intent)

            // Update UI immediately, then verify service status
            serviceRunning = false

            // Verify service was actually stopped after a delay
            Handler(Looper.getMainLooper()).postDelayed({
                serviceRunning = isVpnServiceRunning()
                if (serviceRunning) {
                    Log.w("VPN", "Service didn't stop properly, forcing stop")
                    stopService(Intent(this, ForegroundVpnService::class.java))
                    // Update UI after another check
                    Handler(Looper.getMainLooper()).postDelayed({
                        serviceRunning = isVpnServiceRunning()
                        Log.d("VPN", "Final service running status: $serviceRunning")
                    }, 300)
                } else {
                    Log.d("VPN", "Confirmed service stopped successfully")
                }
            }, 500)
        } catch (e: Exception) {
            Log.e("VPN", "Error stopping VPN service", e)
            Toast.makeText(this, "Error stopping service: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isVpnServiceRunning(): Boolean {
        // First check our static flag in the service
        val serviceRunning = ForegroundVpnService.isServiceRunning

        // Double-check with system service manager as a fallback
        if (!serviceRunning) {
            val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (ForegroundVpnService::class.java.name == service.service.className) {
                    return true
                }
            }
        }
        return serviceRunning
    }

    fun hasUsageStatsPermission(): Boolean {
        val appOpsManager = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            ) == AppOpsManager.MODE_ALLOWED
        } else {
            try {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                ) == AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isServiceRunning: Boolean,
    hasPermission: Boolean,
    onToggleService: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val viewModel: AppViewModel = viewModel()
    val appList by viewModel.filteredApps.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Foreground Internet Manager") },
                actions = {
                    IconButton(onClick = { viewModel.refreshApps() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    onToggleService()
                    val message = if (isServiceRunning) "Stopping service..." else "Starting service..."
                    scope.launch {
                        snackbarHostState.showSnackbar(message)
                    }
                }
            ) {
                val icon = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow
                Icon(icon, contentDescription = "Toggle Service")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // Service status indicator
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                color = if (isServiceRunning)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (isServiceRunning)
                            "Internet Manager is active"
                        else
                            "Internet Manager is inactive",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isServiceRunning)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            SearchBar(
                query = searchQuery,
                onQueryChange = { query ->
                    searchQuery = query
                    viewModel.filterApps(query)
                }
            )

            // Help card explaining the app functionality
            InfoCard()

            // Display a permission reminder if needed
            if (!hasPermission) {
                PermissionReminderCard()
            }

            AppListContent(
                appList = appList,
                onToggleSelection = { app, isSelected ->
                    viewModel.toggleAppSelection(app, isSelected)
                }
            )
        }
    }
}

@Composable
fun InfoCard() {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "How It Works",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "• Selected apps have internet access only when they're in the foreground",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "• When a selected app moves to the background, its internet is blocked",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "• Non-selected apps work normally at all times",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionReminderCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            "Usage Stats permission is required for this app to work properly",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun AppListContent(
    appList: List<AppInfo>,
    onToggleSelection: (AppInfo, Boolean) -> Unit
) {
    // Separate apps into categories
    val thirdPartyApps = remember(appList) { appList.filter { !it.isSystemApp } }
    val systemApps = remember(appList) { appList.filter { it.isSystemApp } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (thirdPartyApps.isNotEmpty()) {
            item {
                Text(
                    text = "Third-Party Apps (${thirdPartyApps.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(
                items = thirdPartyApps,
                key = { app -> app.packageName } // Add key for better performance
            ) { app ->
                AppItem(
                    app = app,
                    onToggleSelection = { isSelected ->
                        onToggleSelection(app, isSelected)
                    }
                )
            }
        }

        if (systemApps.isNotEmpty()) {
            item {
                Text(
                    text = "System Apps (${systemApps.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            items(
                items = systemApps,
                key = { app -> app.packageName } // Add key for better performance
            ) { app ->
                AppItem(
                    app = app,
                    onToggleSelection = { isSelected ->
                        onToggleSelection(app, isSelected)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search apps...") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search"
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        singleLine = true // Better UX for search
    )
}

@Composable
fun AppItem(
    app: AppInfo,
    onToggleSelection: (Boolean) -> Unit
) {
    // Process app icon outside of composable function
    val appIcon = remember(app.packageName) {
        app.icon?.let {
            runCatching {
                it.toBitmap().asImageBitmap()
            }.getOrNull()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Display app icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 16.dp)
            ) {
                appIcon?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "App icon for ${app.name}",
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Default app icon",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
            ) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Add a small chip to indicate system apps
                    if (app.isSystemApp) {
                        SystemAppChip()
                    }
                }
            }

            // Switch for selecting apps to be monitored
            Switch(
                checked = app.isSelected,
                onCheckedChange = onToggleSelection
            )
        }
    }
}

@Composable
private fun SystemAppChip() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(start = 4.dp)
    ) {
        Text(
            text = "System",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content
    )
}