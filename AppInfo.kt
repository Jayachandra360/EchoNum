package com.example.echonum

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val name: String,
    val uid: Int,
    val isSystemApp: Boolean,
    val isSelected: Boolean,
    val icon: Drawable? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppInfo

        if (packageName != other.packageName) return false
        if (name != other.name) return false
        if (uid != other.uid) return false
        if (isSystemApp != other.isSystemApp) return false
        if (isSelected != other.isSelected) return false
        // Note: We don't compare icon as it's not part of the logical equality

        return true
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + uid
        result = 31 * result + isSystemApp.hashCode()
        result = 31 * result + isSelected.hashCode()
        // Note: We don't include icon in hashCode to avoid issues with Drawable comparison
        return result
    }

    override fun toString(): String {
        return "AppInfo(packageName='$packageName', name='$name', uid=$uid, isSystemApp=$isSystemApp, isSelected=$isSelected)"
    }
}