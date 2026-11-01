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
    private val synth = HiChordEngine()

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

// --- VOICE & PRESET ARCHITEKTUR ---
// Engine Types:
// 0: ANALOG SAW (Warm PolyBLEP Saw + Sub-Warmth)
// 1: PWM PULSE (Modulated Pulse Width for Strings, Choir & Vintage Synths)
// 2: PURE TRI/SINE (Warm Triangle-Sine Hybrid with Bell Harmonics)
// 3: 5-SAW CLUSTER (True 5-Oscillator Detuned SuperSaw per Voice)
// 4: DX 2-OP FM (True Phase-Modulation FM with Dynamic Envelope Index for Rhodes/Bells)
// 5: PLUCKED STRING (Harmonic Damped String / Piano Hammer Model)
val engineNames = listOf("SAW", "PWM", "TRI", "SSAW", "DXFM", "STRG")
val fmRatios = floatArrayOf(0.5f, 1.0f, 2.0f, 3.0f, 4.0f, 7.0f)
val adsrNames = listOf("SUST", "SWEL", "PLCK", "KEYS")

class VoiceConfig(
    @Volatile var engine: Int = 4,
    @Volatile var fmRatioIndex: Int = 1,
    @Volatile var fmDepth: Float = 0.25f,
    @Volatile var detuneCents: Int = 0,
    @Volatile var octShift: Int = 0,
    @Volatile var pan: Float = 0f,
    @Volatile var level: Float = 0.85f
)

data class VoicePresetData(
    val engine: Int,
    val fmRatioIndex: Int,
    val fmDepth: Float,
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
    val hammerClick: Float,     // Transient-Anschlag (Piano-Hammer / Gitarren-Plektrum / Mallet)
    val baseCutoff: Float,
    val filterResonance: Float,
    val filterEnvAmount: Float,
    val vibratoDepth: Float,
    val pwmDepth: Float,        // Bewegt die Pulsbreite für lebendigen Nicht-Orgel-Sound
    val glideSpeed: Float,
    val reverbSend: Float,
    val chorusOn: Boolean,
    val delayOn: Boolean,
    val voices: List<VoicePresetData>,
    val isUser: Boolean = false
) {
    fun serialize(): String {
        val vStr = voices.joinToString(";") {
            "${it.engine},${it.fmRatioIndex},${it.fmDepth},${it.detuneCents},${it.octShift},${it.pan},${it.level}"
        }
        return "$id|$name|$category|$adsrMode|$attackMs|$decayMs|$sustainLevel|$releaseMs|$hammerClick|$baseCutoff|$filterResonance|$filterEnvAmount|$vibratoDepth|$pwmDepth|$glideSpeed|$reverbSend|$chorusOn|$delayOn|$vStr"
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
                        fmDepth = vp[2].toFloat(),
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
                    hammerClick = p[8].toFloat(), baseCutoff = p[9].toFloat(),
                    filterResonance = p[10].toFloat(), filterEnvAmount = p[11].toFloat(),
                    vibratoDepth = p[12].toFloat(), pwmDepth = p[13].toFloat(),
                    glideSpeed = p[14].toFloat(), reverbSend = p[15].toFloat(),
                    chorusOn = p[16].toBoolean(), delayOn = p[17].toBoolean(),
                    voices = vList, isUser = true
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

// --- DIE 16 ORIGINALEN HICHORD HARDWARE-PRESETS (OHNE GENRE-GIMMICKS) ---
val factoryPresets = listOf(
    // 01. FM E-PIANO (Warm DX7 / Rhodes Tine Piano)
    SynthPreset(
        id = "hc_01_epiano", name = "FM E-PIANO", category = "KEYS",
        adsrMode = 3, attackMs = 2f, decayMs = 1400f, sustainLevel = 0.14f, releaseMs = 420f,
        hammerClick = 0.35f, baseCutoff = 0.46f, filterResonance = 0.25f, filterEnvAmount = 0.45f,
        vibratoDepth = 0.0018f, pwmDepth = 0f, glideSpeed = 0.040f, reverbSend = 0.35f,
        chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 4, fmRatioIndex = 1, fmDepth = 0.32f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 1, fmDepth = 0.38f, detuneCents = -6, octShift = 0, pan = -0.55f, level = 0.86f),
            VoicePresetData(engine = 4, fmRatioIndex = 2, fmDepth = 0.25f, detuneCents = 6, octShift = 0, pan = 0.55f, level = 0.86f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.18f, detuneCents = 10, octShift = 0, pan = 0.20f, level = 0.68f)
        )
    ),
    // 02. GRAND PIANO (Acoustic Hammer-String Modeling)
    SynthPreset(
        id = "hc_02_piano", name = "GRAND PIANO", category = "KEYS",
        adsrMode = 2, attackMs = 1.5f, decayMs = 1150f, sustainLevel = 0.0f, releaseMs = 340f,
        hammerClick = 0.55f, baseCutoff = 0.52f, filterResonance = 0.20f, filterEnvAmount = 0.58f,
        vibratoDepth = 0.0003f, pwmDepth = 0f, glideSpeed = 0.080f, reverbSend = 0.32f,
        chorusOn = false, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 5, fmRatioIndex = 1, fmDepth = 0.35f, detuneCents = -2, octShift = 0, pan = -0.25f, level = 0.95f),
            VoicePresetData(engine = 5, fmRatioIndex = 1, fmDepth = 0.35f, detuneCents = 2, octShift = 0, pan = 0.25f, level = 0.92f),
            VoicePresetData(engine = 5, fmRatioIndex = 2, fmDepth = 0.22f, detuneCents = -4, octShift = 0, pan = -0.55f, level = 0.88f),
            VoicePresetData(engine = 5, fmRatioIndex = 2, fmDepth = 0.22f, detuneCents = 4, octShift = 0, pan = 0.55f, level = 0.85f)
        )
    ),
    // 03. WARM PAD (Juno-60 Lush Analog PWM + Saw Pad)
    SynthPreset(
        id = "hc_03_warmpad", name = "WARM PAD", category = "SYNTH PAD",
        adsrMode = 1, attackMs = 420f, decayMs = 900f, sustainLevel = 0.88f, releaseMs = 950f,
        hammerClick = 0f, baseCutoff = 0.28f, filterResonance = 0.35f, filterEnvAmount = 0.22f,
        vibratoDepth = 0.0020f, pwmDepth = 0.35f, glideSpeed = 0.0035f, reverbSend = 0.55f,
        chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -9, octShift = 0, pan = -0.60f, level = 0.90f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 9, octShift = 0, pan = 0.60f, level = 0.90f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -16, octShift = 0, pan = -0.80f, level = 0.82f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 16, octShift = 0, pan = 0.80f, level = 0.82f)
        )
    ),
    // 04. STRINGS (Solina / Ensemble String Machine)
    SynthPreset(
        id = "hc_04_strings", name = "STRINGS", category = "ENSEMBLE",
        adsrMode = 1, attackMs = 520f, decayMs = 800f, sustainLevel = 0.92f, releaseMs = 880f,
        hammerClick = 0f, baseCutoff = 0.48f, filterResonance = 0.30f, filterEnvAmount = 0.15f,
        vibratoDepth = 0.0032f, pwmDepth = 0.42f, glideSpeed = 0.0050f, reverbSend = 0.58f,
        chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -12, octShift = 0, pan = -0.75f, level = 0.88f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 12, octShift = 0, pan = 0.75f, level = 0.88f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -19, octShift = 0, pan = -0.45f, level = 0.82f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 19, octShift = 1, pan = 0.45f, level = 0.62f)
        )
    ),
    // 05. SAW SYNTH (Prophet-5 Polyphonic Analog Synth)
    SynthPreset(
        id = "hc_05_sawsynth", name = "SAW SYNTH", category = "ANALOG",
        adsrMode = 0, attackMs = 8f, decayMs = 450f, sustainLevel = 0.80f, releaseMs = 320f,
        hammerClick = 0.12f, baseCutoff = 0.54f, filterResonance = 0.42f, filterEnvAmount = 0.42f,
        vibratoDepth = 0.0014f, pwmDepth = 0f, glideSpeed = 0.0065f, reverbSend = 0.30f,
        chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -7, octShift = 0, pan = -0.40f, level = 0.92f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 7, octShift = 0, pan = 0.40f, level = 0.92f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -14, octShift = 0, pan = -0.75f, level = 0.86f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 14, octShift = 0, pan = 0.75f, level = 0.86f)
        )
    ),
    // 06. SUPERSAW (Wide 5-Oscillator per Voice Stack)
    SynthPreset(
        id = "hc_06_supersaw", name = "SUPERSAW", category = "ANALOG",
        adsrMode = 0, attackMs = 6f, decayMs = 500f, sustainLevel = 0.90f, releaseMs = 380f,
        hammerClick = 0.10f, baseCutoff = 0.68f, filterResonance = 0.35f, filterEnvAmount = 0.35f,
        vibratoDepth = 0.0010f, pwmDepth = 0f, glideSpeed = 0.0055f, reverbSend = 0.42f,
        chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -10, octShift = 0, pan = -0.50f, level = 0.92f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 10, octShift = 0, pan = 0.50f, level = 0.92f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -24, octShift = 0, pan = -0.90f, level = 0.88f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 24, octShift = 0, pan = 0.90f, level = 0.88f)
        )
    ),
    // 07. SYNTH BRASS (OB-Xa / CS-80 Filter Swell Brass)
    SynthPreset(
        id = "hc_07_brass", name = "SYNTH BRASS", category = "ANALOG",
        adsrMode = 0, attackMs = 38f, decayMs = 520f, sustainLevel = 0.84f, releaseMs = 340f,
        hammerClick = 0.05f, baseCutoff = 0.24f, filterResonance = 0.48f, filterEnvAmount = 0.68f,
        vibratoDepth = 0.0022f, pwmDepth = 0.15f, glideSpeed = 0.0030f, reverbSend = 0.38f,
        chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.06f, detuneCents = -8, octShift = 0, pan = -0.45f, level = 0.94f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 8, octShift = 0, pan = 0.45f, level = 0.90f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -18, octShift = 0, pan = -0.85f, level = 0.88f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.08f, detuneCents = 18, octShift = 0, pan = 0.85f, level = 0.88f)
        )
    ),
    // 08. VIBRAPHONE (Metallic Mallet Strike + Motor Tremolo)
    SynthPreset(
        id = "hc_08_vibraphone", name = "VIBRAPHONE", category = "MALLET",
        adsrMode = 2, attackMs = 1.5f, decayMs = 1050f, sustainLevel = 0.0f, releaseMs = 520f,
        hammerClick = 0.48f, baseCutoff = 0.58f, filterResonance = 0.22f, filterEnvAmount = 0.40f,
        vibratoDepth = 0.0042f, pwmDepth = 0f, glideSpeed = 0.060f, reverbSend = 0.48f,
        chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.35f, detuneCents = 0, octShift = 0, pan = -0.25f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.38f, detuneCents = -4, octShift = 0, pan = 0.25f, level = 0.88f),
            VoicePresetData(engine = 2, fmRatioIndex = 3, fmDepth = 0.22f, detuneCents = 4, octShift = 0, pan = -0.60f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.28f, detuneCents = 7, octShift = 1, pan = 0.60f, level = 0.65f)
        )
    ),
    // 09. DELICATE HARP (Plucked Acoustic Harp Strings)
    SynthPreset(
        id = "hc_09_harp", name = "DELICATE HARP", category = "PLUCK",
        adsrMode = 2, attackMs = 1.5f, decayMs = 680f, sustainLevel = 0.0f, releaseMs = 420f,
        hammerClick = 0.42f, baseCutoff = 0.42f, filterResonance = 0.28f, filterEnvAmount = 0.65f,
        vibratoDepth = 0.0008f, pwmDepth = 0f, glideSpeed = 0.050f, reverbSend = 0.54f,
        chorusOn = false, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 5, fmRatioIndex = 1, fmDepth = 0.24f, detuneCents = 0, octShift = 0, pan = -0.30f, level = 0.95f),
            VoicePresetData(engine = 5, fmRatioIndex = 2, fmDepth = 0.20f, detuneCents = -5, octShift = 0, pan = 0.30f, level = 0.90f),
            VoicePresetData(engine = 5, fmRatioIndex = 1, fmDepth = 0.24f, detuneCents = 5, octShift = 0, pan = -0.65f, level = 0.88f),
            VoicePresetData(engine = 5, fmRatioIndex = 3, fmDepth = 0.18f, detuneCents = 8, octShift = 1, pan = 0.65f, level = 0.72f)
        )
    ),
    // 10. WARM E-GUITAR (Jazz / Indie Clean Picked Electric Guitar)
    SynthPreset(
        id = "hc_10_eguitar", name = "WARM E-GUITAR", category = "PLUCK",
        adsrMode = 2, attackMs = 2f, decayMs = 760f, sustainLevel = 0.0f, releaseMs = 320f,
        hammerClick = 0.52f, baseCutoff = 0.34f, filterResonance = 0.38f, filterEnvAmount = 0.62f,
        vibratoDepth = 0.0020f, pwmDepth = 0.18f, glideSpeed = 0.015f, reverbSend = 0.42f,
        chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 5, fmRatioIndex = 1, fmDepth = 0.30f, detuneCents = -3, octShift = 0, pan = -0.35f, level = 0.95f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0.15f, detuneCents = 3, octShift = 0, pan = 0.35f, level = 0.88f),
            VoicePresetData(engine = 5, fmRatioIndex = 2, fmDepth = 0.25f, detuneCents = -7, octShift = 0, pan = -0.65f, level = 0.86f),
            VoicePresetData(engine = 5, fmRatioIndex = 1, fmDepth = 0.22f, detuneCents = 7, octShift = 0, pan = 0.65f, level = 0.84f)
        )
    ),
    // 11. 60s COMBO ORGAN (Vox Continental / Farfisa Transistor Organ)
    SynthPreset(
        id = "hc_11_organ", name = "60s ORGAN", category = "ORGAN",
        adsrMode = 0, attackMs = 4f, decayMs = 200f, sustainLevel = 0.95f, releaseMs = 110f,
        hammerClick = 0.28f, baseCutoff = 0.62f, filterResonance = 0.20f, filterEnvAmount = 0.10f,
        vibratoDepth = 0.0038f, pwmDepth = 0.25f, glideSpeed = 0.070f, reverbSend = 0.34f,
        chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.22f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.92f),
            VoicePresetData(engine = 1, fmRatioIndex = 2, fmDepth = 0.18f, detuneCents = -4, octShift = 0, pan = -0.45f, level = 0.85f),
            VoicePresetData(engine = 2, fmRatioIndex = 3, fmDepth = 0.25f, detuneCents = 4, octShift = 0, pan = 0.45f, level = 0.80f),
            VoicePresetData(engine = 1, fmRatioIndex = 4, fmDepth = 0.15f, detuneCents = 6, octShift = 1, pan = 0f, level = 0.65f)
        )
    ),
    // 12. TAPE KEYS (Wurlitzer / Lo-Fi Warble Keys)
    SynthPreset(
        id = "hc_12_tapekeys", name = "TAPE KEYS", category = "KEYS",
        adsrMode = 3, attackMs = 3f, decayMs = 950f, sustainLevel = 0.18f, releaseMs = 360f,
        hammerClick = 0.38f, baseCutoff = 0.32f, filterResonance = 0.32f, filterEnvAmount = 0.48f,
        vibratoDepth = 0.0048f, pwmDepth = 0.20f, glideSpeed = 0.025f, reverbSend = 0.38f,
        chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.26f, detuneCents = -5, octShift = 0, pan = -0.30f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 1, fmDepth = 0.34f, detuneCents = 5, octShift = 0, pan = 0.30f, level = 0.88f),
            VoicePresetData(engine = 1, fmRatioIndex = 2, fmDepth = 0.18f, detuneCents = -11, octShift = 0, pan = -0.65f, level = 0.84f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.22f, detuneCents = 11, octShift = 0, pan = 0.65f, level = 0.76f)
        )
    ),
    // 13. CHOIR PAD (Vocal Formant Synth Pad)
    SynthPreset(
        id = "hc_13_choir", name = "CHOIR PAD", category = "SYNTH PAD",
        adsrMode = 1, attackMs = 460f, decayMs = 900f, sustainLevel = 0.90f, releaseMs = 920f,
        hammerClick = 0f, baseCutoff = 0.36f, filterResonance = 0.52f, filterEnvAmount = 0.25f,
        vibratoDepth = 0.0028f, pwmDepth = 0.48f, glideSpeed = 0.0030f, reverbSend = 0.62f,
        chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 2, fmDepth = 0.22f, detuneCents = -8, octShift = 0, pan = -0.55f, level = 0.90f),
            VoicePresetData(engine = 1, fmRatioIndex = 2, fmDepth = 0.22f, detuneCents = 8, octShift = 0, pan = 0.55f, level = 0.90f),
            VoicePresetData(engine = 2, fmRatioIndex = 3, fmDepth = 0.28f, detuneCents = -14, octShift = 0, pan = -0.80f, level = 0.82f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.20f, detuneCents = 14, octShift = 1, pan = 0.80f, level = 0.68f)
        )
    ),
    // 14. MARIMBA (Wooden Percussive Mallet Pluck)
    SynthPreset(
        id = "hc_14_marimba", name = "MARIMBA", category = "MALLET",
        adsrMode = 2, attackMs = 1.2f, decayMs = 320f, sustainLevel = 0.0f, releaseMs = 190f,
        hammerClick = 0.62f, baseCutoff = 0.38f, filterResonance = 0.34f, filterEnvAmount = 0.65f,
        vibratoDepth = 0.0005f, pwmDepth = 0f, glideSpeed = 0.070f, reverbSend = 0.36f,
        chorusOn = false, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 4, fmDepth = 0.42f, detuneCents = 0, octShift = 0, pan = -0.25f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.38f, detuneCents = -4, octShift = 0, pan = 0.25f, level = 0.88f),
            VoicePresetData(engine = 2, fmRatioIndex = 2, fmDepth = 0.30f, detuneCents = 4, octShift = 0, pan = -0.55f, level = 0.85f),
            VoicePresetData(engine = 2, fmRatioIndex = 4, fmDepth = 0.35f, detuneCents = 6, octShift = 0, pan = 0.55f, level = 0.80f)
        )
    ),
    // 15. 8-BIT CHIP (Pocket Arcade Chiptune Synth)
    SynthPreset(
        id = "hc_15_chip", name = "8-BIT CHIP", category = "CHIPTUNE",
        adsrMode = 2, attackMs = 1.2f, decayMs = 440f, sustainLevel = 0.0f, releaseMs = 200f,
        hammerClick = 0.35f, baseCutoff = 0.82f, filterResonance = 0.20f, filterEnvAmount = 0.35f,
        vibratoDepth = 0f, pwmDepth = 0.40f, glideSpeed = 0.090f, reverbSend = 0.22f,
        chorusOn = false, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 0, octShift = 0, pan = -0.35f, level = 0.92f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -5, octShift = 0, pan = 0.35f, level = 0.88f),
            VoicePresetData(engine = 1, fmRatioIndex = 2, fmDepth = 0f, detuneCents = 5, octShift = 0, pan = -0.65f, level = 0.85f),
            VoicePresetData(engine = 1, fmRatioIndex = 2, fmDepth = 0f, detuneCents = 0, octShift = 1, pan = 0.65f, level = 0.72f)
        )
    ),
    // 16. BASS CHORD (Deep Sub-Driven House/Funk Chord Stack)
    SynthPreset(
        id = "hc_16_basschord", name = "BASS CHORD", category = "PLUCK",
        adsrMode = 2, attackMs = 2f, decayMs = 520f, sustainLevel = 0.0f, releaseMs = 260f,
        hammerClick = 0.45f, baseCutoff = 0.22f, filterResonance = 0.52f, filterEnvAmount = 0.72f,
        vibratoDepth = 0.0010f, pwmDepth = 0.22f, glideSpeed = 0.009f, reverbSend = 0.30f,
        chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.18f, detuneCents = 0, octShift = -1, pan = 0f, level = 1.0f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0.12f, detuneCents = -10, octShift = 0, pan = -0.60f, level = 0.88f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 10, octShift = 0, pan = 0.60f, level = 0.88f),
            VoicePresetData(engine = 0, fmRatioIndex = 2, fmDepth = 0.15f, detuneCents = 14, octShift = 0, pan = 0f, level = 0.78f)
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

// --- HICHORD ENGINE V5: PER-VOICE ADSR, HAMMER TRANSIENT, KARPLUS STRING & ZERO-MISS TRIGGER ---
class HiChordEngine {
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
    @Volatile var bassMode = 1 // 0: OFF, 1: ROOT, 2: SLASH
    @Volatile var filterWheel = 0.52f

    // Klangparameter pro Preset
    @Volatile var adsrMode = 3
    @Volatile var attackMs = 3f
    @Volatile var decayMs = 1100f
    @Volatile var sustainLevel = 0.15f
    @Volatile var releaseMs = 380f
    @Volatile var hammerClick = 0.35f
    @Volatile var baseCutoff = 0.46f
    @Volatile var filterResonance = 0.30f
    @Volatile var filterEnvAmount = 0.45f
    @Volatile var vibratoDepth = 0.0018f
    @Volatile var pwmDepth = 0.20f
    @Volatile var glideSpeed = 0.025f
    @Volatile var reverbSend = 0.35f

    @Volatile var arpMode = 0

    // Performance FX
    @Volatile var fxDelay = false
    @Volatile var fxCrush = false
    @Volatile var fxStutter = false
    @Volatile var fxChorus = true
    @Volatile var fxPump = false
    @Volatile var fxReverb = true

    @Volatile private var syncResetRequested = false
    // Zähler statt simplen Booleans verhindert verschluckte Tasten-Trigger zu 100%!
    @Volatile private var attackTriggerCount = 0

    val voices = Array(4) { v ->
        VoiceConfig(
            engine = 4,
            fmRatioIndex = 1,
            fmDepth = 0.30f,
            detuneCents = when (v) { 0 -> 0; 1 -> -6; 2 -> 6; else -> 10 },
            octShift = 0,
            pan = when (v) { 0 -> 0f; 1 -> -0.55f; 2 -> 0.55f; else -> 0.2f },
            level = 0.85f
        )
    }

    val scopeBuffer = FloatArray(128)

    // 5 Sub-Oszillator-Phasen pro Stimme für echten 5-Saw Hypersaw-Cluster
    private val oscPhases = Array(4) { FloatArray(5) { Random.nextFloat() } }
    private val modPhases = FloatArray(4) { Random.nextFloat() }

    // Eigene unabhängige ADSR- & Filter-Hüllkurve pro Stimme (verhindert den statischen Kirchenorgel-Effekt!)
    private val voiceStage = IntArray(4) { 0 }
    private val voiceAmpEnv = FloatArray(4) { 0f }
    private val voiceHarmEnv = FloatArray(4) { 0f }
    private val voiceClickEnv = FloatArray(4) { 0f }
    private val voiceDelayCountdown = IntArray(4) { 0 }

    private var bassPhase = 0f
    private var lfoPhase = 0f
    private var pwmLfoPhase = 0f

    // 12dB Chamberlin SVF Filter
    private var svfLowL = 0f
    private var svfBandL = 0f
    private var svfLowR = 0f
    private var svfBandR = 0f

    // Stereo Schroeder Reverb
    private val comb1 = FloatArray(1499)
    private val comb2 = FloatArray(1693)
    private val comb3 = FloatArray(1877)
    private val comb4 = FloatArray(2053)
    private var cPos1 = 0; private var cPos2 = 0; private var cPos3 = 0; private var cPos4 = 0
    private val allpass1 = FloatArray(347)
    private val allpass2 = FloatArray(223)
    private var aPos1 = 0; private var aPos2 = 0

    // Stereo Chorus Delay Line (echter modulierter Juno-Chorus statt starrem Oszillator!)
    private val chorusBufL = FloatArray(2048)
    private val chorusBufR = FloatArray(2048)
    private var chorusWritePos = 0
    private var chorusLfo = 0f

    // Stereo Ping-Pong Tape Delay
    private val delayBufL = FloatArray(44100)
    private val delayBufR = FloatArray(44100)
    private var delayWritePos = 0

    private var crushCounter = 0
    private var crushedL = 0f
    private var crushedR = 0f

    // Atomare Methode für Akkord-Trigger: Setzt Frequenzen, Gate und Attack gleichzeitig (Null ausgefallene Tasten!)
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
            0 -> { // SUSTAIN (Synth / Organ)
                attackMs = 8f; decayMs = 400f; sustainLevel = 0.85f; releaseMs = 280f
            }
            1 -> { // SWELL (Lush Pad / Strings)
                attackMs = 460f; decayMs = 900f; sustainLevel = 0.88f; releaseMs = 920f
            }
            2 -> { // PLUCK (Percussive Decay auf 0.0f)
                attackMs = 1.5f; decayMs = 480f; sustainLevel = 0.0f; releaseMs = 260f
                filterEnvAmount = filterEnvAmount.coerceAtLeast(0.55f)
                hammerClick = hammerClick.coerceAtLeast(0.35f)
            }
            3 -> { // KEYS (E-Piano / Piano Decay mit warmem Nachklang)
                attackMs = 2.5f; decayMs = 1100f; sustainLevel = 0.16f; releaseMs = 380f
                hammerClick = hammerClick.coerceAtLeast(0.30f)
            }
        }
        attackTriggerCount++
    }

    fun loadSynthPreset(preset: SynthPreset) {
        adsrMode = preset.adsrMode
        attackMs = preset.attackMs
        decayMs = preset.decayMs
        sustainLevel = preset.sustainLevel
        releaseMs = preset.releaseMs
        hammerClick = preset.hammerClick
        baseCutoff = preset.baseCutoff
        filterWheel = preset.baseCutoff
        filterResonance = preset.filterResonance
        filterEnvAmount = preset.filterEnvAmount
        vibratoDepth = preset.vibratoDepth
        pwmDepth = preset.pwmDepth
        glideSpeed = preset.glideSpeed
        reverbSend = preset.reverbSend
        fxChorus = preset.chorusOn
        fxDelay = preset.delayOn
        for (v in 0..3) {
            val vp = preset.voices[v]
            voices[v].apply {
                engine = vp.engine
                fmRatioIndex = vp.fmRatioIndex
                fmDepth = vp.fmDepth
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
            VoicePresetData(vc.engine, vc.fmRatioIndex, vc.fmDepth, vc.detuneCents, vc.octShift, vc.pan, vc.level)
        }
        return SynthPreset(
            id = id, name = name, category = "USER PATCH",
            adsrMode = adsrMode, attackMs = attackMs, decayMs = decayMs,
            sustainLevel = sustainLevel, releaseMs = releaseMs,
            hammerClick = hammerClick, baseCutoff = filterWheel,
            filterResonance = filterResonance, filterEnvAmount = filterEnvAmount,
            vibratoDepth = vibratoDepth, pwmDepth = pwmDepth,
            glideSpeed = glideSpeed, reverbSend = reverbSend,
            chorusOn = fxChorus, delayOn = fxDelay,
            voices = vData, isUser = true
        )
    }

    fun triggerChordAttack() { attackTriggerCount++ }
    fun resetClockSync() { syncResetRequested = true }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        audioThread = thread(start = true, isDaemon = true, name = "HiChordAudioThread") {
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
            val superSawSpread = floatArrayOf(0.989f, 0.996f, 1.0f, 1.0045f, 1.0115f)

            while (isRunning) {
                val currentBpm = bpm.coerceIn(60, 200)
                val quarterSamples = ((sampleRate * 60) / currentBpm).coerceAtLeast(2000)
                val sixteenthSamples = (quarterSamples / 4).coerceAtLeast(500)
                val delaySamples = (sixteenthSamples * 3).coerceIn(1000, delayBufL.size - 1)
                val currentGlide = glideSpeed.coerceIn(0.0005f, 0.09f)

                val attSamples = (attackMs * 0.001f * sampleRate).coerceAtLeast(12f)
                val attackStep = 1f / attSamples
                val decSamples = (decayMs * 0.001f * sampleRate).coerceAtLeast(250f)
                val decayMul = 0.001f.pow(1f / decSamples)
                val relSamples = (releaseMs * 0.001f * sampleRate).coerceAtLeast(200f)
                val releaseMul = 0.001f.pow(1f / relSamples)
                // Obertöne klingen schneller ab als der Grundton (wie bei echten Instrumenten!)
                val harmDecayMul = 0.001f.pow(1f / (decSamples * 0.42f))
                val targetSustain = if (adsrMode == 2) 0.0f else sustainLevel.coerceIn(0f, 1f)

                if (syncResetRequested) {
                    syncResetRequested = false
                    sampleCounter = 0
                    beatSampleCounter = 0
                    arpStepCounter = 0
                }

                for (i in 0 until framesPerBlock) {
                    sampleCounter++
                    beatSampleCounter = (beatSampleCounter + 1) % quarterSamples

                    lfoPhase = (lfoPhase + 5.1f / sampleRate) % 1f
                    pwmLfoPhase = (pwmLfoPhase + 0.85f / sampleRate) % 1f
                    chorusLfo = (chorusLfo + 0.65f / sampleRate) % 1f

                    val vibratoFactor = 1f + sin(lfoPhase * 2f * PI.toFloat()) * vibratoDepth
                    val pwmWidth = (0.5f + sin(pwmLfoPhase * 2f * PI.toFloat()) * pwmDepth * 0.38f).coerceIn(0.12f, 0.88f)

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
                            voiceStage[0] = 1
                            voiceHarmEnv[0] = 1.0f
                            voiceClickEnv[0] = hammerClick
                        }
                    }

                    // Prüfe, ob ein neuer Akkord angeschlagen wurde
                    val currentTrig = attackTriggerCount
                    if (currentTrig != lastHandledAttackCount) {
                        lastHandledAttackCount = currentTrig
                        // Mikro-Humanizing (2ms Versatz pro Stimme) oder echter Gitarren-Strum (30ms pro Saite)
                        val delayPerVoiceSamples = if (strumMode) (sampleRate * 0.030f).toInt() else (sampleRate * 0.0018f).toInt()
                        for (v in 0..3) {
                            voiceDelayCountdown[v] = v * delayPerVoiceSamples
                            if (voiceDelayCountdown[v] == 0) {
                                voiceStage[v] = 1
                                voiceHarmEnv[v] = 1.0f
                                voiceClickEnv[v] = hammerClick
                                // Leicht zufällige Startphase verhindert starren Kirchenorgel-Phasen-Lock!
                                oscPhases[v][0] = (oscPhases[v][0] + Random.nextFloat() * 0.15f) % 1f
                            }
                        }
                    }

                    var mixL = 0f
                    var mixR = 0f
                    var maxHarmEnv = 0f
                    var avgAmpEnv = 0f

                    if (arpMode > 0) {
                        // ARPEGGIATOR-MODUS
                        if (!gate && voiceStage[0] != 0 && voiceStage[0] != 4) voiceStage[0] = 4
                        when (voiceStage[0]) {
                            1 -> {
                                voiceAmpEnv[0] += attackStep
                                if (voiceAmpEnv[0] >= 1.0f) { voiceAmpEnv[0] = 1.0f; voiceStage[0] = 2 }
                            }
                            2 -> {
                                voiceAmpEnv[0] = targetSustain + (voiceAmpEnv[0] - targetSustain) * decayMul
                                if (abs(voiceAmpEnv[0] - targetSustain) < 0.0008f) {
                                    voiceAmpEnv[0] = targetSustain
                                    voiceStage[0] = if (targetSustain <= 0.001f) 0 else 3
                                }
                            }
                            3 -> {
                                voiceAmpEnv[0] = targetSustain
                                if (!gate) voiceStage[0] = 4
                            }
                            4 -> {
                                voiceAmpEnv[0] *= releaseMul
                                if (voiceAmpEnv[0] < 0.0004f) { voiceAmpEnv[0] = 0f; voiceStage[0] = 0 }
                            }
                        }
                        voiceHarmEnv[0] *= harmDecayMul
                        voiceClickEnv[0] *= 0.986f

                        val aEnv = voiceAmpEnv[0]
                        val hEnv = voiceHarmEnv[0]
                        maxHarmEnv = hEnv
                        avgAmpEnv = aEnv

                        if (aEnv > 0.0004f) {
                            val vc = voices[arpVoice]
                            val octMult = 2f.pow(vc.octShift.toFloat())
                            val rawTarget = targetFreqs[arpVoice] * octMult * 2f.pow(vc.detuneCents / 1200f) * vibratoFactor
                            if (rawTarget > 20f) {
                                if (arpSmoothFreq <= 20f) arpSmoothFreq = rawTarget
                                arpSmoothFreq += (rawTarget - arpSmoothFreq) * (currentGlide * 1.8f)
                                val sig = renderVoiceSample(0, vc, arpSmoothFreq, aEnv, hEnv, voiceClickEnv[0], pwmWidth, superSawSpread)
                                val panNorm = ((vc.pan + 1f) * 0.5f).coerceIn(0f, 1f)
                                mixL += sig * sqrt(1f - panNorm) * 1.35f
                                mixR += sig * sqrt(panNorm) * 1.35f
                            }
                        }
                    } else {
                        // 4-STIMMIGER POLYPHONER CHORD-MODUS (Jede Stimme mit eigener ADSR & eigenem Charakter!)
                        for (v in 0 until 4) {
                            if (voiceDelayCountdown[v] > 0) {
                                voiceDelayCountdown[v]--
                                if (voiceDelayCountdown[v] == 0 && gate) {
                                    voiceStage[v] = 1
                                    voiceHarmEnv[v] = 1.0f
                                    voiceClickEnv[v] = hammerClick
                                }
                            }

                            if (!gate && voiceStage[v] != 0 && voiceStage[v] != 4) {
                                voiceStage[v] = 4
                            }

                            when (voiceStage[v]) {
                                1 -> {
                                    voiceAmpEnv[v] += attackStep
                                    if (voiceAmpEnv[v] >= 1.0f) { voiceAmpEnv[v] = 1.0f; voiceStage[v] = 2 }
                                }
                                2 -> {
                                    voiceAmpEnv[v] = targetSustain + (voiceAmpEnv[v] - targetSustain) * decayMul
                                    if (abs(voiceAmpEnv[v] - targetSustain) < 0.0008f) {
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
                                    if (voiceAmpEnv[v] < 0.0004f) { voiceAmpEnv[v] = 0f; voiceStage[v] = 0 }
                                }
                            }

                            voiceHarmEnv[v] *= harmDecayMul
                            voiceClickEnv[v] *= 0.986f

                            val aEnv = voiceAmpEnv[v]
                            val hEnv = voiceHarmEnv[v]
                            if (hEnv > maxHarmEnv) maxHarmEnv = hEnv
                            avgAmpEnv += aEnv * 0.25f

                            if (aEnv <= 0.0004f) continue

                            val vc = voices[v]
                            val octMult = 2f.pow(vc.octShift.toFloat())
                            val tFreq = targetFreqs[v] * octMult * 2f.pow(vc.detuneCents / 1200f) * vibratoFactor
                            if (tFreq <= 20f) continue

                            if (currentFreqs[v] <= 20f) currentFreqs[v] = tFreq
                            currentFreqs[v] += (tFreq - currentFreqs[v]) * currentGlide
                            val freq = currentFreqs[v]

                            val sig = renderVoiceSample(v, vc, freq, aEnv, hEnv, voiceClickEnv[v], pwmWidth, superSawSpread)
                            val panNorm = ((vc.pan + 1f) * 0.5f).coerceIn(0f, 1f)
                            mixL += sig * sqrt(1f - panNorm)
                            mixR += sig * sqrt(panNorm)
                        }
                    }

                    // Original HiChord BASS-Oszillator (Warm Filtered Sub-Bass)
                    if (bassMode > 0 && targetBassFreq > 20f && avgAmpEnv > 0.001f) {
                        if (currentBassFreq <= 20f) currentBassFreq = targetBassFreq
                        currentBassFreq += (targetBassFreq - currentBassFreq) * (currentGlide * 1.5f)
                        val bDt = (currentBassFreq / sampleRate).coerceIn(0.0001f, 0.25f)
                        bassPhase = (bassPhase + bDt) % 1f
                        val bSine = sin(bassPhase * 2f * PI.toFloat()) * 0.72f
                        val bSaw = ((2f * bassPhase - 1f) - polyBlep(bassPhase, bDt)) * (0.15f + 0.25f * maxHarmEnv)
                        val bassSig = (bSine + bSaw) * 0.20f * avgAmpEnv
                        mixL += bassSig
                        mixR += bassSig
                    }

                    if (fxStutter) {
                        val stutterPeriod = (sixteenthSamples / 2).coerceAtLeast(1)
                        if ((sampleCounter % stutterPeriod) > (stutterPeriod / 2)) {
                            mixL = 0f; mixR = 0f
                        }
                    }

                    // 12dB Chamberlin SVF Tiefpassfilter mit einstellbarer Resonanz & Pluck-Transient
                    val wheelNorm = filterWheel.coerceIn(0.06f, 1.0f)
                    val envBoost = maxHarmEnv * filterEnvAmount * 0.52f
                    val svfF = (0.045f + (wheelNorm * wheelNorm * 0.56f) + envBoost).coerceIn(0.035f, 0.76f)
                    val svfQ = (0.85f - filterResonance.coerceIn(0f, 0.65f)).coerceIn(0.28f, 0.85f)

                    svfLowL += svfF * svfBandL
                    val highL = mixL - svfLowL - svfQ * svfBandL
                    svfBandL += svfF * highL

                    svfLowR += svfF * svfBandR
                    val highR = mixR - svfLowR - svfQ * svfBandR
                    svfBandR += svfF * highR

                    var finalL = svfLowL
                    var finalR = svfLowR

                    // Echter Stereo Juno-Chorus (Modulierte Delay-Line für seidige Breite ohne Orgel-Starrheit!)
                    chorusBufL[chorusWritePos] = finalL
                    chorusBufR[chorusWritePos] = finalR
                    if (fxChorus) {
                        val modSamplesL = 320f + sin(chorusLfo * 2f * PI.toFloat()) * 140f
                        val modSamplesR = 340f + cos(chorusLfo * 2f * PI.toFloat()) * 140f
                        val rIdxL = (chorusWritePos - modSamplesL.toInt() + chorusBufL.size) % chorusBufL.size
                        val rIdxR = (chorusWritePos - modSamplesR.toInt() + chorusBufR.size) % chorusBufR.size
                        finalL = finalL * 0.72f + chorusBufL[rIdxL] * 0.45f
                        finalR = finalR * 0.72f + chorusBufR[rIdxR] * 0.45f
                    }
                    chorusWritePos = (chorusWritePos + 1) % chorusBufL.size

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

                    // Stereo Ping-Pong Tape Delay
                    val readPos = (delayWritePos - delaySamples + delayBufL.size) % delayBufL.size
                    if (fxDelay) {
                        val dL = delayBufL[readPos]
                        val dR = delayBufR[readPos]
                        finalL += dR * 0.40f
                        finalR += dL * 0.40f
                        delayBufL[delayWritePos] = (finalL * 0.58f).coerceIn(-1f, 1f)
                        delayBufR[delayWritePos] = (finalR * 0.58f).coerceIn(-1f, 1f)
                    } else {
                        delayBufL[delayWritePos] = 0f
                        delayBufR[delayWritePos] = 0f
                    }
                    delayWritePos = (delayWritePos + 1) % delayBufL.size

                    // Lush Stereo Schroeder Reverb
                    if (fxReverb && reverbSend > 0.01f) {
                        val revIn = (finalL + finalR) * 0.5f * reverbSend
                        val c1 = comb1[cPos1]; comb1[cPos1] = (revIn + c1 * 0.78f).coerceIn(-1f, 1f); cPos1 = (cPos1 + 1) % comb1.size
                        val c2 = comb2[cPos2]; comb2[cPos2] = (revIn + c2 * 0.80f).coerceIn(-1f, 1f); cPos2 = (cPos2 + 1) % comb2.size
                        val c3 = comb3[cPos3]; comb3[cPos3] = (revIn + c3 * 0.82f).coerceIn(-1f, 1f); cPos3 = (cPos3 + 1) % comb3.size
                        val c4 = comb4[cPos4]; comb4[cPos4] = (revIn + c4 * 0.84f).coerceIn(-1f, 1f); cPos4 = (cPos4 + 1) % comb4.size

                        val combSumL = (c1 + c3) * 0.34f
                        val combSumR = (c2 + c4) * 0.34f

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

                    val outL = tanh(finalL * 1.12f)
                    val outR = tanh(finalR * 1.12f)
                    if (i < scopeBuffer.size) scopeBuffer[i] = (outL + outR) * 0.5f

                    stereoBuffer[i * 2] = (outL * 30500f).toInt().toShort()
                    stereoBuffer[i * 2 + 1] = (outR * 30500f).toInt().toShort()
                }
                track.write(stereoBuffer, 0, stereoBuffer.size)
            }
            try { track.stop(); track.release() } catch (_: Exception) {}
        }
    }

    // 6 ECHTE SYNTHESE-MODELLE PRO STIMME (Beseitigt den Kirchenorgel-Klang komplett)
    private fun renderVoiceSample(
        vIdx: Int,
        vc: VoiceConfig,
        freq: Float,
        ampEnv: Float,
        harmEnv: Float,
        clickEnv: Float,
        pwmWidth: Float,
        superSawSpread: FloatArray
    ): Float {
        val dt = (freq / sampleRate).coerceIn(0.0001f, 0.45f)
        val ratio = fmRatios[vc.fmRatioIndex.coerceIn(0, fmRatios.lastIndex)]
        modPhases[vIdx] = (modPhases[vIdx] + dt * ratio) % 1f
        val pArr = oscPhases[vIdx]
        pArr[0] = (pArr[0] + dt) % 1f
        val p0 = pArr[0]

        // Dynamischer FM-Index: Beim Anschlag obertonreich, beim Ausklingen warm und rund!
        val dynFm = vc.fmDepth * (0.22f + 0.78f * harmEnv)
        val fmMod = sin(modPhases[vIdx] * 2f * PI.toFloat()) * dynFm

        val rawOsc = when (vc.engine) {
            0 -> { // ANALOG SAW (Bandlimited PolyBLEP Saw + Sub-Warmth)
                val p = (p0 + fmMod * 0.35f + 1f) % 1f
                val saw = (2f * p - 1f) - polyBlep(p, dt)
                val sub = sin(p0 * 2f * PI.toFloat()) * 0.25f
                (saw * 0.82f + sub)
            }
            1 -> { // PWM PULSE (Modulated Pulse Width -> klingt nach Juno/Solina statt Orgel!)
                val p = (p0 + fmMod * 0.25f + 1f) % 1f
                var sq = if (p < pwmWidth) 1f else -1f
                sq += polyBlep(p, dt)
                sq -= polyBlep((p + (1f - pwmWidth)) % 1f, dt)
                sq * 0.82f
            }
            2 -> { // PURE TRI/SINE (Warm Triangle-Sine Hybrid + FM Tine)
                val tri = 1f - 4f * abs(round(p0 - 0.25f) - (p0 - 0.25f))
                val sine = sin((p0 * 2f * PI.toFloat()) + fmMod * 3.8f)
                (tri * 0.45f + sine * 0.65f)
            }
            3 -> { // 5-SAW CLUSTER (Echter 5-Oszillator Detuned SuperSaw pro Akkordton!)
                var sum = (2f * p0 - 1f) - polyBlep(p0, dt)
                for (s in 1..4) {
                    val sDt = (dt * superSawSpread[s]).coerceIn(0.0001f, 0.45f)
                    pArr[s] = (pArr[s] + sDt) % 1f
                    val ps = pArr[s]
                    sum += (2f * ps - 1f) - polyBlep(ps, sDt)
                }
                sum * 0.28f
            }
            4 -> { // DX 2-OP FM (Authentisches Rhodes / Vibraphone / E-Piano Modell)
                val modFeedback = sin(modPhases[vIdx] * 2f * PI.toFloat()) * (0.3f * harmEnv)
                val modulator = sin((modPhases[vIdx] * 2f * PI.toFloat()) + modFeedback) * dynFm * 6.2f
                val carrier = sin((p0 * 2f * PI.toFloat()) + modulator)
                val tineBell = sin(p0 * 6f * PI.toFloat()) * (0.22f * harmEnv * harmEnv)
                carrier * 0.85f + tineBell
            }
            else -> { // PLUCKED STRING / PIANO HAMMER MODEL (Damped Harmonics + String Body)
                pArr[1] = (pArr[1] + dt * 1.0018f) % 1f
                val s1 = (2f * p0 - 1f) - polyBlep(p0, dt)
                val s2 = (2f * pArr[1] - 1f) - polyBlep(pArr[1], dt)
                val fundamental = sin(p0 * 2f * PI.toFloat())
                val octaveHarm = sin(p0 * 4f * PI.toFloat()) * 0.45f * harmEnv
                val fifthHarm = sin(p0 * 6f * PI.toFloat()) * 0.30f * harmEnv * harmEnv
                // Beim Anschlag klingen die Saiten-Obertöne (s1+s2) hell auf und blenden sanft in den warmen Korpuston über!
                val stringBite = (s1 + s2) * 0.48f * (0.12f + 0.88f * harmEnv)
                (fundamental * 0.65f + octaveHarm + fifthHarm + stringBite)
            }
        }

        // Physikalischer Hammer-/Plektrum-Transient im ersten Moment des Anschlags (für Piano, Gitarre, Harp & Mallet)
        val hammerNoise = if (clickEnv > 0.01f) {
            val clickTone = sin(p0 * 14f * PI.toFloat()) * 0.6f + (Random.nextFloat() * 2f - 1f) * 0.4f
            clickTone * clickEnv * 0.45f
        } else 0f

        return (rawOsc + hammerNoise) * 0.22f * vc.level * ampEnv
    }

    @Synchronized
    fun stop() {
        isRunning = false
        audioThread = null
    }
}

// --- MUSIKTHEORIE: DUR (MAJOR) & NATÜRLICHES MOLL (MINOR) + 8-WEGE JOYSTICK ---
val rootNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

// Dur (Major): I, ii, iii, IV, V, vi, vii°
val majorScaleOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
val majorDefaultQuality = intArrayOf(0, 1, 1, 0, 0, 1, 2) // 0: Maj, 1: Min, 2: Dim
val majorRomanDegrees = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")

// Natürliches Moll (Natural Minor): i, ii°, III, iv, v, VI, VII
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
    voiceLeadingOn: Boolean,
    bassMode: Int
): ChordVoicingResult {
    val offsets = if (isMinorScale) minorScaleOffsets else majorScaleOffsets
    val qualities = if (isMinorScale) minorDefaultQuality else majorDefaultQuality

    val rootSemitone = keyIndex + offsets[degree]
    val rootMidiBase = 60 + rootSemitone + (octaveShift * 12)
    val noteName = rootNames[rootSemitone % 12]

    var qual = qualities[degree] // 0 = Maj, 1 = Min, 2 = Dim
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
        JoyChordZone.UP -> { // FLIP Maj <-> Min
            qual = if (qual == 0) 1 else 0
            third = if (qual == 0) 4 else 3
            fifth = 7
            chordLabelSuffix = if (qual == 1) "m" else "MAJ"
        }
        JoyChordZone.UP_RIGHT -> { // Dom 7th
            third = 4; fifth = 7; fourthNote = 10
            chordLabelSuffix = "7"
        }
        JoyChordZone.RIGHT -> { // Maj7 / m7
            fourthNote = if (qual == 0) 11 else 10
            chordLabelSuffix = if (qual == 0) "maj7" else "m7"
        }
        JoyChordZone.DOWN_RIGHT -> { // Add9 / 9th
            fourthNote = 14
            chordLabelSuffix = if (qual == 1) "m9" else "add9"
        }
        JoyChordZone.DOWN -> { // Sus4
            third = 5; fifth = 7
            chordLabelSuffix = "sus4"
        }
        JoyChordZone.DOWN_LEFT -> { // 6th
            fourthNote = 9
            chordLabelSuffix = if (qual == 1) "m6" else "6"
        }
        JoyChordZone.LEFT -> { // Dim7
            third = 3; fifth = 6; fourthNote = 9
            chordLabelSuffix = "dim7"
        }
        JoyChordZone.UP_LEFT -> { // Aug
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

    if (voiceLeadingOn) {
        val centerTarget = 64 + (octaveShift * 12)
        for (i in 0..3) {
            while (midiNotes[i] - centerTarget > 6) midiNotes[i] -= 12
            while (centerTarget - midiNotes[i] > 6) midiNotes[i] += 12
        }
        midiNotes.sort()
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
fun TeHiChordApp(synth: HiChordEngine) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hichord_presets", Context.MODE_PRIVATE) }
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    var keyIndex by remember { mutableStateOf(0) }
    var isMinorScale by remember { mutableStateOf(false) } // Eigener Major/Minor Umschalter!
    var bpm by remember { mutableStateOf(120) }
    var activeDegree by remember { mutableStateOf(-1) }
    var displayChord by remember { mutableStateOf("HI-CHORD") }

    var voiceLeadingOn by remember { mutableStateOf(true) }
    var bassMode by remember { mutableStateOf(1) }
    var octaveShift by remember { mutableStateOf(0) }
    var holdLatch by remember { mutableStateOf(false) }
    var strumMode by remember { mutableStateOf(false) }
    var filterWheel by remember { mutableStateOf(0.46f) }

    var waveIndex by remember { mutableStateOf(4) }
    var adsrMode by remember { mutableStateOf(3) }
    var arpMode by remember { mutableStateOf(0) }
    var activeSlot by remember { mutableStateOf(0) }
    var uiRefreshCounter by remember { mutableStateOf(0) }

    val userPresets = remember {
        val list = mutableStateListOf<SynthPreset>()
        val rawSet = prefs.getStringSet("user_presets_v5", emptySet()) ?: emptySet()
        rawSet.mapNotNull { SynthPreset.deserialize(it) }
            .sortedBy { it.name }
            .forEach { list.add(it) }
        list
    }

    // 4 Schnellwahl-Slots mit den 4 beliebtesten originalen HiChord-Presets vorbelegt
    val slotPresets = remember {
        mutableStateListOf(
            factoryPresets[0],  // FM E-PIANO
            factoryPresets[2],  // WARM PAD
            factoryPresets[8],  // DELICATE HARP
            factoryPresets[5]   // SUPERSAW
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
        prefs.edit().putStringSet("user_presets_v5", serialized).apply()
    }

    var joyX by remember { mutableStateOf(0f) }
    var joyY by remember { mutableStateOf(0f) }
    val activeJoyZone = getJoystickZone(joyX, joyY)

    // Synchrone Echtzeit-Funktion für Akkord-Ansteuerung (Beseitigt den "Tasten reagieren manchmal nicht"-Bug!)
    fun syncChordState(deg: Int, retriggerAttack: Boolean) {
        activeDegree = deg
        if (deg in 0..6) {
            val res = buildHiChordVoicing(keyIndex, isMinorScale, deg, octaveShift, activeJoyZone, voiceLeadingOn, bassMode)
            displayChord = res.displayName
            synth.playChord(res.voiceFreqs, res.bassFreq, retriggerAttack)
        } else {
            synth.releaseChord()
        }
    }

    // Wenn während eines gehaltenen Akkords der Joystick, die Tonart oder Major/Minor geändert wird -> Voicing sofort live aktualisieren!
    LaunchedEffect(keyIndex, isMinorScale, octaveShift, activeJoyZone, voiceLeadingOn, bassMode) {
        if (activeDegree in 0..6) {
            val res = buildHiChordVoicing(keyIndex, isMinorScale, activeDegree, octaveShift, activeJoyZone, voiceLeadingOn, bassMode)
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
            voiceLeadingOn = voiceLeadingOn,
            onToggleVoiceLeading = { voiceLeadingOn = !voiceLeadingOn },
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

// --- 90° QUERFORMAT: EXPERT 4-VOICE LAB & HICHORD PRESET BROWSER ---
@Composable
fun TeExpertLandscapeScreen(
    synth: HiChordEngine,
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
                            text = if (showBrowser) "HICHORD PRESETS" else "EXPERT // 4-VOICE LAB",
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
                sub = "16 HICHORD",
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
                        text = "SELECT HICHORD INSTRUMENT FOR SLOT 0${activeSlot + 1} (KEYS, PADS, STRINGS, PLUCKS, MALLETS)",
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "FM RATIO", color = Color(0xFF00E5FF), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                for (rIdx in fmRatios.indices) {
                                    val rVal = fmRatios[rIdx]
                                    val rText = if (rVal == 0.5f) ".5" else "${rVal.toInt()}x"
                                    val selected = vc.fmRatioIndex == rIdx
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(if (selected) Color(0xFF00E5FF) else Color(0xFF2D2D2B))
                                            .clickable {
                                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                vc.fmRatioIndex = rIdx
                                                onParamChanged()
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(text = rText, color = if (selected) TePanelDark else Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                        TeBarSlider(label = "HARMONICS / FM", valueText = "${(vc.fmDepth * 100).roundToInt()}%", fraction = vc.fmDepth, barColor = Color(0xFF00E5FF), onValueChange = { vc.fmDepth = it; onParamChanged() })
                        TeBarSlider(label = "DETUNE", valueText = "${if (vc.detuneCents >= 0) "+${vc.detuneCents}" else vc.detuneCents}ct", fraction = (vc.detuneCents + 50f) / 100f, barColor = TeOchre, onValueChange = { vc.detuneCents = ((it * 100f) - 50f).roundToInt(); onParamChanged() })
                        val panInt = (vc.pan * 100).roundToInt()
                        val panLabel = when { panInt < -5 -> "L${-panInt}"; panInt > 5 -> "R$panInt"; else -> "CTR" }
                        TeBarSlider(label = "STEREO PAN", valueText = panLabel, fraction = (vc.pan + 1f) * 0.5f, barColor = TeOrange, onValueChange = { vc.pan = (it * 2f) - 1f; onParamChanged() })
                        TeBarSlider(label = "VOICE LEVEL", valueText = "${(vc.level * 100).roundToInt()}%", fraction = vc.level, barColor = Color(0xFF4CAF50), onValueChange = { vc.level = it; onParamChanged() })
                    }
                }
            }
        }

        // 3. BOTTOM AUDITION STRIP (100% ZUVERLÄSSIGER TRIGGER)
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
            modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A28))
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

// --- HOCHFORMAT: HICHORD COMPANION INTERFACE ---
@Composable
fun TePortraitJamScreen(
    synth: HiChordEngine,
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
    voiceLeadingOn: Boolean,
    onToggleVoiceLeading: () -> Unit,
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
                    text = "HICHORD // POCKET SYNTH (ROTATE 90° FOR LAB)",
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

        // 2. OLED DISPLAY & ORIGINAL HICHORD 8-WEGE CHORD JOYSTICK
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
                                    activeDegree >= 0 -> "$activePresetCategory · ${if (isMinorScale) "MINOR SCALE" else "MAJOR SCALE"}"
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
                            text = if (voiceLeadingOn) "V-LEAD" else "ROOT POS",
                            color = if (voiceLeadingOn) TeOchre else Color(0xFF777772),
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
        SectionDivider("SYSTEM // KEY, 1-BPM TEMPO, TAP-SYNC, WAVE, ADSR & ARP")
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

        // 4. PERFORMANCE FX + REVERB & SIDECHAIN PUMP
        SectionDivider("PERFORMANCE FX // MOMENTARY FX + STEREO CHORUS, REVERB & PUMP")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TeMomentaryFxButton(title = "DELAY", sub = "ECHO", isPressed = fxDelay, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxDelay = true; synth.fxDelay = true }, onRelease = { fxDelay = false; synth.fxDelay = false })
            TeMomentaryFxButton(title = "CRUSH", sub = "8-BIT", isPressed = fxCrush, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxCrush = true; synth.fxCrush = true }, onRelease = { fxCrush = false; synth.fxCrush = false })
            TeMomentaryFxButton(title = "STUTTR", sub = "1/32", isPressed = fxStutter, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxStutter = true; synth.fxStutter = true }, onRelease = { fxStutter = false; synth.fxStutter = false })
            TeTactileButton(title = "CHORUS", sub = "JUNO", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxChorus) TeOrange else TeBlue, shadowColor = if (fxChorus) TeOrangeShadow else TeBlueShadow, textColor = Color.White, ledOn = fxChorus, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxChorus = !fxChorus; synth.fxChorus = fxChorus })
            TeTactileButton(title = "REVERB", sub = "HALL", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxReverb) TeOrange else TeKeyDark, shadowColor = if (fxReverb) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = fxReverb, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxReverb = !fxReverb; synth.fxReverb = fxReverb })
            TeTactileButton(title = "PUMP", sub = "DUCK", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxPump) TeOrange else TeBlue, shadowColor = if (fxPump) TeOrangeShadow else TeBlueShadow, textColor = Color.White, ledOn = fxPump, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxPump = !fxPump; synth.fxPump = fxPump })
        }

        // 5. HICHORD PLAY MODES + DEDIZIERTER MAJOR / MINOR BUTTON!
        SectionDivider("HICHORD MODES // DEDICATED MAJOR/MINOR, HOLD, STRUM, V-LEAD & BASS")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            // Eigener Knopf zum Umschalten zwischen Dur (Major) und Moll (Minor)!
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
            TeTactileButton(title = "HOLD", sub = "DRONE", modifier = Modifier.weight(0.9f), height = 46.dp, bgColor = if (holdLatch) TeBlue else TeKeyDark, shadowColor = if (holdLatch) TeBlueShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = holdLatch, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleHold() })
            TeTactileButton(title = "STRUM", sub = "CHORD", modifier = Modifier.weight(0.95f), height = 46.dp, bgColor = if (strumMode) TeOrange else TeKeyDark, shadowColor = if (strumMode) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = strumMode, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleStrum() })
            TeTactileButton(title = "V-LEAD", sub = "SMOOTH", modifier = Modifier.weight(0.95f), height = 46.dp, bgColor = if (voiceLeadingOn) TeOrange else TeKeyDark, shadowColor = if (voiceLeadingOn) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = voiceLeadingOn, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleVoiceLeading() })
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

        // 6. 7 NASHVILLE DIATONIC CHORD BUTTONS (100% REAKTIONS-SICHERER TOUCH-HANDLER)
        SectionDivider("NASHVILLE CHORD BUTTONS // 100% INSTANT RESPONSE (I - VII)")
        var padGridSize by remember { mutableStateOf(IntSize.Zero) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { padGridSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        // Klemmt die Koordinaten immer sicher in das gültige 7-Tasten-Raster (kein "toter Rand" mehr!)
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
