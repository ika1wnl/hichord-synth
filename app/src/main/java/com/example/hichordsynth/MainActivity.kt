package com.example.hichordsynth

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.random.Random

// --- TEENAGE ENGINEERING FARBPALETTE ---
val TeChassis = Color(0xFFD8D6D0)      // Eloxiertes Aluminium / Warmes Grau
val TePanelDark = Color(0xFF191919)    // Tiefschwarz (Display & Gehäuse-Rahmen)
val TeKeyDark = Color(0xFF2B2B2B)      // Dunkelgraue Funktionstasten
val TeKeyDarkShadow = Color(0xFF121212)
val TeKeyLight = Color(0xFFF4F3EF)     // Cremeweiße Haupttasten
val TeKeyLightShadow = Color(0xFFA6A49E)
val TeOrange = Color(0xFFFF4F00)       // International Orange (TE Signature)
val TeOrangeShadow = Color(0xFFB33600)
val TeOchre = Color(0xFFE5A93C)        // Ocker / Gelb-Akzent
val TeBlue = Color(0xFF1473E6)         // Technisches Blau
val TeLedRed = Color(0xFFFF2A2A)       // Rote Status-LED
val TeLedOff = Color(0xFF4A4946)       // Ausgeschaltete LED

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

// --- 4-STIMMIGE ECHTZEIT-SYNTHESE-ENGINE ---
class HiChordEngine {
    private val sampleRate = 44100
    @Volatile var isRunning = false
    @Volatile var activeFreqs = FloatArray(4) { 0f }
    @Volatile var gate = false
    @Volatile var waveform = 0
    @Volatile var joystickX = 0f
    @Volatile var joystickY = 0f
    @Volatile var arpEnabled = false
    @Volatile var drumEnabled = false

    val scopeBuffer = FloatArray(128)
    private var phases = FloatArray(4)
    private var phasesDetune = FloatArray(4)
    private var env = 0f
    private var lpState = 0f

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
            var arpIndex = 0
            var drumEnv = 0f
            var drumPhase = 0f
            var drumStep = 0

            while (isRunning) {
                val cutoff = (0.20f + (joystickY + 1f) * 0.38f).coerceIn(0.03f, 0.96f)
                val arpSamples = sampleRate / 9

                for (i in buffer.indices) {
                    sampleCounter++

                    if (sampleCounter >= arpSamples) {
                        sampleCounter = 0
                        arpIndex = (arpIndex + 1) % 4
                        drumStep = (drumStep + 1) % 8
                        if (drumEnabled && (drumStep == 0 || drumStep == 4 || drumStep == 2)) {
                            drumEnv = 1f
                            drumPhase = 0f
                        }
                    }

                    env = if (gate) {
                        (env + 0.004f).coerceAtMost(1f)
                    } else {
                        (env * 0.9993f).coerceAtLeast(0f)
                    }

                    var chordMix = 0f
                    if (env > 0.001f) {
                        for (v in 0 until 4) {
                            if (arpEnabled && gate && v != arpIndex) continue
                            val freq = activeFreqs[v]
                            if (freq <= 20f) continue

                            phases[v] = (phases[v] + freq / sampleRate) % 1f
                            phasesDetune[v] = (phasesDetune[v] + (freq * 1.006f) / sampleRate) % 1f
                            val p = phases[v]
                            val p2 = phasesDetune[v]

                            val osc = when (waveform) {
                                0 -> (p * 2f) - 1f
                                1 -> if (p < 0.5f) 0.8f else -0.8f
                                2 -> sin(p * 2f * PI.toFloat())
                                else -> (((p * 2f) - 1f) + ((p2 * 2f) - 1f)) * 0.55f
                            }
                            chordMix += osc * 0.22f
                        }
                    }

                    lpState += cutoff * (chordMix * env - lpState)
                    var finalSample = lpState

                    if (drumEnabled && drumEnv > 0.001f) {
                        if (drumStep == 2) {
                            val noise = (Random.nextFloat() * 2f - 1f) * drumEnv * 0.2f
                            finalSample += noise
                            drumEnv *= 0.992f
                        } else {
                            val kFreq = 48f + drumEnv * 110f
                            drumPhase += kFreq / sampleRate
                            finalSample += sin(drumPhase * 2f * PI.toFloat()) * drumEnv * 0.55f
                            drumEnv *= 0.9982f
                        }
                    }

                    val out = finalSample.coerceIn(-1f, 1f)
                    if (i < scopeBuffer.size) scopeBuffer[i] = out
                    buffer[i] = (out * 31000f).toInt().toShort()
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
    joystickX: Float
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

    if (joystickX < -0.35f) {
        intervals[0] -= 12
    } else if (joystickX > 0.35f) {
        intervals[0] += 12
        if (joystickX > 0.7f) intervals[1] += 12
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
    var activeDegree by remember { mutableIntStateOf(-1) }
    var displayChord by remember { mutableStateOf("HI-CHORD") }

    var modFlip by remember { mutableStateOf(false) }
    var mod7th by remember { mutableStateOf(false) }
    var modSus4 by remember { mutableStateOf(false) }
    var octaveShift by remember { mutableIntStateOf(0) }

    var waveIndex by remember { mutableIntStateOf(0) }
    var arpOn by remember { mutableStateOf(false) }
    var drumOn by remember { mutableStateOf(false) }

    var joyX by remember { mutableFloatStateOf(0f) }
    var joyY by remember { mutableFloatStateOf(0f) }

    val waveNames = listOf("SAW", "PULS", "SINE", "SSAW")
    val romanDegrees = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
    val degreeSubLabels = listOf("TONIC", "SUPER", "MEDI", "SUBD", "DOM", "SUBM", "LEAD")

    LaunchedEffect(activeDegree, keyIndex, modFlip, mod7th, modSus4, octaveShift, joyX) {
        if (activeDegree in 0..6) {
            val (name, freqs) = buildChord(keyIndex, activeDegree, modFlip, mod7th, modSus4, octaveShift, joyX)
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
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. HARDWARE HEADER MIT MODELLBEZEICHNUNG & SCHRAUBEN-DETAILS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScrewHead()
                Text(
                    text = "TE // HC-01 CHORD SYNTHESIZER",
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
                .height(155.dp),
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
                // Hintergrund-Punktmatrix im Display
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
                    // Display Top-Statusleiste
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DisplayBadge(text = "KEY ${rootNames[keyIndex]}", bgColor = TeOrange, textColor = Color.White)
                        DisplayBadge(text = waveNames[waveIndex], bgColor = Color(0xFF333333), textColor = TeOchre)
                        DisplayBadge(
                            text = "OCT ${if (octaveShift >= 0) "+$octaveShift" else octaveShift}",
                            bgColor = Color(0xFF333333),
                            textColor = Color.White
                        )
                    }

                    // Haupt-Akkord-Anzeige + Voicing-Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = if (activeDegree >= 0) "DEGREE ${romanDegrees[activeDegree]}" else "STANDBY",
                                color = Color(0xFF888884),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = displayChord,
                                color = Color(0xFFF4F3EF),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        val voicingText = when {
                            joyX < -0.35f -> "BASS DROP"
                            joyX > 0.7f -> "INV 2"
                            joyX > 0.35f -> "INV 1"
                            else -> "ROOT"
                        }
                        Text(
                            text = voicingText,
                            color = TeOrange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Zweifarbiges Oszilloskop
                    Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
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
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                joyX = 0f; joyY = 0f
                                synth.joystickX = 0f; synth.joystickY = 0f
                            },
                            onDragCancel = {
                                joyX = 0f; joyY = 0f
                                synth.joystickX = 0f; synth.joystickY = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val maxRadius = size.width / 2.4f
                            val newX = (joyX * maxRadius + dragAmount.x).coerceIn(-maxRadius, maxRadius) / maxRadius
                            val newY = (joyY * maxRadius - dragAmount.y).coerceIn(-maxRadius, maxRadius) / maxRadius
                            joyX = newX; joyY = newY
                            synth.joystickX = newX; synth.joystickY = newY
                        }
                    }
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxR = min(size.width, size.height) / 2.6f

                    // Technische Skalenringe & Fadenkreuz
                    drawCircle(Color(0xFF9E9C95), radius = maxR, style = Stroke(width = 2f))
                    drawCircle(Color(0xFF9E9C95), radius = maxR * 0.5f, style = Stroke(width = 1f))
                    drawLine(Color(0xFF9E9C95), Offset(center.x - maxR, center.y), Offset(center.x + maxR, center.y), 1.5f)
                    drawLine(Color(0xFF9E9C95), Offset(center.x, center.y - maxR), Offset(center.x, center.y + maxR), 1.5f)

                    val knobPos = Offset(center.x + joyX * maxR, center.y - joyY * maxR)
                    // Geber-Achse
                    drawLine(TePanelDark, center, knobPos, strokeWidth = 6f, cap = StrokeCap.Round)
                    // Oranger Encoder-Knopf mit weißem Mittelpunkt
                    drawCircle(TeOrange, radius = 18.dp.toPx(), center = knobPos)
                    drawCircle(Color.White, radius = 5.dp.toPx(), center = knobPos)
                }
                Text(
                    text = "Y:LPF  X:INV",
                    color = TePanelDark,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // 3. PARAMETER- & SOUND-SEKTION (RUNDE FUNKTIONSTASTEN)
        SectionDivider("PARAMETER // ENGINE")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TeTactileButton(
                title = "KEY -",
                sub = "TRANS",
                modifier = Modifier.weight(1f),
                height = 46.dp,
                bgColor = TeKeyDark,
                shadowColor = TeKeyDarkShadow,
                textColor = Color.White,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    keyIndex = (keyIndex + 11) % 12
                }
            )
            TeTactileButton(
                title = "KEY +",
                sub = "TRANS",
                modifier = Modifier.weight(1f),
                height = 46.dp,
                bgColor = TeKeyDark,
                shadowColor = TeKeyDarkShadow,
                textColor = Color.White,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    keyIndex = (keyIndex + 1) % 12
                }
            )
            TeTactileButton(
                title = waveNames[waveIndex],
                sub = "SYNTH",
                modifier = Modifier.weight(1f),
                height = 46.dp,
                bgColor = TeOchre,
                shadowColor = Color(0xFFA67621),
                textColor = TePanelDark,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    waveIndex = (waveIndex + 1) % 4
                    synth.waveform = waveIndex
                }
            )
            TeTactileButton(
                title = "ARP",
                sub = "1/16",
                modifier = Modifier.weight(1f),
                height = 46.dp,
                bgColor = TeKeyDark,
                shadowColor = TeKeyDarkShadow,
                textColor = Color.White,
                ledOn = arpOn,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    arpOn = !arpOn
                    synth.arpEnabled = arpOn
                }
            )
            TeTactileButton(
                title = "PO-BEAT",
                sub = "DRUM",
                modifier = Modifier.weight(1f),
                height = 46.dp,
                bgColor = TeKeyDark,
                shadowColor = TeKeyDarkShadow,
                textColor = Color.White,
                ledOn = drumOn,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    drumOn = !drumOn
                    synth.drumEnabled = drumOn
                }
            )
        }

        // 4. AKKORD-MODIFIKATOREN (MIT STATUS-LEDS)
        SectionDivider("HARMONIC MODIFIERS // LATCH")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TeTactileButton(
                title = "MAJ/MIN",
                sub = "FLIP",
                modifier = Modifier.weight(1.1f),
                height = 54.dp,
                bgColor = if (modFlip) TeOrange else TeKeyDark,
                shadowColor = if (modFlip) TeOrangeShadow else TeKeyDarkShadow,
                textColor = Color.White,
                ledOn = modFlip,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    modFlip = !modFlip
                }
            )
            TeTactileButton(
                title = "7TH",
                sub = "JAZZ",
                modifier = Modifier.weight(1f),
                height = 54.dp,
                bgColor = if (mod7th) TeOrange else TeKeyDark,
                shadowColor = if (mod7th) TeOrangeShadow else TeKeyDarkShadow,
                textColor = Color.White,
                ledOn = mod7th,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    mod7th = !mod7th
                }
            )
            TeTactileButton(
                title = "SUS4",
                sub = "TENSION",
                modifier = Modifier.weight(1f),
                height = 54.dp,
                bgColor = if (modSus4) TeOrange else TeKeyDark,
                shadowColor = if (modSus4) TeOrangeShadow else TeKeyDarkShadow,
                textColor = Color.White,
                ledOn = modSus4,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    modSus4 = !modSus4
                }
            )
            TeTactileButton(
                title = "OCT -",
                sub = "DOWN",
                modifier = Modifier.weight(0.85f),
                height = 54.dp,
                bgColor = TeKeyDark,
                shadowColor = TeKeyDarkShadow,
                textColor = Color.White,
                ledOn = octaveShift < 0,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    octaveShift = if (octaveShift == -1) 0 else -1
                }
            )
            TeTactileButton(
                title = "OCT +",
                sub = "UP",
                modifier = Modifier.weight(0.85f),
                height = 54.dp,
                bgColor = TeKeyDark,
                shadowColor = TeKeyDarkShadow,
                textColor = Color.White,
                ledOn = octaveShift > 0,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    octaveShift = if (octaveShift == 1) 0 else 1
                }
            )
        }

        // 5. DIATONISCHE KLAVIATUR-PADS (STUFEN I - VII)
        SectionDivider("DIATONIC CHORD TRIGGERS // I - VII")
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Obere Pad-Reihe (Stufen I - IV)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(108.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (deg in 0..3) {
                    val noteLabel = rootNames[(keyIndex + majorScaleOffsets[deg]) % 12]
                    val qualSuffix = if (defaultQuality[deg] == 1) "m" else ""
                    TeChordKeycap(
                        number = "0${deg + 1}",
                        roman = romanDegrees[deg],
                        subLabel = degreeSubLabels[deg],
                        note = "$noteLabel$qualSuffix",
                        isPressed = activeDegree == deg,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            activeDegree = deg
                        },
                        onRelease = { if (activeDegree == deg) activeDegree = -1 }
                    )
                }
            }
            // Untere Pad-Reihe (Stufen V - vii°)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(108.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (deg in 4..6) {
                    val noteLabel = rootNames[(keyIndex + majorScaleOffsets[deg]) % 12]
                    val qualSuffix = when (defaultQuality[deg]) {
                        1 -> "m"
                        2 -> "°"
                        else -> ""
                    }
                    TeChordKeycap(
                        number = "0${deg + 1}",
                        roman = romanDegrees[deg],
                        subLabel = degreeSubLabels[deg],
                        note = "$noteLabel$qualSuffix",
                        isPressed = activeDegree == deg,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            activeDegree = deg
                        },
                        onRelease = { if (activeDegree == deg) activeDegree = -1 }
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
            fontSize = 10.sp,
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
            .padding(bottom = 3.dp) // 3D-Tastenhub-Schatten
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(bgColor)
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            if (ledOn != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (ledOn) TeLedRed else TeLedOff)
                        .align(Alignment.TopEnd)
                )
            }
            Column(
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Text(
                    text = sub,
                    color = textColor.copy(alpha = 0.65f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = title,
                    color = textColor,
                    fontSize = 11.sp,
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
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val topPadding = if (isPressed) 4.dp else 0.dp
    val bottomDepth = if (isPressed) 1.dp else 5.dp

    Box(
        modifier = modifier
            .padding(top = topPadding)
            .clip(RoundedCornerShape(9.dp))
            .background(if (isPressed) TeOrangeShadow else TeKeyLightShadow)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            }
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
            // Oben links: Technische Nummer + Römische Stufe
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

            // Oben rechts: Status-LED
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isPressed) Color.White else TeLedOff.copy(alpha = 0.35f))
                    .align(Alignment.TopEnd)
            )

            // Unten rechts: Große Akkord-Note
            Text(
                text = note,
                color = if (isPressed) Color.White else TePanelDark,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}
