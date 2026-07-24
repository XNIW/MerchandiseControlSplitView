# TASK-139 Android — Evidence ledger e handoff finale

Stato: `REVIEW`; non `DONE` senza conferma utente. I blocker runtime/E2E
restano esplicitamente classificati nelle evidence di dettaglio.

Addendum sync/recovery corrente:
`android-sync-recovery-atomic-addendum.md` (`INTERIM / REVIEW`; include anche i
run falliti e i blocker backend/runtime ancora aperti).

Allineamento successivo al backend contract freeze V6:
`android-v6-contract-freeze-alignment-2026-07-23.md` (`INTERIM / REVIEW`; i
gate JVM completi, build e lint post-fix sono eseguiti; l'instrumentation su
emulatore effimero resta `BLOCKED_ENV` e non è contata come PASS).

## Preflight e contratto

- Baseline `HEAD`/`origin/main`:
  `141ffa07b4ee2b556387ec194fb82b7b76e6a626`; delta `0/0`.
- Nessun file staged, commit TASK-139, merge, push o produzione.
- Contratto/fixture/vector: `cmp` Admin↔Android e iOS↔Android exit `0`.
- Hash contract: `612a403b1397546cad62b38cf70ad666c7290bfcdae1973778ff8b1ff85f1686`.
- Hash fixture valid/invalid/synthetic: `5912807…`, `b089914…`, `3432270…`.
- Matrice requisiti/test/drift: `contract-parity-matrix.md`.
- Gap finale corretto: il test Kotlin condiviso congela direttamente contro il
  JSON canonico input/API/main/thumb/cache budget, ladder, quality e l'allowlist
  completa dei 17 error code. Il runtime rifiuta ora qualsiasi code fuori
  contratto; gli status HTTP restano separati per i retry.

## Gate eseguiti il 2026-07-18

| Gate | Esito reale |
|---|---|
| Product Images + regressioni catalogo unit JVM | `39/39 PASS` (`34` Product Images + `5` catalogo), 0 failure/error/skip; `BUILD SUCCESSFUL in 33s` |
| `assembleDebug` | PASS |
| `assembleDebugAndroidTest` | PASS |
| `lintDebug` | PASS; 0 errori, 23 warning globali residui, zero match Product Image/TASK-139 |
| Emulator `Medium_Phone_API_35`, API 35 | `ProductImageDeviceTest` `3/3 PASS` |
| Visual/performance API 35 | `ProductImageVisualDeviceTest` `2/2 PASS` |
| Contract hash/cmp | PASS |
| Diff check | PASS dopo patch runtime/test/evidence |
| Secret scan scoped | PASS dopo patch runtime/test/evidence |

Il primo comando Gradle senza SDK esplicito ha salvato l'errore reale
`SDK location not found`; i comandi corretti hanno usato `ANDROID_HOME` e il JBR
di Android Studio senza creare `local.properties`.

## JPEG Android reale e loopback

- Picker `ImageOnly`, singolo; camera `TakePicture` con URI `FileProvider`
  app-scoped: PASS.
- Intent, PUT main/thumb, finalize, read thumb e remove contro loopback HTTP:
  PASS; MIME JPEG e assenza di cookie/bearer sugli URL firmati verificate.
- Fixture `Bitmap.compress` 48 MP `8000×6000`: input `1258536 B`, `108 ms`;
  main `1600×1200 / 165295 B`; thumb `384×288 / 17043 B`.
- PSS sample 48 MP before/after: `240024 / 239570 KiB`.
- Main/thumb: EOI terminale, niente trailing, COM, APP1/APP2/APP13 o APP3…APP15;
  APP0 ammesso solo JFIF. Thumb deriva dal main normalizzato.
- Il canonicalizer compatta in-place: sul buffer pulito da `1048576 B` il test
  ha restituito la stessa istanza (`cleanIdentity=true`, `2957750 ns` nel run);
  sul vector con APP2 `19 B → 10 B` crea una sola copia finale canonica, senza
  le due materializzazioni complete precedenti.
- L'accettazione sul Supabase locale/staging è coordinata dalla lane Admin;
  questa lane non trasforma il loopback in claim staging.

## Visual QA e memoria/cache

Il run corrente ha verificato sei stati sintetici, dimensioni `384×288` e
`1600×1200`, progressive red-thumb → blue-main, bounds stabili, placeholder,
label a11y, progress/cancel, offline cache e retry/fallback. Il percorso
performance ha scrollato 200 prodotti e aperto/chiuso 20 editor:

- PSS before/max/after: `165668 / 191864 / 171220 KiB`;
- il campione finale scende di `20644 KiB` dal picco: nessuna crescita monotona;
- massimo `12` immagini composte, non 200;
- cache memory `8380449 B`, entro 8 MiB;
- cache disk `12058200 B / 200 entry`, entro 64 MiB.

I sei PNG privacy-safe persistiti e aperti singolarmente dal reviewer sono:

1. `../TASK-138/android-device/01-list-placeholder-thumbnail.png`;
2. `../TASK-138/android-device/02-detail-thumb-preview.png`;
3. `../TASK-138/android-device/03-detail-main.png`;
4. `../TASK-138/android-device/04-editor-upload.png`;
5. `../TASK-138/android-device/05-offline-cache.png`;
6. `../TASK-138/android-device/06-error-fallback.png`.

Esito ispezione: nessun clipping/overflow, rapporto 4:3 coerente, thumb crop e
main fit coerenti, placeholder leggibile, testo/azioni raggiungibili; nessun
UUID, token, URL o path esposto. I titoli `TASK-138 synthetic` appartengono al
solo harness debug e non alla UI prodotto. Il run TASK-139 ha riacquisito gli
stessi sei stati, ma Gradle ha disinstallato APK e external-files al termine;
non viene dichiarato un nuovo path artifact inesistente.

## Scope isolation e blocker esterni — baseline 2026-07-18

Le due note su staging/emulatore in questa sezione descrivono il run baseline;
l'addendum autenticato del 19 luglio sotto le sostituisce.

- Cache `noBackup`, atomica e bounded; account/shop/version/variant isolation:
  PASS JVM/Emulator.
- No-image: zero read-url, zero download e zero cache write: PASS.
- Batch `100+100`, dedup/coalescing e massimo 4 download: PASS JVM.
- Signed URL: lease bounded 256, safety 30 s, un refresh/retry 401/403: PASS JVM;
  `image_request_failed` resta canonico e lo status HTTP guida il refresh.
- Upload: MIME serializzato, progress ordinato, cancel prima di thumb/finalize,
  un retry solo transient/5xx: PASS JVM/loopback; `image_upload_failed` resta
  canonico e lo status HTTP non viene incorporato nel code.
- `local.properties` assente e nessuna variabile staging Android disponibile.
- Staging autenticato: `BLOCKED_ENV`, non dichiarato PASS.
- Device fisico: `NOT_RUN`; `adb` vedeva solo Emulator.
- Emulator chiuso dopo i test; `adb devices` vuoto e nessun processo qemu.

## Handoff baseline

Lane Android pronta per review cross-platform. Restano esterni: staging
autenticato e device fisico. Nessun commit/push/merge/deploy production,
migration production, Win7POS o secret esposto.

## Addendum recovery editor autenticato — 2026-07-19

### Root cause e patch

- Root cause riprodotta: un retry progressivo con job condiviso
  `MAIN`/`THUMB` poteva lasciare la variant thumb in `LOADING`; il dispose
  editor cancellava solo main e `working` disabilitava camera/libreria per un
  semplice read.
- `DatabaseViewModel` conserva lo snapshot stabile pre-mutation, cancella e
  invalida entrambi i read, ignora completamenti stale durante la mutation e
  ripristina lo snapshot su cancel/discard/close.
- `EditProductDialog` distingue read da mutation, espone
  `Discard failed attempt`, scarta lo stato fallito prima di camera/library e
  chiude entrambe le variant al dispose.
- `ProductImageCaptureFileTracker` traccia e ripulisce i file camera temporanei;
  stringa nuova coperta in `values`, `values-en`, `values-es`, `values-zh`.
- Telemetria solo debug aggiunta nel catch upload: esclusivamente
  `errorCode/httpStatus/phase/retriable`; nessun URL, token, ID, path o body.

File focali del fix:

- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`;
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/EditProductDialog.kt`;
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ProductImageCaptureFileTracker.kt`;
- `app/src/androidTest/java/com/example/merchandisecontrolsplitview/ui/screens/ProductImageProductionUiDeviceTest.kt`;
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt`;
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/screens/ProductImageCaptureFileTrackerTest.kt`;
- le quattro risorse `strings.xml` localizzate.

### Gate reali post-fix

| Gate | Esito |
|---|---|
| `DatabaseViewModelTest` | `46/46 PASS`, 0 failure/error/skip |
| `ProductImageCaptureFileTrackerTest` | `2/2 PASS`, 0 failure/error/skip |
| Product Image + ViewModel esteso | `89/89 PASS`, 0 failure/error/skip |
| `assembleDebug assembleDebugAndroidTest` | PASS |
| `lintDebug` | PASS, 0 errori; 22 warning globali, zero match scoped |
| `git diff --check` | PASS |

Il primo comando mirato del 19 luglio senza `ANDROID_HOME` ha restituito
realmente `SDK location not found`; la ripetizione corretta con SDK esplicito è
`BUILD SUCCESSFUL in 26s`. Nessun `local.properties` è stato creato.

### Runtime autenticato in-place

- Target: `emulator-5554`; APK installata esclusivamente in-place con
  `adb -s emulator-5554 install -r -t app-debug.apk`.
- Nessun uninstall, clear data, reset DB o restart Emulator. Inode DB
  `33697`, shm `33708`, wal `33675` preservati; il prodotto
  `9913…2329` è rimasto ricercabile.
- La prima build configurata ha richiesto di ristabilire la sessione con
  l'account Google già presente nel simulatore; dopo la persistenza, la build
  diagnostica in-place ha ripristinato `Authenticated` automaticamente.
- UI verificata: stato errore con immagine corrente preservata e azioni
  `Retry image`, `Discard failed attempt`, `Take new photo`, `Library` tutte
  abilitate; nuova Library selection del file TASK-139 entra in
  `Preparing image...` e torna allo stato azionabile dopo il fallimento.
- Logcat dopo il run: `FATAL EXCEPTION=0`, ANR app `=0`, errori Room `=0`,
  `no_auth=0`.

Evidenze privacy-safe effimere, escluse dal repository:

- `task139-android-recovery-editor-enabled.png`;
- `task139-android-recovery-diagnostic-progress.png`;
- `task139-android-recovery-diagnostic-result.png`;
- `task139-android-recovery-auth-restored.png`;
- `task139-android-recovery-logcat-redacted.txt`.

Non viene inclusa né citata come evidence alcuna schermata del chooser Google
contenente identità account.

### Blocker reale staging

La nuova selezione non raggiunge upload/finalize. Il boundary server risponde:

```text
2026-07-19T05:10:08.020Z
POST /api/shop/product-images/intent
errorCode=image_request_failed httpStatus=401 phase=PREPROCESSING retriable=false
```

Il token persistito al momento del failure era un JWT Supabase valido con
`aud=authenticated`, `role=authenticated`, issuer del progetto configurato e
circa 45 minuti residui; nessun token o claim identificativo è stato salvato
nell'evidence. Serve correlazione server su route/timestamp per distinguere
mismatch ambiente da validazione auth. Pertanto:

- recovery UI/deadlock: **PASS**;
- conservazione DB/prodotto/sessione dopo install in-place: **PASS**;
- upload + finalize + thumbnail staging: **BLOCKED_AUTH**, non PASS;
- cleanup immagine/prodotto/file sorgente: **NON ESEGUITO** per istruzione.

Prossima fase: review/correzione del boundary auth staging, quindi una sola
nuova selezione e verifica `intent → PUT main/thumb → finalize → thumbnail`,
senza cleanup distruttivo e senza marcare TASK-139 `DONE` prima della conferma
utente.

## Addendum P0 auth + account/shop binding — host gate 2026-07-19

### Root cause e comportamento corretto

- La sessione Android persistita usata dal run precedente risultava revocata:
  la verifica server ha risposto `403 session_not_found`.
- Il restore precedente accettava lo stato locale `Authenticated` senza un
  refresh server; il logout non dichiarava lo scope e poteva quindi revocare
  sessioni su altri device.
- Il binding business precedente era `owner:shop` raw in SharedPreferences e il
  mismatch chiamava automaticamente il reset di catalogo/prezzi/history.

La patch aggiornata esegue un solo refresh del restore; invalida soltanto gli
errori canonici definitivi, conserva la sessione offline per errori transitori,
non pubblica mai owner vuoto e usa `SignOutScope.LOCAL`. Nessun token, email,
owner/shop ID reale o body auth è scritto nell'evidence.

Il binding è ora una riga singleton Room (`business_data_scope_binding`) con
owner SHA-256, store/local-store, protocollo, schema ed epoch. La migration
`20→21` crea soltanto la tabella vuota. La risoluzione binding, il controllo dei
conteggi globali e l'eventuale auto-bind del DB vuoto sono una singola
transazione. Unbound dirty e mismatch non cancellano né adottano dati
automaticamente.

### Matrice e zero outbound

La matrice completa è in `account-shop-binding-matrix-android.md`. I test host
con fake/counter dimostrano, per `CHECKING`, unbound review, account
mismatch e shop mismatch:

- zero bootstrap/push/quick/drain catalogo;
- zero device-status e zero letture `remote.isConfigured` dopo il gate;
- zero history repository/remote;
- zero realtime apply Room e invalidazione del buffer, inclusa la variazione
  scope durante l'attesa del single-flight;
- zero preprocessing e zero chiamate rete immagini;
- refresh/quick sync UI disabilitati e nessun bootstrap history automatico;
- nessun blocco UI positivo «Automatic sync active» fuori dallo scope valido.

Il percorso same-scope prova bootstrap/pull prima di push, poi drain. Lo scarto
unbound esplicito è provato solo su fixture Room sintetica: delete + binding
committano insieme; un trigger di errore forza rollback completo; il device ID
resta invariato. L'azione non può sostituire un binding owner già presente.
Account/shop mismatch espongono un secondo percorso esplicito e separato:
mantieni i dati e torna allo scope precedente, oppure conferma la sostituzione.
Il replace elimina dati/outbox e scrive il nuovo binding nella stessa
transazione; rollback ripristina vecchio binding e dati, il device ID resta
invariato. Il path rifiuta same-scope e schema mismatch.

### Gate reali correnti

| Gate | Esito |
|---|---|
| P0 combinato (binding/migration/auth/catalog/history/realtime/ViewModel/Options/images) | `146/146 PASS`, 0 failure/error/skip |
| Product Images completo | `89/89 PASS`, 0 failure/error/skip |
| JVM completo | `670` totali: `665 PASS`, `5` opt-in/live skipped, 0 failure/error; nessuno skip P0/TASK-139 |
| `assembleDebug assembleDebugAndroidTest` | PASS, `BUILD SUCCESSFUL in 14s` |
| `lintDebug` | PASS, `0 errors, 22 warnings`; nessun warning sui file P0/TASK-139 |
| `git diff --check` | PASS |

### Limite runtime esplicito

Questa patch P0 non è stata installata né esercitata sui simulatori autenticati:
la fase è rimasta host-only per coordinamento con lo smoke iOS. Nessun ADB,
install/uninstall, clear data, reset DB, logout, discard/replace, delete prodotto o
remove immagine è stato eseguito. Di conseguenza login/binding/Options su runtime
autenticato e il retry staging restano `NOT_RUN` per questa patch, non PASS.

## Addendum P0 — acceptance autenticata Android finale

La limitazione host-only sopra descrive il gate precedente ed è ora superata
dal run autenticato in-place del 2026-07-19. Evidence canonica:
`android-authenticated-runtime-p0.md`.

Esito aggiornato:

- same-account/same-shop e binding Room owner-safe: **PASS**;
- session restore dopo login con account già presente: **PASS**;
- Options senza claim positivo offline: **PASS**;
- thumbnail reale in cold offline da cache owner/shop-scoped: **PASS**;
- reconnect con retry shop-context e ritorno automatico a `READY`: **PASS**;
- integrità DB/cache e zero upload duplicato avviato: **PASS**;
- replace/discard/remove/delete sul dato reale: **NON ESEGUITI**.

Gate finale JVM forced: `674` totali, `669 PASS`, `5` skip esclusivamente
opt-in/live/fixture locale, `0` failure/error. `assembleDebug`,
`assembleDebugAndroidTest` e `lintDebug` sono PASS. TASK-139 resta `REVIEW`:
la sostituzione/rimozione cross-platform e l'allineamento iOS richiedono ancora
la decisione esplicita sul database locale iOS.

## Addendum final review — scope-flight race

La race A→B successiva al gate autenticato è corretta e documentata in
`android-final-review-scope-flight-race.md`. La nuova evidence include le due
riproduzioni RED pre-fix, la barriera cancel/join admission-closed, i check
rete/Room, lo scarto ShopContext per generation+owner e i test deferred verdi.

Gate corrente: targeted final-review `124/124 PASS`; JVM `687` totali (`682 PASS`,
`5` skip opt-in/live); build app e test APK PASS; lint `0` errori/`22` warning;
Emulator locale TASK-139 `3/3 PASS`.
La suite instrumentation globale non è falsamente marcata PASS: i soli due
failure sono harness live TASK-072C/D senza run prefix esplicito.

## Addendum UX account/shop mismatch Android

L'utente ha autorizzato una patch Android minima per allineare la semantica
della dialog iOS/Android. La lane non è quindi più `review-only` esclusivamente
per questa UI; policy TASK-126, coordinator, gate owner/shop, repository e
transazioni Room restano invariati.

Risultato: una sola `AlertDialog` Material 3 nativa, titolo
`Scegli quali dati usare`, messaggio `Quali dati vuoi mantenere?`, esattamente
due azioni `Mantieni dati locali` e `Sostituisci con dati cloud`. Keep, back e
dismiss esterno non chiamano business logic; il replace distruttivo è
disponibile solo con auth/shop/snapshot verificati, usa il coordinator owner-safe
esistente e non apre una seconda conferma. La fingerprint persistita impedisce
loop automatici per la stessa identità; `Rivedi` riapre manualmente la dialog.

L'audit finale ha poi chiuso un P1 image cross-scope: upload/remove ora vivono
nella lease generazionale TASK-126 già esistente, con check pre/post su
preprocessing, intent, PUT/retry, finalize, remove e apply locale. Quattro test
deferred con gateway non cooperativo provano stop delle fasi successive,
transition in attesa e zero mark Room/cache stale; la cancellazione remove
ripristina anche lo stato UI precedente senza business retry. Nessun nuovo
coordinator/policy, repository o cambio Room.

Gate reali dell'addendum: targeted JVM `182/182 PASS`; JVM completo `697`
totali (`692 PASS`, `5` skip opt-in/live), `0` failure/error; UI instrumentation
su AVD API 35 effimero `4/4 PASS`; `assembleDebug`,
`assembleDebugAndroidTest`, `lint` e `git diff --check` PASS. Nessun comando è
stato indirizzato all'emulatore autenticato reale; nessun clear-data,
uninstall, replace o cleanup business è stato eseguito.

Evidence dettagliata:
`android-account-shop-mismatch-dialog-addendum.md`.
