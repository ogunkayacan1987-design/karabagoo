package com.lgsextractor.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.lgsextractor.data.local.database.AppDatabase
import com.lgsextractor.data.local.database.dao.CorrectionDao
import com.lgsextractor.data.local.database.dao.PdfDocumentDao
import com.lgsextractor.data.local.database.dao.QuestionDao
import com.lgsextractor.data.repository.PdfRepositoryImpl
import com.lgsextractor.data.repository.QuestionRepositoryImpl
import com.lgsextractor.domain.repository.PdfRepository
import com.lgsextractor.domain.repository.QuestionRepository
import com.lgsextractor.util.FileUtils
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideQuestionDao(db: AppDatabase): QuestionDao = db.questionDao()
    @Provides fun providePdfDocumentDao(db: AppDatabase): PdfDocumentDao = db.pdfDocumentDao()
    @Provides fun provideCorrectionDao(db: AppDatabase): CorrectionDao = db.correctionDao()

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideFileUtils(@ApplicationContext context: Context): FileUtils = FileUtils(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPdfRepository(impl: PdfRepositoryImpl): PdfRepository

    @Binds
    @Singleton
    abstract fun bindQuestionRepository(impl: QuestionRepositoryImpl): QuestionRepository
}
