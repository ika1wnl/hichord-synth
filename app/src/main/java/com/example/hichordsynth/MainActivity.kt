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

// --- TEENAGE ENGINEERING / HICHORD FARBPALETTE ---
val TeChassis = Color(0xFFD8D6D0)
val TePanelDark = Color(0xFF171717)
val TeKeyDark = Color(0xFF282828)
val TeKeyDarkShadow = Color(0xFF101010)
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
    private val synth = WolfChordEngine()

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

// --- CHORD MACHINE / DAISY-SP SYNTHESE-ALGORITHMEN ---
// 0: VA_SAW      (Bandlimited PolyBLEP Saw + Analog Drift)
// 1: OCT_SAW     (Chord Machine Octave-Saw + Sub-Saw Stack)
// 2: VA_PWM      (Variable Pulse Width Modulation + Ensemble)
// 3: FM1_EP      (DX Rhodes Electric Piano: 1:1 + 1:14 Tine Transient)
// 4: FM2_MALLET  (Inharmonic 1:3.5 / 1:4 Vibraphone & Marimba Resonator)
// 5: FM3_BELL    (Metallic Shimmer / Warm Glass Bell)
// 6: KS_STRING   (Echte Karplus-Strong Delay-Line Waveguide für Harp, Guitar & Clav)
// 7: HAMMER_PNO  (Multi-String Piano Model mit Hammer-Filz-Filter & Soundboard)
val engineNames = listOf("SAW", "OSAW", "PWM", "FM1", "FM2", "FM3", "STRG", "PNO")
val fmRatios = floatArrayOf(0.5f, 1.0f, 2.0f, 3.5f, 4.0f, 7.0f, 14.0f)
val adsrNames = listOf("SUST", "SWEL", "PLCK", "KEYS")

class VoiceConfig(
    @Volatile var engine: Int = 3,
    @Volatile var fmRatioIndex: Int = 1,
    @Volatile var timbre: Float = 0.42f,   // Steuert FM-Index, String-Brightness oder PWM
    @Volatile var morph: Float = 0.35f,    // Steuert Decay-Damping, Detune-Spread oder Tine-Pegel
    @Volatile var detuneCents: Int = 0,
    @Volatile var octShift: Int = 0,
    @Volatile var pan: Float = 0f,
    @Volatile var level: Float = 0.85f
)

data class VoicePresetData(
    val engine: Int,
    val fmRatioIndex: Int,
    val timbre: Float,
    val morph: Float,
    val detuneCents: Int,
    val octShift: Int,
    val pan: Float,
    val level: Float
)

data class SynthPreset(
    val id: String,
    val name: String,
    val category: String,
    val adsrMode: Int,          // 0: SUST, 1: SWEL, 2: PLCK, 3: KEYS
    val attackMs: Float,
    val decayMs: Float,
    val sustainLevel: Float,
    val releaseMs: Float,
    val baseCutoff: Float,
    val filterResonance: Float,
    val filterEnvAmount: Float,
    val vibratoDepth: Float,
    val tremoloDepth: Float,    // Stereo Auto-Pan / Tremolo (für E-Piano & Vibraphone)
    val glideSpeed: Float,
    val reverbSend: Float,
    val ensembleOn: Boolean,
    val delayOn: Boolean,
    val voices: List<VoicePresetData>,
    val isUser: Boolean = false
) {
    fun serialize(): String {
        val vStr = voices.joinToString(";") {
            "${it.engine},${it.fmRatioIndex},${it.timbre},${it.morph},${it.detuneCents},${it.octShift},${it.pan},${it.level}"
        }
        return "$id|$name|$category|$adsrMode|$attackMs|$decayMs|$sustainLevel|$releaseMs|$baseCutoff|$filterResonance|$filterEnvAmount|$vibratoDepth|$tremoloDepth|$glideSpeed|$reverbSend|$ensembleOn|$delayOn|$vStr"
    }

    companion object {
        fun deserialize(raw: String): SynthPreset? {
            return try {
                val p = raw.split("|")
                if (p.size < 18) return null
                val vList = p[17].split(";").map { vRaw ->
                    val vp = vRaw.split(",")
                    VoicePresetData(
                        engine = vp[0].toInt(),
                        fmRatioIndex = vp[1].toInt(),
                        timbre = vp[2].toFloat(),
                        morph = vp[3].toFloat(),
                        detuneCents = vp[4].toInt(),
                        octShift = vp[5].toInt(),
                        pan = vp[6].toFloat(),
                        level = vp[7].toFloat()
                    )
                }
                if (vList.size != 4) return null
                SynthPreset(
                    id = p[0], name = p[1], category = p[2],
                    adsrMode = p[3].toInt(), attackMs = p[4].toFloat(), decayMs = p[5].toFloat(),
                    sustainLevel = p[6].toFloat(), releaseMs = p[7].toFloat(),
                    baseCutoff = p[8].toFloat(), filterResonance = p[9].toFloat(),
                    filterEnvAmount = p[10].toFloat(), vibratoDepth = p[11].toFloat(),
                    tremoloDepth = p[12].toFloat(), glideSpeed = p[13].toFloat(),
                    reverbSend = p[14].toFloat(), ensembleOn = p[15].toBoolean(),
                    delayOn = p[16].toBoolean(), voices = vList, isUser = true
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

// --- DIE 16 ORIGINALEN HICHORD & CHORD-MACHINE INSTRUMENTE ---
val factoryPresets = listOf(
    // 01. FM E-PIANO (Rhodes Mk1 + Stereo Auto-Pan Tremolo)
    SynthPreset(
        id = "cm_01_epiano", name = "FM E-PIANO", category = "KEYS",
        adsrMode = 3, attackMs = 2f, decayMs = 1500f, sustainLevel = 0.12f, releaseMs = 420f,
        baseCutoff = 0.62f, filterResonance = 0.18f, filterEnvAmount = 0.35f,
        vibratoDepth = 0.0010f, tremoloDepth = 0.45f, glideSpeed = 0.045f, reverbSend = 0.32f,
        ensembleOn = false, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 3, fmRatioIndex = 1, timbre = 0.42f, morph = 0.35f, detuneCents = -2, octShift = 0, pan = -0.35f, level = 0.95f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, timbre = 0.45f, morph = 0.40f, detuneCents = 3, octShift = 0, pan = 0.35f, level = 0.90f),
            VoicePresetData(engine = 3, fmRatioIndex = 2, timbre = 0.38f, morph = 0.30f, detuneCents = -4, octShift = 0, pan = -0.60f, level = 0.85f),
            VoicePresetData(engine = 3, fmRatioIndex = 5, timbre = 0.30f, morph = 0.48f, detuneCents = 5, octShift = 0, pan = 0.60f, level = 0.78f)
        )
    ),
    // 02. GRAND PIANO (Echte Karplus-Strong Hammer-Saiten-Resonanz)
    SynthPreset(
        id = "cm_02_piano", name = "GRAND PIANO", category = "KEYS",
        adsrMode = 2, attackMs = 1.5f, decayMs = 1450f, sustainLevel = 0.0f, releaseMs = 320f,
        baseCutoff = 0.72f, filterResonance = 0.15f, filterEnvAmount = 0.45f,
        vibratoDepth = 0.0f, tremoloDepth = 0.0f, glideSpeed = 0.090f, reverbSend = 0.30f,
        ensembleOn = false, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 7, fmRatioIndex = 1, timbre = 0.68f, morph = 0.75f, detuneCents = -2, octShift = 0, pan = -0.30f, level = 0.95f),
            VoicePresetData(engine = 7, fmRatioIndex = 1, timbre = 0.65f, morph = 0.72f, detuneCents = 2, octShift = 0, pan = 0.30f, level = 0.92f),
            VoicePresetData(engine = 7, fmRatioIndex = 2, timbre = 0.60f, morph = 0.68f, detuneCents = -3, octShift = 0, pan = -0.60f, level = 0.88f),
            VoicePresetData(engine = 7, fmRatioIndex = 2, timbre = 0.58f, morph = 0.65f, detuneCents = 3, octShift = 0, pan = 0.60f, level = 0.84f)
        )
    ),
    // 03. DELICATE HARP (Echte Karplus-Strong Zupfsaiten!)
    SynthPreset(
        id = "cm_03_harp", name = "DELICATE HARP", category = "PLUCK",
        adsrMode = 2, attackMs = 1.2f, decayMs = 1300f, sustainLevel = 0.0f, releaseMs = 450f,
        baseCutoff = 0.78f, filterResonance = 0.12f, filterEnvAmount = 0.35f,
        vibratoDepth = 0.0006f, tremoloDepth = 0.0f, glideSpeed = 0.080f, reverbSend = 0.52f,
        ensembleOn = false, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 6, fmRatioIndex = 1, timbre = 0.55f, morph = 0.82f, detuneCents = 0, octShift = 0, pan = -0.35f, level = 0.95f),
            VoicePresetData(engine = 6, fmRatioIndex = 1, timbre = 0.58f, morph = 0.80f, detuneCents = -3, octShift = 0, pan = 0.35f, level = 0.92f),
            VoicePresetData(engine = 6, fmRatioIndex = 2, timbre = 0.62f, morph = 0.78f, detuneCents = 3, octShift = 0, pan = -0.70f, level = 0.88f),
            VoicePresetData(engine = 6, fmRatioIndex = 2, timbre = 0.65f, morph = 0.75f, detuneCents = 5, octShift = 1, pan = 0.70f, level = 0.76f)
        )
    ),
    // 04. ACOUSTIC GUITAR (Karplus-Strong Steel-String Guitar)
    SynthPreset(
        id = "cm_04_guitar", name = "STEEL GUITAR", category = "PLUCK",
        adsrMode = 2, attackMs = 1.2f, decayMs = 1150f, sustainLevel = 0.0f, releaseMs = 300f,
        baseCutoff = 0.68f, filterResonance = 0.25f, filterEnvAmount = 0.48f,
        vibratoDepth = 0.0012f, tremoloDepth = 0.0f, glideSpeed = 0.025f, reverbSend = 0.36f,
        ensembleOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 6, fmRatioIndex = 1, timbre = 0.82f, morph = 0.68f, detuneCents = -2, octShift = -1, pan = -0.25f, level = 0.95f),
            VoicePresetData(engine = 6, fmRatioIndex = 1, timbre = 0.78f, morph = 0.70f, detuneCents = 2, octShift = 0, pan = 0.25f, level = 0.90f),
            VoicePresetData(engine = 6, fmRatioIndex = 1, timbre = 0.85f, morph = 0.66f, detuneCents = -4, octShift = 0, pan = -0.65f, level = 0.88f),
            VoicePresetData(engine = 6, fmRatioIndex = 2, timbre = 0.80f, morph = 0.64f, detuneCents = 4, octShift = 0, pan = 0.65f, level = 0.85f)
        )
    ),
    // 05. WARM PAD (Juno-60 Analog PWM + Sub-Saw Pad mit Per-Voice Filter-Sweep)
    SynthPreset(
        id = "cm_05_warmpad", name = "WARM PAD", category = "SYNTH PAD",
        adsrMode = 1, attackMs = 450f, decayMs = 1100f, sustainLevel = 0.85f, releaseMs = 950f,
        baseCutoff = 0.26f, filterResonance = 0.38f, filterEnvAmount = 0.42f,
        vibratoDepth = 0.0022f, tremoloDepth = 0.0f, glideSpeed = 0.0035f, reverbSend = 0.56f,
        ensembleOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.55f, morph = 0.50f, detuneCents = -8, octShift = 0, pan = -0.55f, level = 0.90f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.45f, morph = 0.40f, detuneCents = 8, octShift = 0, pan = 0.55f, level = 0.90f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.50f, morph = 0.60f, detuneCents = -15, octShift = 0, pan = -0.85f, level = 0.84f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.60f, morph = 0.50f, detuneCents = 15, octShift = 0, pan = 0.85f, level = 0.84f)
        )
    ),
    // 06. STRINGS (Solina 3-Phase BBD Ensemble String Machine)
    SynthPreset(
        id = "cm_06_strings", name = "STRINGS", category = "ENSEMBLE",
        adsrMode = 1, attackMs = 540f, decayMs = 900f, sustainLevel = 0.92f, releaseMs = 920f,
        baseCutoff = 0.52f, filterResonance = 0.25f, filterEnvAmount = 0.22f,
        vibratoDepth = 0.0030f, tremoloDepth = 0.12f, glideSpeed = 0.0045f, reverbSend = 0.58f,
        ensembleOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.62f, morph = 0.55f, detuneCents = -11, octShift = 0, pan = -0.75f, level = 0.88f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.68f, morph = 0.60f, detuneCents = 11, octShift = 0, pan = 0.75f, level = 0.88f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.55f, morph = 0.45f, detuneCents = -18, octShift = 0, pan = -0.45f, level = 0.82f),
            VoicePresetData(engine = 1, fmRatioIndex = 2, timbre = 0.50f, morph = 0.50f, detuneCents = 18, octShift = 0, pan = 0.45f, level = 0.78f)
        )
    ),
    // 07. SAW SYNTH (Prophet-5 Polyphonic Analog Saw)
    SynthPreset(
        id = "cm_07_sawsynth", name = "SAW SYNTH", category = "ANALOG",
        adsrMode = 0, attackMs = 6f, decayMs = 480f, sustainLevel = 0.72f, releaseMs = 320f,
        baseCutoff = 0.38f, filterResonance = 0.45f, filterEnvAmount = 0.58f,
        vibratoDepth = 0.0015f, tremoloDepth = 0.0f, glideSpeed = 0.0065f, reverbSend = 0.32f,
        ensembleOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.65f, morph = 0.35f, detuneCents = -7, octShift = 0, pan = -0.45f, level = 0.92f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.65f, morph = 0.35f, detuneCents = 7, octShift = 0, pan = 0.45f, level = 0.92f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.60f, morph = 0.42f, detuneCents = -14, octShift = 0, pan = -0.80f, level = 0.86f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.60f, morph = 0.42f, detuneCents = 14, octShift = 0, pan = 0.80f, level = 0.86f)
        )
    ),
    // 08. OCTAVE SAW (Chord Machine Signature Octave-Saw Stack)
    SynthPreset(
        id = "cm_08_octsaw", name = "OCTAVE SAW", category = "ANALOG",
        adsrMode = 0, attackMs = 5f, decayMs = 520f, sustainLevel = 0.82f, releaseMs = 360f,
        baseCutoff = 0.54f, filterResonance = 0.38f, filterEnvAmount = 0.48f,
        vibratoDepth = 0.0012f, tremoloDepth = 0.0f, glideSpeed = 0.0055f, reverbSend = 0.40f,
        ensembleOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.72f, morph = 0.65f, detuneCents = -10, octShift = 0, pan = -0.55f, level = 0.92f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.72f, morph = 0.65f, detuneCents = 10, octShift = 0, pan = 0.55f, level = 0.92f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.68f, morph = 0.75f, detuneCents = -22, octShift = 0, pan = -0.90f, level = 0.88f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.68f, morph = 0.75f, detuneCents = 22, octShift = 0, pan = 0.90f, level = 0.88f)
        )
    ),
    // 09. SYNTH BRASS (OB-Xa / CS-80 Analog Filter-Sweep Brass)
    SynthPreset(
        id = "cm_09_brass", name = "SYNTH BRASS", category = "ANALOG",
        adsrMode = 0, attackMs = 42f, decayMs = 480f, sustainLevel = 0.76f, releaseMs = 300f,
        baseCutoff = 0.18f, filterResonance = 0.52f, filterEnvAmount = 0.76f,
        vibratoDepth = 0.0020f, tremoloDepth = 0.0f, glideSpeed = 0.0032f, reverbSend = 0.36f,
        ensembleOn = false, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.80f, morph = 0.30f, detuneCents = -9, octShift = 0, pan = -0.50f, level = 0.94f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.65f, morph = 0.35f, detuneCents = 9, octShift = 0, pan = 0.50f, level = 0.90f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.75f, morph = 0.45f, detuneCents = -18, octShift = 0, pan = -0.85f, level = 0.88f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.80f, morph = 0.30f, detuneCents = 18, octShift = 0, pan = 0.85f, level = 0.88f)
        )
    ),
    // 10. VIBRAPHONE (Inharmonic 1:3.5 / 1:4 Modal Mallet + Motor Tremolo)
    SynthPreset(
        id = "cm_10_vibraphone", name = "VIBRAPHONE", category = "MALLET",
        adsrMode = 2, attackMs = 1.2f, decayMs = 1250f, sustainLevel = 0.0f, releaseMs = 520f,
        baseCutoff = 0.68f, filterResonance = 0.15f, filterEnvAmount = 0.38f,
        vibratoDepth = 0.0015f, tremoloDepth = 0.55f, glideSpeed = 0.070f, reverbSend = 0.48f,
        ensembleOn = false, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 4, fmRatioIndex = 4, timbre = 0.45f, morph = 0.65f, detuneCents = 0, octShift = 0, pan = -0.30f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, timbre = 0.48f, morph = 0.60f, detuneCents = -3, octShift = 0, pan = 0.30f, level = 0.90f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, timbre = 0.40f, morph = 0.55f, detuneCents = 3, octShift = 0, pan = -0.60f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, timbre = 0.35f, morph = 0.50f, detuneCents = 5, octShift = 0, pan = 0.60f, level = 0.78f)
        )
    ),
    // 11. MARIMBA (Wooden Hollow Resonator Mallet)
    SynthPreset(
        id = "cm_11_marimba", name = "MARIMBA", category = "MALLET",
        adsrMode = 2, attackMs = 1.0f, decayMs = 360f, sustainLevel = 0.0f, releaseMs = 200f,
        baseCutoff = 0.46f, filterResonance = 0.32f, filterEnvAmount = 0.64f,
        vibratoDepth = 0.0f, tremoloDepth = 0.0f, glideSpeed = 0.085f, reverbSend = 0.34f,
        ensembleOn = false, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 4, fmRatioIndex = 4, timbre = 0.62f, morph = 0.25f, detuneCents = 0, octShift = 0, pan = -0.25f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, timbre = 0.58f, morph = 0.22f, detuneCents = -3, octShift = 0, pan = 0.25f, level = 0.90f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, timbre = 0.52f, morph = 0.20f, detuneCents = 3, octShift = 0, pan = -0.55f, level = 0.86f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, timbre = 0.55f, morph = 0.24f, detuneCents = 5, octShift = 0, pan = 0.55f, level = 0.82f)
        )
    ),
    // 12. FUNK CLAVINET (Crisp Karplus-Strong Funk Clav)
    SynthPreset(
        id = "cm_12_clavinet", name = "FUNK CLAVINET", category = "KEYS",
        adsrMode = 2, attackMs = 1.0f, decayMs = 620f, sustainLevel = 0.0f, releaseMs = 180f,
        baseCutoff = 0.64f, filterResonance = 0.46f, filterEnvAmount = 0.68f,
        vibratoDepth = 0.0005f, tremoloDepth = 0.0f, glideSpeed = 0.080f, reverbSend = 0.24f,
        ensembleOn = false, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 6, fmRatioIndex = 1, timbre = 0.92f, morph = 0.52f, detuneCents = -2, octShift = 0, pan = -0.30f, level = 0.95f),
            VoicePresetData(engine = 6, fmRatioIndex = 1, timbre = 0.90f, morph = 0.50f, detuneCents = 2, octShift = 0, pan = 0.30f, level = 0.92f),
            VoicePresetData(engine = 2, fmRatioIndex = 2, timbre = 0.82f, morph = 0.45f, detuneCents = -4, octShift = 0, pan = -0.60f, level = 0.84f),
            VoicePresetData(engine = 6, fmRatioIndex = 1, timbre = 0.88f, morph = 0.48f, detuneCents = 4, octShift = 0, pan = 0.60f, level = 0.84f)
        )
    ),
    // 13. WURLI REEDS (Warm Reed E-Piano + Tremolo)
    SynthPreset(
        id = "cm_13_wurli", name = "WURLI REEDS", category = "KEYS",
        adsrMode = 3, attackMs = 2f, decayMs = 1050f, sustainLevel = 0.16f, releaseMs = 340f,
        baseCutoff = 0.42f, filterResonance = 0.30f, filterEnvAmount = 0.52f,
        vibratoDepth = 0.0012f, tremoloDepth = 0.52f, glideSpeed = 0.040f, reverbSend = 0.30f,
        ensembleOn = false, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 3, fmRatioIndex = 1, timbre = 0.64f, morph = 0.22f, detuneCents = -3, octShift = 0, pan = -0.30f, level = 0.95f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, timbre = 0.60f, morph = 0.25f, detuneCents = 3, octShift = 0, pan = 0.30f, level = 0.90f),
            VoicePresetData(engine = 2, fmRatioIndex = 2, timbre = 0.48f, morph = 0.20f, detuneCents = -6, octShift = 0, pan = -0.60f, level = 0.84f),
            VoicePresetData(engine = 3, fmRatioIndex = 3, timbre = 0.52f, morph = 0.18f, detuneCents = 6, octShift = 0, pan = 0.60f, level = 0.78f)
        )
    ),
    // 14. CRYSTAL BELLS (Chord Machine FM3 Ambient Shimmer Bells)
    SynthPreset(
        id = "cm_14_bells", name = "CRYSTAL BELLS", category = "MALLET",
        adsrMode = 2, attackMs = 1.5f, decayMs = 1350f, sustainLevel = 0.0f, releaseMs = 680f,
        baseCutoff = 0.74f, filterResonance = 0.22f, filterEnvAmount = 0.40f,
        vibratoDepth = 0.0025f, tremoloDepth = 0.25f, glideSpeed = 0.030f, reverbSend = 0.62f,
        ensembleOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 5, fmRatioIndex = 3, timbre = 0.52f, morph = 0.60f, detuneCents = -5, octShift = 0, pan = -0.45f, level = 0.92f),
            VoicePresetData(engine = 5, fmRatioIndex = 5, timbre = 0.48f, morph = 0.65f, detuneCents = 5, octShift = 0, pan = 0.45f, level = 0.90f),
            VoicePresetData(engine = 5, fmRatioIndex = 6, timbre = 0.42f, morph = 0.70f, detuneCents = -11, octShift = 0, pan = -0.80f, level = 0.82f),
            VoicePresetData(engine = 3, fmRatioIndex = 2, timbre = 0.35f, morph = 0.55f, detuneCents = 11, octShift = 1, pan = 0.80f, level = 0.72f)
        )
    ),
    // 15.TAPE MELLOTRON (Vintage Lo-Fi Flute/String Tape Choir)
    SynthPreset(
        id = "cm_15_mellotron", name = "TAPE MELLOTRON", category = "ENSEMBLE",
        adsrMode = 1, attackMs = 280f, decayMs = 800f, sustainLevel = 0.82f, releaseMs = 620f,
        baseCutoff = 0.30f, filterResonance = 0.42f, filterEnvAmount = 0.28f,
        vibratoDepth = 0.0052f, tremoloDepth = 0.18f, glideSpeed = 0.0040f, reverbSend = 0.46f,
        ensembleOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 2, timbre = 0.42f, morph = 0.65f, detuneCents = -9, octShift = 0, pan = -0.50f, level = 0.90f),
            VoicePresetData(engine = 5, fmRatioIndex = 1, timbre = 0.32f, morph = 0.50f, detuneCents = 9, octShift = 0, pan = 0.50f, level = 0.88f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.38f, morph = 0.60f, detuneCents = -16, octShift = 0, pan = -0.80f, level = 0.82f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.30f, morph = 0.40f, detuneCents = 16, octShift = 0, pan = 0.80f, level = 0.80f)
        )
    ),
    // 16. ANALOG PLUCK (Short Resonant Per-Voice Filter Pluck)
    SynthPreset(
        id = "cm_16_analogpluck", name = "ANALOG PLUCK", category = "PLUCK",
        adsrMode = 2, attackMs = 1.2f, decayMs = 420f, sustainLevel = 0.0f, releaseMs = 220f,
        baseCutoff = 0.16f, filterResonance = 0.58f, filterEnvAmount = 0.82f,
        vibratoDepth = 0.0010f, tremoloDepth = 0.0f, glideSpeed = 0.012f, reverbSend = 0.38f,
        ensembleOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.70f, morph = 0.50f, detuneCents = -6, octShift = 0, pan = -0.40f, level = 0.95f),
            VoicePresetData(engine = 2, fmRatioIndex = 1, timbre = 0.65f, morph = 0.45f, detuneCents = 6, octShift = 0, pan = 0.40f, level = 0.92f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, timbre = 0.75f, morph = 0.40f, detuneCents = -12, octShift = 0, pan = -0.75f, level = 0.88f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, timbre = 0.70f, morph = 0.55f, detuneCents = 12, octShift = 0, pan = 0.75f, level = 0.88f)
        )
    )
)

// --- 8-WEGE JOYSTICK-RICHTUNGEN ---
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

// --- WOLFPUNK / DAISY-SP CHORD MACHINE AUDIO ENGINE ---
class WolfChordEngine {
    private val sampleRate = 44100
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
    @Volatile var filterWheel = 0.58f

    @Volatile var adsrMode = 3
    @Volatile var attackMs = 2f
    @Volatile var decayMs = 1400f
    @Volatile var sustainLevel = 0.12f
    @Volatile var releaseMs = 420f
    @Volatile var baseCutoff = 0.62f
    @Volatile var filterResonance = 0.22f
    @Volatile var filterEnvAmount = 0.45f
    @Volatile var vibratoDepth = 0.0012f
    @Volatile var tremoloDepth = 0.35f
    @Volatile var glideSpeed = 0.040f
    @Volatile var reverbSend = 0.35f

    @Volatile var arpMode = 0

    // Performance FX
    @Volatile var fxDelay = false
    @Volatile var fxCrush = false
    @Volatile var fxStutter = false
    @Volatile var fxChorus = false
    @Volatile var fxPump = false
    @Volatile var fxReverb = true

    @Volatile private var syncResetRequested = false
    @Volatile private var attackTriggerCount = 0

    val voices = Array(4) { v ->
        VoiceConfig(
            engine = 3,
            fmRatioIndex = 1,
            timbre = 0.42f,
            morph = 0.35f,
            detuneCents = when (v) { 0 -> -2; 1 -> 3; 2 -> -4; else -> 5 },
            octShift = 0,
            pan = when (v) { 0 -> -0.35f; 1 -> 0.35f; 2 -> -0.60f; else -> 0.60f },
            level = 0.88f
        )
    }

    val scopeBuffer = FloatArray(128)

    // Oszillator-Phasen & Analog-Drift pro Stimme
    private val oscPhases = Array(4) { FloatArray(4) { Random.nextFloat() } }
    private val modPhases = FloatArray(4) { Random.nextFloat() }
    private val tinePhases = FloatArray(4) { Random.nextFloat() }
    private val voiceDrift = FloatArray(4) { 1f }

    // ECHTE KARPLUS-STRONG WAVEGUIDE DELAY-LINE PRO STIMME (2048 Samples)
    private val ksDelayLines = Array(4) { FloatArray(2048) }
    private val ksWritePos = IntArray(4)
    private val ksLpState = FloatArray(4)
    private val ksExciteRemaining = IntArray(4)

    // EIGENES 2-POL RESONANZ-FILTER (SVF) PRO STIMME!
    private val voiceSvfLow = FloatArray(4)
    private val voiceSvfBand = FloatArray(4)

    // Eigene ADSR- & Transienten-Hüllkurve pro Stimme
    private val voiceStage = IntArray(4)
    private val voiceAmpEnv = FloatArray(4)
    private val voiceFltEnv = FloatArray(4)
    private val voiceTineEnv = FloatArray(4)
    private val voiceStrumWait = IntArray(4)

    private var bassPhase = 0f
    private var lfoPhase = 0f
    private var tremPhase = 0f
    private var pwmPhase = 0f

    // 3-Phasen Solina/Juno BBD Ensemble Chorus
    private val bbdBufL = FloatArray(2048)
    private val bbdBufR = FloatArray(2048)
    private var bbdWritePos = 0
    private var bbdLfoSlow = 0f
    private var bbdLfoFast = 0f

    // Stereo Schroeder Reverb
    private val comb1 = FloatArray(1499)
    private val comb2 = FloatArray(1693)
    private val comb3 = FloatArray(1877)
    private val comb4 = FloatArray(2053)
    private var cPos1 = 0; private var cPos2 = 0; private var cPos3 = 0; private var cPos4 = 0
    private val allpass1 = FloatArray(347)
    private val allpass2 = FloatArray(223)
    private var aPos1 = 0; private var aPos2 = 0

    // Stereo Ping-Pong Tape Delay
    private val delayBufL = FloatArray(44100)
    private val delayBufR = FloatArray(44100)
    private var delayWritePos = 0

    private var crushCounter = 0
    private var crushedL = 0f
    private var crushedR = 0f

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
            0 -> { attackMs = 6f; decayMs = 450f; sustainLevel = 0.80f; releaseMs = 300f }
            1 -> { attackMs = 480f; decayMs = 950f; sustainLevel = 0.88f; releaseMs = 920f }
            2 -> { attackMs = 1.2f; decayMs = 650f; sustainLevel = 0.0f; releaseMs = 280f }
            3 -> { attackMs = 2.0f; decayMs = 1300f; sustainLevel = 0.14f; releaseMs = 400f }
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
        vibratoDepth = preset.vibratoDepth
        tremoloDepth = preset.tremoloDepth
        glideSpeed = preset.glideSpeed
        reverbSend = preset.reverbSend
        fxChorus = preset.ensembleOn
        fxDelay = preset.delayOn
        for (v in 0..3) {
            val vp = preset.voices[v]
            voices[v].apply {
                engine = vp.engine
                fmRatioIndex = vp.fmRatioIndex
                timbre = vp.timbre
                morph = vp.morph
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
            VoicePresetData(vc.engine, vc.fmRatioIndex, vc.timbre, vc.morph, vc.detuneCents, vc.octShift, vc.pan, vc.level)
        }
        return SynthPreset(
            id = id, name = name, category = "USER PATCH",
            adsrMode = adsrMode, attackMs = attackMs, decayMs = decayMs,
            sustainLevel = sustainLevel, releaseMs = releaseMs,
            baseCutoff = filterWheel, filterResonance = filterResonance,
            filterEnvAmount = filterEnvAmount, vibratoDepth = vibratoDepth,
            tremoloDepth = tremoloDepth, glideSpeed = glideSpeed,
            reverbSend = reverbSend, ensembleOn = fxChorus, delayOn = fxDelay,
            voices = vData, isUser = true
        )
    }

    fun triggerChordAttack() { attackTriggerCount++ }
    fun resetClockSync() { syncResetRequested = true }

    private fun exciteVoice(v: Int, freq: Float) {
        voiceStage[v] = 1
        voiceFltEnv[v] = 1.0f
        voiceTineEnv[v] = 1.0f
        // Analog-Drift pro Anschlag leicht variieren -> verhindert jeden Phasen-Lock!
        voiceDrift[v] = 1f + (Random.nextFloat() - 0.5f) * 0.0016f

        // Karplus-Strong Delay-Line mit gefiltertem Hammer-/Zupf-Impuls füllen
        val period = (sampleRate / freq.coerceAtLeast(35f)).toInt().coerceIn(16, 1024)
        val line = ksDelayLines[v]
        var smoothNoise = 0f
        val brightness = voices[v].timbre.coerceIn(0.15f, 0.98f)
        for (idx in 0 until period) {
            val pos = (ksWritePos[v] - idx + line.size) % line.size
            val rawN = (Random.nextFloat() * 2f - 1f)
            val bodyWave = sin(idx.toFloat() / period.toFloat() * 2f * PI.toFloat())
            smoothNoise += brightness * ((rawN * 0.7f + bodyWave * 0.5f) - smoothNoise)
            line[pos] = smoothNoise
        }
        ksExciteRemaining[v] = (sampleRate * 0.0035f).toInt() // 3.5ms Pick/Hammer Burst
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        audioThread = thread(start = true, isDaemon = true, name = "WolfChordAudioThread") {
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(minBufSize).setTransferMode(AudioTrack.MODE_STREAM).build()

            val framesPerBlock = 256
            val stereoBuffer = ShortArray(framesPerBlock * 2)
            track.play()

            var sampleCounter = 0
            var beatSampleCounter = 0
            var arpStepCounter = 0
            var arpVoice = 0
            val pingPongOrder = intArrayOf(0, 1, 2, 3, 2, 1)
            var lastHandledAttackCount = attackTriggerCount

            while (isRunning) {
                val currentBpm = bpm.coerceIn(60, 200)
                val quarterSamples = ((sampleRate * 60) / currentBpm).coerceAtLeast(2000)
                val sixteenthSamples = (quarterSamples / 4).coerceAtLeast(500)
                val delaySamples = (sixteenthSamples * 3).coerceIn(1000, delayBufL.size - 1)
                val currentGlide = glideSpeed.coerceIn(0.0005f, 0.095f)

                val attSamples = (attackMs * 0.001f * sampleRate).coerceAtLeast(10f)
                val attackStep = 1f / attSamples
                val decSamples = (decayMs * 0.001f * sampleRate).coerceAtLeast(250f)
                val decayMul = 0.001f.pow(1f / decSamples)
                val relSamples = (releaseMs * 0.001f * sampleRate).coerceAtLeast(180f)
                val releaseMul = 0.001f.pow(1f / relSamples)
                val fltDecayMul = 0.001f.pow(1f / (decSamples * 0.38f))
                val tineDecayMul = 0.001f.pow(1f / (sampleRate * 0.14f)) // Schneller 140ms Tine-Decay für Rhodes/Bells!
                val targetSustain = if (adsrMode == 2) 0.0f else sustainLevel.coerceIn(0f, 1f)

                val wheelNorm = filterWheel.coerceIn(0.06f, 1.0f)
                val baseF = 0.035f + wheelNorm * wheelNorm * 0.56f
                val svfQ = (0.88f - filterResonance.coerceIn(0f, 0.65f)).coerceIn(0.26f, 0.88f)

                if (syncResetRequested) {
                    syncResetRequested = false
                    sampleCounter = 0
                    beatSampleCounter = 0
                    arpStepCounter = 0
                }

                for (i in 0 until framesPerBlock) {
                    sampleCounter++
                    beatSampleCounter = (beatSampleCounter + 1) % quarterSamples

                    lfoPhase = (lfoPhase + 5.2f / sampleRate) % 1f
                    tremPhase = (tremPhase + 4.4f / sampleRate) % 1f
                    pwmPhase = (pwmPhase + 0.9f / sampleRate) % 1f
                    bbdLfoSlow = (bbdLfoSlow + 0.58f / sampleRate) % 1f
                    bbdLfoFast = (bbdLfoFast + 5.6f / sampleRate) % 1f

                    val vibratoFactor = 1f + sin(lfoPhase * 2f * PI.toFloat()) * vibratoDepth
                    val pwmMod = sin(pwmPhase * 2f * PI.toFloat()) * 0.35f
                    val tremL = 1f - tremoloDepth * 0.45f * (0.5f + 0.5f * sin(tremPhase * 2f * PI.toFloat()))
                    val tremR = 1f - tremoloDepth * 0.45f * (0.5f - 0.5f * sin(tremPhase * 2f * PI.toFloat()))

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
                        // Natürliches Mikro-Strumming (3.5ms) oder Gitarren-Strum (32ms)
                        val strumStepSamples = if (strumMode) (sampleRate * 0.032f).toInt() else (sampleRate * 0.0035f).toInt()
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
                        if (aEnv > 0.0003f) {
                            val vc = voices[arpVoice]
                            val octMult = 2f.pow(vc.octShift.toFloat())
                            val rawTarget = targetFreqs[arpVoice] * octMult * 2f.pow(vc.detuneCents / 1200f) * vibratoFactor
                            if (rawTarget > 20f) {
                                if (arpSmoothFreq <= 20f) arpSmoothFreq = rawTarget
                                arpSmoothFreq += (rawTarget - arpSmoothFreq) * (currentGlide * 1.8f)

                                val rawSig = computeChordMachineVoice(0, vc, arpSmoothFreq, aEnv, voiceFltEnv[0], voiceTineEnv[0], pwmMod)
                                // Eigenes 2-Pol SVF Filter für den Arpeggiator
                                val fCut = (baseF + voiceFltEnv[0] * filterEnvAmount * 0.55f).coerceIn(0.03f, 0.78f)
                                voiceSvfLow[0] += fCut * voiceSvfBand[0]
                                val high = rawSig - voiceSvfLow[0] - svfQ * voiceSvfBand[0]
                                voiceSvfBand[0] += fCut * high
                                val filtered = voiceSvfLow[0]

                                val panNorm = ((vc.pan + 1f) * 0.5f).coerceIn(0f, 1f)
                                mixL += filtered * sqrt(1f - panNorm) * 1.35f
                                mixR += filtered * sqrt(panNorm) * 1.35f
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
                            if (aEnv <= 0.0003f) continue

                            val vc = voices[v]
                            val octMult = 2f.pow(vc.octShift.toFloat())
                            val tFreq = targetFreqs[v] * octMult * 2f.pow(vc.detuneCents / 1200f) * vibratoFactor * voiceDrift[v]
                            if (tFreq <= 20f) continue

                            if (currentFreqs[v] <= 20f) currentFreqs[v] = tFreq
                            currentFreqs[v] += (tFreq - currentFreqs[v]) * currentGlide
                            val freq = currentFreqs[v]

                            val rawSig = computeChordMachineVoice(v, vc, freq, aEnv, voiceFltEnv[v], voiceTineEnv[v], pwmMod)

                            // EIGENES 2-POL SVF FILTER PRO STIMME (mit Key-Tracking!)
                            val keyTrack = ((freq - 220f) / 1600f).coerceIn(-0.05f, 0.18f)
                            val fCut = (baseF + keyTrack + voiceFltEnv[v] * filterEnvAmount * 0.55f).coerceIn(0.03f, 0.78f)
                            voiceSvfLow[v] += fCut * voiceSvfBand[v]
                            val high = rawSig - voiceSvfLow[v] - svfQ * voiceSvfBand[v]
                            voiceSvfBand[v] += fCut * high
                            val filtered = voiceSvfLow[v]

                            val panNorm = ((vc.pan + 1f) * 0.5f).coerceIn(0f, 1f)
                            mixL += filtered * sqrt(1f - panNorm)
                            mixR += filtered * sqrt(panNorm)
                        }
                    }

                    // Stereo Tremolo / Auto-Panning (Rhodes & Vibraphone)
                    mixL *= tremL
                    mixR *= tremR

                    // Warm Analog Bass-Voice (OFF / ROOT / SLASH)
                    if (bassMode > 0 && targetBassFreq > 20f && activeEnvSum > 0.001f) {
                        if (currentBassFreq <= 20f) currentBassFreq = targetBassFreq
                        currentBassFreq += (targetBassFreq - currentBassFreq) * (currentGlide * 1.5f)
                        val bDt = (currentBassFreq / sampleRate).coerceIn(0.0001f, 0.25f)
                        bassPhase = (bassPhase + bDt) % 1f
                        val bSub = sin(bassPhase * 2f * PI.toFloat()) * 0.75f
                        val bWarmSaw = ((2f * bassPhase - 1f) - polyBlep(bassPhase, bDt)) * 0.22f * baseF
                        val bassSig = (bSub + bWarmSaw) * 0.20f * activeEnvSum
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

                    // 3-Phasen Solina/Juno BBD Ensemble Chorus
                    bbdBufL[bbdWritePos] = finalL
                    bbdBufR[bbdWritePos] = finalR
                    if (fxChorus) {
                        val twoPi = 2f * PI.toFloat()
                        val d1 = 280f + sin(bbdLfoSlow * twoPi) * 130f + sin(bbdLfoFast * twoPi) * 18f
                        val d2 = 310f + sin((bbdLfoSlow + 0.333f) * twoPi) * 130f + sin((bbdLfoFast + 0.333f) * twoPi) * 18f
                        val d3 = 340f + sin((bbdLfoSlow + 0.666f) * twoPi) * 130f + sin((bbdLfoFast + 0.666f) * twoPi) * 18f

                        val r1 = (bbdWritePos - d1.toInt() + bbdBufL.size) % bbdBufL.size
                        val r2 = (bbdWritePos - d2.toInt() + bbdBufR.size) % bbdBufR.size
                        val r3 = (bbdWritePos - d3.toInt() + bbdBufL.size) % bbdBufL.size

                        finalL = finalL * 0.68f + (bbdBufL[r1] + bbdBufR[r3] * 0.5f) * 0.38f
                        finalR = finalR * 0.68f + (bbdBufR[r2] + bbdBufL[r3] * 0.5f) * 0.38f
                    }
                    bbdWritePos = (bbdWritePos + 1) % bbdBufL.size

                    if (fxPump) {
                        val beatPhase = beatSampleCounter.toFloat() / quarterSamples.toFloat()
                        val pumpGain = (0.12f + 0.88f * beatPhase.pow(0.65f)).coerceIn(0.12f, 1f)
                        finalL *= pumpGain; finalR *= pumpGain
                    }

                    if (fxCrush) {
                        crushCounter++
                        if (crushCounter >= 9) {
                            crushCounter = 0
                            crushedL = round(finalL * 6f) / 6f
                            crushedR = round(finalR * 6f) / 6f
                        }
                        finalL = crushedL; finalR = crushedR
                    }

                    val readPos = (delayWritePos - delaySamples + delayBufL.size) % delayBufL.size
                    if (fxDelay) {
                        val dL = delayBufL[readPos]
                        val dR = delayBufR[readPos]
                        finalL += dR * 0.38f
                        finalR += dL * 0.38f
                        delayBufL[delayWritePos] = (finalL * 0.56f).coerceIn(-1f, 1f)
                        delayBufR[delayWritePos] = (finalR * 0.56f).coerceIn(-1f, 1f)
                    } else {
                        delayBufL[delayWritePos] = 0f
                        delayBufR[delayWritePos] = 0f
                    }
                    delayWritePos = (delayWritePos + 1) % delayBufL.size

                    if (fxReverb && reverbSend > 0.01f) {
                        val revIn = (finalL + finalR) * 0.5f * reverbSend
                        val c1 = comb1[cPos1]; comb1[cPos1] = (revIn + c1 * 0.78f).coerceIn(-1f, 1f); cPos1 = (cPos1 + 1) % comb1.size
                        val c2 = comb2[cPos2]; comb2[cPos2] = (revIn + c2 * 0.80f).coerceIn(-1f, 1f); cPos2 = (cPos2 + 1) % comb2.size
                        val c3 = comb3[cPos3]; comb3[cPos3] = (revIn + c3 * 0.82f).coerceIn(-1f, 1f); cPos3 = (cPos3 + 1) % comb3.size
                        val c4 = comb4[cPos4]; comb4[cPos4] = (revIn + c4 * 0.84f).coerceIn(-1f, 1f); cPos4 = (cPos4 + 1) % comb4.size

                        val combSumL = (c1 + c3) * 0.32f
                        val combSumR = (c2 + c4) * 0.32f

                        val ap1Buf = allpass1[aPos1]
                        val ap1Out = -combSumL + ap1Buf
                        allpass1[aPos1] = (combSumL + ap1Buf * 0.5f).coerceIn(-1f, 1f)
                        aPos1 = (aPos1 + 1) % allpass1.size

                        val ap2Buf = allpass2[aPos2]
                        val ap2Out = -combSumR + ap2Buf
                        allpass2[aPos2] = (combSumR + ap2Buf * 0.5f).coerceIn(-1f, 1f)
                        aPos2 = (aPos2 + 1) % allpass2.size

                        finalL += ap1Out * 0.42f
                        finalR += ap2Out * 0.42f
                    }

                    val outL = tanh(finalL * 1.15f)
                    val outR = tanh(finalR * 1.15f)
                    if (i < scopeBuffer.size) scopeBuffer[i] = (outL + outR) * 0.5f

                    stereoBuffer[i * 2] = (outL * 30500f).toInt().toShort()
                    stereoBuffer[i * 2 + 1] = (outR * 30500f).toInt().toShort()
                }
                track.write(stereoBuffer, 0, stereoBuffer.size)
            }
            try { track.stop(); track.release() } catch (_: Exception) {}
        }
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
                if (abs(voiceAmpEnv[v] - targetSustain) < 0.0006f) {
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
                if (voiceAmpEnv[v] < 0.0003f) {
                    voiceAmpEnv[v] = 0f
                    voiceStage[v] = 0
                }
            }
        }
        voiceFltEnv[v] *= fltDecayMul
        voiceTineEnv[v] *= tineDecayMul
    }

    // --- DIE 8 CHORD-MACHINE / DAISY-SP SYNTHESE-ALGORITHMEN ---
    private fun computeChordMachineVoice(
        v: Int,
        vc: VoiceConfig,
        freq: Float,
        ampEnv: Float,
        fltEnv: Float,
        tineEnv: Float,
        pwmLfo: Float
    ): Float {
        val dt = (freq / sampleRate).coerceIn(0.0001f, 0.45f)
        val pArr = oscPhases[v]
        pArr[0] = (pArr[0] + dt) % 1f
        val p0 = pArr[0]
        val twoPi = 2f * PI.toFloat()

        val sig = when (vc.engine) {
            0 -> { // VA_SAW: Dual Detuned PolyBLEP Analog Sawtooth
                val spread = 1f + vc.morph * 0.0085f
                val dt2 = (dt * spread).coerceIn(0.0001f, 0.45f)
                pArr[1] = (pArr[1] + dt2) % 1f
                val s1 = (2f * p0 - 1f) - polyBlep(p0, dt)
                val s2 = (2f * pArr[1] - 1f) - polyBlep(pArr[1], dt2)
                (s1 + s2 * 0.85f) * 0.55f
            }
            1 -> { // OCT_SAW: Chord Machine Octave-Saw + Detuned Pair
                val dtOct = (dt * 2.002f).coerceIn(0.0001f, 0.45f)
                val dtSub = (dt * 0.994f).coerceIn(0.0001f, 0.45f)
                pArr[1] = (pArr[1] + dtOct) % 1f
                pArr[2] = (pArr[2] + dtSub) % 1f
                val sMain = (2f * p0 - 1f) - polyBlep(p0, dt)
                val sOct = (2f * pArr[1] - 1f) - polyBlep(pArr[1], dtOct)
                val sWarm = (2f * pArr[2] - 1f) - polyBlep(pArr[2], dtSub)
                (sMain * 0.50f + sWarm * 0.45f + sOct * vc.morph * 0.45f) * 0.65f
            }
            2 -> { // VA_PWM: Variable Pulse Width + Sub-Triangle
                val pw = (0.5f + pwmLfo * vc.timbre).coerceIn(0.12f, 0.88f)
                var pulse = if (p0 < pw) 1f else -1f
                pulse += polyBlep(p0, dt)
                pulse -= polyBlep((p0 + 1f - pw) % 1f, dt)

                val dt2 = (dt * 1.0045f).coerceIn(0.0001f, 0.45f)
                pArr[1] = (pArr[1] + dt2) % 1f
                val saw2 = (2f * pArr[1] - 1f) - polyBlep(pArr[1], dt2)
                (pulse * 0.65f + saw2 * vc.morph * 0.45f) * 0.70f
            }
            3 -> { // FM1_EP: Rhodes / DX7 E-Piano (1:1 Warm Bark + Separate 1:7/1:14 Tine Bell!)
                val ratio = fmRatios[vc.fmRatioIndex.coerceIn(0, fmRatios.lastIndex)]
                modPhases[v] = (modPhases[v] + dt * ratio) % 1f
                tinePhases[v] = (tinePhases[v] + dt * 7.0f) % 1f

                // Bark/Body: Modulations-Index ist nur beim Anschlag hoch und wird sofort warm
                val bodyIndex = vc.timbre * (0.25f + 1.85f * fltEnv)
                val bodyMod = sin(modPhases[v] * twoPi) * bodyIndex
                val bodyCarrier = sin(p0 * twoPi + bodyMod)

                // Rhodes Tine: Inharmonischer Oberton mit kurzem 140ms Tine-Decay
                val tineBell = sin(tinePhases[v] * twoPi) * tineEnv * vc.morph * 0.55f
                (bodyCarrier * 0.85f + tineBell)
            }
            4 -> { // FM2_MALLET: Inharmonic Vibraphone / Marimba Modal Resonator
                // Nutzt ungerade/inharmonische Verhältnisse (1 : 3.5 oder 1 : 4) wie echte Metall-/Holz-Klangplatten
                val ratio = if (vc.fmRatioIndex == 3) 3.5f else 4.0f
                modPhases[v] = (modPhases[v] + dt * ratio) % 1f
                tinePhases[v] = (tinePhases[v] + dt * 9.2f) % 1f

                val modIdx = vc.timbre * fltEnv * 3.2f
                val modSig = sin(modPhases[v] * twoPi) * modIdx
                val fundamental = sin(p0 * twoPi + modSig)
                val strikeTransient = sin(tinePhases[v] * twoPi) * tineEnv * tineEnv * 0.45f
                (fundamental * 0.85f + strikeTransient)
            }
            5 -> { // FM3_BELL: Lush Shimmer / Glass FM Pad & Bell
                val ratio = fmRatios[vc.fmRatioIndex.coerceIn(0, fmRatios.lastIndex)]
                modPhases[v] = (modPhases[v] + dt * ratio) % 1f
                pArr[1] = (pArr[1] + dt * 1.003f) % 1f
                val modIdx = vc.timbre * (0.35f + 0.65f * fltEnv) * 2.6f
                val mod = sin(modPhases[v] * twoPi) * modIdx
                val c1 = sin(p0 * twoPi + mod)
                val c2 = sin(pArr[1] * twoPi - mod * 0.7f)
                (c1 + c2) * 0.55f
            }
            6 -> { // KS_STRING: ECHTE KARPLUS-STRONG WAVEGUIDE STRING (Harp, Guitar, Clavinet)
                stepKarplusStrong(v, freq, stringDamping = 0.25f + vc.morph * 0.70f, pickHardness = vc.timbre)
            }
            else -> { // HAMMER_PNO: Multi-String Acoustic Piano (Karplus-Strong + Hammer-Thump + Unison-String)
                val ksMain = stepKarplusStrong(v, freq, stringDamping = 0.65f + vc.morph * 0.30f, pickHardness = vc.timbre)
                // Zweite minimal verstimmte Klaviersaite (Bicord/Tricord Schwebung)
                val dt2 = (dt * 1.0009f).coerceIn(0.0001f, 0.45f)
                pArr[1] = (pArr[1] + dt2) % 1f
                val soundboard = sin(p0 * twoPi + sin(pArr[1] * twoPi) * 0.6f * fltEnv) * 0.40f
                val hammerThump = sin(p0 * twoPi * 0.5f) * tineEnv * tineEnv * 0.35f
                (ksMain * 0.78f + soundboard + hammerThump)
            }
        }

        return sig * 0.24f * vc.level * ampEnv
    }

    // Echter physikalischer Karplus-Strong Saiten-Algorithmus mit linearer Phasen-Interpolation
    private fun stepKarplusStrong(v: Int, freq: Float, stringDamping: Float, pickHardness: Float): Float {
        val line = ksDelayLines[v]
        val wPos = ksWritePos[v]

        val exactPeriod = (sampleRate / freq.coerceAtLeast(35f)).coerceIn(16f, (line.size - 4).toFloat())
        val intPeriod = exactPeriod.toInt()
        val frac = exactPeriod - intPeriod

        val rPos1 = (wPos - intPeriod + line.size) % line.size
        val rPos2 = (rPos1 - 1 + line.size) % line.size
        val delayedSample = line[rPos1] * (1f - frac) + line[rPos2] * frac

        // One-Pole Tiefpass in der Rückkopplungsschleife: Hohe Frequenzen verklingen schneller als tiefe (wie bei einer echten Saite!)
        val lpCoeff = (0.35f + pickHardness * 0.58f).coerceIn(0.25f, 0.94f)
        ksLpState[v] += lpCoeff * (delayedSample - ksLpState[v])

        var excite = 0f
        if (ksExciteRemaining[v] > 0) {
            ksExciteRemaining[v]--
            excite = (Random.nextFloat() * 2f - 1f) * pickHardness * 0.35f
        }

        val fb = (0.986f + stringDamping * 0.0125f).coerceIn(0.982f, 0.9988f)
        val newSample = (ksLpState[v] * fb + excite).coerceIn(-1.5f, 1.5f)
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

// --- MUSIKTHEORIE: WOLFPUNK CHORD MACHINE INVERSIONS + MAJOR/MINOR + 8-WAY JOYSTICK ---
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

// inversionMode: 0 = AUTO V-LEAD, 1 = ROOT (Inv 0), 2 = 1ST INV (Inv 1), 3 = 2ND INV (Inv 2)
fun buildWolfChordVoicing(
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

    // Wolfpunk Chord Machine Inversion & Voice-Leading Engine
    when (inversionMode) {
        0 -> { // AUTO VOICE-LEADING (Hält alle Noten eng um C4..G4 zusammen)
            val centerTarget = 64 + (octaveShift * 12)
            for (i in 0..3) {
                while (midiNotes[i] - centerTarget > 6) midiNotes[i] -= 12
                while (centerTarget - midiNotes[i] > 6) midiNotes[i] += 12
            }
            midiNotes.sort()
        }
        2 -> { // 1ST INVERSION (Grundton eine Oktave hoch)
            midiNotes[0] += 12
            midiNotes.sort()
        }
        3 -> { // 2ND INVERSION (Grundton + Terz eine Oktave hoch)
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
fun TeHiChordApp(synth: WolfChordEngine) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hichord_presets", Context.MODE_PRIVATE) }
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    var keyIndex by remember { mutableStateOf(0) }
    var isMinorScale by remember { mutableStateOf(false) }
    var bpm by remember { mutableStateOf(120) }
    var activeDegree by remember { mutableStateOf(-1) }
    var displayChord by remember { mutableStateOf("HI-CHORD") }

    var inversionMode by remember { mutableStateOf(0) } // 0: V-LEAD, 1: INV 0, 2: INV 1, 3: INV 2
    var bassMode by remember { mutableStateOf(1) }
    var octaveShift by remember { mutableStateOf(0) }
    var holdLatch by remember { mutableStateOf(false) }
    var strumMode by remember { mutableStateOf(false) }
    var filterWheel by remember { mutableStateOf(0.62f) }

    var waveIndex by remember { mutableStateOf(3) }
    var adsrMode by remember { mutableStateOf(3) }
    var arpMode by remember { mutableStateOf(0) }
    var activeSlot by remember { mutableStateOf(0) }
    var uiRefreshCounter by remember { mutableStateOf(0) }

    val userPresets = remember {
        val list = mutableStateListOf<SynthPreset>()
        val rawSet = prefs.getStringSet("user_presets_v6", emptySet()) ?: emptySet()
        rawSet.mapNotNull { SynthPreset.deserialize(it) }
            .sortedBy { it.name }
            .forEach { list.add(it) }
        list
    }

    val slotPresets = remember {
        mutableStateListOf(
            factoryPresets[0],  // FM E-PIANO
            factoryPresets[2],  // DELICATE HARP (Karplus-Strong)
            factoryPresets[4],  // WARM PAD
            factoryPresets[7]   // OCTAVE SAW
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
        prefs.edit().putStringSet("user_presets_v6", serialized).apply()
    }

    var joyX by remember { mutableStateOf(0f) }
    var joyY by remember { mutableStateOf(0f) }
    val activeJoyZone = getJoystickZone(joyX, joyY)

    fun syncChordState(deg: Int, retriggerAttack: Boolean) {
        activeDegree = deg
        if (deg in 0..6) {
            val res = buildWolfChordVoicing(keyIndex, isMinorScale, deg, octaveShift, activeJoyZone, inversionMode, bassMode)
            displayChord = res.displayName
            synth.playChord(res.voiceFreqs, res.bassFreq, retriggerAttack)
        } else {
            synth.releaseChord()
        }
    }

    LaunchedEffect(keyIndex, isMinorScale, octaveShift, activeJoyZone, inversionMode, bassMode) {
        if (activeDegree in 0..6) {
            val res = buildWolfChordVoicing(keyIndex, isMinorScale, activeDegree, octaveShift, activeJoyZone, inversionMode, bassMode)
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

// --- 90° QUERFORMAT: EXPERT 4-VOICE LAB & PRESET BROWSER ---
@Composable
fun TeExpertLandscapeScreen(
    synth: WolfChordEngine,
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
    uiRefreshCounter: Int,
    onParamChanged: () -> Unit,
    frameTick: Int,
    displayChord: String
) {
    val view = LocalView.current
    var showBrowser by remember { mutableStateOf(false) }
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
                            text = if (showBrowser) "HICHORD PRESETS" else "CHORD MACHINE LAB",
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
                title = if (showBrowser) "VOICE LAB" else "PRESETS",
                sub = "16 INSTRUMENTS",
                modifier = Modifier.weight(0.78f),
                height = 44.dp,
                bgColor = if (showBrowser) TeBlue else TeOchre,
                shadowColor = if (showBrowser) TeBlueShadow else Color(0xFFA67621),
                textColor = if (showBrowser) Color.White else TePanelDark,
                ledOn = showBrowser,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    showBrowser = !showBrowser
                }
            )

            TeTactileButton(
                title = "+ SAVE",
                sub = "USER PATCH",
                modifier = Modifier.weight(0.72f),
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

        if (showBrowser) {
            val allPresets = remember(userPresets.size, refresh) {
                userPresets + factoryPresets
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TePanelDark)
                    .border(1.5.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LOAD INSTRUMENT INTO SLOT 0${activeSlot + 1} (KARPLUS-STRONG STRINGS, DX-FM, BBD ENSEMBLE, ANALOG)",
                        color = Color(0xFF00E5FF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "USER PRESETS: ${userPresets.size}",
                        color = TeOchre,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(allPresets, key = { it.id }) { preset ->
                        val isLoadedInActiveSlot = slotPresets[activeSlot].id == preset.id
                        Box(
                            modifier = Modifier
                                .height(54.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        isLoadedInActiveSlot -> TeOrange
                                        preset.isUser -> Color(0xFF16324A)
                                        else -> Color(0xFF282826)
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isLoadedInActiveSlot) Color.White else Color(0xFF484844),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onAssignPresetToSlot(preset, activeSlot)
                                }
                                .padding(6.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = preset.category,
                                        color = if (isLoadedInActiveSlot) Color.White else if (preset.isUser) Color(0xFF00E5FF) else TeOchre,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (preset.isUser) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(TeLedRed)
                                                .clickable {
                                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    onDeleteUserPreset(preset)
                                                }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "DELETE",
                                                color = Color.White,
                                                fontSize = 7.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = preset.name,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (v in 0..3) {
                    val vc = synth.voices[v]
                    val unused = refresh
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(TePanelDark)
                            .border(1.5.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = voiceTitles[v], color = Color(0xFF9E9C95), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF333333))
                                        .clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            vc.octShift = when (vc.octShift) { -1 -> 0; 0 -> 1; else -> -1 }
                                            onParamChanged()
                                        }
                                        .padding(horizontal = 5.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "OCT:${if (vc.octShift >= 0) "+${vc.octShift}" else vc.octShift}",
                                        color = TeOchre,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(TeOrange)
                                        .clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            vc.engine = (vc.engine + 1) % engineNames.size
                                            onParamChanged()
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(text = engineNames[vc.engine], color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        TeBarSlider(label = "TIMBRE / BRIGHT", valueText = "${(vc.timbre * 100).roundToInt()}%", fraction = vc.timbre, barColor = Color(0xFF00E5FF), onValueChange = { vc.timbre = it; onParamChanged() })
                        TeBarSlider(label = "MORPH / DAMPING", valueText = "${(vc.morph * 100).roundToInt()}%", fraction = vc.morph, barColor = TeBlue, onValueChange = { vc.morph = it; onParamChanged() })
                        TeBarSlider(label = "DETUNE", valueText = "${if (vc.detuneCents >= 0) "+${vc.detuneCents}" else vc.detuneCents}ct", fraction = (vc.detuneCents + 50f) / 100f, barColor = TeOchre, onValueChange = { vc.detuneCents = ((it * 100f) - 50f).roundToInt(); onParamChanged() })
                        val panInt = (vc.pan * 100).roundToInt()
                        val panLabel = when { panInt < -5 -> "L${-panInt}"; panInt > 5 -> "R$panInt"; else -> "CTR" }
                        TeBarSlider(label = "STEREO PAN", valueText = panLabel, fraction = (vc.pan + 1f) * 0.5f, barColor = TeOrange, onValueChange = { vc.pan = (it * 2f) - 1f; onParamChanged() })
                        TeBarSlider(label = "VOICE LEVEL", valueText = "${(vc.level * 100).roundToInt()}%", fraction = vc.level, barColor = Color(0xFF4CAF50), onValueChange = { vc.level = it; onParamChanged() })
                    }
                }
            }
        }

        // 3. BOTTOM AUDITION STRIP
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TeTactileButton(
                title = "HOLD",
                sub = "LATCH",
                modifier = Modifier.weight(0.95f),
                height = 46.dp,
                bgColor = if (holdLatch) TeBlue else TeKeyDark,
                shadowColor = if (holdLatch) TeBlueShadow else TeKeyDarkShadow,
                textColor = Color.White,
                ledOn = holdLatch,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onToggleHold()
                }
            )

            var stripWidth by remember { mutableStateOf(1) }
            Row(
                modifier = Modifier
                    .weight(7f)
                    .fillMaxHeight()
                    .onSizeChanged { stripWidth = it.width.coerceAtLeast(1) }
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(top = if (isAct) 2.dp else 0.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isAct) TeOrangeShadow else TeKeyLightShadow)
                            .padding(bottom = if (isAct) 1.dp else 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isAct) TeOrange else TeKeyLight)
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = romanDegrees[deg],
                                color = if (isAct) Color.White.copy(alpha = 0.8f) else Color(0xFF6E6D68),
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                            Text(
                                text = "$noteLabel$qualSuffix",
                                color = if (isAct) Color.White else TePanelDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.align(Alignment.BottomEnd)
                            )
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

// --- HOCHFORMAT: WOLFPUNK / HICHORD COMPANION INTERFACE ---
@Composable
fun TePortraitJamScreen(
    synth: WolfChordEngine,
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
    var fxCrush by remember(uiRefreshCounter) { mutableStateOf(synth.fxCrush) }
    var fxStutter by remember(uiRefreshCounter) { mutableStateOf(synth.fxStutter) }
    var fxChorus by remember(uiRefreshCounter) { mutableStateOf(synth.fxChorus) }
    var fxPump by remember(uiRefreshCounter) { mutableStateOf(synth.fxPump) }
    var fxReverb by remember(uiRefreshCounter) { mutableStateOf(synth.fxReverb) }

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
        // 1. HARDWARE HEADER + HICHORD FILTER WHEEL
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScrewHead()
                Text(
                    text = "HICHORD // CHORD MACHINE (ROTATE 90° FOR LAB)",
                    color = TePanelDark,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
            var wheelW by remember { mutableStateOf(1) }
            Box(
                modifier = Modifier
                    .width(108.dp)
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
                    text = "TONE WHEEL ${(filterWheel * 100).roundToInt()}%",
                    color = Color.White,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }

        // 2. OLED DISPLAY & 8-WEGE CHORD JOYSTICK
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
                    for (c in 1 until cols) for (r in 1 until rows) drawCircle(Color(0xFF282826), 1.5f, Offset(c * stepX, r * stepY))
                }
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        val scaleName = if (isMinorScale) "${rootNames[keyIndex]}m" else rootNames[keyIndex]
                        DisplayBadge(text = "KEY $scaleName", bgColor = if (isMinorScale) TeBlue else TeOrange, textColor = Color.White)
                        DisplayBadge(text = "$bpm BPM", bgColor = Color(0xFF2E2E2E), textColor = Color(0xFF00E5FF))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2E2E2E))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onCycleSlot()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = "▸ ${activePresetName.take(10)}", color = TeOchre, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text(
                                text = when {
                                    activeJoyZone != JoyChordZone.CENTER -> "JOYSTICK // ${activeJoyZone.desc}"
                                    activeDegree >= 0 -> "$activePresetCategory · ${if (isMinorScale) "MINOR" else "MAJOR"} · ${invLabels[inversionMode]}"
                                    else -> "TAP PRESET OR ROTATE 90° FOR LAB"
                                },
                                color = if (activeJoyZone != JoyChordZone.CENTER) Color(0xFF00E5FF) else Color(0xFF888884),
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
                            text = invLabels[inversionMode],
                            color = TeOchre,
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

        // 3. SYSTEM, TONART, 1-BPM TEMPO, TAP-SYNC, WAVE, ADSR & ARP
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

        // 4. PERFORMANCE FX + BBD ENSEMBLE, REVERB & SIDECHAIN PUMP
        SectionDivider("PERFORMANCE FX // MOMENTARY FX + BBD ENSEMBLE, REVERB & PUMP")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TeMomentaryFxButton(title = "DELAY", sub = "ECHO", isPressed = fxDelay, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxDelay = true; synth.fxDelay = true }, onRelease = { fxDelay = false; synth.fxDelay = false })
            TeMomentaryFxButton(title = "CRUSH", sub = "8-BIT", isPressed = fxCrush, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxCrush = true; synth.fxCrush = true }, onRelease = { fxCrush = false; synth.fxCrush = false })
            TeMomentaryFxButton(title = "STUTTR", sub = "1/32", isPressed = fxStutter, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxStutter = true; synth.fxStutter = true }, onRelease = { fxStutter = false; synth.fxStutter = false })
            TeTactileButton(title = "ENSEMB", sub = "3-BBD", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxChorus) TeOrange else TeBlue, shadowColor = if (fxChorus) TeOrangeShadow else TeBlueShadow, textColor = Color.White, ledOn = fxChorus, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxChorus = !fxChorus; synth.fxChorus = fxChorus })
            TeTactileButton(title = "REVERB", sub = "HALL", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxReverb) TeOrange else TeKeyDark, shadowColor = if (fxReverb) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = fxReverb, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxReverb = !fxReverb; synth.fxReverb = fxReverb })
            TeTactileButton(title = "PUMP", sub = "DUCK", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxPump) TeOrange else TeBlue, shadowColor = if (fxPump) TeOrangeShadow else TeBlueShadow, textColor = Color.White, ledOn = fxPump, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxPump = !fxPump; synth.fxPump = fxPump })
        }

        // 5. WOLFPUNK CHORD MACHINE MODES (DEDICATED MAJOR/MINOR, INVERSION, HOLD, STRUM, BASS, OCT)
        SectionDivider("CHORD MACHINE CONTROLS // MAJOR/MINOR, INV 0-2, HOLD, STRUM & BASS")
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
            TeTactileButton(title = "STRUM", sub = "PLUCK", modifier = Modifier.weight(0.95f), height = 46.dp, bgColor = if (strumMode) TeOrange else TeKeyDark, shadowColor = if (strumMode) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = strumMode, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleStrum() })
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

        // 6. 7 DIATONIC CHORD BUTTONS (I - VII)
        SectionDivider("DIATONIC CHORD KEYS // INSTANT TRIGGER & LEGATO SLIDE (I - VII)")
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
