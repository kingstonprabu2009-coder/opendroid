package com.opendroid.ai.core.teaching

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Teaching Mode: while ON, taps and typed text the user does on the phone are recorded
 * (never password fields). When turned OFF the steps are saved as a named lesson in
 * persistent storage. Later, a spoken request that mentions a lesson's name carries that
 * lesson to the agent as a guide.
 */
object TeachingMode {
    private const val PREFS = "jarvis_teaching"
    private const val MAX_STEPS = 80

    @Volatile var active = false
        private set
    private val steps = mutableListOf<String>()
    private var lessonName = ""

    fun start(context: Context, name: String = "") {
        synchronized(steps) { steps.clear() }
        lessonName = name.trim().ifBlank { "lesson " + (lessons(context).length() + 1) }
        active = true
    }

    /** Ends recording and saves the lesson. Returns "name|stepCount". */
    fun stop(context: Context): Pair<String, Int> {
        active = false
        val copy = synchronized(steps) { steps.toList() }
        if (copy.isNotEmpty()) {
            val all = lessons(context)
            all.put(JSONObject().put("name", lessonName).put("steps", JSONArray(copy)))
            while (all.length() > 30) all.remove(0)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("lessons", all.toString()).apply()
        }
        return lessonName to copy.size
    }

    fun record(step: String) {
        if (!active || step.isBlank()) return
        synchronized(steps) {
            if (steps.size < MAX_STEPS && steps.lastOrNull() != step) steps.add(step)
        }
    }

    private fun lessons(context: Context): JSONArray = try {
        JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lessons", "[]"))
    } catch (e: Exception) { JSONArray() }

    fun lessonNames(context: Context): List<String> {
        val all = lessons(context)
        return (0 until all.length()).map { all.getJSONObject(it).getString("name") }
    }

    fun delete(context: Context, name: String) {
        val all = lessons(context)
        val keep = JSONArray()
        for (i in 0 until all.length()) if (all.getJSONObject(i).getString("name") != name) keep.put(all.getJSONObject(i))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lessons", keep.toString()).apply()
    }

    /** Lessons whose name appears in [query], formatted as a guide for the agent. Empty if none. */
    fun guideFor(context: Context, query: String): String {
        val q = query.lowercase()
        val all = lessons(context)
        val sb = StringBuilder()
        for (i in 0 until all.length()) {
            val l = all.getJSONObject(i)
            val name = l.getString("name")
            if (q.contains(name.lowercase())) {
                sb.append("\n[Taught lesson \"").append(name).append("\" - the user showed these steps:]\n")
                val st = l.getJSONArray("steps")
                for (j in 0 until st.length()) sb.append(j + 1).append(". ").append(st.getString(j)).append('\n')
            }
        }
        return sb.toString().take(1800)
    }
}
