package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatRunState
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.ui.ChatViewModel
import com.secrethero.neurocode.ui.EditorViewModel

@Composable
fun ChatScreen(chat: ChatViewModel, editor: EditorViewModel) {
    val settings by chat.settings.collectAsStateWithLifecycle()
    val sessions by chat.sessions.collectAsStateWithLifecycle()
    val activeId by chat.activeSessionId.collectAsStateWithLifecycle()
    val runState by chat.chatRunState.collectAsStateWithLifecycle()
    val streaming by chat.streamingResponse.collectAsStateWithLifecycle()
    val agentLog by chat.agentLog.collectAsStateWithLifecycle()
    val active = sessions.firstOrNull { it.id == activeId }
    val messages = active?.messages.orEmpty()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    val busy = runState is ChatRunState.Working
    val provider = settings.providers.firstOrNull { it.id == settings.selectedProviderId }

    LaunchedEffect(messages.size, streaming, agentLog.size) {
        val extra = if (streaming.isNotEmpty() || agentLog.isNotEmpty()) 1 else 0
        if (messages.size + extra > 0) {
            listState.animateScrollToItem(messages.size + extra - 1)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                AssistChip(
                    onClick = chat::newChat,
                    label = { Text("РќРѕРІС‹Р№") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                )
            }
            items(sessions, key = { it.id }) { session ->
                FilterChip(
                    selected = session.id == activeId,
                    onClick = { chat.selectChat(session.id) },
                    label = {
                        Text(
                            session.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(130.dp),
                        )
                    },
                    trailingIcon = if (session.id == activeId) {
                        {
                            IconButton(
                                onClick = { chat.deleteChat(session.id) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "РЈРґР°Р»РёС‚СЊ РґРёР°Р»РѕРі",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        if (settings.useLocalModel) {
                            "Р›РѕРєР°Р»СЊРЅРѕ В· ${settings.localModelName ?: "GGUF"}"
                        } else {
                            "${provider?.name ?: "API"} В· ${provider?.model ?: "РјРѕРґРµР»СЊ"}"
                        },
                        maxLines = 1,
                    )
                },
            )
            if (!settings.useLocalModel) {
                Text(
                    if (settings.agentMode) "РђРіРµРЅС‚" else "РўРѕР»СЊРєРѕ С‡Р°С‚",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && streaming.isEmpty()) {
                item {
                    EmptyChatHint(local = settings.useLocalModel)
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
            if (agentLog.isNotEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            TextButton(onClick = { showLog = !showLog }) {
                                Text(if (showLog) "РЎРєСЂС‹С‚СЊ РґРµР№СЃС‚РІРёСЏ Р°РіРµРЅС‚Р°" else "РџРѕРєР°Р·Р°С‚СЊ РґРµР№СЃС‚РІРёСЏ Р°РіРµРЅС‚Р°")
                            }
                            if (showLog) {
                                agentLog.forEach {
                                    Text(
                                        it,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (streaming.isNotEmpty()) {
                item {
                    MessageBubble(
                        ChatMessage(
                            id = "streaming",
                            role = MessageRole.ASSISTANT,
                            content = streaming,
                        ),
                    )
                }
            }
            if (busy && streaming.isEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text((runState as ChatRunState.Working).status)
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (settings.agentMode && !settings.useLocalModel) {
                            "РћРїРёС€РёС‚Рµ Р·Р°РґР°С‡Сѓ РґР»СЏ РїСЂРѕРµРєС‚Р°вЂ¦"
                        } else {
                            "РЎРѕРѕР±С‰РµРЅРёРµвЂ¦"
                        },
                    )
                },
                minLines = 1,
                maxLines = 6,
            )
            FilledIconButton(
                onClick = {
                    if (busy) {
                        chat.cancelChat()
                    } else if (input.isNotBlank()) {
                        chat.sendMessage(input, editor.currentContext())
                        input = ""
                    }
                },
                enabled = busy || input.isNotBlank(),
            ) {
                Icon(
                    if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (busy) "РћСЃС‚Р°РЅРѕРІРёС‚СЊ" else "РћС‚РїСЂР°РІРёС‚СЊ",
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .background(
                    color = if (user) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (user) 16.dp else 4.dp,
                        bottomEnd = if (user) 4.dp else 16.dp,
                    ),
                )
                .padding(12.dp),
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.role == MessageRole.TOOL) Color(0xFFFFB86C)
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun EmptyChatHint(local: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (local) "Р›РѕРєР°Р»СЊРЅР°СЏ РјРѕРґРµР»СЊ РіРѕС‚РѕРІР° Рє РґРёР°Р»РѕРіСѓ" else "РђРіРµРЅС‚ РіРѕС‚РѕРІ СЂР°Р±РѕС‚Р°С‚СЊ СЃ РїСЂРѕРµРєС‚РѕРј",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (local) {
                "РўРµРєСѓС‰РёР№ РѕС‚РєСЂС‹С‚С‹Р№ С„Р°Р№Р» Р°РІС‚РѕРјР°С‚РёС‡РµСЃРєРё РґРѕР±Р°РІР»СЏРµС‚СЃСЏ РІ РєРѕРЅС‚РµРєСЃС‚. Р›РѕРєР°Р»СЊРЅС‹Р№ СЂРµР¶РёРј РЅРµ РёСЃРїРѕР»СЊР·СѓРµС‚ РёРЅС‚РµСЂРЅРµС‚."
            } else {
                "РћРЅ РјРѕР¶РµС‚ С‡РёС‚Р°С‚СЊ С„Р°Р№Р»С‹, РїСЂРµРґР»Р°РіР°С‚СЊ РёР·РјРµРЅРµРЅРёСЏ, Р·Р°РїСѓСЃРєР°С‚СЊ РєРѕРјР°РЅРґС‹ Рё РїСЂРѕРІРµСЂСЏС‚СЊ Git diff. РћРїР°СЃРЅС‹Рµ РґРµР№СЃС‚РІРёСЏ С‚СЂРµР±СѓСЋС‚ РїРѕРґС‚РІРµСЂР¶РґРµРЅРёСЏ."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}


