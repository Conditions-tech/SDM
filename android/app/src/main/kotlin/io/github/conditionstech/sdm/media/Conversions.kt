package io.github.conditionstech.sdm.media

import io.github.conditionstech.sdm.downloads.Publisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** What the converter can make (ADR-0023). */
enum class ConvertFormat(val extension: String, val label: String) {
    MP4("mp4", "MP4 video (no quality loss)"),
    M4A("m4a", "M4A audio (no quality loss)"),
    MP3("mp3", "MP3 audio"),
}

/** A conversion's message for the user; the converter throws it for files it can't handle. */
class ConversionException(message: String) : Exception(message)

/** A finished download to convert: its MediaStore address and the name shown to the user. */
data class ConvertInput(val uri: String, val name: String)

/** Turns one file into another format (Android's media tools on the phone, a fake in tests). */
interface MediaConverter {
    /** Writes the result to [output]; reports progress from 0 to 1. Cancellable. */
    suspend fun convert(input: ConvertInput, format: ConvertFormat, output: File, onProgress: (Float) -> Unit)
}

enum class ConversionState { RUNNING, COMPLETED, FAILED, CANCELLED }

data class Conversion(
    val id: Long,
    val sourceName: String,
    val format: ConvertFormat,
    val state: ConversionState = ConversionState.RUNNING,
    val progress: Float = 0f,
    val savedName: String? = null,
    val error: String? = null,
) {
    val targetName: String get() = outputName(sourceName, format)
}

/** "clip.webm" + MP3 -> "clip.mp3". Download/SDM numbers a taken name ("clip (1).mp3"). */
fun outputName(sourceName: String, format: ConvertFormat): String {
    val dot = sourceName.lastIndexOf('.')
    val stem = if (dot > 0) sourceName.substring(0, dot) else sourceName
    return "$stem.${format.extension}"
}

/**
 * The conversions shown above the download list (like the Windows Convert window). They are not
 * kept in the history: a conversion stopped by closing the app is simply started again. The
 * result goes to Download/SDM like a download; the source file is never changed or opened.
 */
class Conversions(
    private val converter: MediaConverter,
    private val publisher: Publisher,
    private val workDir: File,
    private val scope: CoroutineScope,
    private val onActive: () -> Unit = {},
    private val onFinished: (Conversion) -> Unit = {},
) {
    private val nextId = AtomicLong(1)
    private val jobs = HashMap<Long, Job>()
    private val state = MutableStateFlow<List<Conversion>>(emptyList())

    val items: StateFlow<List<Conversion>> = state.asStateFlow()

    fun start(input: ConvertInput, format: ConvertFormat): Long {
        val conversion = Conversion(nextId.getAndIncrement(), input.name, format)
        state.update { listOf(conversion) + it }
        val job = scope.launch { run(conversion, input) }
        synchronized(jobs) { jobs[conversion.id] = job }
        job.invokeOnCompletion { synchronized(jobs) { jobs.remove(conversion.id) } }
        onActive()
        return conversion.id
    }

    fun cancel(id: Long) {
        synchronized(jobs) { jobs[id] }?.cancel()
    }

    /** Removes a finished, failed or cancelled conversion from the list (the saved file stays). */
    fun dismiss(id: Long) = state.update { list -> list.filterNot { it.id == id && it.state != ConversionState.RUNNING } }

    private suspend fun run(conversion: Conversion, input: ConvertInput) {
        val output = File(workDir, "convert-${conversion.id}.${conversion.format.extension}.sdmpart")
        try {
            converter.convert(input, conversion.format, output) { progress ->
                change(conversion.id) { it.copy(progress = progress.coerceIn(0f, 1f)) }
            }
            val saved = withContext(Dispatchers.IO) { publisher.publish(output, conversion.targetName, null) }
            finish(conversion.id) { it.copy(state = ConversionState.COMPLETED, progress = 1f, savedName = saved.name) }
        } catch (e: CancellationException) {
            change(conversion.id) { it.copy(state = ConversionState.CANCELLED) }
            throw e
        } catch (e: ConversionException) {
            finish(conversion.id) { it.copy(state = ConversionState.FAILED, error = e.message) }
        } catch (e: IOException) {
            finish(conversion.id) { it.copy(state = ConversionState.FAILED, error = "The converted file could not be saved to Download/SDM.") }
        } catch (e: Exception) { // Android's media tools throw plain exceptions for files they can't read
            finish(conversion.id) {
                it.copy(state = ConversionState.FAILED, error = "Android could not read or convert this file (${e.javaClass.simpleName}).")
            }
        } finally {
            output.delete()
        }
    }

    private fun finish(id: Long, edit: (Conversion) -> Conversion) {
        change(id, edit)?.let(onFinished)
    }

    private fun change(id: Long, edit: (Conversion) -> Conversion): Conversion? {
        var changed: Conversion? = null
        state.update { list -> list.map { if (it.id == id) edit(it).also { new -> changed = new } else it } }
        return changed
    }
}
