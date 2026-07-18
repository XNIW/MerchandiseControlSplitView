# TASK-138 — 09 Android Optimization Review

## Stato

- Data: `2026-07-18`.
- Worktree: lane Android TASK-138.
- Base locale e `origin/main` al recovery:
  `69c36c2c4e3e331da4ca6ce76524cf766d0a36f1`.
- Stato implementazione Android: `READY_FOR_REVIEW`, mai `DONE`.
- Stato verifica complessiva Android: `READY_FOR_REVIEW`; parity locale e gate
  visuale sintetico sono stati completati senza allentare il contratto origin.
- Gate mutativo cross-platform opt-in: primo tentativo fermato `400` prima di
  ogni write; fix Android applicato, rerun `replace` e cross-reader Admin
  completati. Il mode `remove` resta intenzionalmente dopo il confronto iOS.
- Nessun commit, push, merge, deploy o modifica production.
- Nessuna nuova dipendenza.

Il prompt utente del `2026-07-18` ha autorizzato esplicitamente
l'optimization pass mentre il mirror era gia in `ACTIVE / REVIEW`. La lane
ritorna quindi a `REVIEW` senza cambiare lo stato globale in `DONE`.

## Requisito

Completare un pass bounded sulla pipeline Product Images Android per:

- evitare la materializzazione integrale dell'input prima del downsample;
- riusare signed URL ancora valide senza persisterle;
- introdurre cache memory/disk con limiti reali ed eviction LRU;
- mostrare in dettaglio `placeholder -> thumb -> main` con decode fuori main
  thread e rilascio delle bitmap;
- mantenere main e thumb come soli due oggetti canonici;
- rendere upload/cancel/retry deterministici;
- produrre evidence reale senza dedurre screenshot, parity o metriche non
  misurate.

## Recovery iniziale — Fase 0

L'audit richiesto e stato eseguito prima delle modifiche e ripetuto sul diff
finale. Risultato finale coerente con lo stato recuperato:

| Check | Risultato |
|---|---|
| `git rev-parse --show-toplevel` | worktree Android TASK-138 corretto |
| `git remote -v` | solo `origin` atteso |
| `git branch --show-current` | vuoto: `HEAD` detached preesistente |
| `git status --short --branch` | solo diff/untracked TASK-138 previsti |
| `git diff --stat` / `--name-status` | nessun file TASK-137 |
| `git diff --check` | `PASS`, output vuoto |
| `git diff --cached --stat` | vuoto; nessun file staged |
| `git ls-files --others --exclude-standard` | soli file TASK-138/test/evidence |
| `git rev-parse HEAD` | `69c36c2c4e3e331da4ca6ce76524cf766d0a36f1` |
| `git rev-parse origin/main` | `69c36c2c4e3e331da4ca6ce76524cf766d0a36f1` |
| `local.properties` | non tracked e ignorato |
| path SDK locali in codice/evidence TASK-138 | nessuno aggiunto |
| diff TASK-137 storico | `0` file |

File TASK-138 gia presenti al recovery: runtime Product Images, UI database e
editor, ViewModel, stringhe localizzate, test cache/ViewModel, Master Plan,
mirror ed evidence Android. Nessun file unrelated e stato ripristinato o
modificato: l'audit non ha trovato file TASK-137 o altre lane nel diff Android.

## Stato prima dell'optimization pass e gap

La prima execution TASK-138 aveva gia batch/dedup/coalescing, limite quattro
download, cache scoped, stale guard, progress upload e un device test 48 MP.
La review bounded ha pero trovato quattro gap concreti:

1. cache senza budget memory/disk LRU misurato in byte reali;
2. signed URL risolta di nuovo dopo ogni eviction dei byte;
3. processor che materializzava l'intero input prima del bounds/downsample;
4. dettaglio che richiedeva la main senza una sequenza condivisa thumb -> main.

Non sono stati aperti refactor fuori scope.

## Risultato dopo la modifica

### Pipeline preprocessing finale

- Validazione dimensione input bounded; per una lunghezza nota viene letto
  soltanto l'header necessario, mentre un input di lunghezza ignota viene
  contato a stream senza conservarne una copia integrale.
- Pass `inJustDecodeBounds` separato e calcolo sample prima del decode.
- `ImageDecoder` usa direttamente source file/content resolver, target massimo
  `1600`, color space sRGB e allocator software.
- La main opaca gia entro `1600` evita una copia normalizzata ulteriore.
- La thumb deriva dalla main normalizzata, non dal 48 MP originale.
- Encode adattivo resta entro `1 MiB` main e `90 KiB` thumb.
- Stream e bitmap possedute sono chiusi/riciclati; sono presenti check di
  cancellazione tra le fasi.

Il device run finale sulla fixture sintetica `8000x6000` ha prodotto main
`1600x1200 / 165769 B` e thumb `384x288 / 17517 B` in `50 ms`. La misura PSS e
passata da `242449` a `245738 KiB`, delta `3289 KiB`. Questo e un delta PSS del
processo, non un claim di picco heap ne una misura di main-thread stall.

### Progressive rendering e rilascio

- Il dettaglio avvia un singolo job lazy condiviso per product/version.
- La thumb viene richiesta prima; la main parte dopo il completamento thumb.
- Recomposition duplicate riusano il job esistente.
- La preview mantiene la thumb se la main fallisce e offre retry.
- La lista continua a richiedere solo `THUMB` con `ContentScale.Crop`.
- Il dettaglio usa la main con `ContentScale.Fit`.
- Il decode `ByteArray -> Bitmap` avviene su `Dispatchers.Default`.
- `Crossfade` sostituisce il target soltanto a decode completato; la bitmap
  uscente viene riciclata in `DisposableEffect`.
- Il box ha dimensioni stabili e la disposal dettaglio elimina anche lo state
  main, rilasciando i byte.

La sequenza e coperta su JVM. Screenshot temporali thumb-preview/main-ready non
sono stati acquisiti e non vengono dedotti dal test.

### Signed URL lease cache

- Cache solo in memoria, keyed dalla reference completa
  account/shop/product/version/variant.
- `expiresAt` viene parsato come `Instant`; una safety window di `30 s` evita
  il riuso vicino alla scadenza.
- Le miss vengono batchate entro `100`; i lease validi non tornano a
  `read-urls`.
- La mappa e un access-order LRU bounded a `256` reference; l'inserimento 257
  elimina il lease meno recente.
- Primo `401/403`: invalidazione e un solo refresh forzato.
- Secondo `401/403`: errore stabile, nessun terzo tentativo e lease eliminato.
- Logout, cambio shop, upload/replace/remove e trim memory purgano i lease
  pertinenti.
- Nessuna signed URL entra in file, Room, outbox o log.

### Cache memory/disk ed eviction

| Livello | Limite | Accounting | Eviction |
|---|---:|---|---|
| memory | `8 MiB` | somma effettiva `ByteArray.size` | access-order LRU |
| disk | `64 MiB` | somma effettiva `File.length()` | oldest `lastModified` |

- Un read aggiorna la recency del file.
- Startup elimina i `.tmp` abbandonati; write usa temp + move atomico/fallback.
- JPEG non decodificabile, oltre dimensione o con APP1 viene eliminata e non
  entra in memoria.
- Purge account/shop/product/version elimina sia memoria sia disco, anche se
  una versione era gia stata evictata da uno dei due livelli.
- Con il worst-case contrattuale main+thumb (`1 MiB + 90 KiB`) la cache disk
  contiene almeno `58` coppie complete prima dell'eviction; la memory cache
  contiene almeno `7` coppie worst-case. Con la coppia 48 MP misurata
  (`183286 B`) il disk budget equivale a circa `366` coppie. Sono capacita
  derivate dai limiti, non soglie di performance cross-platform.

### Upload, cancellazione e retry

- Fasi osservabili: preprocessing, main PUT, thumb PUT, finalize, completed.
- Main e thumb sono inviate in sequenza, con una sola richiesta buffered alla
  volta.
- Ogni PUT ha al massimo un retry solo per errore rete senza status o `5xx`.
- `401/403` e altri errori permanenti non vengono ritentati.
- I check di cancellazione impediscono di proseguire a thumb/finalize quando il
  job e annullato; il ViewModel ripristina lo stato precedente.
- Restano soltanto `main.jpg` e `thumb.jpg`; nessun terzo oggetto preview.

## File coinvolti nell'optimization pass

- `app/src/main/java/com/example/merchandisecontrolsplitview/MerchandiseControlApplication.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/productimage/ProductImageCache.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/productimage/ProductImageProcessor.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/productimage/ProductImageService.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/EditProductDialog.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/productimage/ProductImageCacheTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/productimage/ProductImageServiceTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt`
- `app/src/debug/java/com/example/merchandisecontrolsplitview/ui/screens/ProductImageDebugTestHooks.kt`
- `app/src/androidTest/java/com/example/merchandisecontrolsplitview/productimage/ProductImageLocalTestConfig.kt`
- `app/src/androidTest/java/com/example/merchandisecontrolsplitview/productimage/ProductImageLocalParityDeviceTest.kt`
- `app/src/androidTest/java/com/example/merchandisecontrolsplitview/productimage/ProductImageLocalMutationDeviceTest.kt`
- `app/src/androidTest/java/com/example/merchandisecontrolsplitview/ui/screens/ProductImageVisualDeviceTest.kt`
- mirror/evidence TASK-138.

Gli altri file Product Images/UI/stringhe gia presenti nel diff appartengono
alla prima execution TASK-138 recuperata; non sono stati classificati come
unrelated.

## Test e gate reali

### JVM, build e lint finali

Comando finale mirato:

```text
./gradlew :app:testDebugUnitTest \
  --tests com.example.merchandisecontrolsplitview.productimage.ProductImageServiceTest \
  --tests com.example.merchandisecontrolsplitview.productimage.ProductImageCacheTest \
  --tests com.example.merchandisecontrolsplitview.productimage.ProductImageProcessorTest \
  --tests com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest \
  --tests com.example.merchandisecontrolsplitview.data.ProductImageCatalogContractTest \
  :app:assembleDebug :app:assembleDebugAndroidTest

./gradlew :app:lintDebug
```

Risultati finali dopo il fix MIME runtime: gate JVM/build
`BUILD SUCCESSFUL in 27s`; lint `BUILD SUCCESSFUL in 31s`.

| Suite | Test | Failure/error/skip |
|---|---:|---:|
| `ProductImageServiceTest` | 14 | 0/0/0 |
| `ProductImageCacheTest` | 10 | 0/0/0 |
| `ProductImageProcessorTest` | 4 | 0/0/0 |
| `DatabaseViewModelTest` | 41 | 0/0/0 |
| `ProductImageCatalogContractTest` | 5 | 0/0/0 |
| Totale mirato | 74 | 0/0/0 |

`assembleDebug` e `lintDebug` sono `PASS`. Lint: `0` errori e `23` warning
residui preesistenti/fuori scope; nessun warning nuovo nei file runtime
TASK-138 modificati.

Il nuovo harness opt-in e stato inoltre compilato con
`compileDebugAndroidTestKotlin` (`BUILD SUCCESSFUL in 16s`) e poi incluso in
`assembleDebugAndroidTest` (`BUILD SUCCESSFUL in 14s`).

### Device test sintetico API 35

Classe `ProductImageDeviceTest`: `3/3 PASS`, zero failure/error/skip,
`BUILD SUCCESSFUL in 22s` sul target `Medium_Phone_API_35`, API 35. Evidence
strumentale:

- picker/camera image-only e URI app-scoped: `PASS`;
- HTTP loopback upload/read/remove: `PASS`;
- preprocess/cache 48 MP: `PASS`, metriche riportate sopra;
- report XML:
  `app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_35(AVD) - 15-_app-.xml`;
- emulatore chiuso; `adb devices` vuoto.

### Gate locale opt-in read-only

E stato aggiunto un harness read-only per il catalogo remoto:

- config host JSON bounded, mode `0600`;
- copia nella cache privata app, owner UID app e mode `0600` verificati;
- config cancellata subito dopo il parsing;
- database Room in-memory, cache temporanea, nessuna modifica al DB app;
- nessun upload, replace, remove o chiamata production;
- test previsto: no-image -> rete thumb -> rete main -> trim memory -> hit disk
  thumb/main senza nuova rete.

Risultato runtime autorizzato:

1. primo invocation: `FAIL_HARNESS_PATH` prima di leggere la config o aprire la
   rete, per alias `/data/user/0` non accettato dal controllo canonico Java;
2. unico retry con alias canonico Java: il blocco no-image ha completato tutti
   gli assert (`Absent`, zero eventi gateway, zero cache); la prima richiesta
   thumb si e fermata con `image_signed_url_invalid` prima del download;
3. nessun terzo run; config device cancellata, AVD chiuso, `adb devices` vuoto.

Follow-up autorizzato: la config androidTest richiede ora entrambe le basi su
`http://127.0.0.1:<porta>` e il runbook usa `adb reverse` per `3050` e `54321`.
Questo allinea l'origin emessa dal servizio locale e quella attesa dal client
senza cambiare `sameOrigin` production e senza accettare host aggiuntivi.

Rerun su API 35 con path canonico Java `/data/data/...`: `1/1 PASS`.
Marker redatto:
`no_image=pass thumb_before_main=pass disk_cache_hit=pass network_events=4`.
La sequenza verificata e rete thumb, rete main, trim memory, hit disk thumb e
main senza nuova rete. La config read-only forza `SelectedShop.canWrite=false`,
viene validata owner/mode `0600` e cancellata immediatamente dopo il parsing.

Artifact raw locali, non committati e privi di config:

- `/tmp/task138-android-local-parity-instrument-attempt1.txt`;
- `/tmp/task138-android-local-parity-instrument.txt`.

### Gate UI sintetico e screenshot

Un primo invocation visuale ha prodotto `2/2 FAIL` per un
`IllegalAccessError` del test APK verso il composable internal. Il fix bounded
espone un wrapper pubblico soltanto nel source set `debug`; la release non
contiene hook e il comportamento UI non cambia. Un unico rerun ha completato
`2/2 PASS`.

Artifact acquisiti e ispezionati:

1. `android-device/01-list-placeholder-thumbnail.png`;
2. `android-device/02-detail-thumb-preview.png`;
3. `android-device/03-detail-main.png`;
4. `android-device/04-editor-upload.png`;
5. `android-device/05-offline-cache.png`;
6. `android-device/06-error-fallback.png`;
7. `android-device/07-performance-metrics.json`.

Assert reali: thumb `384x288` e main `1600x1200`, container lista quadrati e
contenuti nel root, bounds dettaglio identici tra thumb e main, main assente
dalla lista, placeholder/stati semantici, label a11y, pixel rosso -> blu dopo
crossfade, fallback thumb rosso su errore, progress/cancel, cache offline e
retry visibili.

Il percorso performance ha scrollato `200` righe e aperto/chiuso `20` editor
in `19817 ms`. PSS processo: `164431 KiB` prima, `194988 KiB` massimo osservato,
`184228 KiB` dopo. Massimo immagini composte: `12`, non `200`. Cache sintetica:
`8385570 B / 138` entry memory e `12153000 B / 200` entry disk, entro i budget
`8 MiB` e `64 MiB`. Sono misure del processo/emulatore, non soglie assolute di
frame time o heap.

### Gate mutativo opt-in separato

Il test richiede una config distinta `canWrite=true` e un mode esplicito:

- `replace`: upload + replace, lascia intenzionalmente la seconda versione
  remota per il confronto Admin/iOS ed emette solo un fingerprint SHA-256
  troncato;
- `remove`: usa una nuova config con la versione autorevole corrente e verifica
  il ritorno a `current_version_null=true`.

Il primo `replace` si e fermato sul primo `createIntent` con HTTP `400`, prima
di PUT/finalize e quindi senza write remoto. La diagnosi contro il parser Admin
ha individuato un bug Android reale: `mimeType` aveva un default serializzabile,
ma il `Json` runtime usa `encodeDefaults=false`, quindi il campo richiesto era
omesso. Il DTO ora rende `mimeType` obbligatorio; una regressione serializza il
body con i default reali e verifica entrambe le occorrenze `image/jpeg`. Il
preflight device verifica anche bytes, dimensioni, aspect, MIME e SHA prima
della rete. `ProductImageServiceTest`: `14/14 PASS`.

Il singolo rerun `replace` dopo il fix e `1/1 PASS`: upload e secondo replace
sono finalizzati, la versione e cambiata e il log espone soltanto un fingerprint
SHA-256 troncato. Il backend locale mostra `3` versioni, `6` oggetti canonici
(`main.jpg` + `thumb.jpg` per versione) e una sola versione current. Il
cross-reader Admin sulla versione Android e `1/1 PASS`. La versione corrente
resta intenzionalmente presente per il cross-reader iOS; `remove` non e ancora
eseguito e non viene dedotto.

## Metriche e limiti dichiarabili

- p50/p90/p95 byte: `NOT_AVAILABLE`, una sola fixture runtime non consente
  percentili rappresentativi.
- timing preprocess end-to-end: `50 ms` sulla singola fixture 48 MP.
- timing decode/encode main e thumb separati: `NOT_MEASURED`.
- PSS before/after: `242449/245738 KiB`; picco heap: `NOT_MEASURED`.
- main-thread stalls/frame timing: `NOT_MEASURED`.
- screenshot lista/placeholder/thumb, dettaglio thumb/main, editor upload,
  offline cache ed errore/fallback: `PASS_SYNTHETIC_EMULATOR`, sei artifact.

Stima Storage derivata dalla singola coppia misurata (`183286 B`, senza
originale): circa `0.183 GB` per 1k, `1.833 GB` per 10k, `3.666 GB` per 20k e
`18.329 GB` per 100k versioni correnti. Non e una previsione p50/p95 e non
include versioni storiche/pending.

## Finding bounded review

- `FIXED`: input full-copy prima del bounds/downsample.
- `FIXED`: cache senza memory/disk budget LRU reale.
- `FIXED`: assenza lease signed URL in-memory.
- `FIXED_P1`: lease map inizialmente senza cap; ora access-order LRU con cap
  `256`, coperto da overflow comportamentale `256 -> 257`.
- `FIXED`: dettaglio main-only senza progressive thumb -> main.
- `PASS`: nessun secret, signed URL, blob o path Storage persistito.
- `PASS`: `local.properties` ignorato e non pubblicabile.
- `PASS`: nessun file TASK-137 storico modificato.
- `PASS`: parity locale thumb/main/disk-cache via loopback `adb reverse`, con
  `sameOrigin` production invariato.
- `PASS`: screenshot/assert visuali sintetici e lista `200`/editor `20`.
- `PASS`: mutation Android upload+replace dopo fix MIME e cross-reader Admin.
- `PENDING_CROSS_PLATFORM`: cross-reader iOS e successivo mode `remove`.
- `NOT_RUN`: device fisico e staging/dev.

## Rischi residui e prossimo passo

1. Verificare la versione Android corrente da iOS, poi eseguire il mode
   `remove` con config aggiornata e confermare assenza da Admin/iOS.
2. Mantenere il task in `ACTIVE / REVIEW` fino a conferma esplicita utente.

Conferme: nessun commit, push, merge, deploy, migration production o modifica
Win7POS; placeholder a costo Storage zero; nessun terzo oggetto preview.

## Review finale e chiusura

La conferma utente e stata ricevuta. Dopo l'evidence precedente:

- iOS ha letto la versione Android `1/1 PASS`;
- Android ha letto la versione iOS `1/1 PASS`;
- Android `remove` della versione iOS `1/1 PASS` e Admin absent `1/1 PASS`;
- Android `replace` finale `1/1 PASS`, consumato poi dal remove iOS;
- la fixture coordinata e stata eliminata con residui DB/Storage/Auth `0`;
- `adb devices` non riporta emulatori attivi.

Il finding MIME resta la sola correzione cross-platform emersa dalla prova
reale: il campo richiesto non e piu omesso da `encodeDefaults=false` ed e coperto
dal serializer runtime. Gate finali invariati e verdi: JVM `74/74`, assemble app
e androidTest, lint `0` errori/`23` warning fuori scope, visual/performance `2/2`.

Verdict: `RELEASE_READY_WITH_MEASURED_GATES`; task `DONE`. Device fisico e
staging/dev autenticato restano `BLOCKED_EXTERNAL_PRECONDITION` dichiarati.
