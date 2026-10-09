package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CarPlayOemLocalThemeTest {
    private val assets = File("src/main/assets/carplay-ultra")
    private fun deployed(test: (CarPlayOemThemeRuntime) -> Unit) {
        val root = Files.createTempDirectory("oem-local-theme").toFile()
        try { test(CarPlayOemAssetDeployment.prepare(root, { File(assets, it).inputStream() })) }
        finally { root.deleteRecursively() }
    }

    private fun CarPlayOemThemeDescriptor.layoutId(style: String) =
        layouts.first { it["layoutStyleID"] == style }["id"] as String

    @Test fun palettesComeFromAssetLayoutStylesWithDefaultFirst() = deployed { runtime ->
        val d = runtime.descriptor
        assertEquals(listOf("custom"), d.palettes(d.layoutId("Cobranded")))
        assertEquals(listOf("vibrant"), d.palettes(d.layoutId("MapsMedia")))
        val digiRing = d.palettes(d.layoutId("DigiRing"))
        assertEquals("green", digiRing.first())
        assertEquals(setOf("blue", "green", "midnight", "orange", "red", "rose", "starlight", "yellow"), digiRing.toSet())
        assertTrue("multicolor" in d.palettes(d.layoutId("Modular")))
        assertEquals(35, d.themeChoices().size)
        assertEquals(d.themeChoices().size, d.themeChoices().distinct().size)
    }

    @Test fun localThemeIsRequestedFromPhoneAndLockedOnceUiSyncConnects() = deployed { runtime ->
        val digiRing = runtime.descriptor.layoutId("DigiRing")
        assertTrue(runtime.selectLocalTheme(digiRing, "orange"))
        assertEquals(digiRing, runtime.scene.layoutId)
        assertEquals("orange", runtime.scene.paletteId)

        val data = runtime.initialCommands().first().toWireMap()["data"] as Map<*, *>
        assertEquals(digiRing, data["layoutID"])
        assertEquals("orange", data["paletteID"])
        assertEquals(runtime.descriptor.initialComponents(digiRing), data["activeComponents"])

        assertFalse(runtime.selectLocalTheme(runtime.descriptor.initialLayout, "custom"))
        assertEquals(digiRing, runtime.scene.layoutId)
    }

    @Test fun unknownPaletteIsRejectedWithoutChangingScene() = deployed { runtime ->
        val before = runtime.scene
        assertThrows(IllegalArgumentException::class.java) {
            runtime.selectLocalTheme(runtime.descriptor.initialLayout, "no-such-palette")
        }
        assertEquals(before, runtime.scene)
    }
}
