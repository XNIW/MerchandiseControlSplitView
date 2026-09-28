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
4. Review indipendente, unico batch fix, re-review e gate canonici finali; commit/PR e integrazione secondo il mandato vigente registrato sotto, senza deploy di produzione.

### File coinvolti

DatabaseViewModel.kt, StorefrontAuthoringContract.kt, storage locale e DI esistenti, Storefront/DatabaseViewModel test JVM, test Compose pertinenti, risorse solo per errori necessari.

### Addendum planning autorizzato — R-A04 live, 2026-09-28

Dopo i gate verdi e la prima PR, il collaudo autenticato coordinato con «Completa attivazione WECHAT-010» ha riprodotto sul candidato TEST8b4cbf1b (sorgenti0f6e353, HEAD575ea71) `binding_replace_device_identity_missing`. Google/shop/profilo e owner hash corrispondono al tester autorizzato; SQLite quick_check ok e dataset/queue/device_state tutti vuoti, binding presente. L'azione UI Review→Replace with cloud data fallisce prima del journal alle18:23:51Z. Evidenze sanitizzate del coordinator in `native-completion-20260928/android-{db-counts.json,recovery-log.txt,replace-result.xml}`.

Nuovo finding P1 concreto nel perimetro CA-07/CA-10: `replaceMismatchedBusinessDataAndBind` presume identità dispositivo presente, mentre inizializzazione/registrazione ordinaria dipende dalla readiness che il recovery deve ripristinare. L'executor deve prima riprodurre con test rosso, poi usare il percorso canonico di identità/registrazione prima del recovery confermato senza fabbricare lease, cambiare backend o indebolire auth/scope. Coprire identità assente/presente, idempotenza, denial e scope stale; baseline regression pertinente, review indipendente mirata e ritest autenticato dello stesso scenario dal coordinator. Nessun accesso writer concorrente al suo device e nessun reset dei dati. La precedente review resta valida per la patch precedente; il nuovo delta richiede un gate specifico.

### Addendum planning autorizzato — R-A05 decode recovery live, 2026-09-28

Il ritest autenticato del candidato R-A04 su DB business vuoto supera l'identità dispositivo ma fra18:45–18:47Z fallisce con `MissingFieldException`, esaurendo i retry `mismatch_replace_confirmed`. Journal preservato, nessun ulteriore Replace. Evidenza sanitizzata `native-completion-20260928/android-ra04-recovery-log.txt`. Nuovo P1 attuale in CA-07/CA-10: identificare RPC e campo realmente mancanti confrontando DTO con contratto TEST distribuito, poi test rosso sul payload rappresentativo e patch client minima coerente con il contratto. Vietati default che trasformino failure o campi obbligatori mancanti in successo, indebolimenti auth/scope, modifiche backend o reset dei dati. Controparte iOS da confrontare; non dedurre un difetto attuale dai vecchi log. Review mirata, gate aggiornati e ritest autenticato richiesti.

## Mandato e separazione dei ruoli

Richiesta utente 2026-09-28 `MERCHANDISECONTROL — AUDIT FUNZIONALE, ROOT-CAUSE FIXES, PARITÀ ANDROID/iOS E SINCRONIZZAZIONE`. Il prompt autorizza orchestrazione/planning, executor separati, correzioni funzionali, test, review indipendente e preparazione commit/PR. Questo planning è registrato dal parent orchestratore prima delle patch; gli executor aggiornano Execution/Fix/Handoff. Nessuna chiusura DONE automatica o merge autorizzato per inferenza dai train storici.

**Mandato coordinato aggiornato:** nella chat «Completa attivazione WECHAT-010», dopo consenso diretto «Sì, coordina le due chat», il nuovo prompt utente autorizza esplicitamente modifiche native, test ADB/XCTest e «Commit, push, PR e merge delle modifiche verificate, nel rispetto delle protezioni e della governance effettivamente applicabili». Il parent di questo task mantiene ownership esclusiva dell’integrazione nativa. Merge normale soltanto dopo review richiesta, CI verde sullo SHA esatto e chiusura dei difetti live confermati; poi verifica origin/main e CI post-merge. Nessun bypass, reset di database utente o deploy produzione.

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

Review indipendente e re-review del primo batch completate; R-A04 scoperto nel successivo collaudo live è stato riprodotto, corretto e revisionato separatamente. Sorgente APPROVED, nessun P0/P1/P2 source aperto dopo R-A01/R-A02/R-A03/R-A04; gate locali aggiornati PASS (970 JVM e 5 Compose). R-A05 sul successivo diniego RPC è anch’esso corretto e revisionato. Gate aggiornato985 totali/978 PASS/7 SKIP; ritest autenticato conferma `checkpoint_resource_exceeded` correttamente classificato, preservando binding, dati e journal. Il blocco server TOAST resta aperto. Evidenza: [independent-review.md](evidence/TASK-143/independent-review.md). La review tecnica non è un'approvazione GitHub di un maintainer né una conferma live.

## Fix

### Batch review R-A03 — 2026-09-28

Il reviewer indipendente ha trovato P2: intent DISPATCHED oltre retention7giorni con read remoto riuscito ma publication assente restava senza uscita UI. Test rosso prima patch `expired uncommitted intent with absent publication permits explicit discard preserving draft`:1FAIL, `/tmp/task143-android-expiry-red.log`22s.

Patch minima: RECOVERY_REQUIRED durevole dopo assenza verificata; retry/nuove mutation bloccati finché l’utente sceglie il comando visibile localizzato `Scarta richiesta salvata`. Lo scarto intenzionale elimina soltanto l’intento scaduto, mantiene input e consente successivo Save con nuova identità. Nuova istanza conserva l’azione. Nessuna chiave cambiata o richiesta cancellata automaticamente. Compose copre visibilità/click azione; unit verifica restart,scarto,input e nuovo salvataggio una volta.

Green slice Storefront+DatabaseVM post-fix PASS35s (`/tmp/task143-android-review-fix-green.log`). Primo canonico interrotto intenzionalmente exit130 prima di instrumentation quando è apparso secondo emulator utente: rilancio limitato ad ANDROID_SERIAL=emulator-5554. Il benchmark opt-in parallelo ha introdotto warning test Json, demandato al suo owner prima del gate finale. Re-review sorgente limitata del reviewer indipendente: **APPROVED condizionato ai gate finali**, R-A03 chiuso nel codice, nessun nuovo P0/P1/P2 (report `/tmp/mobile-parity-independent-audit.md`). Gate definitivo ora PASS come registrato sopra; non è accettazione globale/staging.


### FIX live R-A04 — 2026-09-28

**Root cause:** il percorso di sostituzione confermata richiedeva una riga `sync_event_device_state` già presente, ma la registrazione ordinaria veniva avviata soltanto dopo READY. Un binding ripristinato con database business vuoto e identità assente restava quindi bloccato prima del journal. Il coordinator live ha riprodotto il caso sul candidato `8b4cbf1b`; questa lane non ha letto o modificato emulator-5556.

**Rosso prima patch:** nuovo test Room/Robolectric con tracker reale BLOCKED_SHOP_MISMATCH, binding precedente, dataset/code vuoti e identità assente: **1 FAIL** con `binding_replace_device_identity_missing` a `InventoryRepository.kt:2812`, log `/tmp/task143-ra04-red.log`, XML `/tmp/task143-ra04-red.xml`; 16s. La prova non usa una sessione server fake per aggirare il gate locale.

**File modificati:**
- `InventoryRepository.kt` — `DeviceInstallIdProvider` canonico crea/riusa l'identità nella stessa transazione del journal; errore identità o journal esegue rollback senza cambiare binding o dati.
- `ShopSyncRecoveryCoordinator.kt` — soltanto per mismatch confermato, registrazione canonica shop-scoped prima del checkpoint; controllo account/shop/device e generazione del journal prima/dopo la chiamata. Diniego, shop errato, network failure e cancellation conservano dati e recovery; nessuna autorizzazione canWrite/lease fabbricata. Il server checkpoint resta obbligatorio e mantiene la propria autorizzazione.
- `MerchandiseControlApplication.kt` — callback al trasporto esistente `registerShopDeviceForShop`; nessun cambio al guard READY dei normali flussi di sync.
- `Task139BusinessDataScopeBindingTest.kt` — tre regressioni nuove per bootstrap idempotente nello stato bloccato, rollback journal e fallimento disco identità.
- `ShopSyncRecoveryCoordinatorTest.kt` — sette regressioni nuove per ordine registrazione/checkpoint, diniego, receipt shop errato, retry identità, scope cambiato, journal più nuovo e cancellation.
- `Task139ShopSyncRecoveryForceStopDeviceTest.kt` — factory fake esplicita per la nuova dipendenza; compilazione verificata, harness force-stop non eseguito in questo batch.

**Verifica:** mirato **331 totali /330 PASS /1 SKIP esterno /0 FAIL /0 ERROR**, 27s, `/tmp/task143-ra04-green.log`; tutte le dieci nuove regressioni PASS. Include Application, binding, recovery, device authorization, integrità operativa e repository (baseline TASK-004). Review indipendente mirata sul delta rispetto a `575ea71`: **APPROVED**, nessun nuovo P0/P1/P2, comunicata dal parent prima del gate canonico.

| Check finale R-A04 | Stato | Evidenza |
|---|---|---|
| assembleDebug | ESEGUITO | canonico exit0, `/tmp/task143-ra04-final.log`, BUILD SUCCESSFUL 1m31s |
| Full JVM/Robolectric | ESEGUITO | **977 totali /970 PASS /7 SKIP /0 FAIL /0 ERROR**,69 classi; release non selezionata dal taskgraph |
| Compose | ESEGUITO | **5/5 PASS** effettivi, solo emulator-5554 `Codex_Mobile_Parity_API_35` |
| Lint / warning | ESEGUITO |0 errori,53 warning preesistenti,0 su righe modificate;0 nuovi warning Kotlin |
| Planning / regressioni | ESEGUITO | CA-07/CA-10, bootstrap canonico, fence e dati preservati; nessun backend/schema/nuova dipendenza |
| Riconferma live | NON ESEGUITO da questa lane | nuovo candidato consegnato al coordinator owner esclusivo di5556; non sostituito da fake/JVM/Compose |

Manifest nuovo [android-ra04-test-manifest.json](evidence/TASK-143/android-ra04-test-manifest.json), separato dal batch precedente conservato. I sette skip hanno le stesse motivazioni del gate precedente (fixture/live config, harness Excel sospesi, workbook opzionale, benchmark opt-in). Nessun benchmark ripetuto: questo fix non cambia i percorsi Room/Storefront misurati e non introduce claim prestazionali.

**Candidato TEST:** `/tmp/task143-ra04-test-build/app-debug-test-ra04.apk`, SHA256 `0ebd6f8147d34669540aaa6faf943a7553dc2c1a8b79ddab0658156101b43e41`. Build separata con configurazione primaria ignorata, senza stampare valori, senza modificarla e ripristinando l'assenza di `local.properties` nel worktree; `assembleDebug` PASS. Endpoint Supabase/key/Google client embedded corrispondono alla configurazione autorizzata; applicationId/versionCode e firma debug coincidono con il candidato precedente. Source base HEAD `575ea716f2095bd6e12558846c7e7c19945d98c7` più delta R-A04 non ancora committato al build: hash patch/file e receipt in [android-ra04-build-receipt.json](evidence/TASK-143/android-ra04-build-receipt.json). Il parent assocerà il commit successivo ai medesimi hash; nessun commit/push eseguito dall'executor. Il candidato precedente è preservato. Flag Storefront authoring/WeChat auth restano false come nella configurazione primaria; verifica canonica del target e installazione `-r` restano al coordinator.

### FIX live R-A05 — 2026-09-28

**Root cause e contratto:** il DTO completo del checkpoint veniva decodificato prima di `status`; i rifiuti contrattuali `resource_exceeded` e `invalid_baseline` omettono intenzionalmente `catalog/prices/history/images/integrity`. Il marker derivato può avere tali sezioni nulle. Il validator ha confrontato source SQL e definizioni TEST distribuite, poi creato fixture sintetiche con helper digest SQL reali: [recovery-contract-diagnosis.md](evidence/TASK-143/recovery-contract-diagnosis.md). Le tre fixture sono byte-identiche a quelle condivise con iOS; nessuna identità reale, sessione, token o impersonazione è usata nei test.

La query aggregata read-only sullo scope TEST canonico ha confermato un blocco distinto: `compressed_legacy_history_requires_remediation`,16 History attive compresse,1 violazione storage; i limiti per numero di righe non sono superati. Ciò spiega il ramo atteso dal contratto distribuito, ma il vecchio log app non cattura la risposta esatta. Il nuovo candidato permette al coordinator di verificare RPC/codice effettivi tramite retry dello stesso journal. Nessuna modifica, decompressione o cancellazione dei dati server è stata eseguita; il fix client non rende valido un recovery rifiutato dal server.

**Rosso prima patch:**2/2 FAIL per `MissingFieldException` sulle cinque sezioni mancanti,11s, `/tmp/task143-ra05-red.log` e `/tmp/task143-ra05-red.xml`. Dopo il primo fix, una nuova prova negativa ha rivelato anche la mancata verifica del baseline scope key quando `expectedScope` è assente:112 PASS/1 FAIL, `/tmp/task143-ra05-green.log` e `/tmp/task143-ra05-scope-red.xml`. Aggiunto il confronto esplicito prima della classificazione del rifiuto; nessuna guardia rimossa.

**File modificati:**
- `data/ShopSyncContractModels.kt` — eccezione contrattuale con diagnostica scalare opzionale RPC/nomi campi.
- `data/SupabaseShopSyncReadRemoteDataSource.kt` — envelope obbligatorio schema/shop/scope/account/device/expected scope key/digest prima del discriminante, per checkpoint e marker; solo `ready` prosegue al DTO completo rigoroso. Tre rifiuti noti classificati; stato sconosciuto o risposta malformata restano errori, senza default di successo. Missing field restituiti come nomi DTO, mai body o valori remoti.
- `data/ShopSyncRecoveryCoordinator.kt` — mantiene dati/journal/motivo e termina la finestra corrente di retry per rifiuto o risposta contrattuale invalida. Un successivo trigger foreground/reconnect può rivalutare il server. Log limitato a tipo/codice/RPC e nomi campo filtrati; UI e messaggio localizzato esistente invariati.
- `SupabaseShopSyncReadRemoteDataSourceTest.kt`, `ShopSyncRecoveryCoordinatorTest.kt` —8 nuove regressioni: short envelope, vincoli identità/schema/scope, DTO success incompleto, stato ignoto, marker con sezioni nulle, stop retry con dati preservati e successivo trigger, diagnostica senza identità o payload.
- `fixtures/mobile-recovery-short-{context,invalid-baseline,resource-exceeded}-v1.json` — contratto condiviso con dati sintetici e preflight aggregato sanitizzato; hash nel manifest.

**Mirato finale:**113/113 PASS,0 FAIL/ERROR/SKIP,11s (`/tmp/task143-ra05-green2.log`): Application6, coordinator68, transport19, binding20. Review indipendente del delta rispetto a `78d1fbc`: **APPROVED**, nessun P0/P1/P2; gli8 hash del freeze e il risultato113/113 sono stati verificati dal reviewer prima del canonico.

| Check finale R-A05 | Stato | Evidenza |
|---|---|---|
| assembleDebug / test / lint | ESEGUITO | comando canonico exit0, BUILD SUCCESSFUL1m28s, `/tmp/task143-ra05-final.log` |
| Full JVM/Robolectric | ESEGUITO | **985 totali /978 PASS /7 SKIP /0 FAIL /0 ERROR**,69 classi; testRelease non selezionato, non dichiarato eseguito |
| Lint / warning | ESEGUITO |0 errori,53 warning preesistenti,0 sulle righe modificate;0 warning Kotlin/deprecation nuovi |
| Compose precedente | ESEGUITO nel batch R-A04, riuso motivato |5 PASS effettivi su5554 nel gate R-A04; nessun nuovo run. Application, EditProductDialog, OptionsScreen e test Compose hanno hash identici a78d1fbc; parent autorizza riuso perché R-A05 modifica soltanto recovery/contratto e relative prove JVM |
| Planning / regressioni TASK-004 | ESEGUITO | CA-07/CA-10; full JVM include repository/DatabaseVM/ExcelVM. Nessuna dipendenza, schema, backend, UI o harness aggiunto |
| git diff --check | ESEGUITO | nessun whitespace error; sorgenti identici al freeze revisionato |
| Riconferma autenticata | NON ESEGUITO da questa lane | candidata pronta per owner5556; solo retry journal, nessun ulteriore Replace |

Nuovo manifest [android-ra05-test-manifest.json](evidence/TASK-143/android-ra05-test-manifest.json), per-classe/JVM/skip/lint/hash/riuso Compose, senza sovrascrivere i batch precedenti. XML finali e lint conservati in `/tmp/task143-ra05-final-reports/`. I7 skip restano:1 fixture locale Supabase/WeChat assente,1 config live realtime assente,3 harness Excel sospesi,1 workbook opzionale assente,1 benchmark opt-in. Nessun nuovo benchmark: i percorsi Room e persistenza Storefront già misurati non cambiano; nessuna nuova affermazione prestazionale.

**Candidato TEST completo:** `/tmp/task143-ra05-complete-test-profile/app-debug-test-complete-profile-ra05.apk`, SHA256 `dc11b6ee4be66480f48e50d3cebb0c64e2776d5c8cb8ebdf93a08650d70920d3`; build separata PASS9s. Receipt [android-ra05-build-receipt.json](evidence/TASK-143/android-ra05-build-receipt.json): base HEAD78d1fbc più delta R-A05 non ancora committato, patch SHA256 e8 hash file congelati. Profilo primario TEST più sole aggiunte autorizzate image origin e Storefront=true; WeChat=false. Configurazione generata e valori embedded corrispondono al profilo autorizzato; firma debug/applicationId/versionCode uguali al candidato precedente `c31bc11e` preservato. Config primaria e profilo privato invariati, `local.properties` del worktree nuovamente assente. Nessun accesso o installazione su5554/5556 da questa lane. Il coordinator confermerà target runtime e risultato autenticato, il parent assocerà il commit ai medesimi hash.

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
| CA-07 | NON ESEGUIBILE per convergenza live completa; ritest client ESEGUITO | Android autenticato supera R-A04 e classifica R-A05; recupero rifiutato per16 History TOAST della policy server. Binding/dati invariati e journal1 preservato con metadata di tentativo aggiornati. Nessuna convergenza o pending zero dichiarati; nessun reset/remediation dati |
| CA-08 | NON ESEGUITO integralmente | suite import/export/immagini esistenti verdi e fixture Storefront condivisa; identico workbook nelle due UI e lifecycle immagini reale non eseguiti |
| CA-09 | NON ESEGUITO integralmente | benchmark core 20k/300k e n300 eseguito; before/after UI e target3s live non misurati |
| CA-10 | ESEGUITO | rosso→verde incluso R-A04, suite aggiornata 978 JVM PASS/7 SKIP; 5 Compose del batch precedente con hash UI/Application/test invariati |
| CA-11 | ESEGUITO per review e pubblicazione; integrazione in corso | PR10 aperta; CI78d1fbc verde per R-A04. R-A05 ha review e gate locali aggiornati; nuova CI exact-SHA necessaria prima del merge autorizzato, risultati nel rapporto aggregato e nei check GitHub |
| CA-12 | ESEGUITO per tracciamento, consegna finale in corso | stato REVIEW, niente DONE; rapporto aggregato esterno MERCHANDISECONTROL_MOBILE_PARITY_ROOT_CAUSE_RESULT.md raccoglie anche SHA/PR/CI finali senza commit autoreferenziali |

Ordine integrazione: le due app sono indipendenti e usano il contratto backend già esistente; nessuna migrazione/deploy prerequisite. Merge autorizzato dal mandato coordinato, ancora NOT_MERGED al presente snapshot in attesa dei gate del delta R-A04; distribuzione NOT_DEPLOYED.

### Pubblicazione e coordinamento

PR [#10](https://github.com/XNIW/MerchandiseControlSplitView/pull/10) aperta; CI exact-SHA raccolta nel rapporto finale e nei check della PR. Consenso diretto dell'utente verificato nella chat «Completa attivazione WECHAT-010»: ownership nativa qui, collaudo autenticato sui dispositivi separati dell'altra lane, installazione preservando i dati. Nessun E2E PASS attribuito prima della ricevuta. Il merge è ora esplicitamente autorizzato dal mandato coordinato riportato sopra; stato effettivo e CI post-merge saranno registrati nel rapporto aggregato finale.

### Handoff R-A04

R-A04_CODE_AND_LOCAL_GATES_VERIFIED — source e test congelati dopo review indipendente e gate canonico verde; candidato TEST firmato consegnato per ripetere lo scenario live. Manifest e receipt nuovi conservano le prove precedenti. Nessun accesso a5556, nessun reset dati, nessuna modifica al checkout/config primario. Il parent gestisce aggiornamento PR/CI exact-SHA, ricevuta live, stato task e integrazione secondo il mandato coordinato verificato. Questa lane non dichiara il ritest autenticato PASS né chiusura DONE.

### Handoff R-A05

R-A05_CODE_AND_LOCAL_GATES_VERIFIED — review indipendente e gate canonico PASS;8 file app/test/fixture congelati con hash verificati, manifest e candidato TEST completo consegnati al parent. CPU/Gradle rilasciati alle altre lane; nessun altro test o benchmark richiesto da questo fix. Il candidato è destinato al solo retry del journal live preservato: nessun nuovo Replace, reset o accesso concorrente a5556. La classificazione client è corretta; il blocco della policy History sul server resta esterno e non è un PASS di convergenza. Parent owner di commit/PR/CI exact-SHA, risultato live e integrazione; nessun DONE o merge dichiarato dall'executor.

### Ritest autenticato R-A05 — parent, 2026-09-28

Il coordinator autorizzato ha installato APK `dc11b6ee…` in-place su emulator-5556; sessione Google mantenuta. Il journal riparte al launch senza nuovo Replace. Alle19:12:31Z il boundary registra `checkpoint_resource_exceeded`, RPC `shop_sync_recovery_checkpoint_v1`, `missingFields=none`: rifiuto contrattuale esplicito, non recovery riuscito. Binding e dataset/outbox preservati; journal resta1, con runId/reason/attemptCount6→7 e timestamp del nuovo tentativo aggiornati, gli altri campi invariati. Receipt sanitizzata [android-ra05-live-retest.json](evidence/TASK-143/android-ra05-live-retest.json), log tecnico privo di payload associato. La policy server sui16 History TOAST richiede decisione/remediation separata autorizzata; nessun record esistente è stato modificato.
