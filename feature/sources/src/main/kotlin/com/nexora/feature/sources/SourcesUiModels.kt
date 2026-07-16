package com.nexora.feature.sources

internal enum class SourcesDestination {
    ONBOARDING,
    MANAGEMENT,
    SEARCH_TEST,
}

internal enum class ImportEditor {
    REMOTE_URL,
    PASTED_TEXT,
}

internal enum class SourcesUiErrorCode {
    URL_REQUIRED,
    TEXT_REQUIRED,
    IMPORT_REJECTED,
    OPERATION_FAILED,
    UNSUPPORTED_FILE,
    FILE_NOT_FOUND,
    PERMISSION_DENIED,
    FILE_TOO_LARGE,
    INVALID_UTF8,
    FILE_READ_FAILED,
}

internal sealed interface SourcesUiFeedback {
    data class Imported(val count: Int) : SourcesUiFeedback

    data object SourceUpdated : SourcesUiFeedback

    data object ConfigurationDeleted : SourcesUiFeedback
}

internal fun LocalDocumentError.toUiErrorCode(): SourcesUiErrorCode = when (this) {
    LocalDocumentError.UNSUPPORTED_FILE -> SourcesUiErrorCode.UNSUPPORTED_FILE
    LocalDocumentError.FILE_NOT_FOUND -> SourcesUiErrorCode.FILE_NOT_FOUND
    LocalDocumentError.PERMISSION_DENIED -> SourcesUiErrorCode.PERMISSION_DENIED
    LocalDocumentError.TOO_LARGE -> SourcesUiErrorCode.FILE_TOO_LARGE
    LocalDocumentError.INVALID_UTF8 -> SourcesUiErrorCode.INVALID_UTF8
    LocalDocumentError.READ_FAILED -> SourcesUiErrorCode.FILE_READ_FAILED
}
