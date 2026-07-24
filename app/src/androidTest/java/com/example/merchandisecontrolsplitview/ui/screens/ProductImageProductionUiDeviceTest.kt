package com.example.merchandisecontrolsplitview.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadSource
import com.example.merchandisecontrolsplitview.productimage.ProductImageMutationPhase
import com.example.merchandisecontrolsplitview.productimage.isProductImageOptInEnabled
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiState
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiStatus
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test opt-in sui composable reali di produzione; nessun hook sintetico. */
@RunWith(AndroidJUnit4::class)
class ProductImageProductionUiDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun productRowUses80DpThumbAndWrapsLongContentAtLargeFontScale() {
        assumeProductionUiGate()
        val longName = "Prodotto con un nome volutamente molto lungo che deve andare a capo senza perdere informazioni"
        val longSecondName = "Secondo nome esteso per verificare il wrapping reale della riga prodotto"
        val longSupplier = "Fornitore con denominazione completa e particolarmente lunga"
        val longCategory = "Categoria di inventario con descrizione estesa"
        val product = Product(
            id = 139L,
            barcode = "TASK-139-123456789012345678901234567890",
            itemNumber = "ITEM-123456789012345",
            productName = longName,
            secondProductName = longSecondName,
            purchasePrice = 12345.0,
            retailPrice = 23456.0,
            stockQuantity = 9876.0
        )
        val details = ProductWithDetails(
            product = product,
            supplierName = longSupplier,
            categoryName = longCategory,
            lastPurchase = product.purchasePrice,
            prevPurchase = 10000.0,
            lastRetail = product.retailPrice,
            prevRetail = 20000.0
        )
        val widthDp = mutableStateOf(320)
        val fontScale = mutableStateOf(1.0f)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale.value)
            ) {
                MerchandiseControlTheme(darkTheme = false) {
                    Surface {
                        Box(
                            modifier = Modifier
                                .width(widthDp.value.dp)
                                .testTag(ROW_ROOT_TAG)
                        ) {
                            ProductRow(
                                productDetails = details,
                                imageState = ProductImageUiState(ProductImageUiStatus.ABSENT),
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }
        val density = context.resources.displayMetrics.density
        val cases = listOf(
            320 to 1.0f,
            320 to 1.3f,
            320 to 1.6f,
            375 to 1.0f,
            375 to 1.3f,
            375 to 1.6f,
            430 to 1.0f,
            430 to 1.3f,
            430 to 1.6f
        )
        cases.forEach { (caseWidthDp, caseFontScale) ->
            composeRule.runOnIdle {
                widthDp.value = caseWidthDp
                fontScale.value = caseFontScale
            }
            composeRule.waitForIdle()

            val root = composeRule.onNodeWithTag(ROW_ROOT_TAG).fetchSemanticsNode().boundsInRoot
            val thumbnail = composeRule.onNode(
                matcher = SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.product_image_state_empty)
                ),
                useUnmergedTree = true
            )
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "thumbnail width=${thumbnail.width}, expected=${80f * density}, density=$density",
                abs(thumbnail.width - (80f * density)) <= density
            )
            assertTrue(
                "thumbnail height=${thumbnail.height}, expected=${80f * density}, density=$density",
                abs(thumbnail.height - (80f * density)) <= density
            )
            assertTrue(thumbnail.left >= root.left && thumbnail.right <= root.right)
            listOf(
                longName,
                longSecondName,
                longSupplier,
                longCategory,
                context.getString(R.string.header_stock_quantity),
                context.getString(R.string.price_history)
            ).forEach { text ->
                val bounds = composeRule.onNodeWithText(text)
                    .assertExists()
                    .fetchSemanticsNode()
                    .boundsInRoot
                assertTrue(
                    "$caseWidthDp dp at $caseFontScale font: '$text' escapes horizontally",
                    bounds.left >= root.left && bounds.right <= root.right
                )
                assertTrue(
                    "$caseWidthDp dp at $caseFontScale font: '$text' escapes vertically",
                    bounds.top >= root.top && bounds.bottom <= root.bottom
                )
            }

            val fontTag = (caseFontScale * 10).toInt()
            saveScreenshot(
                ROW_ROOT_TAG,
                "01-production-row-${caseWidthDp}dp-font-$fontTag.png"
            )
        }
    }

    @Test
    fun editorUsesSyncedGatingCameraFirstPendingPreviewAndSeparateRemove() {
        assumeProductionUiGate()
        val scenario = mutableStateOf(EditorScenario.LOCAL_ONLY)
        val oldBytes = solidJpeg(Color.rgb(210, 30, 30))
        val pendingBytes = solidJpeg(Color.rgb(30, 60, 220))
        val existing = Product(
            id = 139L,
            barcode = "TASK-139",
            productName = "TASK-139 production editor",
            primaryImageVersionId = VERSION_ID
        )
        val localOnly = existing.copy(id = 0L, primaryImageVersionId = null)

        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(EDITOR_ROOT_TAG)
                ) {
                    when (scenario.value) {
                        EditorScenario.LOCAL_ONLY -> ProductImageEditorSection(
                            product = localOnly,
                            mainState = null,
                            thumbState = null,
                            apiConfigured = true,
                            canManage = true,
                            hasSyncedRemoteRef = false,
                            currentImageVersionId = null,
                            onChoosePhoto = {},
                            onTakePhoto = {},
                            onRetry = {},
                            onCancelOperation = {},
                            onDiscardFailure = {},
                            onRemove = {}
                        )

                        EditorScenario.READY -> ProductImageEditorSection(
                            product = existing,
                            mainState = readyState(oldBytes),
                            thumbState = null,
                            apiConfigured = true,
                            canManage = true,
                            hasSyncedRemoteRef = true,
                            currentImageVersionId = VERSION_ID,
                            onChoosePhoto = {},
                            onTakePhoto = {},
                            onRetry = {},
                            onCancelOperation = {},
                            onDiscardFailure = {},
                            onRemove = {}
                        )

                        EditorScenario.UPLOADING -> ProductImageEditorSection(
                            product = existing,
                            mainState = readyState(oldBytes).copy(
                                status = ProductImageUiStatus.UPLOADING,
                                pendingPreviewBytes = pendingBytes,
                                mutationPhase = ProductImageMutationPhase.UPLOAD_MAIN
                            ),
                            thumbState = null,
                            apiConfigured = true,
                            canManage = true,
                            hasSyncedRemoteRef = true,
                            currentImageVersionId = VERSION_ID,
                            onChoosePhoto = {},
                            onTakePhoto = {},
                            onRetry = {},
                            onCancelOperation = {},
                            onDiscardFailure = {},
                            onRemove = {}
                        )

                        EditorScenario.ERROR_WITH_STALE_THUMB_LOAD -> ProductImageEditorSection(
                            product = existing,
                            mainState = readyState(oldBytes).copy(
                                status = ProductImageUiStatus.ERROR,
                                errorCode = "image_request_failed"
                            ),
                            thumbState = ProductImageUiState(
                                status = ProductImageUiStatus.LOADING,
                                versionId = VERSION_ID
                            ),
                            apiConfigured = true,
                            canManage = true,
                            hasSyncedRemoteRef = true,
                            currentImageVersionId = VERSION_ID,
                            onChoosePhoto = {},
                            onTakePhoto = {},
                            onRetry = {},
                            onCancelOperation = {},
                            onDiscardFailure = {},
                            onRemove = {}
                        )
                    }
                }
            }
        }

        val takePhoto = context.getString(R.string.product_image_camera)
        val takeNewPhoto = context.getString(R.string.product_image_camera_new)
        val library = context.getString(R.string.product_image_choose)
        val remove = context.getString(R.string.product_image_remove)
        val retry = context.getString(R.string.product_image_retry)
        val discard = context.getString(R.string.product_image_discard_failed_attempt)
        composeRule.onNodeWithText(context.getString(R.string.product_image_save_first)).assertExists()
        composeRule.onNodeWithText(takePhoto).assertIsNotEnabled()
        composeRule.onNodeWithText(library).assertIsNotEnabled()
        composeRule.onNodeWithText(remove).assertDoesNotExist()

        composeRule.runOnIdle { scenario.value = EditorScenario.READY }
        composeRule.waitForIdle()
        val cameraNode = composeRule.onNodeWithText(takeNewPhoto).assertIsEnabled()
        val libraryNode = composeRule.onNodeWithText(library).assertIsEnabled()
        val removeNode = composeRule.onNodeWithText(remove).assertIsEnabled()
        val cameraBounds = cameraNode.fetchSemanticsNode().boundsInRoot
        val libraryBounds = libraryNode.fetchSemanticsNode().boundsInRoot
        val removeBounds = removeNode.fetchSemanticsNode().boundsInRoot
        assertTrue(cameraBounds.left < libraryBounds.left)
        assertTrue(removeBounds.top >= maxOf(cameraBounds.bottom, libraryBounds.bottom))

        composeRule.runOnIdle { scenario.value = EditorScenario.UPLOADING }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.product_image_progress_upload_main))
            .assertExists()
        composeRule.onNodeWithText(context.getString(R.string.product_image_cancel_operation))
            .assertIsEnabled()
        val preview = composeRule.onNodeWithContentDescription(
            context.getString(R.string.product_image_main)
        ).captureToImage().asAndroidBitmap()
        val pixel = preview.getPixel(preview.width / 2, preview.height / 2)
        assertTrue(Color.blue(pixel) > Color.red(pixel) + 100)

        composeRule.runOnIdle { scenario.value = EditorScenario.ERROR_WITH_STALE_THUMB_LOAD }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(takeNewPhoto).assertIsEnabled()
        composeRule.onNodeWithText(library).assertIsEnabled()
        composeRule.onNodeWithText(retry).assertIsEnabled()
        composeRule.onNodeWithText(discard).assertIsEnabled()

        saveScreenshot(EDITOR_ROOT_TAG, "02-production-editor-upload.png")
    }

    private fun readyState(bytes: ByteArray) = ProductImageUiState(
        status = ProductImageUiStatus.READY,
        bytes = bytes,
        versionId = VERSION_ID,
        source = ProductImageLoadSource.CACHE
    )

    private fun solidJpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
            eraseColor(color)
        }
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveScreenshot(tag: String, fileName: String) {
        val image = composeRule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val file = evidenceFile(fileName)
        file.outputStream().use { output ->
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        assertTrue(file.length() > 0L)
    }

    private fun evidenceFile(fileName: String): File {
        val root = requireNotNull(context.getExternalFilesDir("task139-evidence"))
        val directory = File(root, "TASK-139/android-addendum")
        assertTrue(directory.isDirectory || directory.mkdirs())
        return File(directory, fileName)
    }

    private fun assumeProductionUiGate() {
        assumeTrue(
            "TASK-139 production UI evidence is opt-in. Pass -e task139AndroidProductionUiGate true.",
            isProductImageOptInEnabled(
                InstrumentationRegistry.getArguments().getString("task139AndroidProductionUiGate")
            )
        )
    }

    private enum class EditorScenario {
        LOCAL_ONLY,
        READY,
        UPLOADING,
        ERROR_WITH_STALE_THUMB_LOAD
    }

    private companion object {
        const val ROW_ROOT_TAG = "task139-production-row-root"
        const val EDITOR_ROOT_TAG = "task139-production-editor-root"
        const val VERSION_ID = "13900000-0000-4000-8000-000000000001"
    }
}
