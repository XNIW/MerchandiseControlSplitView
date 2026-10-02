# TASK-143 — Mobile parity root-cause android

## Stato

- File task: `docs/TASKS/TASK-143-mobile-parity-root-cause-android.md`
- Stato: `FIX`
- Fase: `FIX`
- Responsabile: `CODEX_EXECUTOR_ANDROID`; orchestratore parent, reviewer indipendente separato.
- Data: 2026-09-28
- Baseline: `d7c4953c4ed6bc2a33cc5dbfd009eb862f70feac`
- Branch corrente: `codex/android-recovery-retained-prices` dal main integrato `9306e8a7`; R-A09 in diagnosi/regressione. PR10/11 e registri PR12 preservati come integrazioni precedenti.
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

## Execution

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

## Review

Review indipendente e re-review del primo batch completate; R-A04 scoperto nel successivo collaudo live è stato riprodotto, corretto e revisionato separatamente. Sorgente APPROVED, nessun P0/P1/P2 source aperto dopo R-A01/R-A02/R-A03/R-A04; gate locali aggiornati PASS (970 JVM e 5 Compose). R-A05 sul successivo diniego RPC è anch’esso corretto e revisionato. Gate aggiornato985 totali/978 PASS/7 SKIP; ritest autenticato conferma `checkpoint_resource_exceeded` correttamente classificato, preservando binding, dati e journal. Il blocco server TOAST resta aperto. Evidenza: [independent-review.md](evidence/TASK-143/independent-review.md). La review tecnica non è un'approvazione GitHub di un maintainer né una conferma live.

## Fix

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
