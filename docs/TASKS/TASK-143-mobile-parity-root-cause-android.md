# TASK-143 — Mobile parity root-cause android

## Stato

- File task: `docs/TASKS/TASK-143-mobile-parity-root-cause-android.md`
- Stato: `REVIEW`
- Fase: `REVIEW`
- Responsabile: `CODEX_EXECUTOR_ANDROID`; orchestratore parent, reviewer indipendente separato.
- Data: 2026-09-28
- Baseline: `d7c4953c4ed6bc2a33cc5dbfd009eb862f70feac`
- Branch: `codex/mobile-parity-root-cause-android`
- Coordination key: `MERCHANDISECONTROL_MOBILE_PARITY_ROOT_CAUSE`

## Scopo / Obiettivo

Audit funzionale mobile completo e correzione cause F01/F02/F03 e ulteriori difetti confermati; F04 documentale, verifica locale e staging/dispositivi disponibile, senza ampliare le funzionalità richieste.

## Planning

### Analisi

DatabaseViewModel.mutateStorefront/loadStorefrontEditor/mutateStorefrontOnline confermano draft e storefrontPendingKeys solo in memoria, key per operazione e reset su apertura/chiusura. F01/F03 richiedono intent persistito nello storage locale appropriato integrato al percorso esistente; non un nuovo worker di sync.

### Approccio

1. Congelare baseline e riprodurre con regressioni rosse prima della patch.
2. Correggere intent/storage e ownership asincrona con cambi minimi; verificare controparte e contratto server.
3. Eseguire audit delle capacità esistenti e test pertinenti; documentare separatamente code review, fake, runtime e staging.
4. Review indipendente, unico batch fix, re-review e gate canonici finali; preparare commit/PR senza assumere merge/produzione.

### File coinvolti

DatabaseViewModel.kt, StorefrontAuthoringContract.kt, storage locale e DI esistenti, Storefront/DatabaseViewModel test JVM, test Compose pertinenti, risorse solo per errori necessari.

## Mandato e separazione dei ruoli

Richiesta utente 2026-09-28 `MERCHANDISECONTROL — AUDIT FUNZIONALE, ROOT-CAUSE FIXES, PARITÀ ANDROID/iOS E SINCRONIZZAZIONE`. Il prompt autorizza orchestrazione/planning, executor separati, correzioni funzionali, test, review indipendente e preparazione commit/PR. Questo planning è registrato dal parent orchestratore prima delle patch; gli executor aggiornano Execution/Fix/Handoff. Nessuna chiusura DONE automatica o merge autorizzato per inferenza dai train storici.

Checkout primari con modifiche preesistenti preservati. Niente reset dati, force push, migrazioni/RLS/deploy production, nuove dipendenze, secondo motore sync o pipeline immagini. Harness Excel sospeso non riattivato. Client pubblico read-only; Admin/Supabase consultati solo per contratti e staging necessario. Task precedenti e gate fisici non chiusi automaticamente.

## Criteri di accettazione

| ID | Criterio e verifica richiesta |
|---|---|
| CA-01 | Baseline branch/HEAD/origin/main/diff/worktree/PR/CI exact-SHA registrata; lavoro preesistente preservato. |
| CA-02 | F01: salvataggio draft durevole prima del successo locale; storage fallibile, account/shop/stable ID, base/payload/version/op/key/stato; dismiss/riapertura/nuova istanza/reconnect/disk failure/due prodotti/due scope/conflict/replay testati. Pending separato da cache LRU. |
| CA-03 | F02: richiesta filtro identificata per scope/filtro/query/generazione; reset immediato, no stale success/error/finally, pagination/Tutti; test deterministici + UI rapida e controparte. |
| CA-04 | F03: intent immutabile persistito per tutte le mutation; stesso retry stessa key; ACK ignoto riconciliato prima di inviare payload diverso; test commit con ACK perso, pre-commit timeout, edit dopo validation, concorrenza e restart. Contratto reale staging verificato o dipendenza esterna precisa. |
| CA-05 | F04: stato documentale iOS riconciliato con PR/merge/CI reali, separando implementazione/integrazione/test/distribuzione e preservando gate fisici. |
| CA-06 | Matrice completa inventario/database/anagrafiche/prezzi/history/import-export/immagini/auth-shop/sync/Storefront/localizzazioni-accessibilità; fonte, file, backend, test eseguito e stato tra VERIFIED/DEFECT/MISSING_REQUIRED/NOT_TESTED/EXTERNAL_DEPENDENCY/INTENTIONAL_PLATFORM_DIFFERENCE. |
| CA-07 | Integrità locale/outbox/server/pull/UI verificata; sync automatica bidirezionale, scope/stale callback, offline/reconnect/restart/conflitti/paging/no-op. Convergenza per ID/campi/relazioni; nessun reset code. Gate live non sostituito dai fake. |
| CA-08 | Import/export condiviso CLP/barcode Unicode/quantità/duplicati/footer/no-op e immagini cache/preparazione/staged; fixture esistenti riusate, regressioni semantiche condivise senza riattivare harness Excel. |
| CA-09 | Misurazioni riproducibili prima/dopo per scenari sostenibili, dataset sintetico isolato, campioni/p50/p95/max/condizioni; nessuna ottimizzazione senza evidenza, niente garanzia assoluta 3s. Distinguere foreground/background/sospensione/force-stop. |
| CA-10 | Test rosso prima patch, verde e regressioni adiacenti; gate canonici finali sul codice finale e test UI eseguiti quando ambiente disponibile. Contare PASS/FAIL/SKIP e non equiparare androidTest compilati a eseguiti. |
| CA-11 | Review indipendente, batch fix e re-review; altri cicli solo nuove regressioni P0/P1/P2 concrete; commit/PR per repo con CI exact-SHA, NOT_MERGED se manca autorizzazione. |
| CA-12 | Report MERCHANDISECONTROL_MOBILE_PARITY_ROOT_CAUSE_RESULT con baseline/finali, matrice, finding/prove, test, sync, prestazioni, residui e stati separati. Ogni CA chiuso con ESEGUITO/NON ESEGUIBILE/NON ESEGUITO motivato; nessun “tutto completo” senza prove obbligatorie. |

## Rischi

ACK perso e aggiornamento concorrente richiedono replay del contratto idempotente, non confronto ingenuo della sola versione. Scritture disco e cancellazioni possono interrompere il percorso; preservare pending, input e isolamento. Staging/device possono non disporre di sessione autorizzata; dichiarare gate non eseguibili senza estendere privilegi.

## Execution

In corso; log executor ed evidenze da aggiungere qui.

### Esecuzione — 2026-09-28 — Integrità operativa Android (executor dedicato)

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — transazioni Room per add/create/rename supplier e category; API editor con baseline immutabile e merge a tre vie all'interno della transazione prodotto/history/dirty.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductEditConflictException.kt` — conflitto esplicito se lo stesso campo è cambiato concorrentemente a un valore diverso o il prodotto non esiste più.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/OperationalMutationIntegrityTest.kt` — nuove regressioni Room/Robolectric sui rollback e sulla concorrenza editor.

**Azioni eseguite:**
1. Riprodotti **8 test ROSSI prima della patch**: add/create supplier e category lasciano righe orfane del relativo dirty intent dopo errore SQLite sul remote ref; rename lascia nome nuovo e revisione invariata; editor stantio sovrascrive prezzo `20→10` e cancella il puntatore immagine aggiornato; overlap non rifiutato. Failure injection mediante trigger SQLite nel DB in-memory del test, nessun trigger/schema remoto modificato. Log `/tmp/task143-android-integrity-red.log`, XML `/tmp/task143-integrity-red/`.
2. Aggiunta transazione minima intorno a entity+dirty mantenendo mutex, lease di scope e notifiche post-commit. Nessuna dipendenza, migration, worker o pipeline aggiunta.
3. `updateProductFromEditor(baseline, product)` applica solo i nove campi realmente modificati dall'editor al current letto nella stessa transazione. Cambi disgiunti e metadati immagini/history correnti preservati; overlap differente/deleted falliscono senza write; overlap allo stesso valore/no-op non generano history, revisione o notifica. History manuale solo per prezzi cambiati effettivamente dal merge. L'API legacy `updateProduct` conserva il comportamento precedente.
4. Primo verde **8/8 PASS**, zero failure/error/skip, run combinato coordinato dall'executor Android (`/tmp/task143-android-green1.log`, XML `/tmp/task143-integrity-green-initial.xml`). Aggiunte altre sette regressioni per catalog price prima del price pull, no-op/same-value, clear a null, history/revisione prezzo, overlap di tutti i nove campi, prodotto eliminato e rollback dopo write prodotto/prezzo; gate finale sulle **15 regressioni** e suite repository affidato all'executor Android per evitare build concorrenti.
5. Integrazione VM, baseline dell'apertura e messaggio conflitto localizzato assegnati all'executor Android owner di `DatabaseViewModel.kt`; questa lane non modifica VM/Storefront né harness Excel sospeso.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO in questa slice | Gate canonico finale coordinato dall'executor Android dopo l'integrazione VM. |
| Lint | NON ESEGUITO in questa slice | Gate canonico finale coordinato dall'executor Android. |
| Warning nuovi | ESEGUITO parziale | Nessun warning Kotlin nel primo verde; verifica definitiva subordinata ai gate finali. |
| Coerenza con planning | ESEGUITO | Difetti confermati nell'audit CA-07/CA-10, correzione minima senza estensioni. |
| Criteri di accettazione | ESEGUITO parziale | Prove locali di atomicità e stale editor; staging/device/review e chiusura di tutti i CA restano al coordinamento generale. |

**Baseline regressione TASK-004:**
- Test eseguiti: `OperationalMutationIntegrityTest` 8/8 verdi dopo 8/8 rossi; si tratta di JVM/Robolectric, non UI Compose/Espresso.
- Test aggiunti: 15 casi nella classe dedicata; sette edge case successivi al primo verde da includere nel prossimo gate con `DefaultInventoryRepositoryTest`.
- Limiti residui: integrazione dialog/VM, gate canonici, baseline estesa e prove live non sostituiti dai test repository in-memory.

**Incertezze:**
- Nessuna sul comportamento riprodotto; integrità server/pull/UI end-to-end richiede le evidenze delle altre lane.

**Handoff notes:**
- API e exception disponibili per l'executor Android; mantenere baseline dell'apertura, mai rileggerla dal current al salvataggio. Nessun commit/push eseguito da questa lane.

### Esecuzione Storefront/editor — 2026-09-28

**File modificati:**
- `data/StorefrontPendingMutationStore.kt` — registro durevole account/shop/stable product in no-backup app-private, fsync e replacement atomico; nessun worker o coda sync alternativa.
- `viewmodel/DatabaseViewModel.kt` — persist-before-confirm/request, restore su nuova istanza, intent A immutabile prima del successore B, readback dopo receipt storica, conflitto durevole e scarto intenzionale; bridge editor operativo con baseline apertura.
- `ui/screens/EditProductDialog.kt`, `values*/strings.xml` — UI/UX: distingue richiesta in attesa da draft locale, errori persistenza/recovery/conflitto operativo localizzati (motivo: conferma veritiera e input preservato).
- `StorefrontDatabaseViewModelTest.kt`, `StorefrontPendingMutationStoreTest.kt`, `DatabaseViewModelTest.kt`, `StorefrontEditorComposeDeviceTest.kt` — regressioni durevolezza/retry/scoping/conflict/filtro e UI, contratto editor aggiornato senza indebolire i test.
- `fixtures/mobile-storefront-intent-parity-v1.json` — fixture condivisa orchestratore, decodificata nel draft reale/persistita e riletta da nuova istanza, canonical payload Unicode/CLP verificato.

**Azioni eseguite:**
1. F01/F03 riprodotti prima patch su codice baseline: 12 test, 10 PASS / 2 FAIL; `/tmp/task143-android-red.log` (3m21s). Errore draft `Durable` perso su reopen; secondo invio usa B invece di replay A.
2. Intent persistito QUEUED/DISPATCHED/REJECTED/CONFLICT, payload+base+version/op/key e successore distinti. Stato salvato non soggetto a eviction; logout/switch cancellano solo accesso UI e job. Retry Storefront esplicito nell'editor ripristinato; sync operativa automatica invariata.
3. Verificati ACK perso prima/dopo commit, A→B e restart, validation→edit, permission denial su recovery senza key nuova, receipt storica dopo modifica iOS fake, conflitto/reapply/cancel, receipt >7 giorni, errore disco, due prodotti e scope, 250 pending oltre cap cache.
4. Android filtro già generazionale via Pager+flatMapLatest: test controllato A sospesa→B, risposta/errore tardivi, All e stessa tipologia/query nuova; nessuna patch produzione filtro necessaria. Composable test aggiunto Published→Draft→All.
5. Android AVD dedicato `Codex_Mobile_Parity_API_35` creato senza reset di AVD utente; API35 arm64 PlayStore, emulator-5554. Gate Compose eseguito nel canonico, esito definitivo sotto.

**Evidenze slice:** green1 12+8 PASS; green2 327 test/6 FAIL/1 SKIP per valutazione noBackupFilesDir su app mock, corretto con costruzione lazy; green3 329 test, 328 PASS/0 FAIL/1 SKIP; green4 Storefront VM24/store5/DatabaseVM61/integrity15/contract7/paging2/locale1 PASS. Log `/tmp/task143-android-green{1,2,3,4}.log`. Canonici finali e conteggi definitivi saranno riportati dopo batch FIX review.

**Prestazioni core misurate:** host JVM sintetico, 3 warmup+40 campioni, fsync+replace write p50=0.486958ms/p95=0.732209/max=0.879333; nuova istanza read p50=0.394792ms/p95=0.787208/max=0.846042. Non misure UI/render/device/sync. Baseline non aveva un percorso durevole equivalente, quindi nessuna pretesa miglioramento before/after.

**Baseline regressione TASK-004:** slice DefaultInventoryRepositoryTest, DatabaseViewModelTest e OperationalMutationIntegrityTest verdi; full `test` nel gate canonico richiesto. Nessun harness Excel sospeso abilitato; opt-in restano disabilitati.

**Incertezze/Handoff:** nessuna sessione Android staging autenticata usata da questa lane; staging contratto SQL curato dal parent separatamente. Fake+unit/Compose non provano Android↔iOS live per-record convergence, background/force-stop o scanner/camera reale. Source review indipendente ha un solo R-A03 P2 (expiry+publication assente): riproduzione e fix tracciati sotto, non DONE.

### Gate Android finale dopo FIX — 2026-09-28

Comando effettivo con JBR/SDK locali e `ANDROID_SERIAL=emulator-5554`:
`./gradlew assembleDebug test connectedDebugAndroidTest lint -Pandroid.testInstrumentationRunnerArguments.class=com.example.merchandisecontrolsplitview.ui.screens.StorefrontEditorComposeDeviceTest`.

**Esito:** `BUILD SUCCESSFUL in 1m 4s`, exit0, `/tmp/task143-android-final-verified.log`. Il precedente run aveva completato full JVM debug sullo stesso codice produzione/JVM test, poi fallito soltanto compilando il nuovo test Compose (trailing lambda associata a Modifier). Corretto il callsite test-only con parametro `onSelected` nominato; l'ultimo comando riusa legittimamente i risultati JVM identici (`UP-TO-DATE`) ed esegue AndroidTest/Lint aggiornati. Nessun PASS di produzione da source differente riutilizzato.

| Check | Stato | Evidenza |
|---|---|---|
| Build Gradle | ✅ ESEGUITO | assembleDebug, APK prodotto; finale exit0 |
| Lint | ✅ ESEGUITO | 0 errori,53 warning su righe preesistenti/dependency notices;0 match su righe modificate |
| Warning nuovi | ✅ ESEGUITO | nessun nuovo warning Kotlin; warning test benchmark Json eliminato; D8 stack-map del jar WeChat esterno preesistente |
| Coerenza planning | ✅ ESEGUITO | F01/F03, equivalente Android F02, integrità R-A01/R-A02 e FIX R-A03; no nuovi backend/motori sync/pipeline immagini |
| Criteri locali | ✅ ESEGUITO | criteri core e test sotto; criteri coordinati/live classificati dal parent, nessun DONE automatico |
| Full JVM/Robolectric | ✅ ESEGUITO | **967 test:960 PASS,0 FAIL,0 ERROR,7 SKIP;69 classi** |
| Compose emulator | ✅ ESEGUITO | **5 PASS,0 FAIL,0 SKIP**, API35 su AVD isolato `Codex_Mobile_Parity_API_35`; non solo compilazione |
| git diff --check | ✅ ESEGUITO | exit0 |

Manifest persistito [`evidence/TASK-143/android-test-manifest.json`](evidence/TASK-143/android-test-manifest.json): per-classe conteggi, skipped case, report XML, lint locations e SHA256 file app modificati. `:app:test` canonico ha selezionato debug; nessuna esecuzione testRelease nel taskgraph, non dichiarata PASS.

**SKIP documentati:**1 fixture locale Supabase/WeChat convergence richiesta ma non impostata;1 live realtime senza config;3 harness Excel OracleLoop/DriveBatch/OracleV2 sospesi e non attivati;1 workbook locale ShoppingHogar assente;1 benchmark sintetico opt-in, eseguito separatamente con **1 PASS** su20.000 prodotti/300.000 prezzi/100 fornitori/50 categorie. Il benchmark non è harness Excel; JSON e misure core/RSS consegnati dall'executor integrità.

**Suite core incluse:**StorefrontVM25/store5/contract7/paging2/localization1;DatabaseVM61;OperationalMutationIntegrity15;DefaultInventoryRepository218 (217PASS,1external SKIP); resto nel manifest. Le cinque prove Compose verificano espansione/actions separate, preview senza immagine operativa, filtro vuoto→Tutti, cambio rapido Published→Draft→All, scarto esplicito richiesta scaduta. UI/locale generale, scanner/camera reale, auth staging da app e convergenza bidirezionale rimangono distinti e non surrogati da questi test.

## Review

Review indipendente e re-review completate: sorgente APPROVED, nessun P0/P1/P2 aperto dopo R-A01/R-A02/R-A03. Gate Android finali PASS. Evidenza: [independent-review.md](evidence/TASK-143/independent-review.md). La review tecnica non è un'approvazione GitHub di un maintainer né una conferma live.

## Fix

### Batch review R-A03 — 2026-09-28

Il reviewer indipendente ha trovato P2: intent DISPATCHED oltre retention7giorni con read remoto riuscito ma publication assente restava senza uscita UI. Test rosso prima patch `expired uncommitted intent with absent publication permits explicit discard preserving draft`:1FAIL, `/tmp/task143-android-expiry-red.log`22s.

Patch minima: RECOVERY_REQUIRED durevole dopo assenza verificata; retry/nuove mutation bloccati finché l’utente sceglie il comando visibile localizzato `Scarta richiesta salvata`. Lo scarto intenzionale elimina soltanto l’intento scaduto, mantiene input e consente successivo Save con nuova identità. Nuova istanza conserva l’azione. Nessuna chiave cambiata o richiesta cancellata automaticamente. Compose copre visibilità/click azione; unit verifica restart,scarto,input e nuovo salvataggio una volta.

Green slice Storefront+DatabaseVM post-fix PASS35s (`/tmp/task143-android-review-fix-green.log`). Primo canonico interrotto intenzionalmente exit130 prima di instrumentation quando è apparso secondo emulator utente: rilancio limitato ad ANDROID_SERIAL=emulator-5554. Il benchmark opt-in parallelo ha introdotto warning test Json, demandato al suo owner prima del gate finale. Re-review sorgente limitata del reviewer indipendente: **APPROVED condizionato ai gate finali**, R-A03 chiuso nel codice, nessun nuovo P0/P1/P2 (report `/tmp/mobile-parity-independent-audit.md`). Gate definitivo ora PASS come registrato sopra; non è accettazione globale/staging.


## Handoff

EXECUTION_AND_FIX_VALIDATED — sorgente Android congelata, review indipendente R-A01/R-A02/R-A03 risolta e re-review sorgente approvata condizionata ai gate ora PASS. Nessun commit/push/PR eseguito dagli executor. Il parent coordina Review/CA finali/report/commit/PR/CI; non dichiarare integrazione main o distribuzione. Checkout primario/toolchain preesistente preservati. Non includere `.kotlin/sessions/` nei file da integrare.

Limiti espliciti: niente app Android autenticata su staging in questa lane, niente E2E Android↔iOS per-record/render latency/background/force-stop, scanner/camera/share reali o sweep UI completo delle4lingue. Contratto SQL staging verificato separatamente dal parent; fake,JVM,Compose,benchmark core e test SQL restano evidenze distinte.

### Criteri — snapshot pre-pubblicazione del parent

| Criterio | Stato | Evidenza / limite |
|---|---|---|
| CA-01 | ESEGUITO | baseline.json; checkout primario ancora invariato al controllo finale locale |
| CA-02 | ESEGUITO | journal e Storefront VM/storage regression; manifest finale |
| CA-03 | ESEGUITO | equivalente Android deterministico + rapido cambio Compose; fix iOS coordinato |
| CA-04 | ESEGUITO | intent/replay locali; contratto reale staging 12/12 in rollback. Non prova COMMIT+HTTP ACK perso dalle app |
| CA-05 | ESEGUITO | F04 iOS riconciliato con PR10 e CI head/merge reali |
| CA-06 | ESEGUITO | functional-matrix.md; copertura locale separata dai flussi live e fisici |
| CA-07 | NON ESEGUIBILE per il giro live | prove locali eseguite; manca login del tester autorizzato sui due device dedicati. Nessun pending azzerato |
| CA-08 | NON ESEGUITO integralmente | suite import/export/immagini esistenti verdi e fixture Storefront condivisa; identico workbook nelle due UI e lifecycle immagini reale non eseguiti |
| CA-09 | NON ESEGUITO integralmente | benchmark core 20k/300k e n300 eseguito; before/after UI e target3s live non misurati |
| CA-10 | ESEGUITO | rosso→verde, suite canonica finale e 5 Compose effettivi, skip espliciti |
| CA-11 | NON ESEGUITO al momento del commit | review/re-review eseguite; pubblicazione PR e CI exact-SHA saranno registrate nel rapporto aggregato finale e nei check GitHub |
| CA-12 | ESEGUITO per tracciamento, consegna finale in corso | stato REVIEW, niente DONE; rapporto aggregato esterno MERCHANDISECONTROL_MOBILE_PARITY_ROOT_CAUSE_RESULT.md raccoglie anche SHA/PR/CI finali senza commit autoreferenziali |

Ordine integrazione: le due app sono indipendenti e usano il contratto backend già esistente; nessuna migrazione/deploy prerequisite. Merge NOT_MERGED e distribuzione NOT_DEPLOYED fino ad autorizzazione distinta.
