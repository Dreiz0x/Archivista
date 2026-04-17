package com.nexus.intelligence.domain.usecase

import com.nexus.intelligence.data.local.entity.IndexingStatsEntity
import com.nexus.intelligence.data.parser.DocumentParser
import com.nexus.intelligence.domain.model.DocumentInfo
import com.nexus.intelligence.domain.model.IndexingProgress
import com.nexus.intelligence.domain.model.SearchResult
import com.nexus.intelligence.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

// ── Search Documents Use Case ────────────────────────────────────

class SearchDocumentsUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    suspend fun executeText(query: String): List<SearchResult> {
        val results = repository.textSearch(query)
        repository.addSearchHistory(query, results.size)
        return results
    }

    suspend fun executeSemantic(query: String): List<SearchResult> {
        val results = repository.semanticSearch(query)
        repository.addSearchHistory(query, results.size)
        return results
    }
}

// ── Index Documents Use Case ─────────────────────────────────────

class IndexDocumentsUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    private val _progress = MutableStateFlow(IndexingProgress())
    val progress: Flow<IndexingProgress> = _progress.asStateFlow()

    suspend fun indexDirectory(directory: File): Int {
        if (!directory.exists() || !directory.isDirectory) return 0

        val files = collectSupportedFiles(directory)
        val totalFiles = files.size

        _progress.value = IndexingProgress(
            totalFiles = totalFiles,
            processedFiles = 0,
            isRunning = true
        )

        var indexed = 0
        val errors = mutableListOf<String>()

        for ((index, file) in files.withIndex()) {
            try {
                _progress.value = _progress.value.copy(
                    processedFiles = index + 1,
                    currentFile = file.name
                )
                val result = repository.indexFile(file)
                if (result != null) indexed++
            } catch (e: Exception) {
                errors.add("${file.name}: ${e.message}")
            }
        }

        _progress.value = IndexingProgress(
            totalFiles = totalFiles,
            processedFiles = totalFiles,
            isRunning = false,
            errors = errors
        )

        return indexed
    }

    suspend fun indexSingleFile(file: File): DocumentInfo? {
        return repository.indexFile(file)
    }

    suspend fun fullScan(directories: List<String>): Int {
        val startTime = System.currentTimeMillis()
        var totalIndexed = 0

        for (dirPath in directories) {
            val dir = File(dirPath)
            totalIndexed += indexDirectory(dir)
        }

        // Clean up deleted files
        repository.removeDeletedFiles()

        // Update stats
        val duration = System.currentTimeMillis() - startTime
        val existingStats = repository.getIndexingStats() as? Flow
        repository.updateIndexingStats(
            IndexingStatsEntity(
                totalFiles = totalIndexed,
                lastScanTimestamp = System.currentTimeMillis(),
                lastScanDurationMs = duration,
                isCurrentlyScanning = false
            )
        )

        return totalIndexed
    }

    private fun collectSupportedFiles(directory: File): List<File> {
        val files = mutableListOf<File>()
        try {
            directory.walkTopDown()
                .filter { it.isFile }
                .filter { it.extension.lowercase() in DocumentParser.SUPPORTED_EXTENSIONS }
                .filter { it.length() > 0 }
                .filter { it.length() < 100 * 1024 * 1024 } // Skip files > 100MB
                .filter { !it.name.startsWith(".") } // Skip hidden files
                .forEach { files.add(it) }
        } catch (e: SecurityException) {
            // Permission denied for this directory
        }
        return files
    }
}

// ── Get Dashboard Stats Use Case ─────────────────────────────────

class GetDashboardStatsUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    fun getDocumentCount(): Flow<Int> = repository.getDocumentCount()
    fun getIndexingStats(): Flow<IndexingStatsEntity?> = repository.getIndexingStats()
    fun getAllDocuments(): Flow<List<DocumentInfo>> = repository.getAllDocuments()
    fun getRecentDocuments(limit: Int = 50): Flow<List<DocumentInfo>> = repository.getRecentDocuments(limit)
    suspend fun isApiAvailable(): Boolean = repository.isApiAvailable()
}

// ── Get File Map Use Case ────────────────────────────────────────

class GetFileMapUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    fun getAllDirectories(): Flow<List<String>> = repository.getAllDirectories()
    fun getDocumentsByDirectory(directory: String): Flow<List<DocumentInfo>> =
        repository.getDocumentsByDirectory(directory)
}

// ── Manage Settings Use Case ─────────────────────────────────────

class ManageSettingsUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    fun getMonitoredFolders() = repository.getMonitoredFolders()

    suspend fun addMonitoredFolder(path: String, label: String = "") {
        repository.addMonitoredFolder(path, label)
    }

    suspend fun removeMonitoredFolder(path: String) {
        repository.removeMonitoredFolder(path)
    }

    suspend fun clearIndex() {
        repository.clearIndex()
    }
}
