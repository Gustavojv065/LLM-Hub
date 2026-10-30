package com.llmhub.llmhub.hybrid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Only allow official HTTPS endpoints. Do not accept arbitrary base URLs or redirects to
 * third-party hosts: the user's Authorization header must not be forwarded elsewhere.
 */
enum class HybridProvider(
    val title: String,
    val fileId: String,
    val endpoint: String,
    val defaultModel: String
) {
    OPENAI("OpenAI", "openai", "https://api.openai.com/v1/chat/completions", "gpt-4.1-mini"),
    NVIDIA("NVIDIA", "nvidia", "https://integrate.api.nvidia.com/v1/chat/completions", "meta/llama-3.1-8b-instruct"),
    OPENROUTER("OpenRouter", "openrouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/auto")
}

data class HybridMessage(val role: String, val content: String)

/**
 * Android Keystore AES/GCM; only encrypted bytes are persisted in noBackupFilesDir.
 * Never save plaintext keys in preferences, source, BuildConfig, logs, or Android backups.
 * Keystore keys are device-bound: after reinstall/restore the user must supply a new key.
 */
object HybridSecrets {
    private const val KEY_ALIAS = "llmhub_hybrid_api_aes_v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyFile(context: Context, provider: HybridProvider): File =
        File(context.noBackupFilesDir, "hybrid_key_" + provider.fileId + ".bin")

    @Synchronized
    fun save(context: Context, provider: HybridProvider, apiKey: String) {
        require(apiKey.isNotBlank()) { "A chave não pode estar vazia." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val payload = byteArrayOf(iv.size.toByte()) + iv + ciphertext
        val file = keyFile(context, provider)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(payload)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IllegalStateException("Não foi possível proteger a chave no dispositivo.")
        }
    }

    @Synchronized
    fun load(context: Context, provider: HybridProvider): String? {
        val file = keyFile(context, provider)
        if (!file.exists()) return null
        return try {
            val data = file.readBytes()
            if (data.size < 30) return null
            val ivLength = data[0].toInt() and 0xFF
            if (ivLength != 12 || data.size <= ivLength + 1) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, data.copyOfRange(1, ivLength + 1))
            )
            String(cipher.doFinal(data.copyOfRange(ivLength + 1, data.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            // An old/corrupt key must not be silently replaced or logged.
            null
        }
    }

    @Synchronized
    fun delete(context: Context, provider: HybridProvider) {
        keyFile(context, provider).delete()
    }
}

class HybridApiException(message: String) : Exception(message)

/**
 * Explicit online-only, text-only chat. Existing offline models, ASR/TTS and image
 * generation remain managed by their original services and are not modified here.
 */
class HybridApiClient {
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(70, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun complete(
        provider: HybridProvider,
        model: String,
        apiKey: String,
        messages: List<HybridMessage>
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw HybridApiException("Salve sua chave de API primeiro.")
        if (model.isBlank()) throw HybridApiException("Informe um modelo do provedor.")
        if (messages.none { it.role == "user" }) throw HybridApiException("Digite sua mensagem.")
        val jsonMessages = JSONArray()
        for (message in messages.takeLast(12)) {
            if (message.role !in listOf("user", "assistant")) continue
            jsonMessages.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        val body = JSONObject()
            .put("model", model.trim())
            .put("stream", false)
            .put("messages", jsonMessages)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(provider.endpoint)
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/json")
            .post(body)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val description = when (response.code) {
                        401, 403 -> "Chave inválida ou sem autorização."
                        402 -> "A conta requer créditos ou pagamento."
                        404 -> "Modelo indisponível. Confira o identificador do modelo."
                        429 -> "Limite de requisições atingido. Tente novamente depois."
                        in 500..599 -> "O provedor está indisponível no momento."
                        else -> "Requisição recusada pelo provedor (HTTP " + response.code + ")."
                    }
                    // Never print raw provider responses, which can contain sensitive data.
                    throw HybridApiException(description)
                }
                val data = response.body?.string()?.take(2_000_000)
                    ?: throw HybridApiException("Resposta vazia do provedor.")
                val message = JSONObject(data)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                val result = message?.optString("content", "")?.trim().orEmpty()
                if (result.isEmpty()) throw HybridApiException("O modelo não retornou texto. Escolha outro modelo.")
                result
            }
        } catch (e: HybridApiException) {
            throw e
        } catch (_: java.io.IOException) {
            throw HybridApiException("Falha de conexão. Verifique a internet e tente novamente.")
        } catch (_: org.json.JSONException) {
            throw HybridApiException("Resposta inesperada do provedor. Confira o modelo.")
        }
    }
}
