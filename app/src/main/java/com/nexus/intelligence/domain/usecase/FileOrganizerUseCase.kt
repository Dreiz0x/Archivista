package com.nexus.intelligence.domain.usecase

import com.nexus.intelligence.data.gemini.GeminiService
import com.nexus.intelligence.data.gemini.DocumentContext
import com.nexus.intelligence.data.local.dao.DocumentDao
import com.nexus.intelligence.domain.model.DocumentInfo
import com.nexus.intelligence.domain.model.FileCluster
import com.nexus.intelligence.domain.model.GenericFileInfo
import com.nexus.intelligence.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileOrganizerUseCase @Inject constructor(
    private val repository: DocumentRepository,
    private val geminiService: GeminiService,
    private val documentDao: DocumentDao
) {
    private val _progress = MutableStateFlow("")
    val progress: Flow<String> = _progress.asStateFlow()

    // ═══════════════════════════════════════════════════════════════
    // ANALISIS PRINCIPAL
    // ═══════════════════════════════════════════════════════════════

    suspend fun analyzeDocuments(documents: List<DocumentInfo>): List<FileCluster> {
        if (documents.isEmpty()) return emptyList()

        _progress.value = "Detectando archivos con nombres genericos..."
        val genericInfos = documents.map { doc ->
            GenericFileInfo(
                document = doc,
                isGenericName = isGenericName(doc.fileName)
            )
        }

        _progress.value = "Generando embeddings para clustering..."
        val embeddings = generateEmbeddingsForAll(documents)

        _progress.value = "Agrupando documentos por similitud..."
        val rawClusters = clusterBySimilarity(documents, embeddings)

        _progress.value = "Consultando Gemini para nombres de carpetas..."
        val namedClusters = nameClustersWithGemini(rawClusters)

        _progress.value = "Listo"
        return namedClusters
    }

    // ═══════════════════════════════════════════════════════════════
    // DETECCION DE NOMBRES GENERICOS
    // ═══════════════════════════════════════════════════════════════

    fun isGenericName(fileName: String): Boolean {
        val name = fileName.substringBeforeLast(".").lowercase().trim()
        if (name.length < 2) return true

        val vowels = setOf('a', 'e', 'i', 'o', 'u')
        val hasVowels = name.any { it in vowels }
        if (!hasVowels && name.length > 3) return true

        val genericPatterns = listOf(
            Regex("^[0-9a-f]{6,}$"),
            Regex("^\\d+$"),
            Regex("^(doc|file|document|archivo|descarga|download|untitled|unnamed)\\d*$"),
            Regex("^(img|image|foto|photo|pic|screenshot|captura)\\d*$"),
            Regex("^[a-z]{1,3}\\d{4,}$"),
            Regex("^\\d{8,}$"),
            Regex("^[a-z0-9]{8,}$")
        )

        val letters = name.filter { it.isLetter() }
        if (letters.length > 4) {
            val consonantRatio = letters.count { it !in vowels }.toFloat() / letters.length
            if (consonantRatio > 0.85f) return true
        }

        return genericPatterns.any { it.matches(name) }
    }

    // ═══════════════════════════════════════════════════════════════
    // GENERACION DE EMBEDDINGS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun generateEmbeddingsForAll(
        documents: List<DocumentInfo>
    ): Map<Long, FloatArray> {
        val result = mutableMapOf<Long, FloatArray>()

        // Si Gemini no esta configurado, usar clustering basico
        if (!geminiService.isConfigured()) {
            _progress.value = "Gemini no configurado - usando clustering basico..."
            documents.forEach { doc ->
                result[doc.id] = doc.contentPreview.toByteArray(Charsets.UTF_8).let { bytes ->
                    FloatArray(128) { i -> if (i < bytes.size) bytes[i].toFloat() / 255f else 0f }
                }
            }
            return result
        }

        documents.forEachIndexed { idx, doc ->
            _progress.value = "Embeddings: ${idx + 1}/${documents.size}..."

            val text = "${doc.fileName} ${doc.contentPreview}".take(2048)
            geminiService.getEmbedding(text)?.let { emb ->
                result[doc.id] = emb
            }
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // CLUSTERING POR SIMILITUD COSENO
    // ═══════════════════════════════════════════════════════════════

    private fun clusterBySimilarity(
        documents: List<DocumentInfo>,
        embeddings: Map<Long, FloatArray>,
        threshold: Float = 0.55f
    ): List<List<DocumentInfo>> {
        if (embeddings.isEmpty()) {
            return listOf(documents)
        }

        val assigned = mutableSetOf<Long>()
        val clusters = mutableListOf<MutableList<DocumentInfo>>()

        for (doc in documents) {
            if (doc.id in assigned) continue

            val cluster = mutableListOf(doc)
            assigned.add(doc.id)

            val docEmb = embeddings[doc.id] ?: continue

            for (other in documents) {
                if (other.id in assigned) continue
                val otherEmb = embeddings[other.id] ?: continue

                val similarity = cosineSimilarity(docEmb, otherEmb)
                if (similarity >= threshold) {
                    cluster.add(other)
                    assigned.add(other.id)
                }
            }

            clusters.add(cluster)
        }

        val unassigned = documents.filter { it.id !in assigned }
        if (unassigned.isNotEmpty()) clusters.add(unassigned.toMutableList())

        val singletons = clusters.filter { it.size == 1 }.flatten()
        val multiClusters = clusters.filter { it.size > 1 }.toMutableList()
        if (singletons.isNotEmpty()) multiClusters.add(singletons.toMutableList())

        return multiClusters
    }

    // ═══════════════════════════════════════════════════════════════
    // NOMBRAR CLUSTERS CON GEMINI
    // ═══════════════════════════════════════════════════════════════

    private suspend fun nameClustersWithGemini(
        rawClusters: List<List<DocumentInfo>>
    ): List<FileCluster> {
        return rawClusters.mapIndexed { idx, docs ->
            _progress.value = "Nombrando grupo ${idx + 1}/${rawClusters.size}..."

            val suggestedName = askGeminiForFolderName(docs) ?: generateFallbackName(docs, idx)

            FileCluster(
                id = UUID.randomUUID().toString(),
                suggestedFolderName = sanitizeFolderName(suggestedName),
                documents = docs
            )
        }
    }

    private suspend fun askGeminiForFolderName(docs: List<DocumentInfo>): String? {
        if (!geminiService.isConfigured()) return null

        val documentContexts = docs.map { doc ->
            DocumentContext(
                fileName = doc.fileName,
                contentPreview = doc.contentPreview
            )
        }

        return geminiService.askForFolderName(documentContexts)
    }

    private fun generateFallbackName(docs: List<DocumentInfo>, idx: Int): String {
        val words = docs.flatMap { doc ->
            doc.fileName.substringBeforeLast(".")
                .replace(Regex("[_\\-.]"), " ")
                .split(" ")
                .filter { it.length > 3 && !isGenericName("$it.x") }
        }

        val mostCommon = words.groupingBy { it.lowercase() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        return mostCommon?.replaceFirstChar { it.uppercase() } ?: "Grupo ${idx + 1}"
    }

    private fun sanitizeFolderName(name: String): String {
        return name
            .replace(Regex("[/\\\\:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(50)
            .ifEmpty { "Carpeta sin nombre" }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
