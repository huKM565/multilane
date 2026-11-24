package org.olcbox.app.net

import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.model.BondRoomConfig
import org.olcbox.app.data.model.LocationConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BondRoomOccupancyTest {
    private fun plan8(): BondPathPlan {
        val paths = (1..8).map { idx ->
            BondRoomConfig(
                room = "https://meet.example.invalid/room-$idx",
                key = "a".repeat(64),
                provider = LocationConfig.PROVIDER_JITSI,
                transport = "datachannel",
            )
        }
        return BondPathPlan(paths, (21081..21088).toList())
    }

    @Test
    fun selectsLeastOccupiedAndAvoidsEmptyRooms() {
        val plan = plan8()
        val occ = mapOf(
            0 to 5,
            1 to 1,
            2 to 0,
            3 to 2,
            4 to 4,
            5 to 1,
            6 to 3,
            7 to 0,
        )
        val chosen = selectActiveBondPlan(plan, 4, occ, Random(42))
        assertEquals(4, chosen.paths.size)
        assertEquals(4, chosen.ports.size)
        assertEquals(
            setOf(plan.paths[1].room, plan.paths[5].room, plan.paths[3].room, plan.paths[6].room),
            chosen.paths.map { it.room }.toSet(),
        )
    }

    @Test
    fun tieBreakingSpreadsEqualOccupancyRooms() {
        val plan = plan8()
        val occ = (0..7).associateWith { 1 }
        val firstRooms = (1..20).map { seed ->
            selectActiveBondPlan(plan, 4, occ, Random(seed)).paths.first().room
        }.toSet()
        assertTrue(firstRooms.size >= 3, "expected spread across tied rooms, got $firstRooms")
    }

    @Test
    fun probesAllEightEvenWhenAllEightAreActive() = runBlocking {
        val plan = plan8()
        var probed = 0
        val logs = mutableListOf<String>()
        val selected = selectBondPlanByOccupancy(
            plan = plan,
            activeCount = 8,
            probe = BondRoomOccupancyProbe {
                probed++
                1
            },
            log = { logs += it },
            random = Random(7),
        )
        assertEquals(8, probed)
        assertEquals(8, selected.paths.size)
        assertTrue(logs.any { it.startsWith("bond occupancy:") })
    }

    @Test
    fun parsesJitsiUrlAndJsConfigFields() {
        assertEquals(
            "meet.mamba.group" to "myroom",
            parseJitsiRoomUrl("https://meet.mamba.group/MyRoom?foo=1"),
        )
        val js = """
            var config = {
                hosts: {
                    domain: 'meet.jitsi',
                    muc: 'muc.' + subdomain + 'meet.jitsi', // comment
                },
                websocket: '//meet.mamba.group/xmpp-websocket',
            };
        """.trimIndent()
        assertEquals("meet.jitsi", extractJsField(js, "domain"))
        assertEquals("muc.meet.jitsi", extractJsField(js, "muc"))
        assertEquals("//meet.mamba.group/xmpp-websocket", extractJsField(js, "websocket"))
    }
}
