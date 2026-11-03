package dev.voxwin.app

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the pure voice-phrase -> Command mapping. */
class CommandParserTest {

    private fun arg(c: Command, key: String): String? =
        (c.args[key] as? JsonPrimitive)?.content

    @Test
    fun volumeUp() {
        assertEquals(Actions.VOLUME_UP, CommandParser.parse("volume up")?.action)
        assertEquals(Actions.VOLUME_UP, CommandParser.parse("  Louder ")?.action)
    }

    @Test
    fun volumeDown() {
        assertEquals(Actions.VOLUME_DOWN, CommandParser.parse("Volume Down")?.action)
        assertEquals(Actions.VOLUME_DOWN, CommandParser.parse("quieter")?.action)
    }

    @Test
    fun muteToggles() {
        assertEquals(Actions.MUTE, CommandParser.parse("mute")?.action)
        assertEquals(Actions.MUTE, CommandParser.parse("unmute")?.action)
    }

    @Test
    fun mediaControls() {
        assertEquals(Actions.MEDIA_PLAY_PAUSE, CommandParser.parse("play pause")?.action)
        assertEquals(Actions.MEDIA_NEXT, CommandParser.parse("skip")?.action)
        assertEquals(Actions.MEDIA_PREV, CommandParser.parse("last track")?.action)
    }

    @Test
    fun openNotepad() {
        val c = CommandParser.parse("open notepad")
        assertEquals(Actions.LAUNCH, c?.action)
        assertEquals("notepad", c?.let { arg(it, "app") })
    }

    @Test
    fun launchRawTarget() {
        val c = CommandParser.parse("launch spotify")
        assertEquals(Actions.LAUNCH, c?.action)
        assertEquals("spotify", c?.let { arg(it, "app") })
    }

    @Test
    fun typeTextPreservesCasing() {
        val c = CommandParser.parse("type Hello World")
        assertEquals(Actions.TYPE_TEXT, c?.action)
        assertEquals("Hello World", c?.let { arg(it, "text") })
    }

    @Test
    fun typeLowercase() {
        val c = CommandParser.parse("type hello world")
        assertEquals("hello world", c?.let { arg(it, "text") })
    }

    @Test
    fun typeTextHandlesCapitalVerbAndSurroundingSpace() {
        // The verb may come back capitalized and padded; the payload casing must survive.
        assertEquals("Hello World", CommandParser.parse("  Type Hello World  ")?.let { arg(it, "text") })
        assertEquals("Buy milk, eggs", CommandParser.parse("type Buy milk, eggs")?.let { arg(it, "text") })
    }

    @Test
    fun pressComboNormalized() {
        val c = CommandParser.parse("press control c")
        assertEquals(Actions.KEY, c?.action)
        assertEquals("ctrl+c", c?.let { arg(it, "combo") })

        val c2 = CommandParser.parse("press control shift escape")
        assertEquals("ctrl+shift+esc", c2?.let { arg(it, "combo") })
    }

    @Test
    fun scroll() {
        val up = CommandParser.parse("scroll up")
        assertEquals(Actions.SCROLL, up?.action)
        assertEquals("up", up?.let { arg(it, "direction") })
        assertEquals("3", up?.let { arg(it, "amount") })

        assertEquals("down", CommandParser.parse("scroll down")?.let { arg(it, "direction") })
    }

    @Test
    fun lockVariants() {
        assertEquals(Actions.LOCK, CommandParser.parse("lock")?.action)
        assertEquals(Actions.LOCK, CommandParser.parse("lock computer")?.action)
        assertEquals(Actions.LOCK, CommandParser.parse("lock the computer")?.action)
    }

    @Test
    fun gibberishReturnsNull() {
        assertNull(CommandParser.parse("flibber zap"))
        assertNull(CommandParser.parse(""))
        assertNull(CommandParser.parse("   "))
    }
}
