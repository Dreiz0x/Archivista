package com.nexus.intelligence.domain.model

data class DocumentInfo(
    val id: Long,
    val filePath: String,
    val fileName: String,
    val fileType: String,
    val fileSize: Long,
    val lastModified: Long,
    val indexedAt: Long,
    val contentPreview: String,
    val parentDirectory: String,
    val mimeType: String,
    val pageCount: Int,
    val isFromNetwork: Boolean = false,
    val networkSourceDevice: String? = null,
    val hasEmbedding: Boolean = false,
    val isFavorite: Boolean = false // <--- Aquí está la pieza que faltaba
) {
    val fileSizeFormatted: String
        get() {
            val kb = fileSize / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.1f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$fileSize B"
            }
        }

    val fileTypeIcon: String
        get() = when (fileType) {
            "PDF" -> "📄"
            "WORD" -> "📝"
            "EXCEL" -> "📊"
            "POWERPOINT" -> "📽"
            "IMAGE" -> "🖼"
            "TEXT" -> "📃"
            "CSV" -> "📈"
            else -> "📁"
        }
}

data class SearchResult(
    val document: DocumentInfo,
    val relevanceScore: Float,
    val matchedSnippet: String,
    val searchType: String // "TEXT" or "SEMANTIC"
) {
    val relevancePercentage: Int
        get() = (relevanceScore * 100).toInt().coerceIn(0, 100)
}

data class IndexingProgress(
    val totalFiles: Int = 0,
    val processedFiles: Int = 0,
    val currentFile: String = "",
    val isRunning: Boolean = false,
    val errors: List<String> = emptyList()
) {
    val progressFraction: Float
        get() = if (totalFiles > 0) processedFiles.toFloat() / totalFiles else 0f
    val progressPercent: Int
        get() = (progressFraction * 100).toInt()
}

data class DashboardStats(
    val totalDocuments: Int = 0,
    val documentsByType: Map<String, Int> = emptyMap(),
    val lastScanTime: Long = 0,
    val isScanning: Boolean = false,
    val apiOnline: Boolean = false,
    val networkNodes: Int = 0,
    val activeWatchers: Int = 0,
    val storageUsed: Long = 0
)

data class DirectoryNode(
    val path: String,
    val name: String,
    val documentCount: Int = 0,
    val totalSize: Long = 0,
    val children: List<DirectoryNode> = emptyList(),
    val depth: Int = 0
)

data class NetworkDevice(
    val name: String,
    val address: String,
    val port: Int,
    val documentCount: Int = 0,
    val isConnected: Boolean = false
)

data class AppSettings(
    val apiEndpoint: String = "http://127.0.0.1:8080",
    val soundEffectsEnabled: Boolean = true,
    val serverEnabled: Boolean = false,
    val serverPort: Int = 9090,
    val autoIndexEnabled: Boolean = true,
    val indexInterval: Long = 30 // minutes
)
