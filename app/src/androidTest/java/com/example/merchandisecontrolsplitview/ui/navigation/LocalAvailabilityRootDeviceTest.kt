package com.example.merchandisecontrolsplitview.ui.navigation

import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.data.*
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production NavHost/screens + file-backed repository, while actual recovery RPC is held.
 * Controlled adapters prove local integration, never authenticated TEST/backend acceptance.
 */
@RunWith(AndroidJUnit4::class)
class LocalAvailabilityRootDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as MerchandiseControlApplication
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val store = ViewModelStore()
    private var database: AppDatabase? = null
    private var auto: CatalogAutoSyncCoordinator? = null
    private val release = CompletableDeferred<Unit>()
    private val visible = mutableStateOf(true)

    @After fun cleanup() {
        release.complete(Unit)
        auto?.shutdown()
        runBlocking { job.cancelAndJoin() }
        compose.runOnUiThread { store.clear() }
        database?.close()
        context.deleteDatabase(DB_NAME)
        ShopSyncRecoveryTestHooks.reset()
    }

    @Test fun actualEmptyFirstBootstrapShellIsNavigableBeforeRecoveryRelease() {
        clearEvidence("android-root-empty-held-options.png","android-root-empty-activated.png")
        context.deleteDatabase(DB_NAME)
        val db=openDatabase().also { database=it }
        val tracker=CatalogSyncStateTracker(Task126BusinessDataScopeState.checking())
        val repository=DefaultInventoryRepository(db,tracker)
        val empty=Task139ShopSyncRecoveryForceStopDeviceTest.EmptyRecoveryRemote()
        val entered=CompletableDeferred<Unit>()
        val held=object : ShopSyncReadRemoteDataSource by empty {
            override suspend fun recoveryPage(context: ShopSyncRpcContext,domain: ShopSyncRowDomain,afterId:String?,limit:Int):Result<ShopSyncRecoveryPage> {
                if(domain==ShopSyncRowDomain.PRODUCTS) { entered.complete(Unit);release.await() }
                return empty.recoveryPage(context,domain,afterId,limit)
            }
        }
        runBlocking {
            db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId=DEVICE,createdAtMs=1L))
            db.syncRecoveryJournalDao().upsert(journal(SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED))
        }
        val recovery=scope.async { coordinator(db,repository,held,tracker).recover(OWNER,shop(),ownerScope()) }
        runBlocking { withTimeout(10_000) { entered.await() } }
        lateinit var databaseVM:DatabaseViewModel;lateinit var excelVM:ExcelViewModel
        compose.runOnUiThread {
            databaseVM=DatabaseViewModel(app,repository);store.put("database",databaseVM)
            excelVM=ExcelViewModel(app,repository);store.put("excel",excelVM)
        }
        compose.setContent {
            val business by tracker.businessDataScopeState.collectAsState()
            val progress by tracker.state.collectAsState()
            MerchandiseControlTheme(darkTheme=false) {
                AppNavGraphContent(app,excelVM,databaseVM,true,AuthState.SignedIn(OWNER,"root-test@example.invalid"),
                    progress,ShopContext(OWNER,emptyList(),shop(),isLoading=true,syncAllowed=false,localAccessAllowed=true),business)
            }
        }
        for(tab in listOf("history","databaseScreen","filePicker")) {
            compose.onNodeWithTag("root-tab-$tab").assertIsEnabled().performClick().assertIsSelected()
            compose.onNodeWithTag("root-business-placeholder").assertExists()
            compose.onNodeWithTag("database-search").assertDoesNotExist()
        }
        compose.onNodeWithTag("root-tab-optionsScreen").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithTag("root-business-placeholder").assertDoesNotExist()
        assertFalse(release.isCompleted)
        assertEquals(0,runBlocking { db.productDao().count() })
        capture("android-root-empty-held-options.png")
        release.complete(Unit)
        assertTrue(runBlocking { withTimeout(15_000) { recovery.await() } } is ShopSyncRecoveryResult.Activated)
        val ready=runBlocking { repository.resolveBusinessDataScope(ownerScope()) }
        compose.runOnIdle { tracker.updateBusinessDataScopeState(ready) }
        compose.onNodeWithTag("root-tab-optionsScreen").assertIsSelected()
        compose.onNodeWithTag("root-tab-databaseScreen").performClick()
        compose.onNodeWithTag("root-business-placeholder").assertDoesNotExist()
        compose.onNodeWithTag("database-search").assertExists()
        assertEquals(0,runBlocking { db.productDao().count() })
        capture("android-root-empty-activated.png")
    }

    @Test fun actualOptionsAccountWrapsAtLargeFontInFourLanguages() {
        val locales=listOf("it","en","es","zh")
        clearEvidence(*locales.map { "android-options-$it-large-font.png" }.toTypedArray())
        context.deleteDatabase(DB_NAME)
        val db=openDatabase().also { database=it };val repository=DefaultInventoryRepository(db)
        lateinit var databaseVM:DatabaseViewModel;lateinit var excelVM:ExcelViewModel
        compose.runOnUiThread {
            databaseVM=DatabaseViewModel(app,repository);store.put("database",databaseVM)
            excelVM=ExcelViewModel(app,repository);store.put("excel",excelVM)
        }
        val language=mutableStateOf("it")
        compose.setContent {
            val cfg=Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language.value));fontScale=1.6f
            }
            val localized=context.createConfigurationContext(cfg)
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides cfg,
                LocalDensity provides Density(density,1.6f)) {
                MerchandiseControlTheme(darkTheme=false) {
                    AppNavGraphContent(app,excelVM,databaseVM,true,
                        AuthState.SignedIn(OWNER,"root-test@a-long-example-domain-for-confirmed-account-text-with-large-font.example.invalid"),
                        CatalogSyncProgressState.idle(),ShopContext(OWNER,emptyList(),shop(),syncAllowed=false,localAccessAllowed=true),
                        Task126BusinessDataScopeState.checking())
                }
            }
        }
        compose.onNodeWithTag("root-tab-optionsScreen").performClick()
        for(locale in locales) {
            compose.runOnIdle { language.value=locale }
            for(tag in listOf("options-account-title","options-account-email")) {
                val layouts=mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
                assertEquals(1,layouts.size)
                capture("android-options-$locale-large-font.png")
                val layout=layouts.single()
                assertFalse("$locale/$tag overflow: width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, size=${layout.size}, lines=${layout.lineCount}, constraints=${layout.layoutInput.constraints}, maxLines=${layout.layoutInput.maxLines}",layout.hasVisualOverflow)
            }
            for(route in listOf("filePicker","history","databaseScreen","optionsScreen")) {
                val layouts=mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("root-tab-label-$route",useUnmergedTree=true).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
                assertEquals(1,layouts.size)
                assertFalse("$locale/$route label overflow at fontScale1.6",layouts.single().hasVisualOverflow)
            }
            compose.onNodeWithTag("options-account-signout").performScrollTo().assertIsDisplayed().assertIsEnabled()
            capture("android-options-$locale-large-font.png")
        }
    }

    @Test fun actualRootTabSearchEditorSaveBeforeRecoveryReleaseThenAutomaticQueueAndReopen() {
        clearEvidence("android-root-held-draft.png","android-root-held-saved.png","android-root-held-second-draft.png","android-root-activated-draft.png","android-root-activated-saved.png")
        context.deleteDatabase(DB_NAME)
        val db = openDatabase().also { database = it }
        val initialRepo = DefaultInventoryRepository(db)
        val empty = Task139ShopSyncRecoveryForceStopDeviceTest.EmptyRecoveryRemote()
        runBlocking {
            db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId=DEVICE,createdAtMs=1L))
            db.syncRecoveryJournalDao().upsert(journal(SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED))
            assertTrue(coordinator(db,initialRepo,empty).recover(OWNER,shop(),ownerScope()) is ShopSyncRecoveryResult.Activated)
            repeat(30) { i -> initialRepo.addProduct(Product(barcode="ROOT-%03d".format(i),
                productName="Held product %03d".format(i),purchasePrice=10.0,retailPrice=20.0,stockQuantity=2.0)) }
            db.syncRecoveryJournalDao().upsert(journal(SyncRecoveryAuthorizationModes.SAME_SCOPE))
        }
        val walBefore=db.openHelper.writableDatabase.isWriteAheadLoggingEnabled
        assertTrue("owned real-root database uses production WAL",walBefore)
        val tracker = CatalogSyncStateTracker(runBlocking { initialRepo.resolveOfflineBusinessDataScope(ownerScope(),true) })
        assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
        assertFalse(tracker.businessDataScopeState.value.allowsCloudSync)
        val repository = DefaultInventoryRepository(db,tracker)
        val observedId=runBlocking { requireNotNull(repository.findProductByBarcode("ROOT-024")).id }
        val hotDao=MutableStateFlow<Boolean?>(null)
        val hotFailure=MutableStateFlow<String?>(null)
        scope.launch { try { repository.observeProductCloudConfirmed(observedId).collect { hotDao.value=it } }
            catch(error:Exception) { hotFailure.value=error.javaClass.simpleName } }
        val entered = CompletableDeferred<Unit>()
        val held = object : ShopSyncReadRemoteDataSource by empty {
            override suspend fun recoveryPage(context: ShopSyncRpcContext,domain: ShopSyncRowDomain,
                afterId: String?,limit: Int): Result<ShopSyncRecoveryPage> {
                if (domain == ShopSyncRowDomain.PRODUCTS) { entered.complete(Unit);release.await() }
                return empty.recoveryPage(context,domain,afterId,limit)
            }
        }
        val recoveryFailure=AtomicReference<Exception?>(null)
        ShopSyncRecoveryTestHooks.onRecoveryFailure={ recoveryFailure.set(it) }
        val recovery = scope.async { coordinator(db,repository,held,tracker).recover(OWNER,shop(),ownerScope()) }
        runBlocking { withTimeout(10_000) { entered.await() } }
        val cloud = RootCatalogRemote()
        val price = RootPriceRemote()
        auto = CatalogAutoSyncCoordinator(repository,cloud,price,
            authFlow=MutableStateFlow<AuthState>(AuthState.SignedIn(OWNER,"root-test@example.invalid")),
            selectedShopProvider=::shop,syncStateTracker=tracker,debounceMs=100)
        repository.onProductCatalogChanged = auto!!::onLocalProductChanged
        repository.onCatalogChanged = auto!!::onLocalCatalogChanged
        lateinit var databaseVM: DatabaseViewModel
        lateinit var excelVM: ExcelViewModel
        compose.runOnUiThread {
            databaseVM=DatabaseViewModel(app,repository);store.put("database",databaseVM)
            excelVM=ExcelViewModel(app,repository);store.put("excel",excelVM)
        }
        compose.setContent {
            val business by tracker.businessDataScopeState.collectAsState()
            val progress by tracker.state.collectAsState()
            MerchandiseControlTheme(darkTheme=false) {
              if (visible.value) {
                AppNavGraphContent(app,excelVM,databaseVM,true,
                    AuthState.SignedIn(OWNER,"root-test@example.invalid"),progress,
                    ShopContext(OWNER,emptyList(),shop(),isLoading=true,syncAllowed=false,localAccessAllowed=true),business)
              }
            }
        }
        compose.onNodeWithTag("root-tab-filePicker").assertIsEnabled().assertIsSelected()
        compose.onNodeWithTag("root-tab-history").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithTag("root-tab-databaseScreen").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithTag("database-search").performTextReplacement("ROOT-")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("database-product-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("database-product-list").performScrollToNode(hasText("ROOT-024",substring=true))
        val scrollBefore = scrollValue()
        compose.onNodeWithText("ROOT-024",substring=true).performClick()
        compose.onNodeWithTag("task141.edit.dialog-root").assertExists()
        compose.onNodeWithTag("product-editor-name").performScrollTo().performClick().performTextReplacement("Draft before network release")
        assertFalse("root was used before release",release.isCompleted)
        compose.onNodeWithTag("product-editor-name").assertIsFocused()
        compose.runOnIdle {
            tracker.update(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
            tracker.updateBusinessDataScopeState(tracker.businessDataScopeState.value.copy(
                status=Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,errorCode="controlled retry"))
        }
        compose.onNodeWithText("Draft before network release").assertExists()
        compose.onNodeWithTag("product-editor-name").assertIsFocused()
        compose.onNodeWithTag("root-tab-databaseScreen").assertIsSelected()
        assertEquals("ROOT-",databaseVM.filter.value)
        assertEquals(scrollBefore,scrollValue(),0f)
        capture("android-root-held-draft.png")
        val pricesBefore=runBlocking { db.productPriceDao().countAll() }
        compose.onNodeWithTag("product-editor-save").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task141.edit.dialog-root").fetchSemanticsNodes().isEmpty() }
        val saved=runBlocking { requireNotNull(repository.findProductByBarcode("ROOT-024")) }
        assertEquals("Draft before network release",saved.productName)
        assertFalse("durable Save preceded transport release",release.isCompleted)
        assertEquals(pricesBefore,runBlocking { db.productPriceDao().countAll() })
        assertTrue(cloud.products.isEmpty())
        compose.waitUntil(10_000) { databaseVM.lastProductSaveCloudConfirmed.value==false }
        compose.onNodeWithText(context.getString(com.example.merchandisecontrolsplitview.R.string.success_product_updated)).assertExists()
        capture("android-root-held-saved.png")
        // A second, still-unsaved editor must survive the actual generation publication too.
        compose.onNodeWithTag("database-product-list").performScrollToNode(hasText("ROOT-026",substring=true))
        val cutoverScroll=scrollValue()
        compose.onNodeWithText("ROOT-026",substring=true).performClick()
        compose.onNodeWithTag("product-editor-name").performScrollTo().performClick().performTextReplacement("Unsaved draft across actual activation")
        compose.onNodeWithTag("product-editor-name").assertIsFocused()
        capture("android-root-held-second-draft.png")
        release.complete(Unit)
        val recovered = runBlocking { withTimeout(15_000) { recovery.await() } }
        assertTrue("$recovered; actual isolated cause=${recoveryFailure.get()?.stackTraceToString()}", recovered is ShopSyncRecoveryResult.Activated)
        assertEquals("cutover preserves the live Room writer and WAL",walBefore,db.openHelper.writableDatabase.isWriteAheadLoggingEnabled)
        assertEquals(saved.id,runBlocking { repository.findProductByBarcode("ROOT-024") }?.id)
        val ready = runBlocking { repository.resolveBusinessDataScope(ownerScope()) }
        assertTrue(ready.toString(), ready.allowsCloudSync)
        compose.runOnIdle { tracker.updateBusinessDataScopeState(ready) }
        auto!!.onDeviceStatusActive() // Same automatic production trigger following active device/recovery.
        compose.waitUntil(15_000) { cloud.products.values.any { it.barcode=="ROOT-024" && it.productName==saved.productName } &&
            runBlocking { !db.productRemoteRefDao().hasPendingWork() } }
        assertEquals("ROOT-",databaseVM.filter.value)
        compose.onNodeWithTag("root-tab-databaseScreen").assertIsSelected()
        compose.onNodeWithText("Unsaved draft across actual activation").assertExists()
        compose.onNodeWithTag("product-editor-name").assertIsFocused()
        assertEquals(cutoverScroll,scrollValue(),0f)
        capture("android-root-activated-draft.png")
        try {
            compose.waitUntil(10_000) { databaseVM.lastProductSaveCloudConfirmed.value==true }
        } catch (failure: Throwable) {
            val diagnostic=runBlocking {
                "vm=${databaseVM.lastProductSaveCloudConfirmed.value}, hotDao=${hotDao.value}, hotFailure=${hotFailure.value}, roomAfter=${roomDiagnostics(db)}, dao=${repository.observeProductCloudConfirmed(saved.id).first()}, " +
                    "ref=${db.productRemoteRefDao().getByProductId(saved.id)}, priceBridgePending=${db.productPriceDao().countPriceRowsPendingPriceBridge()}, " +
                    "remotePrices=${price.count()}, receipts=" + db.syncEventOutboxDao().listPending(OWNER,100).map {
                        "${it.domain}:${it.attemptCount}:${it.lastErrorType}" } + ", cloud=${tracker.state.value}"
            }
            throw AssertionError(diagnostic,failure)
        }
        compose.onNodeWithTag("product-editor-save").performClick()
        compose.waitUntil(15_000) { cloud.products.values.any { it.barcode=="ROOT-026" &&
            it.productName=="Unsaved draft across actual activation" } &&
            runBlocking { !db.productRemoteRefDao().hasPendingWork() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(
            com.example.merchandisecontrolsplitview.R.string.product_save_cloud_confirmed)).fetchSemanticsNodes().isNotEmpty() }
        capture("android-root-activated-saved.png")
        auto!!.shutdown();auto=null
        runBlocking { job.cancelAndJoin() }
        compose.runOnIdle { visible.value = false }
        compose.runOnUiThread { store.clear() }
        db.close();database=null
        val reopened = openDatabase()
        try {
            val durable=runBlocking { DefaultInventoryRepository(reopened).findProductByBarcode("ROOT-024") }
            assertEquals(saved.id,durable?.id);assertEquals(saved.productName,durable?.productName)
            assertFalse(runBlocking { reopened.productRemoteRefDao().hasPendingWork() })
            val second=runBlocking { DefaultInventoryRepository(reopened).findProductByBarcode("ROOT-026") }
            assertEquals("Unsaved draft across actual activation",second?.productName)
            assertTrue(runBlocking { DefaultInventoryRepository(reopened).resolveOfflineBusinessDataScope(ownerScope(),true) }.allowsLocalOperations)
            reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { reopened.close() }
    }

    private suspend fun roomDiagnostics(db:AppDatabase):String = db.withTransaction {
        val sql=db.openHelper.readableDatabase
        val triggers=sql.query("SELECT name FROM sqlite_temp_master WHERE type='trigger' AND name LIKE 'room_%' ORDER BY name").use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0)) } }
        val invalidated=try { sql.query("SELECT table_id,invalidated FROM room_table_modification_log ORDER BY table_id").use { c ->
            buildList { while(c.moveToNext()) add("${c.getLong(0)}:${c.getLong(1)}") } } }
            catch(error:android.database.sqlite.SQLiteException) { listOf("tracker_table_absent:${error.javaClass.simpleName}") }
        "triggers=$triggers; invalidated=$invalidated"
    }

    private fun scrollValue(): Float = compose.onNodeWithTag("database-product-list",useUnmergedTree=true)
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun capture(name: String) {
        val file=File(context.filesDir,"local-availability-root/$name");file.parentFile!!.mkdirs()
        val target = if (name.contains("draft")) compose.onNodeWithTag("task141.edit.dialog-root") else compose.onRoot()
        file.outputStream().use { target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun clearEvidence(vararg names:String) {
        names.forEach { File(context.filesDir,"local-availability-root/$it").delete() }
    }
    private fun openDatabase() = Room.databaseBuilder(context,AppDatabase::class.java,DB_NAME)
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray()).build().also { it.openHelper.writableDatabase }
    private fun coordinator(db: AppDatabase,repo: DefaultInventoryRepository,remote: ShopSyncReadRemoteDataSource,
        tracker: CatalogSyncStateTracker?=null) = ShopSyncRecoveryCoordinator(context,db,repo,remote,
        registerDeviceForRecovery={ Result.success(ShopDeviceRegistrationResult(ok=true,code="success",shopId=it)) },
        scopeStillValid={ account,selected -> account==OWNER && selected==SHOP },
        activationBoundary={ block -> if (tracker==null) block() else tracker.withBusinessDataScopeTransition { block() } },
        availableStorageBytes={Long.MAX_VALUE})
    private fun shop() = SelectedShop(SHOP,"ROOT","Isolated root test","shop_owner","active",true)
    private fun ownerScope() = task126ActiveOwnerStoreScope(OWNER,shop())
    private fun journal(mode: String) = SyncRecoveryJournal(ownerHash=ownerScope().ownerHash,
        storeScope=ownerScope().storeId,shopId=SHOP,deviceId=DEVICE,authorizationMode=mode,
        phase=SyncRecoveryJournalPhases.REQUIRED,reason=if(mode==SyncRecoveryAuthorizationModes.SAME_SCOPE) "root held recovery" else SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
        blockingEventId=42L,attemptCount=0,createdAtMs=1L,updatedAtMs=1L,nextRetryAtMs=1L)
    private companion object {
        const val DB_NAME="task143-local-availability-root.db"
        const val OWNER="10000000-0000-4000-8000-000000000001"
        const val SHOP="10000000-0000-4000-8000-000000000003"
        const val DEVICE="android-recovery-force-stop-device"
    }
}

private class RootCatalogRemote : CatalogRemoteDataSource {
    override val isConfigured=true
    val products=ConcurrentHashMap<String,InventoryProductRow>()
    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> { rows.forEach { products[it.id]=it };return Result.success(Unit) }
    override suspend fun patchProduct(id:String,ownerUserId:String,patch:InventoryProductPatch):Result<Unit> {
        val previous=products[id] ?: return Result.failure(IllegalStateException("root adapter product missing"))
        if(patch.changedFields!=setOf("productname")) return Result.failure(IllegalStateException("root adapter unexpected patch"))
        products[id]=previous.copy(productName=patch.productName)
        return Result.success(Unit)
    }
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>)=Result.success(Unit)
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>)=Result.success(Unit)
    override suspend fun fetchCatalog()=Result.success(InventoryCatalogFetchBundle(emptyList(),emptyList(),products.values.toList()))
    override suspend fun fetchCatalogByIds(supplierIds: Set<String>,categoryIds: Set<String>,productIds: Set<String>)=
        Result.success(InventoryCatalogFetchBundle(emptyList(),emptyList(),products.values.filter { it.id in productIds },false))
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch)=Result.success(Unit)
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch)=Result.success(Unit)
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch)=Result.success(Unit)
}
private class RootPriceRemote : ProductPriceRemoteDataSource {
    override val isConfigured=true
    private val rows=ConcurrentHashMap<String,InventoryProductPriceRow>()
    fun count()=rows.size
    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> { rows.forEach { this.rows[it.id]=it };return Result.success(Unit) }
    override suspend fun fetchProductPrices()=Result.success(rows.values.toList())
    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>)=Result.success(rows.values.filter { it.id in remoteIds })
}
