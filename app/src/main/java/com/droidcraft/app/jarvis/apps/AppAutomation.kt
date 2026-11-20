package com.droidcraft.app.jarvis.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibilitynode.AccessibilityNodeInfo
import com.droidcraft.app.jarvis.a11y.JarvisAccessibilityService

/**
 * Named-app automation (WhatsApp / YouTube / etc.) — generic tap-by-text ke saath,
 * har app ke typical UI flow follow karta hai.
 */
object AppAutomation {

    private const val TAG = "AppAutomation"

    /** App package + kaam → automator. */
    fun run(context: Context, target: String, query: String): String {
        val service = JarvisAccessibilityService.instance
            ?: return "Pehle accessibility ON karo (header ka 🧩 icon)."

        val what = query.trim().lowercase()
        val pkg = resolvePackage(what) ?: resolvePackage(target.lowercase())
        if (pkg == null) {
            // Unknown app — generic tap on text
            val ok = JarvisAccessibilityService.tapText(service, target, exact = false)
            return if (ok) "'$target' par tap kar diya." else "'$target' screen par nahi mila."
        }

        val label = appLabel(pkg)
        return when {
            pkg.contains("whatsapp") -> runWhatsApp(context, service, what, target)
            pkg.contains("youtube") -> runYouTube(context, service, what, target)
            else -> {
                val ok = service.launchApp(context, pkg)
                if (ok) "$label khul gaya. Ab '$target' boliye." else "$label nahi khul paya."
            }
        }
    }

    /** Screen par target text par click (generic automation). */
    fun tapOn(service: JarvisAccessibilityService, target: String): String {
        val ok = service.tapText(target)
        return if (ok) "'$target' par tap kar diya." else "'$target' screen par nahi mila."
    }

    // ---------------------------------------------------------------- WhatsApp

    private fun runWhatsApp(context: Context, service: JarvisAccessibilityService, what: String, target: String): String {
        if (!service.launchApp(context, "com.whatsapp")) {
            return "WhatsApp nahi khul paya."
        }
        val contact = target.ifBlank { what }
        when {
            what.contains("call") -> {
                val ok = JarvisAccessibilityService.tapText(service, "Call", exact = false)
                return if (ok) "WhatsApp par $contact ko call lag raha hai." else "WhatsApp call screen nahi mila."
            }
            what.contains("message") || what.contains("msg") || what.contains("bhejo") || what.contains("bhej") -> {
                // Search box → contact → message box → send
                var ok = JarvisAccessibilityService.tapText(service, "Search", exact = false)
                if (!ok) ok = JarvisAccessibilityService.tapText(service, "search", exact = false)
                if (!ok) return "WhatsApp search nahi mila — accessibility ON hai na?"
                service.sendKeys(contact)
                Thread.sleep(700)
                JarvisAccessibilityService.tapText(service, contact, exact = false)
                Thread.sleep(700)
                val sent = JarvisAccessibilityService.tapText(service, "Send", exact = false)
                return if (sent) "WhatsApp par $contact ke liye message screen khul gaya." else "$contact chat nahi khuli."
            }
            else -> return "WhatsApp khula. Ab 'whatsapp call mummy' ya 'whatsapp message rahul' boliye."
        }
    }

    // ---------------------------------------------------------------- YouTube

    private fun runYouTube(context: Context, service: JarvisAccessibilityService, what: String, target: String): String {
        if (!service.launchApp(context, "com.google.android.youtube")) {
            return "YouTube nahi khul paya."
        }
        when {
            what.contains("subscribe") || what.contains("subscribe") -> {
                val ok = JarvisAccessibilityService.tapText(service, "Subscribe", exact = false)
                return if (ok) "Subscribe kar diya." else "Subscribe button nahi mila."
            }
            what.contains("full screen") || what.contains("fullscreen") || what.contains("poora screen") -> {
                val ok = JarvisAccessibilityService.tapText(service, "Full screen", exact = false)
                return if (ok) "Full screen kar diya." else "Video screen nahi mila."
            }
            what.contains("play") -> {
                val ok = JarvisAccessibilityService.tapText(service, "Play", exact = false)
                return if (ok) "Play kar diya." else "Play button nahi mila."
            }
            else -> {
                var ok = JarvisAccessibilityService.tapText(service, "Search", exact = false)
                if (!ok) ok = JarvisAccessibilityService.tapText(service, "search", exact = false)
                if (ok) {
                    service.sendKeys(target)
                    return "YouTube par '$target' search kar raha hoon."
                }
                return "YouTube khula. 'youtube subscribe' jaise boliye."
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun resolvePackage(text: String): String? = when {
        text.contains("whatsapp") -> "com.whatsapp"
        text.contains("youtube") -> "com.google.android.youtube"
        text.contains("instagram") -> "com.instagram.android"
        text.contains("facebook") -> "com.facebook.katana"
        text.contains("telegram") -> "org.telegram.messenger"
        text.contains("spotify") -> "com.spotify.music"
        else -> null
    }

    private fun appLabel(pkg: String): String = when {
        pkg.contains("whatsapp") -> "WhatsApp"
        pkg.contains("youtube") -> "YouTube"
        pkg.contains("instagram") -> "Instagram"
        pkg.contains("facebook") -> "Facebook"
        pkg.contains("telegram") -> "Telegram"
        pkg.contains("spotify") -> "Spotify"
        else -> pkg.substringAfterLast('.')
    }
}
