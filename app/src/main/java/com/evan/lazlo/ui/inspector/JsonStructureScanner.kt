package com.evan.lazlo.ui.inspector

import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds the array most likely to be "the actual payload" inside an
 * arbitrary JSON body whose shape isn't known ahead of time — an API
 * response is often a few levels of wrapper object around the one array
 * that matters (`data.items`, `result.rows`, …), and staring at raw JSON
 * in the inspector to find it by hand doesn't scale past a few levels of
 * nesting. Walks the whole tree, scores every array it finds by size and
 * by how consistent its elements' keys are with each other, and keeps
 * the best one — the same "deep-scan for the array of similarly-shaped
 * objects" idea plenty of JSON tooling uses to make sense of an unknown
 * response shape. Pure and dependency-free beyond org.json (already used
 * elsewhere in this codebase for request/response shaping), so it's
 * unit-testable without Android.
 */
object JsonStructureScanner {

    data class Finding(val path: String, val itemCount: Int)

    /** Returns null if [body] isn't valid JSON, or no array of similarly-shaped objects was found in it. */
    fun scan(body: String): Finding? {
        val root: Any = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONArray(body) }.getOrNull()
            ?: return null

        var best: Finding? = null
        var bestScore = 0.0
        walk(root, "$") { path, array ->
            val score = scoreArray(array)
            if (score > bestScore) {
                bestScore = score
                best = Finding(path, array.length())
            }
        }
        return best
    }

    // Paths are always "$"-anchored — "$.data.items", never a bare
    // "data.items" — so a path is unambiguous to re-walk later
    // ([resolveArrayLength]) without needing to special-case "the first
    // segment has no leading dot" against "every segment after it does."
    private fun walk(node: Any?, path: String, onArray: (path: String, array: JSONArray) -> Unit) {
        when (node) {
            is JSONObject -> node.keys().forEach { key ->
                walk(node.opt(key), "$path.$key", onArray)
            }
            is JSONArray -> {
                onArray(path, node)
                for (i in 0 until node.length()) walk(node.opt(i), "$path[$i]", onArray)
            }
        }
    }

    /**
     * Higher is better: rewards more elements and keys that stay
     * consistent across them (a real record array), scores zero for an
     * empty array or one that isn't objects at all (a plain string/number
     * list is rarely "the payload" a user's looking for in an inspector).
     */
    private fun scoreArray(array: JSONArray): Double {
        if (array.length() == 0) return 0.0
        val objects = (0 until array.length()).mapNotNull { array.opt(it) as? JSONObject }
        if (objects.isEmpty()) return 0.0
        val keySets = objects.map { obj -> obj.keys().asSequence().toSet() }
        val allKeys = keySets.reduce { a, b -> a union b }
        if (allKeys.isEmpty()) return 0.0
        val consistency = keySets.map { it.size.toDouble() / allKeys.size }.average()
        return objects.size * consistency
    }

    /**
     * Resolves a [Finding.path] against a (possibly different) [body],
     * returning the array's element count if the path still lands on an
     * array, null otherwise. What [LearnedJsonPathCache] uses to check a
     * cached path still holds before trusting it against a new body,
     * rather than assuming the last-seen shape never changes.
     */
    fun resolveArrayLength(body: String, path: String): Int? {
        val root: Any = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONArray(body) }.getOrNull()
            ?: return null
        if (path == "$") return (root as? JSONArray)?.length()

        var current: Any? = root
        val token = Regex("""\.([^.\[]+)|\[(\d+)]""")
        for (match in token.findAll(path.removePrefix("$"))) {
            val key = match.groupValues[1]
            current = if (key.isNotEmpty()) {
                (current as? JSONObject)?.opt(key)
            } else {
                (current as? JSONArray)?.opt(match.groupValues[2].toInt())
            }
            if (current == null) return null
        }
        return (current as? JSONArray)?.length()
    }
}
