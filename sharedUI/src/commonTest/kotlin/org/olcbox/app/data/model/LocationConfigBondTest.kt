package org.olcbox.app.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationConfigBondTest {
    @Test
    fun bondedRoomsKeepThePrimaryAndThreeExtrasInOrder() {
        val config = bondedConfig()

        assertEquals(listOf("room-1", "room-2", "room-3", "room-4"), config.bondedRooms())
        assertTrue(config.isComplete())
    }

    @Test
    fun duplicateRoomsCannotFormAFourRoomBond() {
        val config = bondedConfig(listOf("room-1", "room-2", "room-2", "room-4")).normalized()

        assertEquals(listOf("room-1", "room-2", "room-4"), config.bondedRooms())
        assertFalse(config.isComplete())
    }

    @Test
    fun bondRequiresExactlyFourRooms() {
        assertFalse(bondedConfig(listOf("room-1", "room-2", "room-3")).isComplete())
        assertFalse(bondedConfig(listOf("room-1", "room-2", "room-3", "room-4", "room-5")).isComplete())
    }

    @Test
    fun bondRequiresAVlessLink() {
        assertFalse(bondedConfig(link = "not a link").isComplete())
        assertFalse(bondedConfig(link = "hysteria2://password@127.0.0.1:443#HY2").isComplete())
    }

    @Test
    fun unmarkedLocationKeepsFailoverSemantics() {
        val config = bondedConfig(link = null).normalized()

        assertTrue(config.isComplete())
        assertEquals(emptyList(), config.bondedRooms())
        assertEquals(listOf("room-1", "room-2", "room-3", "room-4"), config.failoverRooms())
    }

    @Test
    fun bondSurvivesJsonSerialization() {
        val config = bondedConfig()

        val decoded = Json.decodeFromString(
            LocationConfig.serializer(),
            Json.encodeToString(LocationConfig.serializer(), config)
        )

        assertEquals(config, decoded)
    }

    @Test
    fun bondSurvivesLocationEntryNormalization() {
        val entry = LocationEntry.from("mamba-x4", bondedConfig()).normalized()

        assertEquals(VLESS_LINK, entry.bondVlessLink)
        assertEquals(VLESS_LINK, entry.location.bondVlessLink)
        assertEquals(listOf("room-1", "room-2", "room-3", "room-4"), entry.location.bondedRooms())
    }

    @Test
    fun legacyBondExpandsToFourPaths() {
        val paths = bondedConfig().bondedPaths()

        assertEquals(listOf("room-1", "room-2", "room-3", "room-4"), paths.map { it.room })
        assertEquals(List(4) { LocationConfig.PROVIDER_JITSI }, paths.map { it.provider })
        assertEquals(List(4) { LocationConfig.TRANSPORT_DATACHANNEL }, paths.map { it.transport })
        assertEquals(List(4) { "a".repeat(64) }, paths.map { it.key })
    }

    @Test
    fun explicitMixedBondSurvivesNormalizationJsonAndLocationEntry() {
        val config = bondedConfig().copy(bondedRoomConfigs = mixedPaths())
        val decoded = Json.decodeFromString(
            LocationConfig.serializer(),
            Json.encodeToString(LocationConfig.serializer(), config)
        )
        val entry = LocationEntry.from("multi-12", decoded).normalized()

        assertTrue(entry.location.isComplete())
        assertEquals(mixedPaths(), entry.location.bondedPaths())
        assertEquals(
            List(4) { "jitsi" } + List(4) { "vkcalls" } + List(4) { "telemost" },
            entry.location.bondedPaths().map { it.provider }
        )
        assertEquals(
            List(4) { "datachannel" } + List(8) { "vp8channel" },
            entry.location.bondedPaths().map { it.transport }
        )
    }

    @Test
    fun explicitBondRejectsWrongSizesDuplicateRoomsAndBlankKeys() {
        assertFalse(bondedConfig().copy(bondedRoomConfigs = mixedPaths().take(3)).isComplete())
        assertFalse(bondedConfig().copy(bondedRoomConfigs = mixedPaths().take(5)).isComplete())
        assertFalse(
            bondedConfig().copy(bondedRoomConfigs = mixedPaths().take(4) + mixedPaths().first())
                .normalized().isComplete()
        )
        assertFalse(
            bondedConfig().copy(bondedRoomConfigs = mixedPaths().take(8) + mixedPaths().first())
                .normalized().isComplete()
        )
        assertFalse(
            bondedConfig().copy(
                bondedRoomConfigs = mixedPaths().mapIndexed { index, path ->
                    if (index == 11) mixedPaths()[10] else path
                }
            ).normalized().isComplete()
        )
        assertFalse(
            bondedConfig().copy(
                bondedRoomConfigs = mixedPaths().mapIndexed { index, path ->
                    if (index == 4) path.copy(key = " ") else path
                }
            ).isComplete()
        )
    }

    @Test
    fun eightAndSixteenRoomsFormAValidBond() {
        val eight = bondedConfig().copy(bondedRoomConfigs = mixedPaths().take(8))
        assertTrue(eight.isComplete())
        assertEquals(8, eight.bondedPaths().size)

        val sixteen = bondedConfig(rooms = (1..16).map { "room-$it" }).normalized()
        assertTrue(sixteen.isComplete())
        assertEquals(16, sixteen.bondedPaths().size)
    }

    @Test
    fun saluteJazzBondUsesOnlyDatachannel() {
        val paths = (1..4).map { index ->
            BondRoomConfig("room$index:pass${index}word", "d".repeat(64), "salutejazz", "datachannel")
        }
        val config = bondedConfig().copy(bondedRoomConfigs = paths)

        assertTrue(config.isComplete())
        assertEquals(List(4) { "salutejazz" }, config.bondedPaths().map { it.provider })
        assertEquals(List(4) { "datachannel" }, config.bondedPaths().map { it.transport })
        assertFalse(
            config.copy(bondedRoomConfigs = paths.map { it.copy(transport = "vp8channel") })
                .normalized().isComplete()
        )
    }

    @Test
    fun bondRequiresPlainTcpVless() {
        assertFalse(bondedConfig(link = VLESS_LINK.replace("security=none", "security=tls")).isComplete())
        assertFalse(bondedConfig(link = VLESS_LINK.replace("encryption=none", "type=ws&security=none")).isComplete())
    }

    private fun mixedPaths(): List<BondRoomConfig> {
        val mamba = (1..4).map { index ->
            BondRoomConfig("mamba-$index", "a".repeat(64), "jitsi", "datachannel")
        }
        val vk = (1..4).map { index ->
            BondRoomConfig("https://vk.ru/call/join/v$index", "b".repeat(64), "vkcalls", "vp8channel")
        }
        val telemost = (1..4).map { index ->
            BondRoomConfig("https://telemost.yandex.ru/j/t$index", "c".repeat(64), "telemost", "vp8channel")
        }
        return mamba + vk + telemost
    }

    private fun bondedConfig(
        rooms: List<String> = listOf("room-1", "room-2", "room-3", "room-4"),
        link: String? = VLESS_LINK,
    ) = LocationConfig(
        name = "Mamba x4",
        id = rooms.first(),
        key = "a".repeat(64),
        bypassProvider = LocationConfig.PROVIDER_JITSI,
        transport = LocationConfig.TRANSPORT_DATACHANNEL,
        failoverRoomIds = rooms.drop(1),
        bondVlessLink = link,
    )

    private companion object {
        const val VLESS_LINK = "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:10000" +
            "?security=none&encryption=none#Bond"
    }
}
