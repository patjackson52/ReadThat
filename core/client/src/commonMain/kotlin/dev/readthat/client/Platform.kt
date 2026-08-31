package dev.readthat.client

internal expect fun platformEpochMillis(): Long
internal expect fun platformElapsedRealtimeMillis(): Long
internal expect fun platformMutationId(prefix: String): String

private val wireUuidPattern = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
)

/**
 * API contracts that declare a UUID must never receive the local mutation namespace prefix.
 * Normalizing existing prefixed rows here lets older offline outboxes drain after migration.
 */
internal fun String.toWireUuid(): String {
    val candidate = substringAfterLast(':')
    require(wireUuidPattern.matches(candidate)) { "Mutation id is not a UUID" }
    return candidate.lowercase()
}

internal fun platformMutationUuid(prefix: String): String = platformMutationId(prefix).toWireUuid()

internal expect suspend fun readStagedMedia(path: String, offset: Long, byteCount: Int): ByteArray
internal expect suspend fun deleteStagedMedia(path: String)
