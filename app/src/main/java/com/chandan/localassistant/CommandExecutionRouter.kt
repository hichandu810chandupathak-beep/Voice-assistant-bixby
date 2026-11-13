package com.chandan.localassistant

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Routes explicit user commands from the overlay or a speech transcript.
 *
 * Supported forms:
 *   open WhatsApp
 *   launch com.whatsapp
 *   back / go back
 *   home / go home
 *   click Search
 *   tap Continue
 *   type hello world into message
 *
 * Accessibility actions require MyAccessibilityService to be enabled by
 * the user in Android Settings > Accessibility.
 */
object CommandExecutionRouter {

    private const val TAG = "BixbyCommandRouter"

    private val knownApps = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "settings" to "com.android.settings",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "camera" to "com.sec.android.app.camera",
        "play store" to "com.android.vending",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "spotify" to "com.spotify.music"
    )

    private val openPattern =
        Regex("^(?:open|launch|start)\\s+(.+)$", RegexOption.IGNORE_CASE)

    private val clickPattern =
        Regex("^(?:click|tap|press)\\s+(.+)$", RegexOption.IGNORE_CASE)

    private val typePattern =
        Regex("^(?:type|enter|write)\\s+(.+?)\\s+(?:into|in)\\s+(.+)$",
            RegexOption.IGNORE_CASE)

    fun execute(context: Context, rawCommand: String): String {
        val command = rawCommand.trim()
        if (command.isEmpty()) return "Please say or type a command."

        val normalized = command.lowercase().replace(Regex("\\s+"), " ").trim()

        when (normalized) {
            "back", "go back", "press back", "navigate back" ->
                return MyAccessibilityService.connectedInstance()?.performNavigation(
                    MyAccessibilityService.NAV_BACK
                ) ?: "Enable Bixby in Android Accessibility settings to use Back."

            "home", "go home", "press home", "open home screen" ->
                return MyAccessibilityService.connectedInstance()?.performNavigation(
                    MyAccessibilityService.NAV_HOME
                ) ?: "Enable Bixby in Android Accessibility settings to use Home."
        }

        openPattern.matchEntire(command)?.groupValues?.getOrNull(1)?.let { appName ->
            return openApp(context, appName.trim())
        }

        clickPattern.matchEntire(command)?.groupValues?.getOrNull(1)?.let { target ->
            return MyAccessibilityService.connectedInstance()?.clickTextOnActiveWindow(
                target.trim()
            ) ?: "Enable Bixby in Android Accessibility settings before clicking screen items."
        }

        typePattern.matchEntire(command)?.let { match ->
            val textToEnter = match.groupValues[1].trim()
            val fieldDescription = match.groupValues[2].trim()
            if (textToEnter.isEmpty() || fieldDescription.isEmpty()) {
                return "Use this format: type your text into field name."
            }
            return MyAccessibilityService.connectedInstance()?.enterTextIntoField(
                fieldDescription,
                textToEnter
            ) ?: "Enable Bixby in Android Accessibility settings before entering text."
        }

        return "Command not recognized. Try: open WhatsApp, back, home, click Search, or type hello into message."
    }

    private fun openApp(context: Context, requestedName: String): String {
        val appKey = requestedName.lowercase().trim()
        val packageName = knownApps[appKey] ?: if (
            appKey.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$"))
        ) {
            appKey
        } else {
            return "I don't have an app mapping for $requestedName."
        }

        return try {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(packageName)
                ?: return "$requestedName is not installed or Android does not expose its launcher."

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            "Opening $requestedName."
        } catch (error: SecurityException) {
            Log.e(TAG, "Android blocked app launch: $packageName", error)
            "Android blocked opening $requestedName."
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to open app: $packageName", error)
            "Couldn't open $requestedName."
        }
    }
}
