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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.ui.TerminalViewModel

@Composable
fun TerminalScreen(terminal: TerminalViewModel) {
    val lines by terminal.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var command by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { terminal.startTerminal() }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF05070A))
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = terminal::interruptTerminal) {
                Icon(Icons.Default.Stop, contentDescription = "РћСЃС‚Р°РЅРѕРІРёС‚СЊ", tint = Color(0xFFFF6B6B))
            }
            IconButton(onClick = terminal::clearTerminal) {
                Icon(Icons.Default.ClearAll, contentDescription = "РћС‡РёСЃС‚РёС‚СЊ", tint = Color(0xFFB9C2CF))
            }
        }
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
                        line.error -> Color(0xFFFF6B6B)
                        line.command -> Color(0xFF65E5C4)
                        else -> Color(0xFFD8DEE9)
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
                .padding(8.dp),
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                prefix = { Text("$ ", color = Color(0xFF65E5C4)) },
                textStyle = TextStyle(
                    color = Color.White,
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
                Icon(Icons.Default.PlayArrow, contentDescription = "Р’С‹РїРѕР»РЅРёС‚СЊ", tint = Color(0xFF65E5C4))
            }
        }
    }
}


