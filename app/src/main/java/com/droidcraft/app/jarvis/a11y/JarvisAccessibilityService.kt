package com.droidcraft.app.jarvis.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Screen automation — Jarvis screen ka text padh sakta hai aur kisi bhi
 * text/button par click kar sakta hai. User ko Accessibility settings mein
 * "Jarvis Automation" enable karna hota hai.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Screen par dikh rahe saare text (buttons, labels) ki list. */
    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val lines = LinkedHashSet<String>()
        collectText(root, lines, 0)
        return lines.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableSet<String>, depth: Int) {
        if (node == null || depth > 40) return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, depth + 1)
        }
    }

    /** Diye gaye text wala element dhundh kar click karo. */
    fun tapText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val needle = target.trim().lowercase()
        if (needle.isEmpty()) return false
        val node = findNode(root, needle, 0) ?: return false
        return clickNode(node)
    }

    private fun findNode(node: AccessibilityNodeInfo?, needle: String, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (text.contains(needle) || desc.contains(needle)) return node
        for (i in 0 until node.childCount) {
            val found = findNode(node.getChild(i), needle, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /** Editable field mein text bharo (ACTION_SET_TEXT). */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = findEditable(root, 0) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching { field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
    }

    /** Search box mein text bharo (automation helper). */
    fun sendKeys(text: String): Boolean = typeText(text)

    /** App launch karo (accessibility service ke paas package manager access hota hai). */
    fun launchApp(context: android.content.Context, pkg: String): Boolean {
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun findEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (node.isEditable && node.className?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    /** Pehle khud click karo, na ho to parent ko clickable tak le jao. */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops++
        }
        return false
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        /** Settings → Accessibility mein service ON hai ya nahi. */
        fun isEnabled(context: android.content.Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains("com.droidcraft.app", ignoreCase = true)
        }
    }
}
