package com.example.hichordsynth

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private val synth = HiChordEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        synth.start()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HiChordScreen(synth)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        synth.stop()
    }
}

// --- 4-STIMMIGE ECHTZEIT-AUDIO-ENGINE ---
class HiChordEngine {
    private val sampleRate = 44100
    @Volatile var isRunning = false
    @Volatile var activeFreqs = FloatArray(4) { 0f }
    @Volatile var gate = false
    @Volatile var waveform = 0 // 0: Saw, 1: Square, 2: Warm Tri, 3: Detuned SuperSaw
    @Volatile var joystickX = 0f // -1f..1f -> Voicing / Inversion
    @Volatile var joystickY = 0f // -1f..1f -> Low-Pass Filter Cutoff
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
                // Cutoff-Steuerung über Joystick Y-Achse
                val cutoff = (0.18f + (joystickY + 1f) * 0.38f).coerceIn(0.03f, 0.96f)
                val arpSamples = sampleRate / 9 // 16tel-Arpeggiator

                for (i in buffer.indices) {
                    sampleCounter++

                    // Arpeggiator & Pocket-Drum-Clock
                    if (sampleCounter >= arpSamples) {
                        sampleCounter = 0
                        arpIndex = (arpIndex + 1) % 4
                        drumStep = (drumStep + 1) % 8
                        if (drumEnabled && (drumStep == 0 || drumStep == 4 || drumStep == 2)) {
                            drumEnv = 1f
                            drumPhase = 0f
                        }
                    }

                    // Sanftes Attack & Release (Envelope)
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
                                0 -> (p * 2f) - 1f // Classic Saw
                                1 -> if (p < 0.5f) 0.8f else -0.8f // Pulse / Square
                                2 -> sin(p * 2f * PI.toFloat()) // Warm Sine/Sub
                                else -> (((p * 2f) - 1f) + ((p2 * 2f) - 1f)) * 0.55f // Chorus SuperSaw
                            }
                            chordMix += osc * 0.22f
                        }
                    }

                    // Einpoliger Tiefpassfilter (gesteuert durch Joystick Y)
                    lpState += cutoff * (chordMix * env - lpState)
                    var finalSample = lpState

                    // Begleit-Drums (Kick auf 0 & 4, Hi-Hat auf 2)
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

// --- MUSIKTHEORIE & DIATONISCHE AKKORDBERECHNUNG ---
val rootNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
val majorScaleOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
// Diatonische Grundgeschlechter: I=Maj, ii=min, iii=min, IV=Maj, V=Maj, vi=min, vii°=dim
val defaultQuality = intArrayOf(0, 1, 1, 0, 0, 1, 2) // 0: Maj, 1: Min, 2: Dim

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
        !add7th -> 12 // Oktav-Dopplung wenn kein 7th aktiv
        quality == 0 -> 11 // Major 7
        else -> 10 // Dominant / Minor 7
    }

    val intervals = intArrayOf(0, third, fifth, seventh)

    // Joystick X-Achse steuert Akkord-Voicing / Umkehrungen (-1 = Bass-Drop, +1 = 1./2. Inversion)
    if (joystickX < -0.35f) {
        intervals[0] -= 12
    } else if (joystickX > 0.35f) {
        intervals[0] += 12
        if (joystickX > 0.7f) intervals[1] += 12
    }

    val freqs = FloatArray(4) { i -> midiToFreq(rootMidi + intervals[i]) }

    val suffix = buildString {
        if (sus4) append("sus4")
        else if (quality == 1) append("m")
        else if (quality == 2) append("dim")
        if (add7th) append(if (quality == 0 && !sus4) "maj7" else "7")
        if (joystickX > 0.35f) append(" (Inv)")
        if (joystickX < -0.35f) append(" (Bass)")
    }

    return "$noteName$suffix" to freqs
}

// --- HICHORD HARDWARE-UI (JETPACK COMPOSE) ---
@Composable
fun HiChordScreen(synth: HiChordEngine) {
    var keyIndex by remember { mutableIntStateOf(0) } // 0 = C-Dur
    var activeDegree by remember { mutableIntStateOf(-1) }
    var displayChord by remember { mutableStateOf("READY") }

    // Echtzeit-Modifier
    var modFlip by remember { mutableStateOf(false) }
    var mod7th by remember { mutableStateOf(false) }
    var modSus4 by remember { mutableStateOf(false) }
    var octaveShift by remember { mutableIntStateOf(0) }

    var waveIndex by remember { mutableIntStateOf(0) }
    var arpOn by remember { mutableStateOf(false) }
    var drumOn by remember { mutableStateOf(false) }

    var joyX by remember { mutableFloatStateOf(0f) }
    var joyY by remember { mutableFloatStateOf(0f) }

    val waveNames = listOf("SAW", "SQR", "SUB", "SUPERSAW")
    val romanDegrees = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")

    // Aktualisiere Frequenzen sofort bei Parameter- oder Joystick-Änderung
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

    // Oszilloskop-Refresh-Takt
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
            .background(Color(0xFF18181A))
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. KOPFBEREICH: OLED-DISPLAY & ANALOG-JOYSTICK
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // OLED Display im Pocket-Synth-Look
            Box(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight()
                    .background(Color(0xFF070B0C), RoundedCornerShape(10.dp))
                    .border(2.dp, Color(0xFF34383B), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "KEY: ${rootNames[keyIndex]} MAJ",
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${waveNames[waveIndex]} | OCT:${if (octaveShift >= 0) "+$octaveShift" else octaveShift}",
                            color = Color(0xFFFF9100),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        text = displayChord,
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    // Echtzeit-Wellenformanzeige (Oszilloskop)
                    Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                        val tick = frameTick // Trigger Redraw
                        val path = Path()
                        val stepX = size.width / synth.scopeBuffer.size
                        val midY = size.height / 2f
                        for (i in synth.scopeBuffer.indices) {
                            val x = i * stepX
                            val y = midY - (synth.scopeBuffer[i] * midY * 0.9f)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color = Color(0xFF00E5FF), style = Stroke(width = 3f))
                    }
                }
            }

            // Daumen-Joystick (X: Voicing/Inversion, Y: Filter Cutoff)
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .background(Color(0xFF232428), CircleShape)
                    .border(3.dp, Color(0xFF3E4047), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                joyX = 0f
                                joyY = 0f
                                synth.joystickX = 0f
                                synth.joystickY = 0f
                            },
                            onDragCancel = {
                                joyX = 0f
                                joyY = 0f
                                synth.joystickX = 0f
                                synth.joystickY = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val maxRadius = size.width / 2.5f
                            val newX = (joyX * maxRadius + dragAmount.x).coerceIn(-maxRadius, maxRadius) / maxRadius
                            val newY = (joyY * maxRadius - dragAmount.y).coerceIn(-maxRadius, maxRadius) / maxRadius
                            joyX = newX
                            joyY = newY
                            synth.joystickX = newX
                            synth.joystickY = newY
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxR = size.width / 3f
                    val knobPos = Offset(center.x + joyX * maxR, center.y - joyY * maxR)
                    drawLine(Color(0xFF555555), center, knobPos, strokeWidth = 8f)
                    drawCircle(Color(0xFFFF5722), radius = 24.dp.toPx(), center = knobPos)
                }
                Text(
                    text = "FILTER / INV",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                )
            }
        }

        // 2. SOUND- & TONART-STEUERUNG (TOP-LEISTE)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallControlBtn("KEY -", Modifier.weight(1f)) { keyIndex = (keyIndex + 11) % 12 }
            SmallControlBtn("KEY +", Modifier.weight(1f)) { keyIndex = (keyIndex + 1) % 12 }
            SmallControlBtn("WAVE", Modifier.weight(1f)) {
                waveIndex = (waveIndex + 1) % 4
                synth.waveform = waveIndex
            }
            SmallControlBtn(if (arpOn) "ARP: ON" else "ARP: OFF", Modifier.weight(1f), arpOn) {
                arpOn = !arpOn
                synth.arpEnabled = arpOn
            }
            SmallControlBtn(if (drumOn) "BEAT: ON" else "BEAT: OFF", Modifier.weight(1f), drumOn) {
                drumOn = !drumOn
                synth.drumEnabled = drumOn
            }
        }

        // 3. AKKORD-MODIFIKATOREN (ZUM GEHALTENBLEIBEN ODER TOGGLEN)
        Text(
            text = "CHORD MODIFIERS (TOGGLE / COMBINE)",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModifierButton("MAJ/MIN", modFlip, Modifier.weight(1f)) { modFlip = !modFlip }
            ModifierButton("7TH", mod7th, Modifier.weight(1f)) { mod7th = !mod7th }
            ModifierButton("SUS4", modSus4, Modifier.weight(1f)) { modSus4 = !modSus4 }
            ModifierButton("OCT -", octaveShift < 0, Modifier.weight(0.8f)) {
                octaveShift = if (octaveShift == -1) 0 else -1
            }
            ModifierButton("OCT +", octaveShift > 0, Modifier.weight(0.8f)) {
                octaveShift = if (octaveShift == 1) 0 else 1
            }
        }

        // 4. DIE 7 DIATONISCHEN HICHORD-HAUPTTASTEN (STUFEN I - VII)
        Text(
            text = "DIATONIC CHORD PAD (HOLD TO PLAY)",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Obere Reihe: I, ii, iii, IV
            Row(
                modifier = Modifier.fillMaxWidth().height(110.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (deg in 0..3) {
                    val noteLabel = rootNames[(keyIndex + majorScaleOffsets[deg]) % 12]
                    ChordPadButton(
                        roman = romanDegrees[deg],
                        note = noteLabel,
                        isPressed = activeDegree == deg,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onPress = { activeDegree = deg },
                        onRelease = { if (activeDegree == deg) activeDegree = -1 }
                    )
                }
            }
            // Untere Reihe: V, vi, vii°
            Row(
                modifier = Modifier.fillMaxWidth().height(110.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (deg in 4..6) {
                    val noteLabel = rootNames[(keyIndex + majorScaleOffsets[deg]) % 12]
                    ChordPadButton(
                        roman = romanDegrees[deg],
                        note = noteLabel,
                        isPressed = activeDegree == deg,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onPress = { activeDegree = deg },
                        onRelease = { if (activeDegree == deg) activeDegree = -1 }
                    )
                }
            }
        }
    }
}

@Composable
fun SmallControlBtn(label: String, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(2.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF00ACC1) else Color(0xFF2C2E33),
            contentColor = Color.White
        )
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ModifierButton(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFFFF6D00) else Color(0xFF282A2E),
            contentColor = Color.White
        )
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ChordPadButton(
    roman: String,
    note: String,
    isPressed: Boolean,
    modifier: Modifier,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    Box(
        modifier = modifier
            .background(
                if (isPressed) Color(0xFF00E5FF) else Color(0xFFD6D7DC),
                RoundedCornerShape(12.dp)
            )
            .border(
                width = 3.dp,
                color = if (isPressed) Color.White else Color(0xFF8E9099),
                shape = RoundedCornerShape(12.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            }
            .padding(10.dp)
    ) {
        Text(
            text = roman,
            color = Color(0xFF18181A),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Text(
            text = note,
            color = Color(0xFF18181A),
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}