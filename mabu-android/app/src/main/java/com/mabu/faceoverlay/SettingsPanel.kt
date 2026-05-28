package com.mabu.faceoverlay

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Programmatic side panel of sliders + toggles for live tuning. Wraps the
 * shared TuningSettings so changes apply immediately to the running app.
 * Visibility toggled by the gear button in MainActivity.
 */
@Suppress("DEPRECATION") // Switch is fine on minSdk 24
class SettingsPanel(
    context: Context,
    private val tuning: TuningSettings,
    private val onChanged: () -> Unit,
    private val onCalibrate: () -> Unit,
    private val onModeSelected: (Mode) -> Unit,
    private val currentMode: () -> Mode
) : FrameLayout(context) {

    private val container: LinearLayout
    private val modeButtons = mutableMapOf<Mode, Button>()

    init {
        setBackgroundColor(Color.argb(220, 12, 12, 18))
        visibility = View.GONE

        val scroll = ScrollView(context)
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }
        scroll.addView(container)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        build()
    }

    private fun build() {
        section("Mode")
        buildModeRow()

        section("Gaze")
        slider("Gaze gain",          0f,    2f,   { tuning.gazeGain }       , { tuning.gazeGain = it })
        slider("Gaze Y offset",     -0.3f,  0.3f, { tuning.gazeYOffset }    , { tuning.gazeYOffset = it })
        slider("Detection smoothing (EMA)", 0.1f, 0.9f, { tuning.emaAlpha } , { tuning.emaAlpha = it })

        section("Motor tween")
        slider("Eye smoothness",     0.05f, 0.6f, { tuning.smoothAlphaEyes }, { tuning.smoothAlphaEyes = it })
        slider("Neck smoothness",    0.04f, 0.4f, { tuning.smoothAlphaNeck }, { tuning.smoothAlphaNeck = it })

        section("Behaviors (à la carte)")
        boolRow("Saccades",  { tuning.enableSaccades }, { tuning.enableSaccades = it })
        boolRow("Glances",   { tuning.enableGlances },  { tuning.enableGlances = it })
        stringRadio("Blink method",
            listOf("spontaneous", "mirror", "both", "none"),
            { tuning.blinkMethod }, { tuning.blinkMethod = it })
        slider("Eyelid coupling (L<->R link)", 0f, 1f,
            { tuning.eyelidCoupling }, { tuning.eyelidCoupling = it })

        section("Lifelike tuning")
        slider("Saccade amplitude",  0f,    0.2f, { tuning.saccadeAmplitude }, { tuning.saccadeAmplitude = it })
        slider("Saccade interval (s)", 0.5f, 6f,  { tuning.saccadeIntervalSec }, { tuning.saccadeIntervalSec = it })
        slider("Glance interval (s)", 3f,  30f,   { tuning.glanceIntervalSec }, { tuning.glanceIntervalSec = it })
        slider("Blink interval (s)",  1f,  12f,   { tuning.blinkIntervalSec }, { tuning.blinkIntervalSec = it })
        slider("Double-blink chance", 0f,   0.5f, { tuning.doubleBlinkChance }, { tuning.doubleBlinkChance = it })

        section("Puppet")
        slider("Neck angle range (deg)", 10f, 60f, { tuning.neckAngleRange }, { tuning.neckAngleRange = it })
        signRow("Neck rot sign",  { tuning.neckRotSign },  { tuning.neckRotSign = it })
        signRow("Neck elev sign", { tuning.neckElevSign }, { tuning.neckElevSign = it })
        signRow("Neck tilt sign", { tuning.neckTiltSign }, { tuning.neckTiltSign = it })
        slider("Eye gaze gain (pupil)", 0.3f, 4f, { tuning.eyeGazeGain }, { tuning.eyeGazeGain = it })
        boolRow("Eyes follow pupil (vs head)", { tuning.useEyeGaze }, { tuning.useEyeGaze = it })

        section("Actions")
        button("Calibrate center (look at camera)") {
            onCalibrate()
        }
        button("Reset tuning (keep calibration)") {
            tuning.reset()
            container.removeAllViews()
            build()
            onChanged()
        }
        button("Reset all (incl. calibration)") {
            tuning.resetAll()
            container.removeAllViews()
            build()
            onChanged()
        }
        button("Close") {
            visibility = View.GONE
        }
    }

    private fun buildModeRow() {
        modeButtons.clear()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
        }
        for (m in Mode.values()) {
            val b = Button(context).apply {
                text = m.name
                textSize = 11f
                setOnClickListener {
                    onModeSelected(m)
                    refreshModeButtons()
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            modeButtons[m] = b
            row.addView(b)
        }
        container.addView(row)
        refreshModeButtons()
    }

    /**
     * Rebuild the panel content. Use after a mode preset writes flags so
     * the UI (radios, switches, sliders) reflects the new values.
     */
    fun rebuildAfterPreset() {
        if (visibility != View.VISIBLE) return
        container.removeAllViews()
        build()
    }

    /** Highlight the active mode button. Call after mode changes externally. */
    fun refreshModeButtons() {
        val active = currentMode()
        for ((m, b) in modeButtons) {
            if (m == active) {
                b.setBackgroundColor(Color.argb(255, 70, 140, 200))
                b.setTextColor(Color.WHITE)
            } else {
                b.setBackgroundColor(Color.argb(255, 40, 40, 50))
                b.setTextColor(Color.LTGRAY)
            }
        }
    }

    private fun section(title: String) {
        container.addView(TextView(context).apply {
            text = title
            setTextColor(Color.argb(255, 120, 220, 255))
            textSize = 17f
            setPadding(0, 30, 0, 8)
        })
    }

    private fun slider(
        label: String, min: Float, max: Float,
        get: () -> Float, set: (Float) -> Unit
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val labelView = TextView(context).apply {
            text = label
            setTextColor(Color.LTGRAY)
            textSize = 13f
        }
        val valueView = TextView(context).apply {
            text = "%.3f".format(get())
            setTextColor(Color.YELLOW)
            textSize = 13f
        }
        val seek = SeekBar(context).apply {
            this.max = 1000
            progress = ((get() - min) / (max - min) * 1000f).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + (p / 1000f) * (max - min)
                    set(v)
                    valueView.text = "%.3f".format(v)
                    if (fromUser) onChanged()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        row.addView(labelView)
        row.addView(valueView)
        row.addView(seek)
        container.addView(row)
    }

    private fun stringRadio(
        label: String, options: List<String>,
        get: () -> String, set: (String) -> Unit
    ) {
        container.addView(TextView(context).apply {
            text = label
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 12, 0, 4)
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        val buttons = mutableMapOf<String, Button>()
        for (opt in options) {
            val b = Button(context).apply {
                text = opt
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener {
                    set(opt)
                    onChanged()
                    buttons.values.forEach {
                        it.setBackgroundColor(Color.argb(255, 40, 40, 50))
                        it.setTextColor(Color.LTGRAY)
                    }
                    setBackgroundColor(Color.argb(255, 70, 140, 200))
                    setTextColor(Color.WHITE)
                }
            }
            buttons[opt] = b
            row.addView(b)
        }
        container.addView(row)
        // Highlight current
        buttons[get()]?.let {
            it.setBackgroundColor(Color.argb(255, 70, 140, 200))
            it.setTextColor(Color.WHITE)
        }
    }

    private fun boolRow(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.LTGRAY)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        val sw = Switch(context).apply {
            isChecked = get()
            setOnCheckedChangeListener { _, checked ->
                set(checked)
                onChanged()
            }
        }
        row.addView(sw)
        container.addView(row)
    }

    private fun signRow(label: String, get: () -> Float, set: (Float) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.LTGRAY)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        val sw = Switch(context).apply {
            isChecked = get() > 0f
            text = if (isChecked) "+" else "-"
            setOnCheckedChangeListener { _, checked ->
                set(if (checked) 1f else -1f)
                text = if (checked) "+" else "-"
                onChanged()
            }
        }
        row.addView(sw)
        container.addView(row)
    }

    private fun button(label: String, onClick: () -> Unit) {
        container.addView(Button(context).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    fun toggle() {
        visibility = if (visibility == View.GONE) View.VISIBLE else View.GONE
    }
}
