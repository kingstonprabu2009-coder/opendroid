package com.opendroid.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.os.Build
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@AndroidEntryPoint
class OpenDroidAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var nodeTraversal: AccessibilityNodeTraversal

    @Inject
    lateinit var habitRoutineEngine: dagger.Lazy<com.opendroid.ai.core.routine.HabitRoutineEngine>

    private val imeSubmitLabels = listOf("search", "go", "send", "done", "submit", "ok", "enter")


    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (com.opendroid.ai.core.teaching.TeachingMode.active && event.packageName?.toString() != packageName) {
            val app = event.packageName?.toString().orEmpty()
            val label = (event.text?.joinToString(" ") ?: "").ifBlank { event.contentDescription?.toString().orEmpty() }
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED ->
                    com.opendroid.ai.core.teaching.TeachingMode.record("tap \"$label\" in $app")
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                    if (!event.isPassword) com.opendroid.ai.core.teaching.TeachingMode.record("type \"$label\" in $app")
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    com.opendroid.ai.core.teaching.TeachingMode.record("open $app")
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank() && pkg != packageName) {
                habitRoutineEngine.get().recordAppOpen(pkg)
            }
        }
    }

    override fun onInterrupt() {
        // Handle interruption
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // --- Node Automation Methods ---

    fun findAndClick(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return nodeTraversal.findAndClick(rootNode, text)
    }

    fun findAndClickById(viewId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return true
                }
                parent = parent.parent
            }
            node.recycle()
        }
        return false
    }

    fun findAndType(searchText: String, content: String): Boolean =
        findAndTypeOutcome(searchText, content).typed

    fun findAndTypeOutcome(searchText: String, content: String): TypeOutcome {
        val rootNode = rootInActiveWindow ?: return TypeOutcome.NOT_FOUND
        return nodeTraversal.findAndType(rootNode, searchText, content)
    }

    fun findAndTypeById(viewId: String, content: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (node.isEditable) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                node.recycle()
                return true
            }
            node.recycle()
        }
        return false
    }

    /**
     * Performs the IME 'enter/search/go' action on the currently focused editable
     * field — used to submit a search box after TYPE_TEXT/TYPE_ID types into it.
     * Uses AccessibilityNodeInfo.ACTION_IME_ENTER on API 30+ (the only reliable way
     * to trigger the IME action programmatically). On older API levels — or if that
     * fails — falls back to tapping a nearby clickable control whose label reads like
     * a submit action ("search", "go", "send", "done", "submit", "ok", "enter").
     */
    fun performImeEnter(): Boolean {
        val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val success = if (focusedNode != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    false
                }
            } finally {
                focusedNode.recycle()
            }
        } else {
            false
        }
        return success || performSubmitFallback()
    }

    private fun performSubmitFallback(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = nodeTraversal.findAndClickSubmitControl(rootNode, imeSubmitLabels)
        rootNode.recycle()
        return result
    }

    fun performScroll(forward: Boolean): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val success = performScrollOnNode(rootNode, action)
        rootNode.recycle()
        return success
    }

    private fun performScrollOnNode(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable) {
            return node.performAction(action)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (performScrollOnNode(child, action)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }


    /** Taps the first video in YouTube search results (nodes described "... - play video"). */
    fun clickFirstVideoResult(query: String = ""): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString().orEmpty()
            val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
            val matches = words.isEmpty() || words.count { desc.lowercase().contains(it) } >= maxOf(1, (words.size + 1) / 2)
            if (desc.contains("play video", ignoreCase = true) && matches) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty) return clickCoordinates(r.exactCenterX(), r.exactCenterY())
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    // --- Generic screen control used by the live voice (works in any app, e.g. CapCut) ---

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Taps the first visible control whose label or text contains [label]. */
    fun tapByText(label: String): Boolean {
        if (label.isBlank()) return false
        val n = findVisibleNode { d, t -> d.contains(label, true) || t.contains(label, true) } ?: return false
        return tapNode(n)
    }

    /** Types into the focused text field, or the first editable one on screen. */
    fun typeIntoFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Package of the app that is on screen right now (null if unknown). */
    fun activePackage(): String? = rootInActiveWindow?.packageName?.toString()

    // --- YouTube watch-page helpers (subscribe / like / comment) ---

    private fun findVisibleNode(test: (desc: String, text: String) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString().orEmpty()
            val txt = node.text?.toString().orEmpty()
            if ((desc.isNotEmpty() || txt.isNotEmpty()) && node.isVisibleToUser && test(desc, txt)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        var t: AccessibilityNodeInfo? = node
        while (t != null && !t.isClickable) t = t.parent
        if (t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        return !r.isEmpty && clickCoordinates(r.exactCenterX(), r.exactCenterY())
    }

    /** "clicked", "already" (was subscribed before) or "notfound". */
    fun youtubeSubscribe(): String {
        if (findVisibleNode { d, t -> d.startsWith("Subscribed", true) || t.equals("Subscribed", true) } != null) return "already"
        val n = findVisibleNode { d, t ->
            (d.startsWith("Subscribe", true) && !d.startsWith("Subscribed", true)) || t.equals("Subscribe", true)
        } ?: return "notfound"
        return if (tapNode(n)) "clicked" else "notfound"
    }

    /** "clicked", "already" or "notfound". */
    fun youtubeLike(): String {
        if (findVisibleNode { d, _ -> d.startsWith("Unlike", true) || d.contains("liked this", true) } != null) return "already"
        val n = findVisibleNode { d, _ -> d.startsWith("Like this video", true) || d.equals("Like", true) } ?: return "notfound"
        return if (tapNode(n)) "clicked" else "notfound"
    }

    /** Opens the comment box on the watch page, types [text] and posts it. */
    suspend fun youtubeComment(text: String): Boolean {
        if (text.isBlank()) return false
        // 1. open the comments panel (scroll once or twice if it is below the fold)
        var opened = false
        for (attempt in 0 until 4) {
            val c = findVisibleNode { d, t -> d.startsWith("Comments", true) || t.equals("Comments", true) }
            if (c != null && tapNode(c)) { opened = true; break }
            performScroll(true)
            kotlinx.coroutines.delay(700)
        }
        if (!opened) return false
        kotlinx.coroutines.delay(1500)
        // 2. tap "Add a comment..." and wait for the text field
        val add = findVisibleNode { d, t -> d.startsWith("Add a comment", true) || t.startsWith("Add a comment", true) }
        if (add != null) tapNode(add)
        kotlinx.coroutines.delay(1200)
        // 3. type into the editable field
        var typed = false
        for (attempt in 0 until 5) {
            val root = rootInActiveWindow
            val field = root?.let { findEditable(it) }
            if (field != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                typed = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (typed) break
            }
            kotlinx.coroutines.delay(600)
        }
        if (!typed) return false
        kotlinx.coroutines.delay(600)
        // 4. press send
        val send = findVisibleNode { d, t ->
            d.equals("Send", true) || d.startsWith("Send comment", true) || d.equals("Post", true) ||
                t.equals("Send", true) || t.equals("Post", true) || t.equals("Comment", true)
        } ?: return false
        return tapNode(send)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    // --- Gesture Automation Methods (Coordinate Taps) ---

    fun clickCoordinates(x: Float, y: Float): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f || x > width || y > height) return false
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().apply {
            addStroke(stroke)
        }.build()

        return dispatchGesture(gesture, null, null)
    }

    /** Performs a controlled swipe/drag gesture. Used for timeline clips and trim handles in editors. */
    fun swipeCoordinates(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 350L): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        if (!startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite()) return false
        if (startX < 0f || startY < 0f || endX < 0f || endY < 0f || startX > width || endX > width || startY > height || endY > height) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(80L, 1500L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    // --- Screen Text Extraction ---

    fun getScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val text = nodeTraversal.screenText(rootNode)
        rootNode.recycle()
        return text
    }

    fun getScreenLayout(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val layout = nodeTraversal.screenLayout(rootNode)
        rootNode.recycle()
        return layout
    }

    suspend fun takeScreenshotAndEncode(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return suspendCoroutine { continuation ->
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                if (bitmap == null) {
                                    continuation.resume(null)
                                    return
                                }
                                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                bitmap.recycle()
                                hardwareBuffer.close()

                                if (softwareBitmap == null) {
                                    continuation.resume(null)
                                    return
                                }

                                val outputStream = ByteArrayOutputStream()
                                softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                                val byteArray = outputStream.toByteArray()
                                val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                                softwareBitmap.recycle()
                                continuation.resume(base64String)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                continuation.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
                continuation.resume(null)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: OpenDroidAccessibilityService? = null

        fun getInstance(): OpenDroidAccessibilityService? = instance
    }
}
