package app.fabula.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One step of shower mode: how much louder, and what it is called. */
internal data class ShowerLevel(val db: Float, val label: String)

/** The steps offered, quietest first. The boost itself goes up to 15 dB. */
internal val SHOWER_LEVELS = listOf(
    ShowerLevel(3f, "Leicht"),
    ShowerLevel(6f, "Mittel"),
    ShowerLevel(9f, "Stark"),
    ShowerLevel(12f, "Sehr stark"),
    ShowerLevel(15f, "Maximal")
)

/**
 * The step a stored boost corresponds to, or null for off or for a value in
 * between -- one set with the slider this sheet replaced, say.
 */
internal fun showerLevelFor(db: Float): ShowerLevel? =
    SHOWER_LEVELS.firstOrNull { abs(it.db - db) < 0.5f }

/**
 * Choosing how strong shower mode is, laid out like Spotify's sleep timer: a
 * sheet with a short list, the current choice in colour with a tick, a way to
 * switch it off at the top while it is on, and a tap that sets and closes.
 *
 * Fixed steps rather than a slider: in the shower, with wet hands, a row is a
 * target and a slider is a fiddle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowerStrengthSheet(
    boostDb: Float,
    speakerOnly: Boolean,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val current = showerLevelFor(boostDb)
    val on = boostDb > 0f

    // Sets, then closes with the sheet's own animation rather than vanishing.
    val choose: (Float) -> Unit = { db ->
        onSelect(db)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                "Duschmodus",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Lauter über den Handylautsprecher, damit du auch unter der Dusche alles verstehst.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(12.dp))

            if (on) {
                LevelRow(
                    label = "Duschmodus ausschalten",
                    detail = null,
                    selected = false,
                    onClick = { choose(0f) }
                )
            }
            SHOWER_LEVELS.forEach { level ->
                LevelRow(
                    label = level.label,
                    detail = "+${level.db.toInt()} dB",
                    selected = level == current,
                    onClick = { choose(level.db) }
                )
            }

            // Set, but not doing anything right now: say why, so the choice
            // doesn't look broken.
            if (on && !speakerOnly) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Wirkt nur über den Handylautsprecher – gerade sind Kopfhörer oder Bluetooth verbunden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
            }
        }
    }
}

@Composable
private fun LevelRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            modifier = Modifier.weight(1f)
        )
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) color else MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.width(12.dp))
        // Always the same width, so the labels line up whether ticked or not.
        Icon(
            Icons.Filled.Check,
            contentDescription = if (selected) "Ausgewählt" else null,
            tint = if (selected) color else Color.Transparent,
            modifier = Modifier.size(20.dp)
        )
    }
}
