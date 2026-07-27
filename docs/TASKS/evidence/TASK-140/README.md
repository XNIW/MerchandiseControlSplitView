# TASK-140 evidence — CATALOG-TEXT-001 Android

Questa directory conserva soltanto evidence testuale redatta e non sensibile.
Non inserire credenziali, token, payload catalogo completi, nomi prodotto reali,
backup, APK, database, log runtime voluminosi o output con caratteri invisibili
raw.

## Baseline

- Repository: `XNIW/MerchandiseControlSplitView`
- Baseline: `ca0a58d8f63fa6447427c2e06846b4c9198e4be5`
- Branch: `codex/catalog-text-integrity-android-20260727`
- Coordination key: `CATALOG-TEXT-001`
- Golden fixture SHA-256 atteso:
  `139d63eedea47b54bb63a9289bef5fc6f7372668f209aac7753b586da7ccd9f8`

## Fixture comune

- Android:
  `app/src/test/resources/fixtures/catalog-text-policy-v1.json`.
- Admin:
  `tests/fixtures/catalog-text-policy-v1.json` nel worktree coordinato.
- `sha256sum` su entrambi:
  `139d63eedea47b54bb63a9289bef5fc6f7372668f209aac7753b586da7ccd9f8`.
- `cmp -s`: exit `0`.
- Fixture vectors: `27`; test JVM policy: `7/7`.

## Gate locali eseguiti

Tutti i comandi Gradle sono stati eseguiti con l’SDK locale esplicito:
`ANDROID_HOME=/Users/minxiang/Library/Android/sdk` e
`ANDROID_SDK_ROOT=/Users/minxiang/Library/Android/sdk`.

| Gate | Esito |
|---|---|
| Policy + Import Analyzer mirati | PASS |
| Test repository, inclusi no PriceHistory, collision apply e PATCH repair | PASS |
| `DefaultInventoryRepositoryTest` completo | `212/212` PASS |
| Import/full DB/resource/TASK-004 focused regression | PASS |
| Collision/redaction/full DB diagnostics post-review/rereview | `13/13` nuovi PASS |
| Full `testDebugUnitTest` finale | `873` totali; `868` eseguiti + `5` skip intenzionali; `0` failure/error |
| `assembleDebug` | PASS |
| `assembleDebugAndroidTest` | PASS |
| `lintDebug` | PASS |
| `git diff --check` | PASS |
| Connected API 35 | `8/8` PASS su `Medium_Phone_API_35` |
| Independent rereview | `APPROVED_PRE_PR`; SHA `334603a7`; P0/P1/P2/P3 `0/0/0/0` |

I cinque skip full JVM sono preesistenti e intenzionali: Supabase realtime
senza configurazione live, tre audit/oracle opt-in e una fixture locale
opzionale assente.

### Regressioni rilevate e risolte

1. Un avvio Gradle iniziale senza SDK esplicito è fallito per assenza di
   `local.properties`; nessun errore codice. I gate successivi hanno usato le
   variabili SDK esplicite.
2. Il test import storico che attendeva truncation a 100 caratteri è fallito:
   aggiornato al contratto comune, che rifiuta oltre 240 senza truncation
   silenziosa.
3. Il primo full JVM pre-fix ha prodotto `1` failure nel fingerprint di una
   DTO product image-only parziale. La canonicalizzazione inbound/fingerprint
   è stata resa partial-safe, aggiungendo copertura local-dirty; rerun iniziale
   `860` totali, `855` eseguiti + `5` skip intenzionali e `0` failure/error.
4. Il primo run post-review ha mostrato che il parser SAX full DB eseguiva il
   trim prima della policy: il test collisione PriceHistory ha reso osservabile
   il difetto. Il parser ora conserva il raw e il rerun mirato è verde.
5. Il primo connected post-review ha eseguito `6` test: `5` PASS e `1` errore
   test-only perché Compose non consente due `setContent` nello stesso metodo.
   Separati i due smoke, il rerun finale è `7/7` PASS.
6. Il primo focused rereview era verde nel comportamento ma un nuovo assert
   test-only confrontava l’enum `ITEM_NUMBER` con la label localizzata
   `Item code`; corretto l’assert sulla label e il rerun è verde.

## Connected smoke API 35

AVD `Medium_Phone_API_35`, API `35`, avviato senza wipe e con snapshot save
disabilitato; arrestato normalmente dopo il test.

`CatalogTextPolicyDeviceTest` (`3/3`) verifica:

- riga/campi del warning reale di Import Analysis;
- NFC/whitespace/emoji ZWJ e reject strict control sul runtime Android;
- presenza e distinzione dei messaggi IT/EN/ES/ZH.

`CatalogTextRealFlowsDeviceTest` (`5/5`) verifica:

- CTA import reale del `DatabaseRootHeader`;
- `CatalogNameDialog` production fino al repository reale Room, con nome
  canonicalizzato;
- workbook XLSX full database reale con foglio Suppliers, warning typed e
  errore redatto;
- manual product save reale fino al push catalogo fake-remote, con payload
  canonico.
- `ImportAnalysisScreen` production fino al callback di conferma, quindi
  `DatabaseScreen` production con Room in-memory/ViewModel controllati,
  apertura dell’editor prodotto completo dalla riga e update canonico
  osservato nel repository; sincronizzazione tramite `waitUntil`, nessuno
  sleep nel test.

Gap residuo non A-14: il picker Activity Result di sistema non seleziona un
documento reale e auth/network live non sono esercitati. `DatabaseScreen`
production è ora montato con ViewModel e Room in-memory controllati; CTA,
parser XLSX reale, dialog production, editor completo, Room e boundary sync
sono eseguiti sul device API 35.

## Evidence finding post-review

- Collisioni strict: distinct raw → stessa identity dopo trim bloccata per
  barcode e itemNumber, case-sensitive; il rereview verifica anche due raw
  itemNumber convergenti sullo stesso barcode prima del last-row-wins, sia
  nell’analyzer diretto sia in un workbook full database reale. Apply atomico
  e PriceHistory full/legacy restano inclusi.
- Redazione: `RowImportError` non conserva ZWSP, bidi o NUL; UI/export usano
  `catalog_text_redacted_value` localizzato e l’XLSX è ispezionato con POI.
- PATCH: repair testo + merge dirty mask avvengono nella stessa transazione;
  il bridge riletto produce una PATCH remote-existing contenente
  `productname` e `retailprice`.
- Suppliers/Categories: source/riga fisica/campo conservati; sample massimo
  `500` per source e `1000` complessivi, con totale esatto (`1002` nel test
  bounded).

## Boundary e privacy

- Manual create/edit prodotto, supplier e category: canonicalizzazione
  anticipata più repository come difesa finale.
- Import Analysis: preview canonica, warning non bloccanti, errori localizzati,
  apply editato rivalidato.
- Workbook single/full DB e recovery: policy comune prima di fingerprint/apply.
- Outbound: repair bounded per candidato pending/dirty, nessun secondo outbox e
  nessuna PriceHistory per variazioni solo testuali.
- Inbound: strict identity, remote-clean canonico, local-dirty non sovrascritto,
  image-only parziale preservato.
- Export: valore canonico persistito, nessun masking export-only.
- Eccezioni/log non contengono il valore raw; nessun token, payload catalogo
  completo o dato reale aggiunto all’evidence.

## Gate esterni pendenti

- staging QA e matrice Admin ↔ Android ↔ iOS;
- repair dati staging, paging Win7POS-equivalente e cleanup;
- CI, PR, merge normale e closeout documentale cross-platform.

Production e Win7POS: `NOT_MODIFIED`. Staging: `NOT_MODIFIED` da questa lane.
Commit fix/rereview: `06d865abd59f0d9d1ab4aa6881f69a271b1c5e34` e
`334603a7515b349ae2000489f229ca0c38ace2bb`. Push/PR: `NOT_RUN_PRE_PR`.
