package com.evan.lazlo.ui.inspector

/**
 * Per-host cache of [JsonStructureScanner] results. A given host's API
 * usually keeps sending the same JSON shape, so once a scan finds the
 * main payload array for it, remembering that path means the next body
 * from the same host can skip straight to it instead of re-walking the
 * whole tree — the same "learn the shape once, take the fast path after"
 * idea any tool benefits from when it has to repeatedly make sense of a
 * shape it doesn't control. In-memory only, and deliberately just a
 * hint, not a source of truth: [InspectorViewModel] always re-scans if a
 * cached path doesn't hold up against the new body (see [forget]) —
 * wrong or stale never means broken, just a fresh scan.
 */
class LearnedJsonPathCache {
    private val learned = mutableMapOf<String, JsonStructureScanner.Finding>()

    fun get(host: String): JsonStructureScanner.Finding? = learned[host]

    fun learn(host: String, finding: JsonStructureScanner.Finding) {
        learned[host] = finding
    }

    /** Drops a cached entry — call when it no longer matches what a fresh scan of that host's traffic finds. */
    fun forget(host: String) {
        learned.remove(host)
    }
}
