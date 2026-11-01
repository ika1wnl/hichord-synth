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

// --- TEENAGE ENGINEERING FARBPALETTE ---
val TeChassis = Color(0xFFD8D6D0)
val TePanelDark = Color(0xFF191919)
val TeKeyDark = Color(0xFF2B2B2B)
val TeKeyDarkShadow = Color(0xFF121212)
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

// --- HYBRID-VOICE-PARAMETER & ERWEITERTES PRESET-DATENMODELL ---
class VoiceConfig(
    @Volatile var engine: Int = 0,       // 0: SAW, 1: PULS, 2: SINE, 3: SSAW, 4: FM
    @Volatile var fmRatioIndex: Int = 1, // 0: 0.5x, 1: 1x, 2: 2x, 3: 3x, 4: 4x, 5: 7x
    @Volatile var fmDepth: Float = 0f,
    @Volatile var detuneCents: Int = 0,
    @Volatile var octShift: Int = 0,     // -1, 0, +1 Oktave pro Stimme
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
    val glideSpeed: Float,
    val defaultEnv: Int,       // 0: ORG, 1: PAD, 2: PLK
    val baseCutoff: Float,     // 0.08f (Dark) .. 0.85f (Bright)
    val filterEnvMod: Float,   // Filter-Pluck-Punch (0f .. 0.8f)
    val pluckDecay: Float,     // 0.9990f (Short Snap) .. 0.99965f (Long Bell)
    val vibratoDepth: Float,   // Lo-Fi Tape Wobble / Vibrato
    val presetChorus: Boolean,
    val presetDelay: Boolean,
    val presetCrush: Boolean,
    val voices: List<VoicePresetData>,
    val isUser: Boolean = false
) {
    fun serialize(): String {
        val vStr = voices.joinToString(";") {
            "${it.engine},${it.fmRatioIndex},${it.fmDepth},${it.detuneCents},${it.octShift},${it.pan},${it.level}"
        }
        return "$id|$name|$category|$glideSpeed|$defaultEnv|$baseCutoff|$filterEnvMod|$pluckDecay|$vibratoDepth|$presetChorus|$presetDelay|$presetCrush|$vStr"
    }

    companion object {
        fun deserialize(raw: String): SynthPreset? {
            return try {
                val p = raw.split("|")
                if (p.size < 13) return null
                val vList = p[12].split(";").map { vRaw ->
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
                    id = p[0],
                    name = p[1],
                    category = p[2],
                    glideSpeed = p[3].toFloat(),
                    defaultEnv = p[4].toInt(),
                    baseCutoff = p[5].toFloat(),
                    filterEnvMod = p[6].toFloat(),
                    pluckDecay = p[7].toFloat(),
                    vibratoDepth = p[8].toFloat(),
                    presetChorus = p[9].toBoolean(),
                    presetDelay = p[10].toBoolean(),
                    presetCrush = p[11].toBoolean(),
                    voices = vList,
                    isUser = true
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

val fmRatios = floatArrayOf(0.5f, 1.0f, 2.0f, 3.0f, 4.0f, 7.0f)
val engineNames = listOf("SAW", "PULS", "SINE", "SSAW", "FM")

// --- 16 KOMPLETT UNTERSCHIEDLICHE PRESETS (8 HICHORD CLASSICS + 8 TRAP/CLOUDRAP) ---
val factoryPresets = listOf(
    // === 1. ORIGINAL HICHORD HARDWARE CLASSICS (hichord.shop) ===
    SynthPreset(
        id = "hc_warmpad", name = "WARM PAD", category = "HICHORD",
        glideSpeed = 0.0025f, defaultEnv = 1, baseCutoff = 0.22f, filterEnvMod = 0.15f, pluckDecay = 0.9995f, vibratoDepth = 0.0015f,
        presetChorus = true, presetDelay = false, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 0, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -10, octShift = 0, pan = -0.65f, level = 0.85f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 10, octShift = 0, pan = 0.65f, level = 0.85f),
            VoicePresetData(engine = 2, fmRatioIndex = 2, fmDepth = 0.08f, detuneCents = 5, octShift = 0, pan = 0f, level = 0.75f)
        )
    ),
    SynthPreset(
        id = "hc_dreamynth", name = "DREAMYNTH", category = "HICHORD",
        glideSpeed = 0.0030f, defaultEnv = 2, baseCutoff = 0.35f, filterEnvMod = 0.45f, pluckDecay = 0.99955f, vibratoDepth = 0.0045f,
        presetChorus = true, presetDelay = true, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.12f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.90f),
            VoicePresetData(engine = 4, fmRatioIndex = 2, fmDepth = 0.28f, detuneCents = -14, octShift = 0, pan = -0.75f, level = 0.80f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 14, octShift = 0, pan = 0.75f, level = 0.80f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.22f, detuneCents = 18, octShift = 1, pan = 0.25f, level = 0.65f)
        )
    ),
    SynthPreset(
        id = "hc_60sorgan", name = "60s ORGAN", category = "HICHORD",
        glideSpeed = 0.0250f, defaultEnv = 0, baseCutoff = 0.55f, filterEnvMod = 0.0f, pluckDecay = 0.9994f, vibratoDepth = 0.0025f,
        presetChorus = false, presetDelay = false, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.15f, detuneCents = 0, octShift = -1, pan = 0f, level = 0.90f),
            VoicePresetData(engine = 1, fmRatioIndex = 2, fmDepth = 0.10f, detuneCents = -3, octShift = 0, pan = -0.45f, level = 0.85f),
            VoicePresetData(engine = 2, fmRatioIndex = 3, fmDepth = 0.20f, detuneCents = 3, octShift = 1, pan = 0.45f, level = 0.75f),
            VoicePresetData(engine = 1, fmRatioIndex = 4, fmDepth = 0.12f, detuneCents = 6, octShift = 1, pan = 0f, level = 0.65f)
        )
    ),
    SynthPreset(
        id = "hc_80sbrass", name = "80s BRASS", category = "HICHORD",
        glideSpeed = 0.0038f, defaultEnv = 0, baseCutoff = 0.25f, filterEnvMod = 0.65f, pluckDecay = 0.9995f, vibratoDepth = 0.0012f,
        presetChorus = true, presetDelay = false, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.05f, detuneCents = -6, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -16, octShift = 0, pan = -0.80f, level = 0.90f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 16, octShift = 0, pan = 0.80f, level = 0.90f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.08f, detuneCents = 22, octShift = 0, pan = 0f, level = 0.85f)
        )
    ),
    SynthPreset(
        id = "hc_cathedral", name = "CATHEDRAL", category = "HICHORD",
        glideSpeed = 0.0018f, defaultEnv = 1, baseCutoff = 0.42f, filterEnvMod = 0.10f, pluckDecay = 0.9996f, vibratoDepth = 0.0010f,
        presetChorus = true, presetDelay = true, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.05f, detuneCents = 0, octShift = -1, pan = 0f, level = 1.0f),
            VoicePresetData(engine = 3, fmRatioIndex = 2, fmDepth = 0.10f, detuneCents = -8, octShift = 0, pan = -0.85f, level = 0.80f),
            VoicePresetData(engine = 3, fmRatioIndex = 2, fmDepth = 0.10f, detuneCents = 8, octShift = 0, pan = 0.85f, level = 0.80f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.25f, detuneCents = 12, octShift = 1, pan = 0f, level = 0.70f)
        )
    ),
    SynthPreset(
        id = "hc_lofikeys", name = "LOFI RHODES", category = "HICHORD",
        glideSpeed = 0.0090f, defaultEnv = 2, baseCutoff = 0.26f, filterEnvMod = 0.40f, pluckDecay = 0.99950f, vibratoDepth = 0.0055f,
        presetChorus = false, presetDelay = false, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.18f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 1, fmDepth = 0.32f, detuneCents = -7, octShift = 0, pan = -0.50f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 2, fmDepth = 0.24f, detuneCents = 7, octShift = 0, pan = 0.50f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.18f, detuneCents = 15, octShift = 0, pan = 0.20f, level = 0.65f)
        )
    ),
    SynthPreset(
        id = "hc_chiptune", name = "POCKET 8-BIT", category = "HICHORD",
        glideSpeed = 0.0300f, defaultEnv = 2, baseCutoff = 0.78f, filterEnvMod = 0.20f, pluckDecay = 0.99915f, vibratoDepth = 0f,
        presetChorus = false, presetDelay = false, presetCrush = true,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 0, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 0, octShift = 0, pan = -0.60f, level = 0.85f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 0, octShift = 0, pan = 0.60f, level = 0.85f),
            VoicePresetData(engine = 1, fmRatioIndex = 2, fmDepth = 0f, detuneCents = 0, octShift = 1, pan = 0f, level = 0.75f)
        )
    ),
    SynthPreset(
        id = "hc_basschord", name = "SUB BASS STACK", category = "HICHORD",
        glideSpeed = 0.0040f, defaultEnv = 2, baseCutoff = 0.18f, filterEnvMod = 0.55f, pluckDecay = 0.99940f, vibratoDepth = 0f,
        presetChorus = false, presetDelay = false, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 0, fmDepth = 0.20f, detuneCents = 0, octShift = -1, pan = 0f, level = 1.0f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.10f, detuneCents = -8, octShift = -1, pan = -0.35f, level = 0.90f),
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 8, octShift = 0, pan = 0.35f, level = 0.75f),
            VoicePresetData(engine = 0, fmRatioIndex = 2, fmDepth = 0.15f, detuneCents = 12, octShift = 0, pan = 0f, level = 0.65f)
        )
    ),

    // === 2. KEN CARSON / YUNG LEAN / JUICE WRLD SIGNATURE PRESETS ===
    SynthPreset(
        id = "kc_opium", name = "KEN CARSON RAGE", category = "OPIUM / RAGE",
        glideSpeed = 0.0075f, defaultEnv = 0, baseCutoff = 0.82f, filterEnvMod = 0.25f, pluckDecay = 0.9993f, vibratoDepth = 0.0010f,
        presetChorus = true, presetDelay = false, presetCrush = false,
        voices = listOf(
            // V1: Brutaler Sub-Saw (-1 Oct)
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.22f, detuneCents = 0, octShift = -1, pan = 0f, level = 1.0f),
            // V2 & V3: Extrem breite, hart verstimmte Hyper-SuperSaws
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0.12f, detuneCents = -34, octShift = 0, pan = -0.95f, level = 0.92f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0.12f, detuneCents = 34, octShift = 0, pan = 0.95f, level = 0.92f),
            // V4: Schneidender High-Octave Rage Lead (+1 Oct)
            VoicePresetData(engine = 3, fmRatioIndex = 2, fmDepth = 0.28f, detuneCents = 42, octShift = 1, pan = 0f, level = 0.85f)
        )
    ),
    SynthPreset(
        id = "kc_filthy", name = "FILTHY XTENDED", category = "OPIUM / RAGE",
        glideSpeed = 0.0050f, defaultEnv = 2, baseCutoff = 0.70f, filterEnvMod = 0.45f, pluckDecay = 0.99918f, vibratoDepth = 0f,
        presetChorus = true, presetDelay = true, presetCrush = true,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 0, fmDepth = 0.35f, detuneCents = -5, octShift = -1, pan = 0f, level = 1.0f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0.25f, detuneCents = -28, octShift = 0, pan = -0.85f, level = 0.90f),
            VoicePresetData(engine = 0, fmRatioIndex = 2, fmDepth = 0.35f, detuneCents = 28, octShift = 0, pan = 0.85f, level = 0.90f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.55f, detuneCents = 38, octShift = 1, pan = 0f, level = 0.80f)
        )
    ),
    SynthPreset(
        id = "yl_sadboys", name = "YUNG LEAN 2002", category = "CLOUDRAP",
        glideSpeed = 0.0020f, defaultEnv = 1, baseCutoff = 0.48f, filterEnvMod = 0.30f, pluckDecay = 0.9996f, vibratoDepth = 0.0038f,
        presetChorus = true, presetDelay = true, presetCrush = false,
        voices = listOf(
            // Warm Sine Sub (-1 Oct) + Shimmering High-Octave FM Bells (+1 Oct)
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.05f, detuneCents = 0, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.42f, detuneCents = -16, octShift = 0, pan = -0.80f, level = 0.78f),
            VoicePresetData(engine = 3, fmRatioIndex = 2, fmDepth = 0.15f, detuneCents = 16, octShift = 0, pan = 0.80f, level = 0.80f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.48f, detuneCents = 24, octShift = 1, pan = 0.30f, level = 0.72f)
        )
    ),
    SynthPreset(
        id = "yl_whitearmor", name = "WHITEARMOR MIST", category = "CLOUDRAP",
        glideSpeed = 0.0014f, defaultEnv = 1, baseCutoff = 0.58f, filterEnvMod = 0.20f, pluckDecay = 0.9996f, vibratoDepth = 0.0025f,
        presetChorus = true, presetDelay = true, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = -12, octShift = 0, pan = -0.50f, level = 0.85f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 12, octShift = 0, pan = 0.50f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.38f, detuneCents = -22, octShift = 1, pan = -0.90f, level = 0.75f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.45f, detuneCents = 22, octShift = 1, pan = 0.90f, level = 0.75f)
        )
    ),
    SynthPreset(
        id = "jw_lucid", name = "JUICE WRLD KEYS", category = "EMO TRAP",
        glideSpeed = 0.0055f, defaultEnv = 2, baseCutoff = 0.32f, filterEnvMod = 0.50f, pluckDecay = 0.99945f, vibratoDepth = 0.0048f,
        presetChorus = true, presetDelay = true, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 1, fmDepth = 0.22f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 2, fmDepth = 0.35f, detuneCents = -9, octShift = 0, pan = -0.60f, level = 0.85f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.15f, detuneCents = 9, octShift = 0, pan = 0.60f, level = 0.82f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.30f, detuneCents = -18, octShift = 1, pan = 0.15f, level = 0.68f)
        )
    ),
    SynthPreset(
        id = "jw_nickmira", name = "NICK MIRA PLUCK", category = "EMO TRAP",
        glideSpeed = 0.0080f, defaultEnv = 2, baseCutoff = 0.28f, filterEnvMod = 0.68f, pluckDecay = 0.99920f, vibratoDepth = 0.0022f,
        presetChorus = true, presetDelay = true, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 1, fmRatioIndex = 1, fmDepth = 0.18f, detuneCents = 0, octShift = 0, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 0, fmRatioIndex = 2, fmDepth = 0.25f, detuneCents = -11, octShift = 0, pan = -0.65f, level = 0.88f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0f, detuneCents = 11, octShift = 0, pan = 0.65f, level = 0.88f),
            VoicePresetData(engine = 4, fmRatioIndex = 4, fmDepth = 0.35f, detuneCents = 16, octShift = 1, pan = 0f, level = 0.72f)
        )
    ),
    SynthPreset(
        id = "tr_metrobell", name = "METRO DARK BELL", category = "DARK TRAP",
        glideSpeed = 0.0100f, defaultEnv = 2, baseCutoff = 0.45f, filterEnvMod = 0.55f, pluckDecay = 0.99935f, vibratoDepth = 0.0015f,
        presetChorus = false, presetDelay = true, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 2, fmRatioIndex = 0, fmDepth = 0.30f, detuneCents = 0, octShift = -1, pan = 0f, level = 0.95f),
            VoicePresetData(engine = 4, fmRatioIndex = 3, fmDepth = 0.62f, detuneCents = -6, octShift = 0, pan = -0.55f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.58f, detuneCents = 6, octShift = 0, pan = 0.55f, level = 0.85f),
            VoicePresetData(engine = 4, fmRatioIndex = 5, fmDepth = 0.70f, detuneCents = 14, octShift = 1, pan = 0f, level = 0.65f)
        )
    ),
    SynthPreset(
        id = "tr_mikedean", name = "MIKE DEAN CS-80", category = "ASTROWORLD",
        glideSpeed = 0.0012f, defaultEnv = 0, baseCutoff = 0.34f, filterEnvMod = 0.50f, pluckDecay = 0.9995f, vibratoDepth = 0.0030f,
        presetChorus = true, presetDelay = true, presetCrush = false,
        voices = listOf(
            VoicePresetData(engine = 0, fmRatioIndex = 0, fmDepth = 0.15f, detuneCents = -4, octShift = -1, pan = 0f, level = 1.0f),
            VoicePresetData(engine = 0, fmRatioIndex = 1, fmDepth = 0.05f, detuneCents = -20, octShift = 0, pan = -0.90f, level = 0.88f),
            VoicePresetData(engine = 3, fmRatioIndex = 1, fmDepth = 0.05f, detuneCents = 20, octShift = 0, pan = 0.90f, level = 0.88f),
            VoicePresetData(engine = 0, fmRatioIndex = 2, fmDepth = 0.12f, detuneCents = 28, octShift = 1, pan = 0f, level = 0.78f)
        )
    )
)

// --- 4-STIMMIGE ECHTZEIT-STEREO-HYBRID-ENGINE MIT ECHTEM PLUCK & FILTER-ENVELOPE ---
class HiChordEngine {
    private val sampleRate = 44100
    @Volatile var isRunning = false
    private var audioThread: Thread? = null

    val targetFreqs = FloatArray(4) { 0f }
    private val currentFreqs = FloatArray(4) { 0f }
    private var arpSmoothFreq = 0f

    @Volatile var gate = false
    @Volatile var bpm = 120
    @Volatile var joystickX = 0f
    @Volatile var joystickY = 0f
    @Volatile var strumMode = false

    // Klangformungs-Parameter pro Preset
    @Volatile var glideSpeed = 0.0035f
    @Volatile var baseCutoff = 0.38f
    @Volatile var filterEnvMod = 0.40f
    @Volatile var pluckDecay = 0.99935f
    @Volatile var vibratoDepth = 0.0015f

    @Volatile var envMode = 0 // 0: ORG, 1: PAD, 2: PLK
    @Volatile var arpMode = 0

    @Volatile var fxDelay = false
    @Volatile var fxCrush = false
    @Volatile var fxStutter = false
    @Volatile var fxChorus = false
    @Volatile var fxPump = false

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
    private var modPhases = FloatArray(4)
    private var voiceEnvs = FloatArray(4) { 0f }
    private var subPhase = 0f
    private var lfoPhase = 0f

    private var env = 0f
    private var filterEnv = 0f

    // 2-stufiges Kaskaden-Filter für warmen, resonanzfähigen Klang ohne Übersteuern
    private var lp1L = 0f
    private var lp2L = 0f
    private var lp1R = 0f
    private var lp2R = 0f

    private val delayBufL = FloatArray(44100)
    private val delayBufR = FloatArray(44100)
    private var delayWritePos = 0

    private var crushCounter = 0
    private var crushedL = 0f
    private var crushedR = 0f

    fun setGlobalWaveform(waveIdx: Int) {
        val w = waveIdx.coerceIn(0, 4)
        for (v in 0..3) {
            voices[v].engine = w
        }
    }

    fun loadSynthPreset(preset: SynthPreset) {
        glideSpeed = preset.glideSpeed
        envMode = preset.defaultEnv
        baseCutoff = preset.baseCutoff
        filterEnvMod = preset.filterEnvMod
        pluckDecay = preset.pluckDecay
        vibratoDepth = preset.vibratoDepth
        fxChorus = preset.presetChorus
        fxDelay = preset.presetDelay
        fxCrush = preset.presetCrush
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
            VoicePresetData(
                engine = vc.engine,
                fmRatioIndex = vc.fmRatioIndex,
                fmDepth = vc.fmDepth,
                detuneCents = vc.detuneCents,
                octShift = vc.octShift,
                pan = vc.pan,
                level = vc.level
            )
        }
        return SynthPreset(
            id = id,
            name = name,
            category = "USER PATCH",
            glideSpeed = glideSpeed,
            defaultEnv = envMode,
            baseCutoff = baseCutoff,
            filterEnvMod = filterEnvMod,
            pluckDecay = pluckDecay,
            vibratoDepth = vibratoDepth,
            presetChorus = fxChorus,
            presetDelay = fxDelay,
            presetCrush = fxCrush,
            voices = vData,
            isUser = true
        )
    }

    fun triggerChordAttack() { noteTriggered = true }
    fun resetClockSync() { syncResetRequested = true }
    fun triggerStrumVoice(voiceIndex: Int) {
        if (voiceIndex in 0..3) {
            voiceEnvs[voiceIndex] = 1.0f
            filterEnv = 1.0f
        }
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

            while (isRunning) {
                val joyYVal = joystickY.coerceIn(-1f, 1f)
                val joyXVal = joystickX.coerceIn(-1f, 1f)
                val currentBpm = bpm.coerceIn(60, 200)
                val quarterSamples = ((sampleRate * 60) / currentBpm).coerceAtLeast(2000)
                val sixteenthSamples = (quarterSamples / 4).coerceAtLeast(500)
                val delaySamples = (sixteenthSamples * 3).coerceIn(1000, delayBufL.size - 1)
                val currentGlide = glideSpeed.coerceIn(0.0005f, 0.05f)
                val pDecay = pluckDecay.coerceIn(0.9988f, 0.9997f)

                // Joystick X erzeugt zusätzlich feines Pitch-Bend (+/- 1 Halbton), damit man ihn sofort hört!
                val joyPitchBend = if (!strumMode) 2f.pow((joyXVal * 0.65f) / 12f) else 1f

                if (syncResetRequested) {
                    syncResetRequested = false
                    sampleCounter = 0
                    beatSampleCounter = 0
                    arpStepCounter = 0
                }

                for (i in 0 until framesPerBlock) {
                    sampleCounter++
                    beatSampleCounter = (beatSampleCounter + 1) % quarterSamples

                    // Tape-Vibrato LFO (5.2 Hz)
                    lfoPhase = (lfoPhase + 5.2f / sampleRate) % 1f
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

                        // Im Arp-Modus triggert jeder 16tel-Schritt einen frischen Pluck-Impuls!
                        if (arpMode > 0 && gate) {
                            if (envMode == 2) env = 1.0f
                            filterEnv = 1.0f
                        }
                    }

                    // Neuer Akkord-Anschlag: Setzt Pluck- & Filter-Hüllkurve exakt einmalig auf 1.0f
                    if (noteTriggered) {
                        noteTriggered = false
                        filterEnv = 1.0f
                        if (envMode == 2) {
                            env = 1.0f
                        } else if (envMode == 0 && env < 0.2f) {
                            env = 0.25f
                        }
                    }

                    // Filter-Hüllkurve klingt sanft ab (sorgt für knackigen Pluck-Transient)
                    filterEnv = (filterEnv * 0.9991f).coerceAtLeast(0f)

                    // KORRIGIERTE LAUTSTÄRKE-HÜLLKURVE:
                    // PLUCK (Modus 2) fällt jetzt wirklich bis auf 0.0f (komplette Stille) ab!
                    env = when (envMode) {
                        1 -> { // PAD (Weiches Swell-Attack & langes Release)
                            if (gate) (env + 0.00032f).coerceAtMost(1f)
                            else (env * 0.99984f).coerceAtLeast(0f)
                        }
                        2 -> { // PLUCK (Knackiger Anschlag -> fällt komplett auf 0.0f ab, kein Endlos-Ton mehr!)
                            if (gate) (env * pDecay).coerceAtLeast(0f)
                            else (env * 0.9982f).coerceAtLeast(0f)
                        }
                        else -> { // ORGAN / LEAD (Sofort da, solange gehalten)
                            if (gate) (env + 0.0045f).coerceAtMost(1f)
                            else (env * 0.9990f).coerceAtLeast(0f)
                        }
                    }

                    var mixL = 0f
                    var mixR = 0f

                    if (env > 0.0005f) {
                        if (arpMode > 0) {
                            val vc = voices[arpVoice]
                            val octMult = 2f.pow(vc.octShift.toFloat())
                            val rawTarget = targetFreqs[arpVoice] * octMult * 2f.pow(vc.detuneCents / 1200f) * joyPitchBend * vibratoFactor
                            if (rawTarget > 20f) {
                                if (arpSmoothFreq <= 20f) arpSmoothFreq = rawTarget
                                arpSmoothFreq += (rawTarget - arpSmoothFreq) * (currentGlide * 1.8f)

                                val ratio = fmRatios[vc.fmRatioIndex.coerceIn(0, fmRatios.lastIndex)]
                                modPhases[0] = (modPhases[0] + (arpSmoothFreq * ratio) / sampleRate) % 1f
                                // Im Pluck-Modus moduliert die Hüllkurve auch den FM-Glockenanteil!
                                val dynFmDepth = vc.fmDepth * (0.4f + 0.6f * filterEnv)
                                val fmMod = sin(modPhases[0] * 2f * PI.toFloat()) * dynFmDepth * 0.9f

                                val detRatio = if (fxChorus) 1.012f else 1.005f
                                phases[0] = (phases[0] + arpSmoothFreq / sampleRate) % 1f
                                phasesDetune[0] = (phasesDetune[0] + (arpSmoothFreq * detRatio) / sampleRate) % 1f
                                val p = (phases[0] + fmMod + 1f) % 1f
                                val p2 = phasesDetune[0]

                                var osc = when (vc.engine) {
                                    0 -> (p * 2f) - 1f
                                    1 -> if (p < 0.5f) 0.85f else -0.85f
                                    2 -> sin(p * 2f * PI.toFloat())
                                    3 -> (((p * 2f) - 1f) + ((p2 * 2f) - 1f)) * 0.58f
                                    else -> sin((phases[0] * 2f * PI.toFloat()) + fmMod * 5.0f)
                                }
                                if (fxChorus) osc = (osc * 0.65f) + (((p2 * 2f) - 1f) * 0.45f)

                                val sig = osc * 0.34f * vc.level
                                val panNorm = (vc.pan + 1f) * 0.5f
                                mixL += sig * (1f - panNorm * 0.65f)
                                mixR += sig * (0.35f + panNorm * 0.65f)
                            }
                        } else {
                            for (v in 0 until 4) {
                                val vc = voices[v]
                                val octMult = 2f.pow(vc.octShift.toFloat())
                                val tFreq = targetFreqs[v] * octMult * 2f.pow(vc.detuneCents / 1200f) * joyPitchBend * vibratoFactor
                                if (tFreq <= 20f) continue

                                if (currentFreqs[v] <= 20f) currentFreqs[v] = tFreq
                                currentFreqs[v] += (tFreq - currentFreqs[v]) * currentGlide
                                val freq = currentFreqs[v]

                                val vEnv = if (strumMode) {
                                    voiceEnvs[v] = (voiceEnvs[v] * 0.99935f).coerceAtLeast(0f)
                                    voiceEnvs[v]
                                } else 1f

                                val ratio = fmRatios[vc.fmRatioIndex.coerceIn(0, fmRatios.lastIndex)]
                                modPhases[v] = (modPhases[v] + (freq * ratio) / sampleRate) % 1f
                                val dynFmDepth = vc.fmDepth * (0.45f + 0.55f * filterEnv)
                                val fmMod = sin(modPhases[v] * 2f * PI.toFloat()) * dynFmDepth * 0.9f

                                val detuneRatio = if (fxChorus) 1.012f else 1.005f
                                phases[v] = (phases[v] + freq / sampleRate) % 1f
                                phasesDetune[v] = (phasesDetune[v] + (freq * detuneRatio) / sampleRate) % 1f
                                val p = (phases[v] + fmMod + 1f) % 1f
                                val p2 = phasesDetune[v]

                                var osc = when (vc.engine) {
                                    0 -> (p * 2f) - 1f
                                    1 -> if (p < 0.5f) 0.85f else -0.85f
                                    2 -> sin(p * 2f * PI.toFloat())
                                    3 -> (((p * 2f) - 1f) + ((p2 * 2f) - 1f)) * 0.58f
                                    else -> sin((phases[v] * 2f * PI.toFloat()) + fmMod * 5.0f)
                                }
                                if (fxChorus) osc = (osc * 0.65f) + (((p2 * 2f) - 1f) * 0.5f)

                                val voiceSig = osc * 0.22f * vc.level * vEnv
                                val panNorm = ((vc.pan + 1f) * 0.5f).coerceIn(0f, 1f)
                                mixL += voiceSig * sqrt(1f - panNorm)
                                mixR += voiceSig * sqrt(panNorm)
                            }
                        }

                        if (fxChorus && targetFreqs[0] > 20f) {
                            subPhase = (subPhase + (targetFreqs[0] * 0.5f) / sampleRate) % 1f
                            val sub = sin(subPhase * 2f * PI.toFloat()) * 0.14f
                            mixL += sub; mixR += sub
                        }
                    }

                    if (fxStutter) {
                        val stutterPeriod = (sixteenthSamples / 2).coerceAtLeast(1)
                        if ((sampleCounter % stutterPeriod) > (stutterPeriod / 2)) { mixL = 0f; mixR = 0f }
                    }

                    // Dynamisches 2-Pol-Tiefpassfilter: Kombiniert Preset-Cutoff + Filter-Pluck-Envelope + Joystick Y!
                    val dynCutoff = (baseCutoff + (filterEnv * filterEnvMod) + (joyYVal * 0.48f)).coerceIn(0.025f, 0.96f)
                    val inL = mixL * env
                    val inR = mixR * env
                    lp1L += dynCutoff * (inL - lp1L)
                    lp2L += dynCutoff * (lp1L - lp2L)
                    lp1R += dynCutoff * (inR - lp1R)
                    lp2R += dynCutoff * (lp1R - lp2R)

                    var finalL = lp2L
                    var finalR = lp2R

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
                        finalL += delayBufR[readPos] * 0.44f
                        finalR += delayBufL[readPos] * 0.44f
                        delayBufL[delayWritePos] = (finalL * 0.65f).coerceIn(-1f, 1f)
                        delayBufR[delayWritePos] = (finalR * 0.65f).coerceIn(-1f, 1f)
                    } else {
                        delayBufL[delayWritePos] = (finalL * 0.22f).coerceIn(-1f, 1f)
                        delayBufR[delayWritePos] = (finalR * 0.22f).coerceIn(-1f, 1f)
                    }
                    delayWritePos = (delayWritePos + 1) % delayBufL.size

                    val outL = finalL.coerceIn(-1f, 1f)
                    val outR = finalR.coerceIn(-1f, 1f)
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

val rootNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
val majorScaleOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
val defaultQuality = intArrayOf(0, 1, 1, 0, 0, 1, 2)

fun midiToFreq(midi: Int): Float = 440f * 2f.pow((midi - 69) / 12f)

fun buildChord(
    keyIndex: Int,
    degree: Int,
    flipMajMin: Boolean,
    add7th: Boolean,
    sus4: Boolean,
    octaveShift: Int,
    joystickX: Float,
    strumMode: Boolean
): Pair<String, FloatArray> {
    val rootMidi = 60 + keyIndex + majorScaleOffsets[degree] + (octaveShift * 12)
    val noteName = rootNames[(keyIndex + majorScaleOffsets[degree]) % 12]

    var quality = defaultQuality[degree]
    if (flipMajMin) {
        quality = if (quality == 0) 1 else 0
    }

    val third = when {
        sus4 -> 5
        quality == 0 -> 4
        else -> 3
    }
    val fifth = if (quality == 2 && !flipMajMin) 6 else 7
    val seventh = when {
        !add7th -> 12
        quality == 0 -> 11
        else -> 10
    }

    val intervals = intArrayOf(0, third, fifth, seventh)

    // Joystick X schaltet Akkord-Umkehrungen (Inversionen) und Sub-Bass-Voicing
    if (!strumMode) {
        if (joystickX < -0.30f) {
            intervals[0] -= 12
            if (joystickX < -0.65f) intervals[1] -= 12
        } else if (joystickX > 0.30f) {
            intervals[0] += 12
            if (joystickX > 0.65f) intervals[1] += 12
        }
    }

    val freqs = FloatArray(4) { i -> midiToFreq(rootMidi + intervals[i]) }

    val suffix = buildString {
        if (sus4) append("SUS4")
        else if (quality == 1) append("m")
        else if (quality == 2) append("DIM")
        if (add7th) append(if (quality == 0 && !sus4) "MAJ7" else "7")
    }

    return "$noteName$suffix" to freqs
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

    var modFlip by remember { mutableStateOf(false) }
    var mod7th by remember { mutableStateOf(false) }
    var modSus4 by remember { mutableStateOf(false) }
    var octaveShift by remember { mutableStateOf(0) }
    var holdLatch by remember { mutableStateOf(false) }
    var strumMode by remember { mutableStateOf(false) }

    var waveIndex by remember { mutableStateOf(0) }
    var envMode by remember { mutableStateOf(0) }
    var arpMode by remember { mutableStateOf(0) }
    var activeSlot by remember { mutableStateOf(0) }
    var uiRefreshCounter by remember { mutableStateOf(0) }

    val userPresets = remember {
        val list = mutableStateListOf<SynthPreset>()
        val rawSet = prefs.getStringSet("user_presets_v3", emptySet()) ?: emptySet()
        rawSet.mapNotNull { SynthPreset.deserialize(it) }
            .sortedBy { it.name }
            .forEach { list.add(it) }
        list
    }

    // 4 Schnellwahl-Slots: 2 HiChord Classics + 2 Trap/Cloudrap Favoriten
    val slotPresets = remember {
        mutableStateListOf(
            factoryPresets[0],  // WARM PAD (HiChord Classic)
            factoryPresets[5],  // LOFI RHODES (HiChord Classic)
            factoryPresets[8],  // KEN CARSON RAGE (Opium)
            factoryPresets[10]  // YUNG LEAN 2002 (Cloudrap)
        )
    }

    LaunchedEffect(Unit) {
        val initial = slotPresets[0]
        synth.loadSynthPreset(initial)
        envMode = initial.defaultEnv
        waveIndex = initial.voices[0].engine
        uiRefreshCounter++
    }

    fun saveUserPresetsToDisk() {
        val serialized = userPresets.map { it.serialize() }.toSet()
        prefs.edit().putStringSet("user_presets_v3", serialized).apply()
    }

    var joyX by remember { mutableStateOf(0f) }
    var joyY by remember { mutableStateOf(0f) }

    LaunchedEffect(activeDegree, keyIndex, modFlip, mod7th, modSus4, octaveShift, joyX, strumMode) {
        if (activeDegree in 0..6) {
            val (name, freqs) = buildChord(keyIndex, activeDegree, modFlip, mod7th, modSus4, octaveShift, joyX, strumMode)
            displayChord = name
            for (i in 0..3) synth.targetFreqs[i] = freqs[i]
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
                envMode = p.defaultEnv
                waveIndex = p.voices[0].engine
                uiRefreshCounter++
            },
            onAssignPresetToSlot = { preset, slot ->
                slotPresets[slot] = preset
                activeSlot = slot
                synth.loadSynthPreset(preset)
                envMode = preset.defaultEnv
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
                            envMode = factoryPresets[0].defaultEnv
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
            onCycleSlot = {
                val next = (activeSlot + 1) % 4
                activeSlot = next
                val p = slotPresets[next]
                synth.loadSynthPreset(p)
                envMode = p.defaultEnv
                waveIndex = p.voices[0].engine
                uiRefreshCounter++
            },
            modFlip = modFlip,
            onToggleFlip = { modFlip = !modFlip },
            mod7th = mod7th,
            onToggle7th = { mod7th = !mod7th },
            modSus4 = modSus4,
            onToggleSus4 = { modSus4 = !modSus4 },
            octaveShift = octaveShift,
            onOctaveChange = { octaveShift = it },
            holdLatch = holdLatch,
            onToggleHold = { holdLatch = !holdLatch; if (!holdLatch) activeDegree = -1 },
            strumMode = strumMode,
            onToggleStrum = { strumMode = !strumMode; synth.strumMode = strumMode },
            waveIndex = waveIndex,
            onWaveChange = { waveIndex = it; synth.setGlobalWaveform(it) },
            envMode = envMode,
            onEnvChange = { envMode = it; synth.envMode = it },
            arpMode = arpMode,
            onArpChange = { arpMode = it; synth.arpMode = it },
            joyX = joyX,
            joyY = joyY,
            onJoyChange = { x, y ->
                joyX = x
                joyY = y
                synth.joystickX = x
                synth.joystickY = y
            },
            uiRefreshCounter = uiRefreshCounter,
            frameTick = frameTick
        )
    }
}

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
        // 1. TOP BAR: MINI OLED + 4 PRESET SLOTS + LIBRARY & SAVE
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
                            text = if (showBrowser) "PRESET BROWSER" else "EXPERT // 4-VOICE LAB",
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
                sub = "NEW USER",
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

        // 2. MITTE: PRESET-BROWSER (MIT DELETE FÜR USER-PRESETS) ODER 4-VOICE EDITOR
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
                        text = "TAP ANY PRESET TO LOAD INTO SLOT 0${activeSlot + 1} // HICHORD CLASSICS + RAGE & CLOUDRAP",
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
                                        preset.category == "HICHORD" -> Color(0xFF2F2D27)
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
                                // Oktav-Schalter pro Stimme (-1, 0, +1)
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

        // 3. BOTTOM AUDITION STRIP (MOMENTARY & SLIDE)
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
                                if (strumMode) for (v in 0..3) synth.triggerStrumVoice(v)
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
                                        if (strumMode) for (v in 0..3) synth.triggerStrumVoice(v)
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

@Composable
fun TePortraitJamScreen(
    synth: HiChordEngine, keyIndex: Int, onKeyChange: (Int) -> Unit, bpm: Int, onBpmChange: (Int) -> Unit,
    activeDegree: Int, onDegreeChange: (Int) -> Unit, displayChord: String, activePresetName: String, onCycleSlot: () -> Unit,
    modFlip: Boolean, onToggleFlip: () -> Unit, mod7th: Boolean, onToggle7th: () -> Unit, modSus4: Boolean, onToggleSus4: () -> Unit,
    octaveShift: Int, onOctaveChange: (Int) -> Unit, holdLatch: Boolean, onToggleHold: () -> Unit, strumMode: Boolean, onToggleStrum: () -> Unit,
    waveIndex: Int, onWaveChange: (Int) -> Unit, envMode: Int, onEnvChange: (Int) -> Unit,
    arpMode: Int, onArpChange: (Int) -> Unit, joyX: Float, joyY: Float, onJoyChange: (Float, Float) -> Unit,
    uiRefreshCounter: Int, frameTick: Int
) {
    val view = LocalView.current
    val tapTimes = remember { mutableStateListOf<Long>() }

    var fxDelay by remember(uiRefreshCounter) { mutableStateOf(synth.fxDelay) }
    var fxCrush by remember(uiRefreshCounter) { mutableStateOf(synth.fxCrush) }
    var fxStutter by remember(uiRefreshCounter) { mutableStateOf(synth.fxStutter) }
    var fxChorus by remember(uiRefreshCounter) { mutableStateOf(synth.fxChorus) }
    var fxPump by remember(uiRefreshCounter) { mutableStateOf(synth.fxPump) }
    var lastStrumZone by remember { mutableStateOf(-1) }

    // Wichtig für den Joystick-Fix: Aktuelle Callback-Referenz halten
    val currentOnJoyChange by rememberUpdatedState(onJoyChange)
    val currentStrumMode by rememberUpdatedState(strumMode)

    val envNames = listOf("ORG", "PAD", "PLK")
    val arpNames = listOf("ARP", "UP", "DOWN", "U/D", "RAND")
    val romanDegrees = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
    val degreeSubLabels = listOf("TONIC", "SUPER", "MEDI", "SUBD", "DOM", "SUBM", "LEAD")

    Column(modifier = Modifier.fillMaxSize().background(TeChassis).padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScrewHead()
                Text(text = "TE // HC-01 COMPANION (ROTATE 90° FOR LAB)", color = TePanelDark, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpeakerGrille()
                ScrewHead()
            }
        }

        Row(modifier = Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1.35f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(TePanelDark).border(2.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp)).padding(10.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cols = 16; val rows = 8
                    val stepX = size.width / cols; val stepY = size.height / rows
                    for (c in 1 until cols) for (r in 1 until rows) drawCircle(Color(0xFF2C2C2A), 1.5f, Offset(c * stepX, r * stepY))
                }
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        DisplayBadge(text = "KEY ${rootNames[keyIndex]}", bgColor = TeOrange, textColor = Color.White)
                        DisplayBadge(text = "$bpm BPM", bgColor = Color(0xFF333333), textColor = Color(0xFF00E5FF))
                        // Klickbares Preset-Badge im Display schaltet direkt durch die 4 Slots!
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF333333))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onCycleSlot()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = activePresetName.take(11), color = TeOchre, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text(text = when { activeDegree >= 0 && holdLatch -> "DEGREE ${romanDegrees[activeDegree]} [HOLD]"; activeDegree >= 0 -> "DEGREE ${romanDegrees[activeDegree]}"; else -> "TAP PATCH NAME OR ROTATE 90°" }, color = if (holdLatch) TeOchre else Color(0xFF888884), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            Text(text = displayChord, color = Color(0xFFF4F3EF), fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                        val activeFxText = buildString {
                            if (fxPump) append("PMP "); if (fxDelay) append("DLY "); if (fxCrush) append("BIT "); if (fxStutter) append("STU "); if (fxChorus) append("CHR ")
                            if (isEmpty()) { if (strumMode) append("STRUM") else when { joyX < -0.30f -> append("BASS"); joyX > 0.30f -> append("INV"); else -> append("${engineNames[waveIndex]}·${envNames[envMode]}") } }
                        }
                        Text(text = activeFxText.trim(), color = if (fxPump || fxDelay || fxCrush || fxStutter || fxChorus) Color(0xFF00E5FF) else TeOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Canvas(modifier = Modifier.fillMaxWidth().height(30.dp)) {
                        val tick = frameTick
                        val path = Path()
                        val stepX = size.width / synth.scopeBuffer.size
                        val midY = size.height / 2f
                        drawLine(Color(0xFF333333), Offset(0f, midY), Offset(size.width, midY), 1f)
                        for (i in synth.scopeBuffer.indices) {
                            val x = i * stepX; val y = midY - (synth.scopeBuffer[i] * midY * 0.95f)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color = if (synth.gate) TeOrange else Color(0xFF666662), style = Stroke(width = 3.5f, cap = StrokeCap.Round))
                    }
                }
            }

            // GEFIXTER MOD-JOYSTICK: Lokale Drag-Akkumulatoren verhindern das Einfrieren bei 0f!
            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFC5C3BC))
                    .border(2.dp, Color(0xFFABA9A2), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        var localX = 0f
                        var localY = 0f
                        detectDragGestures(
                            onDragStart = {
                                localX = 0f
                                localY = 0f
                            },
                            onDragEnd = {
                                localX = 0f
                                localY = 0f
                                currentOnJoyChange(0f, 0f)
                                lastStrumZone = -1
                            },
                            onDragCancel = {
                                localX = 0f
                                localY = 0f
                                currentOnJoyChange(0f, 0f)
                                lastStrumZone = -1
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val maxRadius = (min(size.width, size.height) / 2.3f).coerceAtLeast(1f)
                            localX = (localX + dragAmount.x / maxRadius).coerceIn(-1f, 1f)
                            localY = (localY - dragAmount.y / maxRadius).coerceIn(-1f, 1f)
                            currentOnJoyChange(localX, localY)

                            if (currentStrumMode) {
                                val zone = ((localX + 1f) * 2f).toInt().coerceIn(0, 3)
                                if (zone != lastStrumZone) {
                                    lastStrumZone = zone
                                    synth.triggerStrumVoice(zone)
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                        }
                    }
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxR = min(size.width, size.height) / 2.6f
                    drawCircle(Color(0xFF9E9C95), maxR, style = Stroke(2f))
                    drawCircle(Color(0xFF9E9C95), maxR * 0.5f, style = Stroke(1f))
                    drawLine(Color(0xFF9E9C95), Offset(center.x - maxR, center.y), Offset(center.x + maxR, center.y), 1.5f)
                    drawLine(Color(0xFF9E9C95), Offset(center.x, center.y - maxR), Offset(center.x, center.y + maxR), 1.5f)

                    if (strumMode) {
                        for (s in -1..2) {
                            val sx = center.x + (s + 0.5f) * (maxR / 2f)
                            drawLine(TeOrange.copy(alpha = 0.45f), Offset(sx, center.y - maxR), Offset(sx, center.y + maxR), 2f)
                        }
                    }
                    val knobPos = Offset(center.x + joyX * maxR, center.y - joyY * maxR)
                    drawLine(TePanelDark, center, knobPos, strokeWidth = 6f, cap = StrokeCap.Round)
                    drawCircle(if (strumMode) TeBlue else TeOrange, 16.dp.toPx(), knobPos)
                    drawCircle(Color.White, 4.dp.toPx(), knobPos)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (strumMode) TeBlue else TePanelDark)
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onToggleStrum()
                            if (!strumMode) for (v in 0..3) synth.triggerStrumVoice(v)
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (strumMode) "Y:LPF X:STRUM" else "Y:LPF X:INV+BEND",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        SectionDivider("SYSTEM // 1-BPM, TAP-SYNC, WAVE & ENVELOPE")
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
            TeTactileButton(title = envNames[envMode], sub = "ENV", modifier = Modifier.weight(0.9f), height = 42.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = envMode > 0, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onEnvChange((envMode + 1) % envNames.size); synth.triggerChordAttack() })
            TeTactileButton(title = arpNames[arpMode], sub = "ARP", modifier = Modifier.weight(0.95f), height = 42.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = arpMode > 0, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onArpChange((arpMode + 1) % arpNames.size) })
        }

        SectionDivider("PERFORMANCE FX // HOLD FX OR LATCH PUMP")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TeMomentaryFxButton(title = "DELAY", sub = "ECHO", isPressed = fxDelay, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxDelay = true; synth.fxDelay = true }, onRelease = { fxDelay = false; synth.fxDelay = false })
            TeMomentaryFxButton(title = "CRUSH", sub = "8-BIT", isPressed = fxCrush, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxCrush = true; synth.fxCrush = true }, onRelease = { fxCrush = false; synth.fxCrush = false })
            TeMomentaryFxButton(title = "STUTTR", sub = "1/32", isPressed = fxStutter, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxStutter = true; synth.fxStutter = true }, onRelease = { fxStutter = false; synth.fxStutter = false })
            TeMomentaryFxButton(title = "CHORUS", sub = "WIDE", isPressed = fxChorus, modifier = Modifier.weight(1f), onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxChorus = true; synth.fxChorus = true }, onRelease = { fxChorus = false; synth.fxChorus = false })
            TeTactileButton(title = "PUMP", sub = "DUCK", modifier = Modifier.weight(0.95f), height = 44.dp, bgColor = if (fxPump) TeOrange else TeBlue, shadowColor = if (fxPump) TeOrangeShadow else TeBlueShadow, textColor = Color.White, ledOn = fxPump, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); fxPump = !fxPump; synth.fxPump = fxPump })
        }

        SectionDivider("HARMONIC MODIFIERS // LATCH & HOLD")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TeTactileButton(title = "HOLD", sub = "DRONE", modifier = Modifier.weight(0.95f), height = 46.dp, bgColor = if (holdLatch) TeBlue else TeKeyDark, shadowColor = if (holdLatch) TeBlueShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = holdLatch, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleHold() })
            TeTactileButton(title = "MAJ/MIN", sub = "FLIP", modifier = Modifier.weight(1.05f), height = 46.dp, bgColor = if (modFlip) TeOrange else TeKeyDark, shadowColor = if (modFlip) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = modFlip, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleFlip() })
            TeTactileButton(title = "7TH", sub = "JAZZ", modifier = Modifier.weight(0.9f), height = 46.dp, bgColor = if (mod7th) TeOrange else TeKeyDark, shadowColor = if (mod7th) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = mod7th, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggle7th() })
            TeTactileButton(title = "SUS4", sub = "TENS", modifier = Modifier.weight(0.9f), height = 46.dp, bgColor = if (modSus4) TeOrange else TeKeyDark, shadowColor = if (modSus4) TeOrangeShadow else TeKeyDarkShadow, textColor = Color.White, ledOn = modSus4, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggleSus4() })
            TeTactileButton(title = "OCT -", sub = "DOWN", modifier = Modifier.weight(0.8f), height = 46.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = octaveShift < 0, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onOctaveChange(if (octaveShift == -1) 0 else -1) })
            TeTactileButton(title = "OCT +", sub = "UP", modifier = Modifier.weight(0.8f), height = 46.dp, bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = octaveShift > 0, onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onOctaveChange(if (octaveShift == 1) 0 else 1) })
        }

        SectionDivider("DIATONIC CHORD TRIGGERS // SLIDE OR TAP (I - VII)")
        var padGridSize by remember { mutableStateOf(IntSize.Zero) }

        Column(
            modifier = Modifier.fillMaxWidth().onSizeChanged { padGridSize = it }.pointerInput(holdLatch, strumMode) {
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
                        if (strumMode) for (v in 0..3) synth.triggerStrumVoice(v)
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
                                if (strumMode) for (v in 0..3) synth.triggerStrumVoice(v)
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!holdLatch) onDegreeChange(-1)
                }
            },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (deg in 0..3) {
                    val noteLabel = rootNames[(keyIndex + majorScaleOffsets[deg]) % 12]; val qualSuffix = if (defaultQuality[deg] == 1) "m" else ""
                    TeChordKeycap(number = "0${deg + 1}", roman = romanDegrees[deg], subLabel = degreeSubLabels[deg], note = "$noteLabel$qualSuffix", isPressed = activeDegree == deg, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
fun SpeakerGrille() {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(6) { Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(Color(0xFF706E68))) } }
}

@Composable
fun TeMomentaryFxButton(title: String, sub: String, isPressed: Boolean, modifier: Modifier = Modifier, onPress: () -> Unit, onRelease: () -> Unit) {
    Box(modifier = modifier.height(44.dp).padding(top = if (isPressed) 2.dp else 0.dp).clip(RoundedCornerShape(7.dp)).background(if (isPressed) TeOrangeShadow else TeBlueShadow)
            .pointerInput(Unit) { detectTapGestures(onPress = { onPress(); tryAwaitRelease(); onRelease() }) }.padding(bottom = if (isPressed) 1.dp else 3.dp)) {
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(if (isPressed) TeOrange else TeBlue).padding(horizontal = 5.dp, vertical = 4.dp)) {
            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(if (isPressed) Color.White else Color(0xFF093163)).align(Alignment.TopEnd))
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(text = sub, color = Color.White.copy(alpha = 0.75f), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                Text(text = title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
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
fun TeChordKeycap(number: String, roman: String, subLabel: String, note: String, isPressed: Boolean, modifier: Modifier) {
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
