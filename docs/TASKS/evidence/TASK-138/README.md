# TASK-138 Android Evidence

## Esito

- stato task: `DONE`;
- fase: `DONE_RECONCILED` su conferma esplicita utente;
- base locale: `69c36c2c4e3e331da4ca6ce76524cf766d0a36f1`;
- runtime Android: `IMPLEMENTED_AND_JVM_AND_EMULATOR_VERIFIED`;
- nuove dipendenze: nessuna;
- commit/merge/push: autorizzati dall'override utente finale dopo i gate;
- Supabase live, Win7POS e production: non toccati.

## Optimization pass finale

L'evidence autorevole dell'optimization pass e:
`docs/TASKS/evidence/TASK-138/09-optimization-review.md`.

Nota filesystem: il path richiesto con segmento `EVIDENCE` maiuscolo collassa
nel medesimo path su questo workspace case-insensitive. Il path Git canonico
resta `docs/TASKS/evidence/...`, coerente con l'albero evidence esistente e
portabile su checkout case-sensitive.

Rispetto al run completo documentato sotto sono stati aggiunti cache LRU
memory/disk, lease signed URL, preprocessing streaming/downsample e progressive
thumb -> main. Il gate finale mirato e `74/74 PASS`, `assembleDebug PASS`,
`assembleDebugAndroidTest PASS`, `lintDebug PASS` (`0` errori, `23` warning
residui fuori scope) e il device
test sintetico API 35 e `3/3 PASS` con preprocess 48 MP in `50 ms`.

Il finding bounded-review P1 sulla crescita della lease map e stato chiuso con
access-order LRU capped a `256` e test overflow `256 -> 257`.

La parity locale opt-in e ora `PASS`: loopback `127.0.0.1` con `adb reverse`
allinea l'origin senza cambiare `sameOrigin`; no-image, thumb -> main e hit disk
sono verificati. Il gate visuale sintetico e `2/2 PASS`, con sei PNG e metriche
`200` righe/`20` editor in `android-device/`. Il primo mutation `replace` ha
rilevato prima dei PUT un MIME required omesso dal serializer Android; fix e
regressione sono applicati e il rerun upload+replace e `PASS`. Backend: `3`
versioni, `6` oggetti canonici, `1` current; cross-reader Admin `1/1 PASS`.
Il remove attende intenzionalmente il cross-reader iOS.

## Gate backend upstream

Gate comunicato come `PASS` dal coordinatore prima della patch Kotlin:

- reset locale: `PASS`;
- pgTAP: `149/149 PASS`;
- foundation: `20/20 PASS`;
- route/lifecycle E2E: `PASS`;
- fixture persistenti ruoli/prodotti: disponibili al gate.

La lane Android non ha rilanciato o modificato il backend.

## Implementazione verificata

- preview main editor con `ContentScale.Fit`; thumb lista mantiene il default
  `ContentScale.Crop`;
- `read-urls` batch per shop con dedup, chunk massimi di 100, coalescing per
  reference e semaforo globale di 4 download;
- cache-first scoped account/shop, purge selettivo su cambio shop e purge
  account su logout/account switch;
- nessuna richiesta image/cache per prodotto senza `versionId`;
- risposta JPEG non decodificabile rifiutata prima della cache; retry URL
  scaduta limitato a uno dal contratto esistente;
- versione DB ricontrollata prima della cache e versione desiderata
  ricontrollata prima dell'update UI;
- righe lista registrate solo mentre composte; completamenti offscreen ignorati
  e `ByteArray` rimossi dallo state alla disposal;
- preprocessing cooperativamente cancellabile su dispatcher IO;
- progress ordinato `PREPROCESSING`, `UPLOAD_MAIN`, `UPLOAD_THUMB`,
  `FINALIZING`, `COMPLETED`; cancel disponibile prima del finalize e stato UI
  precedente ripristinato;
- scope change cancella load/mutation/batch, svuota memoria e purga il vecchio
  scope disco.

Durante il test stale-race e stato rilevato e corretto un bug reale: una nuova
versione non partiva se la vecchia era ancora `LOADING`. La guardia ora riusa
il job soltanto quando coincide anche il `versionId`.

## Check eseguiti

JDK usato: JBR di Android Studio.

1. Run finale `./gradlew testDebugUnitTest assembleDebug lintDebug`
   - `BUILD SUCCESSFUL in 36s`;
   - `testDebugUnitTest`, `assembleDebug` e `lintDebug` tutti eseguiti nel
     medesimo run finale;
   - report XML: `604` test, `0` failure, `0` error, `5` skip;
   - i 5 skip sono fixture/live/oracle opzionali gia protetti da assumption,
     non test TASK-138.
2. Classi pertinenti incluse nel run completo:
   - `ProductImageServiceTest`: `7/7 PASS`;
   - `ProductImageCacheTest`: `6/6 PASS`;
   - `ProductImageProcessorTest`: `4/4 PASS`;
   - `ProductImageCatalogContractTest`: `5/5 PASS`;
   - `DatabaseViewModelTest`: `40/40 PASS`;
   - `DefaultInventoryRepositoryTest`: `190/190 PASS`.
3. Report lint finale dopo il fix dell'unico warning nuovo rilevato nel
   componente Compose:
   - `0` errori, `24` warning residui fuori scope (toolchain/dipendenze,
     third-party POI e warning preesistenti su risorse/KTX);
   - nessun warning residuo nei file runtime TASK-138 modificati.
4. `git diff --check`
   - `PASS`, output vuoto.
5. Instrumentation seriale su `Medium_Phone_API_35` (API 35):
   - comando mirato `connectedDebugAndroidTest` con runner class
     `ProductImageDeviceTest`;
   - `3/3 PASS`, `0` failure/error/skip, `BUILD SUCCESSFUL in 12s`;
   - picker/camera image-only e URI app-scoped: `PASS`;
   - loopback upload/read/remove con header e payload verificati: `PASS`;
   - fixture 48 MP: `262 ms`, PSS `240028 -> 245032 kB`, main
     `165769 B` (`1600x1200`), thumb `17517 B` (`384x288`);
   - emulatore chiuso al termine; `adb devices` vuoto.

## Copertura criteri

| Criterio | Evidence | Esito |
|---|---|---|
| Product A senza immagine: zero rete/cache | service test dedicato | `PASS` |
| Product B: thumb crop, main fit | call site + screenshot/assert API 35 | `PASS_EMULATOR` |
| lista 200 / editor 20 / cache bounded | visual instrumentation + metriche JSON | `PASS_EMULATOR` |
| batch <=100, dedup, coalescing, max 4 | 200 richieste service + concurrent duplicate | `PASS` |
| lista 200 visible-only e memoria bounded | ViewModel: 200 enter, 188 dispose, solo 12 richieste/state; dispose finale vuoto | `PASS_JVM` |
| stale completion | service DB race + ViewModel non-cooperative old completion | `PASS` |
| invalid decode no-cache | JPEG marker-only, errore `image_download_invalid`, zero file | `PASS` |
| retry URL scaduta una volta | `ProductImageCatalogContractTest` | `PASS` |
| cache isolation/purge logout-switch | cache test + ViewModel scope test | `PASS` |
| progress e cancel | service ordine completo/cancel + ViewModel restore | `PASS` |
| picker/camera, 48 MP, cache isolata, lifecycle HTTP | instrumentation API 35 `3/3` | `PASS_EMULATOR` |
| regressioni catalogo/sync | full JVM + repository `190/190` | `PASS_JVM` |

## File toccati

- `app/src/main/java/com/example/merchandisecontrolsplitview/productimage/`
  (`ProductImageApiClient.kt`, `ProductImageCache.kt`,
  `ProductImageContract.kt`, `ProductImageProcessor.kt`,
  `ProductImageService.kt`);
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/`
  (`DatabaseScreen.kt`, `DatabaseScreenComponents.kt`,
  `EditProductDialog.kt`);
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`;
- stringhe localizzate `values`, `values-en`, `values-es`, `values-zh`;
- test `ProductImageServiceTest.kt`, `ProductImageCacheTest.kt`,
  `DatabaseViewModelTest.kt`;
- governance TASK-138 (`docs/MASTER-PLAN.md`, mirror task, questa evidence).

## Non eseguito e rischi residui

- screenshot sintetici UI: `2/2 PASS`, sei PNG ispezionati; instrumentation
  Product Images precedente: `3/3 PASS`;
- parity locale opt-in: `PASS`; staging/dev non-production: `NOT_RUN_SCOPE`;
- mutation upload+replace Android e cross-reader Admin: `PASS`; remove dopo il
  cross-reader iOS: `PENDING_CROSS_PLATFORM`;
- restano da review camera reale e cancellazione percepita su device fisico;
- nessun claim live, device fisico o visuale viene dedotto da JVM/emulatore.

## Prossima fase

Review repo-grounded del diff; completare cross-reader iOS + remove e mantenere
il task `ACTIVE / REVIEW` fino a conferma esplicita dell'utente.
