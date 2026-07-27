# TASK-140 — Cross-platform catalog text integrity (Android)

## Stato

- Stato: `REVIEW`
- Fase: `REVIEW`
- Coordination key: `CATALOG-TEXT-001`
- Repository: `XNIW/MerchandiseControlSplitView`
- Baseline: `origin/main` `ca0a58d8f63fa6447427c2e06846b4c9198e4be5`
- Branch implementazione: `codex/catalog-text-integrity-android-20260727`
- Branch closeout: `codex/catalog-text-integrity-closeout-android-20260727`
- Apertura: `2026-07-27`
- Ultimo aggiornamento: `2026-07-27`
- Autorizzazione: richiesta utente cross-platform esplicita del `2026-07-27`.
- Staging: unico ambiente remoto autorizzato.
- Production: `NOT_MODIFIED`.
- Win7POS: riferimento read-only, `NOT_MODIFIED`.

## Dipendenze e coordinamento

- Contratto comune: `catalog_text_policy_v1`.
- Fixture golden canonica: repository Admin Web, SHA-256 atteso
  `139d63eedea47b54bb63a9289bef5fc6f7372668f209aac7753b586da7ccd9f8`.
- PR Admin:
  [XNIW/merchandise-control-admin-web#42](https://github.com/XNIW/merchandise-control-admin-web/pull/42).
- PR Android:
  [XNIW/MerchandiseControlSplitView#3](https://github.com/XNIW/MerchandiseControlSplitView/pull/3).
- PR iOS:
  [XNIW/iOSMerchandiseControl#1](https://github.com/XNIW/iOSMerchandiseControl/pull/1).
- Acceptance finale e passaggio a `DONE` restano cross-platform: richiedono
  review indipendente, CI, merge normali, repair/acceptance staging e gate
  Win7POS-equivalente verdi.

## Scopo Android

Applicare un’unica policy Kotlin pura e versionata a tutti i boundary reali del
testo catalogo Android: creazione/modifica manuale, import Excel e full database,
Import Analysis preview/edit/apply, persistenza repository, sync outbound
pending/dirty, sync inbound/recovery ed export del valore canonico persistito.
La policy deve preservare Unicode valido, normalizzare soltanto display text,
rifiutare identity/code text non valido e produrre esiti tipizzati. La
normalizzazione deve avvenire prima di persistenza, fingerprint, dirty/outbox e
payload, senza PriceHistory per variazioni soltanto testuali e senza perdere i
limiti streaming/resource introdotti dai task precedenti.

## Non incluso

- Nuovi entry point CSV/JSON/SQLite restore non presenti nell’app.
- Trasformazione di note o testo free-form multilinea.
- Nuove dipendenze, refactor architetturali ampi o modifiche schema Room.
- Modifiche production, Win7POS, RLS/grants/backend o dati reali.
- Mutazioni distruttive su device fisici o database autenticati reali.

## Boundary e file potenzialmente coinvolti

- `app/src/main/java/.../util/CatalogTextPolicy.kt` — policy pura condivisa.
- `app/src/main/java/.../util/ImportAnalysis.kt` — analisi, preview canonica,
  warning ed errori riga/campo.
- `app/src/main/java/.../data/ImportAnalysis.kt` — modello warning/count.
- `app/src/main/java/.../ui/screens/ImportAnalysisScreen.kt` — warning
  localizzati e apply del valore visibile.
- `app/src/main/java/.../data/InventoryRepository.kt` — boundary autorevole,
  sync inbound/outbound e preflight bounded.
- `app/src/main/java/.../util/FullDbImportStreaming.kt` — full import bounded.
- `app/src/main/java/.../util/ExcelUtils.kt` — percorso Excel esistente.
- `app/src/main/java/.../viewmodel/DatabaseViewModel.kt` — preview/apply.
- `app/src/main/res/values*/strings.xml` — IT/EN/ES/ZH.
- `app/src/test/...` e `app/src/androidTest/...` — test policy/import/repository/
  sync/UI.

## Criteri di accettazione

| ID | Criterio |
|---|---|
| A-01 | Fixture Android identico byte-per-byte al golden Admin e digest SHA-256 verificato. |
| A-02 | `CatalogTextPolicy` Kotlin pura usa NFC, non NFKC, è idempotente e restituisce `unchanged` / `normalized` / `rejected(reason)`. |
| A-03 | Display text converte CR/LF/TAB/Zs in spazio, collassa/trimma, preserva cinese/accenti/simboli/emoji/ZWJ validi e rifiuta control, ZWSP, WJ, BOM interno, bidi control, surrogate isolati, over-limit e required vuoto. |
| A-04 | Strict identity conserva il trim esistente ma rifiuta control/line separator/zero-width/bidi/surrogate/over-limit senza riscrivere il contenuto interno. |
| A-05 | Manual create/edit di prodotti, fornitori e categorie passa dal boundary repository e non può persistere testo invalido. |
| A-06 | Excel, shared-sheet, single-sheet/full database import e recovery applicano la stessa policy senza regressione streaming/resource. |
| A-07 | Import Analysis mostra valori canonici, warning non bloccanti con righe/campi/totale, errori localizzati ed applica esattamente il preview editato dopo nuova validazione. |
| A-08 | Outbound pending/dirty viene riparato in transazione bounded prima di fingerprint/payload, resta dirty e non duplica outbox. |
| A-09 | Inbound/recovery canonicalizza remote-clean, rifiuta identity invalida e non genera dirty loop né sovrascrive local-dirty. |
| A-10 | Una modifica solo testuale non crea PriceHistory; retry/no-op resta idempotente. |
| A-11 | Export riflette il valore canonico già persistito, senza masking export-only. |
| A-12 | IT/EN/ES/ZH coprono warning e motivi di rifiuto senza loggare caratteri invisibili raw. |
| A-13 | Unit/focused import/repository/sync, full unit, assemble e lint sono verdi; test bounded import resta verde. |
| A-14 | Instrumented/UI smoke API 35 copre Import Analysis, Database Screen e manual edit, oppure registra un blocker ambientale preciso. |
| A-15 | Staging acceptance cross-platform, review indipendente e CI/merge hanno P0/P1/P2 aperti pari a zero prima del closeout. |

## Decisioni

| # | Decisione | Motivazione | Data |
|---|---|---|---|
| 1 | Policy centrale nel dominio `util`, senza dipendenze Android UI. | Deve essere testabile su JVM/Mac e riusabile in import/repository/sync. | 2026-07-27 |
| 2 | Nessun nuovo import CSV/JSON. | Il repository non espone questi entry point; crearli sarebbe scope creep. | 2026-07-27 |
| 3 | Repository come ultima difesa, UI/import come feedback anticipato. | Impedisce bypass da preview edit, recovery e sync. | 2026-07-27 |
| 4 | Nessuna modifica Room schema. | L’invariante è applicabile ai boundary esistenti senza migrazione locale distruttiva. | 2026-07-27 |

## Planning

1. Copiare e verificare il fixture golden comune.
2. Implementare la policy Kotlin pura e i test vector/digest/idempotenza.
3. Integrare preview/apply import e manual repository boundary.
4. Integrare full import/recovery e sync inbound/outbound prima dei
   fingerprint/payload, mantenendo bounded resource e dirty semantics.
5. Aggiungere localizzazioni e test mirati/instrumented realistici.
6. Eseguire TASK-004 baseline, full unit, build, lint ed emulator API 35.
7. Portare task e Master Plan a `REVIEW`; `DONE` solo nel closeout
   cross-platform autorizzato dopo tutti i gate.

## Rischi

- Fingerprint calcolati sul raw possono generare loop: canonicalizzare prima del
  confronto e del commit ref remoto.
- Il preview editabile oggi non viene rianalizzato: validare nuovamente
  all’apply e mantenere l’apply atomico.
- La truncation silenziosa dei nomi import esistenti deve diventare reject
  tipizzato senza rompere il fallback dominio.
- Il preflight outbound deve essere bounded e limitato a pending/dirty, non una
  scansione completa a ogni sync.
- La policy UTF-16 deve rilevare surrogate isolati prima di `Normalizer`.
- I percorsi full import/recovery recenti hanno limiti memoria da preservare.

## Execution

### Esecuzione — 2026-07-27

**Baseline e worklog iniziale:**

- Worktree pulito creato da
  `ca0a58d8f63fa6447427c2e06846b4c9198e4be5` in
  `/Users/minxiang/.codex/worktrees/catalog-text-integrity-20260727/android`.
- Branch `codex/catalog-text-integrity-android-20260727`.
- Checkout dirty originario lasciato intatto; nessun reset/clean/stash.
- Task aperto direttamente in `EXECUTION` per autorizzazione utente esplicita.
- Nessun deploy, write staging/production, commit, stage o push.

**Contratto e implementazione:**

- Fixture Admin copiata byte-per-byte in
  `app/src/test/resources/fixtures/catalog-text-policy-v1.json`;
  `cmp` verde e SHA-256 comune
  `139d63eedea47b54bb63a9289bef5fc6f7372668f209aac7753b586da7ccd9f8`.
- `CatalogTextPolicy.kt` implementa esiti tipizzati, NFC, validazione UTF-16/
  UTF-8, display canonicalizzabile, identity strict, limiti comuni,
  idempotenza e collision check.
- `CatalogTextCanonicalizer.kt` applica il contratto ai modelli dominio e
  impedisce di includere il valore raw nelle eccezioni.
- Boundary coperti: editor prodotto e anagrafiche, Import Analyzer preview/
  apply, import Excel/full database, repository manuale, fingerprint,
  preflight pending/dirty, payload outbound, inbound remote-clean/local-dirty,
  tombstone, realign e recovery.
- Le riparazioni outbound sono limitate al candidato pending/dirty corrente,
  conservano la revisione dirty e non scrivono outbox o PriceHistory.
- Le righe inbound prodotto parziali image-only restano accettate: i campi
  presenti sono validati singolarmente e il fallback dominio è applicato
  soltanto prima di una persistenza prodotto completa.
- Warning/errori localizzati aggiunti in IT/EN/ES/ZH; la preview mostra il
  valore canonico e righe/campi/numero normalizzazioni.

**Matrice dei flussi verificati:**

| Source | Parser/analysis | Apply/local DB | Sync/server |
|---|---|---|---|
| Manual product/clipboard | `EditProductDialog` | canonicalizer + repository | pending/dirty preflight + payload canonico |
| Manual supplier/category | dialog/VM | repository canonicale | pending/dirty preflight + payload canonico |
| Workbook/shared single sheet | `ExcelUtils` → `ImportAnalyzer` | preview editata rivalidata + apply atomico | repository/push canonico |
| Full database workbook | streaming sheet parser + `ImportAnalyzer` | apply atomico + PriceHistory barcode strict | fingerprint canonico |
| Recovery/pull | remote DTO + strict ID validation | remote-clean canonico; local-dirty preservato | fingerprint canonico/no loop |
| Export | writer esistente | legge il valore canonico persistito | nessun masking export-only |

CSV/JSON/SQLite restore non espongono entry point di import catalogo nel
repository corrente e non sono stati inventati.

**File toccati:**

- Policy/adattatori: `CatalogTextPolicy.kt`, `CatalogTextCanonicalizer.kt`.
- Import/fingerprint: `ImportAnalysis.kt` (data e util),
  `FullDbImportStreaming.kt`, `ImportDatasetFingerprint.kt`.
- Repository/sync: `InventoryRepository.kt`,
  `InventoryCatalogRemoteRows.kt`.
- UI: `EditProductDialog.kt`, `ImportAnalysisScreen.kt`.
- Localizzazioni: `values`, `values-en`, `values-es`, `values-zh`.
- Test/fixture: `CatalogTextPolicyTest.kt`, `ImportAnalyzerTest.kt`,
  `DefaultInventoryRepositoryTest.kt`, `FullDbCatalogTextDiagnosticsTest.kt`,
  `ErrorExporterCatalogTextTest.kt`, `CatalogTextPolicyDeviceTest.kt`,
  `CatalogTextRealFlowsDeviceTest.kt`, fixture JSON.
- Governance: questo task, evidence README e `docs/MASTER-PLAN.md`.

**Check obbligatori:**

| Check | Tipo | Stato | Evidenza |
|---|---|---|---|
| Build Gradle | B | PASS | `assembleDebug` + `assembleDebugAndroidTest`, 2026-07-27 |
| Lint | S | PASS | `lintDebug`, 2026-07-27 |
| Warning Kotlin | S | PASS | compile app/test/androidTest senza warning Kotlin nel gate finale |
| Coerenza con planning | — | PASS | matrice boundary e diff verificati; nessuna nuova dipendenza/schema |
| Criteri di accettazione | — | PARTIAL_EXTERNAL | A-01–A-14 locali coperti in misura tecnicamente raggiungibile; A-15 cross-platform/staging pendente |

**Baseline regressione TASK-004:**

- Full JVM: `873` totali, `868` eseguiti + `5` skip opt-in/live storici,
  `0` failure/error.
- Policy: `7/7`; Import Analyzer: `34/34`; full DB round-trip:
  `8/8`; full DB catalog diagnostics: `5/5`; error export redaction:
  `2/2`; resource policy: `6/6`; repository: `212/212`;
  Database ViewModel: `49/49`; Catalog Sync ViewModel: `31/31`;
  recovery coordinator: `59/59`.
- Connected API 35: `8/8` su `Medium_Phone_API_35`: `3/3`
  `CatalogTextPolicyDeviceTest` e `5/5` `CatalogTextRealFlowsDeviceTest`.
  Sono coperti warning production Import Analysis, policy runtime/locali,
  `ImportAnalysisScreen` con callback preview, `DatabaseScreen` completo su
  dipendenze controllate, editor prodotto completo con persistenza Room,
  CTA import del `DatabaseRootHeader`, dialog manuale catalogo, supplier
  workbook XLSX reale e manual product → push sync.
- Il primo full JVM pre-fix ha rilevato una regressione sul fingerprint
  image-only parziale (`1` failure); corretta senza disabilitare test e il
  rerun finale è verde.
- `git diff --check`: PASS.
- Limiti residui: l’Activity Result picker di sistema non seleziona un
  documento reale e auth/network live non sono esercitati; `DatabaseScreen`
  production è però montato con ViewModel e Room in-memory controllati, e il
  parser full workbook reale resta coperto separatamente. Staging sync, data
  repair, nuova review indipendente, CI/PR/merge e acceptance cross-platform
  restano al coordinatore.

### FIX post-review — 2026-07-27

- **R-01:** `ImportAnalyzer` conserva il raw strict fino al collision check e
  blocca l’intero gruppo barcode/itemNumber quando raw distinti, con confronto
  case-sensitive; il parser SAX full DB non trimma più prima della policy.
  `applyImport` ripete il preflight dentro la transazione. PriceHistory full DB
  usa lo stesso tracker e il legacy streaming effettua un pass di preflight
  prima di scrivere il primo batch. Il rereview ha individuato che il primo
  pass itemNumber avveniva ancora sul solo `lastRow`: ora il gruppo
  raw→canonical è aggiornato dentro `rowProducer`, prima dell’overwrite, e
  conserva un errore redatto per ogni barcode coinvolto.
- **R-02:** gli errori catalog text passano da una factory privacy-safe che
  canonicalizza ogni cella ammessa, sostituisce i valori rifiutati e registra
  soltanto la chiave redatta. UI ed export XLSX usano un marker localizzato
  IT/EN/ES/ZH; test ZWSP, bidi e NUL verificano che il raw non sia conservato
  né esportato.
- **R-03:** il repair prodotto pre-push aggiorna prodotto e dirty field mask
  nella stessa transazione; il bridge viene riletto prima di decidere
  PATCH/upsert. Il test remote-existing verifica una PATCH con
  `productname,retailprice`, incluso il testo riparato.
- **R-04:** i fogli Suppliers/Categories producono warning/errori tipizzati
  per source/riga fisica/campo; i campioni sono limitati a `500` per source e
  `1000` complessivi, mentre i contatori totali restano esatti.
- **R-05:** oltre agli smoke component/boundary, il rereview aggiunge uno smoke
  screen-level unico senza sleep test-side: monta `ImportAnalysisScreen`,
  osserva la conferma preview, passa a `DatabaseScreen` con dipendenze
  controllate, apre l’editor prodotto completo da una riga reale e verifica
  l’update canonico in Room. Connected finale `8/8` PASS.

**Gate post-rereview FIX:** full JVM `873` (`868` eseguiti + `5` skip intenzionali),
assemble app/androidTest, lint e connected API 35 `8/8` PASS. Nessun warning
Kotlin nel gate finale e `git diff --check` PASS.

## Review

Rereview finale dello SHA `334603a7515b349ae2000489f229ca0c38ace2bb`:
`APPROVED_PRE_PR`, P0/P1/P2/P3 `0/0/0/0`. Verificate collisioni prima del
last-row-wins, screen production su API 35, Unicode/fingerprint,
local-dirty image-only e bounded preflight.

### Review indipendente — finding da correggere

| ID | Severità | Finding | Stato |
|---|---|---|---|
| R-01 | P1 | Collisioni raw → canonical strict nei batch reali possono usare last-wins, incluso itemNumber sullo stesso barcode. | `FIXED_VERIFIED_POST_REREVIEW` |
| R-02 | P1 | `RowImportError` catalog text conserva raw invisibile in UI/export. | `FIXED_VERIFIED` |
| R-03 | P1 | Il repair pre-push può lasciare una PATCH parziale senza campi testuali riparati. | `FIXED_VERIFIED` |
| R-04 | P2 | Supplier/category full DB non conserva warning/error tipizzati per riga/campo. | `FIXED_VERIFIED` |
| R-05 | P2 | Copertura connected dei flussi production incompleta. | `FIXED_VERIFIED_POST_REREVIEW` |

## Closeout cross-platform — 2026-07-27

- Implementazione Android pubblicata nel PR
  [#3](https://github.com/XNIW/MerchandiseControlSplitView/pull/3), CI verde e
  merge normale a due parent `ec858d0bd75b9d06ff7cbabeebcca9b25be21070`.
- PR Admin #42 e iOS #1 collegati, CI verdi e merge normali verificati. Review
  indipendenti finali dei tre repository: P0/P1/P2/P3 `0/0/0/0`.
- Migration e repair applicati soltanto a `merchandisecontrol-dev`: `345`
  prodotti riparati atomicamente, invalidi post-repair `0`, invarianti
  business preservati. Production `NOT_MODIFIED`.
- Acceptance pubblica sul solo shop QA: Android API 35 ha scritto un prodotto
  con quattro prezzi; iOS e Admin lo hanno letto canonico. Dopo il write iOS,
  Android ha letto prodotto e quattro prezzi canonicali, nello stesso
  owner/shop scope e con identity strict invariata.
- Gate Win7POS-equivalente read-only completato sull'intero catalogo staging:
  `71` categorie, `102` supplier, `19.763` prodotti e `41.228` prezzi;
  snapshot pinned, duplicate ID/cursor e valori invalidi `0`.
- Cleanup transazionale esatto: fixture mobile/Admin, prezzi ed eventi rimossi;
  residue fixture e catalogo shop QA `0`. Nessun dato production o Win7POS
  modificato.
- I check del closeout sono documentali (`git diff --check` e verifica link/
  stato); i gate Android completi restano quelli già eseguiti sul medesimo
  codice integrato: full JVM `873`, assemble, lint e connected API 35 `8/8`
  `PASS`.

## Handoff

Task in `REVIEW / READY_FOR_USER_CONFIRMATION`, mai `DONE` in questa lane.

- Risultato: A-01–A-15 verificati nel perimetro autorizzato; implementazione
  integrata, acceptance staging bidirezionale e cleanup esatto completati.
- File closeout toccati: questo task e
  `docs/TASKS/evidence/TASK-140/README.md`.
- Evidence: gate locali, PR/merge, staging e cleanup sono sintetizzati nel
  README evidence.
- Rischi residui: picker documenti/auth live non erano parte del connected
  locale, ma i flussi pubblici staging richiesti sono stati verificati nella
  acceptance coordinata; nessun P0/P1/P2/P3 aperto.
- Production e Win7POS: `NOT_MODIFIED`.
- Prossima fase: review finale e conferma esplicita dell'utente per l'eventuale
  passaggio a `DONE`.
