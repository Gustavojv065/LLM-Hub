package com.llmhub.llmhub.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.llmhub.llmhub.hybrid.HybridApiClient
import com.llmhub.llmhub.hybrid.HybridMessage
import com.llmhub.llmhub.hybrid.HybridProvider
import com.llmhub.llmhub.hybrid.HybridSecrets
import kotlinx.coroutines.launch

/**
 * Deliberately isolated from the existing ChatViewModel and native inference pipeline.
 * Remote requests contain text and the last 12 messages only; never upload attachments,
 * microphone recordings or RAG memories automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HybridChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToOfflineChat: () -> Unit
) {
    val context = LocalContext.current
    val client = remember { HybridApiClient() }
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(HybridProvider.OPENROUTER) }
    var expanded by remember { mutableStateOf(false) }
    var model by remember(provider) { mutableStateOf(provider.defaultModel) }
    var keyInput by remember(provider) { mutableStateOf("") }
    var hasKey by remember(provider) { mutableStateOf(HybridSecrets.load(context, provider) != null) }
    var keyNotice by remember(provider) { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(emptyList<HybridMessage>()) }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun changeProvider(newProvider: HybridProvider) {
        if (waiting) return
        if (newProvider == provider) return
        provider = newProvider
        messages = emptyList()
        draft = ""
        error = ""
    }

    fun sendOnline() {
        val text = draft.trim()
        if (text.isEmpty() || waiting) return
        val key = HybridSecrets.load(context, provider)
        if (key.isNullOrBlank()) {
            error = "Configure e salve uma chave de API para este provedor."
            return
        }
        if (model.isBlank()) {
            error = "Informe o identificador do modelo."
            return
        }
        val selectedProvider = provider
        val selectedModel = model.trim()
        val history = messages + HybridMessage("user", text)
        messages = history
        draft = ""
        error = ""
        waiting = true
        scope.launch {
            try {
                val answer = client.complete(selectedProvider, selectedModel, key, history)
                messages = history + HybridMessage("assistant", answer)
            } catch (e: Exception) {
                // Do not show HTTP response bodies, credentials, or provider traceback.
                error = if (e is com.llmhub.llmhub.hybrid.HybridApiException) {
                    e.message ?: "Não foi possível obter resposta."
                } else {
                    "Ocorreu um erro ao consultar o provedor."
                }
                messages = history
            } finally {
                waiting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistente híbrido · Online") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToOfflineChat) {
                        Icon(Icons.Default.Smartphone, contentDescription = "Abrir chat local offline")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Cloud, contentDescription = null)
                        Text("Modo online: você escolhe quando enviar", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Somente o texto e até 12 mensagens recentes são enviados ao provedor escolhido. " +
                        "Imagens, áudio e memórias locais não são transmitidos. APIs podem gerar cobranças.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Provedor", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = !waiting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(provider.title)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        HybridProvider.values().forEach { option ->
                            DropdownMenuItem(text = { Text(option.title) }, onClick = {
                                changeProvider(option)
                                expanded = false
                            })
                        }
                    }
                }
                Column(modifier = Modifier.weight(1.3f)) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        enabled = !waiting,
                        singleLine = true,
                        label = { Text("Modelo da API") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (hasKey) "Chave salva (digite apenas para trocar)" else "Chave de API") },
                placeholder = { Text("Cole sua chave aqui") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = keyInput.isNotBlank() && !waiting,
                    onClick = {
                        try {
                            HybridSecrets.save(context, provider, keyInput)
                            keyInput = ""
                            hasKey = true
                            keyNotice = "Chave protegida no dispositivo."
                            error = ""
                        } catch (_: Exception) {
                            keyNotice = "Não foi possível salvar a chave com segurança."
                        }
                    }
                ) { Text("Salvar chave") }
                OutlinedButton(
                    enabled = hasKey && !waiting,
                    onClick = {
                        HybridSecrets.delete(context, provider)
                        hasKey = false
                        keyInput = ""
                        keyNotice = "Chave removida."
                    }
                ) { Text("Excluir chave") }
            }
            if (keyNotice.isNotEmpty()) Text(keyNotice, style = MaterialTheme.typography.bodySmall)
            if (hasKey) Text("Chave configurada para " + provider.title, style = MaterialTheme.typography.labelSmall)

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (messages.isEmpty()) item {
                    Text(
                        "Converse pela internet aqui. Para usar Gemma 4 E2B offline, toque no ícone do celular no topo.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                items(messages) { message ->
                    Card(shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                if (message.role == "user") "Você" else provider.title,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(message.content)
                        }
                    }
                }
                if (waiting) item { Text("Consultando " + provider.title + "…") }
            }
            if (error.isNotEmpty()) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = !waiting,
                    label = { Text("Mensagem") },
                    minLines = 1,
                    maxLines = 3
                )
                Button(
                    onClick = { sendOnline() },
                    enabled = !waiting && draft.isNotBlank() && hasKey,
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Enviar") }
            }
            TextButton(
                onClick = { messages = emptyList(); draft = ""; error = "" },
                enabled = !waiting,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Limpar conversa desta sessão") }
        }
    }
}
