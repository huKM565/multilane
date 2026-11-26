package org.olcbox.app.net

import org.olcbox.app.data.model.BondRoomConfig
import org.olcbox.app.data.model.LocationConfig

data class BondPathPlan(
    val paths: List<BondRoomConfig>,
    val ports: List<Int>,
)

fun planBondPaths(config: LocationConfig, allocatePort: () -> Int): BondPathPlan {
    val normalized = config.normalized()
    require(normalized.bondVlessLink != null) { "a bonded location requires a VLESS link" }
    val paths = normalized.bondedPaths()
    require(paths.size == 4 || paths.size == 8 || paths.size == 12 || paths.size == 16) { "a bonded location requires four, eight, twelve or sixteen paths" }
    val ports = mutableListOf<Int>()
    paths.forEach { _ ->
        val port = allocatePort()
        require(port !in ports) { "could not allocate distinct room ports" }
        ports += port
    }
    return BondPathPlan(paths, ports)
}
