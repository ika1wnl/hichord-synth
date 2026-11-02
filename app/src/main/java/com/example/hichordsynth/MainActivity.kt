package com.example.hichordsynth

import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.random.Random

// --- TEENAGE ENGINEERING / HICHORD FIELD PALETTE ---
val TeChassis = Color(0xFFD8D6D0)
val TePanelDark = Color(0xFF151515)
val TeKeyDark = Color(0xFF262626)
val TeKeyDarkShadow = Color(0xFF0E0E0E)
val TeKeyLight = Color(0xFFF4F3EF)
val TeKeyLightShadow = Color(0xFFA6A49E)
val TeOrange = Color(0xFFFF4F00)
val TeOrangeShadow = Color(0xFFB33600)
val TeOchre = Color(0xFFE5A93C)
val TeBlue = Color(0xFF1473E6)
val TeBlueShadow = Color(0xFF0B4387)
val TeLedRed = Color(0xFFFF2A2A)
val TeLedOff = Color(0xFF4A4946)

class MainActivity : ComponentActivity() {
    private val synth = HiChordProEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TeHiChordApp(synth)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        synth.start()
    }

    override fun onPause() {
        super.onPause()
        synth.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        synth.stop()
    }
}

// --- STUDIO ENGINE MODELLE ---
// 0: CLEAN POLY  (Neutral Warm Saw + Triangle Foundation - Default)
// 1: JUNO PWM    (Modulated Pulse + Saw + Sub - Lush Pads & Cloudrap)
// 2: HYPERSAW    (7-Oscillator Stereo Detuned SuperSaw - Rage & Trap Leads)
// 3: DX TINE EP  (2-Op Phase Modulated Rhodes/FM - Lo-Fi & Neo-Soul Keys)
// 4: MODAL BELL  (Inharmonic Glass/Metal Resonator - Dark Trap Bells & Plucks)
// 5: KARPLUS STR (Physical Waveguide Plucked String - Harp, Guitar & Plucks)
val engineNames = listOf("POLY", "JUNO", "HYPR", "DXEP", "BELL", "STRG")
val fmRatios = floatArrayOf(0.5f, 1.0f, 2.0f, 3.0f, 4.0f, 7.0f)
val adsrNames = listOf("SUST", "PAD", "PLCK", "KEYS")

class VoiceConfig(
    @Volatile var engine: Int = 0,
    @Volatile var fmRatioIndex: Int = 1,
    @Volatile var timbre: Float = 0.25f,
    @Volatile var detuneCents: Int = 0,
    @Volatile var octShift: Int = 0,
    @Volatile var pan: Float = 0f,
    @Volatile var level: Float = 0.85f
)

data class VoicePresetData(
    val engine: Int,
    val fmRatioIndex: Int,
    val timbre: Float,
    val detuneCents: Int,
    val octShift: Int,
    val pan: Float,
    val level: Float
)

data class SynthPreset(
    val id: String,
    val name: String,
    val category: String,
    val adsrMode: Int,          // 0: SUST, 1: PAD, 2: PLCK, 3: KEYS
    val attackMs: Float,
    val decayMs: Float,
    val sustainLevel: Float,
    val releaseMs: Float,
    val baseCutoff: Float,      // 0.0..1.0 (Logarithmic 80Hz..16kHz)
    val filterResonance: Float, // 0.0..0.85
    val filterEnvAmount: Float, // 0.0..1.0
    val unisonSpread: Float,    // 0.0..1.0
    val lofiAmount: Float,      // 0.0..1.0 (Wow/Flutter, Tape Warmth, Vintage Drift)
    val glideSpeed: Float,
    val reverbMix: Float,
    val chorusOn: Boolean,
    val delayOn: Boolean,
    val lofiOn: Boolean,
    val voices: List<VoicePresetData>,
    val isUser: Boolean = false
) {
    fun serialize(): String {
        val vStr = voices.joinToString(";") {
            "${it.engine},${it.fmRatioIndex},${it.timbre},${it.detuneCents},${it.octShift},${it.pan},${it.level}"
        }
        return "$id|$name|$category|$adsrMode|$attackMs|$decayMs|$sustainLevel|$releaseMs|$baseCutoff|$filterResonance|$filterEnvAmount|$unisonSpread|$lofiAmount|$glideSpeed|$reverbMix|$chorusOn|$delayOn|$lofiOn|$vStr"
    }

    companion object {
        fun deserialize(raw: String): SynthPreset? {
            return try {
                val p = raw.split("|")
                if (p.size < 19) return null
                val vList = p[18].split(";").map { vRaw ->
                    val vp = vRaw.split(",")
                    VoicePresetData(
                        engine = vp[0].toInt(),
                        fmRatioIndex = vp[1].toInt(),
                        timbre = vp[2].toFloat(),
                        detuneCents = vp[3].toInt(),
                        octShift = vp[4].toInt(),
                        pan = vp[5].toFloat(),
                        level = vp[6].toFloat()
                    )
                }
                if (vList.size != 4) return null
                SynthPreset(
                    id = p[0], name = p[1], category = p[2],
                    adsrMode = p[3].toInt(), attackMs = p[4].toFloat(), decayMs = p[5].toFloat(),
                    sustainLevel = p[6].toFloat(), releaseMs = p[7].toFloat(),
                    baseCutoff = p[8].toFloat(), filterResonance = p[9].toFloat(),
                    filterEnvAmount = p[10].toFloat(), unisonSpread = p[11].toFloat(),
                    lofiAmount = p[12].toFloat(), glideSpeed = p[13].toFloat(),
                    reverbMix = p[14].toFloat(), chorusOn = p[15].toBoolean(),
                    delayOn = p[16].toBoolean(), lofiOn = p[17].toBoolean(),
                    voices = vList, isUser = true
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

// --- 12 KURATIERTE STUDIO-PRESETS (INKL. EHRLICHEM "INIT NEUTRAL" DEFAULT) ---
val factoryPresets = listOf(
    // 01. INIT NEUTRAL (Ehrlicher, ausgewogener Default-Polysynth ohne Effekt-Matsch)
    SynthPreset(
        id = "p_01_init", name = "INIT NEUTRAL", category = "DEFAULT",
        adsrMode = 0, attackMs = 8f, decayMs = 600f, sustainLevel = 0.78f, releaseMs = 280f,
        baseCutoff = 0.65f, filterResonance = 0.15f, filterEnvAmount = 0.25f,
        unisonSpread = 0.20f, lofiAmount = 0.0f, glideSpeed = 0.025f, reverbMix = 0.18f,
        chorusOn = false, delayOn = false, lofiOn = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.25f, detuneCents = -3, octShift = 0, pan = -0.30f, level = 0.90f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.25f, detuneCents = 3, octShift = 0, pan = 0.30f, level = 0.90f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.25f, detuneCents = -6, octShift = 0, pan = -0.55f, level = 0.85f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.25f, detuneCents = 6, octShift = 0, pan = 0.55f, level = 0.85f)
        )
    ),
    // 02. HICHORD KEYS (Warmes Rhodes Mk1 Tine E-Piano)
    SynthPreset(
        id = "p_02_keys", name = "HICHORD KEYS", category = "HICHORD OG",
        adsrMode = 3, attackMs = 2.5f, decayMs = 1650f, sustainLevel = 0.12f, releaseMs = 420f,
        baseCutoff = 0.58f, filterResonance = 0.12f, filterEnvAmount = 0.35f,
        unisonSpread = 0.25f, lofiAmount = 0.22f, glideSpeed = 0.045f, reverbMix = 0.32f,
        chorusOn = true, delayOn = false, lofiOn = false,
        voices = listOf(
            VoicePresetData(engine = 3, fmRatioIndex = 1, timbre = 0.38f, detuneCents = -2, octShift = 0, pan = -0.35f, level = 0.95f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, timbre = 0.38f, detuneCents = 2, octShift = 0, pan = 0.35f, level = 0.90f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, timbre = 0.35f, detuneCents = -4, octShift = 0, pan = -0.60f, level = 0.85f),
            VoicePresetData(engine = 3, fmRatioIndex = 2, timbre = 0.30f, detuneCents = 4, octShift = 0, pan = 0.60f, level = 0.78f)
        )
    ),
    // 03. JUNO-60 PAD (Klassisches HiChord Analog-PWM-Pad mit BBD Chorus)
    SynthPreset(
        id = "p_03_juno", name = "JUNO-60 PAD", category = "HICHORD OG",
        adsrMode = 1, attackMs = 380f, decayMs = 1200f, sustainLevel = 0.85f, releaseMs = 950f,
        baseCutoff = 0.42f, filterResonance = 0.32f, filterEnvAmount = 0.38f,
        unisonSpread = 0.55f, lofiAmount = 0.18f, glideSpeed = 0.0045f, reverbMix = 0.52f,
        chorusOn = true, delayOn = false, lofiOn = false,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.52f, detuneCents = -7, octShift = 0, pan = -0.50f, level = 0.92f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.52f, detuneCents = 7, octShift = 0, pan = 0.50f, level = 0.92f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.48f, detuneCents = -14, octShift = 0, pan = -0.80f, level = 0.86f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.48f, detuneCents = 14, octShift = 0, pan = 0.80f, level = 0.86f)
        )
    ),
    // 04. TAPE LO-FI KEYS (Verträumtes Vintage-Cassette-Piano mit aktiviertem Lo-Fi-Modus)
    SynthPreset(
        id = "p_04_lofikeys", name = "TAPE LO-FI KEYS", category = "LO-FI VINTAGE",
        adsrMode = 3, attackMs = 4f, decayMs = 1150f, sustainLevel = 0.15f, releaseMs = 380f,
        baseCutoff = 0.40f, filterResonance = 0.24f, filterEnvAmount = 0.42f,
        unisonSpread = 0.35f, lofiAmount = 0.72f, glideSpeed = 0.020f, reverbMix = 0.44f,
        chorusOn = true, delayOn = false, lofiOn = true,
        voices = listOf(
            VoicePresetData(engine = 3, fmRatioIndex = 1, timbre = 0.32f, detuneCents = -6, octShift = 0, pan = -0.30f, level = 0.95f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.28f, detuneCents = 6, octShift = 0, pan = 0.30f, level = 0.88f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, timbre = 0.30f, detuneCents = -12, octShift = 0, pan = -0.60f, level = 0.84f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.25f, detuneCents = 12, octShift = 0, pan = 0.60f, level = 0.80f)
        )
    ),
    // 05. CLOUD CATHEDRAL (Yung Lean / Whitearmor Lush Shimmer Pad)
    SynthPreset(
        id = "p_05_cloud", name = "CLOUD CATHEDRAL", category = "CLOUDRAP",
        adsrMode = 1, attackMs = 520f, decayMs = 1500f, sustainLevel = 0.88f, releaseMs = 1250f,
        baseCutoff = 0.52f, filterResonance = 0.28f, filterEnvAmount = 0.30f,
        unisonSpread = 0.75f, lofiAmount = 0.35f, glideSpeed = 0.0028f, reverbMix = 0.72f,
        chorusOn = true, delayOn = true, lofiOn = false,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.45f, detuneCents = -8, octShift = 0, pan = -0.45f, level = 0.92f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.55f, detuneCents = 8, octShift = 0, pan = 0.45f, level = 0.88f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.60f, detuneCents = -16, octShift = 0, pan = -0.85f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 2, timbre = 0.35f, detuneCents = 16, octShift = 1, pan = 0.85f, level = 0.68f)
        )
    ),
    // 06. OPIUM RAGE SAW (Ken Carson / Playboi Carti 7-Osc Hypersaw Lead Stack)
    SynthPreset(
        id = "p_06_rage", name = "OPIUM RAGE SAW", category = "RAGE / TRAP",
        adsrMode = 0, attackMs = 3f, decayMs = 450f, sustainLevel = 0.92f, releaseMs = 220f,
        baseCutoff = 0.86f, filterResonance = 0.30f, filterEnvAmount = 0.35f,
        unisonSpread = 0.92f, lofiAmount = 0.0f, glideSpeed = 0.0085f, reverbMix = 0.28f,
        chorusOn = false, delayOn = false, lofiOn = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.85f, detuneCents = -10, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.90f, detuneCents = -22, octShift = 0, pan = -0.90f, level = 0.92f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.90f, detuneCents = 22, octShift = 0, pan = 0.90f, level = 0.92f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.85f, detuneCents = 12, octShift = 1, pan = 0f, level = 0.78f)
        )
    ),
    // 07. LUCID PLUCK (Juice WRLD / Nick Mira Melodic Trap Pluck)
    SynthPreset(
        id = "p_07_lucid", name = "LUCID PLUCK", category = "EMO / TRAP",
        adsrMode = 2, attackMs = 1.5f, decayMs = 580f, sustainLevel = 0.0f, releaseMs = 320f,
        baseCutoff = 0.34f, filterResonance = 0.42f, filterEnvAmount = 0.78f,
        unisonSpread = 0.48f, lofiAmount = 0.25f, glideSpeed = 0.015f, reverbMix = 0.46f,
        chorusOn = true, delayOn = true, lofiOn = false,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.55f, detuneCents = -5, octShift = 0, pan = -0.35f, level = 0.95f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.50f, detuneCents = 5, octShift = 0, pan = 0.35f, level = 0.90f),
            VoicePresetData(engine = 3, fmRatioIndex = 2, timbre = 0.42f, detuneCents = -10, octShift = 0, pan = -0.70f, level = 0.85f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.48f, detuneCents = 10, octShift = 0, pan = 0.70f, level = 0.85f)
        )
    ),
    // 08. METRO DARK BELL (Dunkle, glockige Trap-Pluck-Textur)
    SynthPreset(
        id = "p_08_metrobell", name = "METRO DARK BELL", category = "TRAP",
        adsrMode = 2, attackMs = 1.2f, decayMs = 750f, sustainLevel = 0.0f, releaseMs = 420f,
        baseCutoff = 0.48f, filterResonance = 0.25f, filterEnvAmount = 0.62f,
        unisonSpread = 0.30f, lofiAmount = 0.30f, glideSpeed = 0.030f, reverbMix = 0.54f,
        chorusOn = false, delayOn = true, lofiOn = false,
        voices = listOf(
            VoicePresetData(engine = 4, fmRatioIndex = 2, timbre = 0.52f, detuneCents = -3, octShift = 0, pan = -0.30f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 2, timbre = 0.52f, detuneCents = 3, octShift = 0, pan = 0.30f, level = 0.92f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, timbre = 0.46f, detuneCents = -7, octShift = 0, pan = -0.65f, level = 0.84f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, timbre = 0.46f, detuneCents = 7, octShift = 0, pan = 0.65f, level = 0.84f)
        )
    ),
    // 09. TAPE MELLOTRON (Vintage Lo-Fi Flute/String Choir)
    SynthPreset(
        id = "p_09_mellotron", name = "TAPE MELLOTRON", category = "LO-FI VINTAGE",
        adsrMode = 1, attackMs = 260f, decayMs = 900f, sustainLevel = 0.80f, releaseMs = 580f,
        baseCutoff = 0.32f, filterResonance = 0.38f, filterEnvAmount = 0.25f,
        unisonSpread = 0.42f, lofiAmount = 0.82f, glideSpeed = 0.0050f, reverbMix = 0.45f,
        chorusOn = true, delayOn = false, lofiOn = true,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.62f, detuneCents = -8, octShift = 0, pan = -0.40f, level = 0.92f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.45f, detuneCents = 8, octShift = 0, pan = 0.40f, level = 0.90f),
            VoicePresetData(engine = 1, fmRatioIndex = 2, timbre = 0.55f, detuneCents = -15, octShift = 0, pan = -0.75f, level = 0.84f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.40f, detuneCents = 15, octShift = 0, pan = 0.75f, level = 0.82f)
        )
    ),
    // 10. PROPHET BRASS (Mike Dean / Astroworld Analog Filter-Sweep Brass)
    SynthPreset(
        id = "p_10_brass", name = "PROPHET BRASS", category = "ANALOG",
        adsrMode = 0, attackMs = 45f, decayMs = 580f, sustainLevel = 0.76f, releaseMs = 360f,
        baseCutoff = 0.22f, filterResonance = 0.48f, filterEnvAmount = 0.78f,
        unisonSpread = 0.62f, lofiAmount = 0.25f, glideSpeed = 0.0022f, reverbMix = 0.42f,
        chorusOn = true, delayOn = true, lofiOn = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.75f, detuneCents = -6, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.65f, detuneCents = -12, octShift = 0, pan = -0.70f, level = 0.90f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.65f, detuneCents = 12, octShift = 0, pan = 0.70f, level = 0.90f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.70f, detuneCents = 18, octShift = 0, pan = 0f, level = 0.85f)
        )
    ),
    // 11. ACOUSTIC HARP (Echte Karplus-Strong Zupfsaiten)
    SynthPreset(
        id = "p_11_harp", name = "ACOUSTIC HARP", category = "HICHORD OG",
        adsrMode = 2, attackMs = 1.2f, decayMs = 1250f, sustainLevel = 0.0f, releaseMs = 480f,
        baseCutoff = 0.76f, filterResonance = 0.14f, filterEnvAmount = 0.38f,
        unisonSpread = 0.22f, lofiAmount = 0.10f, glideSpeed = 0.070f, reverbMix = 0.52f,
        chorusOn = false, delayOn = true, lofiOn = false,
        voices = listOf(
            VoicePresetData(engine = 5, fmRatioIndex = 1, timbre = 0.68f, detuneCents = -2, octShift = 0, pan = -0.35f, level = 0.95f),
            VoicePresetData(engine = 5, fmRatioIndex = 1, timbre = 0.68f, detuneCents = 2, octShift = 0, pan = 0.35f, level = 0.92f),
            VoicePresetData(engine = 5, fmRatioIndex = 1, timbre = 0.72f, detuneCents = -4, octShift = 0, pan = -0.65f, level = 0.88f),
            VoicePresetData(engine = 5, fmRatioIndex = 1, timbre = 0.75f, detuneCents = 4, octShift = 1, pan = 0.65f, level = 0.75f)
        )
    ),
    // 12. POCKET CHIP (Clean Chiptune Pulse Synth)
    SynthPreset(
        id = "p_12_chip", name = "POCKET CHIP", category = "HICHORD OG",
        adsrMode = 2, attackMs = 1.2f, decayMs = 420f, sustainLevel = 0.0f, releaseMs = 180f,
        baseCutoff = 0.84f, filterResonance = 0.20f, filterEnvAmount = 0.35f,
        unisonSpread = 0.15f, lofiAmount = 0.0f, glideSpeed = 0.090f, reverbMix = 0.22f,
        chorusOn = false, delayOn = true, lofiOn = false,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.85f, detuneCents = 0, octShift = 0, pan = -0.35f, level = 0.92f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.85f, detuneCents = -4, octShift = 0, pan = 0.35f, level = 0.88f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.85f, detuneCents = 4, octShift = 0, pan = -0.65f, level = 0.85f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.85f, detuneCents = 0, octShift = 1, pan = 0.65f, level = 0.74f)
        )
    )
)

// --- 8-WEGE HICHORD JOYSTICK-RICHTUNGEN ---
enum class JoyChordZone(val shortLabel: String, val desc: String) {
    CENTER("NORM", "DIATONIC"),
    UP("FLIP", "MAJ ↔ MIN"),
    UP_RIGHT("DOM7", "BLUESY 7TH"),
    RIGHT("MAJ7", "JAZZY 7TH"),
    DOWN_RIGHT("ADD9", "LUSH 9TH"),
    DOWN("SUS4", "OPEN SUS4"),
    DOWN_LEFT("6TH", "SWEET 6TH"),
    LEFT("DIM", "DARK DIM"),
    UP_LEFT("AUG", "DREAMY AUG")
}

fun getJoystickZone(x: Float, y: Float): JoyChordZone {
    val mag = sqrt(x * x + y * y)
    if (mag < 0.34f) return JoyChordZone.CENTER
    val deg = ((atan2(y, x) * 180f / PI.toFloat()) + 360f) % 360f
    return when {
        deg >= 337.5f || deg < 22.5f -> JoyChordZone.RIGHT
        deg < 67.5f -> JoyChordZone.UP_RIGHT
        deg < 112.5f -> JoyChordZone.UP
        deg < 157.5f -> JoyChordZone.UP_LEFT
        deg < 202.5f -> JoyChordZone.LEFT
        deg < 247.5f -> JoyChordZone.DOWN_LEFT
        deg < 292.5f -> JoyChordZone.DOWN
        else -> JoyChordZone.DOWN_RIGHT
    }
}

fun polyBlep(t: Float, dt: Float): Float {
    if (dt <= 0f) return 0f
    return when {
        t < dt -> {
            val x = t / dt
            x + x - x * x - 1f
        }
        t > 1f - dt -> {
            val x = (t - 1f) / dt
            x * x + x + x + 1f
        }
        else -> 0f
    }
}

// --- HICHORD PRO 32-BIT FLOAT DSP ENGINE (CYTOMIC ZDF FILTER + DATTORRO REVERB + LO-FI TAPE DECK) ---
class HiChordProEngine {
    private val sampleRate = 48000
    @Volatile var isRunning = false
    private var audioThread: Thread? = null

    val targetFreqs = FloatArray(4) { 0f }
    private val currentFreqs = FloatArray(4) { 0f }
    @Volatile var targetBassFreq = 0f
    private var currentBassFreq = 0f
    private var arpSmoothFreq = 0f

    @Volatile var gate = false
    @Volatile var bpm = 120
    @Volatile var strumMode = false
    @Volatile var bassMode = 1
    @Volatile var filterWheel = 0.65f

    // Cohesive Synth Studio Parameters
    @Volatile var adsrMode = 0
    @Volatile var attackMs = 8f
    @Volatile var decayMs = 600f
    @Volatile var sustainLevel = 0.78f
    @Volatile var releaseMs = 280f
    @Volatile var baseCutoff = 0.65f
    @Volatile var filterResonance = 0.15f
    @Volatile var filterEnvAmount = 0.25f
    @Volatile var unisonSpread = 0.20f
    @Volatile var lofiAmount = 0.45f
    @Volatile var glideSpeed = 0.025f
    @Volatile var reverbMix = 0.22f

    @Volatile var arpMode = 0

    // Performance & Vintage Modes
    @Volatile var fxDelay = false
    @Volatile var fxCrush = false
    @Volatile var fxStutter = false
    @Volatile var fxChorus = false
    @Volatile var fxPump = false
    @Volatile var fxReverb = true
    @Volatile var lofiMode = false // Dedizierter Lo-Fi Vintage Tape Modus!

    @Volatile private var syncResetRequested = false
    @Volatile private var attackTriggerCount = 0

    val voices = Array(4) { v ->
        VoiceConfig(
            engine = 0,
            fmRatioIndex = 1,
            timbre = 0.25f,
            detuneCents = when (v) { 0 -> -3; 1 -> 3; 2 -> -6; else -> 6 },
            octShift = 0,
            pan = when (v) { 0 -> -0.30f; 1 -> 0.30f; 2 -> -0.55f; else -> 0.55f },
            level = 0.88f
        )
    }

    val scopeBuffer = FloatArray(128)

    // 7 Sub-Oszillatoren pro Stimme für echten Stereo-Hypersaw
    private val oscPhases = Array(4) { FloatArray(7) { Random.nextFloat() } }
    private val modPhases = FloatArray(4) { Random.nextFloat() }
    private val tinePhases = FloatArray(4) { Random.nextFloat() }
    private val analogDrift = FloatArray(4) { 1f }

    // Karplus-Strong Delay-Line pro Stimme
    private val ksDelayLines = Array(4) { FloatArray(2048) }
    private val ksWritePos = IntArray(4)
    private val ksLpState = FloatArray(4)
    private val ksExciteRemaining = IntArray(4)

    // Cytomic Linear Trapezoidal Integrated SVF (ZDF) States pro Stimme (ic1eq, ic2eq)
    private val zdfIc1 = FloatArray(4)
    private val zdfIc2 = FloatArray(4)

    // Per-Voice ADSR Envelopes
    private val voiceStage = IntArray(4)
    private val voiceAmpEnv = FloatArray(4)
    private val voiceFltEnv = FloatArray(4)
    private val voiceTineEnv = FloatArray(4)
    private val voiceStrumWait = IntArray(4)

    private var bassPhase = 0f
    private var pwmPhase = 0f

    // Lo-Fi Vintage Tape Deck Processor (Modulated Fractional Tape Delay + Vinyl Dust + Head Rolloff)
    private val tapeFlutterBufL = FloatArray(4096)
    private val tapeFlutterBufR = FloatArray(4096)
    private var tapeWritePos = 0
    private var wowPhase = 0f
    private var flutterPhase = 0f
    private var tapeLpL = 0f
    private var tapeLpR = 0f
    private var vinylCrackleEnv = 0f

    // 3-Phase BBD Stereo Ensemble Chorus
    private val bbdBufL = FloatArray(2048)
    private val bbdBufR = FloatArray(2048)
    private var bbdWritePos = 0
    private var bbdLfoSlow = 0f
    private var bbdLfoFast = 0f

    // Modulated Dattorro / FDN Studio Lush Reverb (Kein metallisches Klingen!)
    private val revAp1 = FloatArray(347)
    private val revAp2 = FloatArray(521)
    private val revAp3 = FloatArray(733)
    private var rap1 = 0; private var rap2 = 0; private var rap3 = 0
    private val tankL1 = FloatArray(3119)
    private val tankR1 = FloatArray(3463)
    private val tankL2 = FloatArray(4217)
    private val tankR2 = FloatArray(4591)
    private var tl1 = 0; private var tr1 = 0; private var tl2 = 0; private var tr2 = 0
    private var tankLpL = 0f; private var tankLpR = 0f
    private var revModPhase = 0f

    // Stereo Ping-Pong Tape Delay
    private val delayBufL = FloatArray(48000)
    private val delayBufR = FloatArray(48000)
    private var delayWritePos = 0
    private var delayLpL = 0f
    private var delayLpR = 0f

    private var crushCounter = 0
    private var crushedL = 0f
    private var crushedR = 0f

    // DC-Blocker am Master-Ausgang
    private var dcInL = 0f; private var dcOutL = 0f
    private var dcInR = 0f; private var dcOutR = 0f

    fun playChord(freqs: FloatArray, bassFreq: Float, retriggerAttack: Boolean) {
        for (i in 0..3) targetFreqs[i] = freqs[i]
        targetBassFreq = bassFreq
        gate = true
        if (retriggerAttack) {
            attackTriggerCount++
        }
    }

    fun releaseChord() {
        gate = false
    }

    fun setGlobalWaveform(waveIdx: Int) {
        val w = waveIdx.coerceIn(0, engineNames.lastIndex)
        for (v in 0..3) voices[v].engine = w
        attackTriggerCount++
    }

    fun applyAdsrMacro(mode: Int) {
        adsrMode = mode.coerceIn(0, 3)
        when (adsrMode) {
            0 -> { attackMs = 8f; decayMs = 550f; sustainLevel = 0.78f; releaseMs = 280f }
            1 -> { attackMs = 450f; decayMs = 1200f; sustainLevel = 0.86f; releaseMs = 1050f }
            2 -> { attackMs = 1.2f; decayMs = 580f; sustainLevel = 0.0f; releaseMs = 300f }
            3 -> { attackMs = 2.5f; decayMs = 1500f; sustainLevel = 0.14f; releaseMs = 420f }
        }
        attackTriggerCount++
    }

    fun loadSynthPreset(preset: SynthPreset) {
        adsrMode = preset.adsrMode
        attackMs = preset.attackMs
        decayMs = preset.decayMs
        sustainLevel = preset.sustainLevel
        releaseMs = preset.releaseMs
        baseCutoff = preset.baseCutoff
        filterWheel = preset.baseCutoff
        filterResonance = preset.filterResonance
        filterEnvAmount = preset.filterEnvAmount
        unisonSpread = preset.unisonSpread
        lofiAmount = preset.lofiAmount
        glideSpeed = preset.glideSpeed
        reverbMix = preset.reverbMix
        fxChorus = preset.chorusOn
        fxDelay = preset.delayOn
        lofiMode = preset.lofiOn
        for (v in 0..3) {
            val vp = preset.voices[v]
            voices[v].apply {
                engine = vp.engine
                fmRatioIndex = vp.fmRatioIndex
                timbre = vp.timbre
                detuneCents = vp.detuneCents
                octShift = vp.octShift
                pan = vp.pan
                level = vp.level
            }
        }
        attackTriggerCount++
    }

    fun snapshotToPreset(id: String, name: String): SynthPreset {
        val vData = (0..3).map { v ->
            val vc = voices[v]
            VoicePresetData(vc.engine, vc.fmRatioIndex, vc.timbre, vc.detuneCents, vc.octShift, vc.pan, vc.level)
        }
        return SynthPreset(
            id = id, name = name, category = "USER PATCH",
            adsrMode = adsrMode, attackMs = attackMs, decayMs = decayMs,
            sustainLevel = sustainLevel, releaseMs = releaseMs,
            baseCutoff = filterWheel, filterResonance = filterResonance,
            filterEnvAmount = filterEnvAmount, unisonSpread = unisonSpread,
            lofiAmount = lofiAmount, glideSpeed = glideSpeed,
            reverbMix = reverbMix, chorusOn = fxChorus,
            delayOn = fxDelay, lofiOn = lofiMode,
            voices = vData, isUser = true
        )
    }

    fun triggerChordAttack() { attackTriggerCount++ }
    fun resetClockSync() { syncResetRequested = true }

    private fun exciteVoice(v: Int, freq: Float) {
        voiceStage[v] = 1
        voiceFltEnv[v] = 1.0f
        voiceTineEnv[v] = 1.0f
        val driftAmt = if (lofiMode) 0.0045f else 0.0012f
        analogDrift[v] = 1f + (Random.nextFloat() - 0.5f) * driftAmt

        val period = (sampleRate / freq.coerceAtLeast(35f)).toInt().coerceIn(16, 1024)
        val line = ksDelayLines[v]
        var smoothNoise = 0f
        val brightness = voices[v].timbre.coerceIn(0.20f, 0.96f)
        for (idx in 0 until period) {
            val pos = (ksWritePos[v] - idx + line.size) % line.size
            val rawN = (Random.nextFloat() * 2f - 1f)
            val bodyWave = sin(idx.toFloat() / period.toFloat() * 2f * PI.toFloat())
            smoothNoise += brightness * ((rawN * 0.65f + bodyWave * 0.55f) - smoothNoise)
            line[pos] = smoothNoise
        }
        ksExciteRemaining[v] = (sampleRate * 0.003f).toInt()
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        audioThread = thread(start = true, isDaemon = true, name = "HiChordProAudioThread") {
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(2048)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(minBufSize).setTransferMode(AudioTrack.MODE_STREAM).build()

            val framesPerBlock = 256
            val floatStereoBuffer = FloatArray(framesPerBlock * 2)
            track.play()

            var sampleCounter = 0
            var beatSampleCounter = 0
            var arpStepCounter = 0
            var arpVoice = 0
            val pingPongOrder = intArrayOf(0, 1, 2, 3, 2, 1)
            var lastHandledAttackCount = attackTriggerCount
            val hyperSawOffsets = floatArrayOf(-0.019f, -0.011f, -0.0045f, 0.0f, 0.0048f, 0.0115f, 0.0195f)

            while (isRunning) {
                val currentBpm = bpm.coerceIn(60, 200)
                val quarterSamples = ((sampleRate * 60) / currentBpm).coerceAtLeast(2000)
                val sixteenthSamples = (quarterSamples / 4).coerceAtLeast(500)
                val delaySamples = (sixteenthSamples * 3).coerceIn(1000, delayBufL.size - 1)
                val currentGlide = glideSpeed.coerceIn(0.0005f, 0.095f)

                val attSamples = (attackMs * 0.001f * sampleRate).coerceAtLeast(12f)
                val attackStep = 1f / attSamples
                val decSamples = (decayMs * 0.001f * sampleRate).coerceAtLeast(250f)
                val decayMul = 0.001f.pow(1f / decSamples)
                val relSamples = (releaseMs * 0.001f * sampleRate).coerceAtLeast(180f)
                val releaseMul = 0.001f.pow(1f / relSamples)
                val fltDecayMul = 0.001f.pow(1f / (decSamples * 0.36f))
                val tineDecayMul = 0.001f.pow(1f / (sampleRate * 0.13f))
                val targetSustain = if (adsrMode == 2) 0.0f else sustainLevel.coerceIn(0f, 1f)

                // Cytomic ZDF SVF Resonanz-Dämpfung k = 2 - 2*res
                val zdfK = (2.0f - 1.75f * filterResonance.coerceIn(0f, 0.88f)).coerceIn(0.22f, 2.0f)
                val spreadAmt = unisonSpread.coerceIn(0.05f, 1.0f)
                val twoPi = 2f * PI.toFloat()

                if (syncResetRequested) {
                    syncResetRequested = false
                    sampleCounter = 0
                    beatSampleCounter = 0
                    arpStepCounter = 0
                }

                for (i in 0 until framesPerBlock) {
                    sampleCounter++
                    beatSampleCounter = (beatSampleCounter + 1) % quarterSamples

                    pwmPhase = (pwmPhase + 0.82f / sampleRate) % 1f
                    bbdLfoSlow = (bbdLfoSlow + 0.55f / sampleRate) % 1f
                    bbdLfoFast = (bbdLfoFast + 5.4f / sampleRate) % 1f
                    revModPhase = (revModPhase + 0.72f / sampleRate) % 1f
                    wowPhase = (wowPhase + 0.52f / sampleRate) % 1f
                    flutterPhase = (flutterPhase + 6.4f / sampleRate) % 1f

                    val pwmMod = sin(pwmPhase * twoPi) * 0.36f

                    if (sampleCounter >= sixteenthSamples) {
                        sampleCounter = 0
                        arpStepCounter++

                        arpVoice = when (arpMode) {
                            1 -> arpStepCounter % 4
                            2 -> 3 - (arpStepCounter % 4)
                            3 -> pingPongOrder[arpStepCounter % pingPongOrder.size]
                            4 -> Random.nextInt(4)
                            else -> 0
                        }

                        if (arpMode > 0 && gate) {
                            val tFreq = targetFreqs[arpVoice].coerceAtLeast(110f)
                            exciteVoice(0, tFreq)
                        }
                    }

                    val currentTrig = attackTriggerCount
                    if (currentTrig != lastHandledAttackCount) {
                        lastHandledAttackCount = currentTrig
                        val strumStepSamples = if (strumMode) (sampleRate * 0.032f).toInt() else (sampleRate * 0.0025f).toInt()
                        for (v in 0..3) {
                            voiceStrumWait[v] = v * strumStepSamples
                            if (voiceStrumWait[v] == 0) {
                                val f = targetFreqs[v] * 2f.pow(voices[v].octShift.toFloat())
                                exciteVoice(v, f.coerceAtLeast(110f))
                            }
                        }
                    }

                    var mixL = 0f
                    var mixR = 0f
                    var activeEnvSum = 0f

                    if (arpMode > 0) {
                        if (!gate && voiceStage[0] != 0 && voiceStage[0] != 4) voiceStage[0] = 4
                        stepVoiceEnvelope(0, attackStep, decayMul, releaseMul, targetSustain, fltDecayMul, tineDecayMul)

                        val aEnv = voiceAmpEnv[0]
                        activeEnvSum = aEnv
                        if (aEnv > 0.0002f) {
                            val vc = voices[arpVoice]
                            val octMult = 2f.pow(vc.octShift.toFloat())
                            val rawTarget = targetFreqs[arpVoice] * octMult * 2f.pow((vc.detuneCents * spreadAmt) / 1200f) * analogDrift[0]
                            if (rawTarget > 20f) {
                                if (arpSmoothFreq <= 20f) arpSmoothFreq = rawTarget
                                arpSmoothFreq += (rawTarget - arpSmoothFreq) * (currentGlide * 1.8f)

                                val rawSig = renderStudioVoice(0, vc, arpSmoothFreq, aEnv, voiceFltEnv[0], voiceTineEnv[0], pwmMod, spreadAmt, hyperSawOffsets)
                                val filtered = processCytomicZdfLowpass(0, rawSig, arpSmoothFreq, voiceFltEnv[0], zdfK)

                                val panNorm = ((vc.pan * spreadAmt + 1f) * 0.5f).coerceIn(0f, 1f)
                                mixL += filtered * cos(panNorm * PI.toFloat() * 0.5f) * 1.35f
                                mixR += filtered * sin(panNorm * PI.toFloat() * 0.5f) * 1.35f
                            }
                        }
                    } else {
                        for (v in 0 until 4) {
                            if (voiceStrumWait[v] > 0) {
                                voiceStrumWait[v]--
                                if (voiceStrumWait[v] == 0 && gate) {
                                    val f = targetFreqs[v] * 2f.pow(voices[v].octShift.toFloat())
                                    exciteVoice(v, f.coerceAtLeast(110f))
                                }
                            }

                            if (!gate && voiceStage[v] != 0 && voiceStage[v] != 4) {
                                voiceStage[v] = 4
                            }

                            stepVoiceEnvelope(v, attackStep, decayMul, releaseMul, targetSustain, fltDecayMul, tineDecayMul)

                            val aEnv = voiceAmpEnv[v]
                            activeEnvSum += aEnv * 0.25f
                            if (aEnv <= 0.0002f) continue

                            val vc = voices[v]
                            val octMult = 2f.pow(vc.octShift.toFloat())
                            val tFreq = targetFreqs[v] * octMult * 2f.pow((vc.detuneCents * spreadAmt) / 1200f) * analogDrift[v]
                            if (tFreq <= 20f) continue

                            if (currentFreqs[v] <= 20f) currentFreqs[v] = tFreq
                            currentFreqs[v] += (tFreq - currentFreqs[v]) * currentGlide
                            val freq = currentFreqs[v]

                            val rawSig = renderStudioVoice(v, vc, freq, aEnv, voiceFltEnv[v], voiceTineEnv[v], pwmMod, spreadAmt, hyperSawOffsets)
                            val filtered = processCytomicZdfLowpass(v, rawSig, freq, voiceFltEnv[v], zdfK)

                            val panNorm = ((vc.pan * spreadAmt + 1f) * 0.5f).coerceIn(0f, 1f)
                            mixL += filtered * cos(panNorm * PI.toFloat() * 0.5f)
                            mixR += filtered * sin(panNorm * PI.toFloat() * 0.5f)
                        }
                    }

                    // Warm Analog Sub-Bass Voice
                    if (bassMode > 0 && targetBassFreq > 20f && activeEnvSum > 0.001f) {
                        if (currentBassFreq <= 20f) currentBassFreq = targetBassFreq
                        currentBassFreq += (targetBassFreq - currentBassFreq) * (currentGlide * 1.5f)
                        val bDt = (currentBassFreq / sampleRate).coerceIn(0.0001f, 0.25f)
                        bassPhase = (bassPhase + bDt) % 1f
                        val bSub = sin(bassPhase * twoPi) * 0.78f
                        val bTri = (1f - 4f * abs(round(bassPhase - 0.25f) - (bassPhase - 0.25f))) * 0.25f
                        val bassSig = (bSub + bTri) * 0.22f * activeEnvSum
                        mixL += bassSig
                        mixR += bassSig
                    }

                    if (fxStutter) {
                        val stutterPeriod = (sixteenthSamples / 2).coerceAtLeast(1)
                        if ((sampleCounter % stutterPeriod) > (stutterPeriod / 2)) {
                            mixL = 0f; mixR = 0f
                        }
                    }

                    var finalL = mixL
                    var finalR = mixR

                    // DEDIZIERTER LO-FI VINTAGE TAPE DECK PROCESSOR (Wow & Flutter + Tape Saturation + Vinyl Dust + Head Rolloff)
                    tapeFlutterBufL[tapeWritePos] = finalL
                    tapeFlutterBufR[tapeWritePos] = finalR
                    if (lofiMode) {
                        val lfAmt = lofiAmount.coerceIn(0.25f, 1.0f)
                        val wowMod = (0.5f + 0.5f * sin(wowPhase * twoPi)) * 95f * lfAmt
                        val flutMod = (0.5f + 0.5f * sin(flutterPhase * twoPi)) * 14f * lfAmt
                        val totalDelay = (32f + wowMod + flutMod).coerceIn(4f, 4000f)
                        val dInt = totalDelay.toInt()
                        val dFrac = totalDelay - dInt

                        val r1 = (tapeWritePos - dInt + tapeFlutterBufL.size) % tapeFlutterBufL.size
                        val r2 = (r1 - 1 + tapeFlutterBufL.size) % tapeFlutterBufL.size
                        val wL = tapeFlutterBufL[r1] * (1f - dFrac) + tapeFlutterBufL[r2] * dFrac
                        val wR = tapeFlutterBufR[r1] * (1f - dFrac) + tapeFlutterBufR[r2] * dFrac

                        // Warme asymmetrische Band-Sättigung + Analoger Höhen-Rolloff (3.2 kHz - 6 kHz)
                        val satL = tanh(wL * (1.25f + lfAmt * 0.45f) + 0.04f * lfAmt) - 0.04f * lfAmt
                        val satR = tanh(wR * (1.25f + lfAmt * 0.45f) - 0.04f * lfAmt) + 0.04f * lfAmt

                        // Dezentes Vinyl-/Tape-Knistern
                        if (Random.nextFloat() < 0.0012f * lfAmt) {
                            vinylCrackleEnv = Random.nextFloat() * 0.025f * lfAmt
                        }
                        vinylCrackleEnv *= 0.92f
                        val tapeHiss = ((Random.nextFloat() * 2f - 1f) * 0.0022f * lfAmt) + vinylCrackleEnv

                        val lpCut = (0.42f - lfAmt * 0.22f).coerceIn(0.14f, 0.55f)
                        tapeLpL += lpCut * ((satL + tapeHiss) - tapeLpL)
                        tapeLpR += lpCut * ((satR + tapeHiss) - tapeLpR)
                        finalL = tapeLpL
                        finalR = tapeLpR
                    }
                    tapeWritePos = (tapeWritePos + 1) % tapeFlutterBufL.size

                    // 3-Phasen Solina/Juno BBD Ensemble Chorus
                    bbdBufL[bbdWritePos] = finalL
                    bbdBufR[bbdWritePos] = finalR
                    if (fxChorus) {
                        val d1 = 290f + sin(bbdLfoSlow * twoPi) * 135f + sin(bbdLfoFast * twoPi) * 16f
                        val d2 = 325f + sin((bbdLfoSlow + 0.333f) * twoPi) * 135f + sin((bbdLfoFast + 0.333f) * twoPi) * 16f
                        val d3 = 360f + sin((bbdLfoSlow + 0.666f) * twoPi) * 135f + sin((bbdLfoFast + 0.666f) * twoPi) * 16f

                        val r1 = (bbdWritePos - d1.toInt() + bbdBufL.size) % bbdBufL.size
                        val r2 = (bbdWritePos - d2.toInt() + bbdBufR.size) % bbdBufR.size
                        val r3 = (bbdWritePos - d3.toInt() + bbdBufL.size) % bbdBufL.size

                        finalL = finalL * 0.70f + (bbdBufL[r1] + bbdBufR[r3] * 0.5f) * 0.38f
                        finalR = finalR * 0.70f + (bbdBufR[r2] + bbdBufL[r3] * 0.5f) * 0.38f
                    }
                    bbdWritePos = (bbdWritePos + 1) % bbdBufL.size

                    if (fxPump) {
                        val beatPhase = beatSampleCounter.toFloat() / quarterSamples.toFloat()
                        val pumpGain = (0.10f + 0.90f * beatPhase.pow(0.65f)).coerceIn(0.10f, 1f)
                        finalL *= pumpGain; finalR *= pumpGain
                    }

                    if (fxCrush) {
                        crushCounter++
                        if (crushCounter >= 8) {
                            crushCounter = 0
                            crushedL = round(finalL * 7f) / 7f
                            crushedR = round(finalR * 7f) / 7f
                        }
                        finalL = crushedL; finalR = crushedR
                    }

                    // Warm Filtered Ping-Pong Tape Delay
                    val readPos = (delayWritePos - delaySamples + delayBufL.size) % delayBufL.size
                    if (fxDelay) {
                        val dL = delayBufL[readPos]
                        val dR = delayBufR[readPos]
                        delayLpL += 0.35f * (dR - delayLpL)
                        delayLpR += 0.35f * (dL - delayLpR)
                        finalL += delayLpL * 0.38f
                        finalR += delayLpR * 0.38f
                        delayBufL[delayWritePos] = (finalL * 0.54f).coerceIn(-1f, 1f)
                        delayBufR[delayWritePos] = (finalR * 0.54f).coerceIn(-1f, 1f)
                    } else {
                        delayBufL[delayWritePos] = 0f
                        delayBufR[delayWritePos] = 0f
                    }
                    delayWritePos = (delayWritePos + 1) % delayBufL.size

                    // MODULATED DATTORRO STUDIO LUSH REVERB (Diffusions-Kaskade + LFO-modulierte Tank-Linien)
                    if (fxReverb && reverbMix > 0.01f) {
                        var diff = (finalL + finalR) * 0.5f * reverbMix

                        // 3 Input Allpass-Diffusoren glätten jeden Anschlags-Impuls zu einer weichen Wolke
                        val b1 = revAp1[rap1]; val o1 = -0.7f * diff + b1; revAp1[rap1] = diff + 0.7f * o1; rap1 = (rap1 + 1) % revAp1.size; diff = o1
                        val b2 = revAp2[rap2]; val o2 = -0.7f * diff + b2; revAp2[rap2] = diff + 0.7f * o2; rap2 = (rap2 + 1) % revAp2.size; diff = o2
                        val b3 = revAp3[rap3]; val o3 = -0.65f * diff + b3; revAp3[rap3] = diff + 0.65f * o3; rap3 = (rap3 + 1) % revAp3.size; diff = o3

                        val modOffset = (sin(revModPhase * twoPi) * 24f).toInt()
                        val rIdxL1 = (tl1 - (tankL1.size - 35 + modOffset) + tankL1.size) % tankL1.size
                        val rIdxR1 = (tr1 - (tankR1.size - 35 - modOffset) + tankR1.size) % tankR1.size

                        val tOutL1 = tankL1[rIdxL1]
                        val tOutR1 = tankR1[rIdxR1]
                        val tOutL2 = tankL2[tl2]
                        val tOutR2 = tankR2[tr2]

                        // High-Frequency Damping im Hall-Tank (warmer Ausklang wie Valhalla VintageVerb)
                        tankLpL += 0.38f * (tOutR2 - tankLpL)
                        tankLpR += 0.38f * (tOutL2 - tankLpR)

                        val decay = (0.72f + reverbMix * 0.16f).coerceIn(0.65f, 0.89f)
                        tankL1[tl1] = (diff + tankLpL * decay).coerceIn(-1.2f, 1.2f); tl1 = (tl1 + 1) % tankL1.size
                        tankR1[tr1] = (diff + tankLpR * decay).coerceIn(-1.2f, 1.2f); tr1 = (tr1 + 1) % tankR1.size
                        tankL2[tl2] = (tOutL1 * decay).coerceIn(-1.2f, 1.2f); tl2 = (tl2 + 1) % tankL2.size
                        tankR2[tr2] = (tOutR1 * decay).coerceIn(-1.2f, 1.2f); tr2 = (tr2 + 1) % tankR2.size

                        val wetL = (tOutL1 + tOutL2 * 0.8f) * 0.36f
                        val wetR = (tOutR1 + tOutR2 * 0.8f) * 0.36f
                        finalL += wetL
                        finalR += wetR
                    }

                    // DC-Blocker & Master Analog Soft-Limiter
                    val cleanL = finalL - dcInL + 0.995f * dcOutL; dcInL = finalL; dcOutL = cleanL
                    val cleanR = finalR - dcInR + 0.995f * dcOutR; dcInR = finalR; dcOutR = cleanR

                    val outL = tanh(cleanL * 1.15f) * 0.92f
                    val outR = tanh(cleanR * 1.15f) * 0.92f
                    if (i < scopeBuffer.size) scopeBuffer[i] = (outL + outR) * 0.5f

                    floatStereoBuffer[i * 2] = outL
                    floatStereoBuffer[i * 2 + 1] = outR
                }
                track.write(floatStereoBuffer, 0, floatStereoBuffer.size, AudioTrack.WRITE_BLOCKING)
            }
            try { track.stop(); track.release() } catch (_: Exception) {}
        }
    }

    // Andy Simper (Cytomic) Zero-Delay Feedback (ZDF) Trapezoidal SVF Lowpass Filter
    private fun processCytomicZdfLowpass(v: Int, input: Float, voiceFreq: Float, fltEnv: Float, k: Float): Float {
        val wheelNorm = filterWheel.coerceIn(0.05f, 1.0f)
        val envMod = fltEnv * filterEnvAmount * 0.48f
        val cutoffNorm = (wheelNorm + envMod).coerceIn(0.05f, 0.98f)
        // Logarithmische Frequenzskala von 90 Hz bis 16.500 Hz + Key-Tracking
        val baseHz = 90f * (180f.pow(cutoffNorm)) + (voiceFreq * 0.35f)
        val fc = baseHz.coerceIn(80f, 17500f)

        val g = tan(PI.toFloat() * (fc / sampleRate)).coerceIn(0.002f, 1.25f)
        val a1 = 1.0f / (1.0f + g * (g + k))
        val a2 = g * a1
        val a3 = g * a2

        // Leichte Sättigung im Filter-Eingang für analoge Wärme
        val v0 = tanh(input * 1.1f)
        val v3 = v0 - zdfIc2[v]
        val v1 = a1 * zdfIc1[v] + a2 * v3
        val v2 = zdfIc2[v] + a2 * zdfIc1[v] + a3 * v3

        zdfIc1[v] = 2.0f * v1 - zdfIc1[v]
        zdfIc2[v] = 2.0f * v2 - zdfIc2[v]

        return v2 // 12dB/Oct Zero-Delay Lowpass Output
    }

    private fun stepVoiceEnvelope(
        v: Int,
        attackStep: Float,
        decayMul: Float,
        releaseMul: Float,
        targetSustain: Float,
        fltDecayMul: Float,
        tineDecayMul: Float
    ) {
        when (voiceStage[v]) {
            1 -> {
                voiceAmpEnv[v] += attackStep
                if (voiceAmpEnv[v] >= 1.0f) {
                    voiceAmpEnv[v] = 1.0f
                    voiceStage[v] = 2
                }
            }
            2 -> {
                voiceAmpEnv[v] = targetSustain + (voiceAmpEnv[v] - targetSustain) * decayMul
                if (abs(voiceAmpEnv[v] - targetSustain) < 0.0005f) {
                    voiceAmpEnv[v] = targetSustain
                    voiceStage[v] = if (targetSustain <= 0.001f) 0 else 3
                }
            }
            3 -> {
                voiceAmpEnv[v] = targetSustain
                if (!gate) voiceStage[v] = 4
            }
            4 -> {
                voiceAmpEnv[v] *= releaseMul
                if (voiceAmpEnv[v] < 0.0002f) {
                    voiceAmpEnv[v] = 0f
                    voiceStage[v] = 0
                }
            }
        }
        voiceFltEnv[v] *= fltDecayMul
        voiceTineEnv[v] *= tineDecayMul
    }

    // --- 6 KOHÄRENTE STUDIO-SYNTHESE-ENGINES ---
    private fun renderStudioVoice(
        v: Int,
        vc: VoiceConfig,
        freq: Float,
        ampEnv: Float,
        fltEnv: Float,
        tineEnv: Float,
        pwmMod: Float,
        spreadAmt: Float,
        hyperSawOffsets: FloatArray
    ): Float {
        val dt = (freq / sampleRate).coerceIn(0.0001f, 0.45f)
        val pArr = oscPhases[v]
        pArr[0] = (pArr[0] + dt) % 1f
        val p0 = pArr[0]
        val twoPi = 2f * PI.toFloat()

        val sig = when (vc.engine) {
            0 -> { // CLEAN POLY: Warm PolyBLEP Saw + Triangle Foundation + Gentle Detune Pair (Perfekt für INIT NEUTRAL!)
                val dt2 = (dt * (1f + 0.0032f * spreadAmt)).coerceIn(0.0001f, 0.45f)
                pArr[1] = (pArr[1] + dt2) % 1f
                val saw1 = (2f * p0 - 1f) - polyBlep(p0, dt)
                val saw2 = (2f * pArr[1] - 1f) - polyBlep(pArr[1], dt2)
                val tri = 1f - 4f * abs(round(p0 - 0.25f) - (p0 - 0.25f))
                val sawMix = 0.35f + vc.timbre * 0.55f
                ((saw1 + saw2) * 0.5f * sawMix + tri * (1f - sawMix * 0.5f)) * 0.72f
            }
            1 -> { // JUNO PWM: Classic Roland Juno-60 Simultaneous Saw + Modulated Pulse + Sub-Oscillator
                val pw = (0.5f + pwmMod * (0.4f + vc.timbre * 0.5f)).coerceIn(0.12f, 0.88f)
                var pulse = if (p0 < pw) 1f else -1f
                pulse += polyBlep(p0, dt)
                pulse -= polyBlep((p0 + 1f - pw) % 1f, dt)

                val saw = (2f * p0 - 1f) - polyBlep(p0, dt)
                pArr[1] = (pArr[1] + dt * 0.5f) % 1f
                val sub = sin(pArr[1] * twoPi) * 0.35f
                (pulse * 0.52f + saw * 0.48f + sub) * 0.68f
            }
            2 -> { // HYPERSAW: True 7-Oscillator Detuned SuperSaw Cluster (Für Rage, Trap & Wide Pads)
                var sum = 0f
                val detScale = 0.35f + vc.timbre * 0.85f
                for (s in 0..6) {
                    val sDt = (dt * (1f + hyperSawOffsets[s] * detScale)).coerceIn(0.0001f, 0.45f)
                    if (s > 0) pArr[s] = (pArr[s] + sDt) % 1f
                    val ps = pArr[s]
                    val weight = if (s == 3) 1.0f else 0.65f
                    sum += ((2f * ps - 1f) - polyBlep(ps, sDt)) * weight
                }
                sum * 0.24f
            }
            3 -> { // DX TINE EP: Authentic Rhodes Mk1 Electric Piano (Warm 1:1 Bark + Separate Decaying Tine Bell)
                val ratio = fmRatios[vc.fmRatioIndex.coerceIn(0, fmRatios.lastIndex)]
                modPhases[v] = (modPhases[v] + dt * ratio) % 1f
                tinePhases[v] = (tinePhases[v] + dt * 7.0f) % 1f

                val barkIdx = (0.18f + vc.timbre * 1.4f) * (0.25f + 0.75f * fltEnv)
                val barkMod = sin(modPhases[v] * twoPi) * barkIdx
                val carrier = sin(p0 * twoPi + barkMod)

                // Kurzer Tine-Glocken-Anschlag (verschwindet nach 130ms -> null Orgel-Klang!)
                val tine = sin(tinePhases[v] * twoPi + barkMod * 0.4f) * tineEnv * (0.22f + vc.timbre * 0.35f)
                (carrier * 0.86f + tine)
            }
            4 -> { // MODAL BELL: Glassy / Dark Trap Bell Resonator (1:2 / 1:3.5 + Crisp Mallet Transient)
                val ratio = if (vc.fmRatioIndex == 3) 3.5f else fmRatios[vc.fmRatioIndex.coerceIn(0, fmRatios.lastIndex)]
                modPhases[v] = (modPhases[v] + dt * ratio) % 1f
                tinePhases[v] = (tinePhases[v] + dt * 5.4f) % 1f

                val modIdx = (0.25f + vc.timbre * 2.2f) * fltEnv
                val modSig = sin(modPhases[v] * twoPi) * modIdx
                val body = sin(p0 * twoPi + modSig)
                val malletStrike = sin(tinePhases[v] * twoPi) * tineEnv * tineEnv * 0.38f
                (body * 0.85f + malletStrike)
            }
            else -> { // KARPLUS STR: Physical Waveguide Plucked String (Harp, Guitar & Acoustic Piano)
                stepKarplusStrong(v, freq, brightness = vc.timbre)
            }
        }

        return sig * 0.25f * vc.level * ampEnv
    }

    private fun stepKarplusStrong(v: Int, freq: Float, brightness: Float): Float {
        val line = ksDelayLines[v]
        val wPos = ksWritePos[v]

        val exactPeriod = (sampleRate / freq.coerceAtLeast(35f)).coerceIn(16f, (line.size - 4).toFloat())
        val intPeriod = exactPeriod.toInt()
        val frac = exactPeriod - intPeriod

        val rPos1 = (wPos - intPeriod + line.size) % line.size
        val rPos2 = (rPos1 - 1 + line.size) % line.size
        val delayedSample = line[rPos1] * (1f - frac) + line[rPos2] * frac

        val lpCoeff = (0.35f + brightness * 0.58f).coerceIn(0.25f, 0.94f)
        ksLpState[v] += lpCoeff * (delayedSample - ksLpState[v])

        var excite = 0f
        if (ksExciteRemaining[v] > 0) {
            ksExciteRemaining[v]--
            excite = (Random.nextFloat() * 2f - 1f) * (0.25f + brightness * 0.35f)
        }

        val newSample = (ksLpState[v] * 0.9945f + excite).coerceIn(-1.5f, 1.5f)
        line[wPos] = newSample
        ksWritePos[v] = (wPos + 1) % line.size

        return newSample * 1.25f
    }

    @Synchronized
    fun stop() {
        isRunning = false
        audioThread = null
    }
}

// --- MUSIKTHEORIE: DUR (MAJOR) & MOLL (MINOR) + INVERSIONS + 8-WEGE JOYSTICK ---
val rootNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

val majorScaleOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
val majorDefaultQuality = intArrayOf(0, 1, 1, 0, 0, 1, 2)
val majorRomanDegrees = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")

val minorScaleOffsets = intArrayOf(0, 2, 3, 5, 7, 8, 10)
val minorDefaultQuality = intArrayOf(1, 2, 0, 1, 1, 0, 0)
val minorRomanDegrees = listOf("i", "ii°", "III", "iv", "v", "VI", "VII")

fun midiToFreq(midi: Int): Float = 440f * 2f.pow((midi - 69) / 12f)

data class ChordVoicingResult(
    val displayName: String,
    val voiceFreqs: FloatArray,
    val bassFreq: Float
)

fun buildHiChordVoicing(
    keyIndex: Int,
    isMinorScale: Boolean,
    degree: Int,
    octaveShift: Int,
    joyZone: JoyChordZone,
    inversionMode: Int,
    bassMode: Int
): ChordVoicingResult {
    val offsets = if (isMinorScale) minorScaleOffsets else majorScaleOffsets
    val qualities = if (isMinorScale) minorDefaultQuality else majorDefaultQuality

    val rootSemitone = keyIndex + offsets[degree]
    val rootMidiBase = 60 + rootSemitone + (octaveShift * 12)
    val noteName = rootNames[rootSemitone % 12]

    var qual = qualities[degree]
    var third = if (qual == 0) 4 else 3
    var fifth = if (qual == 2) 6 else 7
    var fourthNote = 12
    var chordLabelSuffix = when (qual) {
        1 -> "m"
        2 -> "dim"
        else -> ""
    }

    when (joyZone) {
        JoyChordZone.CENTER -> {}
        JoyChordZone.UP -> {
            qual = if (qual == 0) 1 else 0
            third = if (qual == 0) 4 else 3
            fifth = 7
            chordLabelSuffix = if (qual == 1) "m" else "MAJ"
        }
        JoyChordZone.UP_RIGHT -> {
            third = 4; fifth = 7; fourthNote = 10
            chordLabelSuffix = "7"
        }
        JoyChordZone.RIGHT -> {
            fourthNote = if (qual == 0) 11 else 10
            chordLabelSuffix = if (qual == 0) "maj7" else "m7"
        }
        JoyChordZone.DOWN_RIGHT -> {
            fourthNote = 14
            chordLabelSuffix = if (qual == 1) "m9" else "add9"
        }
        JoyChordZone.DOWN -> {
            third = 5; fifth = 7
            chordLabelSuffix = "sus4"
        }
        JoyChordZone.DOWN_LEFT -> {
            fourthNote = 9
            chordLabelSuffix = if (qual == 1) "m6" else "6"
        }
        JoyChordZone.LEFT -> {
            third = 3; fifth = 6; fourthNote = 9
            chordLabelSuffix = "dim7"
        }
        JoyChordZone.UP_LEFT -> {
            third = 4; fifth = 8
            chordLabelSuffix = "aug"
        }
    }

    val midiNotes = intArrayOf(
        rootMidiBase,
        rootMidiBase + third,
        rootMidiBase + fifth,
        rootMidiBase + fourthNote
    )

    when (inversionMode) {
        0 -> { // AUTO VOICE-LEADING (Hält alle Akkordtöne butterweich im selben Stimmführungs-Fenster)
            val centerTarget = 64 + (octaveShift * 12)
            for (i in 0..3) {
                while (midiNotes[i] - centerTarget > 6) midiNotes[i] -= 12
                while (centerTarget - midiNotes[i] > 6) midiNotes[i] += 12
            }
            midiNotes.sort()
        }
        2 -> {
            midiNotes[0] += 12
            midiNotes.sort()
        }
        3 -> {
            midiNotes[0] += 12
            midiNotes[1] += 12
            midiNotes.sort()
        }
    }

    var slashSuffix = ""
    val bassMidi = when (bassMode) {
        1 -> rootMidiBase - 12
        2 -> {
            val slashInterval = if (qual == 1) third else fifth
            val slashNoteName = rootNames[(rootSemitone + slashInterval) % 12]
            slashSuffix = "/$slashNoteName"
            rootMidiBase + slashInterval - 12
        }
        else -> 0
    }

    val freqs = FloatArray(4) { i -> midiToFreq(midiNotes[i]) }
    val bFreq = if (bassMidi > 0) midiToFreq(bassMidi) else 0f

    return ChordVoicingResult("$noteName$chordLabelSuffix$slashSuffix", freqs, bFreq)
}

@Composable
fun TeHiChordApp(synth: HiChordProEngine) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hichord_pro_presets", Context.MODE_PRIVATE) }
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    var keyIndex by remember { mutableStateOf(0) }
    var isMinorScale by remember { mutableStateOf(false) }
    var bpm by remember { mutableStateOf(120) }
    var activeDegree by remember { mutableStateOf(-1) }
    var displayChord by remember { mutableStateOf("HI-CHORD") }

    var inversionMode by remember { mutableStateOf(0) }
    var bassMode by remember { mutableStateOf(1) }
    var octaveShift by remember { mutableStateOf(0) }
    var holdLatch by remember { mutableStateOf(false) }
    var strumMode by remember { mutableStateOf(false) }
    var filterWheel by remember { mutableStateOf(0.65f) }

    var waveIndex by remember { mutableStateOf(0) }
    var adsrMode by remember { mutableStateOf(0) }
    var arpMode by remember { mutableStateOf(0) }
    var activeSlot by remember { mutableStateOf(0) }
    var uiRefreshCounter by remember { mutableStateOf(0) }

    val userPresets = remember {
        val list = mutableStateListOf<SynthPreset>()
        val rawSet = prefs.getStringSet("user_presets_pro_v1", emptySet()) ?: emptySet()
        rawSet.mapNotNull { SynthPreset.deserialize(it) }
            .sortedBy { it.name }
            .forEach { list.add(it) }
        list
    }

    // 4 Schnellwahl-Slots: Startet mit dem ehrlichen, neutralen "INIT NEUTRAL" Preset auf Slot 1!
    val slotPresets = remember {
        mutableStateListOf(
            factoryPresets[0],  // Slot 1: INIT NEUTRAL (Default Clean Poly)
            factoryPresets[1],  // Slot 2: HICHORD KEYS (Rhodes E-Piano)
            factoryPresets[4],  // Slot 3: CLOUD CATHEDRAL (Cloudrap Shimmer Pad)
            factoryPresets[5]   // Slot 4: OPIUM RAGE SAW (7-Osc Hypersaw)
        )
    }

    LaunchedEffect(Unit) {
        val initial = slotPresets[0]
        synth.loadSynthPreset(initial)
        adsrMode = initial.adsrMode
        filterWheel = initial.baseCutoff
        waveIndex = initial.voices[0].engine
        uiRefreshCounter++
    }

    fun saveUserPresetsToDisk() {
        val serialized = userPresets.map { it.serialize() }.toSet()
        prefs.edit().putStringSet("user_presets_pro_v1", serialized).apply()
    }

    var joyX by remember { mutableStateOf(0f) }
    var joyY by remember { mutableStateOf(0f) }
    val activeJoyZone = getJoystickZone(joyX, joyY)

    fun syncChordState(deg: Int, retriggerAttack: Boolean) {
        activeDegree = deg
        if (deg in 0..6) {
            val res = buildHiChordVoicing(keyIndex, isMinorScale, deg, octaveShift, activeJoyZone, inversionMode, bassMode)
            displayChord = res.displayName
            synth.playChord(res.voiceFreqs, res.bassFreq, retriggerAttack)
        } else {
            synth.releaseChord()
        }
    }

    LaunchedEffect(keyIndex, isMinorScale, octaveShift, activeJoyZone, inversionMode, bassMode) {
        if (activeDegree in 0..6) {
            val res = buildHiChordVoicing(keyIndex, isMinorScale, activeDegree, octaveShift, activeJoyZone, inversionMode, bassMode)
            displayChord = res.displayName
            synth.playChord(res.voiceFreqs, res.bassFreq, retriggerAttack = false)
        }
    }

    var frameTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30)
            frameTick++
        }
    }

    if (isLandscape) {
        TeExpertLandscapeScreen(
            synth = synth,
            keyIndex = keyIndex,
            isMinorScale = isMinorScale,
            activeDegree = activeDegree,
            onTriggerDegree = { deg, retrigger -> syncChordState(deg, retrigger) },
            holdLatch = holdLatch,
            onToggleHold = {
                holdLatch = !holdLatch
                if (!holdLatch) syncChordState(-1, false)
            },
            activeSlot = activeSlot,
            slotPresets = slotPresets,
            userPresets = userPresets,
            onSelectSlot = { s ->
                activeSlot = s
                val p = slotPresets[s]
                synth.loadSynthPreset(p)
                adsrMode = p.adsrMode
                filterWheel = p.baseCutoff
                waveIndex = p.voices[0].engine
                uiRefreshCounter++
            },
            onAssignPresetToSlot = { preset, slot ->
                slotPresets[slot] = preset
                activeSlot = slot
                synth.loadSynthPreset(preset)
                adsrMode = preset.adsrMode
                filterWheel = preset.baseCutoff
                waveIndex = preset.voices[0].engine
                uiRefreshCounter++
            },
            onSaveNewUserPreset = {
                val nextNum = userPresets.size + 1
                val id = "u_${System.currentTimeMillis()}"
                val name = "USER PATCH ${nextNum.toString().padStart(2, '0')}"
                val newPreset = synth.snapshotToPreset(id, name)
                userPresets.add(newPreset)
                saveUserPresetsToDisk()
                slotPresets[activeSlot] = newPreset
                uiRefreshCounter++
            },
            onDeleteUserPreset = { preset ->
                userPresets.removeAll { it.id == preset.id }
                for (s in 0..3) {
                    if (slotPresets[s].id == preset.id) {
                        slotPresets[s] = factoryPresets[0]
                        if (activeSlot == s) {
                            synth.loadSynthPreset(factoryPresets[0])
                            adsrMode = factoryPresets[0].adsrMode
                            filterWheel = factoryPresets[0].baseCutoff
                        }
                    }
                }
                saveUserPresetsToDisk()
                uiRefreshCounter++
            },
            onSyncFilterWheel = { filterWheel = it },
            uiRefreshCounter = uiRefreshCounter,
            onParamChanged = { uiRefreshCounter++ },
            frameTick = frameTick,
            displayChord = displayChord
        )
    } else {
        TePortraitJamScreen(
            synth = synth,
            keyIndex = keyIndex,
            onKeyChange = { keyIndex = it },
            isMinorScale = isMinorScale,
            onToggleMajorMinor = { isMinorScale = !isMinorScale },
            bpm = bpm,
            onBpmChange = { bpm = it; synth.bpm = it },
            activeDegree = activeDegree,
            onTriggerDegree = { deg, retrigger -> syncChordState(deg, retrigger) },
            displayChord = displayChord,
            activePresetName = slotPresets[activeSlot].name,
            activePresetCategory = slotPresets[activeSlot].category,
            onCycleSlot = {
                val next = (activeSlot + 1) % 4
                activeSlot = next
                val p = slotPresets[next]
                synth.loadSynthPreset(p)
                adsrMode = p.adsrMode
                filterWheel = p.baseCutoff
                waveIndex = p.voices[0].engine
                uiRefreshCounter++
            },
            inversionMode = inversionMode,
            onCycleInversion = { inversionMode = (inversionMode + 1) % 4 },
            bassMode = bassMode,
            onCycleBassMode = {
                bassMode = (bassMode + 1) % 3
                synth.bassMode = bassMode
            },
            octaveShift = octaveShift,
            onOctaveChange = { octaveShift = it },
            holdLatch = holdLatch,
            onToggleHold = {
                holdLatch = !holdLatch
                if (!holdLatch) syncChordState(-1, false)
            },
            strumMode = strumMode,
            onToggleStrum = { strumMode = !strumMode; synth.strumMode = strumMode },
            filterWheel = filterWheel,
            onFilterWheelChange = {
                filterWheel = it
                synth.filterWheel = it
            },
            waveIndex = waveIndex,
            onWaveChange = { waveIndex = it; synth.setGlobalWaveform(it) },
            adsrMode = adsrMode,
            onAdsrChange = {
                adsrMode = it
                synth.applyAdsrMacro(it)
            },
            arpMode = arpMode,
            onArpChange = { arpMode = it; synth.arpMode = it },
            joyX = joyX,
            joyY = joyY,
            activeJoyZone = activeJoyZone,
            onJoyChange = { x, y ->
                joyX = x
                joyY = y
            },
            uiRefreshCounter = uiRefreshCounter,
            frameTick = frameTick
        )
    }
}

// --- 90° QUERFORMAT: 3-TAB STUDIO WORKSTATION (SYNTH STUDIO / 4-VOICE LAB / PRESET BANK) ---
@Composable
fun TeExpertLandscapeScreen(
    synth: HiChordProEngine,
    keyIndex: Int,
    isMinorScale: Boolean,
    activeDegree: Int,
    onTriggerDegree: (Int, Boolean) -> Unit,
    holdLatch: Boolean,
    onToggleHold: () -> Unit,
    activeSlot: Int,
    slotPresets: List<SynthPreset>,
    userPresets: List<SynthPreset>,
    onSelectSlot: (Int) -> Unit,
    onAssignPresetToSlot: (SynthPreset, Int) -> Unit,
    onSaveNewUserPreset: () -> Unit,
    onDeleteUserPreset: (SynthPreset) -> Unit,
    onSyncFilterWheel: (Float) -> Unit,
    uiRefreshCounter: Int,
    onParamChanged: () -> Unit,
    frameTick: Int,
    displayChord: String
) {
    val view = LocalView.current
    // 0 = SYNTH STUDIO (Cohesive Patch Editor), 1 = 4-VOICE LAB (Per-Voice Mixer), 2 = PRESET BANK
    var labPage by remember { mutableStateOf(0) }
    val pageNames = listOf("MODE: STUDIO", "MODE: 4-VOICE", "MODE: PRESETS")
    val voiceTitles = listOf("V1 // ROOT", "V2 // 3RD", "V3 // 5TH", "V4 // 7TH")
    val romanDegrees = if (isMinorScale) minorRomanDegrees else majorRomanDegrees
    val scaleOffsets = if (isMinorScale) minorScaleOffsets else majorScaleOffsets
    val scaleQuals = if (isMinorScale) minorDefaultQuality else majorDefaultQuality
    val refresh = uiRefreshCounter

    val currentOnTrigger by rememberUpdatedState(onTriggerDegree)
    val currentHold by rememberUpdatedState(holdLatch)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TeChassis)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. TOP BAR: OLED + 4 QUICK SLOTS + PAGE SWITCHER + SAVE
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TePanelDark)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = pageNames[labPage],
                            color = TeOrange,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = displayChord,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Canvas(modifier = Modifier.width(55.dp).height(24.dp)) {
                        val tick = frameTick
                        val path = Path()
                        val stepX = size.width / synth.scopeBuffer.size
                        val midY = size.height / 2f
                        for (i in synth.scopeBuffer.indices) {
                            val x = i * stepX
                            val y = midY - (synth.scopeBuffer[i] * midY * 0.9f)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color = Color(0xFF00E5FF), style = Stroke(width = 2.5f))
                    }
                }
            }

            for (s in 0..3) {
                val p = slotPresets[s]
                TeTactileButton(
                    title = p.name.take(10),
                    sub = "SLOT 0${s + 1} · ${p.category.take(7)}",
                    modifier = Modifier.weight(0.85f),
                    height = 44.dp,
                    bgColor = if (activeSlot == s) TeOrange else TeKeyDark,
                    shadowColor = if (activeSlot == s) TeOrangeShadow else TeKeyDarkShadow,
                    textColor = Color.White,
                    ledOn = activeSlot == s,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onSelectSlot(s)
                    }
                )
            }

            TeTactileButton(
                title = when (labPage) { 0 -> "4-VOICES"; 1 -> "PRESETS"; else -> "STUDIO" },
                sub = "SWITCH VIEW",
                modifier = Modifier.weight(0.82f),
                height = 44.dp,
                bgColor = TeOchre,
                shadowColor = Color(0xFFA67621),
                textColor = TePanelDark,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    labPage = (labPage + 1) % 3
                }
            )

            TeTactileButton(
                title = "+ SAVE",
                sub = "USER PATCH",
                modifier = Modifier.weight(0.70f),
                height = 44.dp,
                bgColor = TeKeyDark,
                shadowColor = TeKeyDarkShadow,
                textColor = Color(0xFF00E5FF),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onSaveNewUserPreset()
                }
            )
        }

        // 2. MITTE: STUDIO MACRO EDITOR (Page 0) / 4-VOICE MIXER (Page 1) / PRESET BANK (Page 2)
        when (labPage) {
            0 -> {
                val unused = refresh
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Spalte 1: OSCILLATOR & UNISON (Steuert alle 4 Stimmen kohärent!)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(TePanelDark).border(1.5.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("01 // OSCILLATOR", color = TeOchre, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(TeOrange).clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    val nextEng = (synth.voices[0].engine + 1) % engineNames.size
                                    synth.setGlobalWaveform(nextEng)
                                    onParamChanged()
                                }.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("ENG: ${engineNames[synth.voices[0].engine]}", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            }
                        }
                        TeBarSlider(
                            label = "TIMBRE / BRIGHTNESS",
                            valueText = "${(synth.voices[0].timbre * 100).roundToInt()}%",
                            fraction = synth.voices[0].timbre,
                            barColor = Color(0xFF00E5FF),
                            onValueChange = {
                                for (v in 0..3) synth.voices[v].timbre = it
                                onParamChanged()
                            }
                        )
                        TeBarSlider(
                            label = "UNISON DETUNE & SPREAD",
                            valueText = "${(synth.unisonSpread * 100).roundToInt()}%",
                            fraction = synth.unisonSpread,
                            barColor = TeOchre,
                            onValueChange = { synth.unisonSpread = it; onParamChanged() }
                        )
                        TeBarSlider(
                            label = "PORTAMENTO GLIDE",
                            valueText = if (synth.glideSpeed < 0.008f) "LONG" else if (synth.glideSpeed < 0.025f) "MED" else "FAST",
                            fraction = (1f - (synth.glideSpeed / 0.09f)).coerceIn(0.05f, 1f),
                            barColor = TeOrange,
                            onValueChange = {
                                synth.glideSpeed = ((1f - it) * 0.085f).coerceIn(0.001f, 0.09f)
                                onParamChanged()
                            }
                        )
                    }

                    // Spalte 2: ZDF ANALOG FILTER
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(TePanelDark).border(1.5.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("02 // ZDF LADDER FILTER", color = TeOchre, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        TeBarSlider(
                            label = "FILTER CUTOFF",
                            valueText = "${(synth.filterWheel * 100).roundToInt()}%",
                            fraction = synth.filterWheel,
                            barColor = TeOrange,
                            onValueChange = {
                                synth.filterWheel = it
                                synth.baseCutoff = it
                                onSyncFilterWheel(it)
                                onParamChanged()
                            }
                        )
                        TeBarSlider(
                            label = "RESONANCE (Q)",
                            valueText = "${(synth.filterResonance * 100).roundToInt()}%",
                            fraction = synth.filterResonance / 0.85f,
                            barColor = Color(0xFF00E5FF),
                            onValueChange = { synth.filterResonance = (it * 0.85f).coerceIn(0f, 0.85f); onParamChanged() }
                        )
                        TeBarSlider(
                            label = "ENVELOPE PUNCH (ENV MOD)",
                            valueText = "${(synth.filterEnvAmount * 100).roundToInt()}%",
                            fraction = synth.filterEnvAmount,
                            barColor = Color(0xFF4CAF50),
                            onValueChange = { synth.filterEnvAmount = it; onParamChanged() }
                        )
                    }

                    // Spalte 3: ADSR ENVELOPE
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(TePanelDark).border(1.5.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("03 // ADSR ENVELOPE", color = TeOchre, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        TeBarSlider(
                            label = "ATTACK TIME",
                            valueText = "${synth.attackMs.roundToInt()}ms",
                            fraction = (synth.attackMs / 800f).coerceIn(0f, 1f),
                            barColor = Color(0xFF00E5FF),
                            onValueChange = { synth.attackMs = (1.2f + it * 800f); onParamChanged() }
                        )
                        TeBarSlider(
                            label = "DECAY TIME",
                            valueText = "${synth.decayMs.roundToInt()}ms",
                            fraction = (synth.decayMs / 2000f).coerceIn(0f, 1f),
                            barColor = TeOchre,
                            onValueChange = { synth.decayMs = (150f + it * 1850f); onParamChanged() }
                        )
                        TeBarSlider(
                            label = "SUSTAIN LEVEL",
                            valueText = "${(synth.sustainLevel * 100).roundToInt()}%",
                            fraction = synth.sustainLevel,
                            barColor = Color(0xFF4CAF50),
                            onValueChange = {
                                synth.sustainLevel = it
                                synth.adsrMode = if (it < 0.03f) 2 else 0
                                onParamChanged()
                            }
                        )
                        TeBarSlider(
                            label = "RELEASE TIME",
                            valueText = "${synth.releaseMs.roundToInt()}ms",
                            fraction = (synth.releaseMs / 1500f).coerceIn(0f, 1f),
                            barColor = TeOrange,
                            onValueChange = { synth.releaseMs = (80f + it * 1420f); onParamChanged() }
                        )
                    }

                    // Spalte 4: LO-FI VINTAGE TAPE & DATTORRO SPACE
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(TePanelDark).border(1.5.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("04 // TAPE & SPACE", color = TeOchre, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (synth.lofiMode) TeOrange else Color(0xFF333333)).clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    synth.lofiMode = !synth.lofiMode
                                    onParamChanged()
                                }.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(if (synth.lofiMode) "LO-FI: ON" else "LO-FI: OFF", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            }
                        }
                        TeBarSlider(
                            label = "LO-FI TAPE WARBLE & AGE",
                            valueText = "${(synth.lofiAmount * 100).roundToInt()}%",
                            fraction = synth.lofiAmount,
                            barColor = TeOrange,
                            onValueChange = {
                                synth.lofiAmount = it
                                if (it > 0.08f) synth.lofiMode = true
                                onParamChanged()
                            }
                        )
                        TeBarSlider(
                            label = "DATTORRO CLOUD REVERB",
                            valueText = "${(synth.reverbMix * 100).roundToInt()}%",
                            fraction = synth.reverbMix,
                            barColor = Color(0xFF00E5FF),
                            onValueChange = { synth.reverbMix = it; synth.fxReverb = it > 0.02f; onParamChanged() }
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TeTactileButton(
                                title = "CHORUS", sub = "BBD", modifier = Modifier.weight(1f), height = 36.dp,
                                bgColor = if (synth.fxChorus) TeBlue else TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = synth.fxChorus,
                                onClick = { synth.fxChorus = !synth.fxChorus; onParamChanged() }
                            )
                            TeTactileButton(
                                title = "DELAY", sub = "TAPE", modifier = Modifier.weight(1f), height = 36.dp,
                                bgColor = if (synth.fxDelay) TeBlue else TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = synth.fxDelay,
                                onClick = { synth.fxDelay = !synth.fxDelay; onParamChanged() }
                            )
                        }
                    }
                }
            }
            1 -> {
                val unused = refresh
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (v in 0..3) {
                        val vc = synth.voices[v]
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(TePanelDark).border(1.5.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp)).padding(8.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(voiceTitles[v], color = Color(0xFF9E9C95), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)).clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            vc.octShift = when (vc.octShift) { -1 -> 0; 0 -> 1; else -> -1 }
                                            onParamChanged()
                                        }.padding(horizontal = 5.dp, vertical = 3.dp)
                                    ) {
                                        Text("OCT:${if (vc.octShift >= 0) "+${vc.octShift}" else vc.octShift}", color = TeOchre, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    }
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(TeOrange).clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            vc.engine = (vc.engine + 1) % engineNames.size
                                            onParamChanged()
                                        }.padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(engineNames[vc.engine], color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                            TeBarSlider(label = "VOICE TIMBRE", valueText = "${(vc.timbre * 100).roundToInt()}%", fraction = vc.timbre, barColor = Color(0xFF00E5FF), onValueChange = { vc.timbre = it; onParamChanged() })
                            TeBarSlider(label = "DETUNE", valueText = "${if (vc.detuneCents >= 0) "+${vc.detuneCents}" else vc.detuneCents}ct", fraction = (vc.detuneCents + 50f) / 100f, barColor = TeOchre, onValueChange = { vc.detuneCents = ((it * 100f) - 50f).roundToInt(); onParamChanged() })
                            val panInt = (vc.pan * 100).roundToInt()
                            val panLabel = when { panInt < -5 -> "L${-panInt}"; panInt > 5 -> "R$panInt"; else -> "CTR" }
                            TeBarSlider(label = "STEREO PAN", valueText = panLabel, fraction = (vc.pan + 1f) * 0.5f, barColor = TeOrange, onValueChange = { vc.pan = (it * 2f) - 1f; onParamChanged() })
                            TeBarSlider(label = "VOICE LEVEL", valueText = "${(vc.level * 100).roundToInt()}%", fraction = vc.level, barColor = Color(0xFF4CAF50), onValueChange = { vc.level = it; onParamChanged() })
                        }
                    }
                }
            }
            else -> {
                val allPresets = remember(userPresets.size, refresh) {
                    userPresets + factoryPresets
                }
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(TePanelDark).border(1.5.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp)).padding(8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("SELECT PRESET TO LOAD INTO SLOT 0${activeSlot + 1} (DEFAULT, HICHORD OG, LO-FI, CLOUDRAP, RAGE, TRAP)", color = Color(0xFF00E5FF), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("USER PATCHES: ${userPresets.size}", color = TeOchre, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(allPresets, key = { it.id }) { preset ->
                            val isLoaded = slotPresets[activeSlot].id == preset.id
                            Box(
                                modifier = Modifier.height(54.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if (isLoaded) TeOrange else if (preset.isUser) Color(0xFF16324A) else Color(0xFF262624))
                                    .border(1.dp, if (isLoaded) Color.White else Color(0xFF484844), RoundedCornerShape(6.dp))
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        onAssignPresetToSlot(preset, activeSlot)
                                    }.padding(6.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(preset.category, color = if (isLoaded) Color.White else if (preset.isUser) Color(0xFF00E5FF) else TeOchre, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                        if (preset.isUser) {
                                            Box(
                                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(TeLedRed).clickable {
                                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    onDeleteUserPreset(preset)
                                                }.padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("DELETE", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                    }
                                    Text(preset.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. BOTTOM AUDITION KEYBOARD
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TeTactileButton(
                title = "HOLD", sub = "LATCH", modifier = Modifier.weight(0.95f), height = 46.dp,
                bgColor = if (holdLatch) TeBlue else TeKeyDark, shadowColor = if (holdLatch) TeBlueShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = holdLatch,
                onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleHold() }
            )

            var stripWidth by remember { mutableStateOf(1) }
            Row(
                modifier = Modifier.weight(7f).fillMaxHeight().onSizeChanged { stripWidth = it.width.coerceAtLeast(1) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            fun mapXToDeg(x: Float): Int {
                                val normX = (x / stripWidth.toFloat()).coerceIn(0f, 0.999f)
                                return (normX * 7f).toInt().coerceIn(0, 6)
                            }
                            var curDeg = mapXToDeg(down.position.x)
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            currentOnTrigger(curDeg, true)

                            do {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed }
                                if (ch != null) {
                                    ch.consume()
                                    val newDeg = mapXToDeg(ch.position.x)
                                    if (newDeg != curDeg) {
                                        curDeg = newDeg
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        currentOnTrigger(newDeg, synth.arpMode == 0)
                                    }
                                }
                            } while (ev.changes.any { it.pressed })

                            if (!currentHold) {
                                currentOnTrigger(-1, false)
                            }
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (deg in 0..6) {
                    val noteLabel = rootNames[(keyIndex + scaleOffsets[deg]) % 12]
                    val qualSuffix = when (scaleQuals[deg]) { 1 -> "m"; 2 -> "°"; else -> "" }
                    val isAct = activeDegree == deg
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(top = if (isAct) 2.dp else 0.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (isAct) TeOrangeShadow else TeKeyLightShadow).padding(bottom = if (isAct) 1.dp else 3.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(if (isAct) TeOrange else TeKeyLight).padding(horizontal = 6.dp, vertical = 3.dp)) {
                            Text(romanDegrees[deg], color = if (isAct) Color.White.copy(alpha = 0.8f) else Color(0xFF6E6D68), fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.TopStart))
                            Text("$noteLabel$qualSuffix", color = if (isAct) Color.White else TePanelDark, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.BottomEnd))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TeBarSlider(label: String, valueText: String, fraction: Float, barColor: Color, onValueChange: (Float) -> Unit) {
    var boxWidth by remember { mutableStateOf(1) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, color = Color(0xFFAAAAAA), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            Text(text = valueText, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(15.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A28))
                .onSizeChanged { boxWidth = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onValueChange((down.position.x / boxWidth.toFloat()).coerceIn(0f, 1f))
                        do {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.pressed }
                            if (ch != null) { ch.consume(); onValueChange((ch.position.x / boxWidth.toFloat()).coerceIn(0f, 1f)) }
                        } while (ev.changes.any { it.pressed })
                    }
                }
        ) {
            Box(modifier = Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(barColor))
        }
    }
}

// --- HOCHFORMAT: TE // HC-1 FIELD CHORD SYNTHESIZER ---
@Composable
fun TePortraitJamScreen(
    synth: HiChordProEngine,
    keyIndex: Int,
    onKeyChange: (Int) -> Unit,
    isMinorScale: Boolean,
    onToggleMajorMinor: () -> Unit,
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    activeDegree: Int,
    onTriggerDegree: (Int, Boolean) -> Unit,
    displayChord: String,
    activePresetName: String,
    activePresetCategory: String,
    onCycleSlot: () -> Unit,
    inversionMode: Int,
    onCycleInversion: () -> Unit,
    bassMode: Int,
    onCycleBassMode: () -> Unit,
    octaveShift: Int,
    onOctaveChange: (Int) -> Unit,
    holdLatch: Boolean,
    onToggleHold: () -> Unit,
    strumMode: Boolean,
    onToggleStrum: () -> Unit,
    filterWheel: Float,
    onFilterWheelChange: (Float) -> Unit,
    waveIndex: Int,
    onWaveChange: (Int) -> Unit,
    adsrMode: Int,
    onAdsrChange: (Int) -> Unit,
    arpMode: Int,
    onArpChange: (Int) -> Unit,
    joyX: Float,
    joyY: Float,
    activeJoyZone: JoyChordZone,
    onJoyChange: (Float, Float) -> Unit,
    uiRefreshCounter: Int,
    frameTick: Int
) {
    val view = LocalView.current
    val tapTimes = remember { mutableStateListOf<Long>() }

    var fxDelay by remember(uiRefreshCounter) { mutableStateOf(synth.fxDelay) }
    var fxStutter by remember(uiRefreshCounter) { mutableStateOf(synth.fxStutter) }
    var fxChorus by remember(uiRefreshCounter) { mutableStateOf(synth.fxChorus) }
    var fxPump by remember(uiRefreshCounter) { mutableStateOf(synth.fxPump) }
    var fxReverb by remember(uiRefreshCounter) { mutableStateOf(synth.fxReverb) }
    var lofiMode by remember(uiRefreshCounter) { mutableStateOf(synth.lofiMode) }

    val currentOnJoyChange by rememberUpdatedState(onJoyChange)
    val currentOnTrigger by rememberUpdatedState(onTriggerDegree)
    val currentHold by rememberUpdatedState(holdLatch)
    var lastZone by remember { mutableStateOf(JoyChordZone.CENTER) }

    val arpNames = listOf("ARP", "UP", "DOWN", "U/D", "RAND")
    val invLabels = listOf("V-LEAD", "INV: 0", "INV: 1", "INV: 2")
    val bassLabels = listOf("BASS:OFF", "BASS:ROOT", "BASS:SLSH")
    val romanDegrees = if (isMinorScale) minorRomanDegrees else majorRomanDegrees
    val scaleOffsets = if (isMinorScale) minorScaleOffsets else majorScaleOffsets
    val scaleQuals = if (isMinorScale) minorDefaultQuality else majorDefaultQuality
    val degreeSubLabels = if (isMinorScale) {
        listOf("TONIC", "SUPER", "MEDI", "SUBD", "DOM", "SUBM", "SUBT")
    } else {
        listOf("TONIC", "SUPER", "MEDI", "SUBD", "DOM", "SUBM", "LEAD")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TeChassis)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. HARDWARE HEADER + HICHORD TONE WHEEL
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScrewHead()
                Text(
                    text = "TE // HC-1 FIELD CHORD SYNTH (ROTATE 90° FOR STUDIO)",
                    color = TePanelDark,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
            var wheelW by remember { mutableStateOf(1) }
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(TePanelDark)
                    .border(1.dp, Color(0xFF555550), RoundedCornerShape(4.dp))
                    .onSizeChanged { wheelW = it.width.coerceAtLeast(1) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val d = awaitFirstDown(requireUnconsumed = false)
                            onFilterWheelChange((d.position.x / wheelW.toFloat()).coerceIn(0.06f, 1f))
                            do {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed }
                                if (ch != null) {
                                    ch.consume()
                                    onFilterWheelChange((ch.position.x / wheelW.toFloat()).coerceIn(0.06f, 1f))
                                }
                            } while (ev.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(filterWheel.coerceIn(0.08f, 1f))
                        .fillMaxHeight()
                        .background(TeOrange)
                )
                Text(
                    text = "CUTOFF ${(filterWheel * 100).roundToInt()}%",
                    color = Color.White,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }

        // 2. OLED DISPLAY & 8-WEGE HICHORD JOYSTICK
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(146.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1.28f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TePanelDark)
                    .border(2.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cols = 16; val rows = 8
                    val stepX = size.width / cols; val stepY = size.height / rows
                    for (c in 1 until cols) for (r in 1 until rows) drawCircle(Color(0xFF262624), 1.5f, Offset(c * stepX, r * stepY))
                }
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        val scaleName = if (isMinorScale) "${rootNames[keyIndex]}m" else rootNames[keyIndex]
                        DisplayBadge(text = "KEY $scaleName", bgColor = if (isMinorScale) TeBlue else TeOrange, textColor = Color.White)
                        DisplayBadge(text = "$bpm BPM", bgColor = Color(0xFF2B2B2B), textColor = Color(0xFF00E5FF))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2B2B2B))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onCycleSlot()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = "▸ ${activePresetName.take(11)}", color = TeOchre, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text(
                                text = when {
                                    activeJoyZone != JoyChordZone.CENTER -> "JOYSTICK // ${activeJoyZone.desc}"
                                    lofiMode -> "$activePresetCategory · VINTAGE LO-FI TAPE"
                                    else -> "$activePresetCategory · ${if (isMinorScale) "MINOR" else "MAJOR"} · ${invLabels[inversionMode]}"
                                },
                                color = if (activeJoyZone != JoyChordZone.CENTER) Color(0xFF00E5FF) else if (lofiMode) TeOchre else Color(0xFF888884),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = displayChord,
                                color = Color(0xFFF4F3EF),
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = if (lofiMode) "TAPE WARM" else invLabels[inversionMode],
                            color = if (lofiMode) TeOrange else TeOchre,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
                        val tick = frameTick
                        val path = Path()
                        val stepX = size.width / synth.scopeBuffer.size
                        val midY = size.height / 2f
                        drawLine(Color(0xFF333333), Offset(0f, midY), Offset(size.width, midY), 1f)
                        for (i in synth.scopeBuffer.indices) {
                            val x = i * stepX; val y = midY - (synth.scopeBuffer[i] * midY * 0.95f)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color = if (synth.gate) TeOrange else Color(0xFF666662), style = Stroke(width = 3.2f, cap = StrokeCap.Round))
                    }
                }
            }

            // 8-Wege HiChord Joystick
            Box(
                modifier = Modifier
                    .weight(1.0f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFC5C3BC))
                    .border(2.dp, Color(0xFFABA9A2), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        var localX = 0f
                        var localY = 0f
                        detectDragGestures(
                            onDragStart = { localX = 0f; localY = 0f },
                            onDragEnd = {
                                localX = 0f; localY = 0f
                                lastZone = JoyChordZone.CENTER
                                currentOnJoyChange(0f, 0f)
                            },
                            onDragCancel = {
                                localX = 0f; localY = 0f
                                lastZone = JoyChordZone.CENTER
                                currentOnJoyChange(0f, 0f)
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val maxRadius = (min(size.width, size.height) / 2.3f).coerceAtLeast(1f)
                            localX = (localX + dragAmount.x / maxRadius).coerceIn(-1f, 1f)
                            localY = (localY - dragAmount.y / maxRadius).coerceIn(-1f, 1f)
                            currentOnJoyChange(localX, localY)

                            val z = getJoystickZone(localX, localY)
                            if (z != lastZone) {
                                lastZone = z
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    }
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxR = min(size.width, size.height) / 2.75f
                    drawCircle(Color(0xFF9E9C95), maxR, style = Stroke(1.8f))

                    val zones = listOf(
                        0f to JoyChordZone.RIGHT,
                        45f to JoyChordZone.UP_RIGHT,
                        90f to JoyChordZone.UP,
                        135f to JoyChordZone.UP_LEFT,
                        180f to JoyChordZone.LEFT,
                        225f to JoyChordZone.DOWN_LEFT,
                        270f to JoyChordZone.DOWN,
                        315f to JoyChordZone.DOWN_RIGHT
                    )
                    for ((deg, z) in zones) {
                        val rad = deg * PI.toFloat() / 180f
                        val dotPos = Offset(center.x + cos(rad) * maxR, center.y - sin(rad) * maxR)
                        val isSel = activeJoyZone == z
                        drawCircle(
                            color = if (isSel) TeOrange else TePanelDark,
                            radius = if (isSel) 5.5.dp.toPx() else 3.5.dp.toPx(),
                            center = dotPos
                        )
                    }

                    val knobPos = Offset(center.x + joyX * maxR, center.y - joyY * maxR)
                    drawLine(TePanelDark, center, knobPos, strokeWidth = 6f, cap = StrokeCap.Round)
                    drawCircle(if (activeJoyZone != JoyChordZone.CENTER) TeOrange else TePanelDark, 14.dp.toPx(), knobPos)
                    drawCircle(Color.White, 4.dp.toPx(), knobPos)
                }

                Text("↑ FLIP", color = TePanelDark, fontSize = 7.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.TopCenter))
                Text("↓ SUS4", color = TePanelDark, fontSize = 7.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.BottomCenter))
                Text("DIM", color = TePanelDark, fontSize = 7.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterStart))
                Text("MAJ7", color = TePanelDark, fontSize = 7.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterEnd))
                Text("↖AUG", color = Color(0xFF55544E), fontSize = 6.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.TopStart).padding(2.dp))
                Text("7TH↗", color = Color(0xFF55544E), fontSize = 6.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp))
                Text("↙6TH", color = Color(0xFF55544E), fontSize = 6.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.BottomStart).padding(2.dp))
                Text("9TH↘", color = Color(0xFF55544E), fontSize = 6.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
            }
        }

        // 3. SYSTEM, TONART, 1-BPM TEMPO, TAP-SYNC, ENGINE, ADSR & ARP
        SectionDivider("SYSTEM // KEY, 1-BPM TEMPO, TAP-SYNC, ENGINE, ADSR & ARP")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TeTactileButton(title = "KEY -", sub = "ROOT", modifier = Modifier.weight(0.95f), height = 42.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onKeyChange((keyIndex + 11) % 12) })
            TeTactileButton(title = "KEY +", sub = "ROOT", modifier = Modifier.weight(0.95f), height = 42.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onKeyChange((keyIndex + 1) % 12) })
            TeTactileButton(title = "BPM -", sub = "-1", modifier = Modifier.weight(0.9f), height = 42.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onBpmChange((bpm - 1).coerceAtLeast(60)) })
            TeTactileButton(title = "BPM +", sub = "+1", modifier = Modifier.weight(0.9f), height = 42.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onBpmChange((bpm + 1).coerceAtMost(200)) })
            TeTactileButton(
                title = "TAP", sub = "SYNC", modifier = Modifier.weight(0.95f), height = 42.dp, bgColor = TeOrange, shadowColor = TeOrangeShadow, textColor = Color.White,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val now = SystemClock.elapsedRealtime()
                    if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2200L) tapTimes.clear()
                    tapTimes.add(now)
                    if (tapTimes.size > 4) tapTimes.removeAt(0)
                    if (tapTimes.size >= 2) {
                        val intervals = (1 until tapTimes.size).map { tapTimes[it] - tapTimes[it - 1] }
                        val avgMs = intervals.average()
                        if (avgMs > 0) onBpmChange((60000.0 / avgMs).roundToInt().coerceIn(60, 200))
                    }
                    synth.resetClockSync()
                }
            )
            TeTactileButton(title = engineNames[waveIndex], sub = "ENGINE", modifier = Modifier.weight(0.95f), height = 42.dp, bgColor = TeOchre, shadowColor = Color(0xFFA67621), textColor = TePanelDark, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onWaveChange((waveIndex + 1) % engineNames.size) })
            TeTactileButton(title = adsrNames[adsrMode], sub = "ADSR", modifier = Modifier.weight(0.95f), height = 42.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = adsrMode == 2, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onAdsrChange((adsrMode + 1) % adsrNames.size) })
            TeTactileButton(title = arpNames[arpMode], sub = "ARP", modifier = Modifier.weight(0.95f), height = 42.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = arpMode > 0, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onArpChange((arpMode + 1) % arpNames.size) })
        }

        // 4. PERFORMANCE FX + DEDICATED LO-FI VINTAGE ANALOG TAPE MODE!
        SectionDivider("STUDIO FX // DEDICATED LO-FI TAPE MODE, BBD CHORUS, CLOUD REVERB & PUMP")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            // DEDIZIERTER LO-FI VINTAGE TAPE BUTTON
            TeTactileButton(
                title = "LO-FI",
                sub = "TAPE AGE",
                modifier = Modifier.weight(1.05f),
                height = 44.dp,
                bgColor = if (lofiMode) TeOrange else TeKeyDark,
                shadowColor = if (lofiMode) TeOrangeShadow else TeKeyDarkShadow,
                textColor = Color.White,
                ledOn = lofiMode,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    lofiMode = !lofiMode
                    synth.lofiMode = lofiMode
                }
            )
            TeMomentaryFxButton(title = "DELAY", sub = "ECHO", isPressed = fxDelay, modifier = Modifier.weight(0.95f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxDelay = true; synth.fxDelay = true }, onRelease = { fxDelay = false; synth.fxDelay = false })
            TeMomentaryFxButton(title = "STUTTR", sub = "1/32", isPressed = fxStutter, modifier = Modifier.weight(0.95f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxStutter = true; synth.fxStutter = true }, onRelease = { fxStutter = false; synth.fxStutter = false })
            TeTactileButton(title = "CHORUS", sub = "JUNO", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxChorus) TeOrange else TeBlue, shadowColor = if (fxChorus) TeOrangeShadow else TeBlueShadow, textColor = Color.White, ledOn = fxChorus, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxChorus = !fxChorus; synth.fxChorus = fxChorus })
            TeTactileButton(title = "REVERB", sub = "CLOUD", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxReverb) TeOrange else TeKeyDark, shadowColor = if (fxReverb) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = fxReverb, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxReverb = !fxReverb; synth.fxReverb = fxReverb })
            TeTactileButton(title = "PUMP", sub = "DUCK", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxPump) TeOrange else TeBlue, shadowColor = if (fxPump) TeOrangeShadow else TeBlueShadow, textColor = Color.White, ledOn = fxPump, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxPump = !fxPump; synth.fxPump = fxPump })
        }

        // 5. HICHORD CONTROLS (DEDICATED MAJOR/MINOR, HOLD, STRUM, INVERSION, BASS, OCT)
        SectionDivider("HICHORD CONTROLS // MAJOR/MINOR SCALE, HOLD, STRUM, VOICING & BASS")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TeTactileButton(
                title = if (isMinorScale) "MINOR" else "MAJOR",
                sub = "SCALE",
                modifier = Modifier.weight(1.05f),
                height = 46.dp,
                bgColor = if (isMinorScale) TeBlue else TeOrange,
                shadowColor = if (isMinorScale) TeBlueShadow else TeOrangeShadow,
                textColor = Color.White,
                ledOn = isMinorScale,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onToggleMajorMinor()
                }
            )
            TeTactileButton(title = "HOLD", sub = "LATCH", modifier = Modifier.weight(0.9f), height = 46.dp, bgColor = if (holdLatch) TeBlue else TeKeyDark, shadowColor = if (holdLatch) TeBlueShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = holdLatch, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleHold() })
            TeTactileButton(title = "STRUM", sub = "CHORD", modifier = Modifier.weight(0.95f), height = 46.dp, bgColor = if (strumMode) TeOrange else TeKeyDark, shadowColor = if (strumMode) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = strumMode, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleStrum() })
            TeTactileButton(title = invLabels[inversionMode], sub = "VOICING", modifier = Modifier.weight(0.95f), height = 46.dp, bgColor = if (inversionMode > 0) TeOrange else TeKeyDark, shadowColor = if (inversionMode > 0) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = true, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onCycleInversion() })
            TeTactileButton(title = bassLabels[bassMode], sub = "SUB-OSC", modifier = Modifier.weight(1.05f), height = 46.dp, bgColor = if (bassMode > 0) TeKeyDark else Color(0xFF3A3A38), shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = bassMode > 0, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onCycleBassMode() })
            TeTactileButton(
                title = "OCT ${if (octaveShift >= 0) "+$octaveShift" else octaveShift}",
                sub = "RANGE",
                modifier = Modifier.weight(0.9f),
                height = 46.dp,
                bgColor = TeKeyDark,
                shadowColor = TeKeyDarkShadow,
                textColor = Color.White,
                ledOn = octaveShift != 0,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onOctaveChange(when (octaveShift) { 0 -> 1; 1 -> -1; else -> 0 })
                }
            )
        }

        // 6. 7 DIATONIC CHORD KEYS (I - VII)
        SectionDivider("NASHVILLE CHORD KEYS // INSTANT TRIGGER & LEGATO SLIDE (I - VII)")
        var padGridSize by remember { mutableStateOf(IntSize.Zero) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { padGridSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        fun mapOffsetToDegree(pos: Offset): Int {
                            val w = padGridSize.width.coerceAtLeast(1)
                            val h = padGridSize.height.coerceAtLeast(1)
                            val relX = (pos.x / w.toFloat()).coerceIn(0f, 0.999f)
                            val relY = (pos.y / h.toFloat()).coerceIn(0f, 0.999f)
                            return if (relY < 0.5f) {
                                (relX * 4f).toInt().coerceIn(0, 3)
                            } else {
                                4 + (relX * 3f).toInt().coerceIn(0, 2)
                            }
                        }

                        var currentDeg = mapOffsetToDegree(down.position)
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        currentOnTrigger(currentDeg, true)

                        do {
                            val event = awaitPointerEvent()
                            val activeChange = event.changes.firstOrNull { it.pressed }
                            if (activeChange != null) {
                                activeChange.consume()
                                val slidedDeg = mapOffsetToDegree(activeChange.position)
                                if (slidedDeg != currentDeg) {
                                    currentDeg = slidedDeg
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    currentOnTrigger(slidedDeg, synth.arpMode == 0)
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (!currentHold) {
                            currentOnTrigger(-1, false)
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (deg in 0..3) {
                    val noteLabel = rootNames[(keyIndex + scaleOffsets[deg]) % 12]
                    val qualSuffix = when (scaleQuals[deg]) { 1 -> "m"; 2 -> "°"; else -> "" }
                    TeChordKeycap(
                        number = "0${deg + 1}",
                        roman = romanDegrees[deg],
                        subLabel = degreeSubLabels[deg],
                        note = "$noteLabel$qualSuffix",
                        isPressed = activeDegree == deg,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (deg in 4..6) {
                    val noteLabel = rootNames[(keyIndex + scaleOffsets[deg]) % 12]
                    val qualSuffix = when (scaleQuals[deg]) { 1 -> "m"; 2 -> "°"; else -> "" }
                    TeChordKeycap(
                        number = "0${deg + 1}",
                        roman = romanDegrees[deg],
                        subLabel = degreeSubLabels[deg],
                        note = "$noteLabel$qualSuffix",
                        isPressed = activeDegree == deg,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
fun SectionDivider(title: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, color = Color(0xFF5A5852), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Box(modifier = Modifier.weight(1f).height(1.dp).background(Color(0xFFB5B3AC)))
    }
}

@Composable
fun DisplayBadge(text: String, bgColor: Color, textColor: Color) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bgColor).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text = text, color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ScrewHead() {
    Canvas(modifier = Modifier.size(12.dp)) { drawCircle(Color(0xFFA09E97)); drawLine(Color(0xFF5A5852), Offset(size.width * 0.2f, size.height * 0.5f), Offset(size.width * 0.8f, size.height * 0.5f), 2f) }
}

@Composable
fun TeMomentaryFxButton(title: String, sub: String, isPressed: Boolean, modifier: Modifier = Modifier, onPress: () -> Unit, onRelease: () -> Unit) {
    Box(modifier = modifier.height(44.dp).padding(top = if (isPressed) 2.dp else 0.dp).clip(RoundedCornerShape(7.dp)).background(if (isPressed) TeOrangeShadow else TeBlueShadow)
            .pointerInput(Unit) { detectTapGestures(onPress = { onPress(); tryAwaitRelease(); onRelease() }) }.padding(bottom = if (isPressed) 1.dp else 3.dp)) {
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(if (isPressed) TeOrange else TeBlue).padding(horizontal = 5.dp, vertical = 4.dp)) {
            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(if (isPressed) Color.White else Color(0xFF093163)).align(Alignment.TopEnd))
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(text = sub, color = Color.White.copy(alpha = 0.75f), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                Text(text = title, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun TeTactileButton(title: String, sub: String, modifier: Modifier = Modifier, height: Dp = 48.dp, bgColor: Color, shadowColor: Color, textColor: Color, ledOn: Boolean? = null, onClick: () -> Unit) {
    Box(modifier = modifier.height(height).clip(RoundedCornerShape(7.dp)).background(shadowColor).clickable { onClick() }.padding(bottom = 3.dp)) {
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(bgColor).padding(horizontal = 4.dp, vertical = 3.dp)) {
            if (ledOn != null) Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(if (ledOn) TeLedRed else TeLedOff).align(Alignment.TopEnd))
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(text = sub, color = textColor.copy(alpha = 0.65f), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                Text(text = title, color = textColor, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun TeChordKeycap(number: String, roman: String, subLabel: String, note: String, isPressed: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(top = if (isPressed) 4.dp else 0.dp).clip(RoundedCornerShape(9.dp)).background(if (isPressed) TeOrangeShadow else TeKeyLightShadow).padding(bottom = if (isPressed) 1.dp else 5.dp)) {
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(if (isPressed) TeOrange else TeKeyLight).border(1.dp, if (isPressed) Color.White else Color(0xFFE2E0D8), RoundedCornerShape(8.dp)).padding(8.dp)) {
            Column(modifier = Modifier.align(Alignment.TopStart)) {
                Text(text = "$number // $roman", color = if (isPressed) Color.White else Color(0xFF6E6D68), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(text = subLabel, color = if (isPressed) Color.White.copy(alpha = 0.8f) else Color(0xFF9E9C95), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(if (isPressed) Color.White else TeLedOff.copy(alpha = 0.35f)).align(Alignment.TopEnd))
            Text(text = note, color = if (isPressed) Color.White else TePanelDark, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}
