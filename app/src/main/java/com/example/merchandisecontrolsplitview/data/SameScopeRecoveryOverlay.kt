package com.example.merchandisecontrolsplitview.data

import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** Transaction-local overlay of existing local intent. No network, new keys, or durable second sync queue. */
internal class SameScopeRecoveryOverlay(private val sql: SupportSQLiteDatabase) {
    private data class Domain(val table: String, val key: String, val refs: String, val foreign: String)
    private val domains = listOf(
        Domain("suppliers", "id", "supplier_remote_refs", "supplierId"),
        Domain("categories", "id", "category_remote_refs", "categoryId"),
        Domain("products", "id", "product_remote_refs", "productId"),
        Domain("product_prices", "id", "product_price_remote_refs", "productPriceId"),
        Domain("history_entries", "uid", "history_entry_remote_refs", "historyEntryUid")
    )
    private val tempTables = mutableListOf<String>()

    fun capture() {
        temp("pending_outbox", "SELECT * FROM sync_event_outbox")
        temp("pending_tombstones", "SELECT * FROM pending_catalog_tombstones")
        prepareOwnedWriteProofs()
        for (d in domains) {
            temp("old_${d.refs}", "SELECT * FROM `${d.refs}`")
            sql.execSQL("CREATE UNIQUE INDEX mc_old_${d.refs}_foreign ON mc_old_${d.refs}(${d.foreign})")
            sql.execSQL("CREATE UNIQUE INDEX mc_old_${d.refs}_remote ON mc_old_${d.refs}(remoteId)")
            val dirty = if (d.table == "product_prices") "r.id IS NULL" else
                "r.id IS NULL OR r.lastRemoteAppliedAt IS NULL OR r.localChangeRevision<>r.lastSyncedLocalRevision" +
                    if (d.table == "history_entries") " OR p.syncStatus<>'SYNCED_SUCCESSFULLY'" else ""
            temp("pending_${d.table}", "SELECT p.* FROM `${d.table}` p LEFT JOIN `${d.refs}` r ON r.${d.foreign}=p.${d.key} WHERE $dirty")
            val maximum = scalar("SELECT COALESCE(MAX(${d.key}),0) FROM `${d.table}`")
            val stageMaximum = scalar("SELECT COALESCE(MAX(${d.key}),0) FROM temp.`mc_recovery_source_${d.table}`")
            if (maximum < 0 || stageMaximum < 0 || maximum > Long.MAX_VALUE - stageMaximum) {
                throw ShopSyncContractException("recovery_local_id_range_invalid")
            }
            if(d.table=="product_prices") prepareOwnedPriceMapping()
            val ownPriceJoin=if(d.table=="product_prices") " LEFT JOIN mc_owned_price_mapping a ON a.sourceId=s.id" else ""
            val target=if(d.table=="product_prices") "COALESCE(o.${d.foreign},a.targetId,s.${d.key}+$maximum)" else "COALESCE(o.${d.foreign},s.${d.key}+$maximum)"
            temp("map_${d.table}", "SELECT s.${d.key} sourceId, $target targetId FROM temp.`mc_recovery_source_${d.table}` s JOIN temp.`mc_recovery_source_${d.refs}` r ON r.${d.foreign}=s.${d.key} LEFT JOIN mc_old_${d.refs} o ON o.remoteId=r.remoteId$ownPriceJoin")
            sql.execSQL("CREATE UNIQUE INDEX mc_map_${d.table}_source ON mc_map_${d.table}(sourceId)")
            sql.execSQL("CREATE UNIQUE INDEX mc_map_${d.table}_target ON mc_map_${d.table}(targetId)")
            // A pending edit based on an older, changed or deleted remote body requires explicit reconciliation.
            // Keep the old store and its original intent rather than silently overwriting either side.
            val ownHistory=if(d.table=="history_entries") """
                AND NOT EXISTS(SELECT 1 FROM mc_history_write_proofs w WHERE w.remoteId=o.remoteId
                    AND w.revision>o.lastSyncedLocalRevision AND w.revision<=o.localChangeRevision
                    AND w.fingerprint=n.lastRemotePayloadFingerprint)
            """.trimIndent() else ""
            if (d.table !in setOf("product_prices", "products") && scalar("""
                    SELECT COUNT(*) FROM mc_pending_${d.table} p
                    JOIN mc_old_${d.refs} o ON o.${d.foreign}=p.${d.key}
                    LEFT JOIN temp.`mc_recovery_source_${d.refs}` n ON n.remoteId=o.remoteId
                    WHERE o.lastRemoteAppliedAt IS NOT NULL AND
                        (n.remoteId IS NULL OR o.lastRemotePayloadFingerprint IS NOT n.lastRemotePayloadFingerprint) $ownHistory
                """.trimIndent()) > 0) {
                throw ShopSyncContractException("recovery_local_remote_conflict")
            }
        }
        prepareProductProjection()
    }

    private fun prepareOwnedWriteProofs() {
        for(domain in listOf("product","history")) {
            sql.execSQL("CREATE TEMP TABLE mc_${domain}_write_proofs(remoteId TEXT NOT NULL,revision INTEGER NOT NULL,fingerprint TEXT NOT NULL)")
            tempTables += "mc_${domain}_write_proofs"
            sql.execSQL("CREATE INDEX mc_${domain}_write_proofs_remote ON mc_${domain}_write_proofs(remoteId,revision)")
        }
        sql.execSQL("CREATE TEMP TABLE mc_price_write_proofs(remoteId TEXT NOT NULL,localId INTEGER NOT NULL,fingerprint TEXT NOT NULL)")
        tempTables += "mc_price_write_proofs"
        val binding = sql.query("SELECT ownerHash,storeId,localStoreId,syncProtocolVersion,schemaVersion,storeEpoch,boundAtMs FROM business_data_scope_binding WHERE id=1").use {
            if (it.moveToFirst()) BusinessDataScopeBinding(ownerHash=it.getString(0),storeId=it.getString(1),
                localStoreId=it.getString(2),syncProtocolVersion=it.getInt(3),schemaVersion=it.getInt(4),storeEpoch=it.getInt(5),boundAtMs=it.getLong(6)) else null
        }
        val deviceId = sql.query("SELECT deviceId FROM sync_event_device_state WHERE id=1").use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        sql.query("SELECT ownerUserId,storeScope,sourceDeviceId,clientEventId,metadataJson," +
            "length(CAST(metadataJson AS BLOB)),domain FROM mc_pending_outbox WHERE eventType=?",
            arrayOf(BUSINESS_WRITE_EVENT)).use { c ->
            while (c.moveToNext()) {
                if (c.getLong(5) !in 1..BUSINESS_WRITE_MAX_BYTES.toLong())
                    throw ShopSyncContractException("recovery_owned_write_proof_invalid")
                val raw = c.getString(4)
                val key = "business-write-v1:" + MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray())
                    .joinToString("") { "%02x".format(it) }
                val proof = try { Json.decodeFromString<BusinessWriteAttempt>(raw) }
                    catch (_: Exception) { throw ShopSyncContractException("recovery_owned_write_proof_invalid") }
                val bound = binding?.ownerHash==task126OwnerHash(proof.owner) &&
                    binding.storeId==Task126OwnerStoreScope.normalizedStoreId(proof.store) && binding.schemaVersion==proof.schema &&
                    binding.localStoreId==proof.localStore && binding.syncProtocolVersion==proof.protocol && binding.storeEpoch==proof.epoch
                val device = deviceId==proof.device
                if (!bound || !device || proof.version!=1 || proof.owner!=c.getString(0) ||
                    proof.store!=c.getString(1) || proof.device!=c.getString(2) || key!=c.getString(3) ||
                    proof.store!=proof.shop?.let { "shop:$it" }.orEmpty() || proof.payload.kind!=c.getString(6))
                    throw ShopSyncContractException("recovery_owned_write_scope_invalid")
                try { validateBusinessWritePayload(proof) } catch (_: Exception) {
                    throw ShopSyncContractException("recovery_owned_write_revision_invalid")
                }
                for (revision in proof.payload.revisions) {
                    when(proof.payload.kind) {
                        "PATCH","PRODUCTS" -> {
                            if(revision.fingerprint?.startsWith("product-base-v2:")!=true)
                                throw ShopSyncContractException("recovery_owned_write_revision_invalid")
                            sql.execSQL("INSERT INTO mc_product_write_proofs VALUES(?,?,?)",
                                arrayOf<Any?>(revision.remoteId,revision.revision,revision.fingerprint))
                        }
                        "HISTORY" -> {
                            val row=proof.payload.history.single { it.remoteId==revision.remoteId }
                            val fingerprint=SessionRemotePayload(row.remoteId,row.payloadVersion,row.timestamp,row.supplier,
                                row.category,row.isManualEntry,row.data,row.displayName,row.sessionOverlay,row.deletedAt).payloadFingerprint()
                            if(fingerprint!=revision.fingerprint) throw ShopSyncContractException("recovery_owned_write_revision_invalid")
                            sql.execSQL("INSERT INTO mc_history_write_proofs VALUES(?,?,?)",
                                arrayOf<Any?>(revision.remoteId,revision.revision,fingerprint))
                        }
                        "PRICES" -> sql.execSQL("INSERT INTO mc_price_write_proofs VALUES(?,?,?)",
                            arrayOf<Any?>(revision.remoteId,revision.localPriceId,
                                localAcknowledgedPriceFingerprint(proof.payload.prices.single { it.id==revision.remoteId })))
                    }
                }
            }
        }
        if(scalar("SELECT count(*) FROM (SELECT remoteId FROM mc_price_write_proofs GROUP BY remoteId HAVING count(DISTINCT localId)>1 OR count(DISTINCT fingerprint)>1)")>0 ||
            scalar("SELECT count(*) FROM (SELECT localId FROM mc_price_write_proofs GROUP BY localId HAVING count(DISTINCT remoteId)>1 OR count(DISTINCT fingerprint)>1)")>0)
            throw ShopSyncContractException("recovery_owned_price_identity_invalid")
        sql.execSQL("CREATE INDEX mc_price_write_proofs_remote ON mc_price_write_proofs(remoteId)")
    }

    /** Only an exact owned original wire identity may bridge an ACK-lost append-only point. */
    private fun prepareOwnedPriceMapping() {
        sql.execSQL("CREATE TEMP TABLE mc_owned_price_mapping(sourceId INTEGER PRIMARY KEY,targetId INTEGER NOT NULL UNIQUE)")
        tempTables += "mc_owned_price_mapping"
        sql.query("""
            SELECT DISTINCT s.id,w.remoteId,w.localId,w.fingerprint,
                op.remoteId AS oldParent,l.type AS oldType,l.price AS oldPrice,l.effectiveAt AS oldEffective,
                l.source AS oldSource,l.note AS oldNote,l.createdAt AS oldCreated,
                np.remoteId AS newParent,s.type AS newType,s.price AS newPrice,s.effectiveAt AS newEffective,
                s.source AS newSource,s.note AS newNote,s.createdAt AS newCreated,
                o.productPriceId AS existingBridge
            FROM mc_price_write_proofs w
            JOIN temp.mc_recovery_source_product_price_remote_refs r ON r.remoteId=w.remoteId
            JOIN temp.mc_recovery_source_product_prices s ON s.id=r.productPriceId
            LEFT JOIN product_prices l ON l.id=w.localId
            LEFT JOIN mc_old_product_remote_refs op ON op.productId=l.productId
            JOIN temp.mc_recovery_source_product_remote_refs np ON np.productId=s.productId
            LEFT JOIN mc_old_product_price_remote_refs o ON o.remoteId=w.remoteId
        """.trimIndent()).use { c ->
            fun text(column:String):String?=c.getColumnIndexOrThrow(column).let { if(c.isNull(it)) null else c.getString(it) }
            fun row(prefix:String)=InventoryProductPriceRow(id=c.getString(1),ownerUserId="",
                productId=requireNotNull(text(prefix+"Parent")),type=requireNotNull(text(prefix+"Type")),
                price=c.getDouble(c.getColumnIndexOrThrow(prefix+"Price")),effectiveAt=requireNotNull(text(prefix+"Effective")),
                source=text(prefix+"Source"),note=text(prefix+"Note"),createdAt=requireNotNull(text(prefix+"Created")))
            while(c.moveToNext()) {
                val bridge=c.getColumnIndexOrThrow("existingBridge")
                if(text("oldParent")==null || localAcknowledgedPriceFingerprint(row("old"))!=c.getString(3) ||
                    localAcknowledgedPriceFingerprint(row("new"))!=c.getString(3) ||
                    (!c.isNull(bridge) && c.getLong(bridge)!=c.getLong(2)))
                    throw ShopSyncContractException("recovery_owned_price_body_conflict")
                sql.execSQL("INSERT INTO mc_owned_price_mapping VALUES(?,?)",arrayOf(c.getLong(0),c.getLong(2)))
            }
        }
    }

    private val productFieldColumns = linkedMapOf(
        "barcode" to "barcode", "itemnumber" to "itemNumber", "productname" to "productName",
        "secondproductname" to "secondProductName", "purchaseprice" to "purchasePrice",
        "retailprice" to "retailPrice", "supplier" to "supplierId", "category" to "categoryId",
        "stockquantity" to "stockQuantity"
    )

    private fun prepareProductProjection() {
        temp("projected_products", "SELECT * FROM mc_pending_products")
        val productColumns = columns("products")
        val remoteColumns = productColumns.joinToString(",") { column ->
            val expression = when (column) {
                "supplierId" -> "sm.targetId"
                "categoryId" -> "cm.targetId"
                else -> "n.`$column`"
            }
            "$expression AS `new_$column`"
        }
        sql.query("""
            SELECT p.*, o.remoteId, o.lastRemoteAppliedAt, o.lastRemotePayloadFingerprint,
                o.remoteUpdatedAt, o.localChangedFields, nr.remoteId AS newRemoteId, $remoteColumns,
                os.remoteId AS oldSupplierRemote, oc.remoteId AS oldCategoryRemote,
                ns.remoteId AS newSupplierRemote, nc.remoteId AS newCategoryRemote,
                group_concat(pw.fingerprint,char(31)) AS ownWriteFingerprints
            FROM mc_pending_products p
            JOIN mc_old_product_remote_refs o ON o.productId=p.id
            LEFT JOIN temp.mc_recovery_source_product_remote_refs nr ON nr.remoteId=o.remoteId
            LEFT JOIN temp.mc_recovery_source_products n ON n.id=nr.productId
            LEFT JOIN mc_old_supplier_remote_refs os ON os.supplierId=p.supplierId
            LEFT JOIN mc_old_category_remote_refs oc ON oc.categoryId=p.categoryId
            LEFT JOIN temp.mc_recovery_source_supplier_remote_refs ns ON ns.supplierId=n.supplierId
            LEFT JOIN temp.mc_recovery_source_category_remote_refs nc ON nc.categoryId=n.categoryId
            LEFT JOIN mc_map_suppliers sm ON sm.sourceId=n.supplierId
            LEFT JOIN mc_map_categories cm ON cm.sourceId=n.categoryId
            LEFT JOIN mc_product_write_proofs pw ON pw.remoteId=o.remoteId AND
                pw.revision>o.lastSyncedLocalRevision AND pw.revision<=o.localChangeRevision
            GROUP BY p.id
        """.trimIndent()).use { cursor ->
            fun string(column: String): String? = cursor.getColumnIndexOrThrow(column).let {
                if (cursor.isNull(it)) null else cursor.getString(it)
            }
            fun number(column: String): Double? = cursor.getColumnIndexOrThrow(column).let {
                if (cursor.isNull(it)) null else cursor.getDouble(it)
            }
            fun identifier(column: String): Long? = cursor.getColumnIndexOrThrow(column).let {
                if (cursor.isNull(it)) null else cursor.getLong(it)
            }
            fun product(prefix: String) = Product(
                id = requireNotNull(identifier(prefix + "id")), barcode = requireNotNull(string(prefix + "barcode")),
                itemNumber = string(prefix + "itemNumber"), productName = string(prefix + "productName"),
                secondProductName = string(prefix + "secondProductName"),
                purchasePrice = number(prefix + "purchasePrice"), retailPrice = number(prefix + "retailPrice"),
                oldPurchasePrice = number(prefix + "oldPurchasePrice"), oldRetailPrice = number(prefix + "oldRetailPrice"),
                supplierId = identifier(prefix + "supplierId"), categoryId = identifier(prefix + "categoryId"),
                stockQuantity = number(prefix + "stockQuantity"), primaryImageVersionId = string(prefix + "primaryImageVersionId"),
                primaryImageUpdatedAt = string(prefix + "primaryImageUpdatedAt")
            )
            while (cursor.moveToNext()) {
                if (identifier("lastRemoteAppliedAt") == null) continue // New local row has no acknowledged remote base.
                if (string("newRemoteId") == null) throw ShopSyncContractException("recovery_local_remote_conflict")
                val old = product("")
                val remote = product("new_")
                val encoded = string("localChangedFields").orEmpty().split(',')
                    .map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
                val fields = if (encoded.isEmpty() || "__all__" in encoded) productFieldColumns.keys else encoded
                if (!productFieldColumns.keys.containsAll(fields)) throw ShopSyncContractException("recovery_pending_changed_fields_invalid")
                val dirtyColumns = fields.mapTo(mutableSetOf()) { requireNotNull(productFieldColumns[it]) }
                fun field(product: Product, column: String): Any? = when (column) {
                    "barcode" -> product.barcode; "itemNumber" -> product.itemNumber; "productName" -> product.productName
                    "secondProductName" -> product.secondProductName; "purchasePrice" -> product.purchasePrice
                    "retailPrice" -> product.retailPrice; "supplierId" -> product.supplierId
                    "categoryId" -> product.categoryId; "stockQuantity" -> product.stockQuantity
                    "primaryImageVersionId" -> product.primaryImageVersionId; "primaryImageUpdatedAt" -> product.primaryImageUpdatedAt
                    "oldPurchasePrice" -> product.oldPurchasePrice; "oldRetailPrice" -> product.oldRetailPrice
                    else -> throw ShopSyncContractException("recovery_overlay_product_field_invalid")
                }
                // Compare a reconstructed typed base, without parsing the legacy unescaped fingerprint.
                // Dirty fields come from remote; unchanged fields retain the known local base.
                fun baseString(field: String, column: String): String? =
                    if (field in fields) string("new_" + column) else string(column)
                fun baseNumber(field: String, column: String): Double? =
                    if (field in fields) number("new_" + column) else number(column)
                val candidate = InventoryProductRow(id=requireNotNull(string("remoteId")), ownerUserId="",
                    barcode=requireNotNull(baseString("barcode", "barcode")), itemNumber=baseString("itemnumber", "itemNumber"),
                    productName=baseString("productname", "productName"), secondProductName=baseString("secondproductname", "secondProductName"),
                    purchasePrice=baseNumber("purchaseprice", "purchasePrice"), retailPrice=baseNumber("retailprice", "retailPrice"),
                    supplierId=string(if ("supplier" in fields) "newSupplierRemote" else "oldSupplierRemote"),
                    categoryId=string(if ("category" in fields) "newCategoryRemote" else "oldCategoryRemote"),
                    stockQuantity=baseNumber("stockquantity", "stockQuantity"),
                    primaryImageVersionId=old.primaryImageVersionId, primaryImageUpdatedAt=old.primaryImageUpdatedAt,
                    updatedAt=string("remoteUpdatedAt"))
                val oldFingerprint = string("lastRemotePayloadFingerprint")
                val legacySafe = oldFingerprint?.count { it == '|' } == 12 &&
                    listOf(candidate.itemNumber, candidate.productName, candidate.secondProductName).none { it == "null" }
                val unchangedBase = fingerprintProductInbound(candidate) == oldFingerprint ||
                    (legacySafe && fingerprintProductInboundLegacy(candidate) == oldFingerprint)
                val alreadyMaterialized = dirtyColumns.all { field(old, it) == field(remote, it) }
                // A persisted original attempt explains this base; it remains pending until an actual replay ACK.
                val ownedAttemptBase = fingerprintProductInbound(candidate) in
                    string("ownWriteFingerprints").orEmpty().split('\u001f')
                if (!unchangedBase && !alreadyMaterialized && !ownedAttemptBase)
                    throw ShopSyncContractException("recovery_local_remote_conflict")
                val cleanColumns = productColumns.filter { it != "id" && it !in dirtyColumns }
                val values = cleanColumns.map { field(remote, it) } + old.id
                sql.execSQL("UPDATE mc_projected_products SET " + cleanColumns.joinToString(",") { "`$it`=?" } + " WHERE id=?", values.toTypedArray())
            }
        }
        val dirtyMismatches = productFieldColumns.entries.joinToString(" OR ") { (field, column) ->
            "((o.localChangedFields IS NULL OR trim(o.localChangedFields)='' OR " +
                "instr(','||o.localChangedFields||',',',__all__,')>0 OR " +
                "instr(','||o.localChangedFields||',',',$field,')>0) AND p.`$column` IS NOT n.`$column`)"
        }
        if (scalar("SELECT count(*) FROM mc_pending_products p JOIN mc_projected_products n ON n.id=p.id " +
                "JOIN mc_old_product_remote_refs o ON o.productId=p.id WHERE $dirtyMismatches") != 0L)
            throw ShopSyncContractException("recovery_local_intent_readback_failed")
    }

    fun copyRemoteTable(table: String) {
        val columns = columns(table)
        val domain = domains.firstOrNull { it.table == table || it.refs == table }
            ?: throw ShopSyncContractException("recovery_overlay_table_invalid")
        val ref = table == domain.refs
        val maxRef = if (ref) scalar("SELECT COALESCE(MAX(id),0) FROM mc_old_${domain.refs}") else 0L
        if (ref) {
            val stageMax = scalar("SELECT COALESCE(MAX(id),0) FROM temp.`mc_recovery_source_${domain.refs}`")
            if (maxRef < 0 || stageMax < 0 || maxRef > Long.MAX_VALUE - stageMax) {
                throw ShopSyncContractException("recovery_local_id_range_invalid")
            }
        }
        val selections = columns.map { column ->
            when {
                ref && column == "id" -> "COALESCE(o.id,s.id+$maxRef)"
                ref && column == domain.foreign -> "m.targetId"
                !ref && column == domain.key -> "m.targetId"
                table == "products" && column == "supplierId" -> "sm.targetId"
                table == "products" && column == "categoryId" -> "cm.targetId"
                table == "product_prices" && column == "productId" -> "pm.targetId"
                else -> "s.`$column`"
            }
        }
        val joins = if (ref) {
            "JOIN mc_map_${domain.table} m ON m.sourceId=s.${domain.foreign} LEFT JOIN mc_old_${domain.refs} o ON o.remoteId=s.remoteId"
        } else buildString {
            append("JOIN mc_map_${domain.table} m ON m.sourceId=s.${domain.key}")
            if (table == "products") append(" LEFT JOIN mc_map_suppliers sm ON sm.sourceId=s.supplierId LEFT JOIN mc_map_categories cm ON cm.sourceId=s.categoryId")
            if (table == "product_prices") append(" JOIN mc_map_products pm ON pm.sourceId=s.productId")
        }
        sql.execSQL("INSERT INTO `$table` (${columns.quoted()}) SELECT ${selections.joinToString(",")} FROM temp.`mc_recovery_source_$table` s $joins")
    }

    fun restorePending() {
        // Parent → product → append-only prices/history. UPDATE on conflict avoids REPLACE's cascading deletes.
        for (d in domains) {
            val columns = columns(d.table)
            val updates = columns.filter { it != d.key }.joinToString(",") { "`$it`=excluded.`$it`" }
            val projection = if (d.table == "products") "mc_projected_products" else "mc_pending_${d.table}"
            sql.execSQL("INSERT INTO `${d.table}` (${columns.quoted()}) SELECT ${columns.quoted()} FROM $projection WHERE 1 ON CONFLICT(`${d.key}`) DO UPDATE SET $updates")
            val refs = columns(d.refs)
            sql.execSQL("INSERT OR REPLACE INTO `${d.refs}` (${refs.quoted()}) SELECT ${refs.joinToString(",") { "o.`$it`" }} FROM mc_old_${d.refs} o JOIN mc_pending_${d.table} p ON p.${d.key}=o.${d.foreign}")
        }
        // Keep the existing tombstone operation and remove only its same-scope materialized cloud row.
        for ((entity, d) in listOf("PRODUCT" to domains[2], "CATEGORY" to domains[1], "SUPPLIER" to domains[0])) {
            sql.execSQL("DELETE FROM `${d.table}` WHERE `${d.key}` IN (SELECT r.${d.foreign} FROM `${d.refs}` r JOIN pending_catalog_tombstones t ON t.remoteId=r.remoteId WHERE t.entityType='$entity')")
        }
    }

    fun verifyPreservedIntent() {
        for (d in domains) {
            val projection = if (d.table == "products") "mc_projected_products" else "mc_pending_${d.table}"
            if (scalar("SELECT COUNT(*) FROM (SELECT * FROM $projection EXCEPT SELECT * FROM `${d.table}`)") != 0L ||
                scalar("SELECT COUNT(*) FROM (SELECT o.* FROM mc_old_${d.refs} o JOIN mc_pending_${d.table} p ON p.${d.key}=o.${d.foreign} EXCEPT SELECT * FROM `${d.refs}`)") != 0L) {
                throw ShopSyncContractException("recovery_local_intent_readback_failed")
            }
        }
        for ((original, table) in listOf("pending_outbox" to "sync_event_outbox", "pending_tombstones" to "pending_catalog_tombstones")) {
            if (scalar("SELECT COUNT(*) FROM (SELECT * FROM mc_$original EXCEPT SELECT * FROM `$table`)") != 0L ||
                scalar("SELECT COUNT(*) FROM (SELECT * FROM `$table` EXCEPT SELECT * FROM mc_$original)") != 0L) {
                throw ShopSyncContractException("recovery_local_operation_readback_failed")
            }
        }
    }

    fun close() {
        tempTables.asReversed().forEach { sql.execSQL("DROP TABLE IF EXISTS `$it`") }
    }

    private fun temp(name: String, select: String) {
        val table = "mc_$name"
        sql.execSQL("CREATE TEMP TABLE `$table` AS $select")
        tempTables += table
    }

    private fun scalar(query: String): Long = sql.query(query).use {
        if (!it.moveToFirst()) throw ShopSyncContractException("recovery_overlay_scalar_missing")
        it.getLong(0)
    }

    private fun columns(table: String): List<String> = sql.query("PRAGMA table_info(`$table`)").use {
        buildList { while (it.moveToNext()) add(it.getString(1)) }
    }

    private fun List<String>.quoted(): String = joinToString(",") { "`$it`" }
}
