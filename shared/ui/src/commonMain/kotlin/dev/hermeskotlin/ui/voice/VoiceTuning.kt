package dev.hermeskotlin.ui.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.resources.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * How the voice chat listens, tuned while it runs, for a car or a noisy room: hands-free or push to talk, and,
 * hands-free, cutting in on or off with a meter of what the microphone hears and the gate on it. Talking has to reach
 * the gate to cut in; drag it, or have it set just over the noise heard lately.
 */
@Composable
internal fun VoiceTuning(
    state: VoiceChatState,
    meter: StateFlow<VoiceMeter>,
    pushToTalk: Boolean,
    onPushToTalk: (Boolean) -> Unit,
    /** This phone lets talking over a reply cut in. */
    cutIn: Boolean,
    gate: Float,
    onCutIn: (Boolean) -> Unit,
    onGate: (Float) -> Unit,
    onGateFromNoise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val listenLabels = mapOf(false to stringResource(Res.string.voice_hands_free), true to stringResource(Res.string.voice_push_to_talk))
            SegmentedControl(
                options = listOf(false, true),
                selected = pushToTalk,
                onSelect = onPushToTalk,
                optionLabel = { listenLabels.getValue(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (pushToTalk) {
                Text(
                    stringResource(Res.string.voice_push_to_talk_detail),
                    style = Theme[typography][caption],
                    color = Theme[colors][textSecondary],
                )
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().toggleable(value = cutIn, role = Role.Switch, onValueChange = onCutIn),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.voice_cut_in), style = Theme[typography][body], fontWeight = FontWeight.SemiBold, color = Theme[colors][textColor])
                    Text(
                        stringResource(if (cutIn) Res.string.voice_cut_in_on else Res.string.voice_cut_in_off),
                        style = Theme[typography][caption],
                        color = Theme[colors][textTertiary],
                    )
                }
                Switch(cutIn)
            }
            if (!cutIn) return@Column
            // The level comes 50 times a second: only the meter's drawing follows it, the rest whether the room is heard.
            val roomHeard by remember(meter) { meter.map { it.roomHeard }.distinctUntilChanged() }.collectAsState(meter.value.roomHeard)
            NoiseMeter(meter, gate, onGate)
            Text(
                stringResource(if (gate > 0f) Res.string.voice_gate_set else Res.string.voice_gate_none),
                style = Theme[typography][caption],
                color = Theme[colors][textSecondary],
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    stringResource(Res.string.voice_gate_from_noise),
                    onClick = onGateFromNoise,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = canSetFromNoise(state, roomHeard),
                )
                if (gate > 0f) Button(stringResource(Res.string.voice_gate_clear), onClick = { onGate(0f) }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            }
            Text(stringResource(gateHint(state, roomHeard)), style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
    }
}

/**
 * The level as it comes, the bar cutting in has to clear now (a tick), and the gate (the accent line, which a drag or
 * a tap moves). On a square-root scale, as loudness is heard: the room's noise and a voice both have room on it.
 */
@Composable
private fun NoiseMeter(meter: StateFlow<VoiceMeter>, gate: Float, onGate: (Float) -> Unit) {
    val now = meter.collectAsState()
    // A drag moves the line here, and sets the gate once it ends: not a settings write for every frame of it.
    var dragged by remember { mutableStateOf<Float?>(null) }
    val setGate by rememberUpdatedState(onGate)
    val line = dragged ?: gate
    val track = Theme[colors][surface3]
    val fill = Theme[colors][textTertiary]
    val tick = Theme[colors][textSecondary]
    val lineColor = Theme[colors][accent]
    val meterName = stringResource(Res.string.voice_noise_meter)
    val lineAt = stringResource(Res.string.voice_gate_line, if (line > 0f) percent(line) else stringResource(Res.string.voice_gate_line_none))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics {
                contentDescription = meterName
                stateDescription = lineAt
            }
            .pointerInput(Unit) {
                detectTapGestures { setGate(levelAt(it.x / size.width)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        dragged?.let(setGate)
                        dragged = null
                    },
                    onDragCancel = { dragged = null },
                ) { change, _ -> dragged = levelAt(change.position.x / size.width) }
            },
    ) {
        // Read here, so a new level only draws the meter again.
        val meter = now.value
        val barHeight = 14.dp.toPx()
        val top = (size.height - barHeight) / 2
        val radius = CornerRadius(barHeight / 2)
        drawRoundRect(track, Offset(0f, top), Size(size.width, barHeight), radius)
        drawRoundRect(fill, Offset(0f, top), Size(size.width * meterPosition(meter.level), barHeight), radius)
        meter.bar?.let {
            val x = size.width * meterPosition(it)
            drawLine(tick, Offset(x, top - 3.dp.toPx()), Offset(x, top + barHeight + 3.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
        if (line > 0f) {
            val x = size.width * meterPosition(line)
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
        }
    }
}

/** Where [level] sits along the meter, 0..1: the square root, as loudness is heard. */
internal fun meterPosition(level: Float): Float = sqrt(level.coerceIn(0f, 1f))

/** The level at [position] along the meter, 0..1, so a drag there sets the gate to it. */
internal fun levelAt(position: Float): Float = position.coerceIn(0f, 1f).let { it * it }

/** The noise heard lately is the room's: there's enough of it, and nobody talks or is muted, and nothing plays. */
internal fun canSetFromNoise(state: VoiceChatState, roomHeard: Boolean): Boolean =
    roomHeard && !state.muted && !state.hearing && state.phase != VoicePhase.Speaking

private fun gateHint(state: VoiceChatState, roomHeard: Boolean): StringResource = when {
    state.muted -> Res.string.voice_gate_hint_muted
    state.phase == VoicePhase.Speaking -> Res.string.voice_gate_hint_speaking
    !roomHeard -> Res.string.voice_gate_hint_listen
    else -> Res.string.voice_gate_hint_heard
}

private fun percent(level: Float) = "${(meterPosition(level) * 100).roundToInt()}%"
