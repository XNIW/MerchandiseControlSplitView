# TASK-139 Android — sync events, recovery atomico e mismatch UX

Stato evidence: `INTERIM / REVIEW`; non costituisce `PASS` globale, `DONE` o
autorizzazione al merge. Il contratto backend definitivo e le prove runtime
cross-platform restano obbligatori.

## Baseline Git e protezione del checkout

- Worktree writer unico Android dedicato a TASK-139.
- Branch: `codex/task-139-product-image-hardening`.
- `HEAD` iniziale e `origin/main`:
  `141ffa07b4ee2b556387ec194fb82b7b76e6a626`; delta `0 0`.
- Il diff TASK-139 è stato preservato senza reset, clean o stash.
- Nessuna reinstallazione, `clear-data`, cancellazione Simulator/Emulator,
  replace sul database autenticato o modifica production in questa lane.

## Modifiche locali verificate finora

- Dialog mismatch Material 3 con titolo e messaggio richiesti ed esattamente
  due azioni visibili. Keep/back/dismiss chiudono soltanto la dialog; il replace
  resta disabilitato senza account e shop verificati. La presentazione
  automatica è una volta per fingerprint e la memoria delle fingerprint è
  bounded a 64. Raggiunto il cap viene salvato un marker che disabilita soltanto
  i successivi auto-show, evitando che un'identità espulsa possa riapparire; la
  CTA `Rivedi` resta sempre disponibile per la riapertura manuale.
- Gli eventi shop-scoped vengono letti soltanto tramite
  `shop_sync_event_page_v1`; il subscriber realtime e il `SELECT` diretto su
  `public.sync_events` sono rimossi dal runtime production.
- Evento supportato con `changed_count > 0` e ID nulli, parziali, duplicati,
  malformati o fuori budget: stato `BLOCKED`, journal recovery durevole e
  watermark invariato. La ri-osservazione dello stesso blocker non consuma il
  budget dei tentativi snapshot.
- Un `event_type` non appartenente al dominio dichiarato viene bloccato prima
  di self-skip/apply, conserva il watermark e crea lo stesso journal recovery
  durevole; non può diventare un no-op applicato con `changed_count = 0`.
- Per `prices`, gli ID prodotto ausiliari non possono superare gli ID prezzo
  primari: tre prezzi dello stesso prodotto sono validi; zero prezzi con soli
  product ID o un prezzo con due product ID sono rifiutati fail-closed.
- History recovery e targeted fetch usano batch massimi di 16 righe/ID; il
  test con 250 ID prova 16 RPC (`15 x 16 + 10`) e nessun full pull.
- Recovery full snapshot in un Room DB staging non osservato dalla UI;
  checkpoint A/B, manifest canonico, readback fisico, relazioni, conteggi,
  digest e lease account/shop/device vengono verificati prima di una singola
  transazione di attivazione sul DB corrente.
- Il coordinator ricostruisce lo scope atteso da account autenticato e shop
  canonico e lo confronta con owner hash, store, local store, protocollo,
  schema ed epoch del caller prima di leggere journal o cloud. Uno scope owner
  forged, anche con journal coerente, produce zero RPC e zero mutazioni.
- In recovery `SAME_SCOPE` il gate considera l'outbox fisica intera, non solo
  quella filtrata per account/shop: l'activation cancella l'intero store e non
  puo' quindi eliminare implicitamente una entry foreign-scope. La fixture
  cross-owner/cross-shop resta intatta e il recovery fallisce chiuso prima del
  checkpoint.
- La regressione A/B include esplicitamente conteggi invariati ma digest
  prodotto diverso: la generation staging non viene attivata e G-old resta
  leggibile, quindi la cardinalità da sola non prova convergenza.
- La transazione di attivazione pubblica insieme dati business, manifest,
  baseline, binding e watermark. Failure/cancel prima del commit preservano la
  generazione precedente; crash/relaunch in
  `activated_cleanup_pending` riprende solo verifica e cleanup.
- I retry automatici sono limitati a cinque per singolo trigger esterno. Il
  contatore durevole continua a governare backoff e diagnostica, ma non blocca
  per sempre una nuova finestra dopo relaunch, foreground o reconnect: cinque
  errori transitori non rendono il journal irrecuperabile.
- Anche i sync manuali full e quick che rilevano `manualFullSyncRequired`
  persistono prima `sync_recovery_required`, rilasciano la flight `MANUAL` e
  solo dopo notificano il requester application-owned. Questo delega allo
  scheduler single-flight gia' esistente senza aspettare un nuovo lifecycle,
  reconnect o relaunch. Le regressioni provano una sola richiesta per ciascun
  percorso e verificano che la flight manuale sia gia' acquisibile da un altro
  owner al momento del callback.
- Il contatore durevole dei tentativi e' saturato a `1.000.000`: valori
  negativi, gia' al cap o `Int.MAX_VALUE` non possono fare wrap a un numero
  negativo e riaprire un percorso di retry non limitato.
- La lease account/shop/device viene ricontrollata anche dopo il callback
  post-activation, dopo il cleanup e dentro la transazione finale che elimina
  il journal. Se cambia durante un resume da `activated_cleanup_pending`, la
  cancellazione del journal viene rollbackata e il resume successivo completa
  senza riscaricare pagine o riesporre G-old.
- Cleanup staging UUID-allowlisted, idempotente e limitato a otto generazioni
  orfane per invocazione.
- I checkpoint sono rifiutati con somme overflow o oltre i ceiling mobile:
  totale 350.000 righe; supplier/category 25.000; history 10.000;
  products/images 100.000; prices 150.000. Il body RPC viene letto in
  streaming prima del JSON decode con cap 8 MiB, oppure 20 MiB per history;
  ogni riga history completamente codificata ha cap 512 KiB.
- Il trasferimento recovery applica inoltre 192 MiB per dominio e 384 MiB
  totali. Il DB staging più `-journal`/`-wal`/`-shm` ha cap 768 MiB e, subito
  prima dell'activation, deve restare spazio pari alla dimensione fisica di
  G-new più quella di G-old più 128 MiB. Questo copre conservativamente
  crescita del DB attivo e rollback journal/WAL mentre lo staging resta
  presente. Il calcolo avviene dentro lo stesso `activationBoundary` che
  serializza il commit, dopo la lease check, evitando una finestra TOCTOU con
  writer precedenti. Boundary esatto, valore inferiore di un byte, input
  negativo e overflow sono coperti da regressioni. Overflow,
  page/row/domain/total budget o spazio insufficiente eliminano lo staging e
  lasciano G-old e il suo manifest invariati.
- Prima di creare la `String` o il DOM JSON, il body RPC bounded passa un guard
  lessicale allocation-free: profondita' massima 64, 250.000 token, stringa
  massima 512 KiB e scalare massimo 256 byte. Strutture dentro stringhe ed
  escape non vengono conteggiati come token. Il boundary converte in failure
  solo `Exception`: `OutOfMemoryError` e gli altri VM error non vengono
  mascherati come un normale errore di contratto. Per HISTORY un secondo scan
  raw allocation-free identifica l'unico array top-level `rows`, attraversa
  quote/escape/nesting e applica il cap 512 KiB a ogni elemento diretto prima
  di creare `String` o DOM; key mancante, escaped, duplicata o non-array viene
  rifiutata fail-closed.
- Il coordinator distingue cancellazione, errori recuperabili ed errori VM:
  `CancellationException` viene rilanciata dopo il journal durevole,
  `Exception` resta nel percorso `RetryRequired`, mentre `OutOfMemoryError` e
  gli altri `Error` non vengono convertiti in retry automatici. Le regressioni
  coprono sia staging sia resume da `activated_cleanup_pending`, verificando
  propagazione, attempt count invariato e nessun nuovo download nel resume.
  Anche decode della baseline, cancellazione del DB staging e parsing UUID
  usano `try/catch (Exception)`: nessun `runCatching` resta nel coordinator a
  mascherare `OutOfMemoryError`, `StackOverflowError` o altri VM `Error`.
- `RecoveryGraph` e le sue dodici collection whole-snapshot sono stati
  rimossi. Le relazioni prodotto/immagine, incluse le tombstone, vengono ora
  verificate con merge-join paginato da Room; FK, apply count e readback
  paginato verificano supplier/category/price. Anche il readback history è
  limitato a 16 righe, quindi non carica 500 payload da 512 KiB insieme.
- Il manifest Room v22, gia' introdotto soltanto nel diff TASK-139 non
  pubblicato, possiede ora l'indice
  `(generationId, domain, idLine)`: `pageByIdLine` non richiede più il
  sort/temp SQLite ripetuto durante il merge-join immagini. La migration test
  verifica con `EXPLAIN QUERY PLAN` sia il primo page sia il cursor successivo:
  indice selezionato e nessun `TEMP B-TREE`.
- Upload/remove immagini ricontrollano la lease anche dentro la transazione
  Room che pubblica `primaryImageVersionId`; la revoca `canWrite` durante una
  chiamata impedisce upload/finalize/apply successivi.
- La cache immagini possiede copie private dei byte scritti e restituisce copie
  difensive: la mutazione del buffer sorgente o di una cache hit non può
  corrompere l'entry in memoria o la successiva lettura disco.
- Download JPEG e response JSON sono letti da `ByteReadChannel` con cap
  `maximum + 1`, prima della materializzazione completa: anche una response
  chunked priva di `Content-Length` viene rifiutata senza crescita RAM
  illimitata.
- Il vecchio harness TASK-072D non tenta più di avviare il subscriber
  `sync_events` rimosso. Non è stata reintrodotta una scorciatoia realtime o
  una lettura diretta.

## Finding ancora aperti al freeze backend

1. Capability producer, envelope eventi, marker anti-late-commit, nome scope
   mixed e cutover dell'outbox legacy devono essere allineati allo shape SQL
   finale. Il client non congela nomi o fallback inventati.
2. I producer Android storici chiamano ancora `record_sync_event`; in modalità
   trigger-authoritative v2 dovranno produrre zero recorder/nuove entry outbox.
   Le entry event-only precedenti richiedono cutover scope-safe e recovery
   durevole, non invio cieco né cancellazione prima del journal.
3. Un watermark numerico non rileva da solo una transazione con ID allocato
   prima ma commit successivo. Dopo il freeze servono checkpoint/marker dopo
   drain e tail, con recovery se il marker cambia anche a `maxId` invariato.
4. Il freeze deve serializzare `sync_event_safe_row_v1.id` e il cursor evento
   come stringhe decimali canoniche (`bigint::text`), non come numeri JSON. Il
   decoder Android dovra' provare valori oltre `2^53`, leading zero e overflow
   prima della conversione al `Long` usato dal watermark Room.
5. Il contratto backend deve escludere dai prezzi recovery le righe il cui
   prodotto parent è tombstoned, oppure definire esplicitamente un modello
   diverso. Android materializza soltanto prezzi con prodotto attivo; il
   contratto opposto causa `recovery_stage_apply_incomplete` o
   `recovery_price_product_invalid` e retry bounded senza convergenza.
6. I ceiling mobile sopra sono ora fail-closed e verificati. Restano da
   allineare al freeze i field backend exact `payloadBytes`,
   `oversizeRowCount` e `payloadBudgets`; nessun field provvisorio viene
   accettato o ignorato come prova di convergenza.
7. Il response `read-urls` immagini deve includere metadata verificati. Android
   dovrà controllare SHA-256, byte, dimensioni e MIME sia sul download sia su
   cache hit, con sidecar bounded, eviction su drift e metadata invarianti nel
   refresh 401.
8. I live harness storici TASK-072C/D e TASK-103 contengono ancora chiamate al
   metodo legacy `fetchSyncEventsAfter`, che ora fallisce chiuso. Compilano, ma
   non possono essere usati come evidence runtime finché non vengono migrati
   all'envelope RPC shop-scoped exact; nessun loro risultato è dichiarato PASS.

## Review indipendente Android

La prima review statica/read-only aveva tracciato tre `P2`: indice `idLine`,
cap history post-DOM e headroom G-old non provata. Il follow-up
contract-independent li ha chiusi con indice nella migration v22 non
pubblicata, scanner raw pre-DOM e preflight G-old + G-new + margine con boundary
test. Una seconda review indipendente sul diff aggiornato ha poi concluso
`P0=0`, `P1=1`, `P2=1`: il P1 era il mancato risveglio immediato del recovery
dopo full/quick manuale; il P2 era la conversione di VM Error in
`RetryRequired`. Il primo follow-up ha individuato un residuo P1 nei
`runCatching` del decoder baseline e del delete staging e un P2 di copertura
sul wiring reale/dedup. Dopo la correzione, la re-review read-only dello stesso
delta conclude `P0=0`, `P1=0`, `P2=0`: i test esercitano i boundary effettivi,
la factory ViewModel reale e il riuso della stessa job application-owned tra
callback manuale e trigger rete. Una review indipendente finale sul diff
congelato resta obbligatoria; questo stato non autorizza merge o `DONE`.

## Gate reali intermedi

| Gate | Risultato reale |
|---|---|
| `DefaultInventoryRepositoryTest` mirato | run storico `201` test; ultimo targeted history `1/1`, `0` failure/error/skip |
| `ProductImageServiceTest` mirato | ultimo run `25/25`, `0` failure/error/skip |
| `ProductImageSharedContractTest` | ultimo run `6/6`, `0` failure/error/skip; include body streaming exact-limit/overflow |
| Targeted recovery/contract/UI precedente | `68/68`, `0` failure/error/skip: application `5`, repository history `1`, coordinator `27`, datasource `14`, scope binding `16`, mismatch dialog `5`; antecedente all'ultimo hardening locale |
| Hardening locale pre-review combinato | `67/67`, `0` failure/error/skip: coordinator `29`, datasource `16`, migration `22`; `BUILD SUCCESSFUL in 5s` |
| Fix review manual-recovery/VM-error mirato finale | `69/69`, `0` failure/error/skip: coordinator `32`, ViewModel `31`, Application `6`; `BUILD SUCCESSFUL in 7s` |
| `ShopSyncRecoveryCoordinatorTest` | ultimo run `32/32`, `0` failure/error/skip; include scope/outbox/lease/cleanup bounded, overflow e boundary headroom G-old + G-new + margine, merge-join, G-old/manifest invariati e VM Error non convertiti in retry in staging, baseline decode o delete staging |
| `CatalogSyncViewModelTest` | ultimo run `31/31`, `0` failure/error/skip; include richiesta recovery immediata e singola dopo il rilascio della flight manuale, sia full sia quick |
| `MerchandiseControlApplicationTest` | ultimo run `6/6`, `0` failure/error/skip; include retry bounded, factory callback reale e dedup single-flight tra trigger manuale/rete |
| `SupabaseShopSyncReadRemoteDataSourceTest` | ultimo run `16/16`, `0` failure/error/skip; streaming exact/max+1, guard lessicale e row HISTORY raw pre-DOM con escape/nesting/shape, VM error non mascherato, cap endpoint e count overflow |
| `AppDatabaseMigrationTest` | ultimo run completo `22/22`, `0` failure/error/skip; migration 21→22 e Room schema esportato dichiarano l'indice manifest, query plan indicizzato senza `TEMP B-TREE` |
| `BusinessScopeMismatchDialogTest` | ultimo run `5/5`, `0` failure/error/skip; include cap persistito senza riapertura di identità espulse |
| `SyncEventContractTest` | ultimo run `4/4`, `0` failure/error/skip |
| mismatch `event_type`/domain integrato | `1/1`, `0` failure/error/skip; zero fetch catalogo targeted/full e watermark invariato |
| JVM completo, run conservativo corrente | `784` test: `779` pass, `5` skip intenzionali, `0` failure/error; `BUILD SUCCESSFUL in 43s`; resta da rieseguire dopo freeze |
| `assembleDebug` | exit `0` sul hardening corrente; gate build/lint combinato `BUILD SUCCESSFUL in 56s`; da rieseguire dopo freeze |
| `assembleDebugAndroidTest` | primo run storico FAIL compile sul riferimento harness realtime rimosso; patch mirata; exit `0` sul hardening corrente, da rieseguire dopo freeze |
| `lintDebug` | exit `0` sul hardening corrente, `0` error e `24` warning: versioni/dependency `12`, POI third-party trust-manager `2`, unused resource `6`, usable-space recommendation `2`, KTX recommendation `2`; da rieseguire dopo freeze |
| `git diff --check` | exit `0`, nessun output |
| scan credenziali scoped | nessun secret o signed URL reale; solo simboli, documentazione e fixture sintetiche |

I cinque skip JVM sono: configurazione Supabase realtime assente; workbook
Shopping Hogar locale opzionale assente; Drive batch audit, oracle loop e
oracle v2 riservati ai rispettivi task dedicati. Nessuno è stato contato come
PASS TASK-139.

### Failure storici conservati

- Il primo `./gradlew test` ordinario è terminato con exit `134` per un crash
  JVM Temurin `17.0.17+10`, `SIGSEGV` nel compilatore C2
  `Node::uncast(bool)`. I file temporanei `hs_err`/`replay` sono stati rimossi
  dagli artifact; il rerun con `-XX:TieredStopAtLevel=1 --max-workers=1` ha
  prodotto il conteggio completo sopra.
- Un tentativo preliminare di budget staging con valori non ancora concordati
  ha prodotto `10/15` failure recovery a causa di una lettura `PRAGMA`
  incompatibile nel test Robolectric. Il tentativo è stato rimosso integralmente
  all'epoca; dopo l'allineamento coordinato dei ceiling, la nuova implementazione
  indipendente non usa quella helper e il run recovery corrente è `24/24`.
- Il primo compile del targeted dopo le due regressioni scope/outbox e' fallito
  per uso posizionale errato del costruttore `SyncEventDeviceState` nella sola
  fixture. La fixture e' stata corretta con argomenti nominati; il rerun reale
  completo della classe e' `24/24` PASS.
- Il primo compile del follow-up headroom/raw-row è fallito con due errori
  Kotlin: `SupportSQLiteDatabase.path` nullable e default value non ammesso sul
  metodo astratto di una `fun interface`. Il path è ora validato esplicitamente
  e tutti i call site passano il quarto argomento; il rerun combinato reale è
  `67/67`, senza failure/error/skip.
- Il primo comando mirato manual-recovery/VM-error e' fallito prima della
  compilazione perché `ANDROID_HOME`/`sdk.dir` non erano disponibili in quel
  nuovo shell; il rerun con l'SDK locale esplicito ha raggiunto i test.
- Il primo run delle nuove regressioni ha eseguito `61` test con `4` failure:
  due `assertSame` non consideravano la stacktrace-recovery di coroutine, che
  propaga un wrapper dello stesso `OutOfMemoryError` con l'istanza originale
  come causa; due assert UI pretendevano il suggerimento full mentre lo stato
  e' correttamente fail-closed in `ERROR_RECOVERABLE`. I test sono stati
  ristretti alle proprietà corrette senza indebolire il production code; il
  rerun reale è `62/62`. La review successiva ha scoperto i `runCatching`
  residui prima dei catch esterni; dopo i nuovi test sui boundary effettivi e
  sul wiring factory/single-flight, il targeted finale è `69/69` e la suite
  completa è `784` test senza failure.

## Runtime non eseguito in questa iterazione

- Instrumentation su Emulator autenticato: `NOT_RUN`, in attesa del freeze e
  senza autorizzazione a reinstallare o alterare il database reale.
- Replace distruttivo: `0` in questa iterazione.
- Parity live Admin/Android/iOS, latenza p50/p95/max e immagini post-replace:
  `NOT_RUN`; appartengono al gate E2E coordinato dopo i contratti finali.

## Verdetto intermedio

Android resta `REVIEW`. Atomicità e resource bounds locali sono implementati e
i gate JVM/build/lint correnti sono verdi, ma capability/cutover/late-commit,
field budget backend exact, metadata immagini e prova autenticata finale sono
blocker reali prima di qualsiasi pubblicazione o dichiarazione di convergenza.
