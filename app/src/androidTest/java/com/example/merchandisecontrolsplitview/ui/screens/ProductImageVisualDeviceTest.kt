package com.example.merchandisecontrolsplitview.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Debug
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.productimage.PRODUCT_IMAGE_DISK_CACHE_MAX_BYTES
import com.example.merchandisecontrolsplitview.productimage.PRODUCT_IMAGE_MAIN_MAX_SIDE
import com.example.merchandisecontrolsplitview.productimage.PRODUCT_IMAGE_MEMORY_CACHE_MAX_BYTES
import com.example.merchandisecontrolsplitview.productimage.PRODUCT_IMAGE_THUMB_MAX_SIDE
import com.example.merchandisecontrolsplitview.productimage.ProductImageCache
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadSource
import com.example.merchandisecontrolsplitview.productimage.ProductImageMutationPhase
import com.example.merchandisecontrolsplitview.productimage.ProductImageProcessor
import com.example.merchandisecontrolsplitview.productimage.ProductImageReference
import com.example.merchandisecontrolsplitview.productimage.ProductImageVariant
import com.example.merchandisecontrolsplitview.productimage.isProductImageOptInEnabled
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiState
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiStatus
import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductImageVisualDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun capturesSixSyntheticStatesAndVerifiesProgressiveRendering() {
        assumeVisualGate()
        val thumbBytes = solidJpeg(384, 288, Color.rgb(215, 35, 35))
        val mainBytes = solidJpeg(1_600, 1_200, Color.rgb(25, 65, 220))
        assertDecodedDimensions(thumbBytes, PRODUCT_IMAGE_THUMB_MAX_SIDE, 384, 288)
        assertDecodedDimensions(mainBytes, PRODUCT_IMAGE_MAIN_MAX_SIDE, 1_600, 1_200)

        val scenario = mutableStateOf(VisualScenario.LIST)
        val product = syntheticProduct()
        val thumbnailDescription = context.getString(R.string.product_image_thumbnail)
        val mainDescription = context.getString(R.string.product_image_main)
        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ROOT_TAG)
                ) {
                    when (scenario.value) {
                        VisualScenario.LIST -> ListEvidence(thumbBytes, thumbnailDescription)
                        VisualScenario.DETAIL_THUMB,
                        VisualScenario.DETAIL_MAIN -> DetailEvidence(
                            bytes = if (scenario.value == VisualScenario.DETAIL_THUMB) {
                                thumbBytes
                            } else {
                                mainBytes
                            },
                            mainDescription = mainDescription
                        )

                        VisualScenario.EDITOR_UPLOAD -> ProductImageEditorSectionDebugTestHook(
                            product = product,
                            mainState = readyState(
                                bytes = mainBytes,
                                source = ProductImageLoadSource.NETWORK,
                                status = ProductImageUiStatus.UPLOADING,
                                mutationPhase = ProductImageMutationPhase.UPLOAD_MAIN
                            ),
                            thumbState = readyState(thumbBytes, ProductImageLoadSource.CACHE),
                            apiConfigured = true,
                            canManage = true,
                            onChoosePhoto = {},
                            onTakePhoto = {},
                            onRetry = {},
                            onCancelOperation = {},
                            onRemove = {}
                        )

                        VisualScenario.OFFLINE_CACHE -> ProductImageEditorSectionDebugTestHook(
                            product = product,
                            mainState = readyState(mainBytes, ProductImageLoadSource.CACHE),
                            thumbState = readyState(thumbBytes, ProductImageLoadSource.CACHE),
                            apiConfigured = true,
                            canManage = true,
                            onChoosePhoto = {},
                            onTakePhoto = {},
                            onRetry = {},
                            onCancelOperation = {},
                            onRemove = {}
                        )

                        VisualScenario.ERROR_FALLBACK -> ProductImageEditorSectionDebugTestHook(
                            product = product,
                            mainState = ProductImageUiState(
                                status = ProductImageUiStatus.ERROR,
                                versionId = VERSION_ID,
                                errorCode = "synthetic_error"
                            ),
                            thumbState = readyState(thumbBytes, ProductImageLoadSource.CACHE),
                            apiConfigured = true,
                            canManage = true,
                            onChoosePhoto = {},
                            onTakePhoto = {},
                            onRetry = {},
                            onCancelOperation = {},
                            onRemove = {}
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription(thumbnailDescription).assertCountEquals(2)
        composeRule.onNodeWithContentDescription(mainDescription).assertDoesNotExist()
        composeRule.onNodeWithTag(LIST_PLACEHOLDER_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.product_image_state_empty)
                )
            )
        composeRule.onNodeWithTag(LIST_THUMB_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.product_image_state_ready)
                )
            )
        assertSquareAndContained(LIST_PLACEHOLDER_TAG)
        assertSquareAndContained(LIST_THUMB_TAG)
        saveRootScreenshot("01-list-placeholder-thumbnail.png")

        composeRule.runOnIdle { scenario.value = VisualScenario.DETAIL_THUMB }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(mainDescription).assertIsDisplayed()
        val thumbBounds = composeRule.onNodeWithTag(DETAIL_PREVIEW_TAG)
            .fetchSemanticsNode().boundsInRoot
        val thumbPixel = centerPixel(DETAIL_PREVIEW_TAG)
        assertMostlyRed(thumbPixel)
        saveRootScreenshot("02-detail-thumb-preview.png")

        composeRule.runOnIdle { scenario.value = VisualScenario.DETAIL_MAIN }
        composeRule.waitForIdle()
        val mainBounds = composeRule.onNodeWithTag(DETAIL_PREVIEW_TAG)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(thumbBounds.left, mainBounds.left, 0.5f)
        assertEquals(thumbBounds.top, mainBounds.top, 0.5f)
        assertEquals(thumbBounds.width, mainBounds.width, 0.5f)
        assertEquals(thumbBounds.height, mainBounds.height, 0.5f)
        assertMostlyBlue(centerPixel(DETAIL_PREVIEW_TAG))
        saveRootScreenshot("03-detail-main.png")

        composeRule.runOnIdle { scenario.value = VisualScenario.EDITOR_UPLOAD }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.product_image_progress_upload_main))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.product_image_cancel_operation))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(mainDescription).assertExists()
        saveRootScreenshot("04-editor-upload.png")

        composeRule.runOnIdle { scenario.value = VisualScenario.OFFLINE_CACHE }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.product_image_offline_cache))
            .assertIsDisplayed()
        saveRootScreenshot("05-offline-cache.png")

        composeRule.runOnIdle { scenario.value = VisualScenario.ERROR_FALLBACK }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.product_image_operation_failed))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.product_image_retry))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(mainDescription).assertIsDisplayed()
        assertMostlyRed(
            composeRule.onNodeWithContentDescription(mainDescription)
                .captureToImage()
                .asAndroidBitmap()
                .let { bitmap -> bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) }
        )
        saveRootScreenshot("06-error-fallback.png")

        println(
            "TASK138_ANDROID_VISUAL screenshots=6 thumbnail_dimensions=true " +
                "no_overflow=true layout_stable=true crossfade_complete=true a11y_label=true"
        )
    }

    @Test
    fun scrollsTwoHundredProductsOpensTwentyEditorsAndMeasuresBoundedCache() {
        assumeVisualGate()
        val thumbBytes = performanceThumbJpeg()
        val cacheRoot = File(context.cacheDir, "task138-visual-cache-${System.nanoTime()}")
        val cache = ProductImageCache(cacheRoot, Unit)
        val accountScope = cache.accountScope(ACCOUNT_ID)
        try {
            repeat(PRODUCT_COUNT) { index ->
                cache.write(
                    ProductImageReference(
                        accountScope = accountScope,
                        shopId = SHOP_ID,
                        productId = productUuid(index),
                        versionId = VERSION_ID,
                        variant = ProductImageVariant.THUMB
                    ),
                    thumbBytes
                )
            }
            val cacheSnapshot = cache.snapshot()
            assertTrue(cacheSnapshot.memoryBytes <= PRODUCT_IMAGE_MEMORY_CACHE_MAX_BYTES)
            assertTrue(cacheSnapshot.diskBytes <= PRODUCT_IMAGE_DISK_CACHE_MAX_BYTES)
            assertEquals(PRODUCT_COUNT, cacheSnapshot.diskEntries)

            val showList = mutableStateOf(true)
            val openEditor = mutableStateOf<Int?>(null)
            val mainBytes = solidJpeg(1_600, 1_200, Color.rgb(25, 65, 220))
            val thumbDescription = context.getString(R.string.product_image_thumbnail)
            composeRule.setContent {
                MerchandiseControlTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        when {
                            showList.value -> PerformanceList(thumbBytes, thumbDescription)
                            openEditor.value != null -> ProductImageEditorSectionDebugTestHook(
                                product = syntheticProduct(openEditor.value!! + 1L),
                                mainState = readyState(mainBytes, ProductImageLoadSource.CACHE),
                                thumbState = readyState(thumbBytes, ProductImageLoadSource.CACHE),
                                apiConfigured = true,
                                canManage = true,
                                onChoosePhoto = {},
                                onTakePhoto = {},
                                onRetry = {},
                                onCancelOperation = {},
                                onRemove = {}
                            )

                            else -> Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }

            val startedAt = SystemClock.elapsedRealtime()
            val pssBeforeKb = Debug.getPss()
            var maxPssKb = pssBeforeKb
            var maxComposedImages = 0
            repeat(PRODUCT_COUNT) { index ->
                composeRule.onNodeWithTag(PERFORMANCE_LIST_TAG).performScrollToIndex(index)
                composeRule.waitForIdle()
                val composedImages = composeRule.onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
                ).fetchSemanticsNodes().size
                maxComposedImages = maxOf(maxComposedImages, composedImages)
                maxPssKb = maxOf(maxPssKb, Debug.getPss())
            }
            assertTrue(maxComposedImages in 1 until PRODUCT_COUNT)

            composeRule.runOnIdle { showList.value = false }
            repeat(EDITOR_OPEN_COUNT) { index ->
                composeRule.runOnIdle { openEditor.value = index }
                composeRule.waitForIdle()
                composeRule.onNodeWithContentDescription(
                    context.getString(R.string.product_image_main)
                ).assertIsDisplayed()
                maxPssKb = maxOf(maxPssKb, Debug.getPss())
                composeRule.runOnIdle { openEditor.value = null }
                composeRule.waitForIdle()
            }
            val pssAfterKb = Debug.getPss()
            val elapsedMilliseconds = SystemClock.elapsedRealtime() - startedAt
            val metrics = JSONObject()
                .put("products", PRODUCT_COUNT)
                .put("editorOpens", EDITOR_OPEN_COUNT)
                .put("elapsedMilliseconds", elapsedMilliseconds)
                .put("pssBeforeKb", pssBeforeKb)
                .put("pssMaxKb", maxPssKb)
                .put("pssAfterKb", pssAfterKb)
                .put("maxComposedImages", maxComposedImages)
                .put("cacheMemoryBytes", cacheSnapshot.memoryBytes)
                .put("cacheMemoryEntries", cacheSnapshot.memoryEntries)
                .put("cacheDiskBytes", cacheSnapshot.diskBytes)
                .put("cacheDiskEntries", cacheSnapshot.diskEntries)
            evidenceFile("07-performance-metrics.json").writeText(metrics.toString(2))

            println(
                "TASK138_ANDROID_PERFORMANCE products=$PRODUCT_COUNT opens=$EDITOR_OPEN_COUNT " +
                    "pss_before_kb=$pssBeforeKb pss_max_kb=$maxPssKb " +
                    "pss_after_kb=$pssAfterKb max_composed=$maxComposedImages " +
                    "cache_memory_bytes=${cacheSnapshot.memoryBytes} " +
                    "cache_disk_bytes=${cacheSnapshot.diskBytes} " +
                    "cache_entries=${cacheSnapshot.diskEntries}"
            )
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Composable
    private fun ListEvidence(bytes: ByteArray, contentDescription: String) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("TASK-138 synthetic product list")
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ProductImagePreview(
                    state = ProductImageUiState(ProductImageUiStatus.ABSENT),
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .size(112.dp)
                        .testTag(LIST_PLACEHOLDER_TAG)
                )
                ProductImagePreview(
                    state = readyState(bytes, ProductImageLoadSource.NETWORK),
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .size(112.dp)
                        .testTag(LIST_THUMB_TAG)
                )
            }
        }
    }

    @Composable
    private fun DetailEvidence(bytes: ByteArray, mainDescription: String) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("TASK-138 synthetic product detail")
            ProductImagePreview(
                state = readyState(bytes, ProductImageLoadSource.NETWORK),
                contentDescription = mainDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(320.dp)
                    .height(240.dp)
                    .testTag(DETAIL_PREVIEW_TAG)
            )
        }
    }

    @Composable
    private fun PerformanceList(bytes: ByteArray, contentDescription: String) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(PERFORMANCE_LIST_TAG)
        ) {
            items(
                items = (0 until PRODUCT_COUNT).toList(),
                key = { it }
            ) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProductImagePreview(
                        state = readyState(bytes, ProductImageLoadSource.CACHE),
                        contentDescription = "$contentDescription ${index + 1}",
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Synthetic product ${index + 1}")
                }
            }
        }
    }

    private fun assumeVisualGate() {
        assumeTrue(
            "TASK-138 visual evidence is opt-in. Pass -e task138VisualGate true.",
            isProductImageOptInEnabled(
                InstrumentationRegistry.getArguments().getString("task138VisualGate")
            )
        )
    }

    private fun readyState(
        bytes: ByteArray,
        source: ProductImageLoadSource,
        status: ProductImageUiStatus = ProductImageUiStatus.READY,
        mutationPhase: ProductImageMutationPhase? = null
    ) = ProductImageUiState(
        status = status,
        bytes = bytes,
        versionId = VERSION_ID,
        source = source,
        mutationPhase = mutationPhase
    )

    private fun syntheticProduct(id: Long = 138L) = Product(
        id = id,
        barcode = "task138-synthetic-$id",
        productName = "TASK-138 synthetic product $id",
        primaryImageVersionId = VERSION_ID
    )

    private fun solidJpeg(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
            eraseColor(color)
        }
        return try {
            java.io.ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun performanceThumbJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(1_200, 900, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
        }
        val pixels = IntArray(bitmap.width * bitmap.height) { index ->
            val value = index * 1_103_515_245 + 12_345
            Color.rgb(value and 0xff, value ushr 8 and 0xff, value ushr 16 and 0xff)
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return try {
            ProductImageProcessor().prepareBitmap(bitmap).thumb.bytes
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertDecodedDimensions(
        bytes: ByteArray,
        maxSide: Int,
        expectedWidth: Int,
        expectedHeight: Int
    ) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        assertEquals(expectedWidth, options.outWidth)
        assertEquals(expectedHeight, options.outHeight)
        assertEquals(4 * options.outHeight, 3 * options.outWidth)
        assertTrue(maxOf(options.outWidth, options.outHeight) <= maxSide)
    }

    private fun assertSquareAndContained(tag: String) {
        val root = composeRule.onNodeWithTag(ROOT_TAG).fetchSemanticsNode().boundsInRoot
        val child = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(child.width - child.height) <= 1f)
        assertTrue(child.left >= root.left && child.top >= root.top)
        assertTrue(child.right <= root.right && child.bottom <= root.bottom)
    }

    private fun centerPixel(tag: String): Int {
        val bitmap = composeRule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    private fun assertMostlyRed(pixel: Int) {
        assertTrue(Color.red(pixel) > Color.blue(pixel) + 100)
    }

    private fun assertMostlyBlue(pixel: Int) {
        assertTrue(Color.blue(pixel) > Color.red(pixel) + 100)
    }

    private fun saveRootScreenshot(fileName: String) {
        saveImage(composeRule.onNodeWithTag(ROOT_TAG).captureToImage(), evidenceFile(fileName))
    }

    private fun saveImage(image: ImageBitmap, file: File) {
        file.outputStream().use { output ->
            assertTrue(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        assertTrue(file.length() > 0L)
    }

    private fun evidenceFile(fileName: String): File {
        val externalRoot = requireNotNull(context.getExternalFilesDir("task138-evidence"))
        val directory = File(externalRoot, "TASK-138/android")
        assertTrue(directory.isDirectory || directory.mkdirs())
        return File(directory, fileName)
    }

    private fun productUuid(index: Int): String = String.format(
        Locale.US,
        "13800000-0000-4000-8000-%012d",
        index + 1
    )

    private enum class VisualScenario {
        LIST,
        DETAIL_THUMB,
        DETAIL_MAIN,
        EDITOR_UPLOAD,
        OFFLINE_CACHE,
        ERROR_FALLBACK
    }

    private companion object {
        const val PRODUCT_COUNT = 200
        const val EDITOR_OPEN_COUNT = 20
        const val ROOT_TAG = "task138-visual-root"
        const val LIST_PLACEHOLDER_TAG = "task138-list-placeholder"
        const val LIST_THUMB_TAG = "task138-list-thumbnail"
        const val DETAIL_PREVIEW_TAG = "task138-detail-preview"
        const val PERFORMANCE_LIST_TAG = "task138-performance-list"
        const val ACCOUNT_ID = "13800000-0000-4000-8000-000000009001"
        const val SHOP_ID = "13800000-0000-4000-8000-000000009002"
        const val VERSION_ID = "13800000-0000-4000-8000-000000009003"
    }
}
