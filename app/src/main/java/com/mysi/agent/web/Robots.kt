package com.mysi.agent.web

/**
 * robots.txt rules (RFC 9309) for one site, as they apply to MySI. Used for automated fetching
 * (deep research), not for a page the user asked to open, which is like a browser visit.
 * The group naming "mysi" wins over "*"; the longest matching rule wins; Allow wins ties;
 * `*` and a trailing `$` work as wildcards.
 */
class Robots private constructor(private val rules: List<Pair<Boolean, String>>) {
    fun allowed(pathAndQuery: String): Boolean {
        var best: Pair<Boolean, String>? = null
        for (r in rules) {
            if (r.second.isEmpty() || !matches(r.second, pathAndQuery)) continue
            val b = best
            if (b == null || r.second.length > b.second.length || (r.second.length == b.second.length && r.first)) best = r
        }
        return best?.first ?: true
    }

    companion object {
        const val AGENT = "mysi"
        val ALLOW_ALL = Robots(emptyList())
        val DISALLOW_ALL = Robots(listOf(false to "/"))

        private fun matches(pattern: String, path: String): Boolean {
            val anchored = pattern.endsWith("$")
            val p = if (anchored) pattern.dropLast(1) else pattern
            val regex = p.split('*').joinToString(".*") { Regex.escape(it) } + if (anchored) "$" else ""
            return Regex("^$regex").containsMatchIn(path)
        }

        fun parse(text: String): Robots {
            val groups = mutableListOf<Pair<MutableList<String>, MutableList<Pair<Boolean, String>>>>()
            var lastWasAgent = false
            for (raw in text.lineSequence().take(5000)) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val key = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                when (key) {
                    "user-agent" -> {
                        if (!lastWasAgent || groups.isEmpty()) groups += mutableListOf<String>() to mutableListOf()
                        groups.last().first += value.lowercase()
                        lastWasAgent = true
                    }
                    "allow", "disallow" -> {
                        lastWasAgent = false
                        if (groups.isEmpty()) continue
                        // An empty Disallow means "allow everything" and adds no rule.
                        if (value.isNotEmpty()) groups.last().second += (key == "allow") to value
                    }
                    else -> lastWasAgent = false
                }
            }
            val mine = groups.filter { g -> AGENT in g.first }
            val chosen = mine.ifEmpty { groups.filter { g -> "*" in g.first } }
            return Robots(chosen.flatMap { it.second })
        }
    }
}
