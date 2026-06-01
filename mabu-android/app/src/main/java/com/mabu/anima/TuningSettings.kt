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
    // PUPPET eye-gaze noise control (the pupil heuristic is noisy). InputAlpha
    // low-passes the raw pupil offset; Deadband holds the eye target until a
    // real gaze shift (fixation hysteresis). Raise either to kill jitter; too
    // high makes saccades laggy/coarse.
    var eyeGazeInputAlpha = 0.35f
    var eyeGazeDeadband   = 0.06f

    // Behavior à la carte -- modes apply preset combinations of these, but
    // individual flags can be flipped after.
    /** "spontaneous", "mirror", "both", or "none". */
    var blinkMethod    = "spontaneous"
    var enableSaccades = true
    var enableGlances  = true
    /**
     * Blink coupling strength when BOTH eyes are engaged (neither is clearly
     * open). 0 = each eyelid fully independent (a blink where one eye's prob
     * lags reads as a one-eyed wink). 1 = both eyelids driven by the more-
     * closed eye, so a real two-eye blink always closes both. ~0.8 fixes the
     * "only one eye blinks" problem while still allowing deliberate winks
     * (a clearly-open eye, prob > eyelidWinkOpen, is never coupled).
     */
    var eyelidCoupling = 0.8f
    /** An eye with open-prob above this is treated as deliberately open, so a
     *  wink (one eye open, one shut) stays independent and isn't coupled into
     *  a both-eyes blink. Below it, a dropping eye couples to its partner. */
    var eyelidWinkOpen = 0.80f
    /** Open-prob below this latches a full closure for eyelidBlinkHoldMs, so a
     *  fast blink caught in one ~10 fps frame still renders fully closed. */
    var eyelidCloseLevel = 0.30f
    var eyelidBlinkHoldMs = 120
    /** Low-pass on eye openness for smooth PARTIAL closure (squint). Separate
     *  from the blink latch, which bypasses it for crisp full blinks. */
    var eyelidOpenInputAlpha = 0.45f
    /** Head-pose reliability gate. As |yaw| or |pitch| grows past PoseSoftDeg
     *  the far eye shrinks/occludes and ML Kit's eye-open prob falsely drops
     *  (reads as closing), so the lids are biased back toward open -- fully so
     *  by PoseLimitDeg. Stops the "winks when looking away" artifact. */
    var eyelidPoseSoftDeg  = 15f
    var eyelidPoseLimitDeg = 32f

    /** Pico TTS ignores its own volume param so we set STREAM_MUSIC directly.
     *  On Mabu's speaker 0.2-0.3 is comfortable. Physical volume buttons on
     *  the tablet also adjust STREAM_MUSIC live between slider moves. */
    var ttsVolume = 0.22f

    /**
     * "local"     = LlamaInference + Vosk on-device.
     * "streaming" = llama-server (SSE) + WhisperLive (WS) + Chatterbox (HTTP),
     *               orchestrated device-side (RemoteAsr/RemoteTts/StreamingLlama).
     * "pipecat"   = single WebRTC session to the PC Pipecat pipeline
     *               (pc-brain/pipecat_bot.py); the SDK owns mic/speaker/AEC/turn
     *               -taking. Uses pipecatOfferUrl; the three URLs below are unused.
     */
    var cognitionMode = "streaming"
    var llmServerUrl  = "http://10.0.0.49:8080"
    /** WhisperLive WebSocket ASR server (streaming mode). Vosk is the local
     *  fallback when cognitionMode != "streaming". */
    var asrServerUrl  = "ws://10.0.0.49:9090"
    /** Chatterbox TTS server (streaming mode). Pico is the local fallback. */
    var ttsServerUrl  = "http://10.0.0.49:8123"
    /** Pipecat SmallWebRTC offer endpoint (pipecat mode). */
    var pipecatOfferUrl = "http://10.0.0.49:7860/api/offer"

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
        eyeGazeInputAlpha  = prefs.getFloat("eyeGazeInputAlpha",  eyeGazeInputAlpha)
        eyeGazeDeadband    = prefs.getFloat("eyeGazeDeadband",    eyeGazeDeadband)
        blinkMethod        = prefs.getString("blinkMethod",       blinkMethod) ?: blinkMethod
        enableSaccades     = prefs.getBoolean("enableSaccades",   enableSaccades)
        enableGlances      = prefs.getBoolean("enableGlances",    enableGlances)
        eyelidCoupling     = prefs.getFloat("eyelidCoupling",     eyelidCoupling)
        eyelidWinkOpen     = prefs.getFloat("eyelidWinkOpen",     eyelidWinkOpen)
        eyelidCloseLevel   = prefs.getFloat("eyelidCloseLevel",   eyelidCloseLevel)
        eyelidBlinkHoldMs  = prefs.getInt("eyelidBlinkHoldMs",    eyelidBlinkHoldMs)
        eyelidOpenInputAlpha = prefs.getFloat("eyelidOpenInputAlpha", eyelidOpenInputAlpha)
        eyelidPoseSoftDeg  = prefs.getFloat("eyelidPoseSoftDeg",  eyelidPoseSoftDeg)
        eyelidPoseLimitDeg = prefs.getFloat("eyelidPoseLimitDeg", eyelidPoseLimitDeg)
        ttsVolume          = prefs.getFloat("ttsVolume",          ttsVolume)
        neckFollowGain     = prefs.getFloat("neckFollowGain",     neckFollowGain)
        cognitionMode      = prefs.getString("cognitionMode",     cognitionMode) ?: cognitionMode
        llmServerUrl       = prefs.getString("llmServerUrl",      llmServerUrl)  ?: llmServerUrl
        asrServerUrl       = prefs.getString("asrServerUrl",      asrServerUrl)  ?: asrServerUrl
        ttsServerUrl       = prefs.getString("ttsServerUrl",      ttsServerUrl)  ?: ttsServerUrl
        pipecatOfferUrl    = prefs.getString("pipecatOfferUrl",   pipecatOfferUrl) ?: pipecatOfferUrl
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
            putFloat("eyeGazeInputAlpha",  eyeGazeInputAlpha)
            putFloat("eyeGazeDeadband",    eyeGazeDeadband)
            putString("blinkMethod",       blinkMethod)
            putBoolean("enableSaccades",   enableSaccades)
            putBoolean("enableGlances",    enableGlances)
            putFloat("eyelidCoupling",     eyelidCoupling)
            putFloat("eyelidWinkOpen",     eyelidWinkOpen)
            putFloat("eyelidCloseLevel",   eyelidCloseLevel)
            putInt("eyelidBlinkHoldMs",    eyelidBlinkHoldMs)
            putFloat("eyelidOpenInputAlpha", eyelidOpenInputAlpha)
            putFloat("eyelidPoseSoftDeg",  eyelidPoseSoftDeg)
            putFloat("eyelidPoseLimitDeg", eyelidPoseLimitDeg)
            putFloat("ttsVolume",          ttsVolume)
            putFloat("neckFollowGain",     neckFollowGain)
            putString("cognitionMode",     cognitionMode)
            putString("llmServerUrl",      llmServerUrl)
            putString("asrServerUrl",      asrServerUrl)
            putString("ttsServerUrl",      ttsServerUrl)
            putString("pipecatOfferUrl",   pipecatOfferUrl)
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
        eyeGazeInputAlpha  = 0.35f
        eyeGazeDeadband    = 0.06f
        blinkMethod        = "spontaneous"
        enableSaccades     = true
        enableGlances      = true
        eyelidCoupling     = 0.8f
        eyelidWinkOpen     = 0.80f
        eyelidCloseLevel   = 0.30f
        eyelidBlinkHoldMs  = 120
        eyelidOpenInputAlpha = 0.45f
        eyelidPoseSoftDeg  = 15f
        eyelidPoseLimitDeg = 32f
        ttsVolume          = 0.22f
        neckFollowGain     = 0.4f
        // NOTE: cognitionMode intentionally NOT reset. Resetting it to
        // "streaming" silently put users back on the legacy RemoteAsr/RemoteTts
        // path (which historically had the "Mabu" hotword bias and weaker AEC)
        // when they hit "Reset tuning" -- and they wouldn't see it happen.
        // It's a stack choice, not a behavioral knob, so it stays where the
        // user put it. The dedicated [resetAll] is still the nuclear option.
        llmServerUrl       = "http://10.0.0.49:8080"
        asrServerUrl       = "ws://10.0.0.49:9090"
        ttsServerUrl       = "http://10.0.0.49:8123"
        pipecatOfferUrl    = "http://10.0.0.49:7860/api/offer"
    }

    /** Nuclear reset: blow away calibration too. Use only when re-installing. */
    fun resetAll() {
        reset()
        // resetAll is the "factory wipe" option, so it *does* reset the stack
        // choice. New default is "pipecat" (better AEC + VAD + SmartTurn);
        // historically this was "streaming" but that path's hotword bias hurts
        // more than it helps, and pipecat is the preferred path now.
        cognitionMode      = "pipecat"
        gazeYOffset        = 0.10f
        neckRotSign        = -1f
        neckElevSign       =  1f
        neckTiltSign       =  1f
    }
}
