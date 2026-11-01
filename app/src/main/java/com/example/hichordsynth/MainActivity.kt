package com.example.hichordsynth

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
        synth.start()
        setContent {
            MaterialTheme {
                TeHiChordScreen(synth)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        synth.stop()
    }
}

// --- REINE 4-STIMMIGE SYNTH-ENGINE (100% HEADROOM FÜR EP-133 / PO-33 JAMS) ---
class HiChordEngine {
    private val sampleRate = 44100
    @Volatile var isRunning = false
    @Volatile var activeFreqs = FloatArray(4) { 0f }
    @Volatile var gate = false
    @Volatile var waveform = 0
    @Volatile var bpm = 120
    @Volatile var joystickX = 0f
    @Volatile var joystickY = 0f
    @Volatile var strumMode = false

    // 0: ORGAN (Instant), 1: PAD (Slow Swell), 2: PLUCK (Percussive Decay)
    @Volatile var envMode = 0
    // 0: OFF, 1: UP, 2: DOWN, 3: UP/DOWN, 4: RANDOM
    @Volatile var arpMode = 0

    // Performance & Jam FX
    @Volatile var fxDelay = false
    @Volatile var fxCrush = false
    @Volatile var fxStutter = false
    @Volatile var fxChorus = false
    @Volatile var fxPump = false

    @Volatile private var syncResetRequested = false
    @Volatile private var noteTriggered = false

    val scopeBuffer = FloatArray(128)
    private var phases = FloatArray(4)
    private var phasesDetune = FloatArray(4)
    private var voiceEnvs = FloatArray(4) { 1f }
    private var subPhase = 0f
    private var env = 0f
    private var lpState = 0f

    // Delay Ringbuffer (max 1 Sekunde)
    private val delayBuffer = FloatArray(44100)
    private var delayWritePos = 0

    // Bitcrusher Sample-Hold
    private var crushCounter = 0
    private var crushedSample = 0f

    fun triggerChordAttack() {
        noteTriggered = true
    }

    fun resetClockSync() {
        syncResetRequested = true
    }

    fun triggerStrumVoice(voiceIndex: Int) {
        if (voiceIndex in 0..3) {
            voiceEnvs[voiceIndex] = 1.0f
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(start = true, isDaemon = true, name = "HiChordAudioThread") {
            val bufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val buffer = ShortArray(256)
            track.play()

            var sampleCounter = 0
            var beatSampleCounter = 0
            var arpStepCounter = 0
            var arpVoice = 0
            val pingPongOrder = intArrayOf(0, 1, 2, 3, 2, 1)

            while (isRunning) {
                val cutoff = (0.20f + (joystickY + 1f) * 0.38f).coerceIn(0.03f, 0.96f)
                val currentBpm = bpm.coerceIn(60, 200)
                val quarterSamples = ((sampleRate * 60) / currentBpm).coerceAtLeast(2000)
                val sixteenthSamples = (quarterSamples / 4).coerceAtLeast(500)
                val delaySamples = (sixteenthSamples * 3).coerceIn(1000, delayBuffer.size - 1)

                if (syncResetRequested) {
                    syncResetRequested = false
                    sampleCounter = 0
                    beatSampleCounter = 0
                    arpStepCounter = 0
                }

                for (i in buffer.indices) {
                    sampleCounter++
                    beatSampleCounter = (beatSampleCounter + 1) % quarterSamples

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

                        // Im Pluck-Modus triggert jeder Arp-Step die Hüllkurve neu
                        if (arpMode > 0 && gate && envMode == 2) {
                            env = 1f
                        }
                    }

                    if (noteTriggered) {
                        noteTriggered = false
                        if (envMode == 2) {
                            env = 1f // Sofortiger Attack-Peak für Pluck
                        }
                    }

                    // Hüllkurven-Charakter: 0 = ORGAN, 1 = PAD, 2 = PLUCK
                    env = when (envMode) {
                        1 -> { // PAD (Weiches Ein- und Ausblenden)
                            if (gate) (env + 0.00035f).coerceAtMost(1f)
                            else (env * 0.99982f).coerceAtLeast(0f)
                        }
                        2 -> { // PLUCK (Kurzes perkussives Decay)
                            if (gate) (env * 0.99945f).coerceAtLeast(0.08f)
                            else (env * 0.9988f).coerceAtLeast(0f)
                        }
                        else -> { // ORGAN (Direkt & voll)
                            if (gate) (env + 0.004f).coerceAtMost(1f)
                            else (env * 0.9992f).coerceAtLeast(0f)
                        }
                    }

                    var chordMix = 0f
                    if (env > 0.001f) {
                        for (v in 0 until 4) {
                            if (arpMode > 0 && gate && v != arpVoice) continue
                            val freq = activeFreqs[v]
                            if (freq <= 20f) continue

                            val vEnv = if (strumMode && arpMode == 0) {
                                voiceEnvs[v] = (voiceEnvs[v] * 0.99965f).coerceAtLeast(0.04f)
                                voiceEnvs[v]
                            } else {
                                1f
                            }

                            val detuneRatio = if (fxChorus) 1.012f else 1.005f
                            phases[v] = (phases[v] + freq / sampleRate) % 1f
                            phasesDetune[v] = (phasesDetune[v] + (freq * detuneRatio) / sampleRate) % 1f
                            val p = phases[v]
                            val p2 = phasesDetune[v]

                            var osc = when (waveform) {
                                0 -> (p * 2f) - 1f
                                1 -> if (p < 0.5f) 0.8f else -0.8f
                                2 -> sin(p * 2f * PI.toFloat())
                                else -> (((p * 2f) - 1f) + ((p2 * 2f) - 1f)) * 0.55f
                            }

                            if (fxChorus) {
                                val chorusOsc = (p2 * 2f) - 1f
                                osc = (osc * 0.65f) + (chorusOsc * 0.5f)
                            }
                            chordMix += osc * 0.21f * vEnv
                        }

                        if (fxChorus && activeFreqs[0] > 20f) {
                            subPhase = (subPhase + (activeFreqs[0] * 0.5f) / sampleRate) % 1f
                            chordMix += sin(subPhase * 2f * PI.toFloat()) * 0.16f
                        }
                    }

                    // Punch-In Stutter (32tel-Gate-Chopper)
                    if (fxStutter) {
                        val stutterPeriod = (sixteenthSamples / 2).coerceAtLeast(1)
                        if ((sampleCounter % stutterPeriod) > (stutterPeriod / 2)) {
                            chordMix = 0f
                        }
                    }

                    // Sauberer 1-Pol-Tiefpassfilter ohne Übersteuerung
                    lpState += cutoff * (chordMix * env - lpState)
                    var finalSample = lpState

                    // 4/4 Sidechain-Pump (duckt den Synth auf jeder Viertelnote für externe PO-33 / EP-133 Kickdrums)
                    if (fxPump) {
                        val beatPhase = beatSampleCounter.toFloat() / quarterSamples.toFloat()
                        val pumpGain = (0.12f + 0.88f * beatPhase.pow(0.65f)).coerceIn(0.12f, 1f)
                        finalSample *= pumpGain
                    }

                    // Punch-In Bitcrusher
                    if (fxCrush) {
                        crushCounter++
                        if (crushCounter >= 9) {
                            crushCounter = 0
                            crushedSample = round(finalSample * 6f) / 6f
                        }
                        finalSample = crushedSample
                    }

                    // Punch-In Tape Delay
                    val readPos = (delayWritePos - delaySamples + delayBuffer.size) % delayBuffer.size
                    val delayed = delayBuffer[readPos]
                    if (fxDelay) {
                        finalSample += delayed * 0.46f
                        delayBuffer[delayWritePos] = (finalSample * 0.68f).coerceIn(-1f, 1f)
                    } else {
                        delayBuffer[delayWritePos] = (finalSample * 0.25f).coerceIn(-1f, 1f)
                    }
                    delayWritePos = (delayWritePos + 1) % delayBuffer.size

                    val out = finalSample.coerceIn(-1f, 1f)
                    if (i < scopeBuffer.size) scopeBuffer[i] = out
                    buffer[i] = (out * 30500f).toInt().toShort()
                }
                track.write(buffer, 0, buffer.size)
            }
            track.stop()
            track.release()
        }
    }

    fun stop() {
        isRunning = false
    }
}

// --- MUSIKTHEORIE & VOICING ---
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

    if (!strumMode) {
        if (joystickX < -0.35f) {
            intervals[0] -= 12
        } else if (joystickX > 0.35f) {
            intervals[0] += 12
            if (joystickX > 0.7f) intervals[1] += 12
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

// --- TEENAGE ENGINEERING UI SCREEN ---
@Composable
fun TeHiChordScreen(synth: HiChordEngine) {
    val view = LocalView.current

    var keyIndex by remember { mutableIntStateOf(0) }
    var bpm by remember { mutableIntStateOf(120) }
    var activeDegree by remember { mutableIntStateOf(-1) }
    var displayChord by remember { mutableStateOf("HI-CHORD") }

    var modFlip by remember { mutableStateOf(false) }
    var mod7th by remember { mutableStateOf(false) }
    var modSus4 by remember { mutableStateOf(false) }
    var octaveShift by remember { mutableIntStateOf(0) }
    var holdLatch by remember { mutableStateOf(false) }
    var strumMode by remember { mutableStateOf(false) }

    var waveIndex by remember { mutableIntStateOf(0) }
    var envMode by remember { mutableIntStateOf(0) }
    var arpMode by remember { mutableIntStateOf(0) }

    // Tap-Tempo Speicher
    val tapTimes = remember { mutableStateListOf<Long>() }

    // Performance FX States
    var fxDelay by remember { mutableStateOf(false) }
    var fxCrush by remember { mutableStateOf(false) }
    var fxStutter by remember { mutableStateOf(false) }
    var fxChorus by remember { mutableStateOf(false) }
    var fxPump by remember { mutableStateOf(false) }

    var joyX by remember { mutableFloatStateOf(0f) }
    var joyY by remember { mutableFloatStateOf(0f) }
    var lastStrumZone by remember { mutableIntStateOf(-1) }

    val waveNames = listOf("SAW", "PULS", "SINE", "SSAW")
    val envNames = listOf("ORG", "PAD", "PLK")
    val arpNames = listOf("ARP", "UP", "DOWN", "U/D", "RAND")
    val romanDegrees = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
    val degreeSubLabels = listOf("TONIC", "SUPER", "MEDI", "SUBD", "DOM", "SUBM", "LEAD")

    LaunchedEffect(activeDegree, keyIndex, modFlip, mod7th, modSus4, octaveShift, joyX, strumMode) {
        if (activeDegree in 0..6) {
            val (name, freqs) = buildChord(keyIndex, activeDegree, modFlip, mod7th, modSus4, octaveShift, joyX, strumMode)
            displayChord = name
            synth.activeFreqs = freqs
            synth.gate = true
        } else {
            synth.gate = false
        }
    }

    var frameTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30)
            frameTick++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TeChassis)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. HARDWARE HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScrewHead()
                Text(
                    text = "TE // HC-01 COMPANION SYNTH",
                    color = TePanelDark,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpeakerGrille()
                ScrewHead()
            }
        }

        // 2. DISPLAY-SEKTION & X/Y-VEKTOR-JOYSTICK
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // TE Vektor-OLED Display
            Box(
                modifier = Modifier
                    .weight(1.35f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TePanelDark)
                    .border(2.dp, Color(0xFF3A3A38), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cols = 16
                    val rows = 8
                    val stepX = size.width / cols
                    val stepY = size.height / rows
                    for (c in 1 until cols) {
                        for (r in 1 until rows) {
                            drawCircle(
                                color = Color(0xFF2C2C2A),
                                radius = 1.5f,
                                center = Offset(c * stepX, r * stepY)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DisplayBadge(text = "KEY ${rootNames[keyIndex]}", bgColor = TeOrange, textColor = Color.White)
                        DisplayBadge(text = "$bpm BPM", bgColor = Color(0xFF333333), textColor = Color(0xFF00E5FF))
                        DisplayBadge(text = "${waveNames[waveIndex]}·${envNames[envMode]}", bgColor = Color(0xFF333333), textColor = TeOchre)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = when {
                                    activeDegree >= 0 && holdLatch -> "DEGREE ${romanDegrees[activeDegree]} [HOLD]"
                                    activeDegree >= 0 -> "DEGREE ${romanDegrees[activeDegree]}"
                                    else -> "STANDBY // SYNC READY"
                                },
                                color = if (holdLatch) TeOchre else Color(0xFF888884),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = displayChord,
                                color = Color(0xFFF4F3EF),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        val activeFxText = buildString {
                            if (fxPump) append("PMP ")
                            if (fxDelay) append("DLY ")
                            if (fxCrush) append("BIT ")
                            if (fxStutter) append("STU ")
                            if (fxChorus) append("CHR ")
                            if (isEmpty()) {
                                if (strumMode) {
                                    append("STRUM")
                                } else {
                                    when {
                                        joyX < -0.35f -> append("BASS")
                                        joyX > 0.35f -> append("INV")
                                        else -> append("OCT:${if (octaveShift >= 0) "+$octaveShift" else octaveShift}")
                                    }
                                }
                            }
                        }
                        Text(
                            text = activeFxText.trim(),
                            color = if (fxPump || fxDelay || fxCrush || fxStutter || fxChorus) Color(0xFF00E5FF) else TeOrange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Canvas(modifier = Modifier.fillMaxWidth().height(30.dp)) {
                        val tick = frameTick
                        val path = Path()
                        val stepX = size.width / synth.scopeBuffer.size
                        val midY = size.height / 2f
                        drawLine(Color(0xFF333333), Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)
                        for (i in synth.scopeBuffer.indices) {
                            val x = i * stepX
                            val y = midY - (synth.scopeBuffer[i] * midY * 0.95f)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = if (synth.gate) TeOrange else Color(0xFF666662),
                            style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                        )
                    }
                }
            }

            // TE Fadenkreuz-Joystick Modul
            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFC5C3BC))
                    .border(2.dp, Color(0xFFABA9A2), RoundedCornerShape(8.dp))
                    .pointerInput(strumMode) {
                        detectDragGestures(
                            onDragEnd = {
                                joyX = 0f; joyY = 0f
                                synth.joystickX = 0f; synth.joystickY = 0f
                                lastStrumZone = -1
                            },
                            onDragCancel = {
                                joyX = 0f; joyY = 0f
                                synth.joystickX = 0f; synth.joystickY = 0f
                                lastStrumZone = -1
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val maxRadius = size.width / 2.4f
                            val newX = (joyX * maxRadius + dragAmount.x).coerceIn(-maxRadius, maxRadius) / maxRadius
                            val newY = (joyY * maxRadius - dragAmount.y).coerceIn(-maxRadius, maxRadius) / maxRadius
                            joyX = newX; joyY = newY
                            synth.joystickX = newX; synth.joystickY = newY

                            if (strumMode) {
                                val zone = ((newX + 1f) * 2f).toInt().coerceIn(0, 3)
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

                    drawCircle(Color(0xFF9E9C95), radius = maxR, style = Stroke(width = 2f))
                    drawCircle(Color(0xFF9E9C95), radius = maxR * 0.5f, style = Stroke(width = 1f))
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
                    drawCircle(if (strumMode) TeBlue else TeOrange, radius = 16.dp.toPx(), center = knobPos)
                    drawCircle(Color.White, radius = 4.dp.toPx(), center = knobPos)
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (strumMode) TeBlue else TePanelDark)
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            strumMode = !strumMode
                            synth.strumMode = strumMode
                            if (strumMode) {
                                for (v in 0..3) synth.triggerStrumVoice(v)
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (strumMode) "Y:LPF X:STRUM" else "Y:LPF X:INV",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 3. SYSTEM, TONART, 1-BPM TEMPO, TAP-SYNC & SOUND-SHAPING
        SectionDivider("SYSTEM // 1-BPM, TAP-SYNC, WAVE & ENVELOPE")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TeTactileButton(
                title = "KEY -", sub = "ROOT", modifier = Modifier.weight(0.95f), height = 42.dp,
                bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    keyIndex = (keyIndex + 11) % 12
                }
            )
            TeTactileButton(
                title = "KEY +", sub = "ROOT", modifier = Modifier.weight(0.95f), height = 42.dp,
                bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    keyIndex = (keyIndex + 1) % 12
                }
            )
            TeTactileButton(
                title = "BPM -", sub = "-1", modifier = Modifier.weight(0.9f), height = 42.dp,
                bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    bpm = (bpm - 1).coerceAtLeast(60)
                    synth.bpm = bpm
                }
            )
            TeTactileButton(
                title = "BPM +", sub = "+1", modifier = Modifier.weight(0.9f), height = 42.dp,
                bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    bpm = (bpm + 1).coerceAtMost(200)
                    synth.bpm = bpm
                }
            )
            TeTactileButton(
                title = "TAP", sub = "SYNC", modifier = Modifier.weight(0.95f), height = 42.dp,
                bgColor = TeOrange, shadowColor = TeOrangeShadow, textColor = Color.White,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val now = SystemClock.elapsedRealtime()
                    if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2200L) {
                        tapTimes.clear()
                    }
                    tapTimes.add(now)
                    if (tapTimes.size > 4) tapTimes.removeAt(0)
                    if (tapTimes.size >= 2) {
                        val intervals = (1 until tapTimes.size).map { tapTimes[it] - tapTimes[it - 1] }
                        val avgMs = intervals.average()
                        if (avgMs > 0) {
                            val newBpm = (60000.0 / avgMs).roundToInt().coerceIn(60, 200)
                            bpm = newBpm
                            synth.bpm = newBpm
                        }
                    }
                    synth.resetClockSync()
                }
            )
            TeTactileButton(
                title = waveNames[waveIndex], sub = "WAVE", modifier = Modifier.weight(0.95f), height = 42.dp,
                bgColor = TeOchre, shadowColor = Color(0xFFA67621), textColor = TePanelDark,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    waveIndex = (waveIndex + 1) % 4
                    synth.waveform = waveIndex
                }
            )
            TeTactileButton(
                title = envNames[envMode], sub = "ENV", modifier = Modifier.weight(0.9f), height = 42.dp,
                bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = envMode > 0,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    envMode = (envMode + 1) % envNames.size
                    synth.envMode = envMode
                    synth.triggerChordAttack()
                }
            )
            TeTactileButton(
                title = arpNames[arpMode], sub = "ARP", modifier = Modifier.weight(0.95f), height = 42.dp,
                bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = arpMode > 0,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    arpMode = (arpMode + 1) % arpNames.size
                    synth.arpMode = arpMode
                }
            )
        }

        // 4. PUNCH-IN PERFORMANCE EFFEKTE + 4/4 SIDECHAIN PUMP
        SectionDivider("PERFORMANCE FX // HOLD FX OR LATCH PUMP")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TeMomentaryFxButton(
                title = "DELAY", sub = "ECHO", isPressed = fxDelay, modifier = Modifier.weight(1f),
                onPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    fxDelay = true; synth.fxDelay = true
                },
                onRelease = { fxDelay = false; synth.fxDelay = false }
            )
            TeMomentaryFxButton(
                title = "CRUSH", sub = "8-BIT", isPressed = fxCrush, modifier = Modifier.weight(1f),
                onPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    fxCrush = true; synth.fxCrush = true
                },
                onRelease = { fxCrush = false; synth.fxCrush = false }
            )
            TeMomentaryFxButton(
                title = "STUTTR", sub = "1/32", isPressed = fxStutter, modifier = Modifier.weight(1f),
                onPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    fxStutter = true; synth.fxStutter = true
                },
                onRelease = { fxStutter = false; synth.fxStutter = false }
            )
            TeMomentaryFxButton(
                title = "CHORUS", sub = "WIDE", isPressed = fxChorus, modifier = Modifier.weight(1f),
                onPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    fxChorus = true; synth.fxChorus = true
                },
                onRelease = { fxChorus = false; synth.fxChorus = false }
            )
            TeTactileButton(
                title = "PUMP", sub = "DUCK", modifier = Modifier.weight(0.95f), height = 44.dp,
                bgColor = if (fxPump) TeOrange else TeBlue,
                shadowColor = if (fxPump) TeOrangeShadow else TeBlueShadow,
                textColor = Color.White, ledOn = fxPump,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    fxPump = !fxPump
                    synth.fxPump = fxPump
                }
            )
        }

        // 5. AKKORD-MODIFIKATOREN + HOLD/DRONE LATCH
        SectionDivider("HARMONIC MODIFIERS // LATCH & HOLD")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TeTactileButton(
                title = "HOLD", sub = "DRONE", modifier = Modifier.weight(0.95f), height = 46.dp,
                bgColor = if (holdLatch) TeBlue else TeKeyDark,
                shadowColor = if (holdLatch) TeBlueShadow else TeKeyDarkShadow,
                textColor = Color.White, ledOn = holdLatch,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    holdLatch = !holdLatch
                    if (!holdLatch) activeDegree = -1
                }
            )
            TeTactileButton(
                title = "MAJ/MIN", sub = "FLIP", modifier = Modifier.weight(1.05f), height = 46.dp,
                bgColor = if (modFlip) TeOrange else TeKeyDark,
                shadowColor = if (modFlip) TeOrangeShadow else TeKeyDarkShadow,
                textColor = Color.White, ledOn = modFlip,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    modFlip = !modFlip
                }
            )
            TeTactileButton(
                title = "7TH", sub = "JAZZ", modifier = Modifier.weight(0.9f), height = 46.dp,
                bgColor = if (mod7th) TeOrange else TeKeyDark,
                shadowColor = if (mod7th) TeOrangeShadow else TeKeyDarkShadow,
                textColor = Color.White, ledOn = mod7th,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    mod7th = !mod7th
                }
            )
            TeTactileButton(
                title = "SUS4", sub = "TENS", modifier = Modifier.weight(0.9f), height = 46.dp,
                bgColor = if (modSus4) TeOrange else TeKeyDark,
                shadowColor = if (modSus4) TeOrangeShadow else TeKeyDarkShadow,
                textColor = Color.White, ledOn = modSus4,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    modSus4 = !modSus4
                }
            )
            TeTactileButton(
                title = "OCT -", sub = "DOWN", modifier = Modifier.weight(0.8f), height = 46.dp,
                bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = octaveShift < 0,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    octaveShift = if (octaveShift == -1) 0 else -1
                }
            )
            TeTactileButton(
                title = "OCT +", sub = "UP", modifier = Modifier.weight(0.8f), height = 46.dp,
                bgColor = TeKeyDark, shadowColor = TeKeyDarkShadow, textColor = Color.White, ledOn = octaveShift > 0,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    octaveShift = if (octaveShift == 1) 0 else 1
                }
            )
        }

        // 6. DIATONISCHE KLAVIATUR-PADS MIT ECHTEM FINGER-GLIDING (LEGATO SLIDE)
        SectionDivider("DIATONIC CHORD TRIGGERS // SLIDE OR TAP (I - VII)")
        var padGridSize by remember { mutableStateOf(IntSize.Zero) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { padGridSize = it }
                .pointerInput(holdLatch, strumMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun mapOffsetToDegree(pos: Offset): Int {
                            if (padGridSize.width <= 0 || padGridSize.height <= 0) return -1
                            if (pos.x < 0f || pos.x > padGridSize.width || pos.y < 0f || pos.y > padGridSize.height) return -1
                            val isBottomRow = pos.y > padGridSize.height / 2f
                            return if (!isBottomRow) {
                                val col = ((pos.x / padGridSize.width) * 4f).toInt().coerceIn(0, 3)
                                col
                            } else {
                                val col = ((pos.x / padGridSize.width) * 3f).toInt().coerceIn(0, 2)
                                4 + col
                            }
                        }

                        var currentDeg = mapOffsetToDegree(down.position)
                        if (currentDeg in 0..6) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            activeDegree = currentDeg
                            synth.triggerChordAttack()
                            if (strumMode) {
                                for (v in 0..3) synth.triggerStrumVoice(v)
                            }
                        }

                        do {
                            val event = awaitPointerEvent()
                            val activeChange = event.changes.firstOrNull { it.pressed }
                            if (activeChange != null) {
                                activeChange.consume()
                                val slidedDeg = mapOffsetToDegree(activeChange.position)
                                if (slidedDeg in 0..6 && slidedDeg != activeDegree) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    activeDegree = slidedDeg
                                    synth.triggerChordAttack()
                                    if (strumMode) {
                                        for (v in 0..3) synth.triggerStrumVoice(v)
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (!holdLatch) {
                            activeDegree = -1
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (deg in 0..3) {
                    val noteLabel = rootNames[(keyIndex + majorScaleOffsets[deg]) % 12]
                    val qualSuffix = if (defaultQuality[deg] == 1) "m" else ""
                    TeChordKeycap(
                        number = "0${deg + 1}", roman = romanDegrees[deg], subLabel = degreeSubLabels[deg],
                        note = "$noteLabel$qualSuffix", isPressed = activeDegree == deg,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (deg in 4..6) {
                    val noteLabel = rootNames[(keyIndex + majorScaleOffsets[deg]) % 12]
                    val qualSuffix = when (defaultQuality[deg]) {
                        1 -> "m"
                        2 -> "°"
                        else -> ""
                    }
                    TeChordKeycap(
                        number = "0${deg + 1}", roman = romanDegrees[deg], subLabel = degreeSubLabels[deg],
                        note = "$noteLabel$qualSuffix", isPressed = activeDegree == deg,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

// --- TE DESIGN-KOMPONENTEN ---

@Composable
fun SectionDivider(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = Color(0xFF5A5852),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Color(0xFFB5B3AC))
        )
    }
}

@Composable
fun DisplayBadge(text: String, bgColor: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun ScrewHead() {
    Canvas(modifier = Modifier.size(12.dp)) {
        drawCircle(Color(0xFFA09E97))
        drawLine(
            color = Color(0xFF5A5852),
            start = Offset(size.width * 0.2f, size.height * 0.5f),
            end = Offset(size.width * 0.8f, size.height * 0.5f),
            strokeWidth = 2f
        )
    }
}

@Composable
fun SpeakerGrille() {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(6) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF706E68))
            )
        }
    }
}

@Composable
fun TeMomentaryFxButton(
    title: String,
    sub: String,
    isPressed: Boolean,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val topPad = if (isPressed) 2.dp else 0.dp
    val botDepth = if (isPressed) 1.dp else 3.dp

    Box(
        modifier = modifier
            .height(44.dp)
            .padding(top = topPad)
            .clip(RoundedCornerShape(7.dp))
            .background(if (isPressed) TeOrangeShadow else TeBlueShadow)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            }
            .padding(bottom = botDepth)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(if (isPressed) TeOrange else TeBlue)
                .padding(horizontal = 5.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (isPressed) Color.White else Color(0xFF093163))
                    .align(Alignment.TopEnd)
            )
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = sub,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun TeTactileButton(
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    bgColor: Color,
    shadowColor: Color,
    textColor: Color,
    ledOn: Boolean? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(7.dp))
            .background(shadowColor)
            .clickable { onClick() }
            .padding(bottom = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(bgColor)
                .padding(horizontal = 4.dp, vertical = 3.dp)
        ) {
            if (ledOn != null) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (ledOn) TeLedRed else TeLedOff)
                        .align(Alignment.TopEnd)
                )
            }
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = sub,
                    color = textColor.copy(alpha = 0.65f),
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = title,
                    color = textColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun TeChordKeycap(
    number: String,
    roman: String,
    subLabel: String,
    note: String,
    isPressed: Boolean,
    modifier: Modifier = Modifier
) {
    val topPadding = if (isPressed) 4.dp else 0.dp
    val bottomDepth = if (isPressed) 1.dp else 5.dp

    Box(
        modifier = modifier
            .padding(top = topPadding)
            .clip(RoundedCornerShape(9.dp))
            .background(if (isPressed) TeOrangeShadow else TeKeyLightShadow)
            .padding(bottom = bottomDepth)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isPressed) TeOrange else TeKeyLight)
                .border(
                    width = 1.dp,
                    color = if (isPressed) Color.White else Color(0xFFE2E0D8),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.align(Alignment.TopStart)) {
                Text(
                    text = "$number // $roman",
                    color = if (isPressed) Color.White else Color(0xFF6E6D68),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = subLabel,
                    color = if (isPressed) Color.White.copy(alpha = 0.8f) else Color(0xFF9E9C95),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isPressed) Color.White else TeLedOff.copy(alpha = 0.35f))
                    .align(Alignment.TopEnd)
            )

            Text(
                text = note,
                color = if (isPressed) Color.White else TePanelDark,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}
