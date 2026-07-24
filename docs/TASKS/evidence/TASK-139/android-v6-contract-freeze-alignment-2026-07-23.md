# TASK-139 Android — allineamento al contract freeze V6 (2026-07-23)

Stato: `INTERIM / REVIEW`. Questa evidence non dichiara convergenza
cross-platform, `DONE`, release, merge o deploy.

## Input congelato

- Manifest letto: artifact cross-platform `CONTRACT_FREEZE.json` (posizione
  locale redatta dall'evidence committabile).
- Backend writer congelato localmente: `9406da338691e70e627c26867122499f944de897`.
- Stato freeze: `BACKEND_FROZEN_LOCAL__CROSS_PLATFORM_CLIENTS_PENDING`.
- Il manifest elenca `p_after_id` e `p_entity_ids` come `text`; la migration
  congelata che implementa quelle RPC applica inoltre il regex RFC UUID
  v1--v5/variant RFC. Questa lane segue la sorgente SQL congelata senza
  modificare il manifest backend.
- Worktree Android writer isolato; branch
  `codex/task-139-product-image-hardening`, `HEAD` e `origin/main`
  `141ffa07b4ee2b556387ec194fb82b7b76e6a626` (delta Git `0/0` prima del
  diff TASK-139 preservato).

## Allineamento verificato nel client

- Le cinque RPC V6 usano gli identificatori e i parametri congelati;
  checkpoint/marker usano baseline decimali stringa, mentre page e targeted
  read trasmettono scope opaco, event fence e domain fence. L'event bootstrap
  può omettere il max soltanto alla prima pagina e conserva il valore catturato
  nelle continuazioni.
- I cursor bigint restano stringhe decimali canoniche nel wire e vengono
  convertiti a `Long` soltanto al boundary Room dopo validazione; leading zero,
  overflow e numeri JSON lossy sono rifiutati.
- Il decoder accetta esclusivamente i `scope.kind` e `historyKind` V6,
  incluso `authorized_shop_plus_legacy`, e confronta account, device, shop,
  legacy owner hash e scope key server-opaco senza ricostruirlo.
- I cap effettivi V6 sono applicati a snapshot e targeted fetch: snapshot
  supplier/category/images 240, products 60, prices 120 e history 3; targeted
  catalogo (supplier/category/product) 60, prezzi 120, history 3 e immagini
  240; event page 150. Il contratto immagini mantiene `read-urls` a massimo
  16 ref, 64 KiB, metadata obbligatori SHA-256/byte/dimensioni/JPEG e cache
  scope-bound.
- Il recovery chiama il convergence marker prima della pubblicazione
  no-work/idle; baseline, watermark e journal sono pubblicabili solo dopo
  marker, manifest/readback/digest, outbox vuota e lease ancora valida.

## Delta mirato di questa iterazione

- `ShopSyncRecoveryCoordinator`: digest ordered-chain UTF-8 allineato al
  vector V6; manifest prodotto rifiuta tombstone con riferimenti vivi;
  `prices.type` accetta soltanto `purchase`/`retail` senza normalizzare il
  valore che partecipa al digest; il readback fisico History V2 ricostruisce il
  payload e confronta la fingerprint prima dell'activation.
- I cursor e gli entity ID di recovery che attraversano
  `p_after_id`/`p_entity_ids` sono validati come UUID RFC v1--v5 con variant
  RFC: UUIDv7 e nil vengono rifiutati prima dell'RPC e nuovamente prima di
  manifest/staging/activation. Shop/account e version ID immagine conservano la
  validazione generica prevista dai rispettivi boundary: ciò non implica
  compatibilità con le RPC recovery. `sync_event_entity_ids` resta allineato
  alla stessa restrizione RFC v1--v5 del producer backend.
- `SupabaseProductPriceRemoteDataSource`: il DTO di scrittura ordinaria esclude
  `price_canonical` e `updated_at`, che sono campi di lettura/recovery e non
  possono rientrare accidentalmente nell'upsert client.
- Regressioni aggiunte per rifiuto recovery UUIDv7/nil, tombstone prodotto con
  riferimenti, tipo prezzo sconosciuto, manomissione display/overlay History
  V2, DTO write e CTA destructive con shop verificato.
- I limiti configurabili del reader possono restringere ma non allargare i cap
  V6 per dominio: supplier/category/image 240, prodotti 60, prezzi 120,
  history 3. Questo chiude il finding P2 che permetteva a un override interno
  di costruire una pagina products da 61 righe.

## Risultati eseguiti davvero

| Comando / gruppo | Pass | Fail | Skip | Esito |
| --- | ---: | ---: | ---: | --- |
| Primo targeted recovery/reader/dialog/image | 77 | 1 | 0 | storico FAIL, conservato sotto |
| Stesso targeted dopo correzione della sola aspettativa | 78 | 0 | 0 | PASS |
| `ShopSyncRecoveryCoordinatorTest` dopo l'ultima regressione tipo-prezzo | 54 | 0 | 0 | PASS |
| `SupabaseShopSyncReadRemoteDataSourceTest`, compreso cap V6 non ampliabile | 12 | 0 | 0 | PASS |
| Targeted post-fix UUID recovery (`Coordinator`, reader RPC, `SyncEventContract`) | 70 | 0 | 0 | PASS |
| `DefaultInventoryRepositoryTest`, `SyncEventContractTest`, `ProductImageServiceTest`, `ProductImageProcessorTest` | 240 | 0 | 0 | PASS |
| JVM completo post-fix | 806 | 0 | 5 | PASS |
| `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` post-fix | n/a | 0 | n/a | PASS, `BUILD SUCCESSFUL in 1m 37s` |

I comandi Gradle hanno usato esplicitamente `ANDROID_HOME` verso l'SDK Android
locale, `:app:testDebugUnitTest`, `--no-daemon` e `--console=plain`. Il primo
targeted ha fallito in
`BusinessScopeMismatchDialogTest`: il test trattava lo UUID nil come
malformato nel boundary generico della dialog. È stata corretta soltanto
l'aspettativa UI; non è un precedente per recovery, che ora rifiuta nil/v7
secondo la RPC SQL. Il rerun identico storico è `78/78`.

La JVM completa post-fix contiene `811` test: `806` pass, `0` failure/error e `5` skip
intenzionali. Gli skip non sono claim di TASK-139: configurazione Supabase
realtime assente; fixture locale Shopping Hogar assente; audit Drive, oracle
loop e oracle v2 eseguibili soltanto tramite i rispettivi task opt-in. Il lint
post-fix riporta `0` errori/fatal e `24` warning preesistenti di progetto.

## Review indipendente del delta

Review read-only post-fix: `P0=0`, `P1=0` per il delta V6 controllato. Non è
un verdict cross-platform: security diff scan formale, instrumentation isolata
ed E2E restano fuori da questo risultato.

Residui `P2`, fail-closed:

1. Il readback fisico prezzi non può provare il server `updated_at`, non
   persistito nella ref Room; il manifest/checkpoint lo verifica lato wire.
2. History V1 resta bridge-only; V2 prova la materializzazione business e
   fingerprint, ma non persiste ogni campo remoto come ref separata.
3. Un restore/rimozione immagine concorrente senza precedente attivo può
   produrre retry bounded/digest mismatch, non una publication errata.

## Instrumentation su Emulator effimero

- È stato aggiunto il test device `Task126BusinessDataScopeFlightDeviceTest`:
  account-switch quiesce una flight A non cooperativa, rifiuta una nuova
  admission A e abilita B soltanto dopo il boundary. Compila dentro
  `assembleDebugAndroidTest`; non è contato come eseguito.
- Due soli tentativi di avvio isolato API 35 hanno lasciato `adb devices` vuoto.
  Il secondo ha diagnosticato `No AVD specified. Use '@foo' or '-avd foo'`;
  l'emulator diretto non può creare un AVD dal solo system image disponibile.
  Il log diagnostico effimero è redatto e non è incluso nel repository.
- Per il limite di due retry infrastrutturali non è stato usato un AVD esistente
  né l'emulatore autenticato; instrumentation immagini/sync/account-switch è
  quindi `BLOCKED_ENV`, non PASS. Gli APK compilati non sono evidence runtime.

## Gate ancora da eseguire fuori dalla lane host

- E2E isolato Admin/Android/iOS e matrice immagini post-replace;
- security diff scan formale sull'esatto diff finale.

Nessun artifact AndroidTest è contato come instrumentation eseguita. Non sono
stati eseguiti installazioni sul simulatore autenticato, clear-data, replace
reale, staging/production write, migration remota, reset, clean, stash,
rebase, stage, commit, push o merge.

## Continuation runtime finale su AVD effimero

La continuation successiva supera il precedente `BLOCKED_ENV` senza usare o
modificare AVD esistenti. È stata creata una copia API 35 effimera in `/tmp`,
avviata blank senza snapshot/camera, usata soltanto per l'APK debug locale e
poi spenta e rimossa.

### sec-mobile-prebound-resource-003

Instrumentation nuova:
`Task139PreboundResourceRuntimeDeviceTest`.

| Prova finale | Pass | Fail | Skip |
| --- | ---: | ---: | ---: |
| 64 stale flight A/G1, transizione B/G2, Room/watermark/file sink | 1 | 0 | 0 |
| V1 image tardiva, V2 autorevole, 100 consumer V3 single-flight | 1 | 0 | 0 |
| prepare A/G1 in processo 1 | 1 | 0 | 0 |
| verify B/G2 dopo `am force-stop`, PID diverso | 1 | 0 | 0 |

Le asserzioni controllano zero publish/retry stale, zero V1 in memory/disk/UI,
un solo download V3, binding fail-closed dopo relaunch, vecchio store leggibile
e assenza di publication G1 nei sink B.

Storico conservato: il primo fixture JPEG della prova immagini ha restituito
`image_download_invalid`; usava un JPEG grezzo invece del canonicalizer di
produzione. Il fixture finale usa `ProductImageProcessor` e dimensioni decodificate.
La failure iniziale non viene riclassificata come PASS.

### Owner device e UI production

| Gruppo finale | Pass | Fail | Skip |
| --- | ---: | ---: | ---: |
| `ProductImageDeviceTest` | 3 | 0 | 0 |
| `BusinessScopeMismatchDialogDeviceTest` | 4 | 0 | 0 |
| `ProductImageProductionUiDeviceTest` opt-in | 2 | 0 | 0 |

La prima run UI ha fallito perché il locator puntava alla Card semantica fusa
(`945px`) anziché alla preview (`80dp`). Dopo il fix test-only, la review
visiva ha comunque trovato clipping reale dei metadati sul layout precedente.
Il fix di produzione ha reso preview/dettagli e metadati verticali, preservando
wrapping e touch target.

Matrice screenshot finale:

- width: `320`, `375`, `430dp`;
- font scale: `1.0`, `1.3`, `1.6`;
- `9/9` rendering PASS, miniatura `80dp`, supplier/category/stock/history
  presenti, bounds contenuti nella Card e zero overflow visibile.

Artifact esterni nell'archivio evidence TASK-139, incluso
`responsive-contact-sheet.png`.

### Gate host finali dopo il fix UI

- `:app:testDebugUnitTest`: `811` totali, `806` pass, `0` failure,
  `0` error, `5` skip intenzionali non owner;
- `:app:lintDebug`: PASS;
- `:app:assembleDebug`: PASS;
- `:app:assembleDebugAndroidTest`: PASS;
- `git diff --check`: PASS.

La lane Android passa a handoff `REVIEW`, non `DONE`. Restano fuori da questa
evidence i gate iOS, E2E cross-platform, Win7 canonico e verdict security
cross-repo. Nessun commit/push/merge/deploy o write remoto è stato eseguito.
