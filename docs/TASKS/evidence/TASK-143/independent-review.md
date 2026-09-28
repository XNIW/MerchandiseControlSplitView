# Esito finale del sorgente

Android e iOS APPROVED nella re-review indipendente; i verdetti intermedi sotto sono cronologici e superati dagli addendum finali. Gate Android locali PASS; gate iOS e CI raccolti separatamente dal parent. Nessuna approvazione live implicita.

# Audit funzionale indipendente — 2026-09-28

Reviewer: agente `independent_reviewer`, separato dagli executor Android/iOS. Prima fase: ispezione sorgente read-only; nessuna build/test avviata e nessuna modifica ai repository. Baseline assegnate: Android `d7c4953c4ed6bc2a33cc5dbfd009eb862f70feac`, iOS `30d226d0fb9b8679a1dd034c6e82319645337f22`. Worktree: `/Users/minxiang/.codex/worktrees/mobile-parity-root-cause/{MerchandiseControlSplitView,ios}`. Questo documento non è ancora la review del diff finale.

Letti richiesta utente allegata, AGENTS/CLAUDE, master, task Android 142/iOS 143 e protocolli execution. L'override utente corrente autorizza audit funzionale e agenti separati; task storici non sono stati riaperti dal reviewer. Documentazione storica non trattata come test attuale.

## Rilievi nuovi da riprodurre prima del fix

### R-A01 — P1: editor operativo Android riscrive i campi remoti non modificati dall'utente

Percorso: `ui/screens/DatabaseScreen.kt:515` passa il target iniziale; `EditProductDialog.kt:152–159` conserva campi con `rememberSaveable(sessionId)`, poi `:938–949` crea una copia completa del prodotto; `viewmodel/DatabaseViewModel.kt:persistProductFromEditor` chiama `repository.updateProduct(product)`; `data/InventoryRepository.kt:709–744` legge il prodotto corrente ma esegue `productDao.update(canonicalProduct)` pieno e calcola dirty current→form stale.

Riproduzione deterministica proposta: aprire editor con nome Old/prezzo 10; applicare pull remoto a prezzo 20; modificare soltanto nome New nel form originario; salvare. Dal percorso sorgente il prezzo viene rimesso a 10, marcato dirty e può creare price history 10. Non c'è diff iniziale→form o confronto sovrapposizione con current. La cancellazione degli scope non risolve un aggiornamento remoto nello stesso shop.

Controparte iOS: `EditProductView.swift:save`, righe 822–882, calcola cambi iniziale→form e iniziale→fresh, verifica `Task126ConflictResolver`, applica solo campi utente cambiati. Test da aggiungere Android: merge disjoint, conflitto stesso campo fail-closed, remote delete, zero dirty/history sugli invariati. Evidenza corrente STATIC; il reviewer non ha eseguito questo scenario.

### R-A02 — P1: anagrafica Android e dirty marker non atomici

`InventoryRepository.kt:addSupplier` (869), `addCategory` (1108), `createCatalogEntry` (916), `renameCatalogEntry` (934) separano insert/rename da `touchSupplierDirty`/`touchCategoryDirty` (6304/6317), senza `db.withTransaction`. `withLocalBusinessMutation` (581–588) è una scope lease, non transazione. `deleteCatalogEntry` invece racchiude già le scritture in transazione.

Riproduzione deterministica proposta: supplier già sincronizzato, installare trigger SQLite `BEFORE UPDATE ON supplier_remote_refs` con `RAISE(ABORT,'injected')`, poi rename. L'API fallisce ma il nuovo nome resta committato e revision non cresce; il remoto può poi sovrascriverlo. Ripetere category e creazione. Patch minima suggerita: transazioni Room attorno a write anagrafica e dirty marker, callback dopo commit. Test successo esistenti: `DefaultInventoryRepositoryTest` rename 1913, generic notification 2069; manca fault injection per queste API. Evidenza corrente STATIC, non test eseguito.

## Matrice capacità / sorgenti / test esistenti

Prefissi Android: `app/src/main/java/com/example/merchandisecontrolsplitview/` e `app/src/test/java/com/example/merchandisecontrolsplitview/`. Prefissi iOS: `iOSMerchandiseControl/`, `iOSMerchandiseControlTests/`. Tutti i test qui elencati sono stati soltanto individuati/letti, quindi **NOT_TESTED da questo reviewer**; l'orchestratore deve aggiungere le evidenze effettive della run corrente. `VERIFIED` non è assegnato per sola presenza del codice.

| Capacità / requisito | Android reale | iOS reale | Backend necessario | Test esistenti / stato e rischio |
|---|---|---|---|---|
| Inventario: file→preview→generazione→griglia | `ExcelViewModel.loadFromMultipleUris`, `generateFilteredWithOldPrices`; `PreGenerateScreen`, `GeneratedScreen` | `ExcelSessionViewModel.load`, `generateHistoryEntry`; `PreGenerateView`, `GeneratedView` | Nessuno per uso locale; session contract per sync | `ExcelViewModelTest`, `PreGenerateEntityResolutionTest`; iOS `Task105RealOpsClosureTests`, `Task111ExcelImportParityTests`. NOT_TESTED |
| Salva/annulla inventario, ritorno navigazione | `saveCurrentStateToHistory`, `revertToPreGenerateState`, `GeneratedExitDestinationResolver` | `GeneratedView`, `HistoryEntry` autosave/session persistence | History/session sync per altro device | `GeneratedExitDestinationMatrixTest`, `ImportNavOriginTest`, `ExcelViewModelTest`; iOS `HistorySessionSyncServiceTests`, `InventorySyncServiceTests`. NOT_TESTED |
| Salva editor prodotto, errore, retry/doppio tap | `EditProductDialog`→`startProductEditorSave`→`persistProductFromEditor`→repository transazione prodotto/prezzi/dirty | `EditProductView.save`→`Task126OwnerStoreGate.withLocalMutationFence`→freshContext save prodotto/pending | Offline locale permesso; auto sync dopo commit | Android `DatabaseViewModelTest` failure/retry/post-commit reread/recreation; iOS owner-store/conflict tests. NOT_TESTED; rischio R-A01 da riprodurre |
| Database ricerca/barcode/paginazione | `DatabaseViewModel`, Room Paging/`ProductDao`; scanner ZXing `ScanOptions` | `DatabaseView.filteredProducts`, `@Query Product`, `BarcodeScannerView` AVCapture | Summary RPC solo filtri Storefront | Android search debounce/scanner stale-scope test; iOS `Task105RealOpsClosureTests.testPhysicalCameraBarcodeCaptureCapabilityWhenAvailable` opt-in/capability. NOT_TESTED; iOS filtro locale full-list richiede misura |
| Fornitore/categoria create/rename/assign | `createCatalogEntry`, `renameCatalogEntry`, `addSupplier`, `addCategory`, editor quick-create atomic UI gate | `DatabaseNamedEntityEditorView.save`, `saveSupplier`, `saveCategory`; editor form crea relazione insieme al prodotto | Catalog push/pull e identità remote | Android `DefaultInventoryRepositoryTest` 1817–1936 e `DatabaseViewModelTest`; iOS `CatalogTextIntegrationTests`, `LocalPendingChangeAccumulatorTests`. NOT_TESTED; R-A02 da riprodurre |
| Fornitore/categoria replace/delete con riferimenti | `deleteCatalogEntry` transazione, `ReplaceWithExisting`, `CreateNewAndReplace`, `ClearAssignments` | `DatabaseView.deleteEntity`, `createReplacementAndDelete`, mutation fence | Tombstone + catalog deps | Android test reassign/clear e dirty-field-only; iOS `Task118AutomaticDomainTests` tombstones, `SyncEventIncrementalDomainApplyServiceTests` relation rollback. NOT_TESTED |
| Prezzi e history prezzi | `updateCurrentPriceFromHistory` transazione, ProductPrice key effettiva univoca | `ProductPriceHistoryView.save` fresh-context conflict guard + prezzo corrente + pending | ProductPrice remote contract | Android `Task130PriceContractTest`, repository history tests; iOS `Task130PriceContractTests`, `SupabaseProductPriceApplyServiceTests`. NOT_TESTED |
| History entry rename/delete/filter/group | `ExcelViewModel.renameHistoryEntry/deleteHistoryEntry`; `HistoryScreen`; repository user-visible DAO | `HistoryView`, `HistoryMonthGrouping`, `HistorySessionSyncService` | Session V2 + tombstone | `ExcelViewModelTest`, repo filtered history; iOS `HistoryViewStateTests`, `HistoryMonthGroupingTests`, `HistorySessionSyncServiceTests`. NOT_TESTED |
| Import analisi/conferma/errori/no-op | `DatabaseViewModel.startSmartImport/importProducts`; `InventoryRepository.applyImport` con transaction/mutex; `ImportAnalyzer` | `DatabaseView.applyImportAnalysisInBackground`, `applyConfirmedImportAnalysis`; `ProductImportCore` | Nessuno per apply locale; sync solo delta operativo | Android `FullDbExportImportRoundTripTest` reimport same workbook no-op, VM double-confirm/recovery; iOS `Task105RealOpsClosureTests`, `Task111ExcelImportParityTests`, `CatalogTextIntegrationTests`. NOT_TESTED; harness Excel sospeso non riattivato |
| Import/export numeri CL, barcode zero, Unicode, footer | `ClNumberFormatters`, `ExcelUtils`, `DatabaseExportWriter` | `ProductImportCore`, `ExcelSessionViewModel/ExcelAnalyzer`, `InventoryXLSXExporter` | Nessuno per round-trip | Android `ClNumberFormattersTest`, `ExcelUtilsTest`, `FullDbExportImportRoundTripTest`; iOS `Task141NumericInputTests`, `Task111ExcelImportParityTests` numeric barcode leading zero, `Task105RealOpsClosureTests` round-trip. NOT_TESTED |
| Immagini camera/galleria/preparazione/preview/upload | `EditProductDialog`; `ProductImageProcessor`, `ProductImageService`, staged file store | `EditProductView`; `ProductImageProcessor`, `ProductImageStore`, `ProductImageAPIClient`; decode `Task.detached` | Pipeline Product Images/route contrattuali | Android `ProductImageProcessorTest`, `ProductImageServiceTest`, VM progressive/cancel/retry/staged recovery, device suites; iOS `ProductImages/*Tests`, owner-store races. NOT_TESTED |
| Immagine nuovo prodotto | Android selezione prima del primo save, `PendingStagedProductImageStore` durevole, upload dopo stable remote ref | iOS `EditProductView.productImageSection` mostra `save_first` finché esistente con remoteID, selezione dopo save/sync | Stable remote ID richiesto per upload | INTENTIONAL_PLATFORM_DIFFERENCE candidata: percorso diverso già presente, stessa associazione finale; non classificare nuova feature mancante senza requisito. Runtime NOT_TESTED |
| Sostituzione/rimozione/cache immagini | `ProductImageService.upload/remove/purgeScope`; UI conserva current su failure e scoped thumbnails | `ProductImageStore.activate(scope:)`, mutation generation; `EditProductView.uploadPendingImage/removeCurrentImage` | ACK immagini, URL finalizzate e version ID | Android VM stale completion/scope removal/purge tests; iOS `ProductImageOwnerStoreRaceTests`, `ProductImageCacheTests`, `Task139ProductImageAddendumTests`. NOT_TESTED |
| Login/logout/startup/shop/permessi | `SupabaseAuthManager`; application auth/shop observers, `Task126BusinessDataScopeRuntimeGuard`; business content gate | `SupabaseAuthViewModel`; `ShopContext`; `AccountStoreReplacementCoordinator`, `Task126OwnerStoreGate` | Auth, shop/device authorization, RLS/RBAC già esistenti | Android auth/shop/scope boundary/runtime guard tests; iOS `SupabaseAuthSignOutScopeTests`, `ShopContextTests`, `AccountOwnerStoreSafetyTests`. NOT_TESTED; live credenziali/staging EXTERNAL_DEPENDENCY |
| Trigger automatici locali/reconnect/foreground | application wiring `onProductCatalogChanged`→`CatalogAutoSyncCoordinator.onLocalProductChanged`; generic catalog; history coordinator; network callback | `LocalPendingChangeAccumulator` notification→`ContentView`→`SyncOrchestrator.handleLocalPendingChanges`; foreground/reconnect | Catalog/prices/session/events | Android `CatalogAutoSyncCoordinatorTest` debounce500ms, busy retries, bounded poll, scope; iOS `Task119AutomaticArchitectureTests`, `AutomaticSyncReconnectSchedulerTests`. NOT_TESTED; nessun target 3s misurato |
| Outbox, idempotenza, push/pull/tombstone/conflitti | repository dirty refs/tombstones/event outbox, recovery coordinator, scope lease | `LocalPendingChange`, `SyncAutomaticEngine`, per-domain push/apply, `WatermarkStore`, recovery generation | Shop-scoped read/recovery/events contracts | Android repository/coordinator/scope/recovery suites; iOS `Task118AutomaticDomainTests`, `SyncEventIncrementalDomainApplyServiceTests`, `AtomicGenerationRecoverySnapshotPullServiceTests`. NOT_TESTED; R-A01/R-A02 rilevanti |
| Background, sospensione, force-stop | ProcessLifecycle/foreground loop, reconnect; WorkManager backfill prezzi non prova sync completa in background | `SyncBackgroundTaskScheduler` BGAppRefresh earliest15min, expiration cancella, ritorno foreground recupera | Device/auth + cloud access disponibile | INTENTIONAL_PLATFORM_DIFFERENCE di scheduling OS; determinismo background non promesso. Test force-stop recovery Android presenti; iOS architecture/background source inspected. NOT_TESTED |
| Storefront draft/publish/schedule/hide/archive/conflict | `DatabaseViewModel` + `StorefrontAuthoringContract`; stable remote ID + server ACK | `StorefrontAuthoringStore`, service, `StorefrontAuthoringViews`; same RPC | `storefront_publication_authoring_mutate_v1`, read/summary/bind session | `StorefrontDatabaseViewModelTest`, contract + Compose; iOS `StorefrontAuthoringTests`, `StorefrontEditorUITests`. F01–F03 executor in corso, NOT_TESTED reviewer |
| Storefront prezzo/categoria/immagini/import isolation | editor sezione separata, esplicito align, public image adoption; import contract non contiene publication | stessa separazione, public variant finale, mutate esplicito | Authoring RPC + `storefront/images/adopt` | Storefront contract/state/image/import tests su entrambe. NOT_TESTED |
| IT/EN/ES/ZH, accessibility, empty/loading/error | `values*`, strings semantiche, Compose accessibility; empty filtro reset | `*.lproj/Localizable.strings`, Dynamic Type/accessibility IDs, filter empty reset | Nessuno | Android `StorefrontLocalizationTest`, `CrossPlatformReliabilityPresentationTest`, Compose suites; iOS `LocalizationCoverageTests`, Storefront XCUITest. NOT_TESTED |

## Traccia sync e limiti

Android: Dialog/VM → repository + Room commit/dirty/tombstone → callback application → CatalogAutoSyncCoordinator (500ms debounce locale) / HistorySessionPushCoordinator → remote ACK → event/outbox → pull/apply scoped → Flow/Paging/override puntuale → UI. Il coordinatore è inizializzato eager in `MerchandiseControlApplication.onCreate`.

iOS: View → owner-store mutation fence/fresh SwiftData context → business mutation + LocalPendingChange → commit → notification ricevuta da ContentView → SyncOrchestrator → automatic engine per dominio → remote ACK/event → incremental apply/watermark/generation → SwiftData UI. Non serve una CTA manuale nel normale percorso osservato staticamente.

Il giro completo tra app e confronto canonico per record NON è stato eseguito dal reviewer. Suite live presenti (`Task103CrossPlatformAcceptanceTests`, `Task072*`, Android device harness) hanno gate opt-in e non vanno contate come eseguite per una suite verde che le salta. Nessuna misura p50/p95/max; codice iOS full-fetch editor/search è solo hotspot candidato. Nessuno scan globale security, deploy, cleanup dati o harness Excel riattivato.

## Handoff

1. Executor Android: riprodurre R-A01/R-A02 con test rossi, quindi correzione minima nel batch autorizzato e regressioni adiacenti.
2. Orchestratore: integrare matrice con risultati reali della run corrente, separando app locale, staging/device e merge.
3. Reviewer: attendere diff congelato e relativi log. Review indipendente coordinata del diff intero; un batch fix + re-review; nessuna approvazione anticipata.

## Review coordinata — slice integrità Android (2026-09-28)

Avvio richiesto dall'orchestratore sulle fonti stabili, senza modifica codice e senza build parallele. Letti diff `InventoryRepository.kt`, nuova `ProductEditConflictException.kt`, tutti i 15 casi `OperationalMutationIntegrityTest.kt`, callsite operativo attuale `DatabaseViewModel.saveProductFromEditor` e `ProductWithDetails`/DAO per verificare la provenienza dei prezzi.

- R-A01: merge dei 9 campi modificabili contro baseline immutabile e current nella stessa transazione; proprietà immagine e history preesistenti preservate; overlap con valori differenti e prodotto rimosso falliscono senza commit. Stesso valore concorrente e form invariato sono no-op. Inserimento price history limitato al delta prezzo effettivo: il pull catalogo arrivato prima della history non genera storia manuale fittizia.
- R-A02: add/create/rename supplier/category hanno entity e dirty ref nella stessa transazione Room; mutex preesistenti preservati e callback solo dopo commit. Test con trigger SQLite effettivo, non mock di transazione.
- Evidence letta: `/tmp/task143-integrity-red/TEST-com.example.merchandisecontrolsplitview.data.OperationalMutationIntegrityTest.xml` contiene `8 tests / 8 failures / 0 errors / 0 skipped`; `/tmp/task143-integrity-green-initial.xml` contiene `8 tests / 0 failures / 0 errors / 0 skipped`. Log operativo `/tmp/mobile-parity-android-integrity.md` coerente con file/test.
- I 7 test estesi sono stati letti ma il loro gate non era ancora disponibile. Nessuna esecuzione autonoma reviewer.
- Finding nuovi nella slice stabile: **P0=0, P1=0, P2=0**. Non è un'APPROVED globale: restano review Storefront Android/iOS congelati, integrazione VM, suite finale e limiti live.

## Review coordinata — Android source freeze F01/F03 (2026-09-28)

Diff completo Android esaminato rispetto a `d7c4953`, inclusi nuovi file untracked, UI/IT-EN-ES-ZH, fixture condivisa e test. Nessuna build o modifica del reviewer. Receipt lookup-before-version e TTL verificati con il rapporto `/tmp/mobile-parity-contract-staging.md`; il suo SQL transaction rollback non è app-auth/HTTP E2E.

### R-A03 — P2 — pending scaduto con publication assente non è recuperabile

`DatabaseViewModel.mutateStorefrontOnline`, ramo `recovering && dispatchedAtMs >= 7 giorni` (circa righe 866–884): readback `ok=true, rows=[]` lascia il record `DISPATCHED`, imposta soltanto `mutation_recovery_required` e ritorna. Ogni retry o nuovo Save torna allo stesso ramo. La UI di reapply/cancel richiede `StorefrontConflictState`, non creabile qui perché `server=null`.

Riproduzione deterministica: intento SAVE_DRAFT expectedVersion=0 salvato DISPATCHED 8 giorni prima; timeout della richiesta precedente prima del commit; backend restituisce read ok senza publication. Retry non manda richieste mutation e lascia pending. Modificare draft e salvare aggiorna desiredDraft ma resta bloccato. Chiudere/riaprire o riavviare non sblocca, proprio perché il record è ora durevole. Il test expiry corrente copre solo publication presente.

Fix richiesto nel batch: riconciliazione esplicita anche dell'assenza confermata per quel prodotto/scope, mantenendo desired e offrendo un intento nuovo verificato o uno scarto intenzionale; non cancellare silenziosamente e non inviare una nuova chiave alla cieca. Coprire primo tentativo mai committato + rows empty dopo TTL, restart, nuova bozza e fallimento della persistenza della risoluzione.

Altre aree sorgente esaminate senza finding P0/P1/P2: file app-private noBackup con fsync file/replace atomico, scope account/shop/product, nessuna eviction pending, errore disco prima del dispatch, payload immutabile DISPATCHED A + desired B, replay stessa key prima di successor, readback dopo receipt vecchia, conflitto durevole, override pending solo dopo rifiuto noto, nessun ACK offline per publish/hide/schedule/archive, fallimento remove dopo ACK conserva record per exact replay. Gate finali non ancora valutati. Verdetto Android sorgente attuale: **CHANGES_REQUIRED (R-A03)**.

## Review coordinata — iOS source freeze (2026-09-28)

Esaminati diff rispetto a `30d226d0`, Storefront authoring/storage/UI, fixture, test e compatibilità toolchain nei 24 file dichiarati. Nessuna build o modifica repository del reviewer. Confermata nel diff definitivo la disabilitazione dei campi durante loading/mutation/image adoption; il relativo test UI rosso è stato comunicato dall'executor e resta separato dalle prove autonome.

### R-I01 — P1 — readback di receipt vecchia perde la base ACK e crea conflitti/reapply errati

`StorefrontAuthoringStore.sendIntent`, ramo `response.idempotent` (circa righe 1156–1162), lancia `.conflict(current)` se la versione readback è diversa da quella del receipt prima di riconciliare il journal con l'ACK. Il catch elimina `journal.intent` ma mantiene `journal.draft` e `baseDraft` antecedenti A. `StorefrontAuthoringViews.reapplyConflict` usa quella base precedente per il three-way overlay.

Scenari deterministici da aggiungere:
1. Base nomeOld → A nomeA committata con ACK perso → altro device C nomeC → retry identico A. Il receipt prova che A è già riuscita e non resta un nuovo intento utente, ma iOS conserva A come local draft in conflitto. Android adotta il readback corrente in questo caso. È il falso conflitto che F03 richiede di evitare.
2. Stessa sequenza, ma dopo A l'utente prepara B cambiando soltanto il prezzo e lasciando nomeA. Al readback C il journal B rimane basato su nomeOld: il reapply include anche nomeA e sovrascrive nomeC, benché dopo l'ACK A il solo campo da riapplicare sia il prezzo. Il test concurrent attuale modifica di nuovo il nome e non copre questo delta disgiunto.

Fix richiesto nel singolo batch: riconciliare in modo durevole receipt A prima di classificare il readback corrente; senza successor eliminare solo il pending riconciliato e restituire il record corrente; con B conservare base ACK A e delta A→B e renderli disponibili alla UI prima del reapply, anche dopo restart. Coprire fallimento disco durante il rebase senza perdere l'intento originale.

Nessun altro finding P0/P1/P2 emerso: F02 controlla generation+filterGeneration+scope+query e protegge anche defer; migration legacy rimuove UserDefaults solo dopo write riuscita; errore storage blocca il dispatch e viene mostrato; pending separati dalla LRU; scope e stable ID derivano dalla chiave; scadenza con readback assente/expected0 viene riconciliata con precondition0 e retry della stessa key; ACK cleanup fallito mantiene record; publish/hide/schedule/archive non diventano successi offline. iOS Application Support usa file protection + atomic write e resta incluso nel normale backup, mentre Android usa noBackup: differenza di storage osservata, nessun nuovo leak/auth bypass dimostrato.

Compatibilità XCTest: ispezionate RATIONALE.md, owned-files.json e assertion-check.json. Il reviewer ha eseguito un confronto indipendente di tutte le 24 sorgenti rispetto a `30d226d0`: ogni riga `XCTAssert`, `XCTFail`, `XCTSkip`, continuation registration, `Task.sleep`, `Task.yield` è invariata (24/24). Il diff contiene conversione dei fake ai protocolli MainActor, init Sendable nonisolated/await e letture helper coerenti; la correzione Task103 spezza soltanto il log timing mantenendo chiavi/calcoli. Nessun cambiamento del runtime actor isolation o build settings nel diff. `git diff --check` eseguito dal reviewer: PASS. Il gate comportamentale completo resta necessario.

Verdetto iOS sorgente attuale: **CHANGES_REQUIRED (R-I01)**. Batch coordinato totale: R-A03 P2 Android + R-I01 P1 iOS; gli originari R-A01/R-A02 risultano risolti nella slice integrità, subordinati ai gate finali.

## Re-review limitata Android R-A03 — 2026-09-28

**Sorgente: APPROVED condizionato ai gate finali; R-A03 chiuso nel codice.** Nessun nuovo P0/P1/P2 nella patch di recupero e nella guardia di decoding adiacente. Non è approvazione globale Android/iOS né accettazione staging.

- `RECOVERY_REQUIRED` viene scritto atomicamente solo dopo readback riuscito con publication assente; nessun replay cieco oltre TTL, nessuna nuova identità implicita.
- Il caricamento ripristina il flag dal journal dopo nuova istanza; VM e pulsanti bloccano retry e nuove mutation fino alla decisione esplicita.
- `discardExpiredStorefrontIntent` verifica lo stato persistito, elimina solo la chiave account/shop/product corretta, azzera pending/publication dopo successo IO e conserva draft/dirty input corrente. Gli errori non eliminano lo stato visibile e mostrano `local_persistence_failed`; callback tardi protetti da scope/generation/session.
- Azione Compose esplicita e spiegazione disponibili in IT/EN/ES/ZH. Il nuovo test verifica restart, scarto, conservazione nome e nuovo save con chiave nuova. Test Compose verifica che l'azione sia presente e cliccabile; test runtime finale affidato all'executor.
- La guardia `state/operation/desiredOperation in entries` rifiuta enum Gson sconosciuti/null prima che il record possa essere trattato come nuovo intent; `baseDraft` obbligatorio. Test corruzione esteso con stato non riconosciuto.
- Evidenza letta direttamente: `/tmp/task143-android-expiry-red.log` contiene il test R-A03 fallito prima del fix (BUILD FAILED 22s); `/tmp/task143-android-review-fix-green.log` contiene `testDebugUnitTest` e BUILD SUCCESSFUL 35s. Suite canonicali post-fix/Compose/ultima guardia ancora in esecuzione al momento del verdetto; nessun test eseguito dal reviewer.

Il solo finding aperto del batch resta iOS R-I01 P1; re-review limitata dopo fix e prove rosso/verde attesa.

## Re-review limitata iOS R-I01 — 2026-09-28

**R-I01 chiuso nel codice.** Nessun nuovo P0/P1/P2 nel fix congelato.

- `sendIntent` separa ricevuta ACK e publication corrente. Prima del readback rimuove l'intent A dal journal e, se resta B, ne scrive atomicamente `baseDraft = ricevuta A` ed `expectedVersion = versione A`. Fallimento del write/cleanup mantiene il precedente intent su disco e propaga `localPersistence`.
- Retry identico senza successore restituisce la publication corrente C e non lascia un falso conflitto o una draft A già applicata. Non genera una seconda identità per lo stesso tentativo.
- Un successore B diverso da A resta persistito con base A; se C è più recente viene richiesto il conflitto esplicito. `reconciledBase` espone la ricevuta al solo scope/product corretto e l'editor aggiorna base/versione prima di reapply, anche nei ritorni offline/errore dopo ACK. Il delta A→B contiene solo prezzo nel caso di regressione; la reapply preserva il nome modificato da C.
- `loadEditor` rilegge il journal persistito e ripristina `localExpectedVersion`; scope/cancellazione impediscono aggiornamenti tardivi dopo cambio account/shop. Le ultime guardie UI su reset scope e fine loading/mutating/adoptingImage sono incluse nella review.
- Evidenza letta direttamente: `/tmp/mc-task144-ios/review-r-i01-red.log`, 2 test eseguiti con 5 failure (il retry identico lancia conflitto; il test disgiunto osserva base Original/versione7 e dirty name+price invece di baseA/versione8 e solo price). `/tmp/mc-task144-ios/review-r-i01-green.log`, 34 test (28 Storefront store +6 filter) con 0 failure e TEST SUCCEEDED. Il test disgiunto verifica reload dal journal e overlay che conserva il nome remoto C. Sono risultati dell'executor, non test eseguiti dal reviewer.
- Reviewer: `git diff --check` iOS corrente PASS; nessuna modifica codice e nessuna build concorrente.

## Conclusione coordinata sul sorgente

**APPROVED sul sorgente revisionato Android+iOS, condizionato alla chiusura documentata dei gate finali.** Tutti i finding concreti del ciclo (R-A01 editor merge, R-A02 atomicità anagrafiche, R-A03 recovery scaduta senza publication, R-I01 settlement/ribase ACK) sono chiusi nel codice e accompagnati da riproduzioni rosse e risultati verdi mirati. Nessun P0/P1/P2 aperto nel perimetro della review.

Questa conclusione non dichiara PASS di suite canonicali/Compose/XCUITest ancora in esecuzione né sostituisce la verifica autenticata bidirezionale Android↔iOS, l'E2E dei servizi esterni, la sweep manuale completa, CI o autorizzazione al rilascio. Le righe della matrice iniziale rimangono classificate secondo l'evidenza effettiva; presenza di un test non è esecuzione PASS. Nessun ulteriore audit generalista è richiesto da questo verdetto; un nuovo ciclo serve solo per una regressione concreta dei gate.

### Addendum finale R-I01 — scope cambiato durante readback

Re-review **limitata al delta**: la nuova chiamata `validateCurrentScope` prima di rimuovere conflitti/aggiornare summary/restituire ACK copre anche il ramo in cui il readback sospeso riprende con `.offline` o `.unavailable`. Il controllo esistente interno al `do` non veniva raggiunto in quei due catch; ora cancellazione, generation e active scope sono ricontrollati prima di qualsiasi effetto UI. La persistenza della ricevuta già confermata resta correttamente conclusa nel vecchio scope prima dell'attesa.

Il test controllato `testScopeSwitchDuringReceiptReadbackCannotApplyOfflineFallbackToNewScope` sospende il readback, cambia shop, riprende con offline e richiede CancellationError, summary assente e nessun conflitto nel nuovo scope. Nessuna temporizzazione arbitraria e nessuna assertion precedente indebolita. **Delta source APPROVED; nessun nuovo P0/P1/P2.** `git diff --check` PASS. Questo test non è ancora dichiarato PASS dal reviewer: la full suite in corso era partita prima del delta; slice Storefront/UI/build/analyze successivi e CI sull'exact SHA finale restano gate espliciti.
