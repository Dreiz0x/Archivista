package com.nexus.intelligence.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.nexus.intelligence.data.gemini.GeminiService
import com.nexus.intelligence.data.local.dao.DocumentDao
import com.nexus.intelligence.data.local.database.NexusDatabase
import com.nexus.intelligence.data.parser.DocumentParser
import com.nexus.intelligence.data.repository.DocumentRepositoryImpl
import com.nexus.intelligence.domain.repository.DocumentRepository
import com.nexus.intelligence.domain.usecase.*
import com.nexus.intelligence.service.network.NexusLocalServer
import com.nexus.intelligence.service.network.NexusNetworkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNexusDatabase(@ApplicationContext context: Context): NexusDatabase {
        return Room.databaseBuilder(context, NexusDatabase::class.java, NexusDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideDocumentDao(database: NexusDatabase): DocumentDao = database.documentDao()

    @Provides
    @Singleton
    fun provideDocumentParser(@ApplicationContext context: Context): DocumentParser =
        DocumentParser(context)

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("archivista_settings", Context.MODE_PRIVATE)

    @Provides
    @Singleton
    fun provideGeminiService(
        @ApplicationContext context: Context,
        sharedPreferences: SharedPreferences
    ): GeminiService = GeminiService(context, sharedPreferences)

    @Provides
    @Singleton
    fun provideNexusNetworkManager(): NexusNetworkManager = NexusNetworkManager()

    @Provides
    @Singleton
    fun provideDocumentRepository(
        documentDao: DocumentDao,
        documentParser: DocumentParser,
        geminiService: GeminiService
    ): DocumentRepository = DocumentRepositoryImpl(documentDao, documentParser, geminiService)

    @Provides
    @Singleton
    fun provideNexusLocalServer(
        repository: DocumentRepository,
        networkManager: NexusNetworkManager
    ): NexusLocalServer = NexusLocalServer(repository, networkManager)

    @Provides
    @Singleton
    fun provideSearchDocumentsUseCase(repository: DocumentRepository) =
        SearchDocumentsUseCase(repository)

    @Provides
    @Singleton
    fun provideIndexDocumentsUseCase(repository: DocumentRepository) =
        IndexDocumentsUseCase(repository)

    @Provides
    @Singleton
    fun provideGetDashboardStatsUseCase(repository: DocumentRepository) =
        GetDashboardStatsUseCase(repository)

    @Provides
    @Singleton
    fun provideGetFileMapUseCase(repository: DocumentRepository) =
        GetFileMapUseCase(repository)

    @Provides
    @Singleton
    fun provideManageSettingsUseCase(repository: DocumentRepository) =
        ManageSettingsUseCase(repository)

    @Provides
    @Singleton
    fun provideFileOrganizerUseCase(
        repository: DocumentRepository,
        geminiService: GeminiService,
        documentDao: DocumentDao
    ) = FileOrganizerUseCase(repository, geminiService, documentDao)

    @Provides
    @Singleton
    fun provideFileManagerUseCase(
        documentDao: DocumentDao,
        organizerUseCase: FileOrganizerUseCase
    ) = FileManagerUseCase(documentDao, organizerUseCase)
}
