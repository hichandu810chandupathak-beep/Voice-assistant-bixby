package com.bixby.assistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
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
            command.startsWith("call ") || command.startsWith("phone ") || command.contains("call karo") || command.contains("phone lagao") || command.contains("कॉल") || command.contains("फोन लगाओ") -> {
                val directNumber = Regex("[+]?[0-9][0-9() -]{5,}[0-9]").find(raw)?.value?.replace(Regex("[^0-9+]"), "")
                if (!directNumber.isNullOrBlank()) {
                    launchIntent(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", directNumber, null)), "Opening the dialer. Review the number and tap Call to confirm.", respond)
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    respond("Allow Contacts permission, then repeat the call command.")
                } else {
                    val name = raw.replace(Regex("(?i)^.*?\\b(?:call|phone)\\s+"), "").trim()
                    val number = findContactNumber(name)
                    if (number == null) respond("I couldn't find a matching contact number.")
                    else launchIntent(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)), "Contact found. Review the number and tap Call to confirm.", respond)
                }
                return
            }
            command in setOf("home", "go home", "open home screen", "show home screen") -> {
                launchIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "Opening Home screen.", respond); return
            }
            command in setOf("back", "go back", "press back") -> { goBack(respond); return }
            command.contains("wifi") || command.contains("wi fi") -> {
                val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                launchIntent(Intent(action), "Opening Wi-Fi controls. Confirm the change in Android settings.", respond); return
            }
            command.contains("bluetooth") -> {
                launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Opening Bluetooth settings. Confirm the change in Android settings.", respond); return
            }
            command.contains("airplane") || command.contains("flight mode") -> {
                launchIntent(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), "Opening Airplane Mode settings. Android controls this toggle.", respond); return
            }
            command in setOf("settings", "open settings", "phone settings", "system settings") -> {
                launchIntent(Intent(Settings.ACTION_SETTINGS), "Opening Settings.", respond); return
            }
        }
        val localReply = AssistantCore.reply(raw)
        if (localReply != null) { respond(localReply); return }
        val appName = extractAppName(raw)
        if (appName != null) { launchApp(appName, respond); return }
        if (launchBareAppName(raw, respond)) return
        val assistantOpened = runCatching { context.startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (assistantOpened) { respond("Opening your device assistant for this request."); return }
        if (!hasInternet()) { respond("You're offline. Installed apps and supported device settings still work."); return }
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(raw.trim(), "UTF-8")
        launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Searching the web for: " + raw.trim(), respond)
    }


    private fun findContactNumber(name: String): String? {
        if (name.isBlank()) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        return try {
            val cursor = context.contentResolver.query(uri, columns, null, null, null) ?: return null
            cursor.use {
                val nameColumn = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberColumn = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var partial: String? = null
                while (it.moveToNext()) {
                    val savedName = normalize(it.getString(nameColumn).orEmpty())
                    val phone = it.getString(numberColumn).orEmpty().replace(Regex("[^0-9+]"), "")
                    if (phone.isBlank()) continue
                    if (savedName == normalize(name)) return phone
                    if (savedName.contains(normalize(name)) && partial == null) partial = phone
                }
                partial
            }
        } catch (_: Exception) { null }
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

    private fun launchBareAppName(raw: String, respond: (String) -> Unit): Boolean {
        val wanted = normalize(raw)
        val pm = context.packageManager
        val apps = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        val match = apps.firstOrNull { app ->
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
            app.enabled && (normalize(label) == wanted ||
                normalize(app.packageName.substringAfterLast('.').replace('.', ' ')) == wanted)
        } ?: return false
        val launch = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        val label = pm.getApplicationLabel(match).toString()
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { respond("Opening " + label + ".") }
            .onFailure { respond("Couldn't open " + label + ".") }
        return true
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
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
}
