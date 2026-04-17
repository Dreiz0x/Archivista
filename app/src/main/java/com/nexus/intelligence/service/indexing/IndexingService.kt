package com.nexus.intelligence.service.indexing

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.nexus.intelligence.domain.usecase.IndexDocumentsUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class IndexingService : Service() {

    @Inject
    lateinit var indexDocumentsUseCase: IndexDocumentsUseCase

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val dirs = intent?.getStringArrayListExtra("directories") ?: return@launch
            indexDocumentsUseCase.fullScan(dirs)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
    
    companion object {
        fun startScan(context: Context, directories: List<String>) {
            val intent = Intent(context, IndexingService::class.java).apply {
                putStringArrayListExtra("directories", ArrayList(directories))
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IndexingService::class.java))
        }
    }
}
