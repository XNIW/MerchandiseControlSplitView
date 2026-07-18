package com.example.merchandisecontrolsplitview.productimage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductRemoteRef
import com.example.merchandisecontrolsplitview.data.SelectedShop
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductImageLocalMutationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun replaceOrRemoveUsesRealLocalServiceWithSyntheticFixtures() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "TASK-138 local mutation is opt-in. Pass -e task138LocalMutation true.",
            isProductImageOptInEnabled(args.getString("task138LocalMutation"))
        )
        val config = readPrivateProductImageConfig(
            context = context,
            path = args.getString("task138MutationConfigFile")
                ?: throw AssertionError("task138MutationConfigFile is required"),
            expectedFileName = CONFIG_FILE_NAME
        )
        assertTrue("local mutation requires an explicit owner canWrite config", config.canWrite)
        val mode = args.getString("task138MutationMode")
        if (mode !in MUTATION_MODES) {
            throw AssertionError("task138MutationMode must be replace or remove")
        }

        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val cacheRoot = File(context.cacheDir, "task138-local-mutation-${System.nanoTime()}")
        val cache = ProductImageCache(cacheRoot, Unit)
        val service = ProductImageService(
            context = context,
            database = database,
            api = ProductImageApiClient(
                apiBaseUrl = config.apiBase,
                storageBaseUrl = config.storageBase,
                debugBuild = true
            ),
            accountIdProvider = { config.accountId },
            selectedShopProvider = {
                SelectedShop(
                    shopId = config.shopId,
                    code = "T138",
                    name = "TASK-138 local mutation",
                    role = "owner",
                    status = "active",
                    canWrite = config.canWrite
                )
            },
            accessTokenProvider = { config.token },
            cache = cache,
            networkAvailable = { true }
        )
        val firstSource = if (mode == "replace") syntheticJpeg("first") else null
        val secondSource = if (mode == "replace") syntheticJpeg("second") else null
        var shouldCleanupRemoteMutation = false
        var completedReplace = false

        try {
            val localProductId = insertReadyProduct(database, config)
            when (mode) {
                "replace" -> {
                    assertIntentMetadataContract(requireNotNull(firstSource))
                    val first = service.upload(
                        localProductId,
                        Uri.fromFile(firstSource)
                    )
                    assertTrue(first.status in FINALIZED_STATUSES)
                    val firstVersion = requireNotNull(first.versionId)
                    assertNotEquals(config.versionId, firstVersion)
                    shouldCleanupRemoteMutation = true
                    assertEquals(
                        firstVersion,
                        database.productDao().getById(localProductId)?.primaryImageVersionId
                    )

                    assertIntentMetadataContract(requireNotNull(secondSource))
                    val second = service.upload(
                        localProductId,
                        Uri.fromFile(secondSource)
                    )
                    assertTrue(second.status in FINALIZED_STATUSES)
                    val secondVersion = requireNotNull(second.versionId)
                    assertNotEquals(firstVersion, secondVersion)
                    assertEquals(
                        secondVersion,
                        database.productDao().getById(localProductId)?.primaryImageVersionId
                    )
                    completedReplace = true
                    println(
                        "TASK138_ANDROID_LOCAL_MUTATION mode=replace upload=true replace=true " +
                            "version_changed=true version_fingerprint=${fingerprint(secondVersion)}"
                    )
                }

                "remove" -> {
                    val removed = service.remove(localProductId)
                    assertTrue(removed.status in REMOVED_STATUSES)
                    assertNull(removed.versionId)
                    assertNull(database.productDao().getById(localProductId)?.primaryImageVersionId)
                    println(
                        "TASK138_ANDROID_LOCAL_MUTATION mode=remove remove=true " +
                            "current_version_null=true"
                    )
                }
            }
        } finally {
            if (shouldCleanupRemoteMutation && !completedReplace) {
                val productId = database.productDao().findByBarcode(PRODUCT_BARCODE)?.id
                if (productId != null) {
                    try {
                        service.remove(productId)
                    } catch (_: Throwable) {
                        // Best effort: il test ha gia' fallito e non espone dati di config nei log.
                    }
                }
            }
            firstSource?.delete()
            secondSource?.delete()
            service.close()
            database.close()
            cacheRoot.deleteRecursively()
        }
    }

    private suspend fun insertReadyProduct(
        database: AppDatabase,
        config: ProductImageLocalTestConfig
    ): Long {
        database.productDao().insert(
            Product(
                barcode = PRODUCT_BARCODE,
                productName = "TASK-138 synthetic mutation fixture",
                primaryImageVersionId = config.versionId
            )
        )
        val localId = requireNotNull(database.productDao().findByBarcode(PRODUCT_BARCODE)).id
        database.productRemoteRefDao().insert(
            ProductRemoteRef(productId = localId, remoteId = config.productId)
        )
        return localId
    }

    private fun syntheticJpeg(label: String): File {
        val nonce = System.nanoTime()
        val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
        }
        val canvas = Canvas(bitmap)
        val seed = (nonce xor label.hashCode().toLong()).toInt()
        canvas.drawColor(
            Color.rgb(
                48 + (seed and 0x3f),
                72 + ((seed ushr 6) and 0x3f),
                96 + ((seed ushr 12) and 0x3f)
            )
        )
        val paint = Paint().apply { isAntiAlias = false }
        repeat(36) { index ->
            paint.color = Color.rgb(
                (seed + index * 37) and 0xff,
                (seed + index * 61) ushr 8 and 0xff,
                (seed + index * 89) ushr 16 and 0xff
            )
            val left = (index % 9) * (bitmap.width / 9f)
            val top = (index / 9) * (bitmap.height / 4f)
            canvas.drawRect(
                left,
                top,
                left + bitmap.width / 9f,
                top + bitmap.height / 4f,
                paint
            )
        }
        val output = File(context.cacheDir, "task138-$label-$nonce.jpg")
        try {
            output.outputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream))
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue(output.length() > 0L)
        return output
    }

    private suspend fun assertIntentMetadataContract(source: File) {
        val prepared = ProductImageProcessor().prepare(context, Uri.fromFile(source))
        assertVariantMetadata(prepared.main, PRODUCT_IMAGE_MAIN_MAX_SIDE)
        assertVariantMetadata(prepared.thumb, PRODUCT_IMAGE_THUMB_MAX_SIDE)
        val mainAspect = prepared.main.metadata.width.toDouble() /
            prepared.main.metadata.height.toDouble()
        val thumbAspect = prepared.thumb.metadata.width.toDouble() /
            prepared.thumb.metadata.height.toDouble()
        assertTrue(abs(mainAspect - thumbAspect) <= 0.02)
    }

    private fun assertVariantMetadata(
        variant: PreparedProductImageVariant,
        maxSide: Int
    ) {
        assertTrue(variant.metadata.bytes in 1..variant.bytes.size)
        assertEquals(variant.bytes.size, variant.metadata.bytes)
        assertTrue(variant.metadata.width in 1..maxSide)
        assertTrue(variant.metadata.height in 1..maxSide)
        assertEquals("image/jpeg", variant.metadata.mimeType)
        assertTrue(PRODUCT_IMAGE_SHA256_PATTERN.matches(variant.metadata.sha256))
    }

    private fun fingerprint(versionId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(versionId.encodeToByteArray())
            .take(6)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val CONFIG_FILE_NAME = "task138-local-mutation.json"
        const val PRODUCT_BARCODE = "task138-local-mutation-product"
        val FINALIZED_STATUSES = setOf("finalized", "already_finalized")
        val REMOVED_STATUSES = setOf("removed", "already_removed")
        val MUTATION_MODES = setOf("replace", "remove")
    }
}
