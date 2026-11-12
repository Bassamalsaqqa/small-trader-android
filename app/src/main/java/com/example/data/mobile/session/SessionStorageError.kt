package com.example.data.mobile.session

/**
 * Fail-closed typed errors for session persistence operations.
 *
 * Raw exception causes, ciphertext bytes, and sensitive key material are strictly discarded
 * to guarantee that internal cryptographic errors cannot leak secrets into telemetry or logs.
 */
sealed interface SessionStorageError {
    val message: String

    /**
     * Key is locked, permanently invalidated, or requires user re-authentication.
     */
    data class Locked(override val message: String = "Cryptographic key is locked or permanently invalidated") : SessionStorageError

    /**
     * Key or keystore service is unavailable (e.g. key alias missing, provider uninitialized).
     */
    data class Unavailable(override val message: String = "Cryptographic key or keystore provider is unavailable") : SessionStorageError

    /**
     * Ciphertext or metadata integrity verification failed (tampered ciphertext, invalid GCM tag,
     * header magic mismatch, unsupported version, or malformed serialization).
     */
    data class Corrupt(override val message: String = "Session record is corrupt, tampered, or uses an unsupported format") : SessionStorageError

    /**
     * File size exceeds the hard 1 MiB bound before reading or memory allocation.
     */
    data class Oversized(
        val sizeBytes: Long,
        val maxAllowedBytes: Long = 1024 * 1024
    ) : SessionStorageError {
        override val message: String =
            "Session file size ($sizeBytes bytes) exceeds maximum limit ($maxAllowedBytes bytes)"
    }

    /**
     * Session expiry timestamp has elapsed.
     */
    data class Expired(
        val expiresAt: String,
        val checkedAt: String
    ) : SessionStorageError {
        override val message: String =
            "Session expired at $expiresAt (evaluated at $checkedAt)"
    }

    /**
     * Explicit read scope does not match the stored authenticated session (e.g. device, actor, or company mismatch).
     * The stored token is strictly concealed.
     */
    data class ScopeMismatch(override val message: String = "Session record does not match the requested scope") : SessionStorageError

    /**
     * Low-level file I/O failure during atomic replace or disk access.
     */
    data class IoError(override val message: String = "I/O error occurred during session storage operation") : SessionStorageError
}

/**
 * Result of reading a staff session from secure storage.
 */
sealed interface SessionReadResult {
    /**
     * Authenticated session record successfully read, decrypted, authenticated, and scope-verified.
     */
    data class Success(val session: StaffSessionRecord) : SessionReadResult

    /**
     * No session record exists on disk (clean state, e.g. after logout or fresh install).
     */
    object NoSession : SessionReadResult

    /**
     * Stored session has expired. Token is concealed and record is preserved on disk.
     */
    data class Expired(val error: SessionStorageError.Expired) : SessionReadResult

    /**
     * Scope mismatch occurred. Token is concealed and record is preserved on disk.
     */
    data class ScopeMismatch(val error: SessionStorageError.ScopeMismatch) : SessionReadResult

    /**
     * Key is locked or invalidated. Record is preserved on disk.
     */
    data class Locked(val error: SessionStorageError.Locked) : SessionReadResult

    /**
     * Key is missing or unavailable. Record is preserved on disk.
     */
    data class Unavailable(val error: SessionStorageError.Unavailable) : SessionReadResult

    /**
     * Record is corrupt, tampered, or unsupported format. Record is preserved on disk.
     */
    data class Corrupt(val error: SessionStorageError.Corrupt) : SessionReadResult

    /**
     * File exceeds 1 MiB bound. Record is preserved on disk.
     */
    data class Oversized(val error: SessionStorageError.Oversized) : SessionReadResult

    /**
     * File I/O failure. Record is preserved on disk.
     */
    data class IoError(val error: SessionStorageError.IoError) : SessionReadResult
}
