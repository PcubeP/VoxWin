package dev.voxwin.app

/**
 * Pure, Android-free mapping from a recognized voice phrase to a [Command].
 *
 * Returns null when the phrase is not recognized, so the UI can show a
 * "didn't catch that" hint and send nothing. Fully unit-testable.
 */
object CommandParser {

    /**
     * Parse [raw] into a [Command], or null if unrecognized.
     *
     * Matching is case-insensitive and whitespace-trimmed. We keep two views of
     * the input: [trimmed] (original casing, for payloads like typed text) and
     * [phrase] (lower-cased, internal whitespace collapsed, for verb matching).
     * Because lower-casing preserves length for the ASCII command words we match
     * on, `trimmed.substring(prefix.length)` recovers the original-cased payload
     * safely — regardless of how much whitespace the recognizer inserted.
     */
    fun parse(raw: String): Command? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        val phrase = lower.replace(Regex("\\s+"), " ")

        // Exact-match verbs first.
        when (phrase) {
            "volume up", "louder" -> return Command.of(Actions.VOLUME_UP)
            "volume down", "quieter" -> return Command.of(Actions.VOLUME_DOWN)
            "mute", "unmute" -> return Command.of(Actions.MUTE)
            "play", "pause", "play pause" -> return Command.of(Actions.MEDIA_PLAY_PAUSE)
            "next", "next track", "skip" -> return Command.of(Actions.MEDIA_NEXT)
            "previous", "back", "last track" -> return Command.of(Actions.MEDIA_PREV)
            "scroll up" -> return Command.of(Actions.SCROLL, mapOf("direction" to "up", "amount" to 3))
            "scroll down" -> return Command.of(Actions.SCROLL, mapOf("direction" to "down", "amount" to 3))
            "lock", "lock computer", "lock the computer" -> return Command.of(Actions.LOCK)
        }

        // Prefix verbs. Detect on `lower`, slice the payload from `trimmed`.
        for (prefix in listOf("open ", "launch ", "start ")) {
            if (lower.startsWith(prefix)) {
                // App targets are case-insensitive (Windows + the daemon lower-case them),
                // so normalize them; collapse any internal whitespace too.
                val app = trimmed.substring(prefix.length).trim()
                    .lowercase().replace(Regex("\\s+"), " ")
                if (app.isNotEmpty()) return Command.of(Actions.LAUNCH, mapOf("app" to app))
            }
        }

        if (lower.startsWith("type ")) {
            // Preserve the original casing of the spoken text payload verbatim.
            val text = trimmed.substring("type ".length).trim()
            if (text.isNotEmpty()) return Command.of(Actions.TYPE_TEXT, mapOf("text" to text))
        }

        if (lower.startsWith("press ")) {
            val combo = normalizeCombo(trimmed.substring("press ".length).trim().lowercase())
            if (combo.isNotEmpty()) return Command.of(Actions.KEY, mapOf("combo" to combo))
        }

        return null
    }

    /**
     * Normalize a spoken key combo: spaces become '+', spoken modifier words
     * are mapped to their canonical token. e.g. "control c" -> "ctrl+c",
     * "control shift escape" -> "ctrl+shift+esc".
     */
    private fun normalizeCombo(combo: String): String {
        val tokens = combo.trim().split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString("+") { tok ->
            when (tok) {
                "control", "ctrl" -> "ctrl"
                "alt", "option" -> "alt"
                "shift" -> "shift"
                "win", "windows", "super", "meta" -> "win"
                "escape", "esc" -> "esc"
                "enter", "return" -> "enter"
                "delete", "del" -> "del"
                "tab" -> "tab"
                "space", "spacebar" -> "space"
                else -> tok
            }
        }
    }
}
