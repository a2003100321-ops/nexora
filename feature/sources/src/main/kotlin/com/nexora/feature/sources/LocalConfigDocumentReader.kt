package com.nexora.feature.sources

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LocalConfigDocument(
    val fileName: String,
    val text: String,
)

internal enum class LocalDocumentError {
    UNSUPPORTED_FILE,
    FILE_NOT_FOUND,
    PERMISSION_DENIED,
    TOO_LARGE,
    INVALID_UTF8,
    READ_FAILED,
}

internal sealed interface LocalDocumentReadResult {
    data class Success(val document: LocalConfigDocument) : LocalDocumentReadResult

    data class Failure(val error: LocalDocumentError) : LocalDocumentReadResult
}

internal class LocalConfigDocumentReader(
    private val contentResolver: ContentResolver,
) {
    suspend fun read(uri: Uri): LocalDocumentReadResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull()

        if (!isSupportedConfigDocument(displayName, mimeType)) {
            return@withContext LocalDocumentReadResult.Failure(
                LocalDocumentError.UNSUPPORTED_FILE,
            )
        }

        val fileName = displayName ?: when (mimeType) {
            JSON_MIME_TYPE, TEXT_JSON_MIME_TYPE -> "local-config.json"
            else -> "local-config.txt"
        }

        try {
            val stream = contentResolver.openInputStream(uri)
                ?: return@withContext LocalDocumentReadResult.Failure(
                    LocalDocumentError.FILE_NOT_FOUND,
                )

            when (val result = stream.use { input -> readStrictUtf8Text(input) }) {
                is StrictTextReadResult.Success -> LocalDocumentReadResult.Success(
                    LocalConfigDocument(fileName = fileName, text = result.text),
                )

                StrictTextReadResult.TooLarge -> LocalDocumentReadResult.Failure(
                    LocalDocumentError.TOO_LARGE,
                )

                StrictTextReadResult.InvalidUtf8 -> LocalDocumentReadResult.Failure(
                    LocalDocumentError.INVALID_UTF8,
                )

                StrictTextReadResult.ReadFailed -> LocalDocumentReadResult.Failure(
                    LocalDocumentError.READ_FAILED,
                )
            }
        } catch (_: SecurityException) {
            LocalDocumentReadResult.Failure(LocalDocumentError.PERMISSION_DENIED)
        } catch (_: FileNotFoundException) {
            LocalDocumentReadResult.Failure(LocalDocumentError.FILE_NOT_FOUND)
        } catch (_: IOException) {
            LocalDocumentReadResult.Failure(LocalDocumentError.READ_FAILED)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
        }
    } catch (_: RuntimeException) {
        null
    }
}

internal fun isSupportedConfigDocument(
    displayName: String?,
    mimeType: String?,
): Boolean {
    val extension = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
    val normalizedMimeType = mimeType?.lowercase()
    val supportedName = extension != null && extension in SUPPORTED_EXTENSIONS
    val supportedMime = normalizedMimeType != null && normalizedMimeType in SUPPORTED_MIME_TYPES
    return supportedName || supportedMime
}

private const val JSON_MIME_TYPE = "application/json"
private const val TEXT_JSON_MIME_TYPE = "text/json"
private val SUPPORTED_EXTENSIONS = setOf("json", "txt")
private val SUPPORTED_MIME_TYPES = setOf(JSON_MIME_TYPE, TEXT_JSON_MIME_TYPE, "text/plain")
