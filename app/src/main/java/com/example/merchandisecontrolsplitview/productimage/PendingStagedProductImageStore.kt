package com.example.merchandisecontrolsplitview.productimage

import android.content.Context
import android.net.Uri
import org.json.JSONObject

data class PendingStagedProductImageRecord(
    val accountId: String,
    val barcode: String,
    val createdAtEpochMs: Long,
    val fileUri: Uri,
    val productId: Long?,
    val shopId: String?
)

interface PendingStagedProductImageStore {
    fun prepare(
        fileUri: Uri,
        barcode: String,
        accountId: String,
        shopId: String?
    ): Boolean
    fun bind(fileUri: Uri, productId: Long): Boolean
    fun remove(fileUri: Uri)
    fun records(): List<PendingStagedProductImageRecord>
    fun clear(): List<Uri>
}

class SharedPreferencesPendingStagedProductImageStore(context: Context) :
    PendingStagedProductImageStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "pending_staged_product_images",
        Context.MODE_PRIVATE
    )

    override fun prepare(
        fileUri: Uri,
        barcode: String,
        accountId: String,
        shopId: String?
    ): Boolean {
        if (fileUri.scheme != "file" || barcode.isBlank()) return false
        val record = PendingStagedProductImageRecord(
            accountId = accountId,
            barcode = barcode,
            createdAtEpochMs = System.currentTimeMillis(),
            fileUri = fileUri,
            productId = null,
            shopId = shopId
        )
        return preferences.edit().putString(fileUri.toString(), record.toJson()).commit()
    }

    override fun bind(fileUri: Uri, productId: Long): Boolean {
        if (productId <= 0L) return false
        val current = preferences.getString(fileUri.toString(), null)
            ?.let(::recordFromJson)
            ?: return false
        return preferences.edit().putString(
            fileUri.toString(),
            current.copy(productId = productId).toJson()
        ).commit()
    }

    override fun remove(fileUri: Uri) {
        preferences.edit().remove(fileUri.toString()).commit()
    }

    override fun records(): List<PendingStagedProductImageRecord> =
        preferences.all.values.mapNotNull { value ->
            (value as? String)?.let(::recordFromJson)
        }

    override fun clear(): List<Uri> {
        val uris = records().map(PendingStagedProductImageRecord::fileUri)
        preferences.edit().clear().commit()
        return uris
    }

    private fun PendingStagedProductImageRecord.toJson(): String = JSONObject()
        .put("accountId", accountId)
        .put("barcode", barcode)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("fileUri", fileUri.toString())
        .put("productId", productId ?: JSONObject.NULL)
        .put("shopId", shopId ?: JSONObject.NULL)
        .toString()

    private fun recordFromJson(value: String): PendingStagedProductImageRecord? = runCatching {
        val json = JSONObject(value)
        val uri = Uri.parse(json.getString("fileUri"))
        val barcode = json.getString("barcode")
        val productId = if (json.isNull("productId")) null else json.getLong("productId")
        PendingStagedProductImageRecord(
            accountId = json.optString("accountId"),
            barcode = barcode,
            createdAtEpochMs = json.getLong("createdAtEpochMs"),
            fileUri = uri,
            productId = productId,
            shopId = if (json.isNull("shopId")) null else json.getString("shopId")
        ).takeIf { uri.scheme == "file" && barcode.isNotBlank() && (productId == null || productId > 0L) }
    }.getOrNull()
}
