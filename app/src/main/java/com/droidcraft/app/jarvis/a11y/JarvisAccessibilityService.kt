package com.droidcraft.app.jarvis.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

sealed interface LabelTapResult {
    data object Tapped : LabelTapResult
    data class Refused(val reason: String) : LabelTapResult
    data class NotFound(val reason: String) : LabelTapResult
}

/** Screen-only AccessibilityService. It owns no overlay, conversation, microphone, or brain. */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** A compact, labelled outline; IDs are per-dump labels, never screen coordinates. */
    fun dumpScreen(maxElements: Int = 100): String {
        val root = rootInActiveWindow ?: return "The active window did not expose an accessibility tree."
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectVisibleNodes(root, nodes, 0, maxElements.coerceIn(1, MAX_SCREEN_ELEMENTS))
        val packageName = root.packageName?.toString().orEmpty().ifBlank { "unknown" }
        if (nodes.isEmpty()) return "No labelled accessibility elements are exposed by the active window (package: $packageName)."
        return buildString {
            appendLine("Visible labelled elements in $packageName:")
            nodes.forEachIndexed { index, node ->
                val label = labelOf(node)
                val traits = buildList {
                    if (isClickableOrHasClickableAncestor(node)) add("button")
                    if (node.isEditable) add("editable field")
                    if (node.isScrollable) add("scrollable")
                    if (!node.isEnabled) add("disabled")
                }
                val viewId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                append("e${index + 1}: \"")
                append(label.replace('\n', ' ').take(MAX_LABEL_LENGTH))
                append('"')
                if (traits.isNotEmpty()) append(" [${traits.joinToString(", ")}]")
                if (viewId != null) append(" {id=$viewId}")
                appendLine()
            }
        }.take(MAX_SCREEN_CHARS)
    }

    private fun collectVisibleNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        limit: Int
    ) {
        if (node == null || depth > MAX_TREE_DEPTH || out.size >= limit || !node.isVisibleToUser) return
        if (labelOf(node).isNotBlank()) out += node
        for (index in 0 until node.childCount) {
            if (out.size >= limit) break
            collectVisibleNodes(node.getChild(index), out, depth + 1, limit)
        }
    }

    fun activeWindowPackageName(): String? = rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }

    fun activeWindowAppLabel(): String? = applicationLabelFor(activeWindowPackageName())

    @Suppress("DEPRECATION")
    private fun applicationLabelFor(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return runCatching {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        }.getOrNull()
    }

    /** Taps only one unique visible label; an on-screen confirmation can be bound to its original app. */
    fun tapText(
        target: String,
        confirmedByUser: Boolean = false,
        expectedPackageName: String? = null,
        userText: String = ""
    ): LabelTapResult {
        val root = rootInActiveWindow
            ?: return LabelTapResult.NotFound("The active window is unavailable; no tap was sent.")
        val packageName = root.packageName?.toString()
        val appLabel = applicationLabelFor(packageName)
        if (expectedPackageName != null && packageName != expectedPackageName) {
            return LabelTapResult.Refused("The active app changed before confirmation. No tap was sent; ask again on the intended screen.")
        }
        when (val decision = ActionSafety.assessTap(target, packageName, appLabel, userText)) {
            is ActionSafety.TapDecision.Blocked -> return LabelTapResult.Refused(decision.reason)
            is ActionSafety.TapDecision.RequiresConfirmation -> if (!confirmedByUser) {
                return LabelTapResult.Refused(decision.reason)
            }
            ActionSafety.TapDecision.Allowed -> Unit
        }
        val needle = normalize(target)
        if (needle.isBlank()) return LabelTapResult.Refused("A non-empty visible label is required; raw coordinates are not accepted.")
        val search = findBestLabelledNode(root, needle)
        val match = search.match ?: return if (search.ambiguous) {
            LabelTapResult.NotFound("More than one visible control matches '$target'. Use a more specific exact label; no tap was sent.")
        } else {
            LabelTapResult.NotFound("No visible enabled control labelled '$target' was found; no tap was sent.")
        }
        when (val decision = ActionSafety.assessTap(match.label, packageName, appLabel, userText)) {
            is ActionSafety.TapDecision.Blocked -> return LabelTapResult.Refused(decision.reason)
            is ActionSafety.TapDecision.RequiresConfirmation -> if (!confirmedByUser) {
                return LabelTapResult.Refused(decision.reason)
            }
            ActionSafety.TapDecision.Allowed -> Unit
        }
        return if (runCatching { match.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
            LabelTapResult.Tapped
        } else {
            LabelTapResult.NotFound("Android refused the click on '$target'; no other target was tried.")
        }
    }

    private data class NodeMatch(val clickableNode: AccessibilityNodeInfo, val label: String)
    private data class SearchResult(val match: NodeMatch?, val ambiguous: Boolean)

    private fun findBestLabelledNode(root: AccessibilityNodeInfo, needle: String): SearchResult {
        val exact = LinkedHashMap<String, NodeMatch>()
        val partial = LinkedHashMap<String, NodeMatch>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TREE_DEPTH || !node.isVisibleToUser || !node.isEnabled) return
            val label = labelOf(node)
            if (label.isNotBlank()) {
                val normalized = normalize(label)
                if (normalized == needle || normalized.contains(needle)) {
                    val clickNode = clickableAncestorOrSelf(node)
                    if (clickNode != null) {
                        val bounds = Rect().also(clickNode::getBoundsInScreen)
                        val key = listOf(
                            clickNode.viewIdResourceName.orEmpty(),
                            bounds.left, bounds.top, bounds.right, bounds.bottom, normalized
                        ).joinToString("|")
                        val candidate = NodeMatch(clickNode, label)
                        if (normalized == needle) exact.putIfAbsent(key, candidate)
                        else partial.putIfAbsent(key, candidate)
                    }
                }
            }
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(root, 0)
        val candidates = if (exact.isNotEmpty()) exact.values.toList() else partial.values.toList()
        return when (candidates.size) {
            0 -> SearchResult(null, false)
            1 -> SearchResult(candidates.single(), false)
            else -> SearchResult(null, true)
        }
    }

    private fun clickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_CLICK_PARENT_HOPS) {
            if (current.isVisibleToUser && current.isEnabled && current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun isClickableOrHasClickableAncestor(node: AccessibilityNodeInfo): Boolean = clickableAncestorOrSelf(node) != null

    private fun labelOf(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.trim().orEmpty()

    /** Scrolls an accessible scroll container; a fallback gesture stays inside its reported bounds. */
    fun scroll(direction: String): Boolean {
        val normalized = direction.trim().lowercase(Locale.ROOT)
        if (normalized !in setOf("up", "down")) return false
        val root = rootInActiveWindow ?: return false
        val container = findScrollable(root, 0) ?: return false
        val action = if (normalized == "down") {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (runCatching { container.performAction(action) }.getOrDefault(false)) return true
        // Some apps expose the scroll container but not its scroll action. Derive a short gesture
        // from that element's actual bounds instead of guessing screen-wide coordinates.
        val bounds = Rect().also(container::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val x = bounds.centerX().toFloat()
        val top = bounds.top + bounds.height() * 0.22f
        val bottom = bounds.bottom - bounds.height() * 0.22f
        return dispatchSwipe(x, if (normalized == "down") bottom else top, x, if (normalized == "down") top else bottom)
    }

    /** Directional swipe constrained to a visible scrollable element's bounds. */
    fun swipe(direction: String): Boolean {
        val normalized = direction.trim().lowercase(Locale.ROOT)
        if (normalized !in setOf("up", "down", "left", "right")) return false
        val root = rootInActiveWindow ?: return false
        val container = findScrollable(root, 0) ?: return false
        val bounds = Rect().also(container::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val startX = bounds.left + bounds.width() * 0.78f
        val endX = bounds.left + bounds.width() * 0.22f
        val startY = bounds.top + bounds.height() * 0.78f
        val endY = bounds.top + bounds.height() * 0.22f
        val (x1, y1, x2, y2) = when (normalized) {
            "up" -> listOf(bounds.centerX().toFloat(), startY, bounds.centerX().toFloat(), endY)
            "down" -> listOf(bounds.centerX().toFloat(), endY, bounds.centerX().toFloat(), startY)
            "left" -> listOf(startX, bounds.centerY().toFloat(), endX, bounds.centerY().toFloat())
            else -> listOf(endX, bounds.centerY().toFloat(), startX, bounds.centerY().toFloat())
        }
        return dispatchSwipe(x1, y1, x2, y2)
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_TREE_DEPTH || !node.isVisibleToUser || !node.isEnabled) return null
        if (node.isScrollable) return node
        for (index in 0 until node.childCount) {
            findScrollable(node.getChild(index), depth + 1)?.let { return it }
        }
        return null
    }

    fun navigate(action: String): Boolean = when (action.uppercase(Locale.ROOT)) {
        "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
        else -> false
    }

    /** Only writes to an already focused editable node; Android 13+ uses this service's input channel. */
    @SuppressLint("NewApi")
    fun typeIntoFocusedField(text: String): Boolean {
        if (text.isEmpty() || text.length > MAX_TYPED_TEXT_LENGTH) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocusedEditable(root, 0)
            ?: return false
        if (!focused.isEditable || !focused.isFocused || !focused.isEnabled) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val inputMethod = runCatching { inputMethod }.getOrNull() ?: return false
            if (!runCatching { inputMethod.currentInputStarted }.getOrDefault(false)) return false
            val connection = runCatching { inputMethod.currentInputConnection }.getOrNull() ?: return false
            return runCatching {
                connection.commitText(text, 1, null)
                true
            }.getOrDefault(false)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_TREE_DEPTH || !node.isVisibleToUser || !node.isEnabled) return null
        if (node.isEditable && node.isFocused) return node
        for (index in 0 until node.childCount) {
            findFocusedEditable(node.getChild(index), depth + 1)?.let { return it }
        }
        return null
    }

    /** Android 11+ accessibility screenshot, with no input capability. */
    @SuppressLint("NewApi")
    suspend fun captureScreenshotPng(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                var executor: ExecutorService? = null
                try {
                    executor = Executors.newSingleThreadExecutor()
                    val callbackExecutor = executor
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        callbackExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                try {
                                    val hardwareBuffer = screenshot.hardwareBuffer
                                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                        ?: throw IllegalStateException("Android returned an unreadable screenshot buffer.")
                                    try {
                                        val scale = minOf(1f, MAX_SCREENSHOT_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
                                        val outputBitmap = if (scale < 1f) {
                                            Bitmap.createScaledBitmap(
                                                bitmap,
                                                (bitmap.width * scale).toInt().coerceAtLeast(1),
                                                (bitmap.height * scale).toInt().coerceAtLeast(1),
                                                true
                                            )
                                        } else bitmap
                                        try {
                                            val output = ByteArrayOutputStream()
                                            if (!outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                                throw IllegalStateException("Android could not encode the screenshot.")
                                            }
                                            if (continuation.isActive) continuation.resume(output.toByteArray())
                                        } finally {
                                            if (outputBitmap !== bitmap) outputBitmap.recycle()
                                        }
                                    } finally {
                                        bitmap.recycle()
                                        hardwareBuffer.close()
                                    }
                                } catch (_: Throwable) {
                                    if (continuation.isActive) continuation.resume(null)
                                } finally {
                                    callbackExecutor.shutdown()
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                if (continuation.isActive) continuation.resume(null)
                                callbackExecutor.shutdown()
                            }
                        }
                    )
                } catch (_: Throwable) {
                    executor?.shutdownNow()
                    if (continuation.isActive) continuation.resume(null)
                }
                continuation.invokeOnCancellation { executor?.shutdownNow() }
            }
        }
    }

    private fun stripMatras(value: String): String =
        value.replace(Regex("[\\u093C\\u093E-\\u094D\\u0900-\\u0903\\u0962-\\u0963\\u200C\\u200D]"), "")
private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /** Resolves only an exact or uniquely high-confidence launcher label. */
    fun launchAppByName(query: String): String? {
        val needle = normalize(query)
        if (needle.isBlank()) return null
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }.getOrDefault(emptyList())
        val candidates = apps.mapNotNull { info ->
            val label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault("").trim()
            val normalized = normalize(label)
                        val score = when {
                normalized == needle -> 100
                normalized.replace(" ") == needle.replace(" ") -> 95
                stripMatras(normalized) == stripMatras(needle) -> 90
                normalized.startsWith(needle) -> 80
                normalized.contains(needle) -> 60
                else -> 0
                        }
            if (score == 0) null else Triple(label, info.activityInfo.packageName, score)
        }
        val bestScore = candidates.maxOfOrNull { it.third } ?: return null
        val best = candidates.filter { it.third == bestScore }.distinctBy { it.second }
        if (best.size != 1) return null
        val launchIntent = packageManager.getLaunchIntentForPackage(best.single().second) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(launchIntent)
            best.single().first
        }.getOrNull()
    }

    companion object {
        private const val MAX_TREE_DEPTH = 40
        private const val MAX_CLICK_PARENT_HOPS = 8
        private const val MAX_SCREEN_ELEMENTS = 100
        private const val MAX_SCREEN_CHARS = 6_000
        private const val MAX_LABEL_LENGTH = 240
        private const val MAX_TYPED_TEXT_LENGTH = 2_000
        private const val MAX_SCREENSHOT_SIDE = 1_280
        private const val SWIPE_DURATION_MS = 350L

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, JarvisAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any { it.equals(component.flattenToString(), ignoreCase = true) }
        }
    }
}
