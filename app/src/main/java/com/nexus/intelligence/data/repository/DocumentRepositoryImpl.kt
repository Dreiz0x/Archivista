package com.nexus.intelligence.data.repository

import com.nexus.intelligence.data.local.dao.DocumentDao
import com.nexus.intelligence.data.local.entity.*
import com.nexus.intelligence.data.parser.DocumentParser
import com.nexus.intelligence.data.gemini.GeminiService
import com.nexus.intelligence.data.gemini.DocumentContext
import com.nexus.intelligence.domain.model.DocumentInfo
import com.nexus.intelligence.domain.model.SearchResult
import com.nexus.intelligence.domain.repository.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val documentParser: DocumentParser,
    private val geminiService: GeminiService
) : DocumentRepository {

    private val _isScanning = MutableStateFlow(false)
    override val isCurrentlyScanning: Flow<Boolean> = _isScanning

    private val _lastScan = MutableStateFlow(System.currentTimeMillis())
    override val lastScanTimestamp: Flow<Long> = _lastScan

    override fun getAllDocuments(): Flow<List<DocumentInfo>> =
        documentDao.getAllDocuments().map { list -> list.map { it.toDomainModel() } }

    override fun getRecentDocuments(limit: Int): Flow<List<DocumentInfo>> =
        documentDao.getRecentDocuments(limit).map { list -> list.map { it.toDomainModel() } }

    override fun getDocumentsByType(type: String): Flow<List<DocumentInfo>> =
        documentDao.getDocumentsByType(type).map { list -> list.map { it.toDomainModel() } }

    override fun getDocumentsByDirectory(directory: String): Flow<List<DocumentInfo>> =
        documentDao.getDocumentsByDirectory(directory).map { list -> list.map { it.toDomainModel() } }

    override fun getDocumentCount(): Flow<Int> = documentDao.getDocumentCount()

    override fun getAllDirectories(): Flow<List<String>> = documentDao.getAllDirectories()

    override suspend fun getDocumentById(id: Long): DocumentInfo? =
        documentDao.getDocumentById(id)?.toDomainModel()

    override suspend fun getDocumentByPath(path: String): DocumentInfo? =
        documentDao.getDocumentByPath(path)?.toDomainModel()

    override suspend fun updateDocument(document: DocumentInfo) {
        // Puedes mapear a Entity si luego necesitas update real
    }

    override suspend fun getAllFilePaths(): List<String> =
        documentDao.getAllFilePaths()

    override suspend fun deleteByPath(path: String) =
        documentDao.deleteByPath(path)

    override suspend fun indexFile(file: File): DocumentInfo? = withContext(Dispatchers.IO) {
        val parseResult = documentParser.parseFile(file)
        if (!parseResult.success) return@withContext null

        // Obtener MIME type correcto basado en la extension
        val mimeType = getMimeTypeFromExtension(file.extension)

        val entity = DocumentEntity(
            filePath = file.absolutePath,
            fileName = file.name,
            fileType = DocumentParser.getDocumentTypeLabel(file),
            fileSize = file.length(),
            lastModified = file.lastModified(),
            indexedAt = System.currentTimeMillis(),
            contentPreview = parseResult.text.take(500),
            parentDirectory = file.parent ?: "",
            mimeType = mimeType,
            pageCount = parseResult.pageCount
        )

        val id = documentDao.insertDocument(entity)

        documentDao.insertDocumentContent(
            DocumentContentEntity(
                documentId = id,
                fullTextContent = parseResult.text
            )
        )

        entity.copy(id = id).toDomainModel()
    }

    override suspend fun removeDeletedFiles() {
        val paths = documentDao.getAllFilePaths()
        paths.forEach {
            if (!File(it).exists()) {
                documentDao.deleteByPath(it)
            }
        }
    }

    override suspend fun clearIndex() {
        documentDao.deleteAllDocuments()
        documentDao.deleteAllDocumentContent()
    }

    override suspend fun textSearch(query: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            documentDao.searchDocuments(query).map {
                    SearchResult(
                        document = it.toDomainModel(),
                        relevanceScore = 1.0f,
                        matchedSnippet = it.contentPreview,
                        searchType = "TEXT"
                    )
            }
        }

    override suspend fun semanticSearch(query: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            // Si Gemini no esta configurado, caer a busqueda de texto
            if (!geminiService.isConfigured()) {
                return@withContext textSearch(query)
            }

            try {
                // Obtener embedding de la consulta
                val queryEmbedding = geminiService.getEmbedding(query) ?: return@withContext textSearch(query)

                // Obtener documentos de la base de datos
                val documents = documentDao.getAllDocuments().first()
                if (documents.isEmpty()) return@withContext emptyList()

                // Calcular similaridad para cada documento
                val scoredDocs = documents.mapNotNull { doc ->
                    val docEmbedding = geminiService.getEmbedding(doc.contentPreview)
                        ?: return@mapNotNull null
                    val similarity = geminiService.cosineSimilarity(queryEmbedding, docEmbedding)
                    Triple(doc.toDomainModel(), similarity, doc.contentPreview)
                }.sortedByDescending { it.second }
                    .take(20)

                scoredDocs.map { (doc, score, snippet) ->
                    SearchResult(
                        document = doc,
                        relevanceScore = score,
                        matchedSnippet = snippet,
                        searchType = "SEMANTIC"
                    )
                }
            } catch (e: Exception) {
                // En caso de error, caer a busqueda de texto
                textSearch(query)
            }
        }

    override suspend fun generateEmbeddingsForDocument(docId: Long): Boolean = true

    // ═══════════════════════════════════════════════════════════════
    // METODOS AUXILIARES
    // ═══════════════════════════════════════════════════════════════

    private fun getMimeTypeFromExtension(extension: String): String {
        return when (extension.lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "csv" -> "text/csv"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "tiff", "tif" -> "image/tiff"
            else -> "application/octet-stream"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MONITORED FOLDERS
    // ═══════════════════════════════════════════════════════════════

    override fun getMonitoredFolders(): Flow<List<MonitoredFolderEntity>> =
        documentDao.getAllMonitoredFolders()

    override suspend fun addMonitoredFolder(path: String, label: String) {
        documentDao.insertMonitoredFolder(
            MonitoredFolderEntity(
                path = path,
                label = label
            )
        )
    }

    override suspend fun removeMonitoredFolder(path: String) =
        documentDao.deleteMonitoredFolderByPath(path)

    // ═══════════════════════════════════════════════════════════════
    // HISTORIAL / STATS
    // ═══════════════════════════════════════════════════════════════

    override suspend fun addSearchHistory(query: String, resultCount: Int) {
        documentDao.insertSearchHistory(
            SearchHistoryEntity(
                query = query,
                resultCount = resultCount
            )
        )
    }

    override fun getSearchHistory(): Flow<List<SearchHistoryEntity>> =
        documentDao.getRecentSearches()

    override fun getIndexingStats(): Flow<IndexingStatsEntity?> =
        documentDao.getIndexingStats()

    override suspend fun updateIndexingStats(stats: IndexingStatsEntity) =
        documentDao.updateIndexingStats(stats)

    // ═══════════════════════════════════════════════════════════════
    // CONTROL
    // ═══════════════════════════════════════════════════════════════

    override fun startScan() {
        _isScanning.value = true
    }

    override fun stop() {
        _isScanning.value = false
    }

    override suspend fun isApiAvailable(): Boolean =
        geminiService.isApiAvailable()
}

// ═══════════════════════════════════════════════════════════════
// MAPPER
// ═══════════════════════════════════════════════════════════════

fun DocumentEntity.toDomainModel() = DocumentInfo(
    id = id,
    filePath = filePath,
    fileName = fileName,
    fileType = fileType,
    fileSize = fileSize,
    lastModified = lastModified,
    indexedAt = indexedAt,
    contentPreview = contentPreview,
    parentDirectory = parentDirectory,
    mimeType = mimeType,
    pageCount = pageCount,
    isFavorite = false,
    hasEmbedding = false
)
