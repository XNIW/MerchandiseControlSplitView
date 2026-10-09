# TASK-143 — Mobile parity root-cause android

## Stato

- File task: `docs/TASKS/TASK-143-mobile-parity-root-cause-android.md`
- Stato: `FIX`
- Fase: `FIX`
- Responsabile: `CODEX_EXECUTOR_ANDROID`; orchestratore parent, reviewer indipendente separato.
- Data: 2026-09-28
- Baseline: `d7c4953c4ed6bc2a33cc5dbfd009eb862f70feac`
- Branch corrente di questo delta diagnostico: `codex/android-checkpoint-one-trace`, dal commit verificato814b137; consegna R-A09/R-A10 separata in PR13. PR10/11 e registri PR12 preservati; nessuna nuova acceptance live dedotta.
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

### Addendum planning autorizzato — CI sul commit candidato (2026-10-01)

Il mandato richiede CI sullo SHA esatto. Il checkout predefinito della PR usa il merge temporaneo: metadata headSha non prova la revisione Git testata. Modifica minima autorizzata al solo input ref di actions/checkout: head.sha per pull_request, github.sha per push/workflow_dispatch. Versioni/pin, permessi, trigger e tutti i gate invariati; app/test/fixture invariati. Verificare indipendentemente il diff, quindi la revisione effettiva nel log e CI sul nuovo head prima del merge normale; conservare run precedenti con il loro tested SHA/tree. Nessun nuovo gate locale sull'app dedotto da questa modifica.

### Addendum planning autorizzato — R-A09 prezzi append-only di parent tombstoned, 2026-10-02 UTC

La singola apertura ordinaria dell'app Android finale su TEST registry154 ha prodotto tre fallimenti automatici: count mismatch alle16:14:59 e16:28:53 UTC, HTTP500 alle16:20:08; il quarto tentativo è stato interrotto con normale stop, non accettato. Il coordinatore conserva userdata/sessione/binding/consenso precedente e zero dati business attivi; staging del quarto tentativo parziale. L'HTTP500/SQL57014 del checkpoint è una causa backend separata. L'episodio count mismatch non è ancora attribuito a un dominio dai dati runtime disponibili.

Il codice Android contiene un candidato concreto: prices append-only di un product provatamente tombstoned fanno parte del checkpoint/ledger, ma non hanno parent fisico attivo; applyProductPriceRows li salta mentre il count check richiede tutte le righe. Anche counts e readback fisici finali richiedono allineamento. Il parent autorizza nello stesso CA-07/10 una regressione Room reale su fixture esistente deletedProductImageFixture, ripristinando il price canonico nei due campi prima esclusi, senza modificare produzione prima del RED. Controlli: parent attivo conservato, parent sconosciuto rifiutato, ledger/count/digest completi, staging/activation/reopen e tail con parent divenuto tombstoned. Nessuna prova live dedotta dal solo fixture.

Solo dopo RED, applicare il minimo cambiamento idiomatico Android per distinguere il ledger canonico completo dal sottoinsieme fisicamente materializzabile, escludendo esclusivamente il parent provato tombstoned nel medesimo manifest owner/shop/generation. Conservare tutte le righe/ID/versioni/digest remoti; mantenere fail-closed per parent sconosciuti, duplicati, relazioni invalide, payload incoerenti, scope e limiti. Vietati indebolimento del count/digest, skip generici, schema/reset/queue/consent bypass, full array e N+1 per prezzo: lookup e verifica bounded. Coerenza anche dopo checkpointB/tail, count di staging, activation e recovery/relaunch. Nessun cambio delle business key Android o import/export salvo necessità provata separatamente. Reviewer indipendente, regressioni TASK004 pertinenti, build/lint/test canonici e nuovo TEST/exact-head CI precedono ritest coordinato. Runtime/build richiedono uno slot host esplicito; sole letture e preparazione test possono procedere ora.

### Addendum planning autorizzato — R-A10 continuazione incrementale ordinaria, 2026-10-02 UTC

Dopo R-A09 RED→80GREEN/review408a92ba, il controllo sorgenti distingue un P1 ulteriore nel CA-07/09: ogni drain valido che avanza al capturedMax mantiene il checkpoint locale precedente; markerProvesNoWorkAgainstBaseline richiede watermark/digest vecchi e crea CONVERGENCE_PROOF_REQUIRED/journal REQUIRED. CatalogAutoSync passa a ERROR_RECOVERABLE e Application esegue una nuova full recovery, bloccando il percorso business. PRICES con parent provato tombstoned può analogamente essere trattato come MISSING_REMOTE. Non è causato da hasPriceCountDrift, che modifica soltanto il testo Options. Fonte bounded parent ordinary-delta-proof-followup-design.md SHA eb55edb645ffb08517948e2cd292850e8e1959e49652fcf99459b7ea311fd8d9; fatti statici, non runtime né acceptance.

Autorizzata prima soltanto preparazione di regressioni reali Room→recovery completa→repository drain/AutoSync: evento ordinario valido con target remoto/captured fence coerenti deve applicarsi e permettere il successivo trigger senza full refresh o journal; PRICES con parent tombstoned provato nello stesso scope deve mantenere ledger completo senza falsa mancanza. Preservare unknown parent/cross-scope/gap/digest/dirty/pending e limiti fail-closed. Produzione R-A09 freeze39dffac3 rimane la baseline distinta; non cancellare o indebolire i74 test originali o i6 aggiunti. Ogni runner richiede nuovo GO host e deve conservare RED ufficiale prima di productionfix.

Dopo RED e review del design, un eventuale fix deve riusare lo stesso motore/RPC/manifest/DAO e pubblicare in modo atomico dati fisici, ledger canonico, baseline verificata e watermark con fence/CAS/owner/shop/generation correnti. Non trasformare un marker remoto o un flag UI in prova locale; vietati disattivazione latch/count/digest, skip generici, nuova engine/schema/dependency e bypass auth. Considerare cascade, parent restore, immagini, History tombstone e dirty/pending; la prova forte deve confrontare conteggi/digest canonici e readback del subset fisico ammesso, con paging bounded. Una scansione O(domain) non è una promessa3s: misurare. Se il design non ha una prova sufficiente, documentare la precisa dipendenza, senza claim successo. Reviewer indipendente e gate finali/TASK004/TEST/exactCI/live sul nuovo codice dopo il fix.

**Decisione bounded R-A10 — rappresentazione History nello store attivo (root, 2026-10-02 UTC):** dopo RED/design approvato, il verifier dello store attivo può usare un pager dedicato History `deletedAt IS NULL`, preservando il verifier staging esistente. Un secondo scan bounded deve verificare tutti gli shadow History deleted, inclusi quelli orphan/missing-ref: ref/ID canonico, manifest HISTORY inactive della stessa generazione/scope e tombstone UTC canonico concordante. Nessun INNER JOIN deve nascondere shadow sconosciuti; unknown/cross-scope/active-deleted mismatch fail closed, pending tombstone locale DEFER prima del proof. Non cancellare fisicamente History e non verificarne il vecchio body con il contratto active V2: il tombstone manifest ha cinque campi/payloadDigest null e prova la tombstone/assenza attiva, non il vecchio payload conservato. Non fabbricare una prova del body dalla hash. La baseline catturata deve includere identità record/entity e generation per CAS; A e C hanno digest legittimamente diversi dopo eventi validi, e si persiste il C originale solo dopo prova completa e commit atomico. Questa decisione precisa il confine, senza autorizzare production edit prima del RED.


### Decisione planning R-A10 — rollback del test negativo139, 2026-10-02 UTC

Dopo RED reale3 (due assertion FAIL previste, controllo parent sconosciuto PASS), la nuova prova iniziale deve rifiutare anche una baseline sintetica con manifest assente e digest artificiosi. Il test `139 post recovery nonzero watermark carries checkpoint fence and retains recovery latch after a changed fence` resta un controllo negativo: marker remoto non sufficiente, manualFullSyncRequired/gap/journal di recovery obbligatori. Il precedente watermark8 dopo il rifiuto descriveva un avanzamento prima della prova forte; è intenzionalmente obsoleto. Autorizzato il solo expected watermark8→7 e assertion aggiuntive di baseline/dati invariati, preservando scope/fence e gli altri rifiuti. Non trasformare il caso in noWork o indebolire la prova per mantenere un avanzamento parziale. Ogni altra modifica ad assert originali richiede evidenza distinta; gli80 casi recovery restano invariati. Executor traccia motivazione e diff separato in Execution; reviewer verifica contratto e rollback.


### Decisione planning R-A10 — ACK locale e prova finale completa, 2026-10-02 UTC

L'analisi indipendente dopo il RED ufficiale ha identificato un caso necessario: pending→push confermato→body locale clean già nuovo→evento self dentro la finestra C. La vecchia baseline G0/A conserva ancora il body precedente; imporre la sua proiezione fisica prima del nuovo apply rifiuterebbe un ACK legittimo. Soltanto nel percorso con finestra changed completa, la prova iniziale resta il ledger canonico A integrale con relazioni/material; la pubblicazione richiede preparazione bounded completa C (anche self), checkpoint C originale/material/fence autorevoli, e transazione con CAS intera baseline/entity/generation/owner/shop/binding/device/watermark/journal e pending0. Dopo apply, verificare ledger C canonico integrale e readback fisico C di TUTTO lo store ammesso (conteggi tabelle, integrità/FK, campi/versioni, parent eligibility R-A09 e ogni shadow History). Baseline C/watermark solo dopo prova finale, rollback su qualsiasi errore/cancel/mismatch. Questo sostituisce il solo requisito physical A iniziale nel changed-path. Nel percorso noEvents la prova fisica A integrale è obbligatoria: il precedente markerProvesNoWorkAgainstBaseline da solo non la eseguiva e non basta. Non dedurre ACK o convergenza da cleanflag, marker, vuoto o conteggi uguali.

Test obbligatori aggiuntivi: pending→DEFER senza journal→push reale ACK→self C completo→READY/fresh-open/noWork; tamper clean di riga non coperta o extra/orphan/shadow sconosciuto→nessun GREEN e rollback (DEFER ammesso se davvero pending); covered body corrotto con fingerprint già C→repair esatta o FAIL+rollback, mai falso PASS; noEvents rifiuta tamper fisico; CAS/scope/device/generation/binding/watermark race/cancel impediscono commit. I mapper esistenti conservano i fast-skip: i fingerprint catalogo includono updatedAt e il normale DTO C già differisce dall'ACK push privo di timestamp. Nessun force mode/refactor senza un RED distinto che ne provi la necessità. History V1 mantiene la compatibilità esistente; tombstone shadow prova stamp/assenza attiva, immagini qui metadata/pointer e non pixel della cache. Source/finaltest review e gate restano da eseguire.


### Decisione planning R-A10 — fixture positiva noEvents139, 2026-10-02 UTC

Il caso `139 self verifying recovery baseline reaches marker noWork without relatching` costruisce manifest vuoto con digest a/b/c/d artificiosi. Il comportamento positivo resta valido solo per una baseline vuota canonica. Autorizzata la sola correzione della fixture con i reali hash canonici empty/domain/catalog/identity e scope/fence coerenti tramite i factory/helper esistenti, mantenendo tutti gli assert noWork, watermark7, scope, assenza gap/journal e nessun recovery. Non aggirare la nuova prova fisica A noEvents per mantenere valori artificiali. Separare questo diff dalla modifica intenzionale wm8→7 del test negativo139 e dagli83+ guard nuovi; motivare entrambi in Execution. Nessuna modifica degli80 recovery precedenti.

### Decisione planning R-A10 — receipt attivata vuota e watermark zero, 2026-10-02 UTC

La review statica indipendente `d800bde75003d17ce40915ebc9269c4dfb864fcbf55ec9980d3a1ad6505a6134` ha individuato un candidato P1 non ancora riprodotto: `shouldRunCatalogBootstrap` valuta soltanto il numero di prodotti, mentre `shopSyncBaselineForEventDrain` scarta watermark0 anche quando la recovery reale ha attivato una baseline canonica vuota. Catalogo vuoto e cursor0 non sono di per sé una richiesta di bootstrap né una prova di recovery. Il contratto ordinario deve ammettere una receipt corrente realmente attivata, con scope/device/binding/generation/manifest/fence coerenti e nessun journal/pending, indipendentemente dal numero di prodotti; baseline assente, malformata o non corrente resta nel percorso di bootstrap/rifiuto esistente.

Prima di qualsiasi correzione specifica di produzione, preparare test desiderati sul freeze produttivo `b55b6015400c86b9b7a4f6789702fba7693ed001e464cdf50424cdaa03df2317`: recovery Room effettiva del checkpoint canonico empty0, riapertura e primo evento1 attraverso repository e vero AutoSync. Verificare pubblicazione atomica C, READY, assenza del ramo `bootstrap_required`, binding/device invariati, nessuna full-page recovery aggiuntiva e successivo noEvents; coprire anche noEvents immediatamente dopo empty0. Il caso senza baseline attivata conserva watermark0 e richiede bootstrap. Il RED a due selettori rimane distinto dalle regressioni adiacenti; solo una failure funzionale ufficiale consente la proposta minima di fix, che richiederà nuovo freeze/review e gate. Gli101 casi già congelati, gli83 originali/RED e le due decisioni Default139 restano preservati.

Completare nello stesso batch i guardrail già richiesti: corruzione di body coperto con fingerprint apparentemente C deve fallire e rollback senza force mapper; History pending→DEFER→vero push tombstone ACK→evento self C deve convergere, preservare lo shadow e reggere riapertura/noEvents. Test sintetici JVM/Robolectric e transport controllato, senza rete o credenziali reali; non provano acceptance autenticata.

### Decisione planning R-A10 — correzione del simbolo di compilazione, 2026-10-02 UTC

Il primo ONE ZERO107, source157/test107 congelati e invariati, termina il20:09:49 UTC prima di JUnit: compileDebugKotlin rifiuta `encodeRecoveryCheckpointJson` usato nel nuovo repository alla riga5042 perché la funzione non è definita. Non è un problema di visibilità né un RED funzionale di ZERO; officialXML assente, gruppo posseduto15061 assente e rilascio verificato20:09:49.105285. Ricevute/result/log e `not-red-adjudication.json`5f83229b conservati nel namespace zero-red-targeted; nessun retry sotto lo stesso GO.

Autorizzato soltanto un helper encoder interno accanto al decoder condiviso di ShopSyncRecoveryCoordinator, delegando all'esatto `RECOVERY_JSON.encodeToString(value)` già usato dalla vera activation per il checkpoint. Codec/flag, C originale, wire/storage/schema, mapper e comportamento funzionale ZERO restano invariati; nessuna dipendenza o API esterna nuova. Questo ripara il simbolo della patch R-A10 già successiva al RED ufficiale originale, senza autorizzare il fix del candidato ZERO non ancora riprodotto. Test107 byte-identici, nuovo freeze/request/launcher in namespace distinto, review statica del solo delta e nuovo GO precedono il prossimo ONE RED a due selettori; gate successivi restano aperti.

### Decisione planning R-A10 — fixture ZERO senza evento bloccante incompatibile, 2026-10-02 UTC

Il secondo ONE a due selettori compila e produce due FAIL ufficiali, ma entrambe si fermano nell'assert Activated del helper di setup, prima di reopen/delta/drain. Il seed storico del mismatch inserisce blockingEventId40, mentre il checkpoint canonico ZERO ha maxId0; il guard reale recovery rifiuta correttamente0<40 con recovery_checkpoint_before_blocking_event. Result571bbdad/XMLc05c81b9 e rilascio20:33:21.634676 del gruppo22456 assente restano immutabili. Non è RED funzionale del candidato ZERO, nessuna correzione funzionale di produzione autorizzata.

Autorizzata la sola correzione del nuovo helper recoverAndReopenZeroOrdinaryFixture: prima del recovery sintetico, leggere e verificare il journal mismatch-confirmed creato dal seed e rimuovere soltanto il suo blockingEventId artificiale mediante copy(blockingEventId=null). Il mondo empty0 rappresenta mismatch/consenso senza precedente evento bloccante: preservare owner/store/shop/device/authorizationMode/phase/reason e ogni altro campo. Il seed generale/default40, tutti107 corpi e assert dei test e tutti157 file produttivi de909 restano invariati. Non toccare il guard reale del coordinator né cancellare journal/dati reali. Nuovo namespace attempt03, freeze/request/launcher e review statica/test-invarianza precedono nuovo GO ONE sugli stessi due selettori. Solo la failure nel successivo percorso ordinario dopo activation/reopen effettivi è il RED desiderato; gli altri quattro guard restano per la futura suite completa.

### Decisione planning R-A10 — fix del RED funzionale ActivatedC0, 2026-10-02 UTC

Il ONE attempt03 reale compila e termina2FAIL/0ERROR/0SKIP dopo recovery ActivatedC0, riapertura Room, typed watermark0 realmente presente, binding/READY/assenza journal e verifica canonica/fisica A completa. La prima asserzione3526 fallisce nel drain ordinario evento1: convergence_proof_required/manualFullSync; la seconda3567 nel vero AutoSync: skip bootstrap_required. Result24ee5d5b/XMLb6dd29ee, release20:58:58.070751 e PG35439 assente/signals[]; adjudication indipendente257b9b38 conferma il RED desiderato su157production de909/test107a183 invariati. Gli105 non selezionati e la successiva fisica C1 restano NON ESEGUITI, non dedotti dalla summary. Attempt01/02 compile/fixturefailure restano distinti.

Autorizzata la correzione minima in InventoryRepository, senza API pubblica/schema/mapper/deps nuove: shopSyncBaselineForEventDrain può ammettere0 soltanto con baseline canonica realmente attivata e riga watermark scoped realmente presente/coerente, non il default0 di una riga assente. Conservare canonical/fullphysical A/C, identity/device/binding/generation/journal/pending e CAS esistenti. Per la pubblicazione ordinaria e noEvents, catturare e ricontrollare anche l'entità watermark con presenza/identità esatte: eliminazione della riga0 durante la rete non equivale a invariata scalar0 e non può autorizzare C/noWork. Un receipt attivato con watermark mancante va nel percorso di recovery/rifiuto, senza falsa READY o falsa receipt C. Preservare i percorsi legacy senza receipt attivata e i loro fallimenti dichiarati; non usare normalizzazione di un default come prova.

shouldRunCatalogBootstrap conserva count>0=false e bootstrap per count0 senza prova. L'esenzione count0 deve usare la lease corrente managed di Task126, già disponibile tramite withCurrentBusinessDataScopeFlight e relativo coroutineContext, verificando owner dell'argomento e store/shop/localStore/generation correnti. La baseline non sceglie lo shop a nome del caller. Con binding corrente validato dal gate, device esistente senza getOrCreate, baseline/checkpoint/watermark reali, nessun journal/pending e fullcanonical/fullphysical receipt, eseguire una transazione locale read-only e riletture finali di entità e lease/cancel. Nessuna RPC, scrittura di dato/queue/flag o full recovery nella predicate. Default unmanaged privo di boundScope rimane bootstrap; non fabbricare una lease o assumere il selectedShop dal receipt. Errori di prova consentono bootstrap/rifiuto conservativo, cancellation/scope-changed si propagano senza conversione in successo.

Nello stesso batch aggiungere quattro regressioni significative ai107corpi/assert preservati: (1) genuineActivated0 richiede scope managed corrente per l'esenzione, con unmanaged/owner-shop errato conservativi; (2) typed watermark assente o baseline malformata non autorizzano bootstrap exemption; (3) perdita/modifica watermark0 durante remote reads del changed path non pubblica C e rollback; (4) stesso caso noEvents non pubblica noWork valido. Nuovi helper/hook opzionali mantengono i default dei107 precedenti. Source111Recovery dichiarati e nuova card pertinente442 (111+218Default+61DatabaseVM+52ExcelVM), da verificare sul freeze reale; nessun conteggio è un PASS. Tutti i guard covered fingerprintC, History push-tombstone ACK/self, empty0 noEvents e vecchi rossi restano attivi. Nuovo freeze/review del delta prima dei gate pertinenti/full, build/lint/warning/TEST/CI/integrazione/live; nessun runner sotto il vecchio GO. Task resta FIX.

### Decisione planning R-A10 — fixture mancanti dopo primo442 reale, 2026-10-02 UTC

Il ONE pertinente reale21:41:50.100120→21:42:44.083152/release21:42:44.148504 compila il freeze78bb e produce442unici:436PASS/5FAIL/0ERROR/1SKIP storico condizionale. I due RED ActivatedC0 ora sono PASS, così come empty0/noEvents, default0 negativo e i quattro nuovi guard scope/rowloss. Recovery111=107PASS/4FAIL; Default218=216PASS/1FAIL/1SKIP; Database61 ed Excel52PASS. Result9a867e55/logf6ef4721 conservati, PG54838 assente/signals[]/nessun timeout e157+4test+7build/HEAD invariati. Nessun442GREEN, ritest autentico o attribution live dedotto.

La lettura indipendente producer/reviewer identifica quattro setup incompleti delle nuove regressioni Recovery: Kotlin delegation a NoOp inoltra patchProduct shop-aware4arg alla fixture NoOp, mentre l'override locale copre solo3arg; due casi noEvents non attivano l'opzione esplicita emptyTailConfigured e sollevano fixture_event_page_not_configured; il vero push History è invocato fuori dalla lease managed che il coordinatore produzione normalmente installa. Non cambiare repository/gate/API reale per adattarlo a questi errori fixture.

Autorizzate solo correzioni locali di setup in quei quattro nuovi metodi/helper: override4arg nella fake ACK prodotto, assert sullo shop esatto e delega al proprio override3arg già esistente; emptyTailConfigured=true solo nei due casi noEvents nominati, mai un default globale che nasconda reader non configurati; pushHistory dentro tracker.withBusinessDataScopeFlight corrente managed, senza unmanaged fallback o lease artificiale. Se il helper è annidato nel corpo nuovo, il suo delta di setup è un'eccezione esplicita alla precedente invarianza letterale dei107corpi; conservare tutti gli111ID e ogni assert/valore atteso/spy/rollback, gli altri107corpi invariati e tutti gli83originali/RED non coinvolti. Inversa esatta di questi delta deve ricostruire il freeze78bb; nessuna rimozione/skip/timeout né weakening.

Il quinto caso Default139 nonzero-watermark/fence ha un'A fabbricata: accountKey/deviceKey placeholder, digest canonici count0 placeholder e binding assente. Il nuovo loader identity/fullcanonical rifiuta prima dell'event reader, quindi eventContexts.single non raggiunge lo scenario dichiarato. Autorizzata soltanto A realmente canonica empty7 usando hash owner/device e binding corretti, digest individuali/composito ottenuti dagli helper reali esistenti, stesso owner/shop/device/generation/watermark7 ed evento catalogo8. Preservare tutti gli assert e il controllo negativo C: il reader corrente dichiara catalogdomainMax7 ma la pagina evento8 attesta asOfDomain8, un captured-fence inconsistente che deve essere rifiutato e conservare A/wm7/dati/journal con convergence_proof_required. Il commento deve descrivere questo mismatch esplicito; un avanzamento ordinario valido non è intrinsecamente non verificabile dopo R-A10. Non rendere incoerente A per produrre un rifiuto precoce, non trasformare il caso in positivo, non estendere i default fake ad altri casi.

Solo test/fixture/commento e Execution nello stesso task; tutti157file produttivi freeze78bb, quattro nuovi guard ZERO e due desiredZERO restano byte-identici. Nuovo freeze/patch/inversa/111ID/assert e card442/launcher nel namespace distinto, review indipendente statica prima di nuovo GO owner DIRECT ONE. Se emergono vere regressioni di produzione o le correzioni non conservano la semantica contrattuale, fermare le edit e riportare il finding prima di un fix differente. Gate canonici/full/TEST/CI/live rimangono aperti. Task FIX.

### Decisione planning — singola traccia checkpoint DEBUG, 2026-10-02 UTC

Il caller originale richiesto dal coordinatore backend usa la sessione SDK già autenticata e verifica prima/dopo account/shop/device/binding/generation/lease. Non richiede un marker privato Auth HTTP-in-flight non esposto: quel requisito aggiuntivo del draft viene ritirato. Il timeout HTTP500/SQL57014 resta un episodio da diagnosticare, senza attribuzione a rete, auth o lock prima della prova. La proposta concreta esterna five-file94f10ffd e review f2d7425c sono preparazione, non implementazione o readiness live. Current7fa/TESTbd0 e PR13 restano separati e immutati nel checkout di consegna.

Il parent autorizza nello stesso CA-07/10 un delta separato su branch `codex/android-checkpoint-one-trace`, dal commit814b137 nel worktree isolato dedicato. MASTER/backlog/task restano TASK143 FIX; nessun task nuovo o nuovo motore sync. File produttivi: MainActivity, MerchandiseControlApplication, SupabaseShopSyncReadRemoteDataSource, Task126BusinessDataScopeRuntimeGuard, CatalogSyncStateTracker e il solo accessor interno read-only `diagnosticShopEpoch` in ShopContext, usando il refreshGeneration già sincronizzato. Il sesto file è autorizzato per rilevare A→B→A raw anche quando StateFlow conflates; nessuna nuova business API/lease/readiness o mutazione shop. Test nelle classi esistenti di reader, Application, Task126guard e ShopContext; una regressione Activity/Robolectric pertinente può essere aggiunta usando le dipendenze già presenti.

Adottare la proposta concreta dopo aver letto sorgenti e test. Intenzione fissa DEBUG/warm-only via una sola extra Boolean, traccia/header costanti già concordati, niente valore scope/URL/header dall'intent e un solo consumo per processo. Cold start non sospende o cambia startup ordinario e produce0RPC. La review ha identificato P1 nel candidato: Android pausa la Activity prima di onNewIntent, quindi il controllo RESUMED dentro quel callback rifiuterebbe l'intento warm. Prima riprodurre il candidato con un vero lifecycle pause→onNewIntent→resume e output scalare/0RPC, poi riparare con consegna una sola volta al successivo RESUMED della stessa Activity esistente (callback lifecycle cancellabile se distrutta, nessuna cold activation o doppio consumo). Verificare anche background/destroy/repeated intent e preservare share/smoke ordinari. Nessun test o assert indebolito per passare.

Riservare solo il mutex recovery esistente quando idle, con quiet stamp reale Task126 senza active flight/transition; rifiutare job recovery/esecuzioni scope concorrenti. La prenotazione deve essere rilasciata dal proprio token anche quando il lazy job è cancellato prima del body; un trigger durante la prenotazione non accoda full recovery e il rilascio non ne avvia una. Contesto consentito solo ERROR_RECOVERABLE/sync_recovery_required e journal REQUIRED canonico SAME_SCOPE o MISMATCH_REPLACE_CONFIRMED, con device/binding/baseline/journal/nullable typed-watermark letto atomicamente e controllato prima/dopo. Non creare device/registration/heartbeat, non scrivere journal/counter/outbox/dati/flag, non fabbricare READY/UNMANAGED. Pin esatto SDK posseduto/status Authenticated/app SignedIn/gen e raw-shop epoch/state/epoch; scope cambiato/cancel/entità variate rifiutano o restano unknown. Non reflection SDK, refresh forzato, stop dell'auth, token export o nuovo client.

Pin TEST interno fail-closed: SHA256 UTF8 esatta della SUPABASE_URL decodificata dal builder autorizzato, senza normalizzazione/newline, `42a5d0119a30cb5f291bff1912a46e1092c77b483bbfed663785ef165260c842`. Provenienza owner: sola whitelist SUPABASE_URL del profilo autorizzato, HTTPS/targetTEST qualificati, receipt940127fe; nessuna URL/key/profilo stampata. Getter SDK auto-refresh esistente e threshold reale80% della lifetime, expiry/status/client correnti devono superare safeEnd fisso; quiet window dell'owner esclude auth/shop manuali. Questo è controllo osservabile e qualificato, non prova assoluta di un flag privato HTTP assente.

Solo una checkpoint RPC esistente, params/validator originali, baseline0/scope-key null secondo contratto, header x-eco-recovery-trace fisso eco154-0210-c4b56c63943e4d39be18e4a92732fdb1. Preflight massimo2s e deadline totale prenotazione→readback8s; no retry automatico, convergence/page/full recovery/nuovi endpoint o nuovi engine/deps. Body streaming one-shot del vero SDK/OkHttp limita a una trasmissione completa dopo send, non promette un solo tentativo TCP pre-send. Test reali loopback con client/session/storage sintetici già supportati: perdita risposta dopo body completo,408,503 Retry-After0, normal controls che mostrino davvero replay; body/Content-Type/charset/length esattamente equivalenti, header solo sulla checkpoint diagnostica, seconda invocazione/altre RPC rifiutate, error body non esportato. Se il motore normalcontrol non dimostra replay, conservare NOT_PROVEN invece di inventare riduzione; investigare prima readiness. Se l'equivalenza Content-Type fallisce, è autorizzato il solo adeguamento al charset realmente osservato, senza rilassare l'assert.

Output soltanto trace sintetica, targetTEST bool/null, count, elapsed monotonic, HTTP int/null, SQLSTATE null, pre/post bool/null, cancel bool ed esito enum fisso. Nessun body/header/session/ID/digestbusiness/Throwable o stack raw. HTTP500 non prova SQL57014; successo checkpoint non è recovery/READY/convergenza. Timeout/cancel/postreadback incompleto non diventano PASS. Cleanup socket/thread/client propri bounded e failure preservata.

Executor prima prepara source/test e congela; nessun compiler/socket/client/device/RPC/DDL/Git/protected read senza lo slot diretto primary. Review indipendente minima, suite engine/lifecycle/guard pertinenti actual RED→GREEN e regressioni TASK004, build/lint/full/warning, nuovo TEST artifact/profile/firma/install in-place precedono readiness. Source7fa della consegna precedente non viene modificato né accettato come prova di questo hook. RPC live soltanto con nuovo scope/session/quiet preflight, owner backend nella finestra SQL già autorizzata e rollback esatto della sua temporanea diagnostica; nessuna DDL o credenziale SQL da questa lane. Task resta FIX finché i criteri obbligatori non hanno esito verificato.

## Execution

### Esecuzione — 2026-10-08 UTC — risultato vuoto filtrato separato dai FAB, gate finali actual

**File modificati:** `DatabaseScreenComponents.kt` — solo presentazione EMPTY filtrata; `LocalAvailabilityRootDeviceTest.kt` — metodo8 additivo `actualDatabaseFilteredEmptyMessageDoesNotOverlapFloatingActionsInFourLocales`; questo Task — sole Execution/Handoff. I sette metodi precedenti e gli helper sono preservati integralmente. Il metodo8, incluse tutte le assertion di completezza/altezza/glifi, geometria, azioni/header e query, resta byte-identico dal RED al GREEN (`1709f907…` intero file test).

**UI/UX — intento CA-06/CA-10:** evitare che Camera/Add coprano il risultato della ricerca senza ridurre font, nascondere icone o cambiare stringhe/FAB. Nel solo EMPTY con filtro non vuoto, una Row affianca l'icona esistente56dp, il gap8dp e il Text con peso1, wrapping completo, titleMedium e allineamento Center. La Box riserva il bottom152dp già usato dalla lista. EMPTY non filtrato conserva la Column precedente e padding inferiore0; nessun cambiamento a business, repository, navigation, auth, permessi, focus o stato della ricerca. Produzione finale `231fbd873b12b4b35d701af19755e20af346fc6613ff6d690a01d02710763bb8`.

**Sequenza di evidenza, conservata senza riclassificazioni:**
1. RED reale, receipt `android-empty-message-fab-red-device-01/receipt.json` SHA `4503ca1b089141c1796b708273f73af3592544afe279c14b936c705595dd4086`: un caso ufficiale FAIL,8 configurazioni complete e6 collisioni. IT/ES160 collidono con Camera+Add, ZH/EN160 con Camera; i raster IT/ES mostrano glifi coperti, per ZH/EN si afferma soltanto la collisione dei bounds del Text.
2. Primo probe con solo padding152: receipt `android-empty-message-fab-layout-probe-device-01/receipt.json` SHA `ce378ff535caeb26408ab14a9c4c627c23f253eef6e41182a0ad7721dfbdde8a`, NOT_PASS. Collisioni eliminate ma testo IT160 alto163px/ES160 alto135px contro190px necessari: proposta respinta, assertion intatte.
3. Row probe, receipt `android-empty-message-fab-row-probe-device-01/receipt.json` SHA `b00e7081d5a2ce2814d5cc0a6ff3a61efb0f87133600be14544e51a82edba579`: caso geometrico PASS su8 configurazioni; solo7/8 PNG qualificabili perché EN160 era0byte. Quel gap storico è preservato, non è un PASS visuale8/8. Il successivo finale contiene EN160 valido.

**Check obbligatori — risultati del runner root sul freeze538 `d51ffff0…`:**
| Check | Stato | Evidenza |
|---|---|---|
| Build Gradle | ESEGUITO | Canonico04 unico23:36:01–23:39:00UTC, exit0/BUILD_SUCCESSFUL: assembleDebug e assembleDebugAndroidTest; PG96325 assente, nessun timeout/segnale. |
| Lint | ESEGUITO | 44 warning/0error, stesse firme senza aggiunte/rimozioni; report byte-identico al gate03, non alla baseline storica che differiva nelle sole posizioni già documentate. |
| Warning nuovi | ESEGUITO | Nessun diagnostic Kotlin/deprecation nuovo; messaggi JVM CDS separati, non warning applicativi. |
| Coerenza con planning | ESEGUITO | Correzione minima del difetto UX riprodotto, CA-06/CA-10; Master/Planning, font, risorse e comportamento non filtrato preservati. |
| Criteri di accettazione | ESEGUITO per questa slice; globale aperta | Classe Compose8 e24 PNG qualificati nel perimetro sotto; nessuna promozione a DONE o accettazione autenticata/globale. |

**Baseline regressione TASK-004:** `testDebugUnitTest` completo,76 XML ufficiali freschi,1250 casi unici =1243PASS/7SKIP/0FAIL/0ERROR; stessi ID/ragioni dei7skip e baseline1219 preservata. Include le classi JVM/Robolectric repository, DatabaseViewModel ed ExcelViewModel; non sono test UI. `assembleDebug/lint/testDebugUnitTest/assembleDebugAndroidTest` nello stesso comando canonico; Release non richiesta e non dichiarata eseguita. Receipt `resume-20261008-android-v6-final-gates-actual-04/receipt.json` SHA `b8a23873ec0e0a343a1a6fc21c436b175ee0e986e76a9d68ee107344ea8466e6`; `local-qualified.json` SHA `39014a2dbc5849575457492d7f9fd56c1595deb58bd8de93a9026a3892233779`. APK KEYLESS del gate non installabile come ProperTEST autenticato.

**Compose / visuale actual finale:** tutta la classe LocalAvailabilityRootDeviceTest,8 metodi ufficiali PASS, incluso lo stesso metodo8 del RED; receipt `android-empty-message-fab-final-device-01/receipt.json` SHA `595068ee9ad1e4653ca498229b916baf4f8cb59231bcf419165f888738e0330d`. Finestra23:40:02–23:42:52UTC entro600s con riserva90s; tutti gruppi owned assenti, AVD sintetico5584 eliminato (SIGTERM del solo emulator proprio registrato), sorgente/config/pair preservate. Nessuna operazione sulla primaria5554.

24 PNG completi CRC/raster, manifest SHA `a261e2acd95367b3cf2e848dacf16ab96379cc47910dfca392e417f1a1498c62`. Root ha letto gli8 originali EMPTY (quattro lingue × font1/1.6): messaggio/query/glifi completi, nessuna collisione con entrambi i FAB, header/banner/query preservati; `root-empty-message-fab-visual-readback.json` SHA `08613cda0b0ea0e49a8e6279b8619251da9f22c92576328843caf00ab6ec032d`. Readback indipendente dei16 originali banner/tab, `banner-tabs-visual-readback.json` SHA `d79ef6130562aa927b9bd1a91d865985b787da50cfd3e281aeb5eca475831170`: tutte le lettere complete e controlli liberi. Alcune etichette IT/ES/EN160 vanno a capo dentro le parole, osservazione già nota senza nuova attribuzione di regressione. Le due review indipendenti C della stessa batch sono APPROVED, comunicate dal root; nessuna nuova modifica source dopo questi gate.

Le ricevute citate sono conservate sotto `/Users/minxiang/Projects/MerchandiseControl-Ecosistema/evidence/native-local-availability-20261004/native-residuals-continuation-20261007-01/`. Il presente append è solo documentale: altri537 leaf esatti al freeze d51, nessun nuovo run. Prove sintetiche locali distinte da primaria, auth/READY, E2E per-record, performance, iOS e QA globale, che restano aperte. Task FIX.

### Esecuzione — 2026-10-08 UTC — gate finale tab Database / reporter, risultati actual

Questa voce aggiorna le precedenti preparazioni: codice congelato nel source538 `bff1bd95`, Task pre-gate `950d8937`. Dopo i gate il solo append documentale corrente modifica il Task; gli altri537 file pubblici restano byte-identici al gate03. Nessun rerun richiesto o eseguito per questa sola documentazione; Master/Planning e fonte app/test preservati.

**Check obbligatori — risultati del runner owner:**
| Check | Stato | Evidenza |
|---|---|---|
| Build Gradle | ESEGUITO | Gate03 unico,22:36:21–22:40:24UTC, exit0/BUILD_SUCCESSFUL; assembleDebug e compilazione/package androidTest, PG82084 assente al rilascio, nessun segnale/timeout. |
| Lint | ESEGUITO | 44 warning/0error, stesse firme senza aggiunte/rimozioni. XML non byte-identico: sole posizioni DatabaseScreenComponents400→399 e515→514. |
| Warning nuovi | ESEGUITO | Nessun diagnostic compilatore nuovo; quattro deprecation Thread.id del gate02 eliminate col helper locale API36/31–35. |
| Coerenza con planning | ESEGUITO | Wrapping minimo delle etichette, corretto spazio di coordinate del test e compatibilità reporter; nessuna modifica business, navigation o permessi. |
| Criteri di accettazione | ESEGUITO per questa slice; globale aperta | GREEN7 e QA circoscritta tab/banner/header nelle8 configurazioni; task FIX, non DONE e nessuna accettazione autenticata globale dedotta. |

**Baseline TASK-004:**76 XML ufficiali freschi,1250 casi unici =1243PASS/7SKIP,0FAIL/ERROR, nessun duplicato, stessi ID e ragioni dei sette skip. Le classi JVM/Robolectric pertinenti a repository/ViewModel/Excel/sync sono comprese; le sei nuove guard/privacy regressioni reporter passano. Sono prove locali, non UI autenticata né cattura diagnostica sul processo primario. Receipt raw `resume-20261008-android-v6-final-gates-actual-03/receipt.json` SHA `3e2859490c4fa33138cf51ba97f738a9f203c36ef4410294a98872f614bddf5e`; qualificazione `local-qualified.json` SHA `171e0276685dd6368d3df032d858e3df78ddb9790b6d39db0ed84d50db13fda0`. Gate02 e relativi warning restano preservati come prova precedente, non riclassificati.

**Compose / visuale actual:** tutta LocalAvailabilityRootDeviceTest,7 metodi ufficiali PASS; receipt `android-database-secondary-tabs-green-device-01/receipt.json` SHA `35e09581bbae54ffa6ce3eb3f540668b0f91d927248b1eab44ae8062da153eb7`, terminale22:44:50UTC, tutti gruppi propri assenti e AVD eliminato.16 PNG completi CRC/raster, letti dal root a risoluzione originale; adjudication `visual-adjudication-original-pngs.json` SHA `6219055ea231539c1fef6c66505a7cc8535f98d9bb83dbeb0e0b9c1e0aa7a2d4`. Tutte24 etichette complete, banner/titolo/import/export liberi, azioni dei tab e query preservate nelle quattro lingue × font1/1.6. Il wrapping può spezzare parole a360dp/160%, senza ridurre font o nascondere caratteri. L'apparente prima A del banner ES160 è un errore di lettura risolto: primi350 pixel del raster identici fra i due metodi e A completa, senza nuova immagine o rerun. Sei metodi/helper originali intatti; RED02 delle10 etichette realmente incomplete conservato.

**Limiti:** cattura nativa del reporter NON ESEGUITA, holder/causa della ricerca ancora UNKNOWN. KEYLESS dei gate non è il ProperTEST installabile né prova auth/READY/convergenza/iOS. Il PASS visuale riguarda soltanto tab/banner/header verificati; i residui estranei sono nel Handoff. Root owner di review finale, Git/CI e prossima operazione nativa; nessuna azione di runtime o codice aggiunta da questo append.


### Esecuzione — 2026-10-08 UTC — tab Database completi a font grande e compatibilità reporter

**File modificati:** `DatabaseScreenComponents.kt` (solo rimozione di `maxLines = 1` dalle etichette dei tab secondari); `LocalAvailabilityRootDeviceTest.kt` (solo nuovo metodo7 e relativo oracolo); `MerchandiseControlApplication.kt` (solo accessor privato ID thread compatibile); questo task, sole Execution/Fix. Sei test Compose preesistenti/helper, predicate Excel9e3259, MainActivity/guard reporter, Master e Planning preservati.

**UI/UX — motivo CA-06/CA-10:** la verifica nativa del coordinatore sull'APK corrente mostra etichette Products/Suppliers/Categories e traduzioni tagliate a font1.6. Il test usa AppNavGraphContent reale con receipt Room attivata, viewport360dp, quattro lingue × font1/1.6, azioni tab/header e query preservate; non replica una Row fittizia. La Tab Material3 fornisce già TextAlign.Center e altezza adattiva: rimuovere il limite a una riga permette il wrapping naturale, senza font ridotti, nuovo componente, fillMaxWidth, cambi di stato/navigation/business.

**RED qualificato e precisione dell'oracolo:** RED01 ufficiale conserva24 flag hasVisualOverflow e8PNG, ma non prova24 tagli dipinti. RED02, receipt `android-database-secondary-tabs-precise-red-device-02/receipt.json` SHA `e2637f0df5ca8d3c9084c929a78713b561c50a551392b07f119dad0c57e866a8`, termina22:25:44UTC con1FAIL,8PNG e cleanup/source-preservation PASS:10 etichette incomplete per fine visibile/altezza (IT/ES/EN tre ciascuna a1.6, ES Proveedores a1.0/360dp). Le altre14 erano complete. Foundation1.11.2 ricostruisce il MultiParagraph semantico alla larghezza massima anche se il nodo Text è più stretto; i limiti centrati sono quindi traslati rispetto al nodo. Il solo metodo7 verifica ora TextAlign.Center e sottrae `(multiParagraph.width - size.width) / 2` da x di linee/glifi prima del confronto/proiezione; y invariato. Fine visibile completa, assenza ellissi e overflow verticale restano obbligatori; quei10 RED hanno offset0 e non vengono assorbiti. Width flag resta diagnostico. Tolleranza1px soltanto per Float→IntSize, nessuna aggiunta per i bounds reali del Tab/header. Asserzioni azioni/query e sei test/helper originali intatti. Nessun terzo RED invariato eseguito.

**Warning e gate precedente:** il canonico owner `resume-20261008-android-v6-final-gates-actual-02/receipt.json` SHA `45651ff1b4a16417cb2a6a31868f4e0b56501b052fa60ee8d171d48abaf75025` ha Gradle BUILD_SUCCESSFUL e1250 casi (1243PASS/7sameSKIP,76XML), lint44 byte-identico/0nuovi, ma resta NOT_PASS per quattro deprecation di Thread.id. Un helper privato usa `thread.threadId()` da API36 e l'accessor legacy su Android31–35, con sola Suppress(DEPRECATION) locale motivata; i quattro call-site lo riusano. Nessun cambiamento a formato, limiti, privacy o guard della cattura.

**Check obbligatori sul nuovo delta:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO | Nuovo gate finale e compilazione androidTest affidati al root owner. |
| Lint / warning nuovi | NON ESEGUITO | Verificare il helper API36 e assenza delle quattro deprecation nel nuovo gate; nessun PASS anticipato. |
| Coerenza con planning | ESEGUITO | Difetto UX concreto e warning del diagnostico già autorizzato, CA-06/CA-10; nessun nuovo scope business. |
| Criteri di accettazione | NON ESEGUITO integralmente | Nuovo GREEN7 e QA originale delle configurazioni pendenti; FIX, non DONE. |

**Baseline TASK-004 / limiti:** il canonico1250 precedente include la baseline JVM/Robolectric; il root eseguirà quella finale sulle fonti congelate. Il test Compose non sostituisce TASK-004 né accettazione autenticata, ricerca/latency, READY, convergenza o controparte iOS. KEYLESS del gate non installabile sul primario; nessuna esecuzione/build/device/Git/config da questo executor. Fonte ferma dopo il freeze per gate e review del coordinatore.


### Esecuzione — 2026-10-08 UTC — snapshot tecnico locale DEBUG per ricerca pendente

**File modificati:** `MainActivity.kt`, `MerchandiseControlApplication.kt`, soli test additivi in `CheckpointTraceActivityLifecycleTest.kt` e `MerchandiseControlApplicationTest.kt`; questo task, sole Execution/Handoff. La precedente predicate Excel è invariata.

**Motivo e scope:** sul nuovo APK la query locale resta con refresh Paging attivo e righe precedenti. Il successivo remount con query riuscita non chiude la latenza né identifica il holder; debugcap non disponibile e JDWP non qualificato non consentono una diagnosi già provata. Mandato diagnostico minimo del coordinatore: sola osservazione tecnica in memoria, nessuna correzione dei dati o della sincronizzazione dedotta da questo sintomo.

**Azioni eseguite:** ramo dedicato `task143_local_thread_snapshot` prima di readiness/trace, solo DEBUG e digest TEST esatto già esistente. Cold/nonforeground/nonTEST e intent misti sono rifiutati; gli extra diagnostici/smoke misti vengono disarmati e i relativi handler non sono chiamati. Gli intent ordinari senza il nuovo extra conservano il comportamento precedente. Nessun nuovo lifecycle observer. Reporter su Dispatchers.Default, una sola acquisizione `Thread.getAllStackTraces()` per processo, protetta da stato atomico esclusivamente diagnostico; rifiuti antecedenti al claim non consumano il tentativo. Nessun auth getter, query Room, RPC, mutex business, recovery, reset, flag READY o nuovo motore.

Output limitato a PID/uptime, threadID/state/kind chiuso e frame con classi/metodi allowlisted/linea/native; nomi thread, file, messaggi/stack Throwable, argomenti e valori business non vengono esportati. Massimo128 thread/48 frame per thread/64KiB di messaggi, conteggi e indicazione truncated; simboli sconosciuti omessi. Il cap limita l'export, non il costo interno della raccolta. Gli stack sono temporali per-thread, non atomici; i chiamanti logici di coroutine sospese e il holder possono mancare. Nessuna sospensione debugger esplicita e nessuna garanzia di costo nullo.

**Verifiche preparate, NON ESEGUITE:** tre regressioni Activity reali per cold/mixed/nonTEST/background senza trace/readiness/smoke; tre regressioni Application per rifiuto senza query/lock business, privacy dei frame e budget/troncamento. Metodi/assert/helper preesistenti intatti. Compilazione/test/BUILD/LINT/WARNING e review finale del delta pendenti al runner coordinato; nessun runtime/device/config/backend/Git da questa lane. Coerenza con mandato CA-07/CA-10 verificata staticamente; acceptance globale ancora aperta, task FIX. Master e Planning invariati.

### Esecuzione — 2026-10-08 UTC — attesa terminale del test addManualRow dopo FAIL CI

**File modificati:**
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — una sola predicate di `waitForCondition` nel test `addManualRow appends row updates history entry and tracks last category`; attende `historyActionMessage == app.getString(R.string.manual_row_added)` invece della categoria intermedia. Helper/timeout3000ms, tutte le asserzioni e produzione invariati.
- Questo task, sole Execution/Fix/Handoff — prova CI e motivazione della sincronizzazione del test.

**Azioni eseguite:**
1. MASTER e Task143 confermati ACTIVE/FIX, scope CA-08/CA-10; branch isolato `codex/android-local-query-and-gates-20261008`, HEAD `46bbb8240a68f41d3d68cb0e556089e80761a234`, checkout inizialmente pulito. Nessuna modifica Master/Planning.
2. Letto XML originale mainCI37844829763 in `android-sync-banner-header-main-ci-01/adjudicated/reports/app/build/test-results/testDebugUnitTest/TEST-com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest.xml`: unico FAIL del set1244 (1236 PASS/1 FAIL/7 SKIP), assertion1336 `expected:<Row added> but was:<null>`. Il risultato originale resta preservato, senza rerun artificiale.
3. `addManualRow` salva su Dispatchers.IO tramite `saveCurrentStateToHistory`, poi pubblica `lastUsedCategory` prima di `historyActionMessage`. La vecchia attesa può osservare la prima assegnazione prima della seconda; attendere il feedback terminale già richiesto dall'ultima asserzione elimina questa finestra senza indebolire il contratto dati/categoria/feedback.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO | Lane locale del coordinatore; nessuna build in questa preparazione. |
| Lint | NON ESEGUITO | Da eseguire sul delta finale dal runner owner. |
| Warning nuovi | NON ESEGUITO | Nessuna compilazione o lint nuova; nessun PASS dedotto. |
| Coerenza con planning | ESEGUITO | Riparazione minima del test CI concreto, CA-08/CA-10; sola predicate e documentazione. |
| Criteri di accettazione | NON ESEGUITO | Verde del nuovo test/review/gate ancora pendenti; task FIX. |

**Baseline regressione TASK-004:** test ExcelViewModel pertinente; nessun nuovo caso/assertion, stessi timeout e fixture. Esecuzione sul delta PENDING, non N/A. Il blocco ricerca nativo resta un'indagine distinta, non corretto né attribuito da questa patch.

**Handoff notes:** sorgenti congelati dopo questa modifica; root unico owner Git/runner. Nessun build/test/device/config/backend o modifica di produzione eseguiti dall'executor.

### Esecuzione — 2026-10-08 UTC — banner sync e intestazione Database, RED e fix minimo

**File modificati:**
- `ui/navigation/NavGraph.kt` — Column stabile con indicatore misurato sopra il Box del NavHost; stessa istanza/callsite della navigazione e stessi innerPadding/bottomBar.
- `ui/components/CloudSyncIndicator.kt` — soli margini top/end12dp spostati dentro AnimatedVisibility, per non riservare altezza durante hidden/delay.
- `app/src/androidTest/java/com/example/merchandisecontrolsplitview/ui/navigation/LocalAvailabilityRootDeviceTest.kt` — un solo test additivo `actualBusySyncBannerDoesNotOverlapDatabaseHeaderInFourLocales`; i cinque metodi originali e tutti gli helper sono byte-invariati.
- Questo task, sola Execution — difetto visuale osservato, intenzione UX e verifiche ancora da eseguire.

**Azioni eseguite:**
1. Letti MASTER, Task143 attivo/FIX, protocollo e addendum utente dell'8 ottobre. Le quattro schermate reali it/es/zh/en del coordinatore mostrano il banner sopra titolo e azioni import/export; non sono una prova di esecuzione del nuovo test.
2. Verificato il percorso produttivo: `NavGraph` sovrappone `CloudSyncIndicator` in `TopEnd` al `NavHost`, senza spazio riservato per il `DatabaseRootHeader`. Nessuna modifica di produzione prima del RED effettivo.
3. Preparata una sola regressione Compose sul root reale: recovery/activation e receipt Room reali tramite fixture esistente, viewport360dp, quattro lingue e fontScale1.0/1.6. Il test attende la visibilità del banner, misura intersezioni con titolo e due target cliccabili, raccoglie tutti gli otto casi e conserva query/tab; nessun grant locale fabbricato o backend reale.
4. **UI/UX intenzionale inizialmente proposta e applicata solo dopo RED A02:** riservare spazio al banner nel layout senza ricreare il NavHost al cambio di stato, così da lasciare leggibili e utilizzabili titolo e azioni preservando tab, ricerca, bozza e focus. Il root eseguirà RED e successivi gate sul solo AVD sintetico isolato5590; primaria5554 intatta da questa lane.

**Tentativo ufficiale A01 — setup fallito, NON RED:** il runner AndroidJUnitRunner del coordinatore ha terminato con status-2 e `IllegalStateException: No ActivityResultRegistryOwner was provided via LocalActivityResultRegistryOwner` in `FilePickerScreen.kt:68`, prima delle misure banner/header. Il contesto localizzato del nuovo test perdeva il lookup dell’Activity host. Corretto esclusivamente quel setup: cattura di `LocalActivityResultRegistryOwner.current` prima del provider localizzato e propagazione esplicita dello stesso owner. Nessun cambiamento produttivo, geometrico o alle asserzioni; cinque test/helper originali invariati. Log originale `android-sync-banner-header-red-device-01/instrumentation.log` preservato; cleanup owner conclusa alle20:10:23UTC, senza accesso alla primaria. La nuova coppia APK e il RED A02 sono ancora da eseguire.

**RED effettivo A02 e patch:** il secondo runner ufficiale termina alle20:14:16.600506UTC con l’assert finale `Busy banner occludes the actual Database header`:24 intersezioni (titolo/import/export in ciascuna delle8 configurazioni). I guard precedenti su query/tab passano. Receipt `android-sync-banner-header-red-device-02/receipt.json` SHA `663b319e76b354af30e4c2064fa362af9d8022e907afa62ce9cd2f9eb5342106`, sorgenti invariati e cleanup AVD/processi qualificata dal coordinatore. **Export visuale parziale:** sei file hanno prefisso PNG ma soltanto cinque sono completi e validati; it1.6 è troncato (15872 byte), es1.0/es1.6 non sono stati recuperati (offline/not found). `visual-export-validation.json` separa questi limiti dalla geometria ufficiale che attraversa tutte le8 configurazioni; nessun claim di QA visuale completa. Questo è il RED del difetto; A01 resta un errore di setup distinto.

Dopo il RED, applicata la sola correzione layout in due file: Column sempre presente, banner primo slot e Box del NavHost con peso1 nello spazio restante. Il NavHost e tutto il suo body sono byte-invariati; non è introdotta alcuna key/branch per ricrearlo, né stato di altezza o callback onSizeChanged. I margini del banner vivono nel contenuto animato: durante fade-out lo spazio resta riservato, poi torna a zero; delay700/800ms, fade150/200ms, testo/status e bottomPadding invariati. Test finale SHA `7c7c8b54e7996abf62da510288c072c40c32da86fc52b429daa01323c8a891a4` identico al RED. GREEN e review del delta stabile ancora da eseguire.

**GREEN geometrico e nuovo rilievo visuale sullo stesso banner:** la classe ufficiale6 metodi passa alle20:25:32.248465UTC, receipt `android-sync-banner-header-green-device-01/receipt.json` SHA `7c97ea2d136b01eda476b5c6e62f24f2869f51b12eaee322f08f3d23a230da77`; sorgente invariato e cleanup owner qualificato. Tutti8 PNG sono completi e validati (`visual-export-validation.json` SHA `6ca446ced4633abdf76b09154d3f5be2ea12704c2eaa4fe44ecb8de7907bf23f`). L'occlusione dell'header è risolta. La lettura dei quattro PNG font1.6 rileva però taglio del dettaglio LocalReady e prima lettera stage spagnolo visivamente assente; font1.0 completo. Le risorse contengono le frasi intere; detail è limitato a maxLines1. La causa precisa del taglio iniziale spagnolo resta da misurare, non dedotta dal solo raster.

Su richiesta del coordinatore, aggiunto soltanto al medesimo nuovo metodo un oracolo TextLayoutResult per stage/detail in tutte8 configurazioni: nessun hasVisualOverflow e rettangolo del testo non ritagliato contenuto nel banner. Tutte24 verifiche geometriche/query/tab e i5 test/helper originali sono preservati. Questo nuovo RED è motivato dalla review visuale concreta; non è un rerun della stessa verifica superata. Produzione ferma sul fix geometrico, nuova correzione testo NON applicata prima del RED. Le etichette tab a font grande preesistenti restano un rilievo separato fuori da questo delta.

**RED testo effettivo e correzione minima:** il runner ufficiale successivo fallisce nell'assert finale `Busy banner text must be complete and contained`, dopo il PASS dell'oracolo geometrico:15 overflow stage/detail nelle8 configurazioni, dettagli width/height nel log. Il risultato include overflow misurati anche a font1.0 che la lettura visiva precedente non aveva rilevato. Receipt `android-sync-banner-text-red-device-01/receipt.json` SHA `6a144294e1420807c8222285c6f4e8b2f59408c82dada3d5469406dde9681c1b`, release20:33:52.680334UTC, sorgenti invariati e cleanup owner conclusa. Gli8 PNG sono stati recuperati; qualificazione visuale finale distinta dal RED geometrico/testo.

Solo dopo questo RED, `CloudSyncIndicator` vincola la Column al resto della Row dopo icona/spazio, assegna ai due Text la larghezza disponibile e rimuove i limiti maxLines1/2 per consentire il wrapping completo del copy. Non cambiano testi, stile/font, stato, API, ritardi o animazioni. NavGraph resta quello del GREEN geometrico. Test SHA `ff838b1370b1d34639262b319b62a059742a28f2b322c1105fd2b1c85e8e8556` immutato; GREEN testo, verifica visuale finale e review C sono ancora pendenti. Le etichette dei tab restano separate e non modificate.

**Risultati finali locali del banner — 2026-10-08 UTC:** il gate canonico sul sorgente finale termina exit0, source unchanged, dalle20:41:03.828298 alle20:43:59.119005UTC, gruppo91575 assente e nessun segnale/timeout. Receipt `android-sync-banner-text-final-gates-01/receipt.json` SHA `1d6e3cf4eab53bccc90f0ca1c977b3f68fc05ff7fec368c8acee469e103de445`:76 XML,1244 casi unici =1237 PASS+7 SKIP preesistenti,0 FAIL/ERROR; ID/stati e motivi skip esatti. Build/lint/androidTest compile passano;44 warning lint/0 errori, nessuna firma nuova/rimossa, nessun diagnostico compiler nuovo. Coppia KEYLESS app `c3634e4d`/test `a913d554` del gate sintetico, non ProperTEST e nessuna nuova installazione primaria da questa lane.

La classe Compose completa6 metodi passa sul solo AVD posseduto isolato5584, dalle20:44:33.272467 alle20:46:24.521891UTC; cleanup AVD/processi conclusa e sorgente preservato. Receipt `android-sync-banner-text-green-device-01/receipt.json` SHA `1b5cc00f9289ab6a1573b889c0477b19142b51da478ec2bd8cad6cb16f8e1cf1`. Il test finale `ff838b13` e tutti5 test/helper originali restano invariati rispetto al RED testo: geometria e completezza/contenimento dei due testi passano nelle8 configurazioni.

Otto PNG completi CRC/raster (`visual-export-validation.json` SHA `ea69fcfda96ccc4a98eea81f508fe4673a6cf41677b16389a16c702458a37419`), tutti visionati dal root. Il primo readback della vista ridimensionata di ES1.6 era errato: l'originale corrente, hash `23dcaa4112019af3465eea1589f75651103ec7c46077d0b962b4e2192ce38a05`, mostra la A iniziale e il copy completi. Adjudication `visual-adjudication-original-resolution.json` SHA `3ef3e1679d6b4bb3160fec21e8ce2d2b2b9c6891a4ffc3396c96927760cba869` conserva la review errata `14e0dc17` come storica e qualifica **PASS visuale del solo banner in tutte8 le configurazioni**. Nessuna nuova modifica/oracolo/rerun per quel falso allarme; CircleShape invariato. Il coordinatore C ha approvato separatamente i delta geometrico e testo. I limiti visuali preesistenti delle etichette tab restano un residuo UX globale separato, non nascosto né corretto in questo batch.

**Check obbligatori:**

| Check | Stato | Note |
|---|---|---|
| Build Gradle | ESEGUITO | Gate canonico finale exit0, assembleDebug e androidTest compile; coppia KEYLESS sintetica qualificata separatamente da ProperTEST. |
| Lint | ESEGUITO | 44 warning/0 errori, nessuna firma nuova/rimossa rispetto alla baseline effettiva. |
| Warning nuovi | ESEGUITO | Nessun diagnostico compiler nuovo; nessuna suppression/dipendenza aggiunta. |
| Coerenza planning | ESEGUITO | CA06/CA10 e addendum UX; due file layout, un test additivo e questo log, RED→fix minimo senza cambi business/API. |
| Criteri interessati | ESEGUITO locale / NON ESEGUITO globale | Full1244 e Compose6 PASS/7 skip JVM invariati;8 PNG banner PASS. Non equivale ad accettazione autenticata/cross-platform né chiude i residui UX globali/iOS. |

**Baseline TASK004:** suite JVM/Robolectric completa finale, comprese repository, DatabaseViewModel, ExcelViewModel, import/export/history:1244 casi,1237 PASS e7 SKIP opt-in preesistenti con motivi invariati, nessun harness sospeso attivato. Separatamente i6 test Compose reali sul dispositivo isolato; nessuna equivalenza con backend/autenticazione primaria.

**Incertezze / Handoff:** geometria, wrapping e visuale del banner verificati sul sorgente finale; recensioni C approvate. Nessuna produzione o test modificata dopo i gate. Restano separati etichette tab preesistenti, QA globale, accettazione autenticata/cross-platform e lane iOS. Stato FIX, non DONE; root integra selettivamente i4 file e gestisce CI/ProperTEST/primaria. MASTER, Planning e altri log invariati.

### Esecuzione — 2026-10-08 UTC — contratto V6 e correzione bounded degli intent storici

**Stato FIX, non DONE.** Il contratto deployed acquisito read-only rifiuta shop più legacy store, metadata producer non consentiti, chiavi di altri domini anche vuote e prezzi senza l'esatto insieme dei parent. Le nuove prove usano repository Room, typed ACK, wrapper DeviceGuarded e adapter Supabase reali; nessun backend o dato primario è modificato.

**File modificati:**
- `InventoryRepository.kt` — envelope prezzi/parent sotto shop, split dei nuovi operation ID entro limite JSONB, separazione changed/tombstone e unica correzione storica autorizzata con proof corrente più CAS5→6.
- `SupabaseSyncEventRemoteDataSource.kt` — normalizzazione chiusa del solo wire V6; fallback legacy usa i parametri originali.
- `SyncEventModels.kt` — sola query DAO bounded per selezionare i candidati retry storici; conteggi primari e chunk per dominio preesistenti invariati.
- `DeviceAuthorizationRemoteGuards.kt` — capability interna del delegate Supabase corretto; guard cloud invariato.
- `DefaultInventoryRepositoryTest.kt`, `SyncEventReadBoundaryTest.kt`, `HistorySessionPushCoordinatorTest.kt` —25 nuovi casi e regressioni dei wire effettivi, dei guard preclaim e del fallback legacy.
- `ShopSyncRecoveryCoordinatorTest.kt` — helper test-only che riusa activation/reopen canonici reali prima degli ACK, senza baseline/proof sintetici.

**RED→GREEN:** i FAIL desired scope/metadata/DI/domain/parent/mixed/History e il limite250 restano immutabili. Legacy RED03 `ed1c6a25` riproduce parent aggiunto al body legacy e fallback assente senza bridge; il fixture finale conserva l'ordine effettivo degli ACK, eliminando solo il sorting incidentale. Il caso finale205+205 discrimina JSON compatto16021 byte da testo JSONB PostgreSQL16432 byte. Setup/compile FAIL precedenti restano distinti da RED.

**Check obbligatori:**

| Check | Stato | Evidenza |
|---|---|---|
| Build Gradle | ESEGUITO | Gate canonico `assembleDebug lint testDebugUnitTest`, exit0/BUILD SUCCESSFUL; APK keyless `389e10e7` non distribuibile, non installato. |
| Lint | ESEGUITO | 44 Warning/0 Error; report fresh e byte-identico alla baseline `fd29c629`, 0 issue nuovi/cambiati. |
| Warning nuovi | ESEGUITO | 0 diagnostici compiler nuovi, 0 warning Kotlin; nessuna suppression aggiunta. Le sole due righe CDS OpenJDK restano diagnostici della JVM. |
| Coerenza planning | ESEGUITO sul delta mirato | CA07/CA10;25 casi nuovi su flussi reali locali, nessuna API pubblica/schema/dipendenza. |
| Criteri interessati | ESEGUITO locale / NON ESEGUITO runtime finale | GREEN03 `560ac22e`:275=274PASS/1skip preesistente/0FAIL. Canonico finale `8917cb02`:76 XML fresh/1244=1237PASS/7sameSKIP/0FAIL, tutti1219 ID/stati e reason skip baseline esatti,25 nuovi PASS. Release JVM NOT_OBSERVED; androidTest non richiesti in questo gate. |

**Baseline TASK004:** suite completa JVM/Robolectric eseguita, comprendente repository, DatabaseViewModel, ExcelViewModel, import/history e sync. I tre mirati completi precedono il canonico; tutti25 nuovi casi sono PASS negli XML originali (`c5ce87f5`). Non è Compose/Espresso né convergenza business live. Source538 tracked esatto attraverso il gate, con source23/carry15/8dirty, Task/MASTER/HEAD/index/status preservati. Entry17:35:42.893986Z, release17:38:53.533933Z entro deadline18:05:42.893986Z; PG7655 chiuso, ownGroupRemaining=[] e nessuna cleanup forzata.

Review indipendente finale delta v3→v4 APPROVED, nessun finding residuo; entrambi i P2 precedenti sono corretti. Tutti422 metodi @Test originali restano byte-identici (readback `950a8024`). **Limiti di copertura:** i due witness repository legacy sono fresh-only; il retry persistito legacy è qualificato dalla review statica. Il reject oversize persistito prima del CAS non ha un witness dedicato. **INCERTEZZA:** CI exact-commit, ProperTEST, installazione preservativa e verifica runtime finale NOT_RUN per questo nuovo candidato. I due intent live non hanno consumato il sesto tentativo; eleggibilità e commit remoto devono essere osservati dopo integrazione e installazione normale. Freeze sorgenti v4 `eb49faa8`, patch/inversa offline exact; nessun successo backend assunto dal fake contrattuale.

**Evidenze finali:** `native-residuals-continuation-20261007-01/resume-20261008-android-v6-final-gates-actual-01/receipt.json` SHA `8917cb02021660d300f6c24c76b2241c4e737ac2dabc8f8a48cfc14d890dd797`; `actual-case-id-status.json` SHA `950b3261bd187cf85860a417bd591fe9b43b5ce1c37edea9ccb13837ab59084e`; `actual-xml-manifest.json` SHA `3caf716774690b23881a37e3f92482bbf4455a48b7ee6866a9c1132f8c6bd6f5`; `actual-skip-details.json` SHA `a6bf9945b818abeb8ab3ac66a767443968a58dabe8755c32352d8a03f9bc0dc0`; `gradle.log` SHA `c068c55781b12165685b26a22dcdaee3e0bc15be46511d0bfc0be4f3ba1f462b`; `lint-results-debug.xml` SHA `fd29c629e5d50ad797407362fbdbff8094fe9ada087b8127120825de81f9ed1c`. Il report lint è fresh, non adjudicato da reuse.


### Esecuzione — 2026-10-07 UTC — receipt ACK e ownership inbound canonica

**Stato FIX, non DONE.** Due difetti distinti riprodotti su Room file-backed: il vero typed SupplierCreate ACK cambia la cardinalità fisica senza riscrivere checkpoint A; il legacy bootstrap completo può potare righe pulite possedute da A. Il secondo meccanismo è coerente con la sequenza runtime READY→catalog_prune→bootstrap_ok→receipt mismatch conservata dal coordinatore. Il test ACK non attribuisce le righe mancanti live.

**File modificati:** `ShopSyncRecoveryCoordinator.kt` valida il conteggio attivo sul medesimo keyset ACK strict della prova fisica; `InventoryRepository.kt` protegge catalogo, prezzi e History prima del fetch e nella transazione di apply; `CatalogAutoSyncCoordinator.kt` rilascia BOOTSTRAP, drena con il percorso esistente e risveglia il push locale già presente; `CatalogSyncViewModel.kt` e `HistorySessionPushCoordinator.kt` conservano outbound e risultati effettivi, errori originali e pending distinto da successo. Tre file test esistenti ricevono solo aggiunte: `ShopSyncRecoveryCoordinatorTest`, `CatalogSyncViewModelTest`, `HistorySessionPushCoordinatorTest`. Nessun cambio schema, dipendenza, endpoint o firma pubblica.

**RED→GREEN:** ACK reale `c8ab6b26`; legacy prune producer `7204a537`; consumer automatico invariato sulla vecchia produzione `10b5d74d`; errore catalogo più History defer `05e3621f`; lavoro durabile precedente senza RAM tickle `04d8bb0d`. Il producer legacy conserva il vecchio getOrThrow: non è relabelled GREEN dopo il typed defer. I due finding della review finale hanno correzioni e regressioni dedicate. La nuova regressione durabile ha corretto la sola cattura progress-release prima del readback sospendibile: il corpo finale non è byte-identico al primo RED; replay separato `9acd22ac` usa lo stesso metodo finale e solo Coordinator senza wakeup, fallisce esclusivamente DURABLE_PUSH_DESIRED, quindi restore `f3767428` e GREEN14 `677a83bf`. Tutti i setup/compile/timeout e FAIL precedenti restano storici.

Guard owner/shop/device/lease, ACK body/identity, digest canonici, dirty/orphan/tamper, tombstone, revoca e CAS restano strict; A non viene riscritto. A apparso durante catalogo/prezzi/History fetch è ricontrollato nella medesima Room transaction del vero apply. NoA mantiene il bootstrap completo; autorità invalida non cade nel legacy. La review SAME finale APPROVED sul source d975 non ha finding residui; freeze115363f0 lega gli stessi19 hash ai RED/GREEN effettivi.

**Check obbligatori:**
| Check | Stato | Evidenza |
|---|---|---|
| Build Gradle | ESEGUITO | Canonico offline `test assembleDebug lint assembleDebugAndroidTest`, exit0; APK keyless non installato. |
| Lint | ESEGUITO | 44 Warning/0 Error locali, identità/severity/message/file/molteplicità uguali alla baseline d0 fd29c629. |
| Warning nuovi | ESEGUITO | 0 diagnostici Kotlin e 0 issue lint nuovi/cambiati; nessuna suppression aggiunta. |
| Coerenza planning | ESEGUITO | CA07/CA10; RED reali prima delle patch; minimo routing e push esistenti, nessun engine nuovo. |
| Criteri interessati | ESEGUITO locale / NON ESEGUITO runtime finale | Mirati14P; full Debug1219=1212P/7sameSKIP/0F, tutti1207ID/stati vecchi più12 nuovi. androidTest compilati, non eseguiti in questa lane. Release JVM NOT_OBSERVED. CA07live/CA09 e chiusura globale restano aperti. |

**Baseline TASK004:** full JVM/Robolectric comprende repository, DatabaseViewModel, ExcelViewModel, import/history e sync; non è Compose/Espresso né convergenza business per-record. Ricevuta canonica finale originale `5a33b5377501` conserva FAIL del checker sulla sola mtime del report lint: Gradle ha eseguito le tre analisi, con partial/model originali fresh conservati, e ha riutilizzato il report44 byte-identico come UP-TO-DATE. Adjudication separata `4c770930` verifica20controlli; nessun rerun/cache delete/cambio del comparatore finale. Source19 congelati; Task/MASTER/HEAD/index/status preservati durante i gate. Il canonico storico a56/9d5d mantiene il FAIL del checker che pretendeva XML Release non prodotti: adjudication05fe3964 qualifica Debug1217 reale/ReleaseNOT_OBSERVED; non è il gate del source finale. Target08 è SETUP_NOT_RUN0case (snapshot ometteva androidTest), corretto solo l’inventario nel target09.

**INCERTEZZA:** nuovo candidato non ancora installato né verificato per convergenza live. CI exact commit, merge/mainCI, properTEST/FF/install e per-record restano lane separate. Evidenze: `native-residuals-continuation-20261007-01/android-ack-cardinality-canonical-routing-final-candidate-v4`, `android-ack-cardinality-routing-targeted-09`, `android-ack-cardinality-routing-canonical-02`.

### Esecuzione — 2026-10-07 UTC, sincronizzazione reale del test double-confirm

**Stato FIX,non DONE.** La prima CI del supplemento documentale PR20 (`37556423441`,checkout9ed esatto) è FAIL:1176ID=1168PASS/1FAIL/7sameSKIP. UnicoFAIL `DatabaseViewModelTest.importProducts ignores double confirm while apply is already running`,0.063s,riga2004:MockK `applyImport was not called`. Gli altri1175stati e cinque testV2 coincidono con main4b; assemble/lint PASS. Originale XML `8ffb3d7f56ef064d89efda1128f94fc838a4ec930e084ea651b48102671a2248`,verifica `786fed0bc231973ff656f5cc3c99d762f81a477653297bed407d510eb5e38da5`; nessun rerun per ottenere verde.

**File modificati:**
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — il test attende l'ingresso effettivo nel mock applyImport su IO prima del secondo confirm; gate rilasciato in finally e terminale Success(previewId) atteso con helper/budget esistenti.
- Questo task — solo la presente voce Execution.

**Causa e fix:** MainDispatcherRule controlla soltanto Main con UnconfinedTestDispatcher; `DatabaseViewModel.importProducts` usa `withContext(Dispatchers.IO)` reale. `advanceUntilIdle` e l'async del metodo void non attestavano l'ingresso del worker. La barriera viene completata dentro coAnswers prima di gate.await; il secondo confirm è così verificato durante l'apply effettivamente iniziato. `applyImport exactly1` e `insertHistoryEntry exactly0`,tutti61ID,gli altri60metodi e il resto del file sono invariati. Nessuna nuova sleep/timeout/dipendenza o modifica alla produzione.

**Check obbligatori:**
| Check | Stato | Evidenza |
|---|---|---|
| Build Gradle assembleDebug | ESEGUITO | Unico comando keyless offline,exit0; app task UP-TO-DATE. APK keyless NON installabile,nessun device toccato. |
| Lint | ESEGUITO |44warning legacy/0errori,XML fd29c629e5d50ad797407362fbdbff8094fe9ada087b8127120825de81f9ed1c byte-identico al precedente localeV2. |
| Warning nuovi | ESEGUITO |0warning/errori Kotlin; solo warning JVM CDS preesistente. |
| Coerenza con planning | ESEGUITO |Correzione CA10 del singolo FAIL concreto,oracoli preservati,produzione e scope invariati. |
| Criteri di accettazione | ESEGUITO locale / NON ESEGUITO CI finale |61/61PASS,0FAIL/ERROR/SKIP incluso double-confirm; nuova CI exact-SHA da completare prima del merge. |

**Baseline TASK004:** suite JVM/Robolectric DatabaseViewModelTest completa61/61PASS; non test UI. RED ufficiale CI prepatch conservato,GREEN locale singolo senza loop. Receipt `2e30b4aa18ff54c2bfbc95bcbae4ed6f8afbfdfee977280e18180e9f34afeb28`,XMLGREEN `3d579668fc2ea7b36813d51bdf0b46f5bc38f500ad30425cd5c8f188301f9ec3`. Gate01:38:50→01:39:56Z,66.31s,exit0/PGassente/0segnali;289input e governance preservati. Test finaleSHA `e5c34ed3c4b786eb2895aa919ef6aee28599fce7331cbab26b79871f5f068e5e`; entrambi reviewer indipendenti APPROVED_STATIC sul finale,nessun finding bloccante.

**Handoff:** produzione app/build identica4b e APK4c già installato: test/documentazione non richiedono nuovo proper o reinstallazione. La semantica esistente Applying→Error e un'eventuale terza conferma sono distinte dal FAIL osservato; nessuna duplicazione/perdita provata,nessun cambio produttivo inferito. CA07live/CA09/chiusura globale restano aperti. iOS full033 è ora1513PASS/36sameSKIP/0FAIL con16UI PASS; B storico resta NON_REPRODUCED,build/proper/CI/install iOS separati.

### Esecuzione — 2026-10-07 UTC, CI finale ordinaria, installazione preservativa e concorrenza strumentale

**Stato FIX; non DONE.** Questo supplemento documenta evidenze effettive successive alla PR19; nessuna modifica a Kotlin, risorse, build, logica di recovery o autorità. La PR19 osservativa è integrata normalmente in main `4b4171fc25025ed4952ac482e96dfb46ba559460`; il tree di produzione coincide con il candidato `b20d5d44` verificato. Il checkout primario è aggiornato con fast-forward; tre modifiche preesistenti, configurazione privata, MASTER e index sono preservati.

**File modificato:** questo task, soltanto la presente voce Execution.

**Azioni/evidenze:**
1. CI PR `37552595103` e main `37553939907` SUCCESS su checkout esatti. Originali XML verificati:76file,1176 casi Debug unici,1169 PASS/0 FAIL/7 SKIP; stessi ID/stati PR/main e cinque nuove regressioni PASS. I sei gruppi locali544 sono inclusi esattamente; nessuna esecuzione Release osservata. Lint CI59warning legacy/disponibilità dipendenze,0errori e nessuna issue nel file di produzione modificato.
2. ProperTEST main4b PASS: otto campi DEX/tipi/valori autorizzati e certificato identici al predecessore;171input pubblici, config/profile/dirty/hash-stat preservati. APK `4c248cf4a0ce5a9809b5962ae0e17f093f560f507462ed76321c857550f4818a`; receipt `234ac36e02f3e16554ff02df14d12dd771768c64e4ef748dff267f980cb52b1f`.
3. Aggiornamento in-place `install -r` del nuovo APK riuscito sul target primario qualificato. Dopo la sola quiescenza di manutenzione,19tabelle hanno count/digest tipizzato identici; DB/WAL/SHM e preferenze hanno hash/stat identici prima/dopo. Copie DB private eliminate,nessuna credenziale o riga grezza conservata. Receipt `bb3a69f4456082aae7ec8d2c0803dc7d880c86313ab13b01fec7411a6978d6fb`. Launch/input/E2E NOT_RUN; nessun claim READY.
4. Il gap runtime della classe esistente `CatalogAutoSyncConcurrencyTest` è ora verificato su NUOVO AVD sintetico API35,keyless,seriale5586. Runner ufficiale `AndroidJUnitRunner`,unica instrumentation: classe richiesta con quattro punti e `OK (4 tests)`,exit0,3.282s runner. Quattro nomi attesi da sorgente separati dagli stati individuali non emessi dal formato pretty; nessun record per-method inventato. Rawlog `f4e2cf5074600ab16fd9b61b0d9d81d20ffb97373ebbd91ab8c3913103829816`; adjudication `f493b0c04b945f5fcc50ce92912618194862518093946e08500674185de737c4`.
5. Primo bootstrap AVD NOT_RUN,0test per transitorio device-offline prima di install; receipt originale preservata. Nel tentativo eseguito il solo nuovo AVD e tutti i gruppi propri sono rimossi;288pin source/build e task/master/HEAD invariati. Nessun accesso5554,reset/restart server globale o input alternativo sul primario. Parser originale NOT_PASS non confuso con failure applicativa: richiedeva eventi raw non emessi da `-w`; adjudication separata sul log ufficiale salvato,nessun rerun per arricchire output.
6. Validatore stretto di produzione eseguito una volta su copia privata consistente corrente: `STRICT_RECEIPT_MATCH`,schema22,pending/outboxzero/catalogclean. Receipt `748499f6d73ae166ead0ba21ee06385b8821b47973dbc0626c2a6c099b29079d`; copia e adapter temporaneo rimossi. Prova locale corrente,non marker remoto/READY né attribuzione retroattiva del journal20:18.

**Check obbligatori del presente delta documentale:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle / Lint / warning nuovi | N/A | Solo Execution markdown; build/lint/CI effettivi del codice4b documentati sopra. |
| Coerenza con planning | ESEGUITO | Soltanto evidenza TASK143,nessun cambiamento funzionale o scope aggiunto. |
| Criteri di accettazione | ESEGUITO parziale / NON ESEGUITO finale | CA10: gap classe4 strumentale chiuso; CA07 autenticato,CA09 finale,CA12 chiusura globale restano aperti. |

**Baseline TASK004:** nessun nuovo codice; suite JVM6/544 e CI1176 sopra,distinte dai quattro test strumentali Android effettivamente eseguiti.

**Incertezze / handoff:** il nuovo APK è installato ma la UI primaria resta NON ESEGUIBILE sotto il problema CUA `noWindowsAvailable`; non aggirato con ADB/monkey/amstart. Nessuna nuova convergenza Android↔iOS/backend,misura PSS/dataset popolato/import-export UI o performance live. Il logger V2 è osservativo; il gate4 verifica concorrenza/scope sintetici,non il logger o RPC reali. iOS è separato: correzione A commit033 locale approvata,nuovo B70 CI originale ancora in analisi; full/build/proper/integration finali non dedotti. Il mandato globale resta aperto.

### Esecuzione — 2026-10-03 UTC — ordinary marker failure, RED/GREEN e gate combinato

**File modificati:**
- Questo file task — evidenze della preparazione, RED/GREEN e gate combinato, soltanto questa sottosezione Execution.
- `data/ShopSyncRecoveryCoordinatorTest.kt` — cinque regressioni additive su recovery reale, riapertura Room e failure del reader controllato; RED actual 3 FAIL/2 PASS seguito da GREEN actual 5 PASS, senza modifica dei 111 casi precedenti.
- `data/InventoryRepository.kt` — correzione di due catch: conservare failure HTTP/network del marker nel percorso normale di retry e HTTP del captured window, preservando il trattamento delle prove negative e dei contratti invalidi. Applicata solo dopo il RED actual e il GO GREEN separato.

**Azioni eseguite:**
1. Letti MASTER, task, protocollo e sorgenti coinvolti. C autorizza la preparazione concreta della correzione CA-07/CA-10 in un worktree separato da main `04f6fe26a9b8825f3ffc6d3fcb7e014221880a75`. Il candidato readiness v5, APK `c044...` e freeze precedenti restano invariati. Nessun nuovo task o cambiamento globale.
2. Letti soltanto log esistenti dello stesso processo Android `10258`. La proiezione scalare redatta `native-c044-ordinary-recovery-existing-log-projection.json`, SHA256 `7bc94fee5859720cab7fc86bea49d6032054a9d984eb5e71a683f443975ce52a`, conserva cinque eventi Activated e cinque drain con zero eventi, gap e richiesta recovery fra 20:31 e 21:33 UTC. Non prova READY finale; log raw e identificativi business non sono esportati.
3. C/W riportano 13 checkpoint e 3 marker HTTP500, associati a 16 statement timeout PostgreSQL 57014 nei log TEST esistenti. Il marker chiama internamente il checkpoint. Il matching di ciascun drain nativo con uno specifico errore backend resta condizionale; il journal locale non era stato letto perché il Mac risultava bloccato in quella osservazione storica.
4. Review statica mirata: il catch del marker noEvents converte anche errori remoti in `null`, quindi crea `CONVERGENCE_PROOF_REQUIRED`; il catch del captured window converte anche `shop_sync_rpc_http_*` in recovery. Il checkpoint iniziale propaga già la failure al normale backoff. Proposta minima: propagare HTTP/network dal primo boundary e HTTP dal secondo, senza pubblicare noWork né modificare baseline, watermark, dati o journal su tali failure.
5. Proposta produttiva congelata in `native-marker-transient-retry-proposed-production.patch`, SHA256 `1d12aa1f98302576c4d4e0af7c4c30c8ce7a5c32a0ab5cc07715a012d00aa6f0`; il precedente `git apply --check` era passato sul sorgente originale `39dc405061e10f7643deedf1ecc56c3fbe1fab2f2c99c331e173263d05cde3d8`. Conservata non applicata durante il RED, poi applicata una sola volta dal primary sotto il GO GREEN separato: sorgente risultante `738a7d2e0c3ac6fdd0b142c5e241a3dee07a17d399d0bcef68c8c8636f493b8b`.
6. Consegnati cinque test nuovi, SHA256 del file `237ba00aed4c52eb798878b6c7f3b3f72980b2d57bbcc6131b230a3f492abded`, 116 dichiarati: rimuovere il solo blocco nuovo ricostruisce byte per byte il file con i 111 precedenti. Review statica indipendente APPROVED per proposta produttiva e test, senza P1/P2 concreti. Osservati tre RED sui transport failure e due controlli PASS, poi tutti e cinque GREEN dopo la patch; compilazione e risultati sono documentati nelle ricevute actual sotto. I 116 restano il conteggio dichiarato della classe, non una suite completa eseguita. Manifest di esecuzione mirata `native-marker-transient-retry-red-preparation.json`, SHA256 `d5677772e7967575fcf61ab6a2cdafaef8858ed0cdd8c86fa22c01cc13f0ba45`, cinque filtri esatti, run180s + cleanup proprio15s.
7. Il confronto statico mirato iOS main `8dfbf9a033c1e9be05c941e1cb49712137c893bc` conferma che transport failure ordinario è già propagato come `.failed` e ritentato incrementalmente. Nessun fix iOS equivalente necessario; receipt `72289c7d36eaff2194fa2fa2d08484c3f66bc0f5473dbec7c01c6ba8642350dd`, nessuna nuova prova runtime.

**Qualifica degli invarianti:** device già presente e outbox vuota sono precondizioni esplicite delle nuove fixture. L'invocazione pubblica conserva i percorsi esistenti getOrCreateDevice/retryOutbox prima del drain: non si afferma assenza universale di DML per ogni invocazione. Gli errori trattati dalla patch non pubblicano la finestra preparata né sostituiscono A/watermark con C.

**RED actual — 22:03:21.723915 → 22:04:41.083808 UTC:** il GO mirato `29d30378…` ha eseguito una sola invocazione Gradle con cinque filtri esatti, profilo sintetico senza local.properties o variabili di configurazione live. Cinque casi in un XML fresco: i due noEvents HTTP500/IOException e il changed-window HTTP500 falliscono esattamente sull'assert failure-as-success; wrong-shop/malformed e cancellation passano. `3 FAIL attesi / 2 PASS / 0 ERROR / 0 SKIP`, compilazione riuscita, 79.359556s, exit1 atteso, nessun timeout/segnale e gruppo proprio `49078` assente. Receipt `native-marker-transient-retry-red-attempt01/receipt.json`, SHA256 `7546cb4d40b7adba7f378e13fd12e1555eafe770bb4093180732f48ba818ad64`. Source39dc e test237b invariati durante questo RED; a quel punto la patch era ancora non applicata. Questo prova la regressione deterministica del client, non il matching di ogni errore runtime/backend.

**GREEN actual — 22:11:47.138000 → 22:12:20.242764 UTC:** il GO separato `cbb0c4f0…` autorizza una sola applicazione della patch revisionata e gli stessi cinque test. Source39dc → source738a, test237b invariato. `5 PASS / 0 FAIL / 0 ERROR / 0 SKIP`, tutti gli XML successivi all'actual start, exit0, 33.104504s, nessun timeout/segnale e gruppo proprio `51758` assente. Receipt `native-marker-transient-retry-green-attempt01/receipt.json`, SHA256 `ce13d8230960e202aef9b67e5243fc122df65950b7f74df7ac1e09b92f6a3a4f`. Verificati preservation/reopen, retry noEvents e changed C1, wrong-shop/malformed fail-closed e cancellation in entrambi i rami. Nessuna installazione o nuova prova live.

**Baseline integrale, rinvio storico prima del gate finale sotto:** AGENTS.md:131-149 richiede la baseline minima `DefaultInventoryRepositoryTest`, `DatabaseViewModelTest`, `ExcelViewModelTest` «prima di dichiarare il task completato o passare a REVIEW»; il Protocollo:75-77 conferma lo stesso gate. La direttiva utente «test mirati sui delta, suite complete sul finale», comunicata da C, prevale sull'esecuzione integrale intermedia. Il manifest337 resta preparazione non eseguita; la baseline non era N/A ed era rinviata al candidato finale, ora verificato nel gate finale riportato sotto. Le successive note di rinvio descrivono lo stato al momento di ciascun evento storico. Task resta FIX, senza REVIEW/DONE.

**Integrazione statica con readiness v5:** copiati i sei leaf del candidato v5 verificato, poi aggiunto soltanto il presente log Execution al suo Task; Planning `780ded37…` e MASTER invariati. I cinque leaf tecnici v5 sono byte-identici, con due soli delta tecnici aggiuntivi nella stessa union pubblica265: Inventory738a e RecoveryTest237b. Originali v5/c044/264 e checkout primari invariati. Carry limitato ai cinque GREEN e ai precedenti sei/due readiness per equivalenza dei percorsi/hash; non è nuova eligibility live. Il gate canonical combinato è limitato a build/lint e sei test esistenti pertinenti: due AutoSync per summary/backoff e quattro recovery per physical tamper, pending locale, watermark row loss e genuine empty0. Nessuna intera suite111/76, nessuna ripetizione dei cinque GREEN; nessuna installazione. Il manifest iniziale `native-marker-v5-combined-canonical-preparation.json`, SHA256 `ebcce2827f8edac3129790c509ff2b77d233c72801d48ef8e10fda8396faa7da`, ha prodotto il fallimento di configurazione attempt01. Il manifest con argv corretto `native-marker-v5-combined-canonical-preparation-argv-v2.json`, SHA256 `92311ac58223d5143b41d3c2bb60320c59769d86429fdc62ec597bea07feac42`, è stato eseguito nel successivo attempt02 sotto un nuovo GO.

**Canonico attempt01 — fallimento di configurazione, non risultato funzionale:** dalle22:33:34.844959 alle22:33:40.243495 UTC, exit1 in5,398s: `Problem configuring task :app:lint` / `Unknown command-line option '--tests'`. Nessun test, assemble o lint è stato eseguito. La receipt grezza `native-marker-v5-combined-canonical-attempt01/receipt.json`, SHA256 `6d18b9e701057ad0650f5e3354da2ec596f588401d2379f187b88728aa3d42cb`, conserva cinque casi del precedente XML GREEN `9e558e83…` con `freshAfterActualStart=false`: sono ignorati, non cinque nuovi PASS. Gruppo owner assente, nessun timeout/segnale; receipt storica non riscritta.

**Canonico attempt02 actual — 22:44:29.497685 → 22:46:56.294875 UTC:** una invocazione con argv corretto (`testDebugUnitTest` seguito immediatamente dai sei filtri, poi `assembleDebug lint`), GO `314fa705…`; exit0 in146,797s, nessun timeout/segnale, PG59388 assente al rilascio. Receipt `native-marker-v5-combined-canonical-attempt02/receipt.json`, SHA256 `14dd56a9a09922405b08e2c2fae9d925eda2819f6168bb1e8455b5775b39ba4d`: **6 PASS/0 FAIL/0 ERROR/0 SKIP**, due XML freschi (Recovery4 `dd1ee721…`, CatalogAutoSync2 `af373052…`), esatti sei ID attesi. Fonte pubblica265, Planning e MASTER invariati; build e lint completati. Lint `3d7982b3…`:54 Warning/0 Error contro baseline v5 `471fdb0d…`:29 Warning,25 nuove impronte raw/0 rimosse; Classificazione pubblica `lint-delta-classification.json`, SHA256 `8c4e51d87586b580d7c625dfa79798571e04e6f63895af91b5b8a2889c1ba4b3`: tutti i25 aggiuntivi sono suggerimenti di versioni disponibili su wrapper/TOML byte-identici (14 GradleDependency,2 AndroidGradlePluginVersion,9 NewerVersionAvailable),0 nuovi issue sorgente Kotlin/risorse. Nessun upgrade o suppression; nessuna attribuzione alla disponibilità del profilo. Zero righe warning/error Kotlin secondo receipt, senza dedurre zero warning totali. APK `app-debug-KEYLESS-NONDEPLOYABLE.apk`, SHA256 `f3a9a794b8e629b1a86a79559565ae4fd1a40ff222fdbb38f665182a0538e539`, fresco ma esplicitamente non eleggibile al runtime. Nessuna lettura profilo protetto, installazione o nuova acceptance live.

**Build FULLTEST actual — 23:03:54.958719 → 23:04:36.774914 UTC:** build con profilo TEST completo, receipt `test-builds/android/marker-readiness-v5-full-test-attempt01/build-receipt.json`, SHA256 `1c3f47b883786fa867db308d184c30638aa7f4aa343cf8ba087b0829ddcf03f0`; APK `app-debug-test-complete-profile-marker-readiness-v5.apk`, SHA256 `39938692bc79eb8f00aae1732aa1302ee136f11b1fc55a0447f7bec3bf86162b`. Supervisor41,816s; Gradle PG62316 exit0,38,077s di comando (37s riportati da Gradle), quattro comandi metadata exit0. Tutti i cinque gruppi propri assenti, nessun timeout/segnale, rilascio entro deadline. I controlli salvati dall'owner confermano tutti gli8 valori generati,4 embedded e8 campi DEX effettivi (tipi e valori) corrispondenti al profilo autorizzato e al precedente FULLTEST; package/versione e firma debug `b4afd047507204feaed854b654cbee6784b4be5d6c84a1c80878c25b26a9623d` coincidenti. Nessun valore protetto letto o esportato da questa nota.

**Vincoli della build e carry:** retarget review `60ccdb31e26d284b05510b4f00908ce509e9c71794f1f421704cfbf87f13b4b2` APPROVED_STATIC; carry limitato `024f8a61fd723bdf6e907737db9d74279a83efb918852a4384bce694d4b3cf29` conserva i cinque GREEN marker e i precedenti contesti readiness6+2 con i rispettivi profili/authority/review, senza convertirli in nuovi8PASS. Fonte265/MASTER/Planning preservati; configurazione primaria e profilo privato invariati, `local.properties` ripristinato allo stato ABSENT secondo i controlli owner. I tre blob preesistenti sono preservati e invariati: app/test canonici storici source264 e app keyless corrente; nessuna coppia canonica androidTest v5 corrente è dichiarata. Gli artifact storici source264 restano invariati. Questo step aggiunge build e metadata del nuovo APK, nessun test o lint ulteriore, installazione, readiness autenticata, business READY, convergenza o completamento globale. Il controllo della firma sul dispositivo installato e la verifica runtime del target restano separati.

**Runtime actual — 23:12:15.562524 → 23:13:05.014596 UTC:** il primary ha eseguito una sola installazione in-place `install -r` del candidato FULLTEST, APK installato `c044445dcf7e02ac3649917a04b0ad038e3159b4692d1512d031ff30c3122e4e` → `39938692bc79eb8f00aae1732aa1302ee136f11b1fc55a0447f7bec3bf86162b`, con `firstInstallTime` preservato, e una sola apertura ordinaria senza extra. Receipt `native-marker-v5-runtime-preparation-attempt01/receipt.json`, SHA256 `963cba035ed7461ba24ad949d49572b6dae6b6c4f79a87d815a3d1e7cb6e03d7`:49,452072s, processo app14402 identico e Activity RESUMED prima e dopo40s di osservazione. La successiva unica query warm non consumante restituisce `SNAPSHOT_INCOMPLETE`: `sdk_session=true` e tutti i prerequisiti RAM obbligatori true salvo `recovery_idle=false`; tutti i campi locali Room restano UNKNOWN, `business_READY=false` e `preflight_eligible=false`. `one_use_unconsumed=true`, zero righe nel trace sink, zero trigger diagnostici e zero budget diagnostico consumato. Tutti i gruppi dei comandi owner sono assenti, rilascio entro deadline. Nessun retry, force-stop, recovery manuale, SQL o query aggiuntiva. Il solo booleano `recovery_idle=false` non prova presenza, fase o causa di un journal pendente; il source fix non azzera intenzionalmente un journal preesistente. Installazione e osservazione sono actual, ma readiness/accettazione funzionale restano incomplete; nessun nuovo test, lint o completamento globale. Questa nota deriva soltanto dalla receipt salvata, senza nuove operazioni native/profilo/dispositivo da parte dell'executor.

**Osservazione sola lettura actual — 23:20:58.691483 → 23:22:53.320067 UTC:** receipt di rilascio `native-399386-readonly-observation-release-20261003.json`, SHA256 `b877771a6498e522e0f4a53fb498da4a24e14410de9aff4554ad922f5c80fad3`, e proiezione dei log esistenti `native-399386-existing-log-observation-20261003.json`, SHA256 `09fec958c1c9c64b63a65c3a31728d9b8a1dee118f58adf223572018f424b3a3`, verificate dai file salvati. Durata114,628584s (114,629s arrotondati), entro cap120s e prima della fine della finestra quiet23:24 UTC. PID corrente14402 confermato; una sola lettura dei log esistenti mostra retry HTTP500 alle23:18:38.096 e due ordinary drain SKIP alle23:12:21.066/23:12:23.881. Questi eventi non identificano RPC fallita, SQLSTATE o fase del journal e non provano convergenza. Entrambi i gruppi ADB propri assenti, exit0, zero segnali; nessuna ulteriore readiness, RPC/trace diagnostica, azione sull'app, export di database/sessione, pausa/cancellazione del job o nuovo codice di osservazione.

**Limite Inspector osservato:** Android Studio supportato esponeva una finestra flottante Running Devices con screenshot leggibile. L'input al menu View ha restituito `-10005 timeoutReached`, stato AX invariato e nessun ID della finestra principale Database Inspector disponibile; `surfaceInventoryErrors=[]` non prova un Mac attualmente bloccato. Non è stata aperta alcuna query SQL, editor o tabella. Fase/attempt/nextRetry restano indisponibili: manca un controllo accessibile del Database Inspector del progetto principale. La precedente condizione di Mac bloccato resta un fatto della precedente osservazione; non è riaffermata come stato corrente. La domanda manuale di sblocco già pendente non è stata duplicata. Task FIX e accettazione globale incompleta; questa osservazione non aggiunge gate build/test o una nuova acceptance.

**Secondo percorso UI Inspector — 23:28:00.989 → 23:28:28.039 UTC:** receipt congelata `native-399386-inspector-alternative-ui-release-20261003.json`, SHA256 `7537643647dc0a39f248ce69423a8d786a8abbb28f5219620f6655d1ef30c2b7`. Un solo click sull’elemento Window17 osservato in Android Studio restituisce `-10005 timeoutReached`; AX invariato, controlli del progetto principale/Inspector non raggiunti e nessuna lettura journal. Durata27,050s entro cap90s e deadline23:35 UTC: il nome errato del campo storico `releasedBefore2355=true` non estende tale deadline. Nessun altro percorso UI, riapertura progetto/reset layout, azione app/device/readiness, RPC/SQL/trace o pausa/cancellazione. La causa non viene attribuita a un Mac bloccato; resta pendente il prerequisito umano già chiesto per rendere accessibile l’Inspector esistente, senza duplicare la domanda. Ramo osservazione concluso senza prova READY o recovery terminale.

**Proiezione journal DEBUG — patch applicata e review mirata APPROVED:** il primary conferma l’applicazione della patch autorizzata `native-journal-readonly-debug-preparation/journal-readonly-production.patch`, SHA256 `01dc9e96bee6b565604976a9a5275ffe012bd5b4b6a35d4dbf737de95ce610e1`, al solo Application isolato, risultante SHA256 `1b835dfc1f74dfca78101f5cd79e04653c32daada12ec8a72217305c849251b8`. Il readiness esistente aggiunge quattro scalari nullable in sola lettura: fase enum, attempt, timestamp prossimo retry e categoria da whitelist del reason persistito, mai reason grezzo. L’osservazione durante recovery busy resta vincolata ad auth/client/shop/generation e snapshot locale prima/dopo invariato; l’eligibilità conserva tutti i14 fatti RAM, i vincoli idle e il precedente scope guard REQUIRED. One-shot originale e coda successiva byte-identici alla baseline Application417e. La successiva review focalizzata `focused-delta-review.json`, SHA256 `624e6f9375fed1ee9280e68f49ee1c30e414b773843ce9a56f98ce9e314da83e`, è APPROVED_STATIC senza blocker; i tre test additivi conservano i22 precedenti e il resto del file. Test risultante `feee10cc51cbe1359d5dfb6adeeedcd541bc1383c452bb9a2dfd3c7b371681ef`, patch test `58bbc8f0d03b67dd10c79b173d67449986f1534694e8cd7a80ded8013faf0fe8`; card dei tre target `e1ac9bcd2970565aea28038f3f4733e631f3a294a71efa972c9685d5aeb3b530`. La review statica resta distinta dall’esecuzione actual successiva riportata sotto. Questo aggiornamento modifica soltanto la presente Execution; Planning/MASTER protetti, FIX e accettazione incompleta invariati. La suite integrale finale TASK-004 resta differita, non N/A.

**Tre target journal + build/lint actual — 2026-10-04, 00:04:08.030727 → 00:07:43.917717 UTC:** receipt `native-journal-readonly-debug-three-targets-attempt01/receipt.json`, SHA256 `a017ec80006553c02f260e9b636f403f53d704725aed80d26e4f41272ac04cf9`, **3 PASS/0 FAIL/0 ERROR/0 SKIP**, esatti tre filtri nuovi, XML fresco `b1cb998819174af82e2ea2ca8ed6a354226f4784c0df886d0df8ff850880bd71`. Exit0 in215,886990s; PG71581 assente, sessione9272 chiusa secondo il primary, nessun timeout/segnale e rilascio entro deadline. I casi `checkpointReadinessBusyJournalPhasesAreReadonlyAndNeverQualify`, `checkpointReadinessJournalRejectsUnscopedDriftInvalidAndUnavailableFacts` e `checkpointReadinessAuthenticatedBusyIntentObservesJournalWithoutEffects` verificano fasi busy, rifiuti/drift/redazione/indisponibilità e intent reale con SDK sintetico, senza effetti osservati su stato, storage o dispatch. Build e lint PASS;54 Warning/0 Error lint,0 Warning/Error Kotlin. La successiva classificazione `lint-warning-location-classification.json`, SHA256 `8dad2e88bb1f0f3c843f986d667bb8eddd7008e17d97685449628b873e5e2ba4`, verifica il report fresco SHA256 `3d7982b36cfd2f957b0d9faf6e0e32a503f000775356101213b473c7b888bc09` identico al report registrato nella receipt precedente immutabile verificata `14dd56a9a09922405b08e2c2fae9d925eda2819f6168bb1e8455b5775b39ba4d`:54 warning byte-identici,0 nuovi issue,0 posizioni su Application/Test modificati. È prova di uguaglianza hash, non solo confronto di totali; il vecchio XML non era stato conservato come blob separato. Profilo sintetico con solo target TEST pubblico, cinque stringhe vuote e due booleani false; nessun valore URL riportato, lettura profilo protetto o azione device/RPC/SQL/trace. Fonte265/MASTER/Planning invariati e artifact399386/receipt1c3f/keyless f3a9 preservati. Nuovo APK keyless SHA256 `05f95e3aa12f217ef6245151ce257fe53648fda624ab00e3ecd3665052d68562`, NONDEPLOYABLE, mai installato e non destinato al runtime: nessuna acceptance business, recovery READY o esecuzione live dedotta dai tre test. Al termine di questo gate il nuovo artifact FULLTEST e retarget minimo erano ancora preparazione separata; la successiva esecuzione è registrata sotto.

**FULLTEST journal actual — 2026-10-04, 00:27:55.840924 → 00:28:39.764723 UTC:** receipt `test-builds/android/journal-readonly-debug-full-test-attempt01/build-receipt.json`, SHA256 `488f8a1bd087d70235a81be7937295bc8fafba7e01c4468e5e585e51d2832efc`; APK `app-debug-test-complete-profile-journal-readonly-debug.apk`, SHA256 `8a1722363c4588418b9bd75b63a83cf42abc20f2913aba541ddc4e69505b5c92`. Una sola assemble e quattro comandi metadata, tutti exit0,43,923799s complessivi;17 controlli materiali true secondo verifica primary, profilo/configurazione/valori DEX autorizzati e firma debug `b4afd047507204feaed854b654cbee6784b4be5d6c84a1c80878c25b26a9623d` coincidenti. Fonte265, configurazione primaria/profilo e artifact precedenti invariati, `local.properties` ripristinato. I gruppi74617/74784/74786/74788/74795 sono assenti anche al controllo fresh del primary; sessione95262 chiusa, nessun timeout/segnale e rilascio entro deadline. Il precedente GO b0ee, ricevuto dopo compaction senza più il minimo270s disponibile, non è stato eseguito e ha avuto zero effetti; soltanto il nuovo GO `8e0b976bc2bcf7c78ee91c2228658bda4cc1a9afc48f534efd3cd04dbc1ffec9` ha autorizzato l’unica esecuzione. Nessun test ripetuto, dispositivo o SQL/trace. Al termine della build la preparazione runtime restava pendente sotto separato GO di C; la successiva esecuzione è riportata sotto e questa build non prova business READY, recovery terminale o accettazione. FIX e baseline finale differita invariati.

**Runtime PREP journal actual — 2026-10-04, 00:37:18.931281 → 00:38:08.365980 UTC:** receipt `native-journal-readonly-debug-runtime-preparation-attempt01/receipt.json`, SHA256 `d432613511053d2acb454760a5ff8a8205b7d76ffc513b74f2ceb4b962c70e11`. Una sola installazione in-place399386 →8a172 verificata con `firstInstallTime` preservato, una apertura ordinaria e una query warm non consumante: PID16737 identico/RESUMED prima e dopo40s, sessione SDK presente. Durata49,434699s,18 comandi exit0 e tutti i relativi gruppi owner assenti, confermati dal controllo bounded fresh del primary; sessione31713 chiusa e rilascio entro deadline. La proiezione dei quattro scalari restituisce **STAGING / attempt0 / nextRetry UNKNOWN / error UNKNOWN**. `recovery_idle`, `local_scope_guard`, `journal_required` e `preflight_eligible` sono false; presenza device/journal, baseline, watermark, snapshot locale invariato e fence corrente sono true, così come gli altri prerequisiti RAM. Outcome `BLOCKED_LOCAL_OR_STALE`, stato di esecuzione `PREPARATION_COMPLETED_CALLER_INCOMPLETE`, business READY false. One-use ancora non consumato,0 righe trace/0 budget, nessuna RPC diagnostica, SQL, cancellazione, reset o retry richiesto. La fase è ora osservata direttamente dalla proiezione circoscritta; non prova immobilità, causa, attribuzione all’ultimo HTTP500, recovery terminale, bidirezionalità o accettazione per record. Decisione causale successiva di C ancora pendente; FIX, accettazione aperta e baseline finale differita invariati. Questa nota usa soltanto la receipt salvata, senza nuova azione runtime dell’executor.

**Classificazione bounded di log esistenti — 2026-10-04, 00:47:12.909963 → 00:47:13.776335 UTC:** evidenza disponibile solo nel tool output riportato dal primary, senza nuova receipt o artifact. Durata riportata0,866378s; PID16737 corrente e APK8a172 esatto verificati prima/dopo, cinque comandi di lettura exit0, gruppi77682/77683/77684/77686/77687 assenti. Una sola classificazione rileva `recovery_activated` alle00:43:32.668. Secondo la lettura primaria di `ShopSyncRecoveryCoordinator.kt:473`, quel log segue il ritorno da attivazione atomica, corrispondenza marker, validazione activeManifest/dati fisici e `clearCleanupJournal`: prova il completamento riuscito di quella chiamata coordinator. Non prova lo stato business READY corrente dell’Application o la parità iOS per record e non dichiara risolti i precedenti HTTP500 backend. Nessun export raw, nuova RPC/SQL o callback provocata dall’osservazione.

**UI terminale ordinaria, sola lettura — 2026-10-04:** scope dalle00:52:02.270083, unica chiamata CUA `getAXState` dalle00:52:38.388 alle00:52:38.597, entro hard deadline00:53:02.270083. Evidenza solo nel tool output del primary: tre comandi identità freschi exit0, gruppi78363/78364/78365 assenti e sessione40142 chiusa. `emit=false`, sola proiezione di label in allowlist; le label Options/Cloud/READY/tab non sono esposte, quindi abilitazione UI terminale **NON VERIFICATA**. Nessun raw AX, identificativo business o immagine esportato; zero input UI, altri metodi o ripetizioni log. Sessione SDK, firstInstallTime e fatti journal/baseline restano quelli della receipt d432 delle00:38, non sono stati riletti. Dispositivo lasciato fermo; i passi di accettazione C Step1→2→3 restano da autorizzare/eseguire. FIX, accettazione aperta e baseline integrale finale differita invariati.

**Follow-up visivo autorizzato dopo AX insufficiente — 2026-10-04:** scope C+parent dalle00:56:44.342774, hard deadline00:57:14.342774. Secondo il solo tool output del primary, PID16737 fresco corrispondente, comando exit0 e gruppo78955 assente; identità APK8a172 riportata dal controllo delle00:52:03, senza altro writer di installazione. Una sola `journalInspectorApp.getScreenshot` supportata dalle00:56:49.362 alle00:56:49.444, immagine temporanea del tool senza file esportato e zero input. Visibili Options, Theme Light ed English selezionati, indicatore account Signed in mascherato e Sign out; navigazione Inventory/Database/History/Options resa con testo scuro normale e Options selezionato in viola. Nessuna label business READY esplicita; titolo cloud troncato e card inferiore fuori viewport. L’aspetto di controlli abilitati non dimostra un click funzionale. Nessun click/scroll/menu, RPC, query, readiness, nuova lettura log/DB, test, framework o artifact.

**Qualifica terminale:** i fatti SDK/sessione/journal/baseline restano quelli storici di d432 alle00:38; l’evento coordinator activated delle00:43:32.668 non registra l’inizio della recovery né ne misura la durata, che resta **NON REGISTRATA**. La lettura sorgente riportata dal primary identifica il completamento normale come journal **ABSENT** dopo `clearCleanupJournal` (righe1503–1505 indicate dal primary), prima del log coordinator473; READY_TO_ACTIVATE precede l’attivazione, mentre cleanup pending indica completamento non concluso. È il contratto del sorgente, non una nuova lettura DB che provi assenza corrente. Dispositivo lasciato fermo; handoff Step2→3 soltanto proposto, C possiede il prossimo scope. FIX, baseline finale differita e accettazione aperta invariati.

**STEP2 — integrazione sorgente efficiency autorizzata da C, 2026-10-04 UTC:** creato il branch `codex/android-native-final-integration` dal HEAD corrente `04f6fe26a9b8825f3ffc6d3fcb7e014221880a75` nello stesso worktree. Importato soltanto l’oggetto locale `2ecb2f93705b70e7af42c9c3053c4facb9ccb166` dal clone `/Users/minxiang/Documents/Codex/2026-10-02/task-2/android`, parent `9306e8a7ab3f96b95d9b844e03f624c785df5c96`; nessun cherry-pick/commit. Verificati i due preimage correnti uguali al parent e il nuovo androidTest assente prima dell’applicazione. Applicata la patch esatta delle sole tre paths, SHA256 `1566e5c04102dcc8067ea8c4fceafaf67c86d17f626e2d18fcec1a2983fad6f1`, con check di applicabilità e postimage byte-identici al commit:

- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinator.kt` — +40/−20: riserva il single flight prima del check dispositivo nei cicli push/bootstrap/drain, evita richieste ridondanti durante busy, conserva i segnali e rilascia la prenotazione sui percorsi non autorizzati/errore/cancellazione.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinatorTest.kt` — +205: otto test nuovi,39 dichiarati nella classe risultante; otto PASS nel primo gate mirato, tutti39 PASS nella successiva full finale sotto.
- `app/src/androidTest/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncConcurrencyTest.kt` — nuovo file412 righe, quattro test dichiarati; compilazione ora PASS, esecuzione strumentata ancora NON ESEGUITA.

Totale del solo delta efficiency:3 file,+657/−20. Le otto paths dirty preesistenti, inclusi Task e test snapshot untracked, sono state preservate byte per byte e nei modi durante l’applicazione. Dei265 input pubblici precedenti,263 restano identici e soltanto i due file efficiency preesistenti cambiano; con il nuovo androidTest la union derivata è266, senza nuovo manifest. Application/Repository e relativi test marker/journal invariati. Staging invariato, HEAD ancora04f6, clone sorgente pulito e invariato; `git diff --check` PASS. Nessuna modifica al primario, configurazione, MASTER o Planning. Il riferimento branch nella parte protetta del task resta storico; il branch di integrazione effettivo è quello appena indicato. UX `df66716e` resta non verificato e fuori batch.

**Gate mirato del candidato combinato actual — 2026-10-04, 01:37:45.997212 → 01:41:45.693597 UTC:** receipt `native-final-integration-targeted-android-attempt01/receipt.json`, SHA256 `89452d6e538b18aa61425ebb5929071ae64e02c685cd2304a20f7f60b6355897`, exit0 in239,695758s. Eseguiti **8/8 efficiency JVM PASS,0 FAIL/ERROR/SKIP**; XML persistito in `test-results/TEST-com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest.xml`, SHA256 `2588882e33f9298a22bdfd7c43488336fa5e29868e703bde4e51561ea1a80d2d`, verificato anche da questa lettura documentale. Classe39 totale: gli altri31 non sono stati selezionati in questo run. Argv actual:

```sh
./gradlew --offline --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest.efficiency*' assembleDebug lint assembleDebugAndroidTest --console=plain
```

`assembleDebug`, `lint` e `assembleDebugAndroidTest` PASS; i quattro test strumentati sono **soltanto compilati**, non eseguiti. Report lint SHA256 `3d7982b36cfd2f957b0d9faf6e0e32a503f000775356101213b473c7b888bc09` byte-identico al precedente journal:54 Warning/0 Error/0 Fatal,0 nuovi issue;0 righe warning Kotlin. I dieci sorgenti/test del candidato invariati, `local.properties` assente dopo il ripristino. APK KEYLESS SHA256 `212315798d4b2b74a023bd024f60a32d2c20fb640611ddb8d0394095f70e41af`, NONDEPLOYABLE e non installato; candidato8a172 sul dispositivo non toccato. PG87633 assente anche al controllo fresh del primary, sessione54887 chiusa, nessun timeout. Review batch Android comunicata dal root: nessun finding P0/P1/P2; la gestione del distinto P2 iOS resta al root e non è una dichiarazione di chiusura globale.

**Limiti del primo gate mirato:** solo `testDebugUnitTest` mirato, nessuna suite Release verificata da quel run e nessun problema Release confermato qui. La full finale, ancora pendente al termine del primo gate, è stata eseguita una sola volta dal primary come riportato di seguito; questo aggiornamento documentale non avvia test, build o runtime.

**Full finale Android actual — 2026-10-04, 01:49:36.073096 → 01:50:30.311475 UTC:** receipt `native-final-integration-final-android-attempt01/receipt.json`, SHA256 `10e2d5dd767e529915d4b242cac058ea335bfab8aa77fa37b9979ecd33652650`, nell’evidence BASE già indicata. Unica esecuzione sul candidato finale congelato, exit0 in54,266608s, nessun timeout; PG90319 assente al controllo fresh del primary, sessione53647 chiusa. Argv actual:

```sh
./gradlew --offline --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process test assembleDebug lint assembleDebugAndroidTest --console=plain
```

**74 XML debug,1134 casi:1127 PASS /0 FAIL /0 ERROR /7 SKIP.** Verificati hash e conteggi di tutti i74 XML salvati in `test-results/testDebugUnitTest`; il task aggregato `test` non ha prodotto XML Release, quindi nessuna esecuzione Release è rivendicata. Log SHA256 `af197099cf6e33fcf2812b21f43469683b34f74a557d4805dd3c586dfea8d91e`:90 task,2 eseguiti e88 `UP-TO-DATE`; il PASS di build/lint/androidTest compile comprende correttamente il riuso degli output aggiornati e non implica90 ricompilazioni o nuove esecuzioni strumentate. I quattro casi `CatalogAutoSyncConcurrencyTest` restano compilati soltanto, runtime PENDING.

I sette skip condizionali noti sono: fixture locale Supabase in `DefaultInventoryRepositoryTest`, benchmark grande dataset, test realtime live, i tre audit Excel `ExcelRecognitionAuditOracleLoopTest`/`ExcelRecognitionDriveBatchAuditTest`/`ExcelRecognitionOracleV2Test` e `ShoppingHogarLocalDebugTest`. Nessun harness Excel opt-in attivato o fixture protetta letta per questo aggiornamento. Lint SHA256 `3d7982b36cfd2f957b0d9faf6e0e32a503f000775356101213b473c7b888bc09` identico al report precedente:54 Warning/0 Error/0 Fatal,0 nuovi issue;0 righe warning Kotlin. Receipt conferma i dieci sorgenti/test invariati e `local.properties` assente dopo il gate; azioni dispositivo0, nessuna installazione o nuova prova live. CA07/CA09, parità live/per record, integrazione sul primario, nuova PR e CI sul commit esatto restano PENDING: Task FIX, non DONE.

**Check obbligatori:**

| Check | Stato | Note |
|---|---|---|
| Build Gradle | ✅ ESEGUITO | Full finale receipt10e2d5: assembleDebug e assembleDebugAndroidTest PASS, con output UP-TO-DATE ove indicato dal log; quattro nuovi test strumentati compilati, non eseguiti. APK KEYLESS non installato. |
| Lint | ✅ ESEGUITO | Full finale:54 Warning/0 Error/0 Fatal; report3d7982 byte-identico al precedente. Task soddisfatto con output UP-TO-DATE, nessuna nuova analisi rivendicata oltre quanto eseguito. |
| Warning nuovi | ✅ ESEGUITO con qualifica | Full finale:0 nuovi issue lint per uguaglianza byte/hash del report;0 righe warning Kotlin. I54 warning preesistenti restano, nessuna suppression o upgrade. |
| Coerenza con planning | ✅ ESEGUITO | Delta marker/journal e integrazione efficiency autorizzati, mirati sui delta poi full finale secondo direttiva utente. Dieci sorgenti/test invariati nel gate; questo aggiornamento modifica soltanto la propria Execution, MASTER e Planning preservati. |
| Criteri di accettazione | ❌ NON ESEGUITO — residui aperti | Gate locali JVM/build/lint soddisfatti; quattro strumentati ancora runtime PENDING. CA07/CA09 e parità live/per record non concluse; integrazione primaria, nuova PR e CI exact commit PENDING. Log/UI storici non provano business READY corrente; SDK/journal restano riferiti a d432 delle00:38. |

**Baseline regressione TASK-004:**
- STEP2 efficiency:8 nuovi casi PASS nel mirato; nella full finale tutti39 casi `CatalogAutoSyncCoordinatorTest` PASS, inclusi i31 non selezionati prima. Quattro nuovi strumentati compilati, NON ESEGUITI su dispositivo.
- Test eseguiti sui candidati precedenti: cinque nuovi casi nel RED e nel GREEN; nel gate combinato successivo soltanto due casi CatalogAutoSync e quattro casi Recovery esistenti. Questi sei non ripetono i cinque GREEN. Nel successivo gate journal a017ec eseguiti soltanto i tre nuovi casi Application, tutti PASS.
- Test aggiunti/aggiornati: cinque casi nella classe recovery esistente, senza modificare i 111 test/assert precedenti o i default globali delle fixture; poi tre casi additivi Application, con i22 precedenti e il resto del file preservati secondo review624e.
- Baseline integrale finale TASK-004: **ESEGUITO**, full canonica `test` receipt10e2d5,74 XML debug/1134 casi come sopra. `DefaultInventoryRepositoryTest`:218 casi,217 PASS/1 skip condizionale; `DatabaseViewModelTest`:61 PASS; `ExcelViewModelTest`:52 PASS. Anche `ShopSyncRecoveryCoordinatorTest`116 PASS e `MerchandiseControlApplicationTest`25 PASS. Sono test unitari/Robolectric JVM, non UI Compose/Espresso. Il precedente manifest337 resta preparazione storica non eseguita: la baseline richiesta è soddisfatta dalla full finale, senza attribuire retroattivamente un’esecuzione a quel manifest. Nessuna suite Release dichiarata.
- Limiti residui: d432 documenta alle00:38 sessione SDK e STAGING/attempt0 con nextRetry/error UNKNOWN, non uno stato successivo. La classificazione delle00:47 rileva una chiamata coordinator riuscita alle00:43; AX delle00:52 insufficiente e screenshot delle00:56 conferma solo l’aspetto della UI. Nessun business READY o parità per record dedotti; nessuna cancellazione del job, recovery manuale o trace diagnostico.

**Incertezze:**
- INCERTEZZA: d432 osservava STAGING/attempt0 alle00:38 e nextRetry/error UNKNOWN; questi scalari non sono stati aggiornati dalle successive osservazioni di log/UI. Il log recovery_activated non sostituisce una verifica business READY corrente. Nessuna inferenza di job immobile o causa; il precedente limite Inspector non prova un Mac attualmente bloccato.
- INCERTEZZA: il timeout server era confermato nelle prove precedenti, ma il retry HTTP500 della proiezione09fec non identifica RPC o SQLSTATE. L’attribuzione di ciascun re-latch nativo resta subordinata al matching delle evidenze; nessuna fase del journal è dedotta dal booleano recovery_idle.

**Handoff notes:**
- Task resta FIX, senza REVIEW/DONE. Review mirata, RED→GREEN, gate combinato limitato, classificazione warning8c4e51, build FULLTEST1c3f e installazione/osservazione runtime963cba sono actual; carry readiness conserva i contesti originari. Il nuovo delta journal ha review624e e gate a017ec con3PASS/build/lint actual; proof8dad2e conferma il report lint byte-identico al precedente e0 nuovi issue. Lo snapshot runtime incompleto non soddisfa l’accettazione. Nuovo FULLTEST488f/artifact8a172 e runtime PREP d432 sono actual; il successivo tool output del primary prova una chiamata coordinator riuscita e lo screenshot mostra Options/account/navigazione, senza verifica funzionale terminale o business READY. Dispositivo fermo, handoff Step2→3 soltanto proposto e prossimo scope di C. STEP2 efficiency applicato nel branch di integrazione: review batch Android senza finding P0/P1/P2 comunicata dal root,8 nuovi JVM/build/lint/androidTest compile PASS. Full finale canonica receipt10e2d5 conclusa:1134 debug,1127 PASS/7 skip noti, baseline TASK-004 ESEGUITA, build/lint/androidTest compile PASS con qualificazione UP-TO-DATE. Nessun XML Release; quattro nuovi strumentati ancora runtime PENDING. UX fuori batch; CA07/CA09, parità live/per record, integrazione primaria, nuova PR e CI exact commit restano aperti. Nessuna dichiarazione DONE. Le prove sul vecchio freeze non diventano nuove prove del delta.

### Esecuzione — 2026-10-03 UTC — ONE7, fix di setup e positivo intent preparato

**File modificati:**
- `MerchandiseControlApplication.kt` — solo `owner?.captureClientOrNull()` → `owner.captureClientOrNull()` nel nuovo preflight, warning Kotlin concreto1137 eliminato secondo smartcast; review indipendente equivalente, body diagnostico originale invariato.
- `MerchandiseControlApplicationTest.kt` — tre call locali su `Dispatchers.Default` con timeout2s/assert invariati e diagnostica scalare; un solo test nuovo SDK/Room inizializzati via vero pause/newIntent/resume, contatori HTTP/storage/Room/shop e recheck finale. Setup/cleanup esclusi dalla misura. Target TEST prova ELIGIBLE; profilo canonico prova target-refusal, senza skip o bypass del pin.

**Evidenza precedente actual:** ONE7 dalle18:38:17 alle18:39:36UTC,79.19s,6PASS/1FAIL/0ERROR/SKIP,3XML. Main/unit realmente compilati. Fallimento nuovo localScopeGuard assertion331; causa inizialmente non attribuita, ipotesi tempo virtuale `runTest` versus IO. Nessun aumento timeout o indebolimento assert. Assemble/lint non raggiunti; source originale del job invariato, gruppo proprio assente/signals[]. Receipt54d4b5, adjudication24de94 conservate in resumed-executor del parent.

**Review e applicazione:** test-only patch734e1e71 +warningoneLine approvati da reviewer indipendente8496fbe5 prima apply; nessun nuovo job eseguito sotto precedenteGO. Prove successive richieste: FAILED1 canonico, NEWpositive1 con solo URL pubblico TEST e altri config sintetici; build/lint sul delta, artifact completo autorizzato e device separati. Sei casi PASS preservati per equivalenza; suite76/oneBody immutabili non ripetuti secondo C.

**UI prioritaria18:55–19:00:** precheckMedium5554/current3ccf/MainActivityresumed, un clic normale Inventory da Options, schermata invariata e tab business grigi. Nessuna transizione Inventory→Options riprodotta; private scope non osservato. Scroll cardcloud non completato per noWindowsAvailable, stop e release18:57:16. Nessun difetto di routing provato, nessuna modifica NavGraph/gate/UI. Screenshot prima/dopo inline CUA e receipt nativa nel parent.

**Check obbligatori:** build/lint/warning finale NON ESEGUITI sul nuovo delta; coerenza Planning ESEGUITA, MASTER/Planning invariati. Criteri globali NON ESEGUITI: preflight sintetico/live, recovery terminale, convergenza e misure restano separati. Task FIX.

**Incertezze:** attribuzione definitiva della failure di setup dipende dal mirato successivo; nessun positivo autenticato provato finora. Nessuna lettura credenziali/prefs/userdata o RPC live/SQL.

### Esecuzione — 2026-10-03 UTC — preflight diagnostico non consumante, candidato isolato

**File modificati:**
- `MerchandiseControlApplication.kt` — preflight DEBUG/TEST con istanze già inizializzate, Room già aperto, predicato locale originale e due snapshot entro 2s; solo enum/booleani, nessun consumo one-use o RPC.
- `MainActivity.kt` — extra Boolean fisso `task143_checkpoint_readiness`, query locale dispatchata sul Main e letture live RESUMED/observer all'inizio/fine; mixed-extra rifiutato senza armare trace.
- `CheckpointReadinessSnapshotTest.kt` — spie di inizializzazione, mutex e flag; snapshot fredda e consumo successivo invariato.
- `CheckpointTraceActivityLifecycleTest.kt` — query intent separata dal trigger e campionamento live dopo callback pause/newIntent/resume o stop.
- `MerchandiseControlApplicationTest.kt` — transazione Room senza DML/DDL, UNKNOWN non eligible, fence stale e database costruito ma non aperto.

**Azioni eseguite:**
1. Creato worktree gestito isolato da main `04f6fe26`; checkout primario dirty e source264/TEST3ccf dedicati preservati. TASK FIX, MASTER e Planning invariati.
2. C ha revocato la propria precedente restrizione soloRAM: sono ammesse letture interne read-only dei metadati, mai esportazione di entità/ID/sessioni/DB/prefs.
3. Review v3 ha richiesto quattro fix (import, aggregazione UNKNOWN, lifecycle live, database non aperto). v4 SHA `44ece16be84b7ecf3b1f9fddc6309df4280b2e67c46cef6cece07fc16d2a1f59` APPROVED staticamente dal reviewer indipendente prima dell'applicazione.
4. Corpo originale `requestOneCheckpointTrace`, header, deadline8s, one-shot e retry restano byte-identici. Baseline/watermark assenti mantengono esattamente la semantica originale; snapshot non concede lease/READY business.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO | In attesa di slot host esclusivo C/N. |
| Lint | NON ESEGUITO | Da eseguire sul delta. |
| Warning nuovi | NON ESEGUITO | Da verificare con compilazione/lint. |
| Coerenza con planning | ESEGUITO | CA-07/10, entry preparatoria DEBUG autorizzata da C; nessuna modifica di Planning/MASTER. |
| Criteri di accettazione | NON ESEGUITO per il finale | Test nuovi, profilo TEST/build e qualifica live ancora pendenti; nessun DONE. |

**Baseline regressione TASK-004:** nessuna logica repository/ViewModel/import-export modificata. Le prove precedenti 1103 PASS/7 SKIP restano del source264; non sono nuove prove sul candidato. Gate mirato previsto sui nuovi test e un singolo caso SDK/body esistente; suite76 immutabile non ripetuta secondo C.

**Incertezze:** zero effetti del preflight sul percorso autenticato inizializzato deve ancora essere verificato; profilo canonico unit con pin nonTEST non prova l'esito positivo sul vero intent TEST.

**Handoff notes:** candidato nuovo solo; nessun build/device/extra/RPC/SQL effettuato durante l'applicazione. Serve GO host; install e qualifica live hanno slot distinto. Task resta FIX.

### Esecuzione — 2026-10-03 UTC — checkpoint one-trace, gate locali actual e port byte-exact

**File modificati:** sei file produttivi (`MainActivity.kt`, `MerchandiseControlApplication.kt`, `data/CatalogSyncStateTracker.kt`, `data/ShopContext.kt`, `data/SupabaseShopSyncReadRemoteDataSource.kt`, `data/Task126BusinessDataScopeRuntimeGuard.kt`) e cinque classi test (`MerchandiseControlApplicationTest`, `CheckpointTraceActivityLifecycleTest`, `ShopContextTest`, `SupabaseShopSyncReadRemoteDataSourceTest`, `Task126BusinessDataScopeRuntimeGuardTest`). Il [ledger pubblico](evidence/TASK-143/android-checkpoint-current-local-gates-ledger.json) conserva percorsi/hash degli 11 leaf, classi/conteggi e SHA delle ricevute effettive. Sono aggiunti soltanto questo log Execution/Fix/Handoff e il ledger.

**Azioni eseguite:**
1. Il caller DEBUG warm-only usa header/trace fissi, guard correnti di scope/lease, consumo singolo per processo e zero RPC al cold start. Il fix lifecycle consegna dopo il reale RESUMED, mentre il guard Release preserva share/smoke ordinari. Il fix del `finally` Task126 mantiene il flight registrato fino alla completion effettiva e riguarda anche il normale flusso business.
2. Il RED lifecycle ufficiale e il primo ONE76 (74 PASS/2 FAIL) restano storici. La completion reale e la fixture con connessione keep-alive precedentemente riuscita sono corrette senza indebolire i 76 ID/assert. Il secondo ONE76 è 76 PASS/0 FAIL/ERROR/SKIP, con 33 nuovi casi. Replay ordinario, mancata ripetizione diagnostica, riuso del socket, body/header equivalenti sono provati dagli assert passati; XML non contiene i body/socket raw. Il vecchio controllo su connessione nuova resta NOT_PROVEN nella sua ricevuta.
3. Copiato dapprima il TASK approvato `57a13619…` dal checkout dedicato, poi gli 11 leaf byte per byte in CommonRoot (`codex/android-checkpoint-trace-guard`, base `7e538936…`). Nessuna decisione Planning nuova: prefix raw `780ded37c7d485b0d48baa4709dee888aed6c74a8010dace10c1cb87229f9e7f` e MASTER invariati. Il dedicated HEAD `814b137f…`, TASK, MASTER e tutti i 264 input pubblici restano invariati; CommonRoot264 coincide con freeze `189d3e27…`, fingerprint map `4c40e81b1b6f384de424241f89fcbd91a9c26d703a3b8d44998d5a2e625539b2`.

**Evidenze actual:** review source `2dc871dd…`; ONE76 `dd4697b2…`; canonico `666dad53…`: 73 XML freschi, 1110 ID unici, 1103 PASS/7 SKIP/0 FAIL/ERROR; 1077 ID e stati precedenti preservati, 33 aggiunte PASS. I sette skip condizionali e le ragioni sono nel ledger; non è stato attivato alcun harness Excel/fixture protetta. Queste prove sono state eseguite dal primary sul checkout dedicato e trasferite per equivalenza byte; nessun gate è stato rieseguito nel port.

**Check obbligatori:**

| Check | Stato | Evidenza e limite |
|---|---|---|
| Build Gradle | ESEGUITO sul source264 | `assembleDebug` canonico PASS; main/unit KSP/Kotlin UP-TO-DATE in quel job. KSP/Kotlin/Java/package androidTest realmente eseguiti nel gate successivo. Il build TEST separato ricompila BuildConfig/KSP/Kotlin/Java/DEX/package. |
| Lint | ESEGUITO | Proof `2302ea84221a43234453e0880161c70e386ce06c0d93100498b0ba6d3a754e18`: 29 warning/0 errori contro 39; 27 identici, 2 UseKtx con sole coordinate spostate. Raw delta 2 aggiunti/12 rimossi; 10 notice di disponibilità assenti non sono fix. |
| Warning nuovi | ESEGUITO con qualifica | Nessun nuovo diagnostico sorgente nel proof; 2 CDS separati dai 29 lint e 0 Kotlin/deprecation emessi nel canonico, che non ricompila main/unit. Build TEST fresco: 0 Kotlin/deprecation osservati. |
| Coerenza con Planning | ESEGUITO per questa slice | Sei production/cinque test autorizzati, 264 hash esatti; copia del Planning approvato, senza modificarlo. |
| Criteri di accettazione interi | NON ESEGUITO per questa slice | Gate locali non completano automaticamente i dodici CA. Integrazione Git/CI, ritest autenticato, READY/convergenza e PSS sono separati e non dedotti. |

**Baseline TASK-004:** canonico JVM/Robolectric include `DefaultInventoryRepositoryTest` 217 PASS/1 SKIP, `DatabaseViewModelTest` 61 PASS, `ExcelViewModelTest` 52 PASS. Non sono test UI Compose.

**Compose actual:** review `4e29195cd399a5f6a2f092ce08b161530b1798f6f61e13c293dab00e8d2c8161`, cinque metodi PASS su coppia corrente app `f3a902ee…` / test `d762cd28…`, una invocation sul solo AVD posseduto emulator-5580. Receipt immediata conserva `ownedGroupExistsAfter=true` e SIGTERM/SIGKILL del PG35707; il rilascio registrato 02:05:14 non dimostra assenza immediata. Osservazione terminale separata 02:06:14.940885 UTC: gruppo assente, nessun nuovo segnale. Source/pair pre/post nel risultato sono un booleano salvato dall'owner, non righe hash indipendenti; la review mantiene questa qualifica.

**TEST e otto metadata actual:** build receipt `160b222f…`, metadata receipt `f6d87b56…`, review `3eadaaae2652ccdb001c6fe0135963afed41b3a86f67b82322578eca59d0bf9c`. APK diagnostico corrente `3ccf651ecd94db92afc4129b1eea825eeebb2fb32270f577d58ffc514835f538`, distinto dai precedenti artifact. Build/metadata exit0, gruppi figli assenti, nessun segnale/timeout, supervisor rilasciato 02:08:16.127816 UTC. Saved source map264 coincide con authority/public map; post-restore source/canonical pair attestati da booleani owner. Profilo autorizzato embedded, applicationId/version/debuggable/firma compatibili e ripristino `local.properties` sono controlli owner salvati; nessun protected/APK reread durante review/port. Governance Task/MASTER/Planning è ereditata dalla card immutabile e dal precedente controllo owner, non una verifica whole-governance del builder. Gli otto check includono uguaglianza di tutti gli otto valori DEX, build files, signer, SDK min31/target36 e toolchain dichiarata17; il dato JDK è una lettura owner successiva del contesto JBR, non attestazione storica del binario compilatore.

**Incertezze e handoff:** nessun nuovo finding o modifica semantica nel port. Nessuna installazione di questo APK diagnostico, autenticazione, RPC live, READY, recovery/convergenza o performance è provata dai gate riportati. Git e CI exact-head restano del parent; stato FIX.


### Esecuzione — checkpoint DEBUG, guard Release dopo review P2, 2026-10-03 UTC

**Finding statico e fix minimo:** la review ha rilevato che `consumeCheckpointTraceIntent` consumava l'extra diagnostica e restituiva true anche in Release; un normale ACTION_SEND con EXTRA_STREAM e quel flag avrebbe quindi saltato `handleShareIntent` nel ramo onNewIntent. Aggiunto soltanto `if (!BuildConfig.DEBUG) return false` all'ingresso del helper, prima di leggere o rimuovere l'extra. In Release il chiamante prosegue ora alla gestione share/smoke ordinaria, senza attivare diagnostica; il guard già esistente dello smoke continua a rifiutare l'esecuzione DEBUG in Release.

**Invarianza:** observer lifecycle, Application, tutti gli altri263 input pubblici e cinque file test/76 ID/assert/tail invariati. L'inversa della singola riga ricostruisce esattamente MainActivity Green-v1. Packet precedente immutabile; nuovo `checkpoint-green-preparation-v2` aggiorna source/request/card/environment e mantiene cinque selector/76 casi, budget600/270/90 e namespace ancora inutilizzato `checkpoint-five-classes-targeted-attempt01`. Planning780ded, MASTER e stato FIX invariati.

**Verifica e limiti:** prova statica del ramo Release ESEGUITA; nessun test duplicativo della sola condizione aggiunto, secondo decisione parent. Compilazione/lint e GREEN76 richiesti restano NON ESEGUITI. Nessun runtime/SDK/socket/device/Git/protected IO; sorgenti in HOLD per review mirata e fresh GO primary.

### Esecuzione — checkpoint DEBUG, fix lifecycle dopo RED ufficiale, 2026-10-03 UTC

**RED reale:** ONE attempt02 ha compilato e prodotto XML ufficiale `5e0ca0316d327bb520950f41e26377a019d95fb8efcc0cbe0c8060b852999159`: **1 FAIL / 0 ERROR / 0 SKIP**, assert47 «Warm delivery must wait for the real resume callback». La sequenza reale Robolectric è create/start/resume → pause → newIntent; il fallimento precede resume. Receipt `2764982442646aecf7d842e6e856e90066d3dd72a1e6fd25e56c756d933ef076`, adjudication `f399ca9ad41405f5cfb37d97d7ad34df8ea4837be37a8066296e8d5d7d5c69df`, sorgenti264 invariati e gruppo17871 assente senza segnali; host rilasciato00:33:34.869498 UTC. L'XML non contiene il payload raw della traccia: BLOCKED_COLD è inferenza dal ramo sorgente, non log osservato. Nota suppressed sul looper preservata nell'XML originale; nessuna modifica al test per rimuoverla. Attempt01 resta compile failure separata, altre75 prove ancora non eseguite.

**File modificato:** soltanto `MainActivity.kt` rispetto al freeze v3. onNewIntent considera warm la stessa Activity almeno STARTED, consuma subito l'extra e registra un observer temporaneo. La richiesta effettiva arriva soltanto su evento lifecycle ON_RESUME; l'observer si rimuove prima di chiamare il runner, accorpa duplicate pendenti e cancella su ON_STOP/ON_DESTROY. Non si assume RESUMED dentro onResume. Cold onCreate e stopped/background non armano la consegna warm; one-shot di processo, runner/scalar/auth/lease e gestione ordinaria share/smoke invariati. Nessuna nuova dipendenza/API pubblica.

**Invarianza e preparazione GREEN:** delta e inversa ricostruiscono MainActivity v3 esatta; tutti gli altri263 input pubblici e i cinque file test (33 nuovi/76 totali) sono byte-identici. Quattro tail originali e assert/ID invariati. Packet esterno `android-checkpoint-one-trace/evidence/checkpoint-green-preparation` con source map, patch e request/card ONE per le cinque classi Application19/Activity7/reader28/ShopContext17/Task126guard5. Engine futuri esclusivamente SDK/OkHttp reali sintetici e loopback locale, controlli replay/equivalenza originali non rilassati. Nuovo namespace `checkpoint-five-classes-targeted-attempt01`, comando unico, massimo600s/minimo residuo270s/cleanup90s, stima180s; review e fresh GO primary obbligatori.

**Check obbligatori:** coerenza Planning/inversa ESEGUITA staticamente; compilazione del fix, GREEN76, lint/warning, baseline TASK004 e gate canonici NON ESEGUITI. Nessun runtime/compiler/socket/client/device/RPC/Git/protected IO dall'executor. Planning780ded, MASTER e stato FIX invariati; sorgenti in HOLD, nessuna readiness o acceptance live dedotta.

### Esecuzione — checkpoint DEBUG, setup Robolectric dopo compile failure, 2026-10-03 UTC

**Evidenza reale precedente:** il primary ha eseguito ONE v2 attempt01, terminato in 56,38 s con `compileDebugUnitTestKotlin FAILED`, nessun XML/test selezionato eseguito e nessun assert lifecycle raggiunto. Receipt `049fffc5a7481200ec8878e8652e19e1274b6e97f891f05312a9d9aad8dfd087` e root-adjudication sono preservati in `android-checkpoint-one-trace/evidence/checkpoint-lifecycle-red-attempt01`. Il gruppo posseduto15292 risulta assente, senza segnali; host rilasciato alle00:23:56.908803 UTC. Esito **COMPILE_SETUP_FAILURE_NOT_FUNCTIONAL_RED**: non autorizza una correzione del P1 lifecycle.

**File modificati:** soltanto dieci chiamate nuove nei test: una nel setup `CheckpointTraceActivityLifecycleTest` e nove in `MerchandiseControlApplicationTest`. `RuntimeEnvironment.getApplication<MerchandiseControlApplication>()` diventa `RuntimeEnvironment.getApplication() as MerchandiseControlApplication`, secondo la firma non generica riportata dal compiler. Delta esplicito di setup nei corpi/helper nuovi; nessuna aspettativa/assert/ID modificata e nessun altro errore ipotetico corretto. I due opt-in v2 restano presenti.

**Azioni e invarianza:** verificata inversa esatta dei due file v2 e preservati i quattro tail originali completi. Tutti157 file produttivi, MainActivity con P1 intenzionale, gli altri input pubblici, 33 casi aggiunti/76 totali, MASTER e Planning780ded sono invariati. Packet v3 separato con diff di sole chiamate, source map, request/card/environment aggiornati; stesso unico selettore lifecycle, nuovo output `checkpoint-lifecycle-red-attempt02`. Packet e ricevute precedenti conservati.

**Check obbligatori:** coerenza Planning/inversa ESEGUITA staticamente; compilazione del candidato v3, lint/warning, RED funzionale, altre75 prove e gate finali NON ESEGUITI. Nessun compiler/engine/socket/client/device/RPC/Git/protected IO eseguito dall'executor. Stato FIX e sorgenti in HOLD per review mirata e fresh GO del primary; nessun retry implicito.

### Esecuzione — checkpoint DEBUG, opt-in locali richiesti dalla review statica, 2026-10-02 UTC

**File modificati:** soltanto annotazione `@OptIn(SupabaseInternal::class)` sui due helper test `withWireServer` (nome qualificato) e `traceSdk`, più questa voce Execution. Il requisito deriva da Auth3.6 `AuthConfig.autoSetupPlatform`, annotato con marker di livello ERROR; nessun opt-in globale o cambio di dipendenza.

**Azioni:** conservato il packet RED v1, aggiunte esclusivamente le due annotazioni, verificata l'inversa che ricostruisce esattamente entrambi i file precedenti. Corpi test, aspettative, 33 nuovi casi/76 totali, sei file produttivi e P1 lifecycle immutati. Nuovo packet esterno `android-checkpoint-one-trace/evidence/checkpoint-candidate-red-preparation-v2` con delta esatto, source map e card vincolati ai nuovi hash; ONE lifecycle resta l'unico selettore.

**Check:** coerenza Planning e invarianza sorgenti/predicati ESEGUITI staticamente; build/lint/warning/runtime e RED funzionale NON ESEGUITI. La correzione rimuove il blocker identificato dalla review, non costituisce prova di compilazione. Planning780ded, MASTER e stato FIX invariati; nessun compiler/engine/socket/client/device/RPC/Git/protected IO. Source hold per review del delta e fresh GO primary.

### Esecuzione — checkpoint DEBUG, preparazione candidato RED isolato, 2026-10-02 UTC

**File modificati:** MainActivity, MerchandiseControlApplication, SupabaseShopSyncReadRemoteDataSource, Task126BusinessDataScopeRuntimeGuard, CatalogSyncStateTracker e ShopContext. Aggiunte nelle quattro classi test esistenti (Application, reader, runtime guard, ShopContext) e nuova CheckpointTraceActivityLifecycleTest. Nessun file del checkout delivery modificato.

**Azioni eseguite:**
1. Letti MASTER → Task143 → sorgenti/test, protocollo e skill Supabase. Adottata la proposta94f10ffd con accessor epoch e wiring opzionale; pin TEST esatto42a5d011 fornito dal parent, nessun valore configurazione letto. Client e transport SDK originali, body diagnostico one-shot e output scalare; nessuna API pubblica/schema/dependency nuova.
2. Conservato intenzionalmente il P1 lifecycle: MainActivity è byte-identica al candidato revisionato e controlla ancora RESUMED dentro onNewIntent. Il primo test proposto usa callback Activity reali pause → newIntent → resume; nessuna correzione prima del RED ufficiale. Cold/background/destroy/repeated/share hanno casi separati.
3. Estratti minimamente i predicati interni reali snapshot/budget per esercitarli senza bypass del target TEST: il runner chiama le stesse condizioni di auth/client/status/threshold, Room e idle scope usate dai test. Pre/post/current/epoch/quiet ed entity equality preservati. Snapshot Room reale a cinque entità e typed-watermark nullable; accessor epoch usa il refreshGeneration sincronizzato già esistente.
4. Preparati **33 nuovi casi / 76 totali** nelle cinque classi pertinenti: Application19, Activity7, reader28, ShopContext17, Task126guard5. I corpi/helper originali delle quattro classi preesistenti restano byte-identici. Le prove engine future usano SDK/Auth/OkHttp reali sintetici e loopback bounded; i controlli normali devono mostrare replay realmente, altrimenti NOT_PROVEN/failure. Nessun client/socket/test avviato.
5. Verificate staticamente le firme nei sorgenti cacheSDK3.6 e i tipi Room; nessuna compilazione dedotta. Packet esterno `android-checkpoint-one-trace/evidence/checkpoint-candidate-red-preparation` contiene patch, mappe hash, note e comando proposto ONE per il solo lifecycle RED. Non è GO né readiness.

**Check obbligatori:**

| Check | Stato | Note |
|---|---|---|
| Build Gradle | ❌ NON ESEGUITO | Slot runtime non autorizzato in questa preparazione; compilazione futura del candidato |
| Lint / warning | ❌ NON ESEGUITO | Static read-only delle firme, nessun claim compiler/lint |
| Coerenza Planning | ✅ ESEGUITO | Worktree dedicato, sei file ammessi, sole aggiunte test e Execution; prefisso780ded invariato |
| Criteri di accettazione | ❌ NON ESEGUITO | Candidato/test preparati; RED ufficiale, fix, review, engine/guard e gate finali pendenti |

**Baseline TASK-004:** DefaultInventoryRepositoryTest, DatabaseViewModelTest ed ExcelViewModelTest immutati; regressione futura dopo fix/review. I test JVM/Robolectric preparati non sono acceptance su device autenticato.

**Incertezze / Handoff:** nessun PASS dichiarato. Primo comando deve distinguere compilazione/setup dalla failure nell'assert lifecycle desiderato. Content-Type/charset e replay normali devono essere osservati nell'engine reale; nessun assert rilassato. Il pin TEST resta fail-closed: le prove sintetiche dirette dei predicati non provano dispatch autenticato completo né assenza di attività Auth HTTP privata. Root resta owner di runtime, Git, nuovo APK e finestra live. Stato FIX; nessun RPC/DDL/protected IO o accesso5556.

### Esecuzione R-A09/R-A10 — gate locali canonici, Compose e candidato TEST, 2026-10-02 UTC

**File modificati:**
- Questo task: soltanto Execution, Fix e Handoff, per registrare verifiche già eseguite dal root/owner e adjudicate indipendentemente.
- [Ledger locale portabile](evidence/TASK-143/android-ra09-ra10-local-gates-ledger.json) — conteggi per 72 classi/XML, hash di ricevute e manifest, skip, warning, artefatti e limiti; SHA256 `b942f44cad2a4b60ccc43c6e3be522d97b7261526b3e5a6415a4aba722fc19dc`. Nessun APK, framework runtime o contenuto protetto copiato nella documentazione.

**Azioni eseguite:**
1. Riletti MASTER → TASK-143 → ricevute salvate. Stato `FIX` e Planning invariati: SHA256 del prefisso prima di Execution `8b53c975c9c6a50ab4fc58d0427c2cf1af8d475e5c3cef73c4e91f6226bb1231`. Freeze sorgente `7fa000b8972bec43bef49c0ed3f36956a1f966fad10f019ae587b6bea669c6de`, HEAD `9306e8a7ab3f96b95d9b844e03f624c785df5c96`; verificati **263 input pubblici più 4 metadati pubblici**, senza variazioni. Nessun nuovo runtime, ADB, operazione Git o modifica a sorgenti/test/build in questo aggiornamento.
2. Canonico `assembleDebug test lint` offline, exit 0: **72 XML nuovi, 1.077 ID unici = 1.070 PASS / 0 FAIL / 0 ERROR / 7 SKIP**. Tutti i 1.040 ID/status precedenti sono preservati; le 37 aggiunte Recovery sono PASS. Gruppo owner rilasciato e assente alle `22:42:29.044619 UTC`, nessun segnale. Eseguita la variante JVM debug, nessun claim di test release. Receipt actual `cc9fb1fca028e5071449ba53ed17cfaafab2a82bb49adeeb894453d8e19d7707`; review indipendente `c6c5d25e23207f30add45bb180dc10db3c3e827970c0982f2c09f0fc96bfaec4`.
3. Lint **39 Warning / 0 Error / 0 nuove firme**, confrontando tutte le firme normalizzate con la corretta baseline locale di 54, senza whitelist. Le 15 assenze sono notifiche di aggiornamento dipendenze (14 GradleDependency, 1 AndroidGradlePluginVersion) nel run offline: non sono upgrade o fix di warning. I due UseKtx già accettati restano nella baseline. Nessun nuovo warning Kotlin/deprecation; due messaggi JVM CDS sono censiti separatamente. Il confronto storico E4 non è la baseline cronologica pertinente.
4. `assembleDebugAndroidTest` exit 0, rilascio `22:42:49.511004 UTC`: eseguiti realmente **kspDebugAndroidTestKotlin e compileDebugAndroidTestKotlin**; packaging/assemble UP-TO-DATE. APK androidTest `847aee447925205bca30c2e17a6ab1031e6307ab6f01b7c9caebbda471ee4a41` riutilizzato con byte storici identici: questo step prova compilazione, non nuovo packaging né esecuzione Compose. App canonica `d24f8c5ad4aaf52721156c139207e4ae2110713c83c6987b0546b63c5075ed7e` e APK test copiati prima di qualsiasi override TEST.
5. Successivo collaudo **Compose effettivo: 5/5 PASS**. Cinque metodi ufficiali corrispondenti al sorgente, ciascuno code 0, `OK (5 tests)` e instrumentation code -1; pair canonica d24f/847a sul nuovo AVD sintetico owner `Codex_WECHAT010_Compose_API35_20261002`, solo `emulator-5580`, nessun accesso al device autenticato 5556. Il raw mantiene `ownedSerialAbsentAfter=false` alla lettura immediata e cleanup `SIGTERM` del proprio gruppo, già assente al rilascio `23:00:25.019428 UTC`. La proiezione terminale salvata alle **23:01:54.785484 UTC** conferma successivamente il seriale assente, senza ulteriori kill/wipe/restart/retry. Receipt actual `e3ecce13179a92144cbfd402d24474c158747e304ae6dbce8fa5ad9fc9367271`; supplemento immutabile `236bed2d58bafe1e60f742babace933506aee34c5552b4028708e71ac9cee1b3` risolve la riserva della review iniziale senza riscrivere il false storico.
6. Candidato TEST separato **`bd0a32bd030d653862791df3454060492f4dc94d358f1ec6f2724cb085b998fa`**, build exit 0 e quattro helper metadati exit 0; tutti e cinque i gruppi owner rilasciati, nessun segnale/timeout, fine `23:16:17.207456 UTC` entro deadline. Receipt actual `68a17cea0ac6565d8066bb9cc853384168beeafe8ad19bd0d0e7980652b69f79`, review `36bf7e04ce09f25d93b7491e743ac3628b54e5d22512c546252a39c41242ef30`. Metadati/firma coincidono con il precedente 96b preservato; pair canonica invariata. Il verifier autorizzato attesta profilo corretto, configurazioni primaria/privata invariate e ripristino esatto di esistenza/byte/mode di local.properties; questa lane ha letto solo ricevute e booleani, non valori protetti. Nessuna installazione del nuovo TEST, auth, READY, convergenza o E2E provati; nessun futuro hook DEBUG incluso.

**Check obbligatori:**

| Check | Stato | Evidenza / limite |
|---|---|---|
| Build Gradle | ✅ ESEGUITO nel gate sorgente salvato | Canonico e compilazione androidTest exit 0; nuovo run N/A per questo aggiornamento solo documentale |
| Lint / static | ✅ ESEGUITO nel gate sorgente salvato | 39 warning, 0 errori, 0 nuove firme rispetto alla baseline locale54; nuovo run documentale N/A |
| Warning nuovi | ✅ ESEGUITO nel gate sorgente salvato | Kotlin/deprecation 0; CDS JVM2 separati; nuovo run documentale N/A |
| Coerenza Planning | ✅ ESEGUITO | Stato FIX/prefisso8b53 invariati, sole sezioni consentite e ledger; 263+4 input pubblici invariati |
| Criteri di accettazione | ✅ ESEGUITO per queste verifiche locali; ❌ NON ESEGUITO per i gate successivi | Evidenza locale CA-10 aggiornata; CI exact-SHA e accettazione autenticata/convergenza restano al root/coordinator, nessun DONE |

**Baseline regressione TASK-004:**
- JVM/Robolectric: DefaultInventoryRepositoryTest 218 = 217 PASS + 1 SKIP; DatabaseViewModelTest 61 PASS; ExcelViewModelTest 52 PASS; Recovery 111 PASS. Sono parte dei 1.077 totali, distinti dai 5 test UI Compose.
- Nessun test aggiunto o modificato in questo aggiornamento documentale. I 7 skip storici restano invariati: fixture live WECHAT-004, benchmark opt-in, realtime live, tre audit Excel dedicati e workbook debug opzionale assente. Casi/categorie e motivazioni sanificate sono nel ledger; nessuna fixture protetta letta.

**Incertezze / Handoff notes:**
- Nessuna incertezza sui conteggi locali; autenticazione, READY e convergenza sul candidato non sono valutate da questi gate. Root resta owner di commit/PR/CI esatta e del coordinamento live.
- Questa registrazione aggiorna i gate locali lasciati futuri negli snapshot storici seguenti, che restano preservati; non cambia Planning né dichiara chiusura globale.

### Esecuzione R-A10 — GREEN442 attempt02 effettivo e review indipendente, 2026-10-02 UTC

**File modificati:**
- Solo questo log Execution; produzione, test, build e Planning restano congelati. Nessun nuovo fix o runtime avviato dall'esecutore sorgente.

**Azioni eseguite:**
1. Registrato il singolo comando mirato eseguito dal root/owner nel namespace `evidence/android-ordinary-delta-proof/zero-green-targeted-attempt02`: actual start `22:18:50.214201 UTC`, command end `22:19:41.962951 UTC`, rilascio `22:19:42.022888 UTC`, exit `0`. PID/PGID `74902`, segnali cleanup `[]`, processi del gruppo residui `[]`, gruppo assente al rilascio. Nessun retry o comando aggiuntivo.
2. XML ufficiali: **442 ID unici immutati, 441 PASS, 0 FAIL, 0 ERROR, 1 SKIP condizionale storico**. `ShopSyncRecoveryCoordinatorTest`: 111 PASS; `DefaultInventoryRepositoryTest`: 217 PASS e 1 SKIP; `DatabaseViewModelTest`: 61 PASS; `ExcelViewModelTest`: 52 PASS. Le cinque failure del primo442 sono ora PASS; gli altri 436 PASS e lo stesso skip restano invariati. Gli 8 guard ZERO/default0/absent/rowloss restano PASS.
3. Lo skip riguarda soltanto `wechat 004 real local Supabase fixture converges through production incremental apply`, condizionato alla disponibilità di `WECHAT_004_LOCAL_E2E_FIXTURE`. Non sono stati introdotti nuovi skip né letti contenuti della fixture protetta.
4. Verificata la review formale indipendente `evidence/independent-review-resumed/android-ra10-zero111-green442-attempt02-adjudication/receipt.json`, SHA256 `30584650e1a54be41a54cb4e8eda4c79ede9448e48e0b28767cf24b0d09539fc`, verdict `BOUNDED_442_GREEN_WITH_ONE_KNOWN_CONDITIONAL_SKIP`, nessun finding bloccante. Receipt del runner `ad82a50f9d1d8f7c08cf8584e2b5763d7c1982e1fe2dda8022a6ba870c653e01` e source review precedente `e04afc2ab5c077f7c2090145d551a2814258cbb87b25c46ab973631e43f5236d` preservati.
5. Confermati i 168 pin prima/dopo il comando: 157 file di produzione, 4 test e 7 input build pubblici; HEAD `9306e8a7ab3f96b95d9b844e03f624c785df5c96` invariato. Nuova verifica locale documentale: i 157 sorgenti e i 4 test corrispondono ancora al freeze `7fa000b8972bec43bef49c0ed3f36956a1f966fad10f019ae587b6bea669c6de`; request `21cf832ebb89014e4ba9b0cd958ebb2efaca94c159567504793f0680206f8091`. Planning prefix `8b53c975c9c6a50ab4fc58d0427c2cf1af8d475e5c3cef73c4e91f6226bb1231` invariato.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | ❌ NON ESEGUITO | `assembleDebug` finale ancora da eseguire; compilazione del comando unitario non sostituisce questo gate. |
| Lint | ❌ NON ESEGUITO | Gate finale separato ancora da eseguire. |
| Warning nuovi | ❌ NON ESEGUITO | Verifica completa dei warning finali ancora da eseguire. |
| Coerenza con planning | ✅ ESEGUITO | Cinque riparazioni fixture autorizzate, invarianti e review formale confermati; questo aggiornamento modifica solo Execution. |
| Criteri di accettazione | ❌ NON ESEGUITO complessivo | Il solo gate mirato442 è verificato; full suite/build/lint/CI/nuovo APK TEST/E2E e collaudo autentico restano separati e aperti. |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: quattro classi JVM/Robolectric pertinenti sopra indicate, 441 PASS e un solo skip condizionale storico. Sono test dati/repository/ViewModel, non test UI Compose/Espresso.
- Test aggiunti/aggiornati: nessuno in questo aggiornamento; le cinque riparazioni setup già autorizzate sono documentate nell'entry precedente e ora verificate dal ritest effettivo.
- Limiti residui: nessuna attestazione di full suite, assemble/lint, CI, nuovo artefatto TEST, E2E, prestazioni o causa del precedente fallimento live.

**Incertezze:**
- Nessuna failure nel perimetro442. I gate e il collaudo esterni al perimetro non sono stati eseguiti da questo comando.

**Handoff notes:**
- Stato task resta FIX, nessuna dichiarazione DONE. HOLD su tutti i sorgenti/test/build; root mantiene Git, Planning e i successivi gate/runtime. Il primo442 fallito e la relativa adjudication restano evidenze storiche immutabili.


### Esecuzione R-A10 — primo442 reale e cinque setup repair circoscritti, 2026-10-02 UTC

**File modificati:**
- `data/ShopSyncRecoveryCoordinatorTest.kt` — quattro nuovi metodi soltanto: fake ACK prodotto aggiunge overload shop-aware4arg/assertSHOP e inoltra al proprio3arg; due noEvents abilitano esplicitamente emptyTailConfigured solo localmente, default globalefalse preservato; push History reale entra nella managed tracker.withBusinessDataScopeFlight. Tutti111ID/asserts precedenti e valori attesi invariati, solo nuovo assertSHOP; altri107corpi e tutti83originali byte-identici.
- `data/DefaultInventoryRepositoryTest.kt` — solo setup A del negativo139: hash account/device reali sintetici, emptydomain/id/version/identity/catalog canonici e binding attuale; stessoA7/typedwm7/event8 e serverC deliberatamente incoerente domain7 vs paginaasOf8. Commento descrive questo fault di fence invece di affermare impossibilità generale del delta ordinario. Tutti assert originari/wm7/latch/rollback/fence/spies invariati; altri217corpi byte-identici.

**Azioni eseguite:**
1. Il primo comando442 è stato lanciato direttamente dal ROOT sotto GO, non da questa lane: actualstart21:41:50.100120→commandend21:42:44.083152→release21:42:44.148504, exit1/ownPG54838 assente/signals[] senza timeout. OfficialXML4class442unici verificati:436PASS5FAIL0ERROR1SKIP condizionale storico. Recovery111=107PASS4FAIL; Default218=216PASS1FAIL1SKIP; Database61/Excel52PASS. DesiredZERO2 e quattro guard scoped/rowloss sono PASS ufficiali, non basta per442GREEN. Result9a867e55/logf6ef4721 e namespacezero-green-targeted restano immutabili;157+4test/build7/HEAD pre/post invarianti verificati dal ROOT.
2. Triage read-only indipendente producer4aa90aab/NOTEe3a9ec5c e reviewer91ed8294: i cinque fail si fermano nei confini setup descritti sopra, prima dei relativi assert funzionali. Product transport4arg non intercettato dalla Kotlin delegation; due paginevuote non dichiarate; History call fuori lease; Default139 fake identity e manifest prima della richiesta evento. Nessuna productionpatch dedotta; downstreamasserts ancora da ritestare.
3. Dopo MASTER→Planning8b53/decisione3e7afb→sorgenti, applicate solo le cinque setup repair autorizzate. Eccezioni letterali limitate ai quattro nuovi corpi Recovery e al setup/commento Default139; nessuna weakening/protocol/schema/API/queue/UNMANAGED/globalfakepage fallback. Produzione157 e Inventory39dc restano esattamente78bb.
4. Verifiche statiche: confronto dei111ID/order, allassertcalls/expectedvalues preservati (+soloSHOP), altri107corpi Recovery/original83/217Default invarianti. La sostituzione inversa dei cinque metodi modificati ricostruisce esattamente entrambi i file precedenti78bb, senza cambi fuori metodo. git diff --check PASS statico; nessun nuovo runner.
5. Freeze separato `evidence/android-ordinary-delta-proof/zero-green-preparation-attempt02/`: source7fa000b8, request21cf832e, fullpatch1bb06597, fixture-only39ef1a18, invariance/inverse6482f41c, card6e829c51; Recovery111c5166c7d/Default218e4bd6f98, DB/Excel invarianti. Sourcehold, staticreview e nuovo GO richiesti. Stessi442 dichiarati; launcher02 rootdirect/nativehelper, ONEoffline/no-daemon/worker1/in-process/600s/reserve90/min270 a spawn. Canonicalcardretarget source-only distinto, nessuna esecuzione di questa lane.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO | Compile della precedente4class completata; assembleDebug finale distinto pending, nuovo testsetup non compilato |
| Lint | NON ESEGUITO | Gate finale pending |
| Warning nuovi | NON ESEGUITO | Audit finale warning/Kotlin/deprecation pending |
| Coerenza Planning | ESEGUITO staticamente | Prefix8b53 preservato, produzione invariata e sole eccezioni setup autorizzate |
| Criteri / TASK004 | NON ESEGUITO completo | Prima pertinente442 effettiva436PASS5FAIL1SKIP; nuovo ritest442/canonico/TEST/CI/live pending |

**Incertezze/Handoff:** nessun nuovo442GREEN o acceptance autentica; sourceprod invariato e ZEROguardPASS non provano i cinque flussi downstream bloccati dal vecchio setup. Se gli assert dopo la repair trovano un difetto funzionale, conservarlo e riportarlo senza adattare assert o productionfuoriscope. Namespace/source/review/GO nuovi obbligatori; oldreceipt/failures preservati. Task FIX, nessun Git/Planning/device/config/dati reali modificati.


### Fix statico ZERO v2 — contesto effettivo della lease, 2026-10-02 UTC

Advisory indipendente source-only identifica il receiver CoroutineScope esterno di withContext(IO): il nome coroutineContext nella predicate annidata può riferirsi al contesto antecedente alla lease managed del child flight. Il positivo managed EMPTY0 deve leggere la lease corrente effettiva. Root conferma il delta come applicazione del requisito Planning4ea6, senza scope nuovo.

**File:** solo InventoryRepository nella nuova predicate: lookup lease1 e tre ensureActive usano esplicitamente kotlinx.coroutines.currentCoroutineContext(). Nessun altro contesto/metodo o111testbody/assert modificato. Inverse delle quattro espressioni ricostruisce esattamente la leaf f155;156 altre leaf produttive e quattro file test invarianti. Packet f155 storico conservato e vietato al runtime.

**Freeze corrente:** zero-green-preparation-v2/source-freeze.json78bb2d85; request60daa1a5, fullpatchb2e5c865, ZEROpatchceeeaaa7, context-only989c0ca9, inverseproofe95b2d97, card994abd93. Runtimefalse; conteggio richiesto442 dichiarati (111/218/61/52), non eseguiti. Sourcehold; review statica formale e nuovo GO richiesti. Root/runtimehelper preparano launcher separatemente, nessun processo reale avviato da questa lane. Canonical card e7b7 resta campo HISTORICAL con retarget pending.

**Check:** coerenza Planning/diff-check ESEGUITI staticamente; build/lint/warning/GREEN/TASK004/TEST/CI/live NON ESEGUITI sul nuovo source. Nessuna attribuzione live da advisory o fixture.


### Esecuzione R-A10 ZERO — fix post RED funzionale e freeze111, 2026-10-02 UTC

**File modificati:**
- `data/InventoryRepository.kt` — ammissione baseline canonica wm0 soltanto con entità watermark scoped realmente presente; CAS dell'esatta entità/presenza nei writer changed e noEvents. Receipt presente con wm mancante/invalidato rifiutato. Bootstrap catalogcount0 esentato soltanto dalla lease corrente managed Task126, owner/store/shop/localStore validati dal binding gate, device esistente, receipt/fence/journal/pending/fullcanonical+fullphysical provati in transazione locale read-only e riletture finali; cancellation/scope-changed propagati. Nessuna RPC o writer dalla predicate, selectedshop non derivato dalla baseline.
- `data/ShopSyncRecoveryCoordinatorTest.kt` — quattro guard actualRoom aggiunti: managed matching/unmanaged/foreign owner-store-localstore; typedwm mancante e checkpoint malformato; perdita row0 durante il vero marker C1; stessa race noEvents C0. Corpi/assert/ID107 precedenti e helper default invariati; classe111.

**Azioni eseguite:**
1. Letti MASTER→Planning→sorgenti/gate/DAO watermark. Actual ZERO03 già eseguito e confermato root: recovery Activated0/reopen/fullproof effettivi; XMLb6dd29ee due FAIL funzionali al requisito manualfalse/CONVERGENCE e bootstrap_required nel vero AutoSync, non setup/compile. Result24ee5d5b, independent257b9b38, release20:58:58.070751/ownPG35439 assente restano storici. Solo dopo Planning4ea6b878/decisione731e1c43 applicato il nuovo delta autorizzato.
2. Catturata nullable SyncEventWatermark prima di scalar default0; verified paths ricevono entità nonnull e confrontano DAO.get==capturedEntity. Baseline senza receipt conserva i percorsi legacy dichiarati; una receipt attivata non può adottare il default di riga mancante. Full A/C e le prove PRICES retained/samegeneration/History/shadow restano quelle R-A09/R-A10; no mapperforce/schema/deps/key/API/queue reset.
3. Verifiche statiche:156 leaf produzione vsde909 invarianti, unico delta produttivo InventoryRepo;107 bodyhash immutati e DefaultRepositorybb05 (sole due decisioni139) immutato. Nuovi4 dichiarati producono111Recovery +218Default +61DatabaseVM +52ExcelVM =442 casi dichiarati; NON eseguiti/PASS. git diff --check eseguito senza errori.
4. Freeze separato fuoriGit `evidence/android-ordinary-delta-proof/zero-green-preparation/`: sourcef155b018, request1fccb2c8, fullpatchcf9281ef, ZERO-onlyeeb9d05f, R10-vsR09277cb4a1, invariancef29b0297, card052de142. Snapshot prima/dopo congelati, sourcehold. Unico comando GREEN richiesto:4class offline/no-daemon/max-workers1/in-process, slot600s include90cleanup, stima60–180s non garanzia. ROOT possiede avvio futuro, review indipendente e nuovo GO ancora richiesti. Card canonical/TEST retarget source-only in corso fuoriGit; e7b7/96b/18 restano storici.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO | Nessun runner dopo nuova patch ZERO; assemble finale pending |
| Lint | NON ESEGUITO | Gate finale pending |
| Warning nuovi | NON ESEGUITO | Nuovi byte non compilati, nessun claim assenza warning |
| Coerenza Planning | ESEGUITO staticamente | Prefix4ea6 preservato, delta autorizzato731e e source/testinvariance |
| Criteri / TASK004 | NON ESEGUITO | 442 dichiarati soltanto; GREEN/canonici/TEST/CI/live ancora pending |

**Incertezze/Handoff:** compile e guard nuovi ancora da verificare; nessuna acceptance live o attribuzione registry154. Costo della proof completa resta da misurare dopo gate. Source immutable in attesa review/GO; nessun runtime/build/device/DB reale/retry/Planning/Git eseguito dall'executor in questo pass.


### Esecuzione ZERO attempt02 e preparazione fixture-only attempt03 — 2026-10-02 UTC

**File modificati:**
- `data/ShopSyncRecoveryCoordinatorTest.kt` — soltanto il nuovo helper ZERO: legge il journal reale creato dal seed, verifica owner/store/shop/device/mode mismatch-confirmed/phase REQUIRED/reason mismatch, copia il solo blockingEventId a null e verifica rilettura esatta prima della recovery EMPTY0. Il seed generale resta blocking40; tutti107 corpi/assert dei test, DefaultRepository fixture e tutti157 file produttivi de909 invariati.

**Azioni eseguite:**
1. Attempt02 con launcher12fe/b5626fc9, source/card reviewafa13f3d e GOabb1e94c: unico comando offline2selectors; actualstart20:32:29.536123UTC, commandend20:33:21.577189, actualrelease20:33:21.634676 prima deadline20:35:56.082763. OwnPID/PGID22456 remaining0/groupExistsfalse e terminale concluso, freeze de909/test2997 invariati. Nessun retry.
2. **NON RED desiderato**: XMLc05c81b9 ufficiale2unici/0PASS2FAIL0ERROR0SKIP, entrambe java.lang.AssertionError nel setup helper3765, prima dell'effettiva Activated/reopen e degli assert ordinari. Il journal sintetico del seed ha blockingEvent40 mentre targetmax0; il guard reale273 rifiuta correttamente recovery_checkpoint_before_blocking_event. Receipt571bbdad e adjudication81dc323b distinguono SETUP_FIXTURE_FAILURE_NOT_DESIRED_ZERO_RED; log/source/XML originali preservati in namespaceattempt02. Nessuna correzione funzionale ZERO autorizzata da questo esito.
3. Dopo Planning rootfed687bb/decisione1aa631fb, applicata soltanto correzione helper fixture descritta sopra. Inverse removal dell'esatto nuovo block ricostruisce tutto il precedente file2997;107 nomi/bodyhash immutati,157productionde909 immutati. Nuovo packet separato attempt03: source82b12221, request22867c6e, test107a1833ef7, fixturepatchcd693183, invariancec8a93681, cardb34a59aa. Stessi due selector, nuovo outputattempt03.
4. Richiesta nuova review statica fixture/card e poi derivato launcher12fe con soli literal binding nuovi e15modelli puri; ancora nessun runner attempt03. **Root possiede il prossimo avvio diretto**: questa lane prepara/hold e non lancerà autonomamente il job neppure ricevuto un GO owner.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO | assembleDebug non lanciato; compilazione del comando mirato riuscita, non equivale al build gate |
| Lint | NON ESEGUITO | Gate finale pending |
| Warning nuovi | NON ESEGUITO | Audit finale warning pending; non dedotto dal setup assertion failure |
| Coerenza con planning | ESEGUITO staticamente | Prefixfed687bb preservato, solo helper fixture; guard recovery produzione invariato |
| Criteri di accettazione | NON ESEGUITO | Activated0/desired ZERO assertions non raggiunte; nuovo RED e GREEN/canonical/TEST/CI/live pending |

**Baseline TASK-004:** soltanto due JVM/Robolectric selezionati effettivamente eseguiti, falliscono entrambi nel setup. Nessun successo candidato attribuito. Tutti107 body/assert originali di questo packet e le sole due fixture139 preservati; future438 dichiarati devono ancora essere eseguiti sul source finale.

**Incertezze/Handoff:** il candidato wm0/bootstrap resta statico, finché una fixture coerente arriva a recoveryActivated e al drain. Non indebolire il guard checkpoint-before-blocking, consenso, scope, parent/digest/physicalproof. Namespace/GO nuovi obbligatori; ROOT launch ownership confermata, nessuna attività device/DB/config/reale.


### Esecuzione ZERO attempt01 e preparazione compile-only attempt02 — 2026-10-02 UTC

**File modificati:**
- `data/ShopSyncRecoveryCoordinator.kt` — solo helper interno di due righe `encodeRecoveryCheckpointJson`, accanto al decoder, delega allo stesso `RECOVERY_JSON.encodeToString(value)` già usato da activation. Riparazione compile-only autorizzata root6ddfe319; condizioni wm0/bootstrap/proof/mapper e tutti107 test invariati.

**Azioni eseguite:**
1. Review statica test107 c1cf5e26 APPROVED, launcher finale ad81a3ef/a496dd83 APPROVED; guard min180 e deadline esattamente legata al GO (interval≤600), cleanup ownedPG in finally. Quindici check di modello puro con OS/process fittizi PASS; nessun processo reale avviato dai modelli. Launcher precedenti preservati.
2. Unico comando ZERO2 autorizzato GO9c2801c9: actualstart20:09:27.657419UTC, Gradle exit1/commandend20:09:49.029917; actualrelease20:09:49.105285 prima harddeadline20:18:19.746733, ownPID/PGID15061 remaining0/groupExistsfalse, terminale concluso. Preflight namedheavy0 e157/two testfiles esatti; niente retry/device/DB/network e sorgente/test postrun identici.
3. **COMPILE_FAILURE, NON RED funzionale**: `app:compileDebugKotlin`, InventoryRepository5042 unresolved `encodeRecoveryCheckpointJson`, definizione assente. Nessun XML nuovo/test eseguito; XML storico non riciclato. Receipt9f747e8d, adjudication5f83229b, log9982396c fuori Git in `evidence/android-ordinary-delta-proof/zero-red-targeted/`. Questo esito non autorizza alcuna correzione funzionale ZERO.
4. Dopo autorizzazione compile-only, verificato che156 leaf produttive restano byte-identiche al b55/a7a e la rimozione dell'esatto helper ricostruisce la vecchia leaf coordinator; test107 SHA2997c893 e DefaultRepositorybb05bb7a invariati. Nuovo packet separato `zero-red-preparation-attempt02`: freeze de909a5e, request1d8ca2d0, compile-only patchb4d6bd01; stessi due selector e nuovo outputattempt02. Review/literal-binding del launcher e nuovo GO ancora richiesti; nessun secondo comando avviato.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO | assembleDebug non lanciato; comando mirato effettivo fallisce compilazione pre-test |
| Lint | NON ESEGUITO | Gate finale dopo source finale |
| Warning nuovi | NON ESEGUITO | Compilazione fallita; nessuna conclusione di assenza warning |
| Coerenza con planning | ESEGUITO staticamente | Prefix9a3676a5 preservato, helper stesso codec soltanto; ZERO produzione vietata prima RED |
| Criteri di accettazione | NON ESEGUITO | Tentativo compile failure non prova due casi funzionali; GREEN/canonical/TEST/CI/live aperti |

**Baseline TASK-004:** nessun test nuovo eseguito nel tentativo pre-test. Request futura438 dichiarati resta da ritargettare dopo vero RED/fix/source finale. I107 recovery e le sole due fixture139 restano intatti.

**Incertezze/Handoff:** il test ZERO deve ancora raggiungere le proprie assertion funzionali. Nuovo freeze/review/GO per attempt02; nessun riuso GO scaduti né retries impliciti. Nessuna acceptance o attribuzione live dedotta dal comando.


### Preparazione R-A10 ZERO e guard finali — 2026-10-02 UTC, test-only / nessun runner

**File modificati:**
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/ShopSyncRecoveryCoordinatorTest.kt` — sei regressioni aggiunte ai101 congelati: recovery reale EMPTY/max0 e primo evento1 tramite repository; stessa recovery tramite AutoSync senza bootstrap skip; controllo default0 senza receipt mantiene bootstrap; covered product body corrotto con clean fingerprint già C richiede FAIL/rollback; History pending→DEFER→push tombstone ACK reale→SELF C/riapertura; noEvents immediato dopo Activated EMPTY C0 resta READY/noWork. Helper zero dedicati, logger opt-in e fake empty-event-page opt-in (defaultfalse); nessun corpo dei106 precedenti modificato.

**Azioni eseguite:**
1. Letti MASTER/Planning/sorgenti. Planning root5b76de04 e decisione8a538bd6 autorizzano preparazione; produzione b55 immutata fino al nuovo RED effettivo.
2. Preservati separatamente test101 SHA9167cc4d, packet b55/31e5 e successivo packet106 bf077980/d919cbea/test8efd3b45. Verificati157 file produzione byte-identici a b55, due fixture DefaultRepository139 immutate dal freeze,101 e106 test bodies/hash immutati.
3. Fixture nuova usa recovery realmente Activated/reopen, baseline canonica C0, watermark0, binding/device correnti, journal assente e FULL active proof. Primo HISTORY_CHANGED1 ha fence global1/history1/catalog0/prices0; i due assert desiderati discriminano rispettivamente falso latch e bootstrap_required. NoEvents0 restituisce esplicitamente pagina vuota fenced coerente, senza inventare eventi/summary/marker PASS.
4. Packet107 immutabile fuori Git: `evidence/android-ordinary-delta-proof/zero-red-preparation/source-freeze.json` SHAa7a12cc4; request136134e3, test2997c893, test-only patch86dcdb22, invariance499f2b66. Il solo comando RED richiesto seleziona esattamente i due casi primo-evento ZERO, offline/no-daemon/max-workers1/in-process; richiesta600s, stima60s e cleanup90s inclusi. Nessun runner avviato; nuovo review/GO host obbligatorio dopo rilascio lane iOS.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO | Preparazione test-only; runner non autorizzato in questa lane |
| Lint | NON ESEGUITO | Gate finale dopo freeze production finale |
| Warning nuovi | NON ESEGUITO | Compilazione non ancora eseguita |
| Coerenza con planning | ESEGUITO staticamente | Prefix5b76de04 preservato, produzione157 b55 byte-identica e test precedenti immutati |
| Criteri di accettazione | NON ESEGUITO | RED/GREEN nuovi e successivi canonical/TEST/CI/live ancora aperti |

**Baseline regressione TASK-004:** nessun nuovo test eseguito in questa preparazione. Future GREEN4class retarget obbligatorio: Recovery107 + DefaultRepository218 + DatabaseViewModel61 + ExcelViewModel52 =438 test dichiarati in sorgente; non conteggio XML né risultato. Conditional fixture skip storico resta distinto.

**Incertezze:** nessuna attribuzione live al caso count mismatch154; proof O(whole generation) richiede misura dopo gate. Eventuale productionfix ZERO resta vietato prima di RED ufficiale e conferma root; gli altri quattro guard sono esclusi dall'unico comando RED.

**Handoff notes:** review indipendente dei nuovi byte/fixture e nuovo GO host; catturare XML unici2, log, exit/tempi/source invariance, ownPG terminal0 e actualrelease entro deadline. Nessun secondo comando o retry implicito; nessuna modifica Planning/Git/device/config/sessione/dati reali.


### Preparazione implementation R-A10 — 2026-10-02 UTC, nessun runner dopo RED

**File modificati:**
- `data/InventoryRepository.kt` — window ordinaria completa bounded prima del writer; CAS dell'intera baseline/generation, binding, device, watermark e journal; DEFER da conteggi reali pending; apply/ledger/prova completa C/baseline originale C/status/watermark atomici. NoEvent richiede ora anche prova fisica A, oltre al marker, e ricontrolla pending prima di un latch.
- `data/ShopSyncRecoveryCoordinator.kt` — estrazione interna nello stesso file della prova canonica/fisica preesistente; riuso parent proof R-A09; closure bounded per cascade/restore/prezzi retained/immagini. Active History ha proiezione attiva più scan di tutti gli shadow deleted con LEFT JOIN, primo cursor nullable anche per UID negativi/zero, UTC6/calendario/versione/ref rigorosi; nessuna prova inventata del vecchio body.
- `data/HistoryEntryDao.kt`, `data/SyncRecoveryModels.kt` — pager active History e lookup manifest same-generation/domain IN massimo500 ID; nessuna entity/schema/migration/dependency.
- `data/ShopSyncRecoveryCoordinatorTest.kt` — 18 guard aggiunti dopo83: CAS entity/generation, cancellazione dopo scritture/abort SQL e rollback, pending e push reali/SELF C, restore e prezzo corrotto, History tomb/reopen/delta, orphan/UID negativo/oltre500/UTC invalido, baseline davvero vuota valida, C opaque distinto, fisico scoperto corrotto e noEvent tamper/pending. Gli80 test e i3 RED originali restano identici negli ID/body.
- `data/DefaultInventoryRepositoryTest.kt` — sole due decisioni root139: negativo watermark8→7 più invariance assertions (df2d70bd); positivo fixture canonical empty/domain/catalog/identity/scope e binding con factory opt-in, stessi assert noWork/wm7/noJournal/noGap (ac7e89fe). Gli altri test non cambiano.

**Azioni:** actual RED confermato dal parent d2892d0d prima delle patch. Applicate clausole designcc6cb419 e decisioni History/ACK9cb6ec91/noEventac7e89fe. Nel changedpath A richiede FULL canonical ledger/relazioni/parent proof, mentre C richiede FULL canonical + FULL physical eligible dell'intero store prima del commit; questo consente ACK locale giàC senza fidarsi del flag clean. I mapper esistenti restano invariati. Stage/recovery mantiene il comportamento originale e i limiti History configurabili. Budget window100 eventi/2000 righe/64 targeted call/16MiB, History row512KiB, keyset/IN500. Nessuna RPC dentro il writer, reset/scope/key/queue cleanup o nuovo endpoint.

**Check obbligatori:**
| Check | Stato | Evidenza |
|---|---|---|
| Build Gradle | NON ESEGUITO | Nuovo GO richiesto; nessun runner dopo RED |
| Lint / warning | NON ESEGUITO | Compile/lint ancora da verificare |
| Coerenza Planning | ESEGUITO staticamente | Design e decisioni root citate; source freeze/card esterno |
| Criteri / TASK004 | NON ESEGUITO | GREEN/review/canonico/TEST/CI/live ancora necessari |

**INCERTEZZE:** la compilazione e i101 guard sono soltanto preparati; nessun PASS inferito. La prova completa A/C può costare O(whole generation), soprattutto noEvent; misure successive necessarie, nessuna promessa3s. Causa live registry154 non attribuita da questi test. Pacchetto fuori Git `evidence/android-ordinary-delta-proof/green-preparation/`; root possiede review/Planning/Git/canonico/TEST/CI/live. Task resta FIX.

### Preparazione R-A10 — 2026-10-02 UTC, nessun runner

**File modificati:** solo `app/src/test/java/com/example/merchandisecontrolsplitview/data/ShopSyncRecoveryCoordinatorTest.kt`, aggiunta di3 regressioni e helper isolati; produzione157 foglie invariata rispetto al freeze R-A09 SHA39dffac3.80 corpi/ID test precedenti conservati, nuova classe83.

**Azioni preparate, non eseguite:**
1. Actual recovery→reopen Room→repository reale sotto tracker/lease READY→CatalogAutoSync reale/device attivo: CATALOG_CHANGED43 con vero product body aggiornato, checkpoint/target/marker canonici coerenti. Desiderato apply, receipt C43, READY/no journal/no callback e secondo trigger no-work senza full page; atteso RED funzionale `CONVERGENCE_PROOF_REQUIRED` dopo business apply.
2. PRICES_CHANGED43 per parent tombstoned già provato da recovery mista piccola (1 prodotto attivo non correlato evita il bootstrap), seguito da CATALOG_TOMBSTONE44 ridondante del medesimo parent: catalogmax44/pricesmax43/capturedMax44. Nuovo adapter bounded restituisce il fence materializzato effettivo del dominio, anziché echo di un minimo impossibile. Desiderato full ledger3prezzi/1 fisico, receipt C44/READY/no journal/no callback; atteso RED funzionale `MISSING_REMOTE` dal generico skipped-parent.
3. Controllo negativo sul medesimo checkpoint canonico: targeted price body corrotto con parent sconosciuto e targeted parent assente. Desiderato rifiuto MISSING_REMOTE, vecchia baseline/watermark42 preservati, journal durevole e nessuna nuova price row/bridge/receipt. L'incoerenza della relazione è fault injection esplicita, non un marker inventato come proof.

Auth sintetica scoped, device authorization reale con transport controllato attivo, network verificata true, guard reale del tracker e no-op transport configurati; nessun fake SyncSummary o wrapper della readiness. AutoSync diretto su backgroundScope con debounce massimo e shutdown in finally; nessun polling/clock advancement/runtime app.

**Check:** coerenza Planning ESEGUITO staticamente; diff-check ESEGUITO. RED/GREEN/build/lint/warning/criteri/runtime NON ESEGUITI in questa preparazione; nuovo GO host richiesto. Card canonico/TEST esterna preparata in `evidence/android-ordinary-delta-proof/canonical-preparation/command-card.json` SHAe7b7a24c: runtimefalse e retarget obbligatorio sul freeze finale R-A10; gate18/APK96b storici preservati, profili protetti non letti.

**INCERTEZZA:** la fixture dimostra soltanto comportamento applicativo deterministico quando le letture rispettano i fence dichiarati; nessuna fattibilità live, tempo3s o causa registry154 dedotta. Productionfix solo dopo RED ufficiale e contratto/design approvato del parent. Root owner di Planning/Git/review/canonico/TEST/CI/live; task resta FIX.

### Esecuzione R-A10 — RED effettivo, 2026-10-02 UTC

Sul request201b5dd3/source975afb9f/test66050804 e produzione R-A09 byte-identica, lo slot root GO SHAb935f366 ha autorizzato soltanto3selector in un comando offline/no-daemon/worker1/in-process, deadline18:24:50.968420UTC con90s riserva. Review preparazione indipendente07015b3f e hash/prefight freschi verificati prima del runner; named-heavy0, nessuna qualifica performance globale.

**XML ufficiale: 3 test unici, 1 PASS / 2 FAIL / 0 ERROR / 0 SKIP.** Il controllo negativo unknown-targeted-parent passa e preserva il blocker/baseline. CATALOG fallisce sul requisito desiderato di non richiedere recovery, con `reason=convergence_proof_required`, dopo product apply/counters1 e watermark43. PRICES provatamente retained fallisce sul medesimo requisito con `reason=missing_remote`, dopo targeted price1/parent domain fence44 e physical-count/no-new-ref. Entrambi sono java.lang.AssertionError funzionali, non errori setup/compilazione o timeout; produzioneR10 non modificata prima/durante il RED.

Start18:18:20.075250, fine18:18:46.473974, release effettiva18:18:46.545584UTC (26,40s), exit1. Gruppo proprio78075 terminale/remaining0 senza segnali e senza terminazioni foreign;157 foglie produzione e test6605 invariati al termine,80 test precedenti immutati. Evidence fuori Git `evidence/android-ordinary-delta-proof/red-targeted/result-receipt.json` SHAaa34639924c1e53209bddc34a67cc51c5879525378618f51f88791675babb156, XML SHA1383a1ea788f7e62b11a52d7d69d83e2254e0202edc005eb39683b41f2d1a9b7, log SHA f555ca5c1d1d330402163ee7419f618d94cfa10b9594cca17bdfa4839d413895 e adjudication separata `red-adjudication.json`.

**Gate residui:** patch dopo conferma parent/contratto designcc6cb419 e nuova Planning DecisioneHistory, GREEN e regressioni di proof/dirty/fence/CAS/cancel/reopen, review implementazione, gate canonici/TASK004/TEST/CI/live ancora non completati. Nessun ulteriore runner o GREEN autorizzato da questo RED. Nessun claim causa live/3s/convergenza dal solo fake transport. Task resta FIX.

### Esecuzione R-A09 — 2026-10-02 UTC, preparazione dopo RED

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ShopSyncRecoveryCoordinator.kt` — separazione del ledger prezzi completo dal sottoinsieme fisico, con proof del parent nel medesimo manifest di generazione; applicazione pagine/tail, count di staging e readback fisico/relaunch coerenti.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SyncRecoveryModels.kt` — solo query DAO bounded dei parent product per generationId e lista remoteId; nessuna modifica entity/schema.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/ShopSyncRecoveryCoordinatorTest.kt` — regressione RED preservata e cinque controlli adiacenti; 80 test preparati, 74 test precedenti con ID e corpi byte-identici.

**Azioni eseguite:**
1. RED reale JVM/Robolectric della sola regressione retained-price: 0 PASS / 1 FAIL / 0 SKIP, `recovery_stage_apply_count_mismatch`, senza patch produzione. Start16:55:04.983558, fine16:55:27.352822, release16:55:27.462541 UTC; exit1, gruppo proprio46685 terminato, 157 sorgenti produzione invariati. Ricevute fuori Git `evidence/android-registry154-count-mismatch/red-targeted/`: result SHA9eb7a432 e adjudication SHA121ae5e4. Nessun difetto live attribuito dalla sola fixture.
2. Dopo autorizzazione del parent, il filtro esenta esclusivamente un parent noto nella stessa generazione e provatamente tombstoned; flag active deve concordare con deletedAt canonico. Parent sconosciuto/incoerente resta rifiutato. Manifest/count/digest di checkpoint e prezzi append-only restano completi. Nessuna modifica a repository ordinario, binding, device, scope, watermark, queue, consent, business key o API wire.
3. Lookup parent IN massimo500 UUID per batch, più binding di generazione (limite conservativo502, sotto999). Pager atteso riempie fino500 prezzi materializzabili attraversando raw page senza perdere ID eleggibili; scansione keyset bounded anche per count e readback, senza array globale né query per prezzo. Pagine RPC prezzi restano120.
4. Preparati controlli per parent attivo/95 retained+560 attivi su655 prezzi, parent esistente solo in altra generazione rifiutato, parent diventato tombstoned nel tail B con prezzo precedente e nuovo, cleanup/recovery dopo reopen e ordinary drain no-event senza relatch. Adapter paginato separato; fake single-page originale invariato e default del helper coordinator preservato.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | NON ESEGUITO | Slot host GREEN/canonico del nuovo delta non ancora autorizzato; nessun build avviato in preparazione. |
| Lint | NON ESEGUITO | Da eseguire sul freeze finale nello slot coordinato. |
| Warning nuovi | NON ESEGUITO | Da verificare con compilazione/lint del nuovo delta. |
| Coerenza con planning | ESEGUITO | Perimetro minimo R-A09, RED precedente alla patch, lookup bounded e guard fail-closed preservati; diff-check statico PASS. |
| Criteri di accettazione | NON ESEGUITO | CA-07/10/11 richiedono GREEN, regressioni pertinenti, review indipendente, gate finali e ritest autenticato coordinato. |

**Baseline regressione TASK-004:** regressione mirata RED eseguita; classe recovery80 e baseline repository/ViewModel pertinenti ancora da eseguire sul delta. Sono test JVM/Robolectric, non UI Compose/Espresso.

**Incertezze:** count mismatch di registry154 non ancora attribuito al dominio da metadata runtime. I conteggi read-only41347 totale/41252 active-parent/95 retained supportano la semantica del contratto, non provano la causa live. Se un parent prima tombstoned torna attivo nel tail B, la sola hash del manifest non ricostruisce payload prezzo: senza targeted event che lo recuperi, il readback fisico resta fail-closed. Nessun aggiramento introdotto.

**Handoff:** source freeze e richiesta GREEN fuori Git da consegnare al parent. Nessun runtime avviato dopo RED; root owner di Git/Planning/review/canonico/CI/TEST e coordinator5556. Task resta FIX, non DONE.

### Esecuzione R-A09 — GREEN80 effettivo, 2026-10-02 UTC

Sul freeze `green-preparation/source-freeze.json` SHA39dffac33e7a02916835f63e088febfe54badc6a8e1eb25351eef643c9ee6be6, patch SHA9d5f0f7e e richiesta SHA6debef09, il parent ha autorizzato un solo comando offline/no-daemon/max-workers1/in-process nello slot con deadline17:36UTC e90s riserva cleanup. GO SHA602de655, preflight fresco named-heavy0; nessuna qualifica di quiet/performance globale dedotta.

Comando `testDebugUnitTest --tests com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryCoordinatorTest`: **80 PASS / 0 FAIL / 0 ERROR / 0 SKIP**, exit0. Start17:28:19.891739, fine17:28:54.447137, release effettiva17:28:54.510209 UTC; 34,56s. PID/PGID proprio55539, remaining0, nessun segnale cleanup richiesto e nessuna terminazione foreign.157 sorgenti produzione e test SHAcd07ac63 invariati rispetto al freeze;74 test preesistenti preservati e6 nuovi verdi. File evidence fuori Git `green-targeted/result-receipt.json` SHAcd20fe6677cc4d85b7124747092db67a69f18492872fa42edd331f21abcae8e4, XML SHA3c380384af99d726a4770b788655962ccb5162db1405ffaed38854f4cff11cba, log SHAece6637bc25dd15bd99edfba3c73945db3a2346cf102f78e3b6435fb1c2e79bd.

Preparazione statica bounded del test-mapper: nessun blocker individuato; fake/fixture originali byte-identici e helper con parametro opzionale default invariato. Non sostituisce review formale. Copertura655 usa95 retained consecutivi all'inizio; interleaving e oltre500 retained consecutivi non hanno fixture separata, pur essendo gestiti dalla scansione cursor statica.

**Gate residui:** `assembleDebug`, lint/warning finale, baseline TASK004 repository/ViewModel pertinenti, review indipendente, TEST ed exact-head CI, ritest autenticato e attribuzione dominio live ancora NON ESEGUITI sul nuovo delta. Il solo comando80 non autorizza ulteriori runtime o retry. Task resta FIX.

### Esecuzione — integrazione e CI finali, 2026-10-02 UTC (root)

**File modificati:**
- `docs/MASTER-PLAN.md` — riallineamento dell'integrazione verificata e dei prossimi controlli.
- `docs/TASKS/TASK-143-mobile-parity-root-cause-android.md` — nuova evidenza parent, snapshot precedenti conservati.
- `docs/TASKS/evidence/TASK-143/integration-final/*.json` — ricevute pubbliche CI/merge/live e ledger con riferimenti portabili/12statiCA; ricevuta portabile di checkout CI, conteggi e snapshot live sanitizzato.

**Azioni eseguite:**
1. PR [#11](https://github.com/XNIW/MerchandiseControlSplitView/pull/11) integrata con merge normale il 2026-10-01 alle 23:39:45Z: head `1d7dfd188dcc082c0a172f28dee0b0f412431bf7`, main `0613339f11a4962bd898ae79f10ee1984579dcee`. Origin/main e ancestry verificati; nessun bypass o modifica delle protezioni.
2. CI [head36940178169](https://github.com/XNIW/MerchandiseControlSplitView/actions/runs/36940178169) e [main36941982412](https://github.com/XNIW/MerchandiseControlSplitView/actions/runs/36941982412) SUCCESS. Checkout effettivo provato dai log: rispettivamente `1d7dfd18` e `0613339f`. Ciascun artifact ufficiale contiene 72 XML, 1.040 ID unici: **1.033 PASS / 7 SKIP / 0 FAIL / 0 ERROR**, stessi casi e stati. Assemble/lint/test/upload PASS; lint 54 warning/0 errori. Le due UseKtx già accettate preservano il Boolean di commit; nessun warning Kotlin/deprecation nuovo. Avvisi JVM CDS separati.
3. Gate locali e review conservati: source18 `e1b1ed54…`, 165 mirati PASS, canonico 1.033 PASS/7 SKIP e cinque Compose effettivi PASS. Sorgenti invariati attraverso test e build TEST; questo addendum modifica solo documentazione.
4. Snapshot live del coordinatore alle 00:42:29Z: APK `96b3d613…`, auth ripristinata e owner/shop corretti dopo normale riavvio AVD con gli stessi dati, senza nuovo login/reset. Recovery automatica fallita HTTP500/SQL57014 nel digest prezzi del checkpoint. Conteggi dopo il fallimento NON RILETTI e generazione terminale NON VERIFICATA; auth PASS non prova convergenza. Ricevuta sanitizzata SHA `b8bb4f7d…` vincolata nel [ledger](evidence/TASK-143/integration-final/validation-ledger.json). Il ritest dopo correzione backend e le misure restano separati.

5. Cronologia successiva separata dal receipt00:42: registry150/AdminPR123 preserva dati/permessi ma iOS00:55 e Android00:59 falliscono nella verifica finale v_integrity/57014. Registry151/AdminPR124/main2e236586, CI/postcheckPASS e dati/permessi invariati; unicoRetry iOS01:39:43→01:39:52.012612 ancora500/57014, no nuovo manifest/finalization, binding invariato. Android151 non ripetuto per la stessa failure backend. Ricevuta safe consolidata SHA a291f9c6… nel ledger; authPASS resta distinta. ReadbackAndroid01:15 a appferma: quick_checkok, journalrequired1/attempt16, binding1, manifest/baseline/watermark/business/outbox0 (proiezione c97bea0d…). Profilare l’interaRPC prima di un altrodelta/Retry.

**Check obbligatori dell'aggiornamento solo documentale:**

| Check | Stato | Note |
|---|---|---|
| Build Gradle | N/A | Solo documentazione; gate del codice finale verificati sopra. |
| Lint | N/A | Nessuna modifica a codice, risorse o build. |
| Warning nuovi | N/A | Nessuna nuova compilazione; warning CI classificati e conservati. |
| Coerenza con planning | ESEGUITO | Mandato coordinato e CA-10/11; nessuna riapertura o modifica del backlog. |
| Criteri di accettazione | ESEGUITO per tracciamento | CA-10/11 gate e integrazione ESEGUITI. CA-07/09/12 ancora NON ESEGUITI integralmente per recovery, convergenza e misure residue. |

**Baseline regressione TASK-004:** N/A per questa modifica solo documentale; suite JVM/Robolectric finali già riportate, distinte dai cinque Compose. Skip e run fallite storiche preservati.

**Incertezze:** recovery terminale, confronto per record e misure finali restano da verificare; nessuna perdita dati o convergenza inferita dai soli contatori. Stato FIX, non DONE; nessun deploy produzione.

**Handoff notes:** parent responsabile di integrazione e rapporto; coordinatore owner dei simulatori autenticati. Retry soltanto dopo diagnosi e gate backend, niente azioni concorrenti sui suoi dispositivi.

### Esecuzione — CI checkout del candidato, 2026-10-01 (root)

**File modificati:**
- .github/workflows/ci.yml — solo input ref del checkout: SHA head della PR; fallback github.sha sugli altri eventi.
- docs/TASKS/TASK-143-mobile-parity-root-cause-android.md — planning autorizzato ed evidenza di esecuzione.

**Azioni eseguite:**
1. Checkout PR predefinito constatato nel workflow; su Android run36938212309 checkout Git f4e5500d e head7363cae hanno tree7e1eb674 identico. Quel PASS è TREE_EQUIVALENT_MERGE_CHECKOUT, non exact Git head.
2. App/test/fixture del candidato locale invariati; nessun nuovo PASS app dedotto dall'edit CI.
3. Diff minimo e ricostruzione byte-identica dei workflow precedenti verificati nella review indipendente APPROVED ci-exact-head-checkout-review.md (SHA084c37e9). Pin/versioni, permessi, trigger e gate invariati.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build/test app | ESEGUITO sul freeze app invariato | Gate canonici e hash del batch corrente riportati sotto; nessun nuovo compile locale per il solo checkout input. Nuova CI sul commit effettivo obbligatoria. |
| Static workflow / diff | ESEGUITO | Ref minimo, inverso byte-identico; review APPROVED e git diff --check. |
| Warning nuovi | ESEGUITO | Nessuna nuova modifica a sorgenti/build SDK; diagnostici del batch app conservati. |
| Coerenza con planning | ESEGUITO | Solo provenance richiesta dal mandato exact-SHA. |
| Criterio CI exact-head | NON ESEGUITO al commit | Da verificare nel nuovo run con actual git checkout SHA; metadata headSha da soli insufficienti. |

**Handoff:** root conserva run precedenti e gestisce push/CI/merge normale/mainCI; nessun DONE o bypass.


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

### Esecuzione — 2026-10-04 05:27 UTC, integrazione e build/installazione finale

**File modificati:**
- Questo task, sola sezione Execution — ricevute finali e limiti dell'accettazione runtime; Planning, MASTER e stato FIX invariati.
- [Rapporto aggregato](/Users/minxiang/.codex/worktrees/mobile-parity-root-cause/MERCHANDISECONTROL_MOBILE_PARITY_ROOT_CAUSE_RESULT.md) — integrazione, CI, preservazione, installazioni e matrice CA aggiornati; documento esterno al source congelato.

**Azioni eseguite:**
1. Autorizzazione umana del 4 ottobre03:44UTC verificata nella chat coordinatrice: push/PR/merge e successive build/sync/prestazioni. Normale [PR15](https://github.com/XNIW/MerchandiseControlSplitView/pull/15) dal branch `codex/android-native-final-integration`, head4637424e; merge04:12:01UTC `dbebcd0e6ee2bfb7d53276d1d13a39659d0f5e9b`, tree candidato/main f3015559 identico e ancestry verificata. CI head37175401666 e main37176307870 COMPLETED SUCCESS sul checkout esatto; [ricevuta main](/Users/minxiang/Projects/MerchandiseControl-Ecosistema/evidence/operational-completion-20261003/resumed-executor/native-final-integration-final-android-attempt01/remote-main-pr15/ci-adjudication.json), SHA a488aaac0ffdbf83299adab13eb9ea7d92ff81ef60b8d982c93eba6d1fdfe696.
2. Gate finale locale una volta sul source da integrare: `test assembleDebug lint assembleDebugAndroidTest`, 74XML debug/1134ID =1127PASS/7SKIP/0FAIL o ERROR; stessi ID/stati/motivi skip su PR e main. Lint54 warning invariati, Kotlin0. Quattro nuovi androidTest compilati, non eseguiti. Nessuna suite immutata ripetuta dopo il PASS locale; CI automatiche distinte.
3. Primary aggiornato con fast-forward/autostash riapplicato senza conflitti; backup originali13/13, stash esistenti e modifiche locali preservati. AGP9.4.1/KSP2.3.6/Kotlin2.3.21/Gradle9.6.0 e selector preesistente conservati; profilo privato invariato. [Ricevuta primary](/Users/minxiang/Projects/MerchandiseControl-Ecosistema/evidence/operational-completion-20261003/resumed-executor/native-primary-preservation/android-main-update.json), SHA7a169e1e8e08e0fdecb82e06592adacc84e49e0b47c0297683897b979a9fc6c9.
4. Build TEST primaria finale04:34:00→04:37:13UTC PASS con toolchain utente, assembleDebug offline e flag serializzazione canonici. APK20fe14782caefc204616a3a1a9408f3a3418a7d70fee9a4663cfbb6fce0c41a8, otto campi/tipi DEX byte-equivalenti al profilo autorizzato8a, stessa firma/package/versione/debuggable. [Ricevuta build](/Users/minxiang/Projects/MerchandiseControl-Ecosistema/evidence/operational-completion-20261003/resumed-executor/native-integrated-builds-20261004/android-final-attempt01/receipt.json), SHAe343a9fea293c2ffab0d14a73ba91260d2a28dc6cc47b76dc523b47a420291c5; soli processi propri rilasciati, nessuna scrittura delle configurazioni private.
5. Installazione normale `adb install -r` SUCCESS su emulator5554/MediumPhoneAPI35; SHA installata20fe esatta. Prima/dopo passivi: Room22, quick_checkOK, tutte19tabelle con conteggi/digest tipizzati/ordine colonne identici. Products19744/categories51/suppliers79/prices41206/History83/outbox0; journal1 e manifest61598 sono fatti fisici, non prova READY/stallo. [Ricevuta post-install](/Users/minxiang/Projects/MerchandiseControl-Ecosistema/evidence/operational-completion-20261003/resumed-executor/native-integrated-builds-20261004/android-post-install-data-readonly.json), SHAd9f6b39c9591ee46c4754b43f8f5e437c2b5788abaae11f2d6a789d59fa9497b.
6. Input CUA Inventory fallisce `noWindowsAvailable` prima/dopo AXRaise supportato; screenshot Options/account connesso precede il20fe. DeviceHub iOS restituisce `timeoutReached`. Nessun nuovo reopen/recovery/sync concluso o PSS autenticato; chiesto all'utente il foreground delle finestre, risposta pendente. Sampler PSS c6fa/card9a01 e qualifier CSV04 9ebc hanno review statica; zero nuove misure, wrapper official ancora in review separata. CSV03cap3/3 invariato; fixture CSV04 isolata signed-out distinta dal runtime autenticato.

**Check obbligatori:**
| Check | Stato | Note |
| --- | --- | --- |
| Build Gradle | ✅ ESEGUITO | Full locale10e2, CI head/main e build primaria TESTe343 PASS, APK installato esatto. |
| Lint | ✅ ESEGUITO | 54warning/0errori, firme invariate su source canonico finale e CI. |
| Warning nuovi | ✅ ESEGUITO | Zero nuovi warning Kotlin/deprecation; lint invariato. |
| Coerenza con planning | ✅ ESEGUITO | Source congelato10percorsi con review indipendente; UI/diagnostica e guard scope preservati. |
| Criteri di accettazione | ⚠️ NON ESEGUIBILE integralmente | Stati individuali sotto; TASK resta FIX, nessuna accettazione live dalla CI/installazione. |

**Baseline regressione TASK-004:**
- Eseguiti full1134 e test JVM/Robolectric Repository217PASS+1SKIP, DatabaseVM61PASS, ExcelVM52PASS, recovery116PASS, coordinator39PASS e Application25PASS.
- Test aggiunti/aggiornati nel batch source approvato e già tracciati nelle voci precedenti; nessun test nuovo per questo append documentale.
- Limiti: sette skip opt-in storici, quattro nuovi instrumentation soltanto compilati. Non sono descritti come test UI Compose/Espresso eseguiti.

**Criteri individuali, stato corrente:**
| Criterio | Stato | Evidenza / limite |
| --- | --- | --- |
| CA-01 | ESEGUITO Android | Preservazione primary/19tabelle verificata; la retention iOS completa resta distinta e aperta. |
| CA-02 | ESEGUITO locale | F01/storage/intent e regressioni deterministiche; live finale in CA07. |
| CA-03 | ESEGUITO locale; NON ESEGUIBILE nuovo smoke | UI storica e gate locali preservati; input corrente indisponibile. |
| CA-04 | ESEGUITO locale; NON ESEGUITO live finale | Contratto staging storico12/12 rollback; ACK perso dalle app autentiche nuove non provato. |
| CA-05 | ESEGUITO | Implementazione, integrazione, build/installazione e acceptance separati. |
| CA-06 | ESEGUITO matrice | NOT_TESTED/EXTERNAL_DEPENDENCY rimangono espliciti per runtime/hardware. |
| CA-07 | NON ESEGUIBILE integralmente ora | Recovery/reopen/delta/noWork, stesso scope e convergenza canonica bidirezionale mancano; input CUA fallisce. |
| CA-08 | NON ESEGUITO integralmente | Gate locali import/export/immagini verificati; workbook/lifecycle immagine reale/hardware ancora aperti; Excel harness sospeso. |
| CA-09 | NON ESEGUIBILE finale ora | UI/observer/dataset attuali non qualificati; zero nuovi campioni/PSS/target3s accettati. |
| CA-10 | ESEGUITO gate; NON ESEGUIBILE nuovo UI | RED/GREEN/full/build/lint/CI verificati; instrumentation compile non equivale a execution. |
| CA-11 | ESEGUITO | Review/fix/re-review, push/PR/merge normale e CI esatta verdi. |
| CA-12 | ESEGUITO tracciamento; NON ESEGUITO chiusura | Report corrente; TASK143/144 FIX e mandato globale aperto. |

**Incertezze:**
- Sessione/scopo/READY dopo installazione non verificati dalla UI; journal1 non identifica una causa runtime.
- Baseline PSS8a non misurata prima dell'upgrade; non attribuire confronti autenticati al vecchio PSS signed-out.

**Handoff notes:**
- N solo writer/input nativi; coordinator C conserva il ledger condiviso e W resta senza input nativo concorrente.
- Il solo prerequisito UI richiesto è riportare in foreground le finestre; nessun logout/reset, bypass strumenti o ripetizione di test immutati. Recuperare prima sessione/recovery terminale e convergenza per record, quindi misure qualificate.
- Append documentale successivo al merge PR15, non una nuova modifica di codice né una nuova CI/source accettata.

### Esecuzione — 2026-10-04 06:05 UTC, quattro test Compose su AVD isolato

**File modificati:**
- `app/src/androidTest/java/com/example/merchandisecontrolsplitview/ui/screens/CrossPlatformReliabilityComposeDeviceTest.kt` — corregge soltanto il setup del test di retry: apertura reale dell'editor nel ViewModel e prova dei due ingressi nel repository; nessuna modifica di produzione.
- Questo task, sola Execution — RED/GREEN, build/lint e limiti; Planning/MASTER/FIX invariati.

**Azioni eseguite:**
1. Individuati gli APK finali già compilati: app keyless212315798d4b2b74a023bd024f60a32d2c20fb640611ddb8d0394095f70e41af e testfdcd767f2fac6172135bc4299b1e6a77eedbe599137d8bba1e3cdeed99abab6d, firma b4afd047 identica. Usato solo il Codex_Mobile_Parity_Final_API_35/serial5582, separato dal5554autenticato.
2. Primo bootstrap preflight fermato prima di install/test: riferimento APK96b storico, superato dal baseline7c4a conservato nel successivo runtime-memory-slot01. Ricevute storiche più recenti hash-verificate; nessuna classe eseguita in questa preflight. Processi propri chiusi e ritardo ADB nell'assenza serial risolto con letture passive, nessun restart server/reset.
3. RED qualificato05:50:19→05:51:09UTC: actualAPK7c e database reale isolato Room22/quick_checkOK/sette tabelle vuote prima e dopo, install keyless/test e una sola classe4. **3PASS/1FAIL/0SKIP**, timeout5000 alla chiusura dopo retry. [Receipt RED](/Users/minxiang/Projects/MerchandiseControl-Ecosistema/evidence/operational-completion-20261003/resumed-executor/native-final-instrumentation-20261004/isolated-compose-attempt02/receipt.json), SHA331547b091d1e6ab16ee3056111e098d79d83254a7202f574d1e6c9d3ee727fb, log originale conservato.
4. Review causale indipendente conferma fixture incompleta: il dialog era montato senza `openProductEditor`; `saveProductFromEditor` rifiutava il target nullo prima del repository su entrambi click. Non era una regressione dell'app né un errore auth/READY. Fix minimo nel solo metodo: apertura reale su idle prima degli input, targetassert, hook AtomicInteger che fallisce solo al primo ingresso e assert1/2 per fault/retry; attesa5000 per l'errore Room asincrono. Tutti gli assert precedenti, il timeout5000 di chiusura e i tre altri test byte-invariati; nessuna nuova dipendenza o bypass guard. Source test62e3afa9f4dde86f7929ad8a3433a9cf2a2603c37eb96f2fd3860ef882124594, re-review APPROVED_STATIC.
5. Build/lint/assembleDebugAndroidTest06:00:01→06:00:35UTC PASS. Primo launcher build aveva omesso SDK env e si era fermato prima del compiler; corretto solo ANDROID_HOME verso SDK esistente, senza creare local.properties. [Receipt build finale](/Users/minxiang/Projects/MerchandiseControl-Ecosistema/evidence/operational-completion-20261003/resumed-executor/native-final-instrumentation-20261004/fixture-build-attempt02/receipt.json), SHAd60a1a18c7be80c6859d8a8a75deb82ce5b8a47b49e16f440f8177b7e4573d05. Lint XML3d7982b3 byte-identico/54warning0errori, Kotlin0. App212byte-identico; nuovo testAPK57df42a1a079f74f20a636e8cf83f6168842bbf4dc4cde40f17ae31da16d0e84 e stessa firma.
6. GREEN qualificato06:02:30→06:03:13UTC, una sola nuova invocazione dopo i nuovi byte fixture: **4PASS/0FAIL/0SKIP**, quattro ID esatti con terminal status0, fault repository1 e retry2, rollback iniziale/draft/focus/singola history e chiusura verificati. [Receipt GREEN](/Users/minxiang/Projects/MerchandiseControl-Ecosistema/evidence/operational-completion-20261003/resumed-executor/native-final-instrumentation-20261004/isolated-compose-green-attempt01/receipt.json), SHA471d727e33bc8aef17f0b00292604b78f53bc0d6f317d2b928a02d91814ec05d. Room22/sette conteggi vuoti prima/dopo, gruppi propri e serial5582 assenti, APK/app/config AVD preservati. Nessun input/reset/logout/install sui target5554/459.
7. Branch dedicato `codex/android-compose-retry-fixture` da main dbeb; normale integrazione del solo test e registro in preparazione, review/CI esatta prima del merge. La source di produzione e l'APK autenticato20fe restano invariati; nessun nuovo confronto performance o accettazione sync.

**Check obbligatori:**
| Check | Stato | Note |
| --- | --- | --- |
| Build Gradle | ✅ ESEGUITO | assembleDebug +assembleDebugAndroidTest finale PASS. |
| Lint | ✅ ESEGUITO |54warning/0errori, XML byte-identico al precedente gate. |
| Warning nuovi | ✅ ESEGUITO |Zero warning Kotlin/deprecation nuovi. |
| Coerenza con planning | ✅ ESEGUITO |CA10 runtime dei4test esistenti; solo fixture minima, review indipendente. |
| Criteri di accettazione | ⚠️ NON ESEGUIBILE integralmente |CA10:4Compose ora ESEGUITI; CA07/09 e sessione/retention iOS ancora aperti per UI CUA. |

**Baseline regressione TASK-004:**
- Prodotto e test JVM byte-invariati; ultimo full1134=1127PASS7SKIP rimane applicabile alle medesime logiche. Non ripetuto senza delta pertinente.
- Test modificato: solo fixture Compose di retry;4strumentati eseguiti sul nuovo testAPK. Sono test UI separati dalla baseline JVM/Robolectric.
- Limiti: i4PASS isolati keyless non certificano app autenticata, recovery terminale, convergenza bidirezionale o prestazioni.

**Incertezze:**
- Nessuna sul difetto fixture corretto e sui quattro esiti salvati.
- CUA native conserva noWindowsAvailable/timeoutReached; foreground richiesto all'utente e ancora pendente.

**Handoff notes:**
- CA01/02/03/04/05/06/08/11/12 conservano i limiti della voce05:27 e della matrice del report; CA10 aggiorna soltanto i quattro instrumentation da compilati a eseguiti.
- Non ripetere RED/GREEN immutati né indebolire guard/timeout. Chiudere nuova CI/merge normalmente; poi runtime autenticato ancora da verificare. TASK resta FIX.

### Esecuzione — 2026-10-07 UTC — osservabilità minima della prova ordinaria, V2

**Perimetro e stato:** mandato umano diretto sezioni 6/8, delegato dal coordinatore al solo worktree isolato, baseline `b0ba75bcbe5b2f69d8790e809cb6e3b4d3b00693`. TASK-143 resta **FIX**. Questa aggiunta registra osservabilità e test locali; non modifica Planning, MASTER, comportamento recovery, journal, retry, sync, API o dipendenze. Checkout primario e dati/installazione autenticata preservati.

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — log nel tag esistente `CatalogCloudSync`, con rami `no_work` / `incremental_window`, codici chiusi e prima condizione falsa; nessun identificativo, digest, payload, messaggio arbitrario, classe arbitraria o stack. Le due conferme `stage=recovery_required` sono emesse soltanto dopo il successo della transazione journal. Gli errori marker/local receipt restano registrati al punto di errore.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/ShopSyncRecoveryCoordinatorTest.kt` — cinque regressioni additive con Room reale e baseline attivata/riaperta, privacy, ordine della prima condizione falsa, singola valutazione, short-circuit e rientro già bloccato, finestra prima/dopo eventi, assenza di conferma latch su defer/publication failure.
- Questo task, sola Execution — risultati, limiti e handoff. Nessuna modifica UI/UX.

**Azioni eseguite:**
1. Prima patch: quattro test nuovi **4 FAIL/0 ERROR/0 SKIP**, dopo la preparazione Room reale, poi **4 PASS** sulla V1. Conservati log/XML originali, senza sostituire evidenze precedenti.
2. Gate V1: sei classi, **543 test = 542 PASS/1 SKIP**, build/lint PASS. Review successiva ha individuato due conferme log precedenti al commit e categorie non-contract troppo generiche. Due regressioni contro V1: **2 FAIL/0 ERROR/0 SKIP**, rispettivamente falso log di latch e tipo IllegalState perso. V1 rimane storica e non finale.
3. V2 sposta soltanto le due conferme dopo il commit e mantiene categorie chiuse `illegal_state`, `illegal_argument`, `sqlite`, `serialization`, `other_exception`; un codice contract sconosciuto resta `other_contract`. Test con istanze reali per tutte le categorie, incluso Serialization prima del suo supertipo IllegalArgument e un codice sconosciuto contenente payload. Cancellation, rethrow, guard, transazioni e ordine/numero delle query/RPC restano invariati. `localReceipt=false` mantiene il marker predicate **NOT_EVALUATED**, senza valutazione aggiuntiva.
4. Correzione numerica documentale: la baseline corrente Recovery contiene **137 test**, non 116. Il vecchio campo V1 con etichetta116 resta immutabile; l'inversione del blocco additivo e dell'import ripristina l'intero file originale, inclusi i137 metodi/helper/assert. V2 contiene **142 = 137+5**, senza indebolire test precedenti.
5. Gate finale V2 unico: **00:25:29.262402 → 00:28:34.351290 UTC, 185,089 s, exit0**. Comando offline con JBR Android Studio, SDK esistente, `--no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process testDebugUnitTest`, sei selettori di classe sotto elencati, `assembleDebug lint --console=plain`. Sorgenti congelate durante tutto il gate; processo Gradle proprio14037 assente al controllo finale. `local.properties` assente, otto variabili di profilo escluse: build ordinaria **KEYLESS / NONDEPLOYABLE**, non un nuovo APK TEST approvato.

**Risultati JVM/Robolectric finali:**

| Classe | Totale | PASS | FAIL/ERROR | SKIP |
| --- | ---: | ---: | ---: | ---: |
| ShopSyncRecoveryCoordinatorTest | 142 | 142 | 0/0 | 0 |
| DefaultInventoryRepositoryTest | 222 | 221 | 0/0 | 1 |
| DatabaseViewModelTest | 61 | 61 | 0/0 | 0 |
| ExcelViewModelTest | 52 | 52 | 0/0 | 0 |
| CatalogAutoSyncCoordinatorTest | 39 | 39 | 0/0 | 0 |
| SupabaseShopSyncReadRemoteDataSourceTest | 28 | 28 | 0/0 | 0 |
| **Totale** | **544** | **543** | **0/0** | **1** |

Lo SKIP è esattamente `DefaultInventoryRepositoryTest.wechat 004 real local Supabase fixture converges through production incremental apply`: assumption `WECHAT_004_LOCAL_E2E_FIXTURE is required for the real local gate`, opt-in non attivato e fixture protetta non letta. Già condizionale nelle evidenze precedenti; non è PASS né prova live. Tutti i cinque nuovi ID diagnostici sono PASS.

**Check obbligatori:**

| Check | Stato | Note |
| --- | --- | --- |
| Build Gradle | ✅ ESEGUITO | `assembleDebug` finale exit0; solo build ordinaria keyless, non TEST installabile. |
| Lint | ✅ ESEGUITO | 44 Warning, 0 Error/Fatal; report V2 byte-identico a V1. |
| Warning nuovi | ✅ ESEGUITO | 0 nuove firme lint, 0 warning/error Kotlin/deprecation. Rispetto al report pre-run salvato (59 Warning), 15 soli suggerimenti versione assenti offline:14 GradleDependency +1 AndroidGradlePluginVersion. Nessun upgrade, suppression o fix warning dichiarato. Avviso JVM CDS separato dai warning sorgente. |
| Coerenza con planning | ✅ ESEGUITO | Osservabilità minima autorizzata; short-circuit, business e guard invariati; MASTER e prefix Planning preservati. |
| Criteri di accettazione | ✅ ESEGUITO per il delta locale; ❌ NON ESEGUITO per acceptance live | Privacy, prima condizione falsa, singola valutazione e conferma post-commit provate dai cinque test. Nessuna nuova prova auth/READY/convergenza/business o chiusura globale. |

**Baseline regressione TASK-004:** eseguite integralmente le tre classi Repository/DatabaseViewModel/ExcelViewModel più Recovery/AutoSync/reader pertinenti. Sono test unitari/Robolectric JVM, non UI Compose. Suite globale non ripetuta su aree immutate. Il coordinatore ha verificato che gli instrumentation esistenti DAO/ACK/cutover e lease/scope non coprono emissione/redazione dei log: nessun nuovo AVD né instrumentation non pertinente. Collaudo business primario separato **NON ESEGUITO** in questo pass.

**Evidenze:** directory esterna `MerchandiseControl-Ecosistema/evidence/operational-completion-20261003/resumed-executor/android-ordinary-proof-observability-20261007`; [receipt finale](/Users/minxiang/Projects/MerchandiseControl-Ecosistema/evidence/operational-completion-20261003/resumed-executor/android-ordinary-proof-observability-20261007/final-v2-local-receipt.json), SHA256 `ad7eb58a3e595b202ddebfbf9c8773c093b57af30fbb97b0095772f7aed467b7`. Manifest sei XML `3745bca14cd0b1142ab3c682fc380fd8c499c10ba6e1daac369e87efad35f475`; confronto lint `10ee6358c57326a1f1246cff2abc8200b3a84079e82e4c894cdf83fa2e48c11f`. Report lint finale `fd29c629e5d50ad797407362fbdbff8094fe9ada087b8127120825de81f9ed1c`, pre-run `8e898277aa14376517bb282c5f6e97679785ff53e4d02e3210da0e084597f2f6`: confronto completo attributi/location, non solo conteggi; baseline salvata, non rieseguita. Freeze V2 `db99907ad281a67ebedff4cb3d574b8a53831e0913c529e1ada57bd6a136587c`, patch completa `07dc6f1f3b1513f5fa0a397879e663d86389c35b309381321b0860ea933bd070`, delta review V1→V2 `3411496cf032097652c485c708943066474f13f125669ec46c70405f7365f3b1`.

**Incertezze e handoff:**
- Il journal live già persistito non identifica univocamente il ramo storico e può impedire il rientro nei nuovi log. Il residuo riportato dal coordinatore alle20:18 resta **branch UNKNOWN**, journal **STAGING**; questa nota non è una nuova lettura del dispositivo. Nessuna cancellazione/reset/forzatura del journal o di READY autorizzata/eseguita; nuove osservazioni richiedono un percorso naturale e scope del coordinatore.
- Whitelist verificata sul perimetro corrente, non esaustiva per ogni futuro codice. `shop_sync_reader_unavailable` precede i catch osservati; `targeted_ids_empty`/`targeted_chunk_fence_changed` appartengono al helper legacy non chiamato dalla finestra canonica; `sync_event_domain_fence_missing` è anticipato dal controllo exact delle tre chiavi e dal mapping enum esaustivo. Un `ordinary_outbox_scope_mismatch` persistente rilancia nella pendingCheck del latch prima della conferma post-commit; se comparisse tra i controlli, l'error log locale userebbe `other_contract`. Nessuna normale conversione/latch con discriminante persa accertata in questa verifica statica; nessuna estensione preventiva della whitelist.
- Root/coordinatore gestisce review, Git/CI e futura build TEST con confronto profilo/hash/firma rispetto alla baseline autorizzata. La build ordinaria locale non incorpora una prova GitSHA/profilo TEST. Nessun nuovo package aggiuntivo, device, installazione, backend, lettura auth/sessione, harness Excel o accettazione per-record in questo pass. TASK resta FIX.

### Esecuzione — 2026-10-07 UTC — recovery locale, marker avanzato e prezzo nella generazione corrente

**Perimetro:** nuovo mandato utente di completamento, baseline remota verificata `bb203bae149a76ccfd28cfff35dd62ef406bafc7`, branch isolato `codex/android-recovery-local-capabilities`. Stato **FIX**, nessuna modifica a MASTER/Planning, dipendenze, schema o configurazione. Nessun accesso al device primario, profilo privato o backend. Le evidenze seguenti sono locali; non attribuiscono ai nuovi difetti la causa storica del dispositivo autenticato.

**File modificati:**
- `MerchandiseControlApplication.kt`, `data/CatalogAutoSyncCoordinator.kt`, `data/CatalogSyncStateTracker.kt`, `data/Task126BusinessDataScopeRuntimeGuard.kt` — pubblicazione della recovery cloud conservando soltanto capacità locali qualificate dal repository corrente e dall'autorità effettiva; nessuna seconda cancellazione globale dopo il vero cutover. Controlli di generazione/scope/cancellazione conservati; riscoperta della coda durevole dopo il completamento attraverso il coordinatore esistente.
- `data/ShopSyncContractModels.kt`, `data/SupabaseShopSyncReadRemoteDataSource.kt`, `data/InventoryCatalogRemoteRows.kt`, `data/InventoryRepository.kt`, `viewmodel/CatalogSyncViewModel.kt` e tracker/coordinatore sopra — marker valido B>A classificato come lavoro ordinario pendente dopo prova fisica A e CAS. Un solo successore dopo rilascio della flight, nuove verifiche di autorità/rete/foreground; nessuna receipt B, pubblicazione watermark, recovery o falso successo dal solo hint. Campi di scansione nullable richiesti dal solo nuovo ramo; confronto strict del marker recovery invariato.
- `data/ShopSyncRecoveryCoordinator.kt` — predicato prezzi vincolato alla generazione manifest corrente nelle due query attese e nella query fisica; generazione fisica passata con bind, nessuna nuova query, cache, migrazione o indice.
- Sei classi JVM/Robolectric (`ShopSyncRecoveryCoordinatorTest`, `Task126BusinessDataScopeRuntimeGuardTest`, `DefaultInventoryRepositoryTest`, `SupabaseShopSyncReadRemoteDataSourceTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest`) — 31 casi additivi: recovery/capacità/Save/coda, decoder e successore A→B, corruzione mascherata da generazione estranea e piano SQL reale.
- `androidTest/.../LocalAvailabilityRootDeviceTest.kt` — due casi additivi coordinator→tracker→repository file-backed riaperto→root Compose reale. Tab, ricerca, scroll, editor, focus, draft e Save restano utilizzabili mentre la recovery è trattenuta. Nessun redesign UI.

**Azioni e prove:**
1. RED reale su entrambi i trigger `catalog_push`/`sync_events_drain` e Save in volo; due RED strumentali sul placeholder root. Correzione selettiva senza alterare la transizione forte del cutover. Review V1 ha rilevato il resolver READY senza journal per history legacy: RED mirato e latch cloud `ERROR_RECOVERABLE` preservato in V3. Dinieghi correnti/espliciti non diventano permessi.
2. Completamento/coda: il primo verde aveva una fixture con PURCHASE senza bridge e price remote che rifiutava l'ACK. Predicati effettivi `productAck=1`, bridge mancante e tentativo PRICES hanno isolato la fixture. Corretto soltanto il remote controllato, mantenuta l'asserzione di conferma strict; rieseguito il RED RPC-non-raggiunta con il solo inverse della riscoperta e poi **229/229 PASS** sullo stesso test corretto. Il precedente FAIL non è stato cancellato o rivendicato come successo.
3. A→B: RED del decoder e del repository con A realmente attivata/riaperta; **265/265 PASS** nelle quattro classi coinvolte, inclusi 14 nuovi casi e 20 varianti raw negative. B>A resta un hint, non una prova del contenuto B; i digest del codec sono validati nel formato previsto, non ricomputati crittograficamente. Scope, identità, integrità, scansione, budget, pending locale e CAS restano fail-closed.
4. Prezzi: RED Room effettivo dimostra che un manifest di una generazione estranea può mascherare la corruzione del prezzo corrente. Fix privato generation-bound, quindi **164/164 PASS** Recovery. Il primo verde aveva un solo FAIL nel matcher del testo EXPLAIN: la query era già `SEARCH ... PRIMARY KEY (generationId/domain/remoteId)`. Corretto solo il matcher per le forme SQLite equivalenti, mantenendo rifiuto di `SCAN lp`; nessuna regressione produttiva attribuita a quel FAIL. Nessun claim di latenza sul device o diagnosi della CPU live.
5. Review indipendenti source: recovery V3, A→B e delta prezzo approvati; freeze finale 17 code/test `eee2e5ccf81a720dc4f4a243defe74f088d139556f8d69c0a4435c919faa306e`, patch `52121ee1a50e6279f2a2f2dc90e1cde98b71ebf5e47b80b2777f588d28779fca`. Il freeze annotava il mirato ancora in corso; il successivo risultato è una ricevuta separata, senza riscrivere lo snapshot.

**Check obbligatori finali (stesso codice congelato):**
| Check | Stato | Evidenza / limite |
|---|---|---|
| Build Gradle | ✅ ESEGUITO | Unico comando JBR offline `test assembleDebug lint assembleDebugAndroidTest`, 19:34:31–19:37:21 UTC, exit 0; 90 task, 17 eseguiti/73 up-to-date. |
| Lint | ✅ ESEGUITO | Report fresco `fd29c629e5d50ad797407362fbdbff8094fe9ada087b8127120825de81f9ed1c`, byte-identico al precedente: 44 warning, 0 errori. Nessun aggiornamento/soppressione introdotto. |
| Warning nuovi | ✅ ESEGUITO | 0 diagnostiche Kotlin; 0 nuove issue lint per uguaglianza byte. Diff check PASS; scansione limitata del diff aggiunto per firme credenziali ad alta confidenza: 0 match, non scanner esaustivo. |
| Baseline TASK-004 | ✅ ESEGUITO | 76 XML freschi, **1207 = 1200 PASS + 7 SKIP**, 0 FAIL/ERROR. `test` ha prodotto debug, nessun testRelease XML e nessun PASS Release dedotto. |
| Root Compose su AVD | ✅ ESEGUITO | Cinque metodi originali/additivi, ciascuno statusCode 0, `OK (5 tests)`, runner ufficiale: 47,7 s. Nuovo AVD API35 `Codex_LocalRecoveryFinal_20261007`, solo seriale5590, poi SIGTERM del solo PG proprio/assenza verificata ed eliminazione AVD. |
| Coerenza con planning | ✅ ESEGUITO | Delta limitato ai difetti riprodotti e al mandato di completamento; MASTER e prefisso Planning immutati. |
| Criteri di accettazione | ❌ NON ESEGUITO integralmente | Prove locali CA07/CA10 e controlli adiacenti eseguiti. ProperTEST, collaudo autenticato, parità per-record/CA09, CI del nuovo commit e accettazione globale restano separati e pendenti. |

**Baseline e limiti:** DefaultInventoryRepository223 (222 PASS/1 SKIP), DatabaseViewModel61, ExcelViewModel52, Recovery164, guard11, AutoSync41, reader30 e CatalogSyncVM32 compresi nel full. I sette SKIP sono fixture Supabase locale, realtime live, benchmark opt-in, tre harness Excel sospesi e workbook opzionale; non contano come PASS. Questi sono test JVM/Robolectric, distinti dai cinque test UI strumentali. Il pacchetto del gate è KEYLESS (`49219f8ceb0cfbe0d348664dcbbbcd615fe2fab09b4ca89a48b6836ca58457e6`), utilizzato soltanto sul nuovo AVD sintetico; non è una distribuzione ProperTEST né autorizza installazione primaria. Test APK `3ea508f4f34ab4ff02d65504379f8e7badd95fead51d7fe7e44e130cfc8a61d7`. Nessun nuovo login, reset, clear journal, installazione primaria, SQL/RPC live o prova di READY business.

**Evidenze persistenti:** base esterna `/Users/minxiang/Projects/MerchandiseControl-Ecosistema/evidence/operational-completion-20261003/resumed-executor/`:
- `android-recovery-latch-and-queue-green-20261007-02/receipt.json` SHA `bf4d597d3733ec2d2369a1d981b21c7442a204eee08f5534ddf13140d784d160`;
- `android-no-work-advanced-regression-20261007-01/receipt.json` SHA `032a88766af2918fd07c4946184b4f2353482f38c74e176e6cb07ac72c7dbfeb`;
- `android-price-generation-green-20261007-02/receipt.json` SHA `c852f750eb19254124743ce919cd460db8015315fe3cd2cbd832021853d068ec`;
- `android-recovery-advanced-price-canonical-20261007-01/receipt.json` SHA `62af4e032855f19ef4464c1b912525049e20f9eae3c95cb45b8e057d5f0c0498`; manifest per classe/caso/skip/lint `test-lint-manifest.json` SHA `33072de2db60b965a181b5743adb70ff13e22f2f6647c74ccf533a6521534dc9`;
- `android-recovery-advanced-price-root-device-20261007-01/receipt.json` SHA `0795010d46b88065b53a2ccbb793c353e78c3ab8f1022381c09b8cf56587b23e` con comando effettivo, cinque nomi emessi, log e cleanup. Tutti i pin sorgenti/config erano invariati durante i job.

**Incertezze / handoff:** nessun difetto residuo trovato nei gate locali; causalità dello stato storico del primario non dedotta dalle fixture. ProperTEST richiede confronto separato di profilo, firma e sorgenti; merge solo dopo CI del vero SHA e coordinamento del parent. TASK resta FIX.

## Review

Review indipendente e re-review del primo batch completate; R-A04 scoperto nel successivo collaudo live è stato riprodotto, corretto e revisionato separatamente. Sorgente APPROVED, nessun P0/P1/P2 source aperto dopo R-A01/R-A02/R-A03/R-A04; gate locali aggiornati PASS (970 JVM e 5 Compose). R-A05 sul successivo diniego RPC è anch’esso corretto e revisionato. Gate aggiornato985 totali/978 PASS/7 SKIP; ritest autenticato conferma `checkpoint_resource_exceeded` correttamente classificato, preservando binding, dati e journal. Il blocco server TOAST resta aperto. Evidenza: [independent-review.md](evidence/TASK-143/independent-review.md). La review tecnica non è un'approvazione GitHub di un maintainer né una conferma live.

## Fix

### Fix — 2026-10-08 UTC — wrapping tab e ID thread compatibile

Dopo il RED02 reale di10 etichette incomplete, rimossa soltanto la restrizione maxLines1 dai Text dei tab Database; centratura e altezza restano Material3. L'oracolo corregge lo spazio di coordinate del MultiParagraph semantico senza rilassare completezza/ellissi/altezza o bounds. L'accessor threadId passa all'API36 con fallback31–35 e suppress locale, per risolvere i quattro warning osservati. Sorgente congelata, nuovi build/lint/GREEN7/QA ancora pendenti; prove RED e gate precedente conservati, nessun DONE.


### Fix — 2026-10-08 UTC — sincronizzazione del test manual row

La sola attesa del test CI ora osserva il feedback terminale `manual_row_added`, già asserito dal test, anziché la precedente assegnazione `lastUsedCategory`. Tutte le asserzioni, il timeout3000ms e la produzione restano byte-identici. FAIL CI originale preservato; nessun GREEN dichiarato prima del runner coordinato.

### Fix — 2026-10-08 UTC — verifica finale del banner

Chiuso localmente il batch geometria/testo: RED24 intersezioni→GREEN, poi RED15 overflow→GREEN6 e8 PNG validi/visione originale PASS. Gate finale1244=1237PASS+7sameSKIP,44 lint/0nuovi. Il falso allarme ES1.6 della vista ridimensionata è adjudicato sullo stesso PNG originale, senza patch della forma né rerun. Sorgenti/test finali invariati; review C approvate, task FIX e accettazione globale aperta. Le righe «pendenti» nelle note di preparazione sottostanti descrivono quei momenti storici.

### Fix — 2026-10-08 UTC — testo completo nel banner sync

Dopo il RED additivo effettivo (15 overflow TextLayoutResult su8 configurazioni, geometria header già PASS), Column e Text ricevono la larghezza residua della Row e il copy può andare a capo senza maxLines1/2. Oracolo ff838 invariato; nessun cambio a stato/timing/API o agli altri flussi. Nuovo GREEN e visuale finale pendenti.

### Fix — 2026-10-08 UTC — spazio riservato al banner sync

Dopo il RED Compose A02 (24 intersezioni su8 configurazioni), il banner occupa il proprio spazio misurato sopra un NavHost stabile. Margini dentro AnimatedVisibility; stato/tempi/copy e padding inferiore invariati. Nessun dato, auth, permesso o gate di disponibilità modificato. Oracolo e cinque test originali intatti; build/GREEN6/review ancora pendenti, task FIX.

### Fix — 2026-10-08 UTC — wire shop V6 e unica correzione storica

Il wire shop normalizza solo l'alias legacy uguale allo shop e gli envelope Android riconosciuti; contraddizioni, metadata sconosciuti e IDs non vuoti fuori dominio restano fail-closed. I parent dei prezzi sono derivati bounded sotto autorità corrente; changed_count conta solo i prezzi. Il fallback realmente unscoped conserva body/opID originali e non richiede parent bridge. Intent storici non vengono divisi o relabelled. L'eccezione5→6 richiede plain PayloadValidation, producer completo, owner/shop/device e baseline/ACK/body same-generation, tutti verificati prima del claim atomico; errore/cancel conserva6 e impedisce un nuovo tentativo. Il tag durabile delle nuove failure V6 le esclude da questa eccezione. Nessuna modifica di max retry ordinario, schema, UI, timeout o guard cloud.

### Fix — 2026-10-07 UTC — cardinalità ACK e inbound canonical-owned

La receipt attiva conta solo il keyset canonico più ACK same-generation già validati dalla prova fisica strict; non accetta righe pulite arbitrarie. Guard prefetch e CAS al commit escludono legacy catalogo/prezzi/History sotto A. Il consumer automatico rilascia BOOTSTRAP prima del drain e del bounded push locale già esistente, così gli intent durabili non dipendono da un segnale RAM. Full manual e History preservano outbound, contatori ed errori effettivi: un errore catalogo non diventa un defer con summary nulla; pending non aggiorna last-success/completed. Tutti gli assert/budget originali e gli ID precedenti restano invariati.

### Fix — 2026-10-07 UTC — disponibilità locale e prove ordinarie

Applicati i tre delta riprodotti descritti nella nuova Execution: preservazione delle capacità locali qualificate durante recovery cloud e completamento; distinzione del marker A→B dal vero no-work; vincolo di generazione nella deroga prezzi pendenti. Dinieghi espliciti, cutover forte, prova fisica/CAS, ACK e marker recovery strict restano protetti. Review e gate locali finali approvati/passati; nessun DONE globale o successo live dedotto.


### Esecuzione / Fix — 2026-10-05 — disponibilità locale e root reale Android

Mandato originale e addenda utente 2026-10-04 preservati; questa voce riguarda il nuovo delta sul worktree isolato `codex/android-local-availability-20261004`, baseline main `fe0927c379082925864b200b747bf9d152c5b750`. Lo stato resta FIX. Checkout primario (Gradle/libs/wrapper e file IDE preesistenti), MASTER/Planning/backlog, harness Excel e altre lane preservati. Evidenza portabile: [ledger](evidence/TASK-143/local-availability-20261005/ledger.json), [limiti e ricevute](evidence/TASK-143/local-availability-20261005/README.md). C resta unico writer del report ecosistema; questo log non ne sostituisce la matrice.

**File modificati (percorsi completi e hash nel producer/ledger):**
- `MerchandiseControlApplication.kt` — qualificazione fisica locale in IO, restore del contesto confermato prima della verifica remota e separazione delle autorità locali/cloud.
- `CatalogSyncStateTracker.kt`, `Task126BusinessDataScopeRuntimeGuard.kt`, `Task126SyncPolicy.kt` — letture/scritture sullo stesso snapshot qualificato durante checking/retry/recovery; foreign/unknown/unbound/revoked restano protetti.
- `ShopContext.kt`, `ShopDeviceRegistrationRemoteDataSource.kt` — cache bounded per owner/shop già confermato e diniego dispositivo autorevole durevole; 401 refresh distinto da 403 denial.
- `InventoryRepository.kt`, `BusinessWriteAttempt.kt`, `InventoryCatalogRemoteRows.kt`, `SyncEventModels.kt` — stesso outbox esistente con tentativo wire immutabile, identità sealed/readback prima HTTP, replay prima del nuovo lavoro, ACK della sola revisione inviata e gestione del Save successivo.
- `LocalAcknowledgedBodies.kt`, `HistoryEntryDao.kt` — prova sparsa del body esatto realmente ACKed e qualificazione fisica bounded; mantenimento del checkpoint server originale e rifiuto del tamper clean Product/History/Price.
- `ProductRemoteRefDao.kt` — conferma del singolo prodotto/revisione corrente indipendente da altro pending, prezzi non bridged e receipt pertinenti esclusi dal successo prematuro.
- `ShopSyncRecoveryCoordinator.kt`, `SyncRecoveryModels.kt`, `RecoveryPageProgress.kt` — ledger/proiezione/cursore di pagina atomici sotto A originale, resume senza riscaricare pagine accettate, cutover con scope/device/G/journal CAS, preservazione del commit durevole su fault ordinario postcommit e budget storage con scratch TEMP.
- `SameScopeRecoveryOverlay.kt` — stesso intento/payload A conservato durante cutover; merge dei soli campi dirty, prova owned History e mapping esatto del prezzo A con ACK perso; nessuna History/Price duplicata. Sorgente staging copiata con cursore e prepared statement nelle sole TEMP della transazione, senza ATTACH/DETACH o cambio WAL.
- `DatabaseViewModel.kt` — editor field-aware rispetto alla baseline reale; conferma del last saved ID dal DAO; null shop transitorio non cancella lo scope immagini valido.
- `NavGraph.kt` — AppNavGraphContent reale condiviso dal wrapper produzione, shell/tabs navigabili, guard business prima della composizione e altezza NavigationBar adattata al font.
- `DatabaseScreen.kt`, `DatabaseScreenComponents.kt` — feedback locale/pending e conferma remota realmente provata; tag dei controlli esistenti per il test integrato reale.
- `OptionsScreen.kt` — dipendenza auth passata coerentemente e account title/email a larghezza stabile con wrapping.
- `CloudSyncIndicator.kt` — progress debounced e animazione solo durante lavoro attivo; errore persistente statico.
- `values/strings.xml`, `values-en/strings.xml`, `values-es/strings.xml`, `values-zh/strings.xml` — successo locale distinto dalla conferma cloud nelle quattro lingue.
- `DefaultInventoryRepositoryTest.kt`, `ShopSyncRecoveryCoordinatorTest.kt` — regressioni reali A/lost ACK/B/cutover/replay/reopen, body corruption e fault ordinario postcommit, mantenendo i guard originali.
- `ShopContextTest.kt`, `ShopContextOfflineAuthorizationTest.kt`, `Task126BusinessDataScopeRuntimeGuardTest.kt`, `BusinessContentGateTest.kt`, `ProductBaselineFingerprintTest.kt` — checking/offline/denial/scope/capability e confronto del body originale; nessun READY fabbricato.
- `Task139ShopSyncRecoveryForceStopDeviceTest.kt` — sola visibilità interna della fixture canonica riusata; `LocalAvailabilityRootDeviceTest.kt` — actual root/Room/ViewModel/AutoSync con rete held prima degli input, bootstrap vuoto, font1.6/quattro lingue, draft/focus/query/scroll e reopen. Dopo root14 aggiunta capture BEFORE del secondo draft; root15 main selector1 PASS con coppia visuale del medesimo draft prima/dopo C (font/empty restano prove separate root14).
- `docs/TASKS/evidence/TASK-143/local-availability-20261005/*` e questa sezione Fix — ricevute, hash, screenshot sintetici originali e confini della prova.

**Azioni eseguite:**
1. Distinte identità/autorizzazione, integrità locale e freschezza cloud: pending/conflict non diventano un blocco globale sui dati sicuri dello stesso scope.
2. Preservate le operazioni già inviate byte per byte. Core reale copre Product/History/Price A→ACK perso/tardivo→B stesso/differenti campi→cutover/reopen→replay A originale→ACK B; drain ripetuti senza duplicati. Nessuna riscrittura di A dai valori B o deduzione di ACK dallo snapshot.
3. Il test actual root ha trovato DAO hot/VM stale mentre DAO fresh provava ACK; execSQL ATTACH disabilita WAL e perde TEMP Room. I candidati prepared ATTACH/DETACH e public deferred cleanup hanno FAIL preservati. Driver ufficiale2.6.2 mappa DEFERRED a reader; rimossi i workaround, ora source/overlay TEMP e commit principale unici mantengono WAL e invalidazioni. Non ricreate tabelle interne Room e non forzata conferma UI.
4. Nuovo fault ordinario dopo commit riproduce RED1: atteso activated_cleanup_pending, ottenuto staging. Catch normale ora riusa la stessa prova durevole scoped/CAS già presente nella cancellazione. Test desiderato invariato GREEN in Recovery133; nuova istanza completa il cleanup della stessa C/G, non ridownload implicito.
5. **UI/UX intenzionale:** copy Saved on device/cloud pending e conferma per record (chiarezza); error progress statico/debounced (stabilità); account wrapping e altezza/font NavigationBar in it/en/es/zh (leggibilità). Nessuna feature Android rimossa o business logic spostata nei composable.
6. `android-root-temp-source-14`: actual3 PASS/0 FAIL/0 SKIP su emulator5556 isolato. Held prima di tab/search/editor/Save locale durevole; secondo draft ancora unsaved e focused conservato al cutover effettivo, tab/query/scroll invariati; normale AutoSync conferma DAO/VM/UI e secondo Save, file-backed reopen qualifica. Bootstrap vuoto e Options/Nav quattro lingue font1.6 PASS. APK e sorgenti pin/hash nelle ricevute,10 PNG originali validi; non SDK wiring dell'intera Application o backend autenticato.
7. `android-temp-source-current-core-01`:505=504 PASS/0 FAIL/1 SKIP preesistente. Repository222=221P1S; Recovery133P; DatabaseVM61P; ExcelVM52P; ShopContext17P/offline6P; RuntimeGuard8P; ContentGate4P; Fingerprint2P. Producer `bbd1f147003667a089d09e0f317114eac6228a1c2897dd2be791aa3dfa5b1ded` uguale a root14 per quel baseline; due file produttivi poi cambiati dal fix scoped delete descritto sotto. Sono test JVM/Robolectric, non UI Compose/Espresso.

8. Review v2 UX APPROVED; dati CHANGES_REQUESTED per prezzo nuovo ACKed → deleteProduct legittimo → reopen. RED1:1 PASS/1 FAIL, assertion desiderata di qualificazione fallisce; controllo raw cascade passa. Correzione minima `InventoryRepository.kt`/`LocalAcknowledgedBodies.kt`: prima della FK cascade, nella stessa transazione/tombstone, ritirate soltanto prove private di prezzi del padre verificato, identità binding/device/G e body esatto validati con join/keyset bounded256. Canonical C/manifest e outbox non modificati. GREEN2:3 PASS/0 FAIL/0 SKIP, medesimo test desiderato più controllo corrupt body con rollback prodotto/tombstone/prove. Primo tentativo GREEN non compilato (array type), runtime NON ESEGUITO. Ricevute/XML portabili separati; nuovo freeze v3 e gate canonici ancora pendenti.

9. Root15 main selector actual1 PASS/0 FAIL/0 SKIP, build0,5PNG root freschi inclusa la coppia stesso draft BEFORE/AFTER C, input/focus/tab/query/scroll e drain/ACK/reopen invariati. Le Options PNG esportate dal device non sono nuovi test root15; quattro lingue/empty riusate da root14 senza cambi UX. Receipt `e76dcdfdd641acb384af278f400f8220bbbf9897b43163a29e9a704a21cefc71`.
10. Review v3 UX APPROVED/equivalente, P1 delete chiuso; P2 guard PRICE revision deve essere0 come nel qualificatore fisico. Test desiderato nuova revisione corrotta RED1:0 PASS/1 FAIL; aggiunto solo `identity.revision == 0L` prima del retire. GREEN1:4 PASS/0 FAIL/0 SKIP, rollback padre/tombstone/prove e pending/C invariati. Root15 precede soltanto questo guard delete non invocato nel caso root; UI/cutover source invariati. Freeze v4/re-review dello stretto delta e gate finali ancora pendenti.

11. V4 entrambe review APPROVED (stessi2reviewer),72/72 hash controllati, guard revisione/body/scoped delete/canonical/pending e root15 stessa bozza verificati. Canonico `android-final-canonical-v4-01`:assembleDebug/lint/test/assembleDebugAndroidTest PASS, source72 invariato,76 XML/1171=1164 PASS/0 FAIL/7 SKIP preesistenti. Repository/Recovery/DatabaseVM/ExcelVM inclusi full; nessun nuovo warning Kotlin/deprecation. Lint0 errori/59 warning,5 nuovi advisory ShopContext motivati:2 apply-vs-commit contrari alla persistenza sincrona cache confermata e3 KTX stilistici (device denial verifica Boolean commit). Motivazione portabile nel ledger/classification; nessun suppression o cambio comportamento. CI/integrazione/TEST/primary auth ancora pendenti.

**Check obbligatori correnti:**
| Check | Stato | Evidenza / limite |
|---|---|---|
| Build Gradle | ESEGUITO | assembleDebug + assembleDebugAndroidTest canonico v4 PASS; root15 capture/device PASS precede solo il guard prezzo delete non invocato in quel scenario |
| Lint | ESEGUITO | lint canonico v4 PASS,0 errori/59 warning;5 advisory nuovi motivati nel classification, nessun warning/deprecation Kotlin |
| Warning nuovi | ESEGUITO per build/core | nessun nuovo warning Kotlin/deprecation;5 lint advisory di storage/KTX esplicitamente motivati (protocollo consente motivazione); class-sharing JVM preesistente |
| Coerenza planning | ESEGUITO | task/addenda correnti, stesso motore/outbox/schema/API/dipendenze; scope/device/lease/CAS conservati |
| Criteri accettazione | NON ESEGUITO integralmente | stato individuale corrente sotto; niente DONE o acceptance live dai fake |

**Baseline regressione TASK-004:** test eseguiti Repository, DatabaseVM, ExcelVM e adiacenti sopra; aggiornati test dei confini realmente cambiati e nuovo fault postcommit. I61 DatabaseVM originali e52 ExcelVM sono verdi; null-shop image regression corretta in produzione, assertion non indebolita. Limiti: CI finale, primary SDK/authenticated flow, hardware e prestazioni non sostituiti dalla suite.

**Criteri individuali — corrente candidate, non chiusura finale:**
| Criterio | Stato | Evidenza / limite |
|---|---|---|
| CA-01 | ESEGUITO sorgente/preservazione checkout; NON ESEGUIBILE baseline dati attuale | PR17 integrata/main37505e11 e dirty byte-identici; baseline preinstall fermata dal Mac locked attuale |
| CA-02 | ESEGUITO locale | intento/storage e root Save held +secondo draft/C/reopen; collaudo live in CA07 |
| CA-03 | ESEGUITO Android controllato; NON ESEGUITO controparte/live | root tab/query/scroll/editor conservati; iOS owner separato |
| CA-04 | ESEGUITO locale; NON ESEGUITO live nuovo | Product/History/Price A/B/ACK/replay/C/reopen reali controllati; staging autenticato non eseguito sul nuovo candidato |
| CA-05 | NON ESEGUITO nuovo closeout | vecchie PR/CI distinte; iOS nuovo delta ancora in esecuzione dal suo unico writer |
| CA-06 | ESEGUITO tracciamento; NON ESEGUITO completo | matrice C mantiene lane runtime/hardware/UX mancanti; non importata readiness POS |
| CA-07 | NON ESEGUIBILE target principale attuale; NON ESEGUITO live finale | root14/15 controllati verdi, canonico/CI1171 verificati; TEST install e A↔I/offline/reconnect/reopen fermati dal nuovo Mac locked, iOS candidato ancora in esecuzione |
| CA-08 | ESEGUITO regressione JVM pertinente; NON ESEGUITO runtime completo | ExcelVM52 e repository; stesso workbook/immagini/hardware non rimpiazzati; harness sospeso |
| CA-09 | NON ESEGUITO | nessun campione dataset/PSS/CSV04 qualificato nuovo; tempi runner non sono budget1s/100ms/300ms/500ms/~3s |
| CA-10 | ESEGUITO locale | RED/GREEN/core/root15, canonico1164 PASS/7 SKIP, lint motivato, build/AndroidTest compile; live separato |
| CA-11 | ESEGUITO Android review/CI/merge | due APPROVED v4; PR17 head01f87b2/CI37357374445 e main37505e11/CI37359159127 SUCCESS; entrambi XML1164 PASS/0 FAIL/7 SKIP |
| CA-12 | ESEGUITO log; NON ESEGUITO chiusura | report consolidato soltanto C; task FIX e nessuna percentuale/GO |

**Incertezze:**
- Primo install nuovo profilo TEST e SDK Application, autenticazione/convergenza e misure reali non provati da controlled root. Il check supportato fresco `cua.getApp(Android Studio)` alle18:59:04Z ha restituito Mac locked e richiesta di sblocco manuale: BLOCKED_EXTERNAL attuale, UIinput/baseline/install0, nessun canale alternativo; richiesta umana pendente. I precedenti deny restano storici.
- TEMP source scratch è coperto dal nuovo headroom conservativo; costo sul dataset reale da misurare, nessuna promessa3s dal fixture.

**Handoff notes:**
- Android integrato indipendentemente da iOS: PR17/main37505e11 e CI head/main SUCCESS, checkout principale riconciliato preservando tre dirty/untracked hash. APK full TEST88e84eef con otto campi DEX/tipi e firma precedenti esatti, sorgenti app/test36 equivalenti al merge; nessuna installazione nuova. Dopo risposta umana di Mac sbloccato: nuovo controllo supportato su condizione cambiata, baseline minima fresca/redatta e install-r preservativa, readback e collaudo autenticato; nessun nuovo GO, reset/wipe o vecchia trace.
- Nessun deploy produzione/store, migration/SQL155/old trace, nuovo framework/owner o riattivazione Excel/Win7. Se fresh OS input deny, fermare la sola lane interessata senza bypass e documentare motivo attuale.

**Aggiornamento Git/TEST e limite runtime — 2026-10-05, 19:04 UTC:**
- File documentazione aggiornati: questo solo log Fix e README; aggiunte cinque ricevute JSON portabili di profilo TEST, preservazione checkout, CI head, integrazione e blocco UI attuale. Nessun sorgente/test/risorsa/build config modificato dopo v4.
- PR17 merge normale `37505e11d74fb6625df180771322ae3cef5eae10`, tree app identico al head approvato `01f87b2`; CI head/main SUCCESS, entrambi76XML/1171=1164 PASS/0 FAIL/7 SKIP verificati dagli archivi ufficiali soltanto in memoria. Profilo full TEST APK `88e84eef19332c8bff0dcb00d83e18fe960de097b1909e1e5348c739f17d3225`, firma e otto costanti DEX verificati senza esportarne i valori.
- Check di questo delta solo documentazione: BUILD/LINT/WARNING N/A; coerenza planning e criteri ESEGUITI con stati individuali sopra, stato task FIX. I gate di produzione già PASS restano riferiti al candidato invariato; nessuna reinstallazione o collaudo autenticato dedotto.
- Baseline dati/in-place/runtime/PSS/CSV04 nuovi NOT_RUN, perché il tool UI corrente richiede lo sblocco manuale prima di proseguire nella lane primaria. Preparazione read-only non eseguita; nessuna continuità auth/data dell'installazione mai effettuata dichiarata. Il solo successo Git/CI non chiude CA07/09/12.

### Fix — 2026-10-03 UTC — chiusura dei due failure ONE76 e port invariato

Il `finally` Task126 ora conserva il registry fino alla completion reale, anche con cleanup NonCancellable e cancellazione LAZY. È un fix del normale confine business oltre che del caller DEBUG. La fixture response-loss usa lo stesso SDK/session/origin con una normale RPC precedente completamente consumata, poi verifica il riuso del socket prima del fault dopo il body completo: nessun retry manuale/client o parametro motore nuovo. I 76 assert/ID rimangono; ONE76 attempt02 è PASS. Il precedente NOT_PROVEN e il primo 74/2 non sono riscritti come successi.

Il port CommonRoot copia gli 11 leaf approvati senza alterare codice, test, build config o dipendenze. Il precedente “nessun ulteriore fix di produzione” appartiene alla slice source7fa; questo delta include il fix Task126. Review e risultati locali sono vincolati al nuovo source264, senza nuova audit globale o prova live. MASTER, Planning raw780ded e stato FIX restano preservati.


### R-A09/R-A10 — consolidamento documentale dei gate finali locali, 2026-10-02 UTC

Nessun ulteriore fix di produzione o test in questa fase. Il freeze7fa resta identico attraverso il mirato442, il canonico1.077, i 5 Compose effettivi e il candidato TEST bd0; i dettagli verificabili sono nel [ledger locale](evidence/TASK-143/android-ra09-ra10-local-gates-ledger.json) e nella nuova Execution. Review c6c5, supplemento cleanup236bed e review candidato36bf approvano soltanto i rispettivi risultati locali. Il false immediato della serial absence e SIGTERM owner restano storici; la successiva osservazione salvata risolve la sola cleanup. Stato FIX, CI exact-SHA e accettazione autenticata/convergenza ancora da completare sotto il coordinamento root.

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

### Handoff — 2026-10-08 UTC — EMPTY/FAB verificato, writer rilasciato

Il residuo EMPTY/FAB registrato nell'handoff tab/reporter precedente è ora corretto nel perimetro filtrato e verificato: canonico04 PASS1250/1243PASS/7sameSKIP,44 firme lint invarianti/0warning nuovi; Compose8 PASS e24 PNG validi letti a risoluzione originale. RED4503, padding-only NOT_PASSce378 e Row probe con EN1600byte restano storici immutati; EN160 finale valido. Test8/assert e sette metodi/helper precedenti intatti, nonfiltered EMPTY/font/stringhe/FAB invariati; nessun risultato futuro anticipato.

Fonte ferma; dopo i gate cambia soltanto questo Task, altri537 file identici a d51. Root gestisce staging selettivo dei soli tre percorsi, Git/CI exact-SHA, ProperTEST e installazione preservativa autorizzata. Questa lane non esegue ulteriori test, build, Git o input nativi. Reporter native capture NON ESEGUITA e holder ricerca UNKNOWN; accettazione primaria/auth/business/iOS/performance/QA globale separata e aperta. Stato FIX, non DONE; nessuna modifica a Master/Planning/criteri/Chiusura.

### Handoff — 2026-10-08 UTC — slice tab/reporter verificata localmente, writer rilasciato

Gate03 actual1250 (1243PASS/7sameSKIP), build/lint/0nuovi warning e GREEN7/16PNG circoscritti PASS. Il solo Task viene aggiornato dopo il gate; altri537 leaf pubblici identici, Master/Planning invariati. Le precedenti frasi «test pendenti» sono snapshot di preparazione superati da questa Execution; la raccolta nativa del reporter resta invece NON ESEGUITA e holder ricerca UNKNOWN. Fonte ferma, writer rilasciato; root gestisce review/Git/CI e installazione autorizzata separatamente, nessun nuovo gate per soli docs.

Residuo visuale da valutare separatamente: in alcune viste sintetiche vuote IT/ES/ZH a160%, il testo di risultato vuoto interseca il Camera FAB. È fuori dal fix minimo dei tab e non è stato corretto né dichiarato PASS. Etichette complete con wrapping naturale anche interno alle parole; nessuna riduzione del font. Nessuna chiusura globale UI/auth/business/iOS o DONE; task FIX.


### Handoff — 2026-10-08 UTC — diagnostico stack locale preparato, non eseguito

Solo il coordinatore può compilare/qualificare TEST/installare e decidere una raccolta sul processo corrente. Il reporter non forza reentry, non legge journal o dati e non prova READY/convergenza; il blocco ricerca resta senza causa identificata. Fonte congelata, writer rilasciato dopo consegna patch/pin; sei nuovi test ancora NON ESEGUITI. Conservare il precedente fix Excel e tutti i test/assert originali. Nessun nuovo framework o dipendenza.

### Handoff — 2026-10-08 UTC — test Excel congelato, writer rilasciato

Unica predicate modificata in ExcelViewModelTest e sole tre aggiunte documentali owned. Verifica locale/CI sul nuovo delta ancora PENDING; nessuna modifica al blocco ricerca, ai dati, al banner o ai test Compose. Root mantiene ownership di build/review/Git e dispositivo; task FIX, acceptance globale aperta.

### Handoff — 2026-10-08 UTC — banner sync verificato, writer rilasciato

Delta finale sui soli NavGraph, CloudSyncIndicator, LocalAvailabilityRootDeviceTest e questo Task: NavHost stabile/spazio misurato, wrapping del copy, unico test additivo con cinque originali/helper preservati. Full1244, Compose6,8 PNG e review C qualificati in Execution. Nessun sorgente/test cambiato dopo GREEN; root mantiene proprietà di Git/CI/ProperTEST e input/installazione primaria. Non dichiarati DONE, nuova acceptance autenticata o chiusura dei residui tab/iOS/QA globale.

### Handoff — 2026-10-08 UTC — candidato V6 in attesa dei gate finali

Task resta FIX. GREEN03 chiuso17:19:50.857142Z; canonico finale `8917cb02` chiuso17:38:53.533933Z, PG7655 assente. FullDebug1244=1237PASS/7sameSKIP/0FAIL, assembleDebug/lint PASS,44 warning baseline esatti e0 nuovi diagnostici. Source v4 immutabile e review finale APPROVED senza finding. CI/ProperTEST/install e runtime per-record NOT_RUN per questo candidato; integrare solo le ricevute reali delle rispettive lane. I limiti fresh-only legacy e oversize persistito senza witness dedicato restano dichiarati; nessun ulteriore test richiesto dalla review. Root coordina compilerlane, integrazione/ProperTEST/install e live; nessun reset outbox o sesto tentativo da questa lane. Conservare tutti i RED e setupFAIL e il body originale degli intent, distinguendo normalizzazione wire e prova contrattuale locale dalla prova live.

### Handoff — 2026-10-07 UTC — ACK/cardinalità e routing finale

Task resta FIX. Set Debug1207→1219: tutti gli ID/stati originali e7skip preservati,12 test aggiuntivi PASS. RED, setup FAIL, target07 e checker FAIL storico restano immutabili. Review finale e gate locali PASS; commit selettivo solo8Kotlin e queste3insertions owned. Executor I unico watcher/acquisitore della futura PR CI; root unico owner merge/mainCI/properTEST/FF/install e runtime per-record. Nessun reset dati/backend/schema/dependency/auth. La prova ACK non attribuisce il pruning live; pending/cancel/error non sono successo o readiness.

### Handoff aggiornato — 2026-10-07 UTC — freeze recovery/A→B/prezzi

Questa voce descrive il batch corrente sulla baseline `bb203bae`; gli snapshot datati precedenti restano storici. Sorgente 17 file congelato, full1207/1200 PASS/7 SKIP e root Compose5 PASS; ricevute e limiti in Execution. Sono autorizzati staging selettivo dei soli 17 code/test e di questo Task, commit/push/PR normale; parent coordina CI exact-SHA, merge e ProperTEST sul profilo autorizzato. La copia KEYLESS del gate non è il candidato autenticato. Nessun altro writer o job locale attivo; primaria e configurazioni private intatte. Master/Planning immutati, task FIX e accettazione globale ancora aperta.


### CURRENT — 2026-10-03 UTC — checkpoint source264 portato, gate locali actual, Git/live pendenti

Il checkout di consegna è CommonRoot sul branch `codex/android-checkpoint-trace-guard`, base `7e538936bf217fffea2ee2c1bbf548433f984c11`. L'header storico e il Planning descrivono il checkout dedicato di esecuzione (`codex/android-checkpoint-one-trace`, HEAD `814b137fb72db58dab99b0ea4971dc4ef50feb29`): sono mantenuti byte-identici per rispettare il Planning raw approvato. La copia del TASK `57a13619…` prima del port riflette quell'autorizzazione; questo handoff non introduce decisioni nuove. Dedicated source264/TASK/MASTER/HEAD restano immutati per la lane PSS.

Source264 freeze `189d3e27…` e fingerprint map `4c40e81b…` coincidono nel checkout di consegna. Gate effettivi: 76/76 mirati; full JVM 1110 = 1103 PASS + 7 SKIP invariati su 73 XML; assemble/lint e androidTest compile; cinque Compose actual; nuovo TEST `3ccf651e…` e otto metadata verificati sulle ricevute. Dettagli, SHA integrali, conteggi per classe e limiti nel [ledger portabile](evidence/TASK-143/android-checkpoint-current-local-gates-ledger.json). Nessun APK/log/profilo/framework è copiato nel repository. Le qualifiche cleanup Compose, booleani source/pair owner, governance ereditata e JBR post-build sono conservate in Execution e ledger.

Root gestisce i 13 percorsi espliciti (11 code/test + TASK + ledger), review finale del port, commit/push/PR, CI del vero SHA e CI main dopo normale integrazione. I conteggi locali non sono attribuiti alla futura CI. Nessun gate locale è rieseguito da questa lane e nessuna operazione Git/runtime/device/protected IO è compiuta durante il port. L'APK corrente non è installato da questa slice: auth, singola traccia live, checkpoint riuscito, READY, recovery/convergenza, PSS, dodici CA e DONE globale rimangono separati. TASK-143 resta FIX; nessun GO runtime è implicito.


### Handoff corrente R-A09/R-A10 — gate locali verificati, integrazione e live pendenti

**CURRENT — LOCAL_GATES_VERIFIED, task FIX, non DONE.** Freeze7fa: canonico debug **1.070 PASS / 7 SKIP / 0 FAIL / 0 ERROR**, 72 XML; vecchi1.040 ID/status preservati più37 Recovery PASS; lint39/0error/0nuove firme rispetto alla baseline54. KSP e compilazione androidTest effettivi con APK storico847a riutilizzato; successiva esecuzione nuova **Compose5 PASS** sulla pair canonica d24f/847a, cleanup terminale confermata solo dalla lettura salvata23:01:54 (rawfalse/SIGTERM preservati).

Candidato TEST bd0, receipt68a/review36bf: firma/metadati e profilo autorizzato verificati tramite i risultati salvati del builder, local.properties ripristinato e263 input pubblici invariati. Il [ledger portabile](evidence/TASK-143/android-ra09-ra10-local-gates-ledger.json) contiene SHA completi, conteggi per classe, warning e limiti. Nessuna installazione del nuovo TEST, login/auth, READY, convergenza o E2E provati; nessun hook diagnostico futuro incluso.

Il root può preparare commit/PR e CI sullo SHA esatto; mantiene ownership esclusiva di Git e del coordinamento dispositivo autenticato. Questo handoff prevale sulle indicazioni future dei gate locali negli snapshot seguenti; i loro risultati CI/live restano riferiti ai rispettivi sorgenti storici, non al candidato7fa.

**Snapshot precedente — INTEGRATED / CI_VERIFIED, task FIX, non DONE.** PR11/main `0613339f` e CI esatte head/main PASS; iOS PR11/main `2322c5e1` con CI 1.403 PASS/36 SKIP verificata separatamente. Auth nativa PASS nel relativo snapshot; recovery business ancora HTTP500/SQL57014 nella verifica finale anche dopo registry151, convergenza e misure ancora aperte. Il ledger e la nuova Execution prevalgono sui riferimenti futuri degli snapshot sotto. Nessuna distribuzione produzione o chiusura globale inferita.

### Handoff finale R-A07/R-A08 — 2026-10-01

R-A07_R-A08_CODE_AND_LOCAL_GATES_VERIFIED — source18 congelati e revisionati APPROVED,165 mirati PASS; canonico1.040 totali/1.033 PASS/7 SKIP, build/lint PASS e0 warning Kotlin/deprecation. Lint54 contro baseline52:2 UseKtx intenzionali motivati dal checked commit Boolean e accettati dal parent, senza suppression. Cinque Compose Storefront effettivi PASS sul fresh AVD Codex_Mobile_Parity_Final_API_35/emulator-5554; geometria1080×2400/density420, nuova userdata sintetica, nessun accesso a5556/5580. Rimane acceso per le misure coordinate.

APK TEST separato96b3d6134ea50b61e226a14105f8de014ebefa1d8f86c66e3ee740cccac6be70, profilo e firma verificati rispetto a e93 preservato; config primaria/profilo privato/source18 immutati, local.properties worktree ripristinato. Ricevute versionate [manifest finale](evidence/TASK-143/android-ra07-ra08-final-manifest.json), [canonico](evidence/TASK-143/android-ra07-ra08-canonical-receipt.json), [Compose](evidence/TASK-143/android-ra07-ra08-compose-receipt.json), [TEST](evidence/TASK-143/android-ra07-ra08-test-build-receipt.json); raw XML/log/lint/APK disponibili nei percorsi persistenti dichiarati, nessun claim dipende da /tmp. Sourcehash identici attraverso mirato, canonico, build TEST e Compose.

Parent owner di Git/PR/CI exact-SHA, misure before/after e coordinator autenticato; nessun commit/push/device5556 da questa lane. I fake controllati e Compose non provano la convergenza live. Stato task FIX conservato, non DONE; chiusura globale solo dopo i criteri coordinati finali.

**Snapshot executor del primo batch, precedente alle integrazioni PR10/PR11:** EXECUTION_AND_FIX_VALIDATED — sorgente Android congelata, review indipendente R-A01/R-A02/R-A03 risolta e re-review sorgente approvata condizionata ai gate ora PASS. Nessun commit/push/PR eseguito dagli executor. Il parent coordina Review/CA finali/report/commit/PR/CI; non dichiarare integrazione main o distribuzione da questo solo snapshot. Checkout primario/toolchain preesistente preservati. Non includere `.kotlin/sessions/` nei file da integrare.

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
