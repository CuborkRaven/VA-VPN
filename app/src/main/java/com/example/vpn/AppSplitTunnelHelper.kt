package com.example.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

data class LaunchableAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

object AppSplitTunnelHelper {

    fun loadLaunchableApps(context: Context): List<LaunchableAppInfo> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }

        return resolved
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == selfPkg) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                    .ifEmpty { pkg }
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                LaunchableAppInfo(packageName = pkg, label = label, icon = icon)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
