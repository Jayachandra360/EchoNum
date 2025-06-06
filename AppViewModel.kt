package com.example.echonum

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel that manages the application list and selections
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository.getInstance(application)

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val _filteredApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val filteredApps: StateFlow<List<AppInfo>> = _filteredApps

    init {
        loadAllApps()
    }

    private fun loadAllApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val pm = context.packageManager
            val showSystemApps = context.getSharedPreferences(
                "AppPreferences",
                Context.MODE_PRIVATE
            ).getBoolean("show_system_apps", true)

            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appList = installedApps
                .asSequence()
                .map { appInfo ->
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    val packageName = appInfo.packageName
                    val uid = appInfo.uid
                    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val icon = try {
                        pm.getApplicationIcon(appInfo)
                    } catch (_: Exception) {
                        null
                    }

                    AppInfo(
                        packageName = packageName,
                        name = appName,
                        uid = uid,
                        isSystemApp = isSystemApp,
                        isSelected = repository.isAppPrioritized(packageName),
                        icon = icon
                    )
                }
                .filter { !it.isSystemApp || showSystemApps }
                // Sort with non-system apps first, then by name within each group
                .sortedWith(compareBy({ it.isSystemApp }, { it.name.lowercase() }))
                .toList()

            _apps.value = appList
            _filteredApps.value = appList
        }
    }

    fun filterApps(query: String) {
        val filteredList = if (query.isEmpty()) {
            _apps.value
        } else {
            _apps.value.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        _filteredApps.value = filteredList
    }

    fun refreshApps() {
        loadAllApps()
    }

    fun toggleAppSelection(app: AppInfo, isSelected: Boolean) {
        val updatedApp = app.copy(isSelected = isSelected)

        // Update in-memory list
        val appList = _apps.value.toMutableList()
        val index = appList.indexOfFirst { it.packageName == app.packageName }
        if (index != -1) {
            appList[index] = updatedApp
            _apps.value = appList
        }

        // Update filtered list
        val filteredList = _filteredApps.value.toMutableList()
        val filteredIndex = filteredList.indexOfFirst { it.packageName == app.packageName }
        if (filteredIndex != -1) {
            filteredList[filteredIndex] = updatedApp
            _filteredApps.value = filteredList
        }

        // Update repository for prioritized apps
        if (isSelected) {
            repository.addPrioritizedApp(app.packageName)
        } else {
            repository.removePrioritizedApp(app.packageName)
        }
    }
}