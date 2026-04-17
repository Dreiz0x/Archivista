package com.nexus.intelligence.domain.repository

import com.nexus.intelligence.data.local.entity.IndexingStatsEntity
import com.nexus.intelligence.data.local.entity.MonitoredFolderEntity
import com.nexus.intelligence.data.local.entity.SearchHistoryEntity
import com.nexus.intelligence.domain.model.DocumentInfo
import com.nexus.intelligence.domain.model.SearchResult
import kotlinx.coroutines.flow.Flow
import java.io.File

interface DocumentRepository {
    // Documentos
    fun getAllDocuments(): Flow<List<DocumentInfo>>
    fun getRecentDocuments(limit: Int): Flow<List<DocumentInfo>>
    fun getDocumentsByType(type: String): Flow<List<DocumentInfo>>
    fun getDocumentsByDirectory(directory: String): Flow<List<DocumentInfo>>
    fun getDocumentCount(): Flow<Int>
    fun getAllDirectories(): Flow<List<String>>
    suspend fun getDocumentById(id: Long): DocumentInfo?
    suspend fun getDocumentByPath(path: String): DocumentInfo? // Requerido por UseCases
    suspend fun updateDocument(document: DocumentInfo)        // Requerido por UseCases

    // Indexación
    suspend fun indexFile(file: File): DocumentInfo?
    suspend fun removeDeletedFiles()
    suspend fun clearIndex()
    suspend fun deleteByPath(path: String)
    suspend fun getAllFilePaths(): List<String>

    // Búsqueda e IA
    suspend fun textSearch(query: String): List<SearchResult>
    suspend fun semanticSearch(query: String): List<SearchResult>
    suspend fun generateEmbeddingsForDocument(docId: Long): Boolean
    suspend fun isApiAvailable(): Boolean

    // Carpetas e Historial (Con parámetros correctos)
    fun getMonitoredFolders(): Flow<List<MonitoredFolderEntity>>
    suspend fun addMonitoredFolder(path: String, label: String)
    suspend fun removeMonitoredFolder(path: String)
    suspend fun addSearchHistory(query: String, resultCount: Int)
    fun getSearchHistory(): Flow<List<SearchHistoryEntity>>

    // Control de Escaneo (Requerido por ViewModels y MainActivity)
    fun getIndexingStats(): Flow<IndexingStatsEntity?>
    suspend fun updateIndexingStats(stats: IndexingStatsEntity)
    fun startScan()
    fun stop()
    val isCurrentlyScanning: Flow<Boolean>
    val lastScanTimestamp: Flow<Long>
}
