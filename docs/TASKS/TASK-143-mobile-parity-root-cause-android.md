# TASK-143 — Mobile parity root-cause android

## Stato

- File task: `docs/TASKS/TASK-143-mobile-parity-root-cause-android.md`
- Stato: `FIX`
- Fase: `FIX`
- Responsabile: `CODEX_EXECUTOR_ANDROID`; orchestratore parent, reviewer indipendente separato.
- Data: 2026-09-28
- Baseline: `d7c4953c4ed6bc2a33cc5dbfd009eb862f70feac`
- Branch: `codex/mobile-auth-session-restore` (follow-up da main `1bf758dd8d83a771dcfdb1844a223a036ba19eff`; primo batch integrato dalla PR10)
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

### Addendum planning autorizzato — ripristino sessione, 2026-09-29

Dopo l’integrazione della PR10 (merge `1bf758dd`, CI main SUCCESS con 978 PASS / 7 SKIP), il coordinatore osserva sul TEST R-A05 un normale force-stop/restart senza reinstallazione o reset: Options passa da Connected a Not signed in e rimane così oltre 20 secondi. Il sintomo non prova perdita di storage o causa specifica; log sanitizzati richiesti. L’ispezione dei sorgenti SDK 3.6.0 effettivi e del client individua un ramo da riprodurre: refresh iniziale fino a 90 s o RefreshFailure ritentato, attesa applicativa 10 s, successivo Authenticated ignorato dall’observer. La precedente suite auth non esercita questo percorso.

Il parent autorizza nello stesso task (CA-06/07/10), su branch separato dal main integrato, una regressione deterministica prima della patch per Initializing prolungato e RefreshFailure seguiti da Authenticated. Se confermata, correzione minima del ripristino tardivo, mantenendo validation, logout esplicito, switch scope e rejection delle sessioni invalide; nessun login implicito dopo logout, nessun token/sessione reale letto, nessuna modifica alla policy server o reset. Review indipendente, test mirati/adiacenti, gate canonici e PR separata con CI exact-SHA; distinguere difetto deterministico da attribuzione dell’episodio live. Gli artefatti e i PASS della PR10 restano validi per lo SHA precedente e non sono riciclati sul nuovo delta.

### Addendum planning coordinato — controparte auth R-I05, 2026-10-01

La review circoscritta dello SDK Android 3.6.0 individua un candidato distinto da R-A06: `refreshCurrentSession` cattura il refresh token prima della richiesta ma, al ritorno, importa la risposta usando come source la sessione corrente. Dopo logout seguito da nuovo login, una risposta precedente potrebbe sostituire le nuove credenziali. `clearSession` cancella il job auto-refresh ma non necessariamente una chiamata esplicita attivata dal fallback access-token di una richiesta business. Nessun difetto runtime viene dichiarato prima della riproduzione.

Il parent autorizza nell'attuale task CA-06/07/10 un test deterministico sullo SDK reale, storage in memoria e transport controllato, senza rete o credenziali reali e senza nuove dipendenze. Verificare logout senza nuovo login, nuovo account, nuovo login stesso account e rilettura da storage. Produzione invariata durante il rosso; solo dopo prova e review indipendente proporre il confine minimo di correzione, preservando bootstrap/refresh normali, storage esistente e integrazioni. Nessun fork/upgrade SDK, secondo motore auth o refactor generale è implicitamente autorizzato. Nuovi gate e nuovo commit richiesti per un eventuale delta; R-A06 e4bdac44 conserva il proprio ambito.

### Addendum planning autorizzato — R-A07 refresh precedente e nuovo login, 2026-10-01

Il candidato è ora P1 riprodotto e confermato dal reviewer indipendente: SDK3.6.0 reale, HTTP controllato cancellabile, storage in memoria condiviso. Quattro casi ufficiali: 2 controlli PASS (logout senza nuovo login e cancellazione del job SDK); 2 FAIL (nuovo account e nuovo login stesso account). Dopo la risposta tardiva, current SDK, sessione persistita e nuova istanza riletta contengono tutti la sessione precedente. Produzione byte-identica a e4bdac44 durante il rosso. Non è la causa attribuita al precedente episodio R-A06, ora verificato con log e UI nel riavvio ordinario.

Correzione autorizzata nel confine app: facciata interna che conserva l'API esistente `SupabaseClient?`, un solo client SDK attivo, generazioni serializzate dello storage SDK esistente e notifiche auth. Gli intenti espliciti logout/clear/nuovo login ritirano il precedente client; i suoi save/load/delete non possono toccare la nuova sessione. Rimozione locale e readback prima di rendere disponibile il nuovo client, fallimenti dichiarati e fail-closed nel processo; nessun falso logout riuscito con record persistito. La cancellazione/restart del client non sostituisce il fence durevole sulla scrittura. Conservare startup, refresh ordinario, R-A06 tardivo legittimo, login A/B e stesso account, cancellazione e storage fallibile. Un guardrail controllato per HTTP500 del logout è autorizzato, senza attribuirgli un difetto prima della prova.

File minimi previsti: owner/facciata auth interna, `MerchandiseControlApplication`, `SupabaseAuthManager`, cached API in `SupabaseShopSyncRpcInvoker`, cleanup del canale Realtime dal suo client creatore, test auth mirati. Interfacce pubbliche dei consumer restano compatibili; nessun upgrade/fork SDK, nuova dipendenza, secondo motore auth, modifica Room/business data/journal o refactor generale. I test devono esercitare il vero confine applicativo con SDK reale: il probe SDK originale resta prova storica del rosso e non può diventare un falso verde per una libreria non modificata. Review indipendente sul freeze, gate canonici aggiornati e nuovo TEST artifact/CI prima dell'integrazione.

**Guardrail storage autorizzati nello stesso batch:** il test sul manager reale con logout HTTP500 ha confermato UI SignedOut ma sessione precedente persistita e riletta da nuovo SDK. La pulizia locale verificata deve quindi avvenire anche se il logout remoto fallisce. La migrazione SDK del record globale legacy `session` richiede inoltre selezione per progetto: un adapter delegato di Settings può consentire la migrazione/rimozione solo quando l'issuer del JWT sintetico/record esistente corrisponde esattamente all'endpoint auth del progetto configurato. Questo confronto seleziona lo storage e non autorizza una sessione: refresh/validation SDK restano obbligatori. Record foreign, malformati o senza issuer restano preservati e non vengono migrati nel progetto corrente. Chiave/serializzazione canoniche SDK invariate; nessun wipe globale o nuovo marker. Coprire con test SDK reali coexistence progetto+legacy proprio, record foreign/unknown, errori dello storage e restart, senza leggere o loggare credenziali reali.

**Addendum della review R-A07, 2026-10-01:** la catena primaria SDK3.6.0 → Settings no-arg1.3.0 → SharedPreferencesSettings1.3.0 usa lo stesso file `${applicationContext.packageName}_preferences`, ma `apply()` è asincrono e `hasKey()` legge la cache; anche il flag commit=true della libreria ignora il Boolean. Non prova la cancellazione durevole su failure disco. È autorizzato un adapter interno checked sul medesimo SharedPreferences e sulle chiavi/formato esistenti: scritture/rimozioni String auth sincrone con `commit()` e Boolean falso trasformato in errore di persistenza; nessuna nuova dipendenza, file auth, schema o motore. Prima della patch, test controllato del manager/SDK con memoria aggiornata ma commit disco fallito e restart da snapshot persistita; dopo, errore fail-closed e retry verificato, senza dichiarare simulata failure come guasto live osservato. Il precedente errore di cleanup deve inoltre rimanere non dismissibile se picker/provider fallisce o viene annullato prima della pulizia verificata. Un record legacy con token strutturalmente malformato (segmenti JWT vuoti) deve restare preservato/nascosto; controllare la struttura minima per selezione storage senza verificare firme o autorizzare token. Questi sono fix circoscritti del medesimo contratto R-A07, da includere nel freeze/re-review e gate finali.

### Addendum planning autorizzato — R-A08 History ISO millisecondi, 2026-10-01

Il preflight TEST scoped delle20:18:23Z rifiuta History con shape/storage non valida (compressione0). La diagnosi read-only del coordinatore isola3righe attive valide per storage/data/overlay, con timestamp business UTC ISO8601 esattamente tre millisecondi; l'helper backend ammette soltanto il formato legacy spazio/secondi. Le ricevute sanitizzate preservate in `evidence/staging-current-history/` del pacchetto parent non contengono ID o contenuti. Il controllo dei nativi conferma che il ledger recovery Android produce `invalid` sulla stessa stringa: il solo fix backend non consentirebbe la verifica del checkpoint.

È autorizzato nello stesso task CA-07/10 un test rosso sul recovery reale con fixture sintetica e digest della stringa originale, seguito da un helper specifico History nel `ShopSyncRecoveryCoordinator`. Conservare il formato legacy esistente; aggiungere soltanto `YYYY-MM-DDTHH:mm:ss.SSSZ` esatto (3cifre, anno non0000, calendario gregoriano valido, ore0–23/secondi0–59, Z uppercase). Nessun trim, offset, forma senza frazioni o diversa precisione, conversione della stringa nel digest, modifica ai timestamp prezzi/UTC6, modello/fingerprint/outbound, dati/queue o schema. Room/new instance devono conservare la stringa esatta; casi invalidi e mismatch digest restano rifiutati. Contratto coordinato identico backend/iOS, nessuna riscrittura delle3righe. Review indipendente e gate canonici finali aggiornati dopo R-A07+R-A08.

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

### Addendum autorizzato R-A08 — oracle condiviso (2026-10-01)

Il confronto statico della fixture sintetica condivisa di 45 vettori ha individuato `0000-01-01 00:00:00`: il legacy parser Java preesistente lo ammette, mentre History backend non ha mai ammesso l'anno zero. Autorizzato un rifiuto minimo **solo nel nuovo helper History**, prima della delega al legacy helper. Le forme legacy valide restano immutate; helper condiviso legacy, prezzi e UTC6 restano byte-identici. La precedente formulazione di preservazione legacy non richiede accettare questo valore non valido per History.

Copiare la fixture condivisa senza trasformazioni e verificare i 45 vettori attraverso il percorso reale recovery/checkpoint. Registrare rosso anno-zero prima della patch, verde del contratto, digest raw e regressioni adiacenti. Il campo `legacyAccepted` dell'oracle descrive il contratto backend; non dichiara modificato o verificato il legacy helper fuori History. Nessuna riscrittura dati, nuovo schema, SDK o dipendenza.

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

### Esecuzione R-A06 — 2026-09-29 UTC — ripristino auth tardivo

**Osservazione e attribuzione:** il coordinator ha osservato Connected prima del force-stop e Not signed in stabile dopo il normale restart, senza reinstallazione/reset o nuovo login. Il log sanitizzato del processo corrente conferma attesa applicativa di10s e bootstrap `Timeout` alle22:19:30.821, poi SignedOut. Non prova perdita delle credenziali né un successivo Authenticated reale: questi due esiti non vengono dedotti dalla UI. Nessuna sessione, preferenza auth o token reale è stata letta; nessun accesso al device5556 da questa lane.

**Contratto verificato nel codice SDK effettivo:** Supabase Auth3.6.0 abilita load/save automatici della sessione; il client Android usa lo storage privato tramite SettingsSessionManager. Il refresh iniziale può rimanere sospeso oltre10s e il retry transitorio può emettere RefreshFailure prima di Authenticated. L'app attendeva soltanto10s e ignorava Authenticated successivo nell'observer; il restore è chiamato una sola volta al bootstrap. Il test controllato dimostra questa lacuna separatamente dall'esito ancora pendente del cold restart live.

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SupabaseAuthManager.kt` — ripresa limitata al bootstrap Timeout/RefreshFailure, validazione esistente e fence per generazione/stato/identità prima di publish o cleanup.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/SupabaseAuthManagerTest.kt` —15 nuove regressioni deterministiche, classe finale25 test; clock/status controllati, senza sleep reali o credenziali.
- `docs/TASKS/evidence/TASK-143/android-ra06-test-manifest.json` — conteggi per classe, skip, lint, hash, prove rosse e Compose storico distinto dal nuovo gate.
- `docs/TASKS/evidence/TASK-143/android-ra06-build-receipt.json` — candidato TEST, provenienza sorgenti e confronti configurazione/firma come booleani, senza valori protetti.

**Azioni eseguite:**
1. Prima della patch, su main1bf758dd, suite auth16 test:14 PASS/2 FAIL per Initializing oltre10s→Authenticated e RefreshFailure→Authenticated. Guardrail logout, invalid session, invalidazione SDK e switch account verdi. Log `/tmp/task143-auth-restore-red.log`, XML omonimo e receipt `/tmp/task143-auth-restore-red.json`.
2. Latch soltanto per bootstrap interrotto: consumato prima del refresh sotto lo stesso mutex; observer rilegge lo stato SDK corrente quando acquisisce il lock. Successo riusa validazione/publish; errore transitorio conserva la policy locale offline esistente, invalidazione definitiva resta fail-closed.
3. Login esplicito, logout, invalidazione SDK e shutdown invalidano il tentativo. Login/logout lo fanno prima di attendere il mutex; un vecchio refresh non può pubblicare o cancellare la sessione successiva. Cancellation propagata; nessun worker, timer, login generico da evento SDK, nuovo storage o cambio UI.
4. Primo mirato118 PASS sul freeze iniziale; la successiva review ha individuato due regressioni della patch e richiesto quattro nuovi casi. Quel freeze e quel risultato restano storici, sostituiti dal freeze finale e dai gate sotto.

**Incertezze/Handoff:** validazione locale completa sotto; esito autenticato del nuovo candidato PENDING al coordinator. Nessuna nuova affermazione prestazionale: le misure Room/Storefront precedenti non misurano il ripristino autenticato.

### Esecuzione — 2026-10-01 — R-A07 confine auth SDK e R-A08 History ISO millisecondi

**File modificati R-A07:**
- `data/GenerationOwnedSupabaseClient.kt` — facciata interna stabile, un solo SDK attivo, lease storage/status per generazione; logout/clear/nuovo login serializzati, cleanup verificato prima del nuovo client, close normale conserva la sessione.
- `data/ProjectSessionPersistence.kt` — adapter del solo legacy `session`; issuer esatto del progetto seleziona storage senza autorizzare JWT. Canonical key/serializer SDK invariati, own legacy eliminato/verificato, foreign/malformed/unknown preservati e nascosti alla migrazione. CheckedAuthSettings verifica il Boolean di commit sullo stesso SharedPreferences SDK per String put/remove.
- `MerchandiseControlApplication.kt` — delegate Settings SDK creati una volta, lease assegnati già nel primo install(Auth); API `SupabaseClient?` preservata.
- `data/SupabaseAuthManager.kt` — snapshot SDK per operazione, relay owner, intent correnti verificati sotto lifecycle lock; logout locale indipendente da picker sospeso, publication/cleanup superseduti fenced anche stesso UUID. Cleanup fallibile resta ErrorRecoverable finché verificato; retry reale disponibile.
- `data/SupabaseShopSyncReadRemoteDataSource.kt` — cached invoker ricostruisce API sul client SDK catturato per chiamata.
- `data/SupabaseRealtimeSessionSubscriber.kt` — remove/disconnect dal Realtime che ha creato il canale.
- `ui/screens/OptionsScreen.kt`, `values{,-en,-es,-zh}/strings.xml` — UI/UX intenzionale: errore locale redatto e tradotto, CTA Riprova richiama logout; non nasconde un cleanup non verificato come SignedOut (chiarezza e recupero).
- `data/SupabaseAuthLifecycleTest.kt`, `data/ProjectSessionPersistenceTest.kt`, `data/CheckedAuthSettingsTest.kt` (src/test) —26+5+3 controlli real SDK/app boundary e storage/restart, senza credenziali/network; vecchio probe SDK rimane rosso storico distinto.

**File modificati R-A08 (executor dedicato, runner coordinato):**
- `data/ShopSyncRecoveryCoordinator.kt` — un solo callsite History usa helper che accetta legacy esistente o ISO UTC esatto3millisecondi/anno non0000/calendario gregoriano, restituendo stringa originale. Prezzi/UTC6/model/fingerprint/outbound invariati.
- `data/ShopSyncRecoveryCoordinatorTest.kt` —6 metodi nuovi:27 vettori iniziali più oracle condiviso45, recovery reale e Room riaperto, raw checkpoint byte-preserved, invalid grammar/calendar/digest e prezzo invariato.
- `app/src/test/resources/fixtures/history-timestamp-compatibility-v1.json` — oracle sintetico45 byte-identico alle altre lane, SHA b5848df0; nessun dato business.
- `docs/TASKS/evidence/TASK-143/android-ra07-freeze-manifest.json`, `android-ra08-test-manifest.json`, `android-ra07-ra08-case-summary.md`, `android-ra07-ra08-{final-manifest,canonical-receipt,compose-receipt,test-build-receipt}.json` — receipt versionate bounded, hash sorgenti/patch/XML e percorsi persistenti dei raw artifact, disponibili e verificati.

**Azioni eseguite:**
1. Rosso SDK3.6.0 su produzione byte-identica e4bdac44:4 casi/2PASS2FAIL, current/saved/new SDK tutti stale A dopo logout→newB o nuovo login stessoA. Manager reale HTTP500:1FAIL, UI SignedOut ma A stored/restarted. SDK Settings constructor/restart:2FAIL, own legacy riappare e foreign viene consumato. SDK API automatico Postgrest refresh resta raggiungibile fuori app authMutex; nessun fork/upgrade/new dependency/new auth engine. Non attribuito al precedente cold restart R-A06.
2. Lease retired save/load/delete lanciano cancellation prima della mutation/pubblicazione SDK; status relay accetta solo client attivo. Canonical session+own legacy e PKCE delete/readback devono riuscire prima di esporre il nuovo SDK; storage failure chiude accesso remoti, non dichiara logout riuscito. Logout HTTP fallito/offline/caller canceled completa cleanup locale. Expected SDK snapshot verifica stale cleanup sotto lifecycle lock; intent corrente consente new login A/B durante TRANSITION.
3. Guardrail canceled logout mentre picker tiene app mutex inizialmente rosso: sessione vecchia retained. Correzione usa owner lifecycle mutex senza aspettare picker e nessuna HTTP NonCancellable. Controllo finale cancel+join e nuovo SDK empty prima di pickerRelease; vecchio picker poi ritorna false, senza publication tardiva. Delete sospeso mantiene Checking; errori delete/readback e retry, PKCE silently retained e cached RPC reale verificati.
4. Setup failures esclusi:2 engine seams iniziali; Google fixture non-JWT; opt-in Ktor legacy e awaitInitialization erroneo sul caso SDK empty. Il source SDK lascia Initializing quando load vuoto; il guardrail usa completion reale `loadFromStorage()==false`. Retry cleanup su SDK HTTP già chiuso distingueva male internal JobCancellation da caller canceled: corretto e ripetuto mirato, nessun falso verde.
5. R-A08 rosso actual recovery1FAIL con `recovery_manifest_digest_mismatch_history`; production754c3bf7→4aecaf98, testc10e5a7f identico red→green. Wholeclass73/73PASS, helper History restituisce la stringa originale e conserva exact raw digest/Room su restart. Contratto ristretto coordinato dal parent; nessuna riscrittura delle righe backend.
6. Freeze-v1 storico, precedente alla re-review:155/155PASS,0FAIL/ERROR/SKIP,17s. Lifecycle20/Persistence5/AuthManager25/Recovery73/ShopSyncRead19/RealtimeCoordinator13. Source e6XML ufficiali congelati con log in bundle persistente `evidence/android-auth-lifecycle/freeze-v1/`; manifest SHA256 ba1df5314a04b2c086ee56590c8a2e716f4d9582b2e19ffdbac496dd0fdb035d. Review FIX REQUIRED su due P1 circoscritti sotto; questo verde non autorizza i gate finali della patch successiva.
7. Review fix R-A07 completati:4 rossi manager reale per Google/WeChat cancel/provider failure dopo cleanup fallito, poi osservatore Unconfined riproduce4 publication transitorie false; i branch scelgono cleanupError prima di pubblicare. Due rossi normal A→B riproducono cleanup failure nata nel sign-in; il solo errore typed di persistenza è mappato direttamente a cleanupError in entrambi provider. Record legacy `.payload.` rosso preservato, selettore limitato a tre segmenti JWT non vuoti. Leaf disco actual SDK/manager:3 casi,2FAIL1PASS, false commit aggiorna cache ma non snapshot persistita; logout appariva SignedOut mentre nuovo SDK disk-only ripristinava A, save pubblicava Authenticated senza commit riuscito. Adapter checked sul medesimo SharedPreferences SDK:3/3 verdi. Nessun guasto disco live asserito; harness/barrier failures precedenti esclusi.
8. Supplemento R-A08 oracle45: rosso sul recovery reale solo per legacy year0000; guard History anticipato prima del delegate legacy, senza cambiare helper condiviso/prezzi/UTC6. Test9ae2dba1 e fixtureb5848df0 identici red→green; production finale46bc3f6b. Wholeclass74/74 PASS, oracle45/45. Review indipendente auth-v4 e supplemento/composito APPROVED; freeze finale18 hash/7XML,165/165 PASS0FAIL/ERROR/SKIP (Lifecycle26/Persistence5/Checked3/Manager25/Recovery74/RPC19/Realtime13), manifest raw0ae8cb2a.
9. Gate canonico sul freeze approvato: `./gradlew assembleDebug test lint --max-workers=1 --console=plain` PASS2m51s;1.040 totali/1.033 PASS/7 SKIP/0FAIL/ERROR, source18 identico prima/dopo,0 warning Kotlin/deprecation. Lint0 errori/54 warning rispetto ai52 della CI exact-e4: sole2 nuove signature normalizzate UseKtx in CheckedAuthSettings. Motivazione accettata dal parent: KTX edit(commit=true) restituisce Unit e perderebbe il Boolean di commit richiesto e verificato; nessuna suppression o sostituzione della API.
10. Cinque Compose Storefront esistenti PASS effettivi (`OK (5 tests)`, cinque metodi distinti STATUS_CODE0), classe target esatta su emulator-5554. Dopo due avvii rifiutati del vecchio AVD occupato sulla porta5580, fresh AVD autorizzato Codex_Mobile_Parity_Final_API_35 usa immagine API35arm64 già installata, nuova userdata sintetica, Pixel7 1080×2400/density420. Nessuna copia dati o operazione sulle istanze5580/5556. Bootcompleted=1, install canonical app/test PASS; source18 identico. Questi sono test UI Compose Storefront, non test auth o failure disco. AVD lasciato disponibile per la lane performance.
11. Build TEST separata PASS14s, sole whitelist pubbliche e override autorizzati, WeChat=false/Storefront=true. APK persistente `test-builds/android/ra07-ra08/app-debug-test-complete-profile-ra07-ra08.apk`, SHA25696b3d6134ea50b61e226a14105f8de014ebefa1d8f86c66e3ee740cccac6be70. Configurazione generata/embedded, applicationId/versionCode/firma debug equivalenti al candidato e93; source18/config primaria/profilo privato immutati e local.properties worktree ripristinato. Nessun valore protetto stampato; target runtime autenticato resta al coordinator.

**Check obbligatori finali locali R-A07/R-A08:**
| Check | Stato | Evidenza |
|---|---|---|
| Build Gradle assembleDebug | ESEGUITO | Canonico PASS; TEST separato e assembleDebugAndroidTest PASS sul source finale. |
| Lint | ESEGUITO |0 errori/54 warning; baseline52 più2 UseKtx intenzionali motivati e accettati sopra. |
| Warning Kotlin/deprecation nuovi | ESEGUITO |0 nel log canonico; nessuna suppression introdotta. |
| Coerenza planning | ESEGUITO | Addendum R-A07/R-A08 CA-06/07/10; nessuna API consumer pubblica, dipendenza, Room/schema/journal/nuovo motore. |
| Criteri CA-06/07/10 della slice | ESEGUITO locale; live NON ESEGUITO qui | Rosso→verde+165 mirati,1.033 JVM PASS/7 SKIP e5 Compose; autenticato/performance/integration affidati al parent. |
| Review CA-11 source | ESEGUITO | Auth-v4 APPROVED14 hash/91 PASS; supplemento/composito APPROVED18 hash/165 PASS. GitHub/CI/integration gestiti dal parent. |

**Baseline TASK-004 / limiti:** full JVM/Robolectric include DefaultInventoryRepositoryTest218 casi/217 PASS/1 SKIP, DatabaseViewModelTest61/61 e ExcelViewModelTest52/52; non sono test UI Compose/Espresso. I7 skip restano fixture Supabase locale assente, realtime live non configurato,3 harness Excel sospesi, workbook opzionale assente e benchmark grande opt-in, nessuno conteggiato come PASS. La suite non sostituisce gate autenticato. Legacy global PKCE mantiene migration SDK default una sola volta: nessuna rimozione globale senza provenance. Stato task FIX, non DONE; parent coordina runtime, misure prestazionali, CI e integrazione.

## Review

Review indipendente e re-review del primo batch completate; R-A04 scoperto nel successivo collaudo live è stato riprodotto, corretto e revisionato separatamente. Sorgente APPROVED, nessun P0/P1/P2 source aperto dopo R-A01/R-A02/R-A03/R-A04; gate locali aggiornati PASS (970 JVM e 5 Compose). R-A05 sul successivo diniego RPC è anch’esso corretto e revisionato. Gate aggiornato985 totali/978 PASS/7 SKIP; ritest autenticato conferma `checkpoint_resource_exceeded` correttamente classificato, preservando binding, dati e journal. Il blocco server TOAST resta aperto. Evidenza: [independent-review.md](evidence/TASK-143/independent-review.md). La review tecnica non è un'approvazione GitHub di un maintainer né una conferma live.

## Fix

### Batch review R-A07 — 2026-10-01 — persistenza durevole e cleanup non dismissibile

Freeze-v1 **FIX REQUIRED**, report indipendente persistente `evidence/independent-review-resumed/android-ra07-freeze-v1-review.md`. Due P1 dello stesso contratto CA-06/07/10, planning addendum registrato dal parent prima delle correzioni.

- Cleanup non verificato:4 test rossi del manager pubblico con storage fallibile e nuovo SDK ripristinano A dopo Google/WeChat cancel/provider failure; UI poteva diventare SignedOut direttamente o dopo dismiss del generic error. Patch limitata ai tentativi che iniziano con il precedente errore cleanup: lo conserva se il login non riesce e il canonical clear non è verificato, senza delete implicito. Flussi ordinari invariati. Rosso XML553f4cff; verde lifecycle24/24 nello slice successivo.
- Durabilità: il delegate SDK Settings no-arg1.3.0 usa apply; anche SharedPreferencesSettings(commit=true) scarta il Boolean. Leaf `CheckedAuthSettingsTest.kt` usa real SDK3.6.0 e manager con editor controllato che aggiorna memoria, fallisce commit disco e costruisce un nuovo SDK dalla sola snapshot persistita. Rosso definitivo test8827677c/XMLd7bf71e6:2FAIL1PASS, tutti restart/retry raccolti prima dell'asserzione; logout [SignedOut, client disponibile, clear=true, restored A], save import riuscito/Auth SDK pubblicato con disk empty; cancellation locale normale PASS. Setup iniziale (manager Checking senza restore esplicito) e lettura restart prima initialization sono conservati separatamente, non contati come product red.
- Fix minimo: `CheckedAuthSettings` interno in `ProjectSessionPersistence.kt` delega le letture, ma String put/remove usano commit sincrono e Boolean=false solleva errore di persistenza. Application apre esattamente `${applicationContext.packageName}_preferences`, stesso file/chiavi/serializer SDK. Nessuna dipendenza o nuovo storage auth. Retry deve verificare commit e readback prima del nuovo client; nessun successo durevole dedotto dalla cache.
- Selettore legacy: test rosso su `.payload.` con issuer del progetto; ora richiede tre segmenti non vuoti per selezionare il record. Foreign/unknown/malformed restano preservati/nascosti. Nessuna verifica della firma o autorizzazione tramite issuer.

R-A07 auth freeze-v4 **APPROVED**:14 hash source identici,91/91 PASS0FAIL/ERROR/SKIP in6class,7s, manifest166af1a1 e report indipendente persistente. Il guardrail Unconfined ha prima riprodotto4 publication transitorie false su v2 (XMLf5c08d70), poi passa con scelta cleanupError prima della publication. Due nuovi rossi normal A→B (XML816493c4, test92445865 invariato red→green) dimostrano cleanup failure nata nel sign-in; il solo typed SupabaseSessionPersistenceException ora pubblica cleanupError direttamente in entrambi provider e non diventa dismissibile. Barestorage constructor fixture usa alwaysAutoRefresh=false mantenendo tutte le assert; dedicated owner actual auto-refresh control invariato. Il precedente consolidato164 aveva163PASS/1FAIL0ERROR0SKIP in quel fixture no-network con clock reale/virtuale;7XML/source/log preservati separatamente.

R-A08 supplemento e source composito finale **APPROVED**:18 hash identici,165/165 PASS, History74 conserva i73 precedenti e oracle45/45. Rosso legacy-year-zero XML9c187d22; guard0000 anticipato è l'unico delta production supplementare, reversibile al source4aecaf98 byte-esatto. Canonico1.033 PASS/7 SKIP, assembleDebug/lint PASS,5 Compose finali su fresh5554 PASS, TEST96b3 pronto; tutte le ricevute in [manifest finale](evidence/TASK-143/android-ra07-ra08-final-manifest.json). Sole2 UseKtx nuove intenzionali accettate per mantenere checked commit Boolean,0 warning Kotlin/deprecation. Nessuna operazione su5556 o account reale, nessuna integrazione/DONE dichiarata dalla lane.

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

### FIX review R-A06 e gate finale — 2026-09-29 UTC

**Rosso di review prima della correzione:**24 test/3 FAIL per Checking lasciato sospeso dopo invalidazione SDK, cambio account noto durante refresh o richiesta Google esplicita mentre il mutex è occupato; poi25 test/4 FAIL aggiungendo il caso SDK supportato user locale null→identità server valida. Log/XML `/tmp/task143-auth-restore-review-red{,2}.{log,xml}`. Gli altri21 casi restano verdi, inclusi null finale fail-closed e account noto A→B protetto.

**Correzione minima:** la cancellazione del tentativo Checking e il diniego/cambio identità osservati dopo refresh producono SignedOut anche se l'observer è accodato. Il confronto di identità blocca il cambio tra due account noti; un refresh valido può invece completare l'identità locale inizialmente assente, come supportato dal contratto SDK. Nessun clear su una sessione successiva; le guardie si applicano prima degli effetti e non soltanto prima del publish.

**Verde mirato finale:**122/122 PASS,0 FAIL/ERROR/SKIP,11s, `/tmp/task143-auth-restore-review-green.log`; Auth25, Application6, RecoveryCoordinator68, ScopeRuntimeGuard3, Binding20. XML in `/tmp/task143-auth-restore-review-targeted/`. Reviewer indipendente **APPROVED**, nessun nuovo P0/P1/P2: verificati i due hash file, patch SHA256 `7ca32ee3251716d3359d61905d218502d3da0dcfb717ee40a3431f2038677d47` e rosso→verde prima del gate canonico.

| Check finale R-A06 | Stato | Evidenza |
|---|---|---|
| Build Gradle | ESEGUITO | `./gradlew assembleDebug test lint --console=plain`, JBR/SDK locali, exit0, BUILD SUCCESSFUL1m25s; `/tmp/task143-ra06-final.log` |
| Full JVM/Robolectric | ESEGUITO |1000 totali,993 PASS,7 SKIP,0 FAIL/ERROR,69 classi; testRelease non selezionato, non dichiarato eseguito |
| Lint / warning | ESEGUITO |0 errori,53 warning preesistenti,0 sulle righe modificate; nessun nuovo warning Kotlin/deprecation, soli avvisi JVM CDS già presenti |
| Coerenza planning | ESEGUITO | addendum restore CA-06/07/10, patch di soli auth manager e test; nessuna dipendenza, UI, backend o nuova pipeline |
| Baseline TASK-004 | ESEGUITO | full JVM include repository, DatabaseViewModel ed ExcelViewModel; harness sospesi invariati |
| Compose | NON ESEGUITO nel batch, riuso autorizzato |5 PASS effettivi R-A04; Application, OptionsScreen, EditProductDialog e test Compose byte-identici a78d1fbc e main1bf758dd, hash nel manifest; non sostituisce il cold restart autenticato |
| Scan auth / diff hygiene | ESEGUITO |8 callsite log aggiunti/spostati,0 interpolazioni dirette token/session/user; controllo limitato al delta app, logging SDK invariato; `git diff --check` PASS |
| Riconferma autenticata | NON ESEGUITO da questa lane / PENDING coordinator | candidata pronta; installazione in-place e cold restart affidati all'owner5556, senza nuovo login/reset/Replace |

I7 skip sono espliciti nel [manifest R-A06](evidence/TASK-143/android-ra06-test-manifest.json): fixture Supabase locale assente, configurazione realtime live assente,3 harness Excel sospesi, workbook ShoppingHogar opzionale assente e benchmark grande opt-in. Nessuno skip mascherato come PASS. XML/lint copiati in `/tmp/task143-ra06-final-reports/`; nessun nuovo benchmark perché i percorsi misurati Room/Storefront non cambiano.

**Candidato TEST completo:** `/tmp/task143-ra06-complete-test-profile/app-debug-test-complete-profile-ra06.apk`, SHA256 `e93b81e00de2979f108fd72df92be215f07754f6b5c75cd9c054a891d7e78e70`; build separata PASS9s. [Receipt R-A06](evidence/TASK-143/android-ra06-build-receipt.json): HEAD base1bf758dd più delta R-A06 non ancora committato, medesimi hash revisionati e testati. Stesso profilo autorizzato del candidato dc11b6ee: configurazione primaria TEST più image origin e Storefront=true; WeChat=false. Confronti configurazione generata/valori embedded, applicationId/versionCode e firma debug PASS. Primaria e profilo privato invariati; local.properties ignorato del worktree ripristinato assente. APK dc11 originale e copia preservati. Nessuna installazione o lettura della firma dal device da questa lane; target runtime e cold restart restano verifica indipendente del coordinator.

### Esecuzione — 2026-10-01 — Diagnosi CI R-A06, sincronizzazione del test footer

**File modificati:**
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — il solo test footer attende analisi non nulla e stato finale Idle, mantenendo tutte le asserzioni e il timeout di 3.000 ms.

**Azioni eseguite:**
1. Esaminata la CI exact-SHA `36915031781` su `e4bdac44`: 1.000 casi, 992 PASS / 1 FAIL / 7 SKIP. XML ufficiale preservato dal parent. L'unico fallimento è `expected:<Idle> but was:<Loading(message=Analyzing data…, progress=85)>` all'assert finale del test footer, riga 1531. Risultato non nullo, due prodotti, assenza barcode 0 e nome footer sono già verificati; non è una regressione nella classificazione footer.
2. Confrontati test/VM/parser con main `1bf758dd`: invariati prima della correzione. `publishPreviewAnalysis` pubblica l'analisi prima dell'assegnazione Idle; dispatcher Main Unconfined e IO/Default reali consentono al polling di vedere il risultato intermedio. Il vecchio predicato terminava su risultato non nullo, prima dello stato che poi veniva asserito.
3. Applicata, su mandato del parent, la stessa condizione di completamento già usata dal test happy-path adiacente: `(result != null && Idle) || Error`. Nessuna asserzione rimossa o indebolita, nessun incremento timeout, retry cieco o modifica produzione. Diagnosi e hash in `evidence/android-ra06/ci-failure-diagnosis.{md,json}` del bundle aggregato esterno.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO in questa slice | Gate canonico finale coordinato dal parent dopo i delta Android ancora attivi. |
| Lint / warning | NON ESEGUITO in questa slice | Verifica finale del parent; delta limitato a espressione di attesa in un test esistente. |
| Coerenza con planning | ESEGUITO | CA-10 e baseline TASK-004: correggere il fallimento reale della CI senza indebolire il contratto del test. |
| Criteri di accettazione | ESEGUITO per la slice; finale NON ESEGUITO | Intera classe DatabaseViewModelTest 61/61 PASS, 0 FAIL/ERROR/SKIP; review indipendente, canonico completo e CI nuova ancora necessari. |

**Baseline regressione TASK-004:**
- Test eseguiti: `testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest' --max-workers=1 --console=plain`, JBR/SDK locali, exit0 e BUILD SUCCESSFUL 7s. XML ufficiale: 61/61 PASS, nessun FAIL/ERROR/SKIP; caso footer 0,093s PASS. Log/XML/patch e cinque hash sorgenti in `evidence/android-ra06/footer-determinism-class/manifest.json` del bundle aggregato. Regola Main dispatcher e tre file produzione byte-identici al main integrato.
- Test aggiunti/aggiornati: solo predicato d'attesa del caso footer; fixture, errori e asserzioni invariati.
- Limiti residui: slice JVM/Robolectric eseguita, non UI o full regression; assemble/lint/canonico completo, review e CI exact-SHA successivi restano al parent. CI fallita non riclassificata.

**Incertezze / Handoff:**
- L'XML e l'ordine di pubblicazione sostengono la race nel test; nessun problema funzionale footer osservato. Tutte le cinque asserzioni restano byte-identiche; ripristinare il solo predicato ricostruisce l'intero file di e4bdac44. Slot Gradle rilasciato dopo la slice; parent coordina review del delta, gate canonici, freeze, commit e CI sul nuovo SHA. Nessuna modifica fuori dal test singolo.

## Handoff

### Handoff finale R-A07/R-A08 — 2026-10-01

R-A07_R-A08_CODE_AND_LOCAL_GATES_VERIFIED — source18 congelati e revisionati APPROVED,165 mirati PASS; canonico1.040 totali/1.033 PASS/7 SKIP, build/lint PASS e0 warning Kotlin/deprecation. Lint54 contro baseline52:2 UseKtx intenzionali motivati dal checked commit Boolean e accettati dal parent, senza suppression. Cinque Compose Storefront effettivi PASS sul fresh AVD Codex_Mobile_Parity_Final_API_35/emulator-5554; geometria1080×2400/density420, nuova userdata sintetica, nessun accesso a5556/5580. Rimane acceso per le misure coordinate.

APK TEST separato96b3d6134ea50b61e226a14105f8de014ebefa1d8f86c66e3ee740cccac6be70, profilo e firma verificati rispetto a e93 preservato; config primaria/profilo privato/source18 immutati, local.properties worktree ripristinato. Ricevute versionate [manifest finale](evidence/TASK-143/android-ra07-ra08-final-manifest.json), [canonico](evidence/TASK-143/android-ra07-ra08-canonical-receipt.json), [Compose](evidence/TASK-143/android-ra07-ra08-compose-receipt.json), [TEST](evidence/TASK-143/android-ra07-ra08-test-build-receipt.json); raw XML/log/lint/APK disponibili nei percorsi persistenti dichiarati, nessun claim dipende da /tmp. Sourcehash identici attraverso mirato, canonico, build TEST e Compose.

Parent owner di Git/PR/CI exact-SHA, misure before/after e coordinator autenticato; nessun commit/push/device5556 da questa lane. I fake controllati e Compose non provano la convergenza live. Stato task FIX conservato, non DONE; chiusura globale solo dopo i criteri coordinati finali.

EXECUTION_AND_FIX_VALIDATED — sorgente Android congelata, review indipendente R-A01/R-A02/R-A03 risolta e re-review sorgente approvata condizionata ai gate ora PASS. Nessun commit/push/PR eseguito dagli executor. Il parent coordina Review/CA finali/report/commit/PR/CI; non dichiarare integrazione main o distribuzione. Checkout primario/toolchain preesistente preservati. Non includere `.kotlin/sessions/` nei file da integrare.

Limiti espliciti: niente app Android autenticata su staging in questa lane, niente E2E Android↔iOS per-record/render latency/background/force-stop, scanner/camera/share reali o sweep UI completo delle4lingue. Contratto SQL staging verificato separatamente dal parent; fake,JVM,Compose,benchmark core e test SQL restano evidenze distinte.

### Criteri — snapshot storico precedente alla PR10, superato dagli aggiornamenti R-A06

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

### Pubblicazione e coordinamento — snapshot storico PR10

PR [#10](https://github.com/XNIW/MerchandiseControlSplitView/pull/10) aperta; CI exact-SHA raccolta nel rapporto finale e nei check della PR. Consenso diretto dell'utente verificato nella chat «Completa attivazione WECHAT-010»: ownership nativa qui, collaudo autenticato sui dispositivi separati dell'altra lane, installazione preservando i dati. Nessun E2E PASS attribuito prima della ricevuta. Il merge è ora esplicitamente autorizzato dal mandato coordinato riportato sopra; stato effettivo e CI post-merge saranno registrati nel rapporto aggregato finale.

### Handoff R-A04

R-A04_CODE_AND_LOCAL_GATES_VERIFIED — source e test congelati dopo review indipendente e gate canonico verde; candidato TEST firmato consegnato per ripetere lo scenario live. Manifest e receipt nuovi conservano le prove precedenti. Nessun accesso a5556, nessun reset dati, nessuna modifica al checkout/config primario. Il parent gestisce aggiornamento PR/CI exact-SHA, ricevuta live, stato task e integrazione secondo il mandato coordinato verificato. Questa lane non dichiara il ritest autenticato PASS né chiusura DONE.

### Handoff R-A05

R-A05_CODE_AND_LOCAL_GATES_VERIFIED — review indipendente e gate canonico PASS;8 file app/test/fixture congelati con hash verificati, manifest e candidato TEST completo consegnati al parent. CPU/Gradle rilasciati alle altre lane; nessun altro test o benchmark richiesto da questo fix. Il candidato è destinato al solo retry del journal live preservato: nessun nuovo Replace, reset o accesso concorrente a5556. La classificazione client è corretta; il blocco della policy History sul server resta esterno e non è un PASS di convergenza. Parent owner di commit/PR/CI exact-SHA, risultato live e integrazione; nessun DONE o merge dichiarato dall'executor.

### Ritest autenticato R-A05 — parent, 2026-09-28

Il coordinator autorizzato ha installato APK `dc11b6ee…` in-place su emulator-5556; sessione Google mantenuta. Il journal riparte al launch senza nuovo Replace. Alle19:12:31Z il boundary registra `checkpoint_resource_exceeded`, RPC `shop_sync_recovery_checkpoint_v1`, `missingFields=none`: rifiuto contrattuale esplicito, non recovery riuscito. Binding e dataset/outbox preservati; journal resta1, con runId/reason/attemptCount6→7 e timestamp del nuovo tentativo aggiornati, gli altri campi invariati. Receipt sanitizzata [android-ra05-live-retest.json](evidence/TASK-143/android-ra05-live-retest.json), log tecnico privo di payload associato. La policy server sui16 History TOAST richiede decisione/remediation separata autorizzata; nessun record esistente è stato modificato.

### Handoff R-A06

R-A06_CODE_AND_LOCAL_GATES_VERIFIED — i due file app/test, manifest, receipt e sezioni executor sono congelati dopo review APPROVED,122 mirati verdi e canonico993 PASS/7 SKIP. Il parent ha verificato conteggi/hash e consegnato APK e93b81e0 al coordinator per il ritest in-place; **risultato live PENDING** al presente snapshot. Il timeout live è confermato, il successo SDK tardivo nell'episodio originario non è dimostrato e la persistenza credenziali non è stata ispezionata. Nessun nuovo test/build/device access richiesto a questa lane; slot CPU/Gradle rilasciato. Parent owner di commit, nuova PR separata, CI exact-SHA, ricevuta live e decisione d'integrazione; task non dichiarato DONE. Le prove della PR10 e i freeze intermedi restano conservati con il proprio ambito; nessun risultato precedente viene presentato come gate del nuovo delta.

### Aggiornamento integrazione e ritest — parent, 2026-09-29 UTC

Il primo batch è integrato: PR10 MERGED normalmente, head `84899b8e496130d9c96e61d98202b0526fb06f46`, merge `1bf758dd8d83a771dcfdb1844a223a036ba19eff`. CI head `36471042788` e CI main `36472844511` SUCCESS, entrambi verificati tramite XML: 978 PASS / 7 SKIP, nessun errore. Il follow-up R-A06 parte da quel main su branch separato; le tabelle pre-pubblicazione precedenti sono storiche, non lo stato corrente.

R-A06 source e gate locali sono APPROVED/ESEGUITI: 122 test mirati, full JVM 993 PASS / 7 SKIP, build/lint senza nuovi warning, review con due finding risolti e re-review verificata. Il parent ha ricalcolato gli hash dei due sorgenti e del TEST APK e ha contato direttamente i 69 XML/1.000 casi. La review è conservata in [android-ra06-independent-review.md](evidence/TASK-143/android-ra06-independent-review.md).

Ritest autenticato del nuovo APK: installazione `-r` riuscita, normale cold start e attesa oltre 120 s senza nuovo login/reset/Retry; Options resta Not signed in. Log sanitizzato conferma il timeout di 10 s, ma non un Authenticated successivo. È **NON VERIFICATO** il recupero reale/persistenza, non perdita di credenziali dimostrata. Il coordinator verifica se il filtro abbia escluso il nuovo messaggio `Restore in attesa del completamento SDK`; diagnosi read-only in corso. Questo non annulla il rosso→verde deterministico, ma impedisce di dichiarare il cold restart risolto. Task resta FIX e non DONE; PR/CI del follow-up e la decisione di merge sono del parent.

Aggiornamento dipendenza TEST: la chat coordinata ha normalizzato le 16 History con ID/hash/updated_at invariati, compression null, zero marker ed eventi 2.074 invariati. Il nuovo Retry iOS supera il vecchio rifiuto ma fallisce con HTTP 500/SQL 57014, ancora in diagnosi backend; nessun secondo Retry cieco e nessuna DDL da questa lane. Convergenza per record, immagini remote e target 3 s restano NON ESEGUIBILI al gate corrente. Le prove UI isolate Android su workbook e quattro lingue, e le 240 misure prima/dopo, sono nel report aggregato con i rispettivi limiti; non equivalgono a tale accettazione autenticata.
