package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.AppDesign
import com.secrethero.neurocode.ui.TerminalViewModel

@Composable
fun TerminalScreen(terminal: TerminalViewModel) {
    val lines by terminal.lines.collectAsStateWithLifecycle()
    val settings by terminal.settings.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var command by remember { mutableStateOf("") }

    // Ключ по проекту: при переключении сессия перезапускается в новом корне.
    LaunchedEffect(settings.selectedProjectId) { terminal.startTerminal() }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    // Классика — чёрный терминал GitHub-dark, современный дизайн — тёмная dev-панель Gemini.
    val modern = settings.appDesign == AppDesign.MODERN
    val screenColor = if (modern) Color(0xFF0D0E0F) else Color(0xFF000000)
    val barColor = if (modern) Color(0xFF0D0E0F) else Color(0xFF161B22)
    val accentColor = if (modern) Color(0xFF6DD58C) else Color(0xFF7EE787)
    val textColor = if (modern) Color(0xFFE3E3E3) else Color(0xFFE6EDF3)
    val errorColor = if (modern) Color(0xFFF28B82) else Color(0xFFF85149)
    val mutedColor = if (modern) Color(0xFF8E918F) else Color(0xFF8B949E)

    Column(
        Modifier
            .fillMaxSize()
            .background(screenColor)
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.terminal_session_label),
                color = mutedColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            )
            IconButton(onClick = terminal::interruptTerminal) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_cd), tint = errorColor)
            }
            IconButton(onClick = terminal::clearTerminal) {
                Icon(Icons.Default.ClearAll, contentDescription = stringResource(R.string.clear_cd), tint = mutedColor)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
        ) {
            items(lines) { line ->
                Text(
                    line.text,
                    color = when {
                        line.error -> errorColor
                        line.command -> accentColor
                        else -> textColor
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(8.dp),
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                prefix = {
                    Text(if (modern) "❯ " else "$ ", color = accentColor)
                },
                textStyle = TextStyle(
                    color = textColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (command.isNotBlank()) {
                            terminal.runTerminal(command)
                            command = ""
                        }
                    },
                ),
            )
            IconButton(
                onClick = {
                    if (command.isNotBlank()) {
                        terminal.runTerminal(command)
                        command = ""
                    }
                },
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.run_cd), tint = accentColor)
            }
        }
    }
}


