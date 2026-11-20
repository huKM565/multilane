package org.olcbox.app.net

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM-only: emits the Xray-core xhttp config from the real parser+builder to
 * `build/xray-configs/`. The core-verify CI workflow runs `xray test` on it
 * against the pinned Xray binary — proving the xhttp config is accepted by real
 * Xray (which, unlike sing-box, supports XHTTP).
 */
class XrayConfigDumpTest {
    private val outDir = File("build/xray-configs")

    private fun dump(name: String, json: String) {
        outDir.mkdirs()
        File(outDir, "$name.json").writeText(json)
    }

    @Test fun dumpVlessXhttp() {
        val link = "vless://22222222-2222-2222-2222-222222222222@127.0.0.1:443" +
            "?type=xhttp&security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
            "&sid=cd34&fp=chrome&sni=www.microsoft.com&path=%2Fdownload&host=www.microsoft.com&mode=packet-up#FI-xhttp"
        val spec = LinkParser.parse(link)
        assertNotNull(spec)
        assertTrue(spec is OutboundSpec.Vless)
        dump("vless-xhttp", XrayConfig.buildXhttp(spec))
    }

    /**
     * The iOS shapes behind a tun2socks, where Xray answers DNS itself: Global,
     * and Bypass Russia with the bundled lists inline. `xray -test` is what
     * says the inline rules parse and the resolvers, outbounds and rules refer
     * to each other correctly; the unit tests only know the shape.
     */
    @Test fun dumpVlessXhttpAnsweringDns() = runTest {
        val spec = LinkParser.parse(link) as OutboundSpec.Vless
        dump("vless-xhttp-answers-dns", XrayConfig.buildXhttp(spec, answersDns = true))
        dump(
            "vless-xhttp-bypass-russia",
            XrayConfig.buildXhttp(
                spec,
                routing = Routing.BypassRussia("unused-on-xray", DirectDns.Servers(listOf("77.88.8.8"))),
                geodata = XrayGeodata.lists(),
            )
        )
    }

    @Test fun dumpVlessMambaX4() {
        val spec = LinkParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:10000" +
                "?security=none&encryption=none#Mamba-x4"
        ) as OutboundSpec.Vless
        dump(
            "vless-mamba-x4",
            XrayConfig.buildVlessBond(
                spec,
                roomPorts = listOf(21081, 21082, 21083, 21084),
                roomUsername = "room-user",
                roomPassword = "room-pass",
            )
        )
        assertTrue(File(outDir, "vless-mamba-x4.json").exists())
    }

    @Test fun dumpVlessMultiX12() {
        val spec = LinkParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:10000" +
                "?security=none&encryption=none#Multi-x12"
        ) as OutboundSpec.Vless
        dump(
            "vless-multi-x12",
            XrayConfig.buildVlessBond(
                spec,
                roomPorts = (21081..21092).toList(),
                roomUsername = "room-user",
                roomPassword = "room-pass",
            )
        )
        assertTrue(File(outDir, "vless-multi-x12.json").exists())
    }

    @Test fun dumpVlessMultiX12Adaptive() {
        val spec = LinkParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:10000" +
                "?security=none&encryption=none#Multi-x12-adaptive"
        ) as OutboundSpec.Vless
        dump(
            "vless-multi-x12-adaptive",
            XrayConfig.buildVlessBond(
                spec,
                roomPorts = (21081..21092).toList(),
                roomUsername = "room-user",
                roomPassword = "room-pass",
                adaptive = BondScheduling(
                    expected = 4,
                    costs = listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 1.0, 1.25, 0.75, 2.25, 3.5),
                ),
            )
        )
        assertTrue(File(outDir, "vless-multi-x12-adaptive.json").exists())
    }

    private val link = "vless://22222222-2222-2222-2222-222222222222@127.0.0.1:443" +
        "?type=xhttp&security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
        "&sid=cd34&fp=chrome&sni=www.microsoft.com&path=%2Fdownload&host=www.microsoft.com&mode=packet-up#FI-xhttp"
}
