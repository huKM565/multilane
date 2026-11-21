package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.olcbox.app.data.model.BondRoomConfig
import org.olcbox.app.vpn.BondRoomError
import org.olcbox.app.vpn.BondRoomStatus
import org.olcbox.app.vpn.bondRoomLabels
import org.olcbox.app.vpn.displayedOccupants
import org.olcbox.app.vpn.withOccupancy

internal class DesktopBondRoomState {
    private val _states = MutableStateFlow<List<BondRoomStatus>?>(null)
    val states: StateFlow<List<BondRoomStatus>?> = _states.asStateFlow()
    private var relayReady = emptyList<Boolean>()
    private var rawOccupants = emptyList<Int?>()

    @Synchronized
    fun configure(paths: List<BondRoomConfig>) {
        val labels = bondRoomLabels(paths.map { it.provider })
        _states.value = paths.mapIndexed { index, _ ->
            BondRoomStatus(labels[index], active = false)
        }
        relayReady = List(paths.size) { false }
        rawOccupants = List(paths.size) { null }
    }

    @Synchronized
    fun setActive(activeIndexes: List<Int>?) {
        if (activeIndexes == null) {
            _states.value = null
            relayReady = emptyList()
            rawOccupants = emptyList()
            return
        }
        _states.value = _states.value.orEmpty().mapIndexed { index, status ->
            status.copy(
                active = index in activeIndexes,
                occupants = displayedOccupants(
                    rawOccupants[index],
                    relayReady.getOrElse(index) { false },
                ),
            )
        }
    }

    @Synchronized
    fun setOccupant(index: Int, participants: Int) {
        val states = _states.value ?: return
        val status = states.getOrNull(index) ?: return
        rawOccupants = rawOccupants.toMutableList().also { it[index] = participants }
        _states.value = states.toMutableList().also {
            it[index] = status.withOccupancy(
                participants,
                relayReady.getOrElse(index) { false },
            )
        }
    }

    @Synchronized
    fun setRoomState(index: Int, state: String) {
        val states = _states.value ?: return
        val status = states.getOrNull(index) ?: return
        val isRelayReady = state == "relay-ready"
        relayReady = relayReady.toMutableList().also { it[index] = isRelayReady }
        val error = when (state) {
            "joined", "relay-ready" -> null
            "join-failed" -> BondRoomError.CLIENT
            "relay-failed" -> BondRoomError.SERVER
            else -> return
        }
        val active = if (state == "join-failed" || state == "relay-failed") false else status.active
        _states.value = states.toMutableList().also {
            it[index] = status.copy(active = active, error = error).withOccupancy(
                rawOccupants.getOrNull(index),
                isRelayReady,
            )
        }
    }
}
