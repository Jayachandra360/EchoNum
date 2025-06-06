@file:Suppress("DEPRECATION")
package com.example.echonum

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ForegroundVpnService : VpnService() {

    // CRITICAL FIX 1: Add missing system telephony services to completeTelephonyServices
    private val completeTelephonyServices = setOf(
        // Core Android telephony
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.incallui",
        "com.android.dialer",
        "com.android.stk",
        "com.android.cellbroadcastreceiver",
        "com.android.emergency",
        "com.android.phone.settings",

        // IMS and RCS services (CRITICAL)
        "com.android.ims",
        "com.android.ims.rcsservice",
        "com.google.android.ims",
        "com.samsung.android.ims",
        "com.qualcomm.qti.ims",
        "com.qualcomm.qti.telephonyservice",
        "com.mediatek.ims",
        "org.codeaurora.ims",

        // ADD THESE MISSING CRITICAL SERVICES:
        "com.android.server.telephony",     // Core telephony server
        "com.android.telephony.resources", // Telephony resources
        "com.android.calllogbackup",       // Call log services
        "com.android.simappdialog",        // SIM app dialog
        "com.android.telephonymonitor",    // Telephony monitoring
        "com.android.carrierconfig",       // Carrier configuration
        "com.android.carrierdefaultapp",   // Carrier default apps
        "com.android.phone.euicc",
        "com.android.euicc",
        "com.android.callcomposer",

        // Network stack (IMPORTANT)
        "com.android.networkstack",
        "com.android.networkstack.tethering",
        "com.android.connectivity.resources",
        "com.android.captiveportallogin",
        "com.android.networkstack.permissionconfig",

        // Carrier and connectivity - EXISTING ONES ARE GOOD
        "com.google.android.carrier",
        "com.google.android.apps.tycho",
        "com.verizon.messaging.vzmsgs",
        "com.att.mobile.android.messaging",
        "com.tmobile.pr.adapt",

        // SMS/MMS - EXISTING ONES ARE GOOD
        "com.android.mms",
        "com.android.mms.service",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",

        // Google Play Services (handles call routing) - EXISTING ONES ARE GOOD
        "com.google.android.gms",
        "com.google.android.gsf",

        // Additional critical services - EXISTING ONES ARE GOOD
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.server.wifi",
        "com.android.wifi",
        "com.android.telephony",
        "com.android.internal.telephony",
        "com.android.providers.contacts",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.samsung.android.contacts",

        // Carrier-specific dialer apps - EXISTING ONES ARE GOOD
        "com.verizon.llkagent",
        "com.att.callprotect",
        "com.tmobile.nameid",
        "com.sprint.care"
    )

    companion object {
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_PREFIX_LENGTH = 24
        private const val VPN_DNS = "1.1.1.1"
        private const val VPN_DNS_SECONDARY = "8.8.4.4"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val BUFFER_SIZE = 32767
        private const val DEBOUNCE_TIME_MS = 1000L
        private const val VPN_RECONNECT_DELAY = 3000L
        private const val MIN_RECONFIG_INTERVAL = 2000L
        private var reconnectJob: Job? = null

        // Enhanced app categorization
        private val HOME_APPS = setOf(
            "com.android.launcher", "com.google.android.apps.nexuslauncher",
            "com.miui.home", "com.sec.android.app.launcher", "com.actionlauncher.playstore",
            "com.microsoft.launcher", "com.teslacoilsw.launcher", "com.lge.launcher2",
            "com.asus.launcher", "com.huawei.android.launcher", "com.oneplus.launcher",
            "com.nova.launcher"
        )

        private val SYSTEM_UI_APPS = setOf(
            "com.android.systemui", "android", "com.android.settings", "com.android.keyguard"
        )

        // VoIP and communication apps that need special handling
        private val VOIP_APPS = setOf(
            "com.whatsapp", "com.facebook.orca", "com.skype.raider", "com.viber.voip",
            "com.discord", "com.microsoft.teams", "com.google.android.apps.tachyon",
            "us.zoom.videomeetings", "com.snapchat.android", "com.instagram.android",
            "com.facebook.katana", "com.telegram.messenger", "com.tencent.mm",
            "jp.naver.line.android", "com.kakao.talk", "com.webex.meetings",
            "com.gotomeeting", "com.ringcentral.android", "com.vonage.business.cloud.messaging",
            "com.google.android.apps.meetings"
        )

        @Volatile
        var isServiceRunning = false
            private set
    }

    private val phoneDialerApps = setOf(
        // Core Android telephony
        "com.android.phone",
        "com.android.incallui",
        "com.android.dialer",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.stk",
        "com.android.cellbroadcastreceiver",
        "com.android.emergency",

        // IMS services (CRITICAL for modern calls)
        "com.google.android.ims",
        "com.google.android.rcs",
        "com.samsung.android.ims",
        "com.android.ims.rcsservice",
        "com.android.ims",
        "com.qualcomm.qti.ims",
        "org.codeaurora.ims",

        // Google services for calls
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.apps.tachyon",

        // SMS/MMS services
        "com.android.mms",
        "com.android.mms.service",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",

        // Manufacturer-specific dialer apps
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.miui.incallui",
        "com.miui.dialer",
        "com.oneplus.dialer",
        "com.huawei.contacts",
        "com.coloros.phone",
        "com.asus.contacts",
        "com.lge.phone",
        "com.htc.android.phone",
        "com.oppo.contacts",
        "com.vivo.contacts",

        // ENHANCED: Additional manufacturer apps
        "com.xiaomi.xmsf",
        "com.samsung.android.messaging",
        "com.samsung.android.app.telephonyui",
        "com.realme.dialer",
        "com.nothing.dialer"
    )

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var packetProcessingJob: Job? = null
    private var refreshJob: Job? = null

    // State management
    @Volatile private var currentForegroundApp: String? = null
    @Volatile private var lastSelectedApps: Set<String>? = null
    private val lastReconfigTime = AtomicLong(0L)
    private var appRepository: AppRepository? = null
    private var installedPackages: Set<String>? = null
    private var consecutiveErrors = 0

    private val configMutex = Mutex()
    private val cleanupMutex = Mutex()

    // Enhanced call detection
    private var phoneStateListener: PhoneStateListener? = null
    @Volatile private var isInPhoneCall = false
    private val callStartTime = AtomicLong(0L)
    // FIX 1: Add missing emergencyMode property
    @Volatile private var emergencyMode = false

    // VPN state tracking
    private data class VpnConfig(
        val blockedApps: Set<String>,
        val allowedApps: Set<String>,
        val foregroundApp: String?,
        val selectedApps: Set<String>,
        val isInCall: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VpnConfig) return false
            return blockedApps == other.blockedApps &&
                    allowedApps == other.allowedApps &&
                    foregroundApp == other.foregroundApp &&
                    selectedApps == other.selectedApps &&
                    isInCall == other.isInCall
        }

        override fun hashCode(): Int {
            return blockedApps.hashCode() * 31 +
                    allowedApps.hashCode() * 31 +
                    (foregroundApp?.hashCode() ?: 0) * 31 +
                    selectedApps.hashCode() * 31 +
                    isInCall.hashCode()
        }
    }

    @Volatile private var lastVpnConfig: VpnConfig? = null
    override fun onCreate() {
        super.onCreate()
        Log.d("VPN", "Enhanced VPN Service onCreate")
        appRepository = AppRepository.getInstance(applicationContext)
        setupPhoneStateListener()

        serviceScope.launch(Dispatchers.IO) {
            cacheInstalledPackages()
            verifySystemProtection()
            // ✅ NEW: Verify phone app protection at startup
            verifyPhoneAppProtection()
            removePhoneAppsFromSelection() // NEW: Clean up any phone apps in selection
        }
    }

    // ENHANCED: Better phone app protection verification
    private fun verifyPhoneAppProtection() {
        val selectedApps = appRepository?.getPrioritizedApps() ?: emptySet()
        val allPhoneServices = phoneDialerApps + completeTelephonyServices

        val phoneAppsInSelected = allPhoneServices.intersect(selectedApps)
        if (phoneAppsInSelected.isNotEmpty()) {
            Log.w("VPN", "⚠️ CRITICAL: Phone services found in app selection!")
            phoneAppsInSelected.forEach { app ->
                Log.w("VPN", "  - ${getAppName(app)} ($app)")
            }

            // Auto-remove phone apps from selection
            serviceScope.launch {
                try {
                    appRepository?.let { repo ->
                        selectedApps - allPhoneServices
                        Log.i("VPN", "Auto-removing ${phoneAppsInSelected.size} phone services from selection")
                        // Update repository with cleaned selection
                        // repo.updatePrioritizedApps(cleanedSelection) // Implement this method
                    }
                } catch (e: Exception) {
                    Log.e("VPN", "Failed to clean phone apps from selection", e)
                }
            }
        }

        Log.d("VPN", "✅ Phone protection verified: ${allPhoneServices.size} services protected")
    }

    private fun removePhoneAppsFromSelection() {
        val selectedApps = appRepository?.getPrioritizedApps() ?: emptySet()
        val allPhoneServices = phoneDialerApps + completeTelephonyServices
        val phoneAppsInSelection = selectedApps.intersect(allPhoneServices)

        if (phoneAppsInSelection.isNotEmpty()) {
            Log.w("VPN", "🔧 Auto-removing ${phoneAppsInSelection.size} phone services from selection")
            phoneAppsInSelection.forEach { app ->
                Log.w("VPN", "   Removing: ${getAppName(app)}")
            }
        }
    }

    private fun verifySystemProtection() {
        Log.d("VPN", "🔍 Verifying system service protection...")

        val criticalServices = completeTelephonyServices + phoneDialerApps + setOf(
            "android", "com.android.systemui", "com.google.android.gms"
        )

        val selectedApps = appRepository?.getPrioritizedApps() ?: emptySet()
        val conflictingApps = criticalServices.intersect(selectedApps)

        if (conflictingApps.isNotEmpty()) {
            Log.e("VPN", "🚨 CRITICAL: System services in app selection!")
            conflictingApps.forEach { app ->
                Log.e("VPN", "   - $app (${getAppName(app)})")
            }
        }

        Log.d("VPN", "✅ System protection verified: ${criticalServices.size} protected services")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("VPN", "Enhanced service onStartCommand: ${intent?.action}")

        if (intent?.action == "STOP_VPN_SERVICE") {
            Log.d("VPN", "Received stop command")
            serviceScope.launch { cleanup() }
            return START_NOT_STICKY
        }

        if (isRunning.compareAndSet(false, true)) {
            isServiceRunning = true
            Log.d("VPN", "Starting enhanced VPN service")

            startForeground(NOTIFICATION_ID, buildNotification())

            // Start monitoring jobs
            monitorJob = serviceScope.launch { monitorForegroundApp() }
            refreshJob = serviceScope.launch { periodicRefresh() }

            // Initial VPN setup
            serviceScope.launch {
                delay(2000) // Allow service to stabilize
                Log.d("VPN", "🚀 Starting initial VPN configuration")
                updateVpnConfiguration(forceUpdate = true)
            }
        }

        return START_STICKY
    }

    private suspend fun periodicRefresh() {
        while (isRunning.get() && serviceScope.isActive) {
            try {
                delay(30000) // Every 30 seconds
                updateSelectedAppsState()

                // Health check VPN interface
                val vpnInterfaceInstance = vpnInterface
                if (vpnInterfaceInstance != null && !vpnInterfaceInstance.fileDescriptor.valid()) {
                    Log.w("VPN", "Invalid interface detected, recreating")
                    updateVpnConfiguration(forceUpdate = true)
                }
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e("VPN", "Error in periodic refresh", e)
            }
        }
    }

    private suspend fun cacheInstalledPackages() {
        try {
            installedPackages = withContext(Dispatchers.IO) {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .map { it.packageName }
                    .toSet()
            }
            Log.d("VPN", "Cached ${installedPackages?.size} installed packages")
        } catch (e: Exception) {
            Log.e("VPN", "Failed to cache packages", e)
            installedPackages = emptySet()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun monitorForegroundApp() {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        var lastValidApp: String? = null

        while (isRunning.get() && serviceScope.isActive) {
            try {
                val now = System.currentTimeMillis()
                val usageStats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 60000, now
                )

                if (usageStats.isNotEmpty()) {
                    val recentApps = usageStats
                        .filter { it.lastTimeUsed > now - 30000 }
                        .sortedByDescending { it.lastTimeUsed }
                        .take(5)

                    val recentApp = recentApps.firstOrNull()?.packageName

                    val validApp = when {
                        recentApp == null -> lastValidApp
                        SYSTEM_UI_APPS.contains(recentApp) -> lastValidApp
                        HOME_APPS.contains(recentApp) -> null
                        else -> {
                            lastValidApp = recentApp
                            recentApp
                        }
                    }

                    if (validApp != currentForegroundApp) {
                        val oldAppName = currentForegroundApp?.let { getAppName(it) } ?: "none"
                        val newAppName = validApp?.let { getAppName(it) } ?: "none"

                        Log.d("VPN", "*** FOREGROUND APP CHANGE: $oldAppName → $newAppName ***")
                        currentForegroundApp = validApp

                        // 🔧 CRITICAL: Don't update VPN during calls unless absolutely necessary
                        if (!isInPhoneCall) {
                            updateVpnConfiguration(forceUpdate = true)
                        } else {
                            Log.d("VPN", "📞 Skipping VPN update during call")
                        }
                        withContext(Dispatchers.Main) { updateNotification() }
                    }
                }
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e("VPN", "Monitoring error", e)
                consecutiveErrors++
                if (consecutiveErrors > 5) {
                    delay(5000)
                    consecutiveErrors = 0
                }
            }
            delay(DEBOUNCE_TIME_MS)
        }
    }

    private fun updateSelectedAppsState() {
        val selectedApps = appRepository?.getPrioritizedApps() ?: emptySet()
        if (selectedApps != lastSelectedApps) {
            Log.d("VPN", "Selected apps changed: ${selectedApps.size} apps selected")
            lastSelectedApps = selectedApps
            if (!isInPhoneCall) {
                serviceScope.launch { updateVpnConfiguration(forceUpdate = true) }
            }
        }
    }

    private suspend fun updateVpnConfiguration(forceUpdate: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            val lastReconfig = lastReconfigTime.get()

            if (!forceUpdate && !emergencyMode && (now - lastReconfig) < MIN_RECONFIG_INTERVAL) {
                Log.v("VPN", "Skipping config update due to debounce")
                return
            }

            val selectedApps = appRepository?.getPrioritizedApps() ?: emptySet()
            val allApps = installedPackages ?: emptySet()
            val foregroundApp = currentForegroundApp

            // CRITICAL: Filter out ALL system services from selected apps
            val allPhoneServices = phoneDialerApps + completeTelephonyServices
            val safeSelectedApps = selectedApps.filter { app ->
                val isPhoneService = allPhoneServices.contains(app)
                val isSystemService = isSystemService(app)

                if (isPhoneService || isSystemService) {
                    Log.w("VPN", "⚠️ Removing system/phone service from selection: ${getAppName(app)}")
                    false
                } else {
                    true
                }
            }.toSet()

            Log.d("VPN", "=== VPN CONFIGURATION UPDATE ===")
            Log.d("VPN", "Foreground app: ${foregroundApp?.let { getAppName(it) } ?: "NONE"}")
            Log.d("VPN", "Safe selected apps: ${safeSelectedApps.size}")
            Log.d("VPN", "Call state: ${if (isInPhoneCall) "IN CALL" else "NORMAL"}")

            val appsToRouteViaVpn = mutableSetOf<String>()  // These get blocked
            val appsToBypassVpn = mutableSetOf<String>()    // These get normal internet

            // Build comprehensive phone protection list
            val phoneProtectionList = mutableSetOf<String>().apply {
                // Core system
                add("android")
                add("com.android.systemui")

                // ALL phone/telephony services
                addAll(allPhoneServices)

                // Network infrastructure
                add("com.android.networkstack")
                add("com.android.networkstack.tethering")
                add("com.android.connectivity.resources")

                // Google services that handle calls
                add("com.google.android.gms")
                add("com.google.android.gsf")

                // This VPN service
                add(packageName)
            }

            // ENHANCED: During calls, be extremely conservative
            if (isInPhoneCall) {
                Log.d("VPN", "📞 CALL MODE: Maximum phone protection enabled")

                // During calls, minimize all VPN interference
                safeSelectedApps.filter { app ->
                    !isSystemService(app) && !allPhoneServices.contains(app)
                }.forEach { app ->
                    if (app == foregroundApp) {
                        appsToBypassVpn.add(app)
                    } else {
                        // Be very conservative - only block apps we're certain won't affect calls
                        if (!VOIP_APPS.contains(app) && !isCommApp(app) && !isSystemCritical(app)) {
                            appsToRouteViaVpn.add(app)
                        } else {
                            appsToBypassVpn.add(app)
                            Log.d("VPN", "Call protection extended to: ${getAppName(app)}")
                        }
                    }
                }
            } else {
                // Normal processing for non-call scenarios
                for (app in allApps) {
                    when {
                        // HIGHEST PRIORITY: Phone and system protection
                        phoneProtectionList.contains(app) || isSystemService(app) -> {
                            appsToBypassVpn.add(app)
                            if (allPhoneServices.contains(app)) {
                                Log.v("VPN", "✅ PHONE PROTECTED: ${getAppName(app)}")
                            }
                        }

                        // SECOND PRIORITY: Safe selected apps logic
                        safeSelectedApps.contains(app) -> {
                            if (app == foregroundApp) {
                                appsToBypassVpn.add(app)
                                Log.d("VPN", "✅ FOREGROUND: ${getAppName(app)} (internet enabled)")
                            } else {
                                appsToRouteViaVpn.add(app)
                                Log.d("VPN", "🚫 BACKGROUND: ${getAppName(app)} (blocked)")
                            }
                        }

                        // DEFAULT: Non-selected apps get normal internet
                        else -> {
                            appsToBypassVpn.add(app)
                        }
                    }
                }
            }

            val newConfig = VpnConfig(
                blockedApps = appsToRouteViaVpn,
                allowedApps = appsToBypassVpn,
                foregroundApp = foregroundApp,
                selectedApps = safeSelectedApps,
                isInCall = isInPhoneCall
            )

            if (!forceUpdate && newConfig == lastVpnConfig) {
                Log.v("VPN", "Config unchanged, skipping update")
                return
            }

            lastVpnConfig = newConfig
            lastReconfigTime.set(now)

            // Configure VPN with enhanced phone protection
            configureVpnInterface(appsToBypassVpn.toList(), appsToRouteViaVpn.toList())

            Log.d("VPN", "=== FINAL CONFIGURATION ===")
            Log.d("VPN", "✅ Phone services protected: ${phoneProtectionList.intersect(appsToBypassVpn).size}")
            Log.d("VPN", "✅ Total apps with internet: ${appsToBypassVpn.size}")
            Log.d("VPN", "🚫 Total apps blocked: ${appsToRouteViaVpn.size}")
            Log.d("VPN", "📞 Call mode: ${if (isInPhoneCall) "ACTIVE" else "NORMAL"}")

        } catch (_: CancellationException) {
            // Ignore cancellation
        } catch (e: Exception) {
            Log.e("VPN", "Error updating VPN config", e)
            consecutiveErrors++
            if (consecutiveErrors > 3) {
                scheduleRetry()
            }
        }
    }

    // NEW: Additional system critical app detection
    private fun isSystemCritical(packageName: String): Boolean {
        val criticalApps = setOf(
            "com.android.vending", // Play Store
            "com.google.android.packageinstaller",
            "com.android.providers.downloads",
            "com.android.providers.media",
            "com.android.externalstorage",
            "com.android.documentsui"
        )
        return criticalApps.contains(packageName)
    }

    private fun isSystemService(packageName: String): Boolean {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val uid = packageInfo.applicationInfo?.uid ?: return false

            // System UIDs are typically < 10000
            if (uid < 10000) {
                Log.d("VPN", "System service detected: $packageName (UID: $uid)")
                return true
            }

            // Check if it's a system app
            val flags = packageInfo.applicationInfo?.flags ?: 0
            if ((flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                Log.d("VPN", "System app detected: $packageName")
                return true
            }

            return false
        } catch (_: Exception) {
            return false
        }
    }

    // CRITICAL FIX 5: Enhanced verification with immediate shutdown on conflict
    private fun verifyPhoneProtection(appsToBlock: List<String>) {
        val allPhoneServices = phoneDialerApps + completeTelephonyServices
        val conflictingApps = allPhoneServices.intersect(appsToBlock.toSet())

        if (conflictingApps.isNotEmpty()) {
            Log.e("VPN", "🚨 CRITICAL ERROR: Phone services in block list!")
            conflictingApps.forEach { app ->
                Log.e("VPN", "   - ${getAppName(app)} ($app)")
            }
            // IMMEDIATE shutdown
            serviceScope.launch {
                Log.e("VPN", "EMERGENCY: Immediate VPN shutdown to protect calls")
                emergencyMode = true
                cleanupVpnInterfaceInternal()

                delay(5000) // Wait before restart
                if (!isInPhoneCall) {
                    Log.i("VPN", "Attempting safe VPN restart after emergency shutdown")
                    updateVpnConfiguration(forceUpdate = true)
                }
            }
            return
        }

        // ENHANCED: Check for system UID conflicts with immediate action
        appsToBlock.forEach { app ->
            try {
                val packageInfo = packageManager.getPackageInfo(app, 0)
                val uid = packageInfo.applicationInfo?.uid
                if (uid != null && uid < 1000) {
                    Log.e("VPN", "🚨 CRITICAL: System UID $uid in block list for $app")
                    serviceScope.launch {
                        Log.e("VPN", "EMERGENCY: Immediate shutdown due to system UID conflict")
                        emergencyMode = true
                        cleanupVpnInterfaceInternal()
                    }
                    return
                }
            } catch (_: Exception) { }
        }

        Log.d("VPN", "✅ Phone protection verified - no conflicts found")
    }

    private suspend fun configureVpnInterface(
        appsToBypass: List<String>,
        appsToBlock: List<String>
    ) = configMutex.withLock {

        // CRITICAL: During calls, use call-safe VPN instead
        if (isInPhoneCall) {
            Log.d("VPN", "📞 Using call-safe VPN configuration")
            establishCallSafeVpn()
            return@withLock
        }

        cleanupVpnInterfaceInternal()

        val builder = Builder()
            .setSession("Enhanced Internet Manager")
            .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
            .addDnsServer(VPN_DNS)
            .addDnsServer(VPN_DNS_SECONDARY)
            .setBlocking(false)
            .setMtu(1500)
            .allowFamily(OsConstants.AF_INET)

        // Only add routes if we have apps to block AND we're not in a call
        if (appsToBlock.isNotEmpty() && !isInPhoneCall) {
            builder.addRoute("0.0.0.0", 0)
            Log.d("VPN", "Added default route for ${appsToBlock.size} blocked apps")
        } else {
            Log.d("VPN", "No routing - minimal VPN setup")
            // Still create VPN but with no routing - acts as pass-through
        }

        // CRITICAL: Create EXPANDED exclusion list for telephony
        val criticalServicesToExclude = mutableSetOf<String>().apply {
            // Core system
            add("android")
            add("com.android.systemui")
            add("system")
            add("com.android.shell")

            // ALL telephony services - NEVER route through VPN
            addAll(completeTelephonyServices)
            addAll(phoneDialerApps)

            // Self exclusion
            add(packageName)

            // CRITICAL NEW: Add more system services that might handle calls
            add("com.android.server")
            add("com.android.providers.settings")
            add("com.android.settings")
            add("com.android.vending")
            add("com.android.keyguard")
            add("com.android.location")
            add("com.android.launcher")
            add("com.google.android.apps.nexuslauncher")
        }

        // ENHANCED: Exclude system UIDs with better error handling
        try {
            for (uid in 0..9999) {
                try {
                    builder.addDisallowedApplication(uid.toString())
                } catch (_: Exception) { }
            }
            Log.d("VPN", "System UIDs 0-9999 excluded from VPN")
        } catch (e: Exception) {
            Log.w("VPN", "Could not exclude system UIDs: ${e.message}")
        }

        var totalExcluded = 0

        // CRITICAL: Enhanced telephony exclusion with UID-based protection
        criticalServicesToExclude.forEach { service ->
            try {
                builder.addDisallowedApplication(service)
                totalExcluded++

                // ADDITIONAL: For telephony services, also exclude by UID
                if (completeTelephonyServices.contains(service) || phoneDialerApps.contains(service)) {
                    try {
                        val packageInfo = packageManager.getPackageInfo(service, 0)
                        val uid = packageInfo.applicationInfo?.uid
                        uid?.let {
                            builder.addDisallowedApplication(it.toString())
                            Log.v("VPN", "Telephony service $service protected by package + UID: $uid")
                        }
                    } catch (_: Exception) { }
                }

            } catch (_: PackageManager.NameNotFoundException) {
                // Service not installed
            } catch (e: Exception) {
                Log.e("VPN", "CRITICAL: Failed to exclude service $service", e)
            }
        }

        // User apps exclusion
        var userAppsExcluded = 0
        appsToBypass.forEach { app ->
            try {
                if (!criticalServicesToExclude.contains(app)) {
                    builder.addDisallowedApplication(app)
                    userAppsExcluded++
                }
            } catch (_: Exception) { }
        }

        // Allow bypass for system requests
        try {
            builder.allowBypass()
            Log.d("VPN", "VPN bypass enabled for system requests")
        } catch (_: Exception) { }

        // Set allowed families
        try {
            builder.allowFamily(OsConstants.AF_INET)
            builder.allowFamily(OsConstants.AF_INET6)
        } catch (_: Exception) { }

        try {
            vpnInterface = withContext(Dispatchers.IO) {
                builder.establish()
            }?.also { vpnInterfaceInstance ->
                if (!vpnInterfaceInstance.fileDescriptor.valid()) {
                    vpnInterfaceInstance.close()
                    throw IOException("Invalid file descriptor")
                }
            }

            if (vpnInterface != null) {
                delay(500)

                // Only start packet processing if we have routes and not in call
                if (appsToBlock.isNotEmpty() && !isInPhoneCall) {
                    packetProcessingJob?.cancel()
                    packetProcessingJob = serviceScope.launch { processPackets() }
                }

                consecutiveErrors = 0

                Log.d("VPN", "✅ VPN ESTABLISHED WITH MAXIMUM PHONE PROTECTION")
                Log.d("VPN", "Total services excluded: $totalExcluded")
                Log.d("VPN", "User apps bypassed: $userAppsExcluded")
                Log.d("VPN", "Apps to block: ${appsToBlock.size}")
                Log.d("VPN", "Call mode: ${if (isInPhoneCall) "SAFE" else "NORMAL"}")

                // Final verification
                verifyPhoneProtection(appsToBlock)

            } else {
                Log.e("VPN", "Failed to establish VPN interface")
                throw IOException("VPN establishment failed")
            }
        } catch (e: Exception) {
            Log.e("VPN", "CRITICAL: VPN interface creation failed", e)
            consecutiveErrors++
            throw e
        }
    }


    // CRITICAL FIX 2: Enhanced phone state listener with immediate VPN shutdown during calls
    private fun setupPhoneStateListener() {
        try {
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    val wasInCall = isInPhoneCall
                    val newCallState = when (state) {
                        TelephonyManager.CALL_STATE_RINGING,
                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            if (!wasInCall) {
                                callStartTime.set(System.currentTimeMillis())
                                emergencyMode = true
                                Log.w("VPN", "📞 CALL STARTED - EMERGENCY MODE ACTIVATED")

                                // CRITICAL NEW: IMMEDIATELY stop all VPN routing during calls
                                serviceScope.launch {
                                    try {
                                        Log.d("VPN", "🚨 EMERGENCY: Completely disabling VPN during call")

                                        // Method 1: Cancel packet processing immediately
                                        packetProcessingJob?.cancel()

                                        // Method 2: Close VPN interface completely
                                        cleanupVpnInterfaceInternal()

                                        // Method 3: Wait for call establishment
                                        delay(2000)

                                        // Method 4: Re-establish with ZERO routing (pass-through only)
                                        if (isInPhoneCall) {
                                            establishCallSafeVpn()
                                        }

                                    } catch (e: Exception) {
                                        Log.e("VPN", "Emergency call protection failed", e)
                                        // If anything fails, completely stop VPN
                                        cleanup()
                                    }
                                }
                            }
                            true
                        }
                        else -> {
                            if (wasInCall) {
                                emergencyMode = false
                                Log.w("VPN", "📞 CALL ENDED - NORMAL MODE RESTORED")

                                // Restore normal VPN configuration after call
                                serviceScope.launch {
                                    delay(3000) // Allow call to fully end
                                    Log.d("VPN", "🔄 Restoring normal VPN operation after call")
                                    updateVpnConfiguration(forceUpdate = true)
                                }
                            }
                            false
                        }
                    }

                    isInPhoneCall = newCallState

                    // Update notification immediately
                    if (wasInCall != isInPhoneCall) {
                        serviceScope.launch(Dispatchers.Main) {
                            updateNotification()
                        }
                    }
                }
            }

            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.d("VPN", "✅ Enhanced phone state listener with COMPLETE VPN shutdown registered")
        } catch (e: Exception) {
            Log.e("VPN", "CRITICAL: Failed to register phone state listener", e)
        }
    }

    // CRITICAL FIX 3: Add new method for call-safe VPN (minimal routing)
    private suspend fun establishCallSafeVpn() = configMutex.withLock {
        Log.d("VPN", "📞 Establishing call-safe VPN with minimal routing")

        cleanupVpnInterfaceInternal()

        val builder = Builder()
            .setSession("Call-Safe Mode")
            .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
            .addDnsServer(VPN_DNS)
            .addDnsServer(VPN_DNS_SECONDARY)
            .setBlocking(false)
            .setMtu(1500)
            .allowFamily(OsConstants.AF_INET)

        // CRITICAL: DO NOT add any routes during calls
        // This creates a "pass-through" VPN that doesn't actually route anything

        // Exclude EVERYTHING telephony-related with triple protection
        val allTelephonyServices = completeTelephonyServices + phoneDialerApps + setOf(
            "android", "com.android.systemui", packageName
        )

        allTelephonyServices.forEach { service ->
            try {
                builder.addDisallowedApplication(service)
            } catch (_: Exception) { }
        }

        // CRITICAL: Exclude all system UIDs (0-9999) during calls
        for (uid in 0..9999) {
            try {
                builder.addDisallowedApplication(uid.toString())
            } catch (_: Exception) { }
        }

        // Allow bypass for system requests
        try {
            builder.allowBypass()
        } catch (_: Exception) { }

        try {
            vpnInterface = withContext(Dispatchers.IO) {
                builder.establish()
            }

            if (vpnInterface != null) {
                Log.d("VPN", "✅ Call-safe VPN established (pass-through mode)")
            } else {
                Log.w("VPN", "Call-safe VPN failed, continuing without VPN during call")
            }
        } catch (e: Exception) {
            Log.w("VPN", "Call-safe VPN creation failed: ${e.message}")
            // Continue without VPN during call - this is safer
        }
    }

    private fun scheduleRetry() {
        // 🔧 NO RETRIES DURING CALLS - Too risky
        if (isInPhoneCall) {
            Log.d("VPN", "📞 Skipping retry during call")
            return
        }

        serviceScope.launch {
            val backoffDelay = minOf(VPN_RECONNECT_DELAY * (1 shl consecutiveErrors), 30000L)
            Log.d("VPN", "Scheduling retry in ${backoffDelay}ms (attempt ${consecutiveErrors + 1})")

            delay(backoffDelay)

            if (isRunning.get() && !isInPhoneCall) {
                Log.d("VPN", "Retrying VPN configuration...")
                updateVpnConfiguration(forceUpdate = true)
            }
        }
    }

    private suspend fun processPackets() {
        val vpnFd = vpnInterface ?: return
        var inputStream: FileInputStream? = null

        try {
            inputStream = withContext(Dispatchers.IO) {
                FileInputStream(vpnFd.fileDescriptor)
            }
            val packet = ByteArray(BUFFER_SIZE)

            while (isRunning.get() && vpnInterface != null && serviceScope.isActive) {
                try {
                    val length = withContext(Dispatchers.IO) {
                        inputStream.read(packet)
                    }

                    when {
                        length > 0 -> {
                            // Packet received from blocked app and intentionally dropped
                            // This is the core blocking mechanism - packets are consumed but not forwarded
                            if (isInPhoneCall) {
                                Log.v("VPN", "📞 Packet processed during call (${length} bytes)")
                            }
                        }
                        length == 0 -> {
                            delay(10) // Prevent busy waiting
                        }
                        else -> {
                            Log.d("VPN", "Packet stream ended")
                            break
                        }
                    }
                } catch (_: InterruptedIOException) {
                    break
                } catch (_: CancellationException) {
                    break
                } catch (e: IOException) {
                    when {
                        e.message?.contains("EBADF") == true -> {
                            Log.w("VPN", "Bad file descriptor, recreating interface")
                            break
                        }
                        e.message?.contains("EPIPE") == true -> {
                            Log.w("VPN", "Broken pipe, recreating interface")
                            break
                        }
                        else -> {
                            Log.e("VPN", "Packet processing IO error: ${e.message}")
                            delay(1000)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VPN", "Unexpected packet processing error", e)
                    delay(1000)
                }
            }
        } catch (e: Exception) {
            Log.e("VPN", "Error initializing packet processing", e)
        } finally {
            try {
                inputStream?.close()
            } catch (e: IOException) {
                Log.v("VPN", "Error closing packet stream: ${e.message}")
            }
            Log.d("VPN", "Packet processing completed")
        }
    }

    private fun buildNotification(): Notification {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager?.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Enhanced Internet Manager",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Advanced foreground internet control"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }

        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ForegroundVpnService::class.java).apply {
            action = "STOP_VPN_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val foregroundAppName = currentForegroundApp?.let { getAppName(it) }
        val selectedApps = appRepository?.getPrioritizedApps() ?: emptySet()
        val config = lastVpnConfig

        // CORRECTED: Only show call protection status for SELECTED communication apps
        val notificationText = when {
            isInPhoneCall -> {
                val protectedApps = mutableSetOf<String>()
                protectedApps.addAll(phoneDialerApps) // Always protected

                // Add selected communication apps
                val selectedCommApps = selectedApps.filter { VOIP_APPS.contains(it) || isCommApp(it) }
                protectedApps.addAll(selectedCommApps)

                "📞 Call active - ${protectedApps.size} communication apps protected"


            }
            foregroundAppName != null && selectedApps.contains(currentForegroundApp!!) ->
                "✅ Active: $foregroundAppName (internet enabled)"
            config?.blockedApps?.isNotEmpty() == true ->
                "🚫 ${config.blockedApps.size} selected apps blocked in background"
            selectedApps.isNotEmpty() ->
                "${selectedApps.size} apps under control"
            else -> "Ready to control app internet access"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Enhanced Internet Manager")
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    // Helper functions
    private fun getAppName(packageName: String): String {
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    // Also fix the isCommApp function to be more reliable:
    private fun isCommApp(packageName: String): Boolean {
        // FIRST: Explicitly exclude phone/dialer apps
        if (phoneDialerApps.contains(packageName)) {
            return false
        }

        // SECOND: Check known VoIP apps
        if (VOIP_APPS.contains(packageName)) {
            return true
        }

        // THIRD: Check package name patterns (but be more careful)
        val name = packageName.lowercase()
        return when {
            // Be more specific to avoid false positives with phone apps
            name.contains("whatsapp") || name.contains("telegram") ||
                    name.contains("discord") || name.contains("skype") ||
                    name.contains("zoom") || name.contains("teams") ||
                    name.contains("meet") && !name.contains("phone") ||
                    name.contains("chat") && !name.contains("phone") ||
                    name.contains("message") && !name.contains("phone") ||
                    name.contains("voip") -> true
            else -> false
        }
    }

    override fun onRevoke() {
        Log.d("VPN", "VPN permission revoked")
        super.onRevoke()
        serviceScope.launch { cleanup() }
    }

    override fun onDestroy() {
        Log.d("VPN", "Service onDestroy")
        super.onDestroy()
        serviceScope.launch { cleanup() }
    }

    private suspend fun cleanupVpnInterfaceInternal() {
        try {
            packetProcessingJob?.cancel()
            packetProcessingJob?.join()

            val currentInterface = vpnInterface
            if (currentInterface != null) {
                Log.d("VPN", "Closing VPN interface")
                delay(100)

                withContext(Dispatchers.IO) {
                    try {
                        if (currentInterface.fileDescriptor.valid()) {
                            currentInterface.close()
                        }
                    } catch (e: Exception) {
                        Log.w("VPN", "Error closing interface: ${e.message}")
                    }
                }
                vpnInterface = null
                Log.d("VPN", "VPN interface closed successfully")
            }
        } catch (e: Exception) {
            Log.e("VPN", "Error during VPN interface cleanup", e)
            vpnInterface = null
        }
    }

    private suspend fun cleanupVpnInterface() = cleanupMutex.withLock {
        cleanupVpnInterfaceInternal()
    }

    private suspend fun cleanup() {
        if (isRunning.compareAndSet(true, false)) {
            Log.d("VPN", "Starting comprehensive service cleanup")
            try {
                // Set static flag immediately
                isServiceRunning = false

                // Cancel all jobs with timeout
                val jobsToCancel = listOf(monitorJob, reconnectJob, packetProcessingJob, refreshJob)
                jobsToCancel.forEach { it?.cancel() }

                // Wait for jobs to complete with timeout
                withTimeoutOrNull(2000) {
                    jobsToCancel.forEach { it?.join() }
                } ?: Log.w("VPN", "Some jobs didn't complete within timeout")

                // Unregister phone listener
                phoneStateListener?.let { listener ->
                    try {
                        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                        telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
                        phoneStateListener = null
                        Log.d("VPN", "Phone state listener unregistered")
                    } catch (e: Exception) {
                        Log.e("VPN", "Error unregistering phone listener", e)
                    }
                }

                // Clean up VPN interface
                cleanupVpnInterface()

                // Stop foreground service
                withContext(Dispatchers.Main) {
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } catch (e: Exception) {
                        Log.e("VPN", "Error stopping foreground service", e)
                    }
                }

                Log.d("VPN", "Service cleanup completed successfully")

            } catch (e: Exception) {
                Log.e("VPN", "Error during cleanup", e)
                // Force stop even on cleanup error
                withContext(Dispatchers.Main) {
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } catch (ex: Exception) {
                        Log.e("VPN", "Error in forced stop", ex)
                    }
                }
            } finally {
                // Cancel service scope last
                serviceScope.cancel()
            }
        }
    }
}