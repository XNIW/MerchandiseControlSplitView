package com.example.merchandisecontrolsplitview.data

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One durable intent per account/shop/stable product. Never a disposable publication cache. */
data class StorefrontPendingMutation(
    val accountId: String,
    val shopId: String,
    val remoteProductId: String,
    val operation: StorefrontMutationOperation,
    val draft: StorefrontEditorDraft,
    val baseDraft: StorefrontEditorDraft,
    val basePublication: StorefrontPublication?,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val state: StorefrontIntentState = StorefrontIntentState.QUEUED,
    val desiredDraft: StorefrontEditorDraft = draft,
    val desiredOperation: StorefrontMutationOperation = operation,
    val server: StorefrontPublication? = null,
    val errorCode: String? = null,
    val dispatchedAtMs: Long? = null,
    val formatVersion: Int = 1
) {
    fun matches(operation: StorefrontMutationOperation, draft: StorefrontEditorDraft): Boolean =
        this.operation == operation &&
            storefrontMutationPayload(remoteProductId, this.operation, this.draft) ==
            storefrontMutationPayload(remoteProductId, operation, draft)
}

enum class StorefrontIntentState { QUEUED, DISPATCHED, REJECTED, CONFLICT, RECOVERY_REQUIRED }

interface StorefrontPendingMutationStore {
    fun read(accountId: String, shopId: String, remoteProductId: String): StorefrontPendingMutation?
    fun write(record: StorefrontPendingMutation)
    fun remove(accountId: String, shopId: String, remoteProductId: String)
}

/** App-private, excluded from cloud backup/device transfer. IO failures are never local success. */
class FileStorefrontPendingMutationStore private constructor(private val directoryProvider: () -> File) : StorefrontPendingMutationStore {
    constructor(directory: File) : this({ directory })
    constructor(context: Context) : this({ File(context.noBackupFilesDir, "storefront-intents-v1") })

    private val directory: File get() = directoryProvider()

    private val gson = Gson()

    override fun read(accountId: String, shopId: String, remoteProductId: String): StorefrontPendingMutation? =
        synchronized(lock) {
            val file = file(accountId, shopId, remoteProductId)
            if (!file.exists()) return@synchronized null
            val record = requireNotNull(gson.fromJson(file.readText(), StorefrontPendingMutation::class.java))
            require(record.formatVersion == 1 && record.accountId == accountId &&
                record.shopId == shopId && record.remoteProductId == remoteProductId &&
                record.expectedVersion >= 0 && isStorefrontRemoteIdentity(record.idempotencyKey))
            require(record.state in StorefrontIntentState.entries &&
                record.operation in StorefrontMutationOperation.entries &&
                record.desiredOperation in StorefrontMutationOperation.entries)
            requireNotNull(record.baseDraft)
            // Force validation of required decoded fields; corrupt records must not be overwritten.
            storefrontMutationPayload(remoteProductId, record.operation, record.draft)
            storefrontMutationPayload(remoteProductId, record.desiredOperation, record.desiredDraft)
            record
        }

    override fun write(record: StorefrontPendingMutation) = synchronized(lock) {
        Files.createDirectories(directory.toPath())
        val target = file(record.accountId, record.shopId, record.remoteProductId)
        val temporary = File.createTempFile("intent-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(gson.toJson(record).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // Atomic replacement leaves the previous intent intact on write/move failure.
            Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
        Unit
    }

    override fun remove(accountId: String, shopId: String, remoteProductId: String) = synchronized(lock) {
        Files.deleteIfExists(file(accountId, shopId, remoteProductId).toPath())
        Unit
    }

    private fun file(accountId: String, shopId: String, remoteProductId: String): File {
        require(isStorefrontRemoteIdentity(accountId) && isStorefrontRemoteIdentity(shopId) &&
            isStorefrontRemoteIdentity(remoteProductId))
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$accountId/$shopId/$remoteProductId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }

    private companion object { val lock = Any() }
}
