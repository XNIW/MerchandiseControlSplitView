# TASK-144 — Sync automatica, recovery non bloccante e UX offline Android

## Informazioni generali

| Campo | Valore |
|---|---|
| ID / File task | TASK-144 / `docs/TASKS/TASK-144-mobile-sync-recovery-offline-ux-android.md` |
| Stato / fase corrente | **BLOCKED / REVIEW** (gate locali conclusi, prove applicative esterne mancanti) |
| Responsabile attuale | **COORDINATOR / REVIEWER-INTEGRATOR — review e lane CI/app/TEST** |
| Priorità / area | ALTA / sync, recovery, locale e presentazione |
| Creato / ultimo aggiornamento | 2026-10-15 / 2026-10-16T06:00:27.497056+00:00 |
| Ultimo agente | CODEX_MOBILE_EXECUTOR |
| Coordination key | MOBILE_SYNC_RECOVERY_OFFLINE_UX |
| Verdetto documentale | PLANNING_COMPLETE / READY_FOR_EXECUTION; execution non iniziata |

## Mandato / override e dipendenze

Il mandato utente autorizza esplicitamente Codex planner/coordinatore, task/master/documenti, analisti read-only e futuro executor/reviewer distinti. Non autorizza patch applicative in questo turno. Override registrato rispetto al ruolo executor storico, senza modificare AGENTS/CLAUDE. TASK-143 è sospeso **BLOCKED**, ultima fase FIX e prove preservate. Nessun branch creato né DONE dichiarato.

Controparte [iOS TASK-145](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/TASKS/TASK-145-mobile-sync-recovery-offline-ux-ios.md). [Dossier canonico](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/README.md), [contratto condiviso](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/CONTRACT.md), [riferimento Supabase](/Users/minxiang/Desktop/MerchandiseControlSupabase/docs/mobile_sync_recovery_offline_ux_planning_reference.md). Nessuna copia divergente del contratto nel repo Android.

## Scopo e stato attuale

Preservare lavoro offline nello scope autorizzato, riprendere automaticamente pending/recovery transitori, limitare errori a dati indipendenti quando sicuro e mostrare informazioni veritiere. HEAD/main/origin/main/GitHub `4b74ed5cfb4e4bce024513561e7dc1b6998003ae`, CI38623121443 SUCCESS preesistente. Tre entry point richiesti letti su GitHub e byte-identici al checkout. Dirty toolchain e untracked TASK140F/tools/IDE preservati.

Installazione Emulator API35 Android15, app1.0(1) DEBUGGABLE, APK SHA256 `1510010be33b2201343e2fcf97b188034b71f56eb95fb76813c74be8c8d8bb3e`; source/config equivalence **UNKNOWN**, nessun test runtime eseguito. Recuperare receipt prima di attribuire a questo APK i finding statici. Fonte backend attuale in Admin migration; workspace Supabase storico non Git, live schema non verificato oggi.

## Cosa cambia / non incluso

Retry automatico oltre la singola finestra per errori transitori, dati locali usabili se qualificati, date corrette e banner pertinenti. Restano split local/cloud già integrato, NavHost stabile, scope/revoca, transaction/ACK/recovery overlay, price/history/tombstone e dipendenze esistenti. Nessun secondo motore, refactor ampio, nuova dipendenza, API pubblica, reset/install, migration o deploy implicito.

## Planning — Codex su override

[RCA canonica](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/ROOT_CAUSE_ANALYSIS.md): AND-R01 recovery5 tentativi senza successore; AND-R02 history250ms e 429/5xx unknown; AND-Q01 outbox transient permanentemente esclusa dopo5; AND-Q02 decode malformed abortisce loop; AND-D01 lastSuccessAt da esito non qualificato/replay; AND-U01 FAILED generico persistente. AND-L01 cold auth→SignedOut è scenario da riprodurre prima di patch; AND-P01 costo proof no-work confermato come percorso, impatto non misurato. Finding iOS include budget3 persistente e conteggio falso localOnly, differenze native da mantenere distinte.

File/simboli candidati, sotto `app/src/main/java/com/example/merchandisecontrolsplitview/`:

- `MerchandiseControlApplication.kt`: `schedulePendingBusinessRecovery` e auth/lifecycle admission.
- `data/CatalogAutoSyncCoordinator.kt`, `HistorySessionPushCoordinator.kt`, `SyncErrorClassifier.kt`, `SupabaseShopSyncReadRemoteDataSource.kt`: retry/categorie/Retry-After, nessun nuovo coordinator.
- `data/InventoryRepository.kt`, `SyncEventModels.kt`: retry eligibility e decode per-entry; `BusinessWriteAttempt.kt` solo dopo RED/dipendenze.
- `data/CatalogSyncStateTracker.kt`, `viewmodel/CatalogSyncViewModel.kt`: tempo/scope/esito qualificato.
- `ui/components/CloudSyncIndicator.kt`, `ui/navigation/NavGraph.kt`, `ui/screens/OptionsScreen.kt`, `app/src/main/res/values*/strings.xml`: UI/lingue.
- `data/SupabaseAuthManager.kt`, `ShopContext.kt`, `Task126BusinessDataScopeRuntimeGuard.kt`: auth locale condizionale, nessuna guardia rimossa.
- `data/ShopSyncRecoveryCoordinator.kt`: principalmente regressioni/profiling; non riscrivere overlay/manifest/cutover esistenti.

Test specifici e batch in [IMPLEMENTATION_PLAN](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/IMPLEMENTATION_PLAN.md). Fasi0 baseline/owner;1 RED;2 capacità/shell solo gap provati;3 retry;4 integrity/independence;5 UI;6 collaudo/performance;7 review/consolidamento. Nessun general sync WorkManager attuale: PriceBackfillWorker è distinto. Catalog realtime callback non è collegata dall'app (poll bounded), History realtime sì; non introdurre listener per somiglianza con iOS.

## Decisioni e alternative

| Decisione | Alternativa scartata / motivazione |
|---|---|
| Window bounded con successore transient scope-bound | Trigger manuale obbligatorio o loop veloce non soddisfano liveness/efficienza |
| Conservare record invalido e isolare soltanto indipendenti | Catch-all/skip sigillo corrotto viola integrità |
| Timestamp dell'esito all'origine | `now` in VM/recomposition inventa un successo |
| Auth cold-start da RED prima della patch | Togliere SignedIn guard alla cieca rischia accesso foreign |
| Misurare proof no-work prima di ottimizzare | Cache indiscriminata può saltare corruzione/revoca |

## Criteri di accettazione

CA01–CA12 vincolanti nella [matrice canonica](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/TEST_MATRIX.md). B=BUILD, S=STATIC, E=Emulator, M=MANUAL; UNIT distinto da runtime.

| CA | Oggetto Android | Tipo | Stato / casi |
|---|---|---|---|
| CA01 | Baseline, config/build e WIP | S/B | NOT_RUN candidato; baseline S eseguita |
| CA02 | Offline locale e Save | UNIT/E | NOT_RUN T01/T02/T06/T14 |
| CA03 | Retry/queue oltre5 e restart | UNIT/E | NOT_RUN T03–T05 |
| CA04 | Revoca/scope/callback | UNIT/E | NOT_RUN T06/T07/T12 |
| CA05 | ChangedFields/conflitti | UNIT/E | NOT_RUN T08/T09 |
| CA06 | Atomicità/replay/ACK/prezzi | UNIT/E | NOT_RUN T03/T10/T13/T16 |
| CA07 | Per-item independence/barriere | UNIT/E | NOT_RUN T11 |
| CA08 | Recovery A–G e overlay | UNIT/E | NOT_RUN T07/T12–T14 |
| CA09 | Timestamp/esiti/banner | UNIT/E | NOT_RUN T15/T19 |
| CA10 | Layout/lingue/TalkBack | E/M | NOT_RUN T14/T18 |
| CA11 | Due direzioni/performance | E/B | NOT_RUN T16/T19/T20 |
| CA12 | Gate/review/integration | B/S/M | NOT_RUN |

## Matrice test

[T01–T20 completi](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/TEST_MATRIX.md) con precondizioni, fault, esiti local/remote/UI, strumenti e prove. Registro iniziale Android: **T01, T02, T03, T04, T05, T06, T07, T08, T09, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20 = NOT_RUN** (execution non iniziata). `docs/CODEX-EXECUTION-PROTOCOL.md` e DoD UX del master si applicano alla futura execution; compile androidTest non equivale a esecuzione. T20 conserva target3s foreground sano e30 campioni/direzione p50/p95/max su dataset sintetico locale20k/40k+; nessun bulk nel negozio reale.

## Rischi rimasti

APK/source/config non allineati per prova, timeout backend non attribuito, cold auth e writer dependency da riprodurre, retry storm o stale timers, contesa no-work non misurata. Guard di generazione/cancellation e validazione ricevute vanno preservati. Review indipendente richiesta; gate fisici, accessibilità e runtime non dedotti dalla CI.

## Check planning / tracking

✅ ESEGUITO S: sorgenti/GitHub/tracking/contratti e dossier con CA/handoff; WIP preservato. ❌ NON ESEGUITO build/lint/warning/JVM/Emulator: documentazione soltanto, N/A build di fase e NOT_RUN test applicativi. ⚠️ NON ESEGUIBILE qui acceptance live finale: manca source/config receipt APK e ammissione fixture/runtime; non impedisce preparazione dei test isolati dopo assignment.

## Handoff

[Handoff completo all'executor distinto](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/EXECUTION_HANDOFF.md). Attendere assegnazione successiva prima di EXECUTION; partire da RED window recovery/outbox transient, malformed head e timestamp replay. Un owner writer per repo, risorse pesanti serializzate. Reviewer indipendente usa [checklist](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/REVIEW_CHECKLIST.md); dopo FIX sempre REVIEW, mai DONE automatico.

## Execution / Review / Fix / Chiusura

Execution locale conclusa, EXECUTION→REVIEW;accettazioneesternaBLOCKED. Il planning originale noncertificava unarisoluzione: valgono ora igate locali qui registrati e il relativo handoff. TASK-143resta sospeso con prove storiche intatte;nessunDONE.

### Execution — gate locali 2026-10-16T04:45:41Z

Candidata finale locale congelata20file: regressione Debug1328casi/1321PASS/0FAIL/7SKIP, assembleDebug PASS, assembleRelease PASS(unsigned), lint PASS con57warning non sulle linee aggiunte e0error; nessun warningKotlin emesso. Le209segnalazioniD8provengono dalla dipendenzaWeChat6.8.34nonmodificata, senza pretesa di confronto ratebaseline. XML/report/APK nuovi archiviati nel registro; APKstorico1510010… hashverificato fuoriapp/build, ricevutaoriginale intatta. Nessuna installazione o azione device.

Recovery/queue/date,4negativiWAIT e4Q01, SDKwire1POST/1invocazioneV6, fixturestoriche correction5→6 e baselineTASK004 sonoGREEN su questo freeze; review indipendente APPROVED_LOCAL_DELTA. Rimangono originali32FAIL regression01 e2FAIL regression02 con causafix/receipt, non cancellati. Dopo reviewSDK preservata condizione schemaheader identica all'SDK. La baselineTask004 è JVM/Robolectric e non prova UICompose/Espresso. Dettaglio baseline: DefaultInventoryRepositoryTest257PASS/0FAIL/1SKIPpreesistente; DatabaseViewModelTest61PASS; ExcelViewModelTest52PASS. Lo skip originale è conservato nel XML e non conteggiato come prova eseguita.

CIcandidata, runtime sulleappinstallate, TESTbidirezionale/offline/reopen, performance20k/40k30campioni e accessibilità/fisico restano NOT_RUN. Legacy esaurito senza failure tipizzata resta fail-closed: upgrade/liveness reale UNKNOWN. Config/WIP/HEAD/index e dati/sessioni conservati secondo manifest; controllofinale06PASSGit/5fileprotetti/39piniOS+Android,33/37preexistingimmutati e4documenti trackingaggiornati/preservati. Nessun DONE.

### Execution — assegnazione successiva 2026-10-16T02:15:45.420438+00:00

Execution ricevuta dopo il planning da coordinator01a14272; executor distinto dal planner. Baseline/WIP e risultati nel [registro canonico](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/EXECUTION/EXECUTION_LOG.md). Solo esperimenti sintetici e batch ammessi; review successiva indipendente. Nessuna modifica reale o distribuzione implicita.


### Handoff post-execution → REVIEW — 2026-10-16T06:01:45.021167+00:00

Gate locali PASS sul freeze di 20 file: 1.328 casi, 1.321 PASS, 0 FAIL e 7 SKIP; Debug PASS, Release PASS_UNSIGNED_BUILD_ONLY, lint Debug PASS con 57 warning/0 errori e Release vital skipped. La toolchain WIP Gradle9.6/AGP9.4.1/KSP2.3.6 è preservata e distinta da HEAD9.5.1/9.2.1/2.3.4. Nessun warning Kotlin; 209 D8 dalla dipendenza WeChat non modificata. Nessun finding lint sulle righe aggiunte, senza confronto quantitativo con una nuova baseline. [Handoff comune](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/EXECUTION/20261015-01/LOCAL_GATE_HANDOFF.md), [CA/T Android](/Users/minxiang/Desktop/iOSMerchandiseControl/docs/COORDINATION/MOBILE_SYNC_RECOVERY_OFFLINE_UX/EXECUTION/20261015-01/android-final-acceptance-boundary.md).

Review indipendente APPROVED_LOCAL_DELTA; originali FAIL/RED e sette skip conservati. APK storico1510010… e receipt verificati fuori app/build; APK nuovi archiviati separatamente. Nessuna installazione, azione device, reset o logout. CI sull'esatto SHA, configurazione integrata, Release firmata, upgrade/app TEST bidirezionale/offline/reopen, accessibilità fisica e benchmark con 30 campioni su 20k/40k sono NOT_RUN. Upgrade e liveness del legacy exhausted sono UNKNOWN.

Owner del prossimo passo: coordinator/reviewer-integrator. Validare patch e WIP, assegnare pubblicazione/CI e poi installazione/fixture TEST, preservando dati, sessioni e coda. Il mandato locale esclude commit/push/installazione/live write/deploy; l'accettazione comune resta BLOCKED, nessun DONE.


### Handoff integrazione Git — 2026-10-16 UTC

Il responsabile di integrazione riprende il mandato umano di consolidamento e il §8 del mandato mobile: commit/push normali, review e merge dopo gate verdi. Il limite no-commit/no-push della precedente execution locale resta riferito a quell’incarico; non viene rimosso dalla baseline originale. Stato prodotto ancora **BLOCKED / REVIEW**, nessun DONE o acceptance runtime.

Selezione: soli file prodotto/test del freeze finale, tracking pertinente e ricevute testuali esplicitamente selezionate. Configurazioni WIP preesistenti escluse e copiate in backup privato verificato; nessun loro valore o binario pubblicato. Le prove locali mantengono la propria configurazione WIP e non vengono attribuite per equivalenza al nuovo commit. La configurazione pubblicata resta quella già in HEAD.

Riuso del branch remoto di integrazione già esistente, senza creare branch/worktree; PR normale e CI sull’esatto SHA con configurazione HEAD prima del merge. Gate richiesti per la qualifica CI: Android assembleDebug/lint/full unit; iOS Debug/full unit e UI/Analyze/scan. Release con i pin HEAD, pacchetto dispositivo, installazione preservativa, legacy reale, TEST, prestazioni e causa storica iOS A restano gate distinti, non promossi da questa fase. Nessuna installazione, live write, logout/reset, migrazione, deploy, dipendenza o accesso persistente introdotto.

Il risultato conclusivo con SHA/PR/CI e prova di preservazione viene consegnato fuori dai repository prodotto nel dossier Ecosistema `evidence/mobile-sync-integration-20261016/`, senza aggiungere commit sul vecchio branch dopo il merge. I riferimenti raw/local-only delle evidenze sono dichiarati nell’indice di pubblicazione del dossier canonico.
