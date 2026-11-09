package net.ankio.ledger.capture

import android.net.Uri
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.ocr.RecognitionException

sealed class ImageImportState {
    data object Idle : ImageImportState()
    data object Working : ImageImportState()
    data class Finished(val result: IngestResult) : ImageImportState()
    data class Failed(val message: String) : ImageImportState()
}

/** Imports survive Activity recreation. The original Uri is used only for the duration of the job. */
class ImageImporter(private val app: LedgerApp, private val client: net.ankio.ledger.ocr.OcrClient = app.recognizer) {
    val state = MutableStateFlow<ImageImportState>(ImageImportState.Idle)
    @Volatile var previewBytes: ByteArray? = null
        private set
    private var job: kotlinx.coroutines.Job? = null
    @Volatile private var generation = 0L
    @Volatile var activeTask:Long?=null
        private set

    @Synchronized fun cancel() {
        generation++; job?.cancel(); job = null; previewBytes = null
        activeTask?.let { app.tasks.update(it,"CANCELLED","识别已停止，可以重新导入截图");app.changed() };activeTask=null
        state.value = ImageImportState.Idle
    }

    @Synchronized fun start(uri: Uri) = app.recognitionWork.admit(Unit) {
        if (state.value == ImageImportState.Working) return@admit
        val token = ++generation
        val recognitionGeneration = app.recognitionSession.generation
        previewBytes = null
        val taskId=app.tasks.create(Source.IMAGE,"image");activeTask=taskId;app.changed()
        state.value = ImageImportState.Working
        job = app.recognitionWork.launch {
            try {
                val preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 1800) decoder.setTargetSize(maxOf(1, info.size.width * 1800 / longest), maxOf(1, info.size.height * 1800 / longest))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try {
                    previewBytes = ByteArrayOutputStream().use { output ->
                        preview.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray()
                    }
                    app.changed()
                    val result = CaptureProcessor(app, client).process(preview, Source.IMAGE, "image",taskId) { generation == token && app.recognitionSession.generation == recognitionGeneration }
                    if (generation == token) state.value = ImageImportState.Finished(result)
                } finally { preview.recycle() }
            } catch (cancelled: CancellationException) { app.tasks.update(taskId,"CANCELLED","识别已停止，可以重新导入截图");app.changed();throw cancelled }
            catch (error: Exception) {
                val message=(error as? RecognitionException)?.userMessage ?: "识别失败，请换一张清晰截图重试"
                app.tasks.update(taskId,"FAILED",message);app.changed()
                if (generation == token) state.value = ImageImportState.Failed(message)
            } finally { if(generation==token)activeTask=null }
        }
    }
}
