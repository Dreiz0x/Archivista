package com.nexus.intelligence.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.intelligence.data.gemini.GeminiService
import com.nexus.intelligence.data.local.entity.MonitoredFolderEntity
import com.nexus.intelligence.domain.usecase.ManageSettingsUseCase
import com.nexus.intelligence.service.indexing.IndexingService
import com.nexus.intelligence.service.network.NexusLocalServer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences,
    private val manageSettingsUseCase: ManageSettingsUseCase,
    private val geminiService: GeminiService,
    private val localServer: NexusLocalServer
) : ViewModel() {

    // ═══════════════════════════════════════════════════════════════
    // ESTADOS (STATEFLOW)
    // ═══════════════════════════════════════════════════════════════

    private val _geminiApiKey = MutableStateFlow(geminiService.getApiKey())
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _monitoredFolders = MutableStateFlow<List<MonitoredFolderEntity>>(emptyList())
    val monitoredFolders: StateFlow<List<MonitoredFolderEntity>> = _monitoredFolders.asStateFlow()

    private val _soundEnabled = MutableStateFlow(prefs.getBoolean("sound_enabled", true))
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _serverEnabled = MutableStateFlow(prefs.getBoolean("server_enabled", false))
    val serverEnabled: StateFlow<Boolean> = _serverEnabled.asStateFlow()

    private val _serverPort = MutableStateFlow(prefs.getString("server_port", "9090") ?: "9090")
    val serverPort: StateFlow<String> = _serverPort.asStateFlow()

    private val _autoIndexEnabled = MutableStateFlow(prefs.getBoolean("auto_index", true))
    val autoIndexEnabled: StateFlow<Boolean> = _autoIndexEnabled.asStateFlow()

    private val _apiStatus = MutableStateFlow(false)
    val apiStatus: StateFlow<Boolean> = _apiStatus.asStateFlow()

    private val _newFolderPath = MutableStateFlow("")
    val newFolderPath: StateFlow<String> = _newFolderPath.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        viewModelScope.launch {
            manageSettingsUseCase.getMonitoredFolders().collect { folders ->
                _monitoredFolders.value = folders
            }
        }
        testApiConnection()
    }

    // ═══════════════════════════════════════════════════════════════
    // GEMINI API KEY CONFIGURACIÓN
    // ═══════════════════════════════════════════════════════════════

    fun updateGeminiApiKey(apiKey: String) {
        _geminiApiKey.value = apiKey
        geminiService.setApiKey(apiKey)
        // 🔥 Crucial: Re-probamos la conexión en cuanto el usuario actualiza la key
        testApiConnection()
    }

    fun testApiConnection() {
        viewModelScope.launch {
            val keyActual = _geminiApiKey.value
            if (keyActual.isBlank()) {
                _snackbarMessage.value = "⚠️ La API Key está vacía en el ViewModel."
                _apiStatus.value = false
                return@launch
            }

            _snackbarMessage.value = "⏳ Probando conexión a Gemini..."
            val success = geminiService.isApiAvailable()
            _apiStatus.value = success
            
            if (success) {
                _snackbarMessage.value = "✅ ¡Conexión exitosa, bato!"
            } else {
                _snackbarMessage.value = "❌ Falló la conexión. ¿Llave incorrecta o sin internet?"
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GESTIÓN DE CARPETAS
    // ═══════════════════════════════════════════════════════════════

    fun updateNewFolderPath(path: String) {
        _newFolderPath.value = path
    }

    fun addMonitoredFolder() {
        val path = _newFolderPath.value.trim()
        if (path.isBlank()) { _snackbarMessage.value = "Escribe una ruta primero"; return }
        val dir = File(path)
        if (!dir.exists()) { _snackbarMessage.value = "Ruta no existe: $path"; return }
        if (!dir.isDirectory) { _snackbarMessage.value = "No es una carpeta: $path"; return }

        viewModelScope.launch {
            manageSettingsUseCase.addMonitoredFolder(path, path.substringAfterLast("/"))
            _newFolderPath.value = ""
            _snackbarMessage.value = "Carpeta agregada"
            if (_autoIndexEnabled.value) {
                IndexingService.startScan(context, listOf(path))
            }
        }
    }

    fun removeMonitoredFolder(path: String) {
        viewModelScope.launch { manageSettingsUseCase.removeMonitoredFolder(path) }
    }

    // ═══════════════════════════════════════════════════════════════
    // PREFERENCIAS Y SERVIDOR
    // ═══════════════════════════════════════════════════════════════

    fun toggleServer(enabled: Boolean) {
        _serverEnabled.value = enabled
        prefs.edit().putBoolean("server_enabled", enabled).apply()
        if (enabled) localServer.start(_serverPort.value.toIntOrNull() ?: 9090)
        else localServer.stop()
    }

    fun updateServerPort(port: String) {
        _serverPort.value = port
        prefs.edit().putString("server_port", port).apply()
    }

    fun toggleAutoIndex(enabled: Boolean) {
        _autoIndexEnabled.value = enabled
        prefs.edit().putBoolean("auto_index", enabled).apply()
        if (enabled) {
            val folders = _monitoredFolders.value.filter { it.isEnabled }.map { it.path }
            if (folders.isNotEmpty()) IndexingService.startScan(context, folders)
            else _snackbarMessage.value = "Agrega carpetas para monitorear primero"
        } else {
            IndexingService.stop(context)
        }
    }

    fun toggleSound(enabled: Boolean) {
        _soundEnabled.value = enabled
        prefs.edit().putBoolean("sound_enabled", enabled).apply()
    }

    fun clearIndex() {
        viewModelScope.launch {
            manageSettingsUseCase.clearIndex()
            _snackbarMessage.value = "Índice limpiado"
        }
    }

    fun clearSnackbar() { _snackbarMessage.value = null }
}

