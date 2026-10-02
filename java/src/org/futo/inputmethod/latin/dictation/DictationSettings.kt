package org.futo.inputmethod.latin.dictation

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.futo.inputmethod.latin.uix.SettingsKey

/** Speechmatics `max_delay`, seconds. Allowed range 0.7–4.0. */
val DICTATION_MAX_DELAY = SettingsKey(floatPreferencesKey("dictation_max_delay"), 2.0f)
const val DICTATION_MAX_DELAY_MIN = 0.7f
const val DICTATION_MAX_DELAY_MAX = 4.0f

/** Continuous silence (no recognized speech) before the mic stops, seconds. */
val DICTATION_IDLE_TIMEOUT_S = SettingsKey(intPreferencesKey("dictation_idle_timeout_s"), 30)

/**
 * Seconds of silence that make the recognizer finalize early. 0 = off (default): a pause then
 * doesn't force a sentence end; words finalize via max_delay with the words that follow.
 */
val DICTATION_EOU_SILENCE_S = SettingsKey(floatPreferencesKey("dictation_eou_silence_s2"), 0.0f)

/** Speechmatics punctuation_overrides.sensitivity, 0..1. Lower = fewer punctuation marks. */
val DICTATION_PUNCT_SENSITIVITY = SettingsKey(floatPreferencesKey("dictation_punct_sensitivity"), 0.5f)

val DICTATION_CLEANUP_ENABLED = SettingsKey(booleanPreferencesKey("dictation_cleanup_enabled"), true)
val DICTATION_HOLD_TO_TALK = SettingsKey(booleanPreferencesKey("dictation_hold_to_talk"), false)
/** On by default until the navigation-bar mic is confirmed to reach this keyboard (Phase 4). */
val DICTATION_FALLBACK_MIC_KEY = SettingsKey(booleanPreferencesKey("dictation_fallback_mic_key"), true)
/** Target language for the Translate action (a language name; the last choice is remembered). */
val DICTATION_TRANSLATE_LANG = SettingsKey(stringPreferencesKey("dictation_translate_language"), "Spanish")
val DICTATION_DEBUG_LOGGING = SettingsKey(booleanPreferencesKey("dictation_debug_logging"), false)
val DICTATION_SAVE_AUDIO = SettingsKey(booleanPreferencesKey("dictation_save_audio"), false)
val DICTATION_LANGUAGE = SettingsKey(stringPreferencesKey("dictation_language"), "en")

/** Speechmatics real-time endpoint. Overridable for region/self-hosted. */
val DICTATION_STT_URL = SettingsKey(
    stringPreferencesKey("dictation_stt_url"),
    "wss://eu.rt.speechmatics.com/v2/"
)

/** Records which One UI voice-input path fired last (RecognitionService / voice subtype / keyboard). */
val DICTATION_LAST_TRIGGER_PATH = SettingsKey(stringPreferencesKey("dictation_last_trigger_path"), "none")

val DICTATION_VOCAB_SEED = listOf(
    "Terra",
    "The Mower Medic",
    "carburetor",
    "stator",
    "deck spindle",
    "flywheel key",
    "ignition coil",
    "governor",
    "primer bulb",
    "Briggs",
    "Kohler",
    "Kawasaki",
    "Husqvarna",
    "Toro",
    "Exmark",
)

/** Newline-separated custom vocabulary. */
val DICTATION_VOCAB = SettingsKey(
    stringPreferencesKey("dictation_vocab"),
    DICTATION_VOCAB_SEED.joinToString("\n")
)

/** Spoken word → replacement, applied to transcripts (whole-word, case-insensitive). */
val DICTATION_SPOKEN_REPLACEMENTS = mapOf(
    "lol" to "😂",
)

private val spokenReplacementRegex = Regex(
    "(?<![\\p{L}\\p{N}])(" + DICTATION_SPOKEN_REPLACEMENTS.keys.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])[.,]?",
    RegexOption.IGNORE_CASE
)

fun String.applySpokenReplacements(): String =
    spokenReplacementRegex.replace(this) { m -> DICTATION_SPOKEN_REPLACEMENTS[m.groupValues[1].lowercase()] ?: m.value }

fun String.toVocabList(): List<String> =
    lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
