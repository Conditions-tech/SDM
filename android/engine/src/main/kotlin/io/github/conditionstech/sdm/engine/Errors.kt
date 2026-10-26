package io.github.conditionstech.sdm.engine

/** Expected failures SDM reports to the user. Every message must be safe to show and to log. */
open class SdmException(message: String) : Exception(message)

/** The URL is malformed or uses something SDM refuses (ftp:, file:, embedded passwords...). */
class InvalidUrlException(message: String) : SdmException(message)

/** The network policy refused the destination: this phone, a private network... (ADR-0007). */
class BlockedDestinationException(message: String) : SdmException(message)

/**
 * A download failed. [retryable] says whether trying again might help (a timeout might, a 404
 * won't); [retryAfterSeconds] is the delay the server asked for, if any.
 */
class DownloadException(
    message: String,
    val retryable: Boolean = false,
    val retryAfterSeconds: Double? = null,
) : SdmException(message)
