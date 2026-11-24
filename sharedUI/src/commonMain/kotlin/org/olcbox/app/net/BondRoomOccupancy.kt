package org.olcbox.app.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.BondRoomConfig
import org.olcbox.app.data.model.LocationConfig
import kotlin.random.Random

fun interface BondRoomOccupancyProbe {
    suspend fun occupants(room: BondRoomConfig): Int?
}

expect fun createJitsiOccupancyProbe(): BondRoomOccupancyProbe

private const val OCCUPANCY_PROBE_TIMEOUT_MS = 4_000L

fun selectActiveBondPlan(
    plan: BondPathPlan,
    activeCount: Int,
    occupancies: Map<Int, Int>,
    random: Random = Random.Default,
): BondPathPlan {
    val total = plan.paths.size
    if (total == 0) return plan
    val want = activeCount.coerceIn(1, total)

    val tieOrder = plan.paths.indices.shuffled(random).withIndex().associate { it.value to it.index }
    fun tier(count: Int?): Int = when {
        count == null || count < 0 -> 1
        count >= 1 -> 0
        else -> 2
    }
    val ranked = plan.paths.indices.sortedWith(
        compareBy<Int>(
            { tier(occupancies[it]) },
            { if (tier(occupancies[it]) == 0) occupancies[it] ?: Int.MAX_VALUE else 0 },
            { tieOrder[it] ?: it },
        )
    )
    val chosen = ranked.take(want)
    return BondPathPlan(
        paths = chosen.map { plan.paths[it] },
        ports = chosen.map { plan.ports[it] },
    )
}

suspend fun selectBondPlanByOccupancy(
    plan: BondPathPlan,
    activeCount: Int,
    probe: BondRoomOccupancyProbe,
    log: (String) -> Unit = {},
    random: Random = Random.Default,
): BondPathPlan {
    val total = plan.paths.size
    if (total == 0) return plan
    val want = activeCount.coerceIn(1, total)
    val jitsiCount = plan.paths.count { it.provider == LocationConfig.PROVIDER_JITSI }
    val shouldProbe = jitsiCount >= 2 && (want < total || total > 4)
    if (!shouldProbe) {
        if (want == total) return plan
        return selectActiveBondPlan(plan, want, emptyMap(), random)
    }

    val occupancies = withTimeoutOrNull(OCCUPANCY_PROBE_TIMEOUT_MS) {
        coroutineScope {
            plan.paths.mapIndexed { idx, room ->
                async {
                    if (room.provider != LocationConfig.PROVIDER_JITSI) {
                        idx to -1
                    } else {
                        val n = runCatching { probe.occupants(room) }.getOrNull()
                        idx to (n ?: -1)
                    }
                }
            }.awaitAll().toMap()
        }
    } ?: emptyMap()

    val selected = selectActiveBondPlan(plan, want, occupancies, random)
    val summary = plan.paths.indices.joinToString(" ") { idx ->
        val c = occupancies[idx]
        if (c == null || c < 0) "r${idx + 1}=?" else "r${idx + 1}=$c"
    }
    val indexByRoom = plan.paths.withIndex().associate { it.value.room to (it.index + 1) }
    val chosenLabels = selected.paths.joinToString(",") { "r${indexByRoom[it.room] ?: 0}" }
    log("bond occupancy: $summary -> active ${selected.paths.size}/$total ($chosenLabels)")
    return selected
}

internal fun parseJitsiRoomUrl(raw: String): Pair<String, String>? {
    val trimmed = raw.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val afterScheme = trimmed.substring(schemeEnd + 3)
    val slash = afterScheme.indexOf('/')
    if (slash <= 0) return null
    val host = afterScheme.substring(0, slash).trim()
    val path = afterScheme.substring(slash + 1).substringBefore('?').substringBefore('#').trim('/')
    val room = path.substringAfterLast('/').trim().lowercase()
    if (host.isBlank() || room.isBlank()) return null
    return host to room
}

internal fun extractJsField(body: String, key: String): String {
    for (rawLine in body.lineSequence()) {
        var t = rawLine.trim()
        t = t.removePrefix("config.hosts.").removePrefix("config.")
        if (!t.startsWith(key)) continue
        var rest = t.removePrefix(key)
        if (rest.isEmpty() || rest[0] !in listOf(':', '=', ' ', '\t')) continue
        rest = rest.trimStart(' ', '\t', ':', '=')
        val sepIdx = rest.indexOfFirst { it == ';' || it == ',' }
        if (sepIdx >= 0) rest = rest.substring(0, sepIdx)
        val commentIdx = indexJsComment(rest)
        if (commentIdx >= 0) rest = rest.substring(0, commentIdx)
        val value = joinJsLiterals(rest)
        if (value.isNotEmpty()) return value
    }
    return ""
}

private fun indexJsComment(s: String): Int {
    var quote = 0.toChar()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (quote != 0.toChar()) {
            if (c == quote) quote = 0.toChar()
            i++
            continue
        }
        if (c == '\'' || c == '"') {
            quote = c
            i++
            continue
        }
        if (c == '/' && i + 1 < s.length && s[i + 1] == '/') return i
        i++
    }
    return -1
}

private fun joinJsLiterals(expr: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < expr.length) {
        val c = expr[i]
        if (c != '\'' && c != '"') {
            i++
            continue
        }
        val end = expr.indexOf(c, i + 1)
        if (end < 0) break
        sb.append(expr, i + 1, end)
        i = end + 1
    }
    return sb.toString()
}

internal fun extractXmlTag(text: String, tag: String): String {
    val open = "<$tag>"
    val start = text.indexOf(open)
    if (start < 0) return ""
    val bodyStart = start + open.length
    val end = text.indexOf("</$tag>", bodyStart)
    if (end < 0) return ""
    return text.substring(bodyStart, end)
}

internal fun extractXmlAttribute(text: String, attr: String): String {
    for (q in charArrayOf('"', '\'')) {
        val key = "$attr=$q"
        val start = text.indexOf(key)
        if (start < 0) continue
        val valStart = start + key.length
        val end = text.indexOf(q, valStart)
        if (end < 0) continue
        return text.substring(valStart, end)
    }
    return ""
}
