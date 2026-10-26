package io.github.conditionstech.sdm.engine

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The partial file a download streams into, and the rename that never overwrites (ADR-0008).
 */
object PartFiles {
    const val PART_SUFFIX = ".sdmpart"
    const val MAX_NAME_VARIANTS = 1000 // "name.ext" through "name (999).ext"

    /** Creates "<name>.sdmpart" (or a numbered one), never reusing another download's file. */
    fun create(directory: File, name: String): File {
        for (number in 0 until MAX_NAME_VARIANTS) {
            val part = File(directory, FileNames.numbered(name, number) + PART_SUFFIX)
            try {
                Files.createFile(part.toPath()) // fails if the file exists: atomic
                return part
            } catch (e: FileAlreadyExistsException) {
                continue
            }
        }
        throw DownloadException("Too many files named like $name in ${directory.name}.")
    }

    /**
     * Moves [part] to [name] in [directory], or "name (1).ext" and so on if that name is taken.
     *
     * A plain rename would silently replace an existing file on Android (POSIX rename), so the
     * final name is first claimed by creating it (atomic, fails if it exists) and only then is
     * the partial file moved over that empty, claimed file.
     */
    fun finish(part: File, directory: File, name: String): File {
        for (number in 0 until MAX_NAME_VARIANTS) {
            val target = File(directory, FileNames.numbered(name, number))
            try {
                Files.createFile(target.toPath())
            } catch (e: FileAlreadyExistsException) {
                continue
            }
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                target.delete() // give the claimed name back
                throw e
            }
            return target
        }
        throw DownloadException("Too many files named like $name in ${directory.name}.")
    }
}
