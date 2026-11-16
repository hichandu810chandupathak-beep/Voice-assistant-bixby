package com.bixby.assistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

class CommandRouter(private val context: Context) {
    fun execute(raw: String, respond: (String) -> Unit) {
        worker.execute {
            executeCommand(raw) { message -> mainHandler.post { respond(message) } }
        }
    }

    private fun executeCommand(raw: String, respond: (String) -> Unit) {
        val command = normalize(raw)
        if (command.isBlank()) { respond("Please say a complete command."); return }
        when {
            command in setOf("home", "go home", "open home screen", "show home screen") -> {
                launchIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "Opening Home screen.", respond); return
            }
            command in setOf("back", "go back", "press back") -> { goBack(respond); return }
            command.contains("wifi") || command.contains("wi fi") -> {
                val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                launchIntent(Intent(action), "Opening Wi-Fi controls. Confirm the change in Android settings.", respond); return
            }
            command.contains("bluetooth") -> {
                val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_BLUETOOTH else Settings.ACTION_BLUETOOTH_SETTINGS
                launchIntent(Intent(action), "Opening Bluetooth controls. Confirm the change in Android settings.", respond); return
            }
            command.contains("airplane") || command.contains("flight mode") -> {
                launchIntent(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), "Opening Airplane Mode settings. Android controls this toggle.", respond); return
            }
            command in setOf("settings", "open settings", "phone settings", "system settings") -> {
                launchIntent(Intent(Settings.ACTION_SETTINGS), "Opening Settings.", respond); return
            }
        }
        val appName = extractAppName(raw)
        if (appName != null) { launchApp(appName, respond); return }
        if (!hasInternet()) { respond("You're offline. Installed apps and supported device settings still work."); return }
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(raw.trim(), "UTF-8")
        launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Searching the web for: " + raw.trim(), respond)
    }

    private fun launchApp(name: String, respond: (String) -> Unit) {
        val pm = context.packageManager
        val wanted = normalize(name)
        val apps = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        val candidate = apps.mapNotNull { app ->
            if (!app.enabled) return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
            val score = score(wanted, normalize(label), normalize(app.packageName.substringAfterLast('.').replace('.', ' ')))
            if (score < 65 || pm.getLaunchIntentForPackage(app.packageName) == null) null
            else Triple(app.packageName, label, score)
        }.maxByOrNull { it.third }
        if (candidate == null) { respond("This app is not available on your device: " + name + "."); return }
        val intent = pm.getLaunchIntentForPackage(candidate.first)
        if (intent == null) { respond(candidate.second + " is installed but cannot be launched."); return }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { respond("Opening " + candidate.second + ".") }
            .onFailure { respond("Couldn't open " + candidate.second + ".") }
    }

    private fun score(wanted: String, label: String, packageName: String): Int {
        if (wanted == label) return 100
        if (wanted == packageName) return 96
        if (label.startsWith(wanted)) return 88
        if (label.contains(wanted)) return 80
        val a = wanted.split(' ').filter { it.length > 1 }.toSet()
        val b = label.split(' ').filter { it.length > 1 }.toSet()
        return if (a.isNotEmpty() && a.intersect(b).isNotEmpty()) 65 + 15 * a.intersect(b).size / a.size else 0
    }

    private fun extractAppName(raw: String): String? {
        val value = raw.trim()
        val lower = normalize(value)
        val prefixes = listOf("please open ", "open ", "launch ", "start ")
        val prefix = prefixes.firstOrNull { lower.startsWith(it.trim()) } ?: return null
        return value.drop(prefix.trim().length).trim().takeIf { it.isNotBlank() }
    }

    private fun launchIntent(intent: Intent, message: String, respond: (String) -> Unit) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { respond(message) }
            .onFailure { respond("That action is not available on this Android version.") }
    }

    private fun goBack(respond: (String) -> Unit) {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == AssistantAccessibilityService::class.java.name }
        if (enabled && AssistantAccessibilityService.instance?.goBack() == true) respond("Going back.")
        else {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            respond("Enable Bixby's Accessibility service in Settings to use the Back voice command.")
        }
    }

    private fun hasInternet(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
