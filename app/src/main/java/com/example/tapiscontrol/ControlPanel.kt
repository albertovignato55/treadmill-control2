package com.example.tapiscontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BIG_PAD = PaddingValues(horizontal = 4.dp, vertical = 2.dp)

/** Pulsante grande per i comandi a schermo intero (da premere anche col tapis in movimento). */
@Composable
fun BigBtn(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    height: Dp = 46.dp,
    fontSize: TextUnit = 18.sp,
    onClick: () -> Unit,
) {
    val m = modifier.height(height)
    if (primary) {
        Button(onClick = onClick, modifier = m, enabled = enabled, contentPadding = BIG_PAD) {
            Text(text, fontSize = fontSize, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = m,
            enabled = enabled,
            contentPadding = BIG_PAD,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        ) { Text(text, fontSize = fontSize, maxLines = 1) }
    }
}

/**
 * Pannello comandi a schermo intero: colonna verticale sul lato destro con pulsanti grandi.
 * Con "Nascondi" resta solo un piccolo pulsante "Comandi" (piu' "Esci") in alto a destra.
 */
@Composable
fun BoxScope.SidePanel(
    showControls: Boolean,
    onToggle: () -> Unit,
    onExit: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (showControls) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(240.dp)
                .background(Color(0xB3000000))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigBtn("Nascondi", Modifier.weight(1.25f), height = 40.dp, fontSize = 13.sp, onClick = onToggle)
                BigBtn("✕ Esci", Modifier.weight(1f), height = 40.dp, fontSize = 13.sp, onClick = onExit)
            }
            content()
        }
    } else {
        Column(
            Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BigBtn("Comandi", Modifier.width(130.dp), height = 40.dp, fontSize = 14.sp, onClick = onToggle)
            BigBtn("✕ Esci", Modifier.width(130.dp), height = 40.dp, fontSize = 14.sp, onClick = onExit)
        }
    }
}
