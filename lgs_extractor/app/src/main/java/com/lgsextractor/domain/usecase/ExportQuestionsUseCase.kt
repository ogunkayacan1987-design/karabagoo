package com.lgsextractor.domain.usecase

import android.content.Context
import com.lgsextractor.domain.model.Question
import com.lgsextractor.processing.export.CropEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

class ExportQuestionsUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cropEngine: CropEngine
) {
    data class ExportConfig(
        val outputDir: File,
        val prefix: String = "soru",
        val quality: Int = 90,
        val createZip: Boolean = false,
        val groupByPage: Boolean = false
    )

    sealed class ExportResult {
        data class Success(val exportedFiles: List<File>, val zipFile: File? = null) : ExportResult()
        data class Error(val message: String) : ExportResult()
    }

    fun execute(
        questions: List<Question>,
        config: ExportConfig
    ): Flow<Pair<Int, ExportResult>> = flow {
        val exportedFiles = mutableListOf<File>()

        config.outputDir.mkdirs()

        questions.forEachIndexed { index, question ->
            val fileName = "${config.prefix}_${String.format("%03d", question.questionNumber)}_s${question.pageNumber + 1}.jpg"
            val outputFile = if (config.groupByPage) {
                val pageDir = File(config.outputDir, "sayfa_${question.pageNumber + 1}")
                pageDir.mkdirs()
                File(pageDir, fileName)
            } else {
                File(config.outputDir, fileName)
            }

            val cropResult = cropEngine.exportToFile(question, outputFile, config.quality)
            cropResult.onSuccess { exportedFiles.add(it) }
                .onFailure {
                    emit(index to ExportResult.Error("Soru ${question.questionNumber} export edilemedi: ${it.message}"))
                    return@forEachIndexed
                }

            emit(index to ExportResult.Success(exportedFiles.toList()))
        }

        if (config.createZip && exportedFiles.isNotEmpty()) {
            val zipFile = File(config.outputDir.parent, "${config.outputDir.name}.zip")
            createZip(exportedFiles, zipFile)
            emit(questions.size to ExportResult.Success(exportedFiles, zipFile))
        } else {
            emit(questions.size to ExportResult.Success(exportedFiles))
        }
    }

    private fun createZip(files: List<File>, zipFile: File) {
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            files.forEach { file ->
                zos.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
