package com.pixel.classicsearch

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

object ShizukuHelper {
    private const val TAG = "ShizukuHelper"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_REQUEST_CODE = 2001

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        if (!isRunning()) return false
        return try {
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check Shizuku permission", e)
            false
        }
    }

    fun requestPermission(requestCode: Int = SHIZUKU_REQUEST_CODE) {
        if (isRunning() && !isPermissionGranted()) {
            try {
                Shizuku.requestPermission(requestCode)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to request Shizuku permission", e)
            }
        }
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceComponent = ComponentName(context, LauncherStateService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val a11yEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        return a11yEnabled == 1 && enabledServices.contains(serviceComponent)
    }

    private fun execShizukuCommand(cmd: String): Int {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        return process.waitFor()
    }

    fun grantWriteSecureSettings(context: Context): Boolean {
        if (!isRunning() || !isPermissionGranted()) {
            Log.w(TAG, "Cannot grant permission: Shizuku running=${isRunning()}, granted=${isPermissionGranted()}")
            return false
        }
        return try {
            val pkg = context.packageName
            val cmd = "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
            val exitCode = execShizukuCommand(cmd)
            Log.i(TAG, "Executed '$cmd', exit code: $exitCode")
            exitCode == 0
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to grant WRITE_SECURE_SETTINGS via Shizuku", e)
            false
        }
    }

    fun enableAccessibilityService(context: Context): Boolean {
        if (!isRunning() || !isPermissionGranted()) {
            return false
        }
        return try {
            val serviceComponent = ComponentName(context, LauncherStateService::class.java).flattenToString()
            val cmd = "settings put secure enabled_accessibility_services $serviceComponent && settings put secure accessibility_enabled 1"
            val exitCode = execShizukuCommand(cmd)
            Log.i(TAG, "Executed enable a11y service via Shizuku, exit code: $exitCode")
            exitCode == 0
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to enable AccessibilityService via Shizuku", e)
            false
        }
    }
}
