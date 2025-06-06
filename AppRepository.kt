package com.example.echonum

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Repository class for managing app selection status for internet control
 */
class AppRepository private constructor(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)

    companion object {
        private const val PRIORITIZED_APPS_KEY = "prioritized_apps"
        private const val SELECTED_APPS_KEY = "selected_apps" // For backward compatibility
        @Volatile
        private var instance: AppRepository? = null

        fun getInstance(context: Context): AppRepository {
            return instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get the list of apps that are selected for monitoring
     * (their internet access will be blocked when in background)
     */
    fun getPrioritizedApps(): Set<String> {
        // Try to get from new key first, if empty, check old key for migration
        val apps = sharedPreferences.getStringSet(PRIORITIZED_APPS_KEY, null)

        if (apps == null) {
            // Perform migration if needed
            val oldApps = getSelectedApps()
            if (oldApps.isNotEmpty()) {
                sharedPreferences.edit().apply {
                    putStringSet(PRIORITIZED_APPS_KEY, oldApps)
                    apply()
                }
            }
            return oldApps
        }
        return apps.toSet() // Create a copy to avoid mutation issues
    }

    // Legacy - used for migration
    internal fun getSelectedApps(): Set<String> {
        return sharedPreferences.getStringSet(SELECTED_APPS_KEY, emptySet())?.toSet() ?: emptySet()
    }

    /**
     * Check if an app is selected for monitoring
     */
    fun isAppPrioritized(packageName: String): Boolean {
        return getPrioritizedApps().contains(packageName)
    }

    /**
     * Add an app to the monitored list
     */
    fun addPrioritizedApp(packageName: String) {
        val prioritizedApps = getPrioritizedApps().toMutableSet()
        prioritizedApps.add(packageName)
        sharedPreferences.edit().apply {
            putStringSet(PRIORITIZED_APPS_KEY, prioritizedApps)
            apply()
        }
        Log.d("AppRepository", "Added app to monitored list: $packageName")
    }

    /**
     * Remove an app from the monitored list
     */
    fun removePrioritizedApp(packageName: String) {
        val prioritizedApps = getPrioritizedApps().toMutableSet()
        prioritizedApps.remove(packageName)
        sharedPreferences.edit().apply {
            putStringSet(PRIORITIZED_APPS_KEY, prioritizedApps)
            apply()
        }
        Log.d("AppRepository", "Removed app from monitored list: $packageName")
    }
}