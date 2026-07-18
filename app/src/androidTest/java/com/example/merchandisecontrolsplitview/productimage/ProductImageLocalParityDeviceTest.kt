package com.example.merchandisecontrolsplitview.productimage

import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductRemoteRef
import com.example.merchandisecontrolsplitview.data.SelectedShop
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductImageLocalParityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun localNoImageThenThumbMainAndDiskCacheParity() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "TASK-138 local parity is opt-in. Pass -e task138LocalParity true.",
            isProductImageOptInEnabled(args.getString("task138LocalParity"))
        )
        val config = readPrivateProductImageConfig(
            context = context,
            path = args.getString("task138ConfigFile")
                ?: throw AssertionError("task138ConfigFile is required"),
            expectedFileName = CONFIG_FILE_NAME
        )
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val cacheRoot = File(context.cacheDir, "task138-local-parity-${System.nanoTime()}")
        val cache = ProductImageCache(cacheRoot, Unit)
        val gateway = RecordingGateway(
            ProductImageApiClient(
                apiBaseUrl = config.apiBase,
                storageBaseUrl = config.storageBase,
                debugBuild = true
            )
        )
        val service = ProductImageService(
            context = context,
            database = database,
            api = gateway,
            accountIdProvider = { config.accountId },
            selectedShopProvider = {
                SelectedShop(
                    shopId = config.shopId,
                    code = "T138",
                    name = "TASK-138 local parity",
                    role = "viewer",
                    status = "active",
                    canWrite = false
                )
            },
            accessTokenProvider = { config.token },
            cache = cache,
            networkAvailable = { true }
        )

        try {
            val noImageLocalId = insertProduct(database, "task138-local-no-image", null)
            val absent = service.loadBatch(
                listOf(
                    ProductImageLoadRequest(
                        localProductId = noImageLocalId,
                        variant = ProductImageVariant.THUMB,
                        expectedVersionId = null
                    )
                )
            ).single()
            assertEquals(ProductImageLoadResult.Absent, absent.result)
            assertEquals(null, absent.errorCode)
            assertTrue(gateway.events.isEmpty())
            assertEquals(ProductImageCacheSnapshot(0, 0, 0, 0), cache.snapshot())

            val readyLocalId = insertProduct(
                database = database,
                barcode = "task138-local-ready",
                versionId = config.versionId,
                remoteId = config.productId
            )
            val thumbRequest = ProductImageLoadRequest(
                localProductId = readyLocalId,
                variant = ProductImageVariant.THUMB,
                expectedVersionId = config.versionId
            )
            val mainRequest = thumbRequest.copy(variant = ProductImageVariant.MAIN)

            val firstThumb = ready(service.loadBatch(listOf(thumbRequest)).single())
            assertEquals(ProductImageLoadSource.NETWORK, firstThumb.source)
            assertImageBounds(firstThumb.bytes, PRODUCT_IMAGE_THUMB_MAX_SIDE)
            val firstMain = ready(service.loadBatch(listOf(mainRequest)).single())
            assertEquals(ProductImageLoadSource.NETWORK, firstMain.source)
            assertImageBounds(firstMain.bytes, PRODUCT_IMAGE_MAIN_MAX_SIDE)
            assertEquals(
                listOf("read:thumb", "download:thumb", "read:main", "download:main"),
                gateway.events.toList()
            )

            val diskSnapshot = cache.snapshot()
            assertEquals(2, diskSnapshot.diskEntries)
            assertTrue(diskSnapshot.diskBytes > 0L)
            service.trimMemory()
            assertEquals(0, cache.snapshot().memoryEntries)

            val cachedThumb = ready(service.loadBatch(listOf(thumbRequest)).single())
            val cachedMain = ready(service.loadBatch(listOf(mainRequest)).single())
            assertEquals(ProductImageLoadSource.CACHE, cachedThumb.source)
            assertEquals(ProductImageLoadSource.CACHE, cachedMain.source)
            assertEquals(
                listOf("read:thumb", "download:thumb", "read:main", "download:main"),
                gateway.events.toList()
            )

            println(
                "TASK138_ANDROID_LOCAL_PARITY no_image=pass thumb_before_main=pass " +
                    "disk_cache_hit=pass network_events=${gateway.events.size}"
            )
        } finally {
            service.close()
            database.close()
            cacheRoot.deleteRecursively()
        }
    }

    private suspend fun insertProduct(
        database: AppDatabase,
        barcode: String,
        versionId: String?,
        remoteId: String? = null
    ): Long {
        database.productDao().insert(
            Product(
                barcode = barcode,
                productName = barcode,
                primaryImageVersionId = versionId
            )
        )
        val localId = requireNotNull(database.productDao().findByBarcode(barcode)).id
        if (remoteId != null) {
            database.productRemoteRefDao().insert(
                ProductRemoteRef(productId = localId, remoteId = remoteId)
            )
        }
        return localId
    }

    private fun ready(item: ProductImageBatchItem): ProductImageLoadResult.Ready {
        assertEquals(null, item.errorCode)
        return item.result as? ProductImageLoadResult.Ready
            ?: throw AssertionError("Expected a ready Product Image result")
    }

    private fun assertImageBounds(bytes: ByteArray, maxSide: Int) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        assertTrue(options.outWidth > 0 && options.outHeight > 0)
        assertTrue(maxOf(options.outWidth, options.outHeight) <= maxSide)
    }

    private class RecordingGateway(
        private val delegate: ProductImageRemoteGateway
    ) : ProductImageRemoteGateway by delegate {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun readUrls(
            accessToken: String,
            body: ProductImageReadBody
        ): ProductImageReadResponse {
            body.refs.forEach { events += "read:${it.variant}" }
            return delegate.readUrls(accessToken, body)
        }

        override suspend fun downloadSignedJpeg(
            signedUrl: String,
            variant: ProductImageVariant
        ): ByteArray {
            events += "download:${variant.wireName}"
            return delegate.downloadSignedJpeg(signedUrl, variant)
        }
    }

    private companion object {
        const val CONFIG_FILE_NAME = "task138-local-parity.json"
    }
}
