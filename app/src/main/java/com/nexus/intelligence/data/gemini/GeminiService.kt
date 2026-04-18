package com.nexus.intelligence.data.gemini

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Servicio de Gemini AI para búsquedas semánticas y organización de archivos.
 * Versión apuntando al nuevo modelo multimodal de 2026.
 */
@Singleton
class GeminiService @Inject constructor(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ═══════════════════════════════════════════════════════════════
    // CONFIGURACIÓN
    // ═══════════════════════════════════════════════════════════════

    fun getApiKey(): String = prefs.getString(PREF_GEMINI_API_KEY, "") ?: ""

    fun setApiKey(apiKey: String) {
        prefs.edit().putString(PREF_GEMINI_API_KEY, apiKey.trim()).apply()
    }

    fun isConfigured(): Boolean = getApiKey().isNotBlank()

    private fun getBaseUrl(): String = "https://generativelanguage.googleapis.com"

    // 🔥 AQUÍ ESTÁ LA MAGIA: El modelo más nuevo y potente
    private fun getModelForEmbeddings(): String = "gemini-embedding-2-preview"
    private fun getModelForChat(): String = "gemini-1.5-flash"

    // ═══════════════════════════════════════════════════════════════
    // VERIFICACIÓN DE CONEXIÓN
    // ═══════════════════════════════════════════════════════════════

    suspend fun isApiAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        try {
            // Mandamos un texto simple para que el endpoint devuelva un 200 OK
            val result = getEmbedding("test connection")
            result != null
        } catch (e: Exception) {
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEXT EMBEDDINGS
    // ═══════════════════════════════════════════════════════════════

    suspend fun getEmbedding(text: String): FloatArray? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            android.util.Log.e("GeminiService", "❌ API Key no configurada")
            return@withContext null
        }

        try {
            // Estructura de request obligatoria para v1beta (usa 'content')
            val requestBody = GeminiEmbeddingRequest(
                content = Content(
                    parts = listOf(Part(text = text.take(2048)))
                )
            )

            val json = gson.toJson(requestBody)

            val request = Request.Builder()
                .url("${getBaseUrl()}/v1beta/models/${getModelForEmbeddings()}:embedContent?key=$apiKey")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            android.util.Log.d("GeminiService", "📡 Enviando embedding al modelo 2-preview → ${text.take(30)}...")

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                android.util.Log.e("GeminiService", "❌ Error HTTP ${response.code}: $errorBody")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val embeddingResponse = gson.fromJson(body, GeminiEmbeddingResponse::class.java)

            val embedding = embeddingResponse.embedding?.values?.map { it.toFloat() }?.toFloatArray()

            android.util.Log.d("GeminiService", "✅ Embedding recibido exitosamente: ${embedding?.size ?: 0} dimensiones")
            embedding
        } catch (e: Exception) {
            android.util.Log.e("GeminiService", "💥 Excepción en getEmbedding", e)
            null
        }
    }

    suspend fun getEmbeddings(texts: List<String>): List<FloatArray?>? = withContext(Dispatchers.IO) {
        texts.map { getEmbedding(it) }
    }

    // ═══════════════════════════════════════════════════════════════
    // CHAT COMPLETIONS
    // ═══════════════════════════════════════════════════════════════

    suspend fun generateText(prompt: String, maxTokens: Int = 512): String? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) return@withContext null

        try {
            val requestBody = GeminiGenerateRequest(
                contents = listOf(
                    Content(
                        parts = listOf(Part(text = prompt))
                    )
                ),
                generationConfig = GenerationConfig(
                    maxOutputTokens = maxTokens,
                    temperature = 0.3f
                )
            )

            val json = gson.toJson(requestBody)
            val request = Request.Builder()
                .url("${getBaseUrl()}/v1beta/models/${getModelForChat()}:generateContent?key=$apiKey")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                android.util.Log.e("GeminiService", "❌ Error Chat: $errorBody")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val genResponse = gson.fromJson(body, GeminiGenerateResponse::class.java)

            genResponse.candidates?.firstOrNull()
                ?.content?.parts?.firstOrNull()
                ?.text?.trim()
        } catch (e: Exception) {
            android.util.Log.e("GeminiService", "💥 Excepción en generateText", e)
            null
        }
    }

    suspend fun askForFolderName(documents: List<DocumentContext>): String? {
        if (documents.isEmpty()) return null

        val fileList = documents.take(10).joinToString("\n") { doc ->
            "- ${doc.fileName}: ${doc.contentPreview.take(80)}"
        }

        val prompt = """Analiza estos archivos y sugiere UN nombre de carpeta corto (2-4 palabras en español) que los agrupe temáticamente.
Solo responde con el nombre de la carpeta, sin explicaciones ni puntuación extra.

Archivos:
$fileList

Nombre de carpeta:"""

        return generateText(prompt, maxTokens = 20)
            ?.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ _-]"), "") 
            ?.trim()
            ?.take(50)
            ?.ifEmpty { null }
    }

    suspend fun classifyDocument(content: String): String? {
        val prompt = """Clasifica este documento en una categoría predefinida.
Solo responde con UNA palabra que sea la categoría más apropiada.

Categorías válidas: Legal, Finanzas, Educación, Medicina, Tecnología, Personal, Trabajo, Impuestos, Contratos, Reportes, Presentaciones, Otros

Contenido del documento:
${content.take(500)}

Categoría:"""

        return generateText(prompt, maxTokens = 10)
            ?.replace(Regex("[^a-zA-ZáéíóúÁÉÍÓÚñÑ]"), "")
            ?.trim()
            ?.ifEmpty { null }
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILIDADES
    // ═══════════════════════════════════════════════════════════════

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    fun findTopK(
        query: FloatArray,
        docs: List<Pair<Long, FloatArray>>,
        k: Int
    ): List<Pair<Long, Float>> {
        return docs.map { (id, emb) ->
            id to cosineSimilarity(query, emb)
        }.sortedByDescending { it.second }
            .take(k)
    }

    companion object {
        const val PREF_GEMINI_API_KEY = "gemini_api_key"
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

data class Content(
    val parts: List<Part>
)

data class Part(
    val text: String
)

data class GeminiEmbeddingRequest(
    val content: Content
)

data class GeminiEmbeddingResponse(
    val embedding: EmbeddingValues?
)

data class EmbeddingValues(
    val values: List<Double>?
)

data class GeminiGenerateRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig
)

data class GenerationConfig(
    val maxOutputTokens: Int,
    val temperature: Float
)

data class GeminiGenerateResponse(
    val candidates: List<Candidate>?
)

data class Candidate(
    val content: Content?
)

data class DocumentContext(
    val fileName: String,
    val contentPreview: String
)
