package com.stocksdetective.app.ui

import com.stocksdetective.app.Deps
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.stocksdetective.app.ai.ChatMessage
import com.stocksdetective.app.ai.Interrogation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterrogationScreen(symbol: String, deps: Deps, onDone: () -> Unit) {
    val model = deps.model
    val file = model.cases[symbol]
    if (file == null) { LaunchedEffect(Unit) { onDone() }; return }
    // Keeps the conversation for as long as this screen is open.
    val interrogation = remember(symbol) { Interrogation(file, model.reports[symbol], model.provider, model.ai) }
    var draft by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    fun send(text: String) {
        draft = ""
        scope.launch { interrogation.ask(text) }
    }

    LaunchedEffect(interrogation.messages.size, interrogation.isThinking) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Interrogation") }, actions = { TextButton(onClick = onDone) { Text("Done") } })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().imePadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Ask the detective…") }, maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) send(draft) }),
                        shape = RoundedCornerShape(20.dp),
                    )
                    IconButton(onClick = { send(draft) }, enabled = draft.isNotBlank() && !interrogation.isThinking) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (draft.isNotBlank()) Palette.accent else MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(), state = list,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Ask follow-up questions about $symbol. The detective can pull prices and headlines for other tickers to compare.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (interrogation.messages.isEmpty()) {
                items(interrogation.suggestions) { s -> OutlinedButton(onClick = { send(s) }) { Text(s) } }
            }
            items(interrogation.messages, key = { it.id }) { Bubble(it) }
            if (interrogation.isThinking) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingAI()
                        Text("  Investigating…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    val user = message.role == ChatMessage.Role.USER
    Row(Modifier.fillMaxWidth()) {
        if (user) Spacer(Modifier.weight(1f))
        Column(Modifier.widthIn(max = 300.dp)) {
            Text(
                message.text,
                Modifier
                    .background(if (user) Palette.accent else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                color = if (user) Color.Black else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!user) Spacer(Modifier.weight(1f))
    }
}
