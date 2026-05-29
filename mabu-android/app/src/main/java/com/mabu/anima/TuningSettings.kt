package com.mabu.anima

import android.content.SharedPreferences

/**
 * Runtime-tunable parameters surfaced in the on-screen settings panel.
 * Read by the main loop on every tick, so changes apply immediately.
 * Persisted to SharedPreferences so they survive app restart.
 */
class TuningSettings {

    // Gaze (FOLLOW mode)
    var gazeGain        = 1.0f
    var gazeYOffset     = 0.10f
    var emaAlpha        = 0.45f

    // Motor tween
    var smoothAlphaEyes = 0.30f
    var smoothAlphaNeck = 0.12f

    // Saccades
    var saccadeAmplitude   = 0.05f
    var saccadeIntervalSec = 1.8f

    // Glances
    var glanceIntervalSec  = 13f

    // Blinks
    var blinkIntervalSec   = 5f
    var doubleBlinkChance  = 0.15f

    // Puppet
    var neckAngleRange = 30f
    var neckRotSign    = -1f
    var neckElevSign   =  1f
    var neckTiltSign   =  1f
    /** How much the neck moves with the eyes in FOLLOW mode. 0 = head fixed,
     *  eyes only; 1 = neck swings as much as eyes. ~0.4 looks natural. */
    var neckFollowGain = 0.4f
    // Gain applied to the pupil-offset gaze vector (PUPPET mode eyes).
    // Pupils rarely swing to ±1 of the half-box; gain of ~1.5 maps a
    // half-swing to roughly the full motor range.
    var eyeGazeGain    = 1.5f
    // When true, PUPPET eyes follow pupil direction (real gaze). When
    // false, they follow head pose (the old behavior).
    var useEyeGaze     = true

    // Behavior à la carte -- modes apply preset combinations of these, but
    // individual flags can be flipped after.
    /** "spontaneous", "mirror", "both", or "none". */
    var blinkMethod    = "spontaneous"
    var enableSaccades = true
    var enableGlances  = true
    /**
     * 0 = each eyelid follows its own eye-open probability (independent;
     * fine winks but false-positive single-eye blinks slip through).
     * 1 = both eyelids follow the brighter (more-open) eye, fully linked.
     * Intermediate values let the clearer eye drag the noisier one up.
     */
    var eyelidCoupling = 0.5f

    /** Pico TTS ignores its own volume param so we set STREAM_MUSIC directly.
     *  On Mabu's speaker 0.2-0.3 is comfortable. Physical volume buttons on
     *  the tablet also adjust STREAM_MUSIC live between slider moves. */
    var ttsVolume = 0.22f

    /** "local" = LlamaInference on-device; "streaming" = llama-server on LAN. */
    var cognitionMode = "streaming"
    var llmServerUrl  = "http://10.0.0.49:8080"
    /** WhisperLive WebSocket ASR server (streaming mode). Vosk is the local
     *  fallback when cognitionMode != "streaming". */
    var asrServerUrl  = "ws://10.0.0.49:9090"
    /** Chatterbox TTS server (streaming mode). Pico is the local fallback. */
    var ttsServerUrl  = "http://10.0.0.49:8123"

    fun load(prefs: SharedPreferences) {
        gazeGain           = prefs.getFloat("gazeGain",           gazeGain)
        gazeYOffset        = prefs.getFloat("gazeYOffset",        gazeYOffset)
        emaAlpha           = prefs.getFloat("emaAlpha",           emaAlpha)
        smoothAlphaEyes    = prefs.getFloat("smoothAlphaEyes",    smoothAlphaEyes)
        smoothAlphaNeck    = prefs.getFloat("smoothAlphaNeck",    smoothAlphaNeck)
        saccadeAmplitude   = prefs.getFloat("saccadeAmplitude",   saccadeAmplitude)
        saccadeIntervalSec = prefs.getFloat("saccadeIntervalSec", saccadeIntervalSec)
        glanceIntervalSec  = prefs.getFloat("glanceIntervalSec",  glanceIntervalSec)
        blinkIntervalSec   = prefs.getFloat("blinkIntervalSec",   blinkIntervalSec)
        doubleBlinkChance  = prefs.getFloat("doubleBlinkChance",  doubleBlinkChance)
        neckAngleRange     = prefs.getFloat("neckAngleRange",     neckAngleRange)
        neckRotSign        = prefs.getFloat("neckRotSign",        neckRotSign)
        neckElevSign       = prefs.getFloat("neckElevSign",       neckElevSign)
        neckTiltSign       = prefs.getFloat("neckTiltSign",       neckTiltSign)
        eyeGazeGain        = prefs.getFloat("eyeGazeGain",        eyeGazeGain)
        useEyeGaze         = prefs.getBoolean("useEyeGaze",       useEyeGaze)
        blinkMethod        = prefs.getString("blinkMethod",       blinkMethod) ?: blinkMethod
        enableSaccades     = prefs.getBoolean("enableSaccades",   enableSaccades)
        enableGlances      = prefs.getBoolean("enableGlances",    enableGlances)
        eyelidCoupling     = prefs.getFloat("eyelidCoupling",     eyelidCoupling)
        ttsVolume          = prefs.getFloat("ttsVolume",          ttsVolume)
        neckFollowGain     = prefs.getFloat("neckFollowGain",     neckFollowGain)
        cognitionMode      = prefs.getString("cognitionMode",     cognitionMode) ?: cognitionMode
        llmServerUrl       = prefs.getString("llmServerUrl",      llmServerUrl)  ?: llmServerUrl
        asrServerUrl       = prefs.getString("asrServerUrl",      asrServerUrl)  ?: asrServerUrl
        ttsServerUrl       = prefs.getString("ttsServerUrl",      ttsServerUrl)  ?: ttsServerUrl
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().apply {
            putFloat("gazeGain",           gazeGain)
            putFloat("gazeYOffset",        gazeYOffset)
            putFloat("emaAlpha",           emaAlpha)
            putFloat("smoothAlphaEyes",    smoothAlphaEyes)
            putFloat("smoothAlphaNeck",    smoothAlphaNeck)
            putFloat("saccadeAmplitude",   saccadeAmplitude)
            putFloat("saccadeIntervalSec", saccadeIntervalSec)
            putFloat("glanceIntervalSec",  glanceIntervalSec)
            putFloat("blinkIntervalSec",   blinkIntervalSec)
            putFloat("doubleBlinkChance",  doubleBlinkChance)
            putFloat("neckAngleRange",     neckAngleRange)
            putFloat("neckRotSign",        neckRotSign)
            putFloat("neckElevSign",       neckElevSign)
            putFloat("neckTiltSign",       neckTiltSign)
            putFloat("eyeGazeGain",        eyeGazeGain)
            putBoolean("useEyeGaze",       useEyeGaze)
            putString("blinkMethod",       blinkMethod)
            putBoolean("enableSaccades",   enableSaccades)
            putBoolean("enableGlances",    enableGlances)
            putFloat("eyelidCoupling",     eyelidCoupling)
            putFloat("ttsVolume",          ttsVolume)
            putFloat("neckFollowGain",     neckFollowGain)
            putString("cognitionMode",     cognitionMode)
            putString("llmServerUrl",      llmServerUrl)
            putString("asrServerUrl",      asrServerUrl)
            putString("ttsServerUrl",      ttsServerUrl)
            apply()
        }
    }

    /**
     * Reset behavioral tuning only. Preserves the hardware calibration
     * values -- Y offset for the tablet's physical mount and the three
     * motor sign flips that track per-unit motor wiring -- since those
     * are properties of the install, not user preferences.
     */
    fun reset() {
        gazeGain           = 1.0f
        emaAlpha           = 0.45f
        smoothAlphaEyes    = 0.30f
        smoothAlphaNeck    = 0.12f
        saccadeAmplitude   = 0.05f
        saccadeIntervalSec = 1.8f
        glanceIntervalSec  = 13f
        blinkIntervalSec   = 5f
        doubleBlinkChance  = 0.15f
        neckAngleRange     = 30f
        eyeGazeGain        = 1.5f
        useEyeGaze         = true
        blinkMethod        = "spontaneous"
        enableSaccades     = true
        enableGlances      = true
        eyelidCoupling     = 0.5f
        ttsVolume          = 0.22f
        neckFollowGain     = 0.4f
        cognitionMode      = "streaming"
        llmServerUrl       = "http://10.0.0.49:8080"
        asrServerUrl       = "ws://10.0.0.49:9090"
        ttsServerUrl       = "http://10.0.0.49:8123"
    }

    /** Nuclear reset: blow away calibration too. Use only when re-installing. */
    fun resetAll() {
        reset()
        gazeYOffset        = 0.10f
        neckRotSign        = -1f
        neckElevSign       =  1f
        neckTiltSign       =  1f
    }
}
