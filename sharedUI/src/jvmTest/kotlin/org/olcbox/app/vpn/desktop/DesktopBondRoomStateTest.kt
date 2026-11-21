package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.BondRoomConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopBondRoomStateTest {
    @Test
    fun occupancyReceivedBeforeActivationIsShownWhenRoomsBecomeActive() {
        val state = DesktopBondRoomState()
        state.configure(List(4) { index ->
            BondRoomConfig("room-$index", "key", "jitsi", "datachannel")
        })
        state.setActive(listOf(0))

        state.setOccupant(1, 2)
        state.setOccupant(2, 3)
        state.setActive(listOf(0, 1, 2))

        assertEquals(listOf(null, 2, 3, null), state.states.value?.map { it.occupants })
    }
}
