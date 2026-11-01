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

// --- TEENAGE ENGINEERING & HICHORD FARBPALETTE ---
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
class VoiceConfig(
    @Volatile var engine: Int = 0,       // 0: SAW, 1: PULS, 2: SINE, 3: SSAW, 4: FM
    @Volatile var fmRatioIndex: Int = 1, // 0: 0.5x, 1: 1x, 2: 2x, 3: 3x, 4: 4x, 5: 7x
    @Volatile var fmDepth: Float = 0f,
    @Volatile var detuneCents: Int = 0,
    @Volatile var octShift: Int = 0,     // -1, 0, +1
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

// ADSR Mode: 0 = SUSTAIN (Organ/Synth), 1 = SWELL (Pad/Strings), 2 = PLUCK (Percussive Decay to 0), 3 = KEYS (Piano/Rhodes Decay + Soft Sustain)
data class SynthPreset(
    val id: String,
    val name: String,
    val category: String,
    val adsrMode: Int,
    val attackMs: Float,
    val decayMs: Float,
    val sustainLevel: Float,
    val releaseMs: Float,
    val baseCutoff: Float,
    val filterEnvAmount: Float,
    val vibratoDepth: Float,
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
        return "$id|$name|$category|$adsrMode|$attackMs|$decayMs|$sustainLevel|$releaseMs|$baseCutoff|$filterEnvAmount|$vibratoDepth|$glideSpeed|$reverbSend|$chorusOn|$delayOn|$vStr"
    }

    companion object {
        fun deserialize(raw: String): SynthPreset? {
            return try {
                val p = raw.split("|")
                if (p.size < 16) return null
                val vList = p[15].split(";").map { vRaw ->
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
                    baseCutoff = p[8].toFloat(), filterEnvAmount = p[9].toFloat(),
                    vibratoDepth = p[10].toFloat(), glideSpeed = p[11].toFloat(),
                    reverbSend = p[12].toFloat(), chorusOn = p[13].toBoolean(), delayOn = p[14].toBoolean(),
                    voices = vList, isUser = true
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

val fmRatios = floatArrayOf(0.5f, 1.0f, 2.0f, 3.0f, 4.0f, 7.0f)
val engineNames = listOf("SAW", "PULS", "SINE", "SSAW", "FM")
val adsrNames = listOf("SUST", "SWEL", "PLCK", "KEYS")

// --- 16 REVERSE-ENGINEERED HICHORD CLASSICS & STUDIO GENRE PRESETS ---
val factoryPresets = listOf(
    // === 1–8: DIE 8 ORIGINALEN HICHORD HARDWARE-INSTRUMENTE (aus dem offiziellen HiChord Manual) ===
    SynthPreset(
        id = "hc_fm_epiano", name = "FM E-PIANO", category = "HICHORD OG",
        adsrMode = 3, attackMs = 4f, decayMs = 1100f, sustainLevel = 0.22f, releaseMs = 450f,
        baseCutoff = 0.42f, filterEnvAmount = 0.45f, vibratoDepth = 0.0022f, glideSpeed = 0.025f,
        reverbSend = 0.35f, chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 4, fmRatioIndex = 1, fmDepth = 0.28f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 1, fmDepth = 0.32f, detuneCents = -5, octShift = 0, pan = -0.55f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 2, fmDepth = 0.24f, detuneCents = 5, octShift = 0, pan = 0.55f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.18f, detuneCents = 9, octShift = 0, pan = 0.20f, level = 0.70f)
        )
    ),
    SynthPreset(
        id = "hc_strings", name = "STRINGS", category = "HICHORD OG",
        adsrMode = 1, attackMs = 550f, decayMs = 800f, sustainLevel = 0.90f, releaseMs = 950f,
        baseCutoff = 0.38f, filterEnvAmount = 0.15f, vibratoDepth = 0.0028f, glideSpeed = 0.004f,
        reverbSend = 0.55f, chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -7, octShift = -1, pan = 0f, level = 0.90f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -14, octShift = 0, pan = -0.80f, level = 0.88f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 14, octShift = 0, pan = 0.80f, level = 0.88f),
            VoicePresetData(engine = 0, fmRatioIndex = 2, fmDepth = 0.06f, detuneCents = 19, octShift = 1, pan = 0f, level = 0.65f)
        )
    ),
    SynthPreset(
        id = "hc_piano", name = "ACOUSTIC PIANO", category = "HICHORD OG",
        adsrMode = 2, attackMs = 2f, decayMs = 780f, sustainLevel = 0.0f, releaseMs = 320f,
        baseCutoff = 0.48f, filterEnvAmount = 0.52f, vibratoDepth = 0.0004f, glideSpeed = 0.050f,
        reverbSend = 0.32f, chorusOn = false, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 4, fmRatioIndex = 1, fmDepth = 0.38f, detuneCents = 0, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 1, fmDepth = 0.34f, detuneCents = -3, octShift = 0, pan = -0.40f, level = 0.90f),
            VoicePresetData(engine = 0, fmRatioIndex = 2, fmDepth = 0.18f, detuneCents = 3, octShift = 0, pan = 0.40f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.22f, detuneCents = 5, octShift = 0, pan = 0f, level = 0.78f)
        )
    ),
    SynthPreset(
        id = "hc_saw", name = "SAW SYNTH", category = "HICHORD OG",
        adsrMode = 0, attackMs = 12f, decayMs = 400f, sustainLevel = 0.85f, releaseMs = 350f,
        baseCutoff = 0.58f, filterEnvAmount = 0.38f, vibratoDepth = 0.0012f, glideSpeed = 0.006f,
        reverbSend = 0.30f, chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.92f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -12, octShift = 0, pan = -0.65f, level = 0.88f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 12, octShift = 0, pan = 0.65f, level = 0.88f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 18, octShift = 0, pan = 0f, level = 0.80f)
        )
    ),
    SynthPreset(
        id = "hc_vibraph", name = "VIBRAPHONE", category = "HICHORD OG",
        adsrMode = 2, attackMs = 2f, decayMs = 920f, sustainLevel = 0.0f, releaseMs = 480f,
        baseCutoff = 0.52f, filterEnvAmount = 0.42f, vibratoDepth = 0.0045f, glideSpeed = 0.040f,
        reverbSend = 0.48f, chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.15f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.42f, detuneCents = -4, octShift = 0, pan = -0.50f, level = 0.82f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.38f, detuneCents = 4, octShift = 0, pan = 0.50f, level = 0.82f),
            VoicePresetData(engine = 2, fmRatioIndex = 5, fmDepth = 0.25f, detuneCents = 8, octShift = 1, pan = 0f, level = 0.68f)
        )
    ),
    SynthPreset(
        id = "hc_harp", name = "DELICATE HARP", category = "HICHORD OG",
        adsrMode = 2, attackMs = 2f, decayMs = 540f, sustainLevel = 0.0f, releaseMs = 380f,
        baseCutoff = 0.36f, filterEnvAmount = 0.62f, vibratoDepth = 0.0008f, glideSpeed = 0.030f,
        reverbSend = 0.52f, chorusOn = false, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.20f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 2, fmRatioIndex = 2, fmDepth = 0.25f, detuneCents = -5, octShift = 0, pan = -0.60f, level = 0.85f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.18f, detuneCents = 5, octShift = 0, pan = 0.60f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.22f, detuneCents = 9, octShift = 1, pan = 0f, level = 0.72f)
        )
    ),
    SynthPreset(
        id = "hc_organ", name = "CLASSIC ORGAN", category = "HICHORD OG",
        adsrMode = 0, attackMs = 5f, decayMs = 200f, sustainLevel = 1.0f, releaseMs = 120f,
        baseCutoff = 0.65f, filterEnvAmount = 0.05f, vibratoDepth = 0.0032f, glideSpeed = 0.045f,
        reverbSend = 0.35f, chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.18f, detuneCents = 0, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 2, fmRatioIndex = 2, fmDepth = 0.22f, detuneCents = -3, octShift = 0, pan = -0.45f, level = 0.88f),
            VoicePresetData(engine = 1, fmRatioIndex = 3, fmDepth = 0.15f, detuneCents = 3, octShift = 0, pan = 0.45f, level = 0.80f),
            VoicePresetData(engine = 2, fmRatioIndex = 4, fmDepth = 0.25f, detuneCents = 6, octShift = 1, pan = 0f, level = 0.72f)
        )
    ),
    SynthPreset(
        id = "hc_eguitar", name = "WARM E-GUITAR", category = "HICHORD OG",
        adsrMode = 2, attackMs = 3f, decayMs = 680f, sustainLevel = 0.0f, releaseMs = 300f,
        baseCutoff = 0.30f, filterEnvAmount = 0.65f, vibratoDepth = 0.0020f, glideSpeed = 0.012f,
        reverbSend = 0.40f, chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0.32f, detuneCents = 0, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.28f, detuneCents = -7, octShift = 0, pan = -0.55f, level = 0.88f),
            VoicePresetData(engine = 1, fmRatioIndex = 2, fmDepth = 0.22f, detuneCents = 7, octShift = 0, pan = 0.55f, level = 0.88f),
            VoicePresetData(engine = 0, fmRatioIndex = 3, fmDepth = 0.18f, detuneCents = 11, octShift = 0, pan = 0f, level = 0.78f)
        )
    ),

    // === 9–16: PRO STUDIO TRAP, RAGE & CLOUDRAP PRESETS (Ken Carson, Yung Lean, Juice WRLD) ===
    SynthPreset(
        id = "pr_opium_rage", name = "OPIUM RAGE", category = "KEN CARSON",
        adsrMode = 0, attackMs = 4f, decayMs = 350f, sustainLevel = 0.95f, releaseMs = 240f,
        baseCutoff = 0.88f, filterEnvAmount = 0.25f, vibratoDepth = 0.0008f, glideSpeed = 0.008f,
        reverbSend = 0.28f, chorusOn = true, delayOn = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.25f, detuneCents = 0, octShift = -1, pan = 0f, level = 1.0f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0.14f, detuneCents = -32, octShift = 0, pan = -0.95f, level = 0.94f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0.14f, detuneCents = 32, octShift = 0, pan = 0.95f, level = 0.94f),
            VoicePresetData(engine = 3, fmRatioIndex = 2, fmDepth = 0.28f, detuneCents = 40, octShift = 1, pan = 0f, level = 0.85f)
        )
    ),
    SynthPreset(
        id = "pr_xtended_pluck", name = "XTENDED PLUCK", category = "KEN CARSON",
        adsrMode = 2, attackMs = 1.5f, decayMs = 360f, sustainLevel = 0.0f, releaseMs = 200f,
        baseCutoff = 0.45f, filterEnvAmount = 0.72f, vibratoDepth = 0f, glideSpeed = 0.015f,
        reverbSend = 0.34f, chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 0, fmDepth = 0.35f, detuneCents = 0, octShift = -1, pan = 0f, level = 1.0f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0.22f, detuneCents = -25, octShift = 0, pan = -0.85f, level = 0.90f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0.22f, detuneCents = 25, octShift = 0, pan = 0.85f, level = 0.90f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.48f, detuneCents = 34, octShift = 1, pan = 0f, level = 0.80f)
        )
    ),
    SynthPreset(
        id = "pr_sadboys_2002", name = "SADBOYS 2002", category = "YUNG LEAN",
        adsrMode = 1, attackMs = 380f, decayMs = 900f, sustainLevel = 0.85f, releaseMs = 1100f,
        baseCutoff = 0.46f, filterEnvAmount = 0.30f, vibratoDepth = 0.0038f, glideSpeed = 0.0025f,
        reverbSend = 0.65f, chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.08f, detuneCents = 0, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.44f, detuneCents = -15, octShift = 0, pan = -0.80f, level = 0.80f),
            VoicePresetData(engine = 3, fmRatioIndex = 2, fmDepth = 0.15f, detuneCents = 15, octShift = 0, pan = 0.80f, level = 0.80f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.52f, detuneCents = 24, octShift = 1, pan = 0.25f, level = 0.74f)
        )
    ),
    SynthPreset(
        id = "pr_ginseng_bell", name = "GINSENG PLUCK", category = "YUNG LEAN",
        adsrMode = 2, attackMs = 2f, decayMs = 490f, sustainLevel = 0.0f, releaseMs = 420f,
        baseCutoff = 0.50f, filterEnvAmount = 0.55f, vibratoDepth = 0.0025f, glideSpeed = 0.010f,
        reverbSend = 0.60f, chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 0, fmDepth = 0.20f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.55f, detuneCents = -10, octShift = 0, pan = -0.70f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.48f, detuneCents = 10, octShift = 0, pan = 0.70f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.62f, detuneCents = 18, octShift = 1, pan = 0f, level = 0.72f)
        )
    ),
    SynthPreset(
        id = "pr_lucid_keys", name = "LUCID DREAMS", category = "JUICE WRLD",
        adsrMode = 2, attackMs = 3f, decayMs = 620f, sustainLevel = 0.0f, releaseMs = 380f,
        baseCutoff = 0.34f, filterEnvAmount = 0.58f, vibratoDepth = 0.0042f, glideSpeed = 0.007f,
        reverbSend = 0.48f, chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.24f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 2, fmDepth = 0.38f, detuneCents = -9, octShift = 0, pan = -0.60f, level = 0.86f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.18f, detuneCents = 9, octShift = 0, pan = 0.60f, level = 0.84f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.32f, detuneCents = -16, octShift = 1, pan = 0.20f, level = 0.70f)
        )
    ),
    SynthPreset(
        id = "pr_mira_pluck", name = "NICK MIRA PLUCK", category = "JUICE WRLD",
        adsrMode = 2, attackMs = 1.5f, decayMs = 380f, sustainLevel = 0.0f, releaseMs = 260f,
        baseCutoff = 0.28f, filterEnvAmount = 0.74f, vibratoDepth = 0.0018f, glideSpeed = 0.012f,
        reverbSend = 0.42f, chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0.22f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 0, fmRatioIndex = 2, fmDepth = 0.30f, detuneCents = -11, octShift = 0, pan = -0.65f, level = 0.88f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 11, octShift = 0, pan = 0.65f, level = 0.88f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.40f, detuneCents = 16, octShift = 1, pan = 0f, level = 0.74f)
        )
    ),
    SynthPreset(
        id = "pr_metro_bell", name = "METRO DARK BELL", category = "DARK TRAP",
        adsrMode = 2, attackMs = 1.5f, decayMs = 520f, sustainLevel = 0.0f, releaseMs = 350f,
        baseCutoff = 0.44f, filterEnvAmount = 0.60f, vibratoDepth = 0.0015f, glideSpeed = 0.018f,
        reverbSend = 0.50f, chorusOn = false, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 0, fmDepth = 0.32f, detuneCents = 0, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.68f, detuneCents = -6, octShift = 0, pan = -0.55f, level = 0.86f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.64f, detuneCents = 6, octShift = 0, pan = 0.55f, level = 0.86f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.75f, detuneCents = 14, octShift = 1, pan = 0f, level = 0.68f)
        )
    ),
    SynthPreset(
        id = "pr_mike_dean", name = "MIKE DEAN CS-80", category = "ASTROWORLD",
        adsrMode = 0, attackMs = 35f, decayMs = 600f, sustainLevel = 0.92f, releaseMs = 650f,
        baseCutoff = 0.36f, filterEnvAmount = 0.55f, vibratoDepth = 0.0030f, glideSpeed = 0.0014f,
        reverbSend = 0.58f, chorusOn = true, delayOn = true,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 0, fmDepth = 0.18f, detuneCents = -4, octShift = -1, pan = 0f, level = 1.0f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.06f, detuneCents = -20, octShift = 0, pan = -0.90f, level = 0.88f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0.06f, detuneCents = 20, octShift = 0, pan = 0.90f, level = 0.88f),
            VoicePresetData(engine = 0, fmRatioIndex = 2, fmDepth = 0.14f, detuneCents = 28, octShift = 1, pan = 0f, level = 0.78f)
        )
    )
)

// --- 8-WEGE HICHORD JOYSTICK-RICHTUNGEN (Originalgetreu nach HiChord Manual) ---
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

// --- POLYBLEP ANTI-ALIASING HILFSFUNKTION (Beseitigt digitales Sägezahn-Kratzen) ---
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

// --- HICHORD REVERSE-ENGINEERED 6-VOICE STEREO ENGINE + REVERB + TRUE PLUCK ---
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
    @Volatile var bassMode = 1 // 0: OFF, 1: ROOT (-2 Oct), 2: SLASH (5th/3rd in Bass)
    @Volatile var filterWheel = 0.58f // 0.0f .. 1.0f (wie das Drehrad am echten HiChord)

    // Echte ADSR- & Klangparameter pro Preset
    @Volatile var adsrMode = 0 // 0: SUST, 1: SWEL, 2: PLCK, 3: KEYS
    @Volatile var attackMs = 10f
    @Volatile var decayMs = 450f
    @Volatile var sustainLevel = 0.85f
    @Volatile var releaseMs = 350f
    @Volatile var baseCutoff = 0.45f
    @Volatile var filterEnvAmount = 0.45f
    @Volatile var vibratoDepth = 0.0015f
    @Volatile var glideSpeed = 0.006f
    @Volatile var reverbSend = 0.38f

    @Volatile var arpMode = 0 // 0: OFF, 1: UP, 2: DOWN, 3: U/D, 4: RAND

    // Performance FX
    @Volatile var fxDelay = false
    @Volatile var fxCrush = false
    @Volatile var fxStutter = false
    @Volatile var fxChorus = false
    @Volatile var fxPump = false
    @Volatile var fxReverb = true

    @Volatile private var syncResetRequested = false
    @Volatile private var noteTriggered = false

    val voices = Array(4) { v ->
        VoiceConfig(
            engine = 0,
            fmRatioIndex = 1,
            fmDepth = 0f,
            detuneCents = when (v) { 0 -> 0; 1 -> -8; 2 -> 8; else -> 14 },
            octShift = 0,
            pan = when (v) { 0 -> 0f; 1 -> -0.55f; 2 -> 0.55f; else -> 0.2f },
            level = 0.85f
        )
    }

    val scopeBuffer = FloatArray(128)
    private var phases = FloatArray(4)
    private var phasesDetune = FloatArray(4)
    private var phasesSub = FloatArray(4)
    private var modPhases = FloatArray(4)
    private var voiceEnvs = FloatArray(4) { 0f }
    private var bassPhase = 0f
    private var lfoPhase = 0f

    // Echte ADSR State-Machine (0 = Idle, 1 = Attack, 2 = Decay, 3 = Sustain, 4 = Release)
    private var envStage = 0
    private var ampEnv = 0f
    private var fltEnv = 0f

    // 12dB Resonant Chamberlin SVF Filter (Pro Kanal L/R)
    private var svfLowL = 0f
    private var svfBandL = 0f
    private var svfLowR = 0f
    private var svfBandR = 0f

    // Lush Stereo Schroeder Reverb (4 Comb Filters + 2 Allpass Filters)
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

    fun setGlobalWaveform(waveIdx: Int) {
        val w = waveIdx.coerceIn(0, 4)
        for (v in 0..3) voices[v].engine = w
    }

    fun applyAdsrMacro(mode: Int) {
        adsrMode = mode.coerceIn(0, 3)
        when (adsrMode) {
            0 -> { // SUSTAIN (Organ / Lead)
                attackMs = 6f; decayMs = 300f; sustainLevel = 0.90f; releaseMs = 220f
            }
            1 -> { // SWELL (Lush Pad / Strings)
                attackMs = 480f; decayMs = 800f; sustainLevel = 0.88f; releaseMs = 950f
            }
            2 -> { // PLUCK (Echter kurzer Pluck -> fällt komplett auf 0.0f ab!)
                attackMs = 1.5f; decayMs = 380f; sustainLevel = 0.0f; releaseMs = 240f
                filterEnvAmount = filterEnvAmount.coerceAtLeast(0.55f)
            }
            3 -> { // KEYS (E-Piano / Rhodes Decay mit sanftem Sustain-Teppich)
                attackMs = 3f; decayMs = 850f; sustainLevel = 0.20f; releaseMs = 400f
            }
        }
        triggerChordAttack()
    }

    fun loadSynthPreset(preset: SynthPreset) {
        adsrMode = preset.adsrMode
        attackMs = preset.attackMs
        decayMs = preset.decayMs
        sustainLevel = preset.sustainLevel
        releaseMs = preset.releaseMs
        baseCutoff = preset.baseCutoff
        filterWheel = preset.baseCutoff
        filterEnvAmount = preset.filterEnvAmount
        vibratoDepth = preset.vibratoDepth
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
        triggerChordAttack()
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
            baseCutoff = filterWheel, filterEnvAmount = filterEnvAmount,
            vibratoDepth = vibratoDepth, glideSpeed = glideSpeed,
            reverbSend = reverbSend, chorusOn = fxChorus, delayOn = fxDelay,
            voices = vData, isUser = true
        )
    }

    fun triggerChordAttack() { noteTriggered = true }
    fun resetClockSync() { syncResetRequested = true }

    fun triggerStrumChord() {
        for (v in 0..3) voiceEnvs[v] = 1.0f
        fltEnv = 1.0f
    }

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
            var strumSampleTimer = 0
            var strumNextVoice = 4

            while (isRunning) {
                val currentBpm = bpm.coerceIn(60, 200)
                val quarterSamples = ((sampleRate * 60) / currentBpm).coerceAtLeast(2000)
                val sixteenthSamples = (quarterSamples / 4).coerceAtLeast(500)
                val delaySamples = (sixteenthSamples * 3).coerceIn(1000, delayBufL.size - 1)
                val currentGlide = glideSpeed.coerceIn(0.0005f, 0.08f)

                // Echte ADSR-Koeffizienten pro Sample aus Millisekunden berechnen
                val attSamples = (attackMs * 0.001f * sampleRate).coerceAtLeast(15f)
                val attackStep = 1f / attSamples
                val decSamples = (decayMs * 0.001f * sampleRate).coerceAtLeast(200f)
                val decayMul = 0.001f.pow(1f / decSamples)
                val relSamples = (releaseMs * 0.001f * sampleRate).coerceAtLeast(200f)
                val releaseMul = 0.001f.pow(1f / relSamples)
                val fltDecayMul = 0.001f.pow(1f / (decSamples * 0.55f))

                // Im PLUCK-Modus (adsrMode == 2) ist Sustain zwingend 0.0f -> Ton verklingt komplett!
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

                    lfoPhase = (lfoPhase + 5.0f / sampleRate) % 1f
                    val vibratoFactor = 1f + sin(lfoPhase * 2f * PI.toFloat()) * vibratoDepth

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
                            envStage = 1
                            fltEnv = 1.0f
                        }
                    }

                    if (noteTriggered) {
                        noteTriggered = false
                        envStage = 1
                        fltEnv = 1.0f
                        if (strumMode) {
                            // Echter HiChord Strum-Roll: Spielt die 4 Akkordnoten nacheinander wie eine Gitarre an!
                            voiceEnvs[0] = 1.0f
                            voiceEnvs[1] = 0f; voiceEnvs[2] = 0f; voiceEnvs[3] = 0f
                            strumNextVoice = 1
                            strumSampleTimer = (sampleRate * 0.032f).toInt() // 32ms pro Saite
                        }
                    }

                    if (strumMode && strumNextVoice in 1..3) {
                        strumSampleTimer--
                        if (strumSampleTimer <= 0) {
                            voiceEnvs[strumNextVoice] = 1.0f
                            fltEnv = 0.85f
                            strumNextVoice++
                            strumSampleTimer = (sampleRate * 0.032f).toInt()
                        }
                    }

                    if (!gate && envStage != 0 && envStage != 4) {
                        envStage = 4
                    }

                    // ECHTE ADSR-HÜLLKURVEN-MASCHINE (Garantiert echtes Ausklingen auf 0.0f bei Plucks!)
                    when (envStage) {
                        1 -> { // ATTACK
                            ampEnv += attackStep
                            if (ampEnv >= 1.0f) {
                                ampEnv = 1.0f
                                envStage = 2
                            }
                        }
                        2 -> { // DECAY
                            ampEnv = targetSustain + (ampEnv - targetSustain) * decayMul
                            if (abs(ampEnv - targetSustain) < 0.0008f) {
                                ampEnv = targetSustain
                                envStage = if (targetSustain <= 0.001f) 0 else 3
                            }
                        }
                        3 -> { // SUSTAIN
                            ampEnv = targetSustain
                            if (!gate) envStage = 4
                        }
                        4 -> { // RELEASE
                            ampEnv *= releaseMul
                            if (ampEnv < 0.0005f) {
                                ampEnv = 0f
                                envStage = 0
                            }
                        }
                        else -> ampEnv = 0f
                    }

                    // Filter- & FM-Pluck-Hüllkurve fällt exponentiell ab (erzeugt den echten Zupf-/Hammer-Anschlag)
                    fltEnv *= fltDecayMul
                    if (fltEnv < 0.0005f) fltEnv = 0f

                    var mixL = 0f
                    var mixR = 0f

                    if (ampEnv > 0.0005f || strumMode) {
                        if (arpMode > 0) {
                            val vc = voices[arpVoice]
                            val octMult = 2f.pow(vc.octShift.toFloat())
                            val rawTarget = targetFreqs[arpVoice] * octMult * 2f.pow(vc.detuneCents / 1200f) * vibratoFactor
                            if (rawTarget > 20f) {
                                if (arpSmoothFreq <= 20f) arpSmoothFreq = rawTarget
                                arpSmoothFreq += (rawTarget - arpSmoothFreq) * (currentGlide * 1.8f)

                                val dt = (arpSmoothFreq / sampleRate).coerceIn(0.0001f, 0.45f)
                                val ratio = fmRatios[vc.fmRatioIndex.coerceIn(0, fmRatios.lastIndex)]
                                modPhases[0] = (modPhases[0] + dt * ratio) % 1f
                                // FM-Index fällt mit fltEnv ab -> metallischer Attack, warmer Ausklang!
                                val dynFm = vc.fmDepth * (0.25f + 0.75f * fltEnv)
                                val fmMod = sin(modPhases[0] * 2f * PI.toFloat()) * dynFm

                                val detRatio = if (fxChorus) 1.010f else 1.004f
                                phases[0] = (phases[0] + dt) % 1f
                                phasesDetune[0] = (phasesDetune[0] + dt * detRatio) % 1f
                                val p = (phases[0] + fmMod * 0.5f + 1f) % 1f
                                val p2 = phasesDetune[0]

                                var osc = when (vc.engine) {
                                    0 -> ((2f * p - 1f) - polyBlep(p, dt))
                                    1 -> {
                                        var sq = if (p < 0.5f) 1f else -1f
                                        sq += polyBlep(p, dt)
                                        sq -= polyBlep((p + 0.5f) % 1f, dt)
                                        sq * 0.8f
                                    }
                                    2 -> sin(p * 2f * PI.toFloat())
                                    3 -> {
                                        val s1 = (2f * p - 1f) - polyBlep(p, dt)
                                        val s2 = (2f * p2 - 1f) - polyBlep(p2, dt)
                                        (s1 + s2) * 0.58f
                                    }
                                    else -> sin((phases[0] * 2f * PI.toFloat()) + fmMod * 5.5f)
                                }

                                val sig = osc * 0.34f * vc.level * ampEnv
                                val panNorm = ((vc.pan + 1f) * 0.5f).coerceIn(0f, 1f)
                                mixL += sig * sqrt(1f - panNorm)
                                mixR += sig * sqrt(panNorm)
                            }
                        } else {
                            for (v in 0 until 4) {
                                val vc = voices[v]
                                val octMult = 2f.pow(vc.octShift.toFloat())
                                val tFreq = targetFreqs[v] * octMult * 2f.pow(vc.detuneCents / 1200f) * vibratoFactor
                                if (tFreq <= 20f) continue

                                if (currentFreqs[v] <= 20f) currentFreqs[v] = tFreq
                                currentFreqs[v] += (tFreq - currentFreqs[v]) * currentGlide
                                val freq = currentFreqs[v]
                                val dt = (freq / sampleRate).coerceIn(0.0001f, 0.45f)

                                val vEnv = if (strumMode) {
                                    voiceEnvs[v] *= decayMul
                                    if (!gate) voiceEnvs[v] *= 0.995f
                                    voiceEnvs[v]
                                } else {
                                    ampEnv
                                }
                                if (vEnv <= 0.0005f) continue

                                val ratio = fmRatios[vc.fmRatioIndex.coerceIn(0, fmRatios.lastIndex)]
                                modPhases[v] = (modPhases[v] + dt * ratio) % 1f
                                val dynFm = vc.fmDepth * (0.25f + 0.75f * fltEnv)
                                val fmMod = sin(modPhases[v] * 2f * PI.toFloat()) * dynFm

                                val detuneRatio = if (fxChorus) 1.011f else 1.0045f
                                phases[v] = (phases[v] + dt) % 1f
                                phasesDetune[v] = (phasesDetune[v] + dt * detuneRatio) % 1f
                                phasesSub[v] = (phasesSub[v] + dt * 0.9955f) % 1f
                                val p = (phases[v] + fmMod * 0.5f + 1f) % 1f
                                val p2 = phasesDetune[v]
                                val p3 = phasesSub[v]

                                var osc = when (vc.engine) {
                                    0 -> ((2f * p - 1f) - polyBlep(p, dt))
                                    1 -> {
                                        var sq = if (p < 0.5f) 1f else -1f
                                        sq += polyBlep(p, dt)
                                        sq -= polyBlep((p + 0.5f) % 1f, dt)
                                        sq * 0.8f
                                    }
                                    2 -> sin(p * 2f * PI.toFloat())
                                    3 -> { // Echter 3-Oszillator Bandlimited SuperSaw!
                                        val s1 = (2f * p - 1f) - polyBlep(p, dt)
                                        val s2 = (2f * p2 - 1f) - polyBlep(p2, dt)
                                        val s3 = (2f * p3 - 1f) - polyBlep(p3, dt)
                                        (s1 + s2 + s3) * 0.42f
                                    }
                                    else -> sin((phases[v] * 2f * PI.toFloat()) + fmMod * 5.5f)
                                }

                                if (fxChorus) {
                                    val chSig = (2f * p2 - 1f) - polyBlep(p2, dt)
                                    osc = osc * 0.70f + chSig * 0.38f
                                }

                                val voiceSig = osc * 0.21f * vc.level * vEnv
                                val panNorm = ((vc.pan + 1f) * 0.5f).coerceIn(0f, 1f)
                                mixL += voiceSig * sqrt(1f - panNorm)
                                mixR += voiceSig * sqrt(panNorm)
                            }
                        }

                        // Original HiChord BASS-Modus (OFF / ROOT / SLASH)
                        if (bassMode > 0 && targetBassFreq > 20f && ampEnv > 0.001f) {
                            if (currentBassFreq <= 20f) currentBassFreq = targetBassFreq
                            currentBassFreq += (targetBassFreq - currentBassFreq) * (currentGlide * 1.5f)
                            val bDt = (currentBassFreq / sampleRate).coerceIn(0.0001f, 0.25f)
                            bassPhase = (bassPhase + bDt) % 1f
                            val bSaw = ((2f * bassPhase - 1f) - polyBlep(bassPhase, bDt)) * 0.35f
                            val bSine = sin(bassPhase * 2f * PI.toFloat()) * 0.65f
                            val bassSig = (bSine + bSaw) * 0.22f * ampEnv
                            mixL += bassSig
                            mixR += bassSig
                        }
                    }

                    if (fxStutter) {
                        val stutterPeriod = (sixteenthSamples / 2).coerceAtLeast(1)
                        if ((sampleCounter % stutterPeriod) > (stutterPeriod / 2)) {
                            mixL = 0f; mixR = 0f
                        }
                    }

                    // 12dB Chamberlin SVF Tiefpassfilter mit dynamischem Pluck-Transient + HiChord Filter-Wheel
                    val wheelNorm = filterWheel.coerceIn(0.05f, 1.0f)
                    val envBoost = fltEnv * filterEnvAmount * 0.55f
                    val svfF = (0.04f + (wheelNorm * wheelNorm * 0.58f) + envBoost).coerceIn(0.035f, 0.78f)
                    val svfQ = 0.65f // Warme analoge Güte ohne Selbstoszillation

                    svfLowL += svfF * svfBandL
                    val highL = mixL - svfLowL - svfQ * svfBandL
                    svfBandL += svfF * highL

                    svfLowR += svfF * svfBandR
                    val highR = mixR - svfLowR - svfQ * svfBandR
                    svfBandR += svfF * highR

                    var finalL = svfLowL
                    var finalR = svfLowR

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

                    // Stereo Ping-Pong Tape Delay (Kein Geister-Feedback mehr, wenn fxDelay == false!)
                    val readPos = (delayWritePos - delaySamples + delayBufL.size) % delayBufL.size
                    if (fxDelay) {
                        val dL = delayBufL[readPos]
                        val dR = delayBufR[readPos]
                        finalL += dR * 0.42f
                        finalR += dL * 0.42f
                        delayBufL[delayWritePos] = (finalL * 0.62f).coerceIn(-1f, 1f)
                        delayBufR[delayWritePos] = (finalR * 0.62f).coerceIn(-1f, 1f)
                    } else {
                        delayBufL[delayWritePos] = 0f
                        delayBufR[delayWritePos] = 0f
                    }
                    delayWritePos = (delayWritePos + 1) % delayBufL.size

                    // Lush Stereo Schroeder Reverb (Verleiht Plucks, Keys und Cloudrap-Pads echten Studio-Raum!)
                    if (fxReverb && reverbSend > 0.01f) {
                        val revIn = (finalL + finalR) * 0.5f * reverbSend
                        val c1 = comb1[cPos1]; comb1[cPos1] = (revIn + c1 * 0.78f).coerceIn(-1f, 1f); cPos1 = (cPos1 + 1) % comb1.size
                        val c2 = comb2[cPos2]; comb2[cPos2] = (revIn + c2 * 0.80f).coerceIn(-1f, 1f); cPos2 = (cPos2 + 1) % comb2.size
                        val c3 = comb3[cPos3]; comb3[cPos3] = (revIn + c3 * 0.82f).coerceIn(-1f, 1f); cPos3 = (cPos3 + 1) % comb3.size
                        val c4 = comb4[cPos4]; comb4[cPos4] = (revIn + c4 * 0.84f).coerceIn(-1f, 1f); cPos4 = (cPos4 + 1) % comb4.size

                        val combSumL = (c1 + c3) * 0.35f
                        val combSumR = (c2 + c4) * 0.35f

                        val ap1Buf = allpass1[aPos1]
                        val ap1Out = -combSumL + ap1Buf
                        allpass1[aPos1] = (combSumL + ap1Buf * 0.5f).coerceIn(-1f, 1f)
                        aPos1 = (aPos1 + 1) % allpass1.size

                        val ap2Buf = allpass2[aPos2]
                        val ap2Out = -combSumR + ap2Buf
                        allpass2[aPos2] = (combSumR + ap2Buf * 0.5f).coerceIn(-1f, 1f)
                        aPos2 = (aPos2 + 1) % allpass2.size

                        finalL += ap1Out * 0.45f
                        finalR += ap2Out * 0.45f
                    }

                    // Analog Soft-Clipper (tanh-Approximation verhindert hartes digitales Kratzen)
                    val outL = tanh(finalL * 1.1f)
                    val outR = tanh(finalR * 1.1f)
                    if (i < scopeBuffer.size) scopeBuffer[i] = (outL + outR) * 0.5f

                    stereoBuffer[i * 2] = (outL * 30500f).toInt().toShort()
                    stereoBuffer[i * 2 + 1] = (outR * 30500f).toInt().toShort()
                }
                track.write(stereoBuffer, 0, stereoBuffer.size)
            }
            try { track.stop(); track.release() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        audioThread = null
    }
}

// --- MUSIKTHEORIE: ORIGINAL HICHORD 8-WAY JOYSTICK VOICING + AUTO VOICE LEADING ---
val rootNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
val majorScaleOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
val defaultQuality = intArrayOf(0, 1, 1, 0, 0, 1, 2) // 0: Maj, 1: Min, 2: Dim

fun midiToFreq(midi: Int): Float = 440f * 2f.pow((midi - 69) / 12f)

data class ChordVoicingResult(
    val displayName: String,
    val voiceFreqs: FloatArray,
    val bassFreq: Float
)

fun buildHiChordVoicing(
    keyIndex: Int,
    degree: Int,
    octaveShift: Int,
    joyZone: JoyChordZone,
    voiceLeadingOn: Boolean,
    bassMode: Int
): ChordVoicingResult {
    val rootSemitone = keyIndex + majorScaleOffsets[degree]
    val rootMidiBase = 60 + rootSemitone + (octaveShift * 12)
    val noteName = rootNames[rootSemitone % 12]

    var qual = defaultQuality[degree] // 0 = Maj, 1 = Min, 2 = Dim
    var third = if (qual == 0) 4 else 3
    var fifth = if (qual == 2) 6 else 7
    var fourthNote = 12 // Standard: Oktav-Verdopplung des Grundtons
    var chordLabelSuffix = when (qual) {
        1 -> "m"
        2 -> "dim"
        else -> ""
    }

    // 8-Wege HiChord Joystick Harmonie-Transformation (exakt nach HiChord Handbuch)
    when (joyZone) {
        JoyChordZone.CENTER -> {}
        JoyChordZone.UP -> { // FLIP Maj <-> Min
            qual = if (qual == 0) 1 else 0
            third = if (qual == 0) 4 else 3
            fifth = 7
            chordLabelSuffix = if (qual == 1) "m" else "MAJ"
        }
        JoyChordZone.UP_RIGHT -> { // Bluesy Dom 7th
            third = 4; fifth = 7; fourthNote = 10
            chordLabelSuffix = "7"
        }
        JoyChordZone.RIGHT -> { // Jazzy Maj7 / min7
            fourthNote = if (qual == 0) 11 else 10
            chordLabelSuffix = if (qual == 0) "maj7" else "m7"
        }
        JoyChordZone.DOWN_RIGHT -> { // Lush Add9 / 9th
            fourthNote = 14
            chordLabelSuffix = if (qual == 1) "m9" else "add9"
        }
        JoyChordZone.DOWN -> { // Open Sus4
            third = 5; fifth = 7
            chordLabelSuffix = "sus4"
        }
        JoyChordZone.DOWN_LEFT -> { // Sweet 6th
            fourthNote = 9
            chordLabelSuffix = if (qual == 1) "m6" else "6"
        }
        JoyChordZone.LEFT -> { // Dark Diminished 7th
            third = 3; fifth = 6; fourthNote = 9
            chordLabelSuffix = "dim7"
        }
        JoyChordZone.UP_LEFT -> { // Dreamy Augmented
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

    // Original HiChord AUTO VOICE LEADING:
    // Faltet alle Akkordnoten automatisch in das engste Stimmführungs-Fenster (58..71 MIDI),
    // sodass Akkordwechsel butterweich klingen statt parallel zu springen!
    if (voiceLeadingOn) {
        val centerTarget = 64 + (octaveShift * 12)
        for (i in 0..3) {
            while (midiNotes[i] - centerTarget > 6) midiNotes[i] -= 12
            while (centerTarget - midiNotes[i] > 6) midiNotes[i] += 12
        }
        midiNotes.sort()
    }

    // Original HiChord BASS-Modus (0 = OFF, 1 = ROOT, 2 = SLASH)
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
    var bpm by remember { mutableStateOf(120) }
    var activeDegree by remember { mutableStateOf(-1) }
    var displayChord by remember { mutableStateOf("HI-CHORD") }

    var voiceLeadingOn by remember { mutableStateOf(true) }
    var bassMode by remember { mutableStateOf(1) } // 0: OFF, 1: ROOT, 2: SLASH
    var octaveShift by remember { mutableStateOf(0) }
    var holdLatch by remember { mutableStateOf(false) }
    var strumMode by remember { mutableStateOf(false) }
    var filterWheel by remember { mutableStateOf(0.58f) }

    var waveIndex by remember { mutableStateOf(0) }
    var adsrMode by remember { mutableStateOf(3) }
    var arpMode by remember { mutableStateOf(0) }
    var activeSlot by remember { mutableStateOf(0) }
    var uiRefreshCounter by remember { mutableStateOf(0) }

    val userPresets = remember {
        val list = mutableStateListOf<SynthPreset>()
        val rawSet = prefs.getStringSet("user_presets_v4", emptySet()) ?: emptySet()
        rawSet.mapNotNull { SynthPreset.deserialize(it) }
            .sortedBy { it.name }
            .forEach { list.add(it) }
        list
    }

    // 4 Schnellwahl-Slots: 2 HiChord Classics + 2 Genre-Signaturen
    val slotPresets = remember {
        mutableStateListOf(
            factoryPresets[0],  // FM E-PIANO (HiChord OG)
            factoryPresets[5],  // DELICATE HARP (HiChord OG Pluck)
            factoryPresets[8],  // OPIUM RAGE (Ken Carson)
            factoryPresets[10]  // SADBOYS 2002 (Yung Lean)
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
        prefs.edit().putStringSet("user_presets_v4", serialized).apply()
    }

    var joyX by remember { mutableStateOf(0f) }
    var joyY by remember { mutableStateOf(0f) }
    val activeJoyZone = getJoystickZone(joyX, joyY)

    LaunchedEffect(activeDegree, keyIndex, octaveShift, activeJoyZone, voiceLeadingOn, bassMode) {
        if (activeDegree in 0..6) {
            val res = buildHiChordVoicing(keyIndex, activeDegree, octaveShift, activeJoyZone, voiceLeadingOn, bassMode)
            displayChord = res.displayName
            for (i in 0..3) synth.targetFreqs[i] = res.voiceFreqs[i]
            synth.targetBassFreq = res.bassFreq
            synth.gate = true
        } else {
            synth.gate = false
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
            activeDegree = activeDegree,
            onDegreeChange = { deg -> activeDegree = deg },
            holdLatch = holdLatch,
            onToggleHold = { holdLatch = !holdLatch; if (!holdLatch) activeDegree = -1 },
            strumMode = strumMode,
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
            bpm = bpm,
            onBpmChange = { bpm = it; synth.bpm = it },
            activeDegree = activeDegree,
            onDegreeChange = { activeDegree = it },
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
            onToggleHold = { holdLatch = !holdLatch; if (!holdLatch) activeDegree = -1 },
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
    synth: HiChordEngine,
    keyIndex: Int,
    activeDegree: Int,
    onDegreeChange: (Int) -> Unit,
    holdLatch: Boolean,
    onToggleHold: () -> Unit,
    strumMode: Boolean,
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
    val romanDegrees = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
    val refresh = uiRefreshCounter

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
                            text = if (showBrowser) "PRESET LIBRARY" else "EXPERT // 4-VOICE LAB",
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
                title = if (showBrowser) "VOICE LAB" else "LIBRARY",
                sub = "16+ PRESETS",
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
                        text = "LOAD INTO SLOT 0${activeSlot + 1} // 8 HICHORD ORIGINALS + 8 RAGE/CLOUDRAP STUDIO PATCHES",
                        color = Color(0xFF00E5FF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "USER SAVED: ${userPresets.size}",
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
                                        preset.category == "HICHORD OG" -> Color(0xFF2F2D27)
                                        else -> Color(0xFF242426)
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
                        TeBarSlider(label = "FM DEPTH", valueText = "${(vc.fmDepth * 100).roundToInt()}%", fraction = vc.fmDepth, barColor = Color(0xFF00E5FF), onValueChange = { vc.fmDepth = it; onParamChanged() })
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
                    .pointerInput(holdLatch, strumMode) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun mapXToDeg(x: Float): Int {
                                if (x < 0f || x > stripWidth) return -1
                                return ((x / stripWidth.toFloat()) * 7f).toInt().coerceIn(0, 6)
                            }

                            var curDeg = mapXToDeg(down.position.x)
                            if (curDeg in 0..6) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onDegreeChange(curDeg)
                                synth.triggerChordAttack()
                            }

                            do {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed }
                                if (ch != null) {
                                    ch.consume()
                                    val newDeg = mapXToDeg(ch.position.x)
                                    if (newDeg in 0..6 && newDeg != curDeg) {
                                        curDeg = newDeg
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        onDegreeChange(newDeg)
                                        if (synth.arpMode == 0) synth.triggerChordAttack()
                                    }
                                }
                            } while (ev.changes.any { it.pressed })

                            if (!holdLatch) {
                                onDegreeChange(-1)
                            }
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (deg in 0..6) {
                    val noteLabel = rootNames[(keyIndex + majorScaleOffsets[deg]) % 12]
                    val qualSuffix = when (defaultQuality[deg]) { 1 -> "m"; 2 -> "°"; else -> "" }
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

// --- HOCHFORMAT: HICHORD REVERSE-ENGINEERED INTERFACE ---
@Composable
fun TePortraitJamScreen(
    synth: HiChordEngine,
    keyIndex: Int,
    onKeyChange: (Int) -> Unit,
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    activeDegree: Int,
    onDegreeChange: (Int) -> Unit,
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
    var lastZone by remember { mutableStateOf(JoyChordZone.CENTER) }

    val arpNames = listOf("ARP", "UP", "DOWN", "U/D", "RAND")
    val bassLabels = listOf("BASS:OFF", "BASS:ROOT", "BASS:SLSH")
    val romanDegrees = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
    val degreeSubLabels = listOf("TONIC", "SUPER", "MEDI", "SUBD", "DOM", "SUBM", "LEAD")

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
            // Original HiChord Top-Edge Filter/Tone Wheel
            var wheelW by remember { mutableStateOf(1) }
            Box(
                modifier = Modifier
                    .width(105.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(TePanelDark)
                    .border(1.dp, Color(0xFF555550), RoundedCornerShape(4.dp))
                    .onSizeChanged { wheelW = it.width.coerceAtLeast(1) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val d = awaitFirstDown(requireUnconsumed = false)
                            onFilterWheelChange((d.position.x / wheelW.toFloat()).coerceIn(0.05f, 1f))
                            do {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed }
                                if (ch != null) {
                                    ch.consume()
                                    onFilterWheelChange((ch.position.x / wheelW.toFloat()).coerceIn(0.05f, 1f))
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

        // 2. OLED DISPLAY & ORIGINAL HICHORD 8-WEGE CHORD-MODIFICATION JOYSTICK
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // HiChord OLED Display
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
                        DisplayBadge(text = "KEY ${rootNames[keyIndex]}", bgColor = TeOrange, textColor = Color.White)
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
                                    activeDegree >= 0 -> "${activePresetCategory} · ${adsrNames[adsrMode]}"
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

            // ORIGINAL HICHORD 8-WEGE CHORD MODIFICATION JOYSTICK (mit 8 beschrifteten Richtungs-Punkten)
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
                            onDragStart = {
                                localX = 0f; localY = 0f
                            },
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

                    // Die 8 originalen HiChord-Punkte im Kreis um den Joystick
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

                // Beschriftung der 4 Hauptachsen + aktiver Akkord-Morph
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
        SectionDivider("SYSTEM // 1-BPM, TAP-SYNC, WAVE, ADSR & ARP")
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
            TeTactileButton(title = engineNames[waveIndex], sub = "WAVE", modifier = Modifier.weight(0.95f), height = 42.dp, bgColor = TeOchre, shadowColor = Color(0xFFA67621), textColor = TePanelDark, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onWaveChange((waveIndex + 1) % engineNames.size) })
            TeTactileButton(title = adsrNames[adsrMode], sub = "ADSR", modifier = Modifier.weight(0.95f), height = 42.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = adsrMode == 2, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onAdsrChange((adsrMode + 1) % adsrNames.size) })
            TeTactileButton(title = arpNames[arpMode], sub = "ARP", modifier = Modifier.weight(0.95f), height = 42.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = arpMode > 0, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onArpChange((arpMode + 1) % arpNames.size) })
        }

        // 4. PERFORMANCE FX + REVERB & SIDECHAIN PUMP
        SectionDivider("PERFORMANCE FX // MOMENTARY FX + REVERB & PUMP")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TeMomentaryFxButton(title = "DELAY", sub = "ECHO", isPressed = fxDelay, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxDelay = true; synth.fxDelay = true }, onRelease = { fxDelay = false; synth.fxDelay = false })
            TeMomentaryFxButton(title = "CRUSH", sub = "8-BIT", isPressed = fxCrush, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxCrush = true; synth.fxCrush = true }, onRelease = { fxCrush = false; synth.fxCrush = false })
            TeMomentaryFxButton(title = "STUTTR", sub = "1/32", isPressed = fxStutter, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxStutter = true; synth.fxStutter = true }, onRelease = { fxStutter = false; synth.fxStutter = false })
            TeMomentaryFxButton(title = "CHORUS", sub = "WIDE", isPressed = fxChorus, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxChorus = true; synth.fxChorus = true }, onRelease = { fxChorus = false; synth.fxChorus = false })
            TeTactileButton(title = "REVERB", sub = "SPACE", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxReverb) TeOrange else TeKeyDark, shadowColor = if (fxReverb) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = fxReverb, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxReverb = !fxReverb; synth.fxReverb = fxReverb })
            TeTactileButton(title = "PUMP", sub = "DUCK", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxPump) TeOrange else TeBlue, shadowColor = if (fxPump) TeOrangeShadow else TeBlueShadow, textColor = Color.White, ledOn = fxPump, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxPump = !fxPump; synth.fxPump = fxPump })
        }

        // 5. ORIGINAL HICHORD PLAY MODES (HOLD, STRUM, VOICE LEADING, BASS, OCTAVE)
        SectionDivider("HICHORD VOICING & PLAY MODES // V-LEAD, STRUM & BASS")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TeTactileButton(title = "HOLD", sub = "DRONE", modifier = Modifier.weight(0.95f), height = 46.dp, bgColor = if (holdLatch) TeBlue else TeKeyDark, shadowColor = if (holdLatch) TeBlueShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = holdLatch, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleHold() })
            TeTactileButton(title = "STRUM", sub = "GUITAR", modifier = Modifier.weight(0.95f), height = 46.dp, bgColor = if (strumMode) TeOrange else TeKeyDark, shadowColor = if (strumMode) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = strumMode, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleStrum() })
            TeTactileButton(title = "V-LEAD", sub = "SMOOTH", modifier = Modifier.weight(1.0f), height = 46.dp, bgColor = if (voiceLeadingOn) TeOrange else TeKeyDark, shadowColor = if (voiceLeadingOn) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = voiceLeadingOn, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleVoiceLeading() })
            TeTactileButton(title = bassLabels[bassMode], sub = "SUB-OSC", modifier = Modifier.weight(1.1f), height = 46.dp, bgColor = if (bassMode > 0) TeKeyDark else Color(0xFF3A3A38), shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = bassMode > 0, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onCycleBassMode() })
            TeTactileButton(title = "OCT -", sub = "DOWN", modifier = Modifier.weight(0.8f), height = 46.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = octaveShift < 0, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onOctaveChange(if (octaveShift == -1) 0 else -1) })
            TeTactileButton(title = "OCT +", sub = "UP", modifier = Modifier.weight(0.8f), height = 46.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = octaveShift > 0, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onOctaveChange(if (octaveShift == 1) 0 else 1) })
        }

        // 6. 7 NASHVILLE DIATONIC CHORD BUTTONS (I - VII)
        SectionDivider("NASHVILLE CHORD BUTTONS // HOLD + TILT JOYSTICK TO MORPH")
        var padGridSize by remember { mutableStateOf(IntSize.Zero) }

        Column(
            modifier = Modifier.fillMaxWidth().onSizeChanged { padGridSize = it }.pointerInput(holdLatch) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun mapOffsetToDegree(pos: Offset): Int {
                        if (padGridSize.width <= 0 || padGridSize.height <= 0) return -1
                        if (pos.x < 0f || pos.x > padGridSize.width || pos.y < 0f || pos.y > padGridSize.height) return -1
                        val isBottomRow = pos.y > padGridSize.height / 2f
                        return if (!isBottomRow) ((pos.x / padGridSize.width) * 4f).toInt().coerceIn(0, 3) else 4 + ((pos.x / padGridSize.width) * 3f).toInt().coerceIn(0, 2)
                    }

                    var currentDeg = mapOffsetToDegree(down.position)
                    if (currentDeg in 0..6) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onDegreeChange(currentDeg)
                        synth.triggerChordAttack()
                    }

                    do {
                        val event = awaitPointerEvent()
                        val activeChange = event.changes.firstOrNull { it.pressed }
                        if (activeChange != null) {
                            activeChange.consume()
                            val slidedDeg = mapOffsetToDegree(activeChange.position)
                            if (slidedDeg in 0..6 && slidedDeg != currentDeg) {
                                currentDeg = slidedDeg
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onDegreeChange(slidedDeg)
                                if (synth.arpMode == 0) synth.triggerChordAttack()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!holdLatch) onDegreeChange(-1)
                }
            },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (deg in 0..3) {
                    val noteLabel = rootNames[(keyIndex + majorScaleOffsets[deg]) % 12]; val qualSuffix = if (defaultQuality[deg] == 1) "m" else ""
                    TeChordKeycap(number = "0${deg + 1}", roman = romanDegrees[deg], subLabel = degreeSubLabels[deg], note = "$noteLabel$qualSuffix", isPressed = activeDegree == deg, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (deg in 4..6) {
                    val noteLabel = rootNames[(keyIndex + majorScaleOffsets[deg]) % 12]; val qualSuffix = when (defaultQuality[deg]) { 1 -> "m"; 2 -> "°"; else -> "" }
                    TeChordKeycap(number = "0${deg + 1}", roman = romanDegrees[deg], subLabel = degreeSubLabels[deg], note = "$noteLabel$qualSuffix", isPressed = activeDegree == deg, modifier = Modifier.weight(1f).fillMaxHeight())
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
