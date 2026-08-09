# TASK-141 evidence — Android

- Baseline: `4b2b4a93dd5d4db7d1cfb83e897aa5cbac40366e`
- Branch: `agent/mobile-catalog-data-integrity-20260809`
- Scope: catalog numeric safety, atomic mutations, price-history collision safety,
  truthful duplicate-barcode import copy.
- Baseline focused audit: `ImportAnalyzerTest` + `ClNumberFormattersTest`,
  `48` test, `0` failure, `0` skip, exit `0`.
- Client TASK-033: `NOT_MODIFIED`.
- Production/Supabase/secrets/deploy: `NOT_MODIFIED`.

## Matrice criteri → evidence

| Criterio | Esito | Evidence |
|---|---|---|
| A-141-01 | `PASS` | `ClNumberFormattersTest`, `CatalogTextRealFlowsDeviceTest`; screenshot esterno `visual-after/task141-android-invalid.png`. |
| A-141-02 | `PASS` | `CatalogTextRealFlowsDeviceTest` esercita `ManualEntryDialog`: invalid/negative bloccati, blank purchase fallback `50`, blank quantity preservata e raw invalido mai emesso. |
| A-141-03 | `PASS` | rollback add/update in `DefaultInventoryRepositoryTest`; 233 test mirati post-fix. |
| A-141-04 | `PASS` | test deterministico add + 61 update same-clock: 62 history point univoci e valore finale coerente. |
| A-141-05 | `PASS` | `Task141ImportPolicyLocalizationTest` IT/EN/ES/ZH e baseline `ImportAnalyzerTest`. |
| A-141-06 | `PASS` | nessuna dipendenza/schema/API pubblica; helper e test seam restano `internal`; audit file/stato repository. |
| A-141-07 | `PASS` | gate sotto; i limiti physical device/TalkBack sono registrati separatamente come `NOT_RUN`. |

## Gate finali

| Tipo | Comando | Risultato |
|---|---|---|
| Targeted JVM | `./gradlew --no-daemon :app:testDebugUnitTest --tests '*ClNumberFormattersTest' --tests '*Task141ImportPolicyLocalizationTest' --tests '*DefaultInventoryRepositoryTest' :app:assembleDebugAndroidTest` | `PASS`, 233 test, 0 failure/error/skip, exit `0`. |
| Full unit/Robolectric | `./gradlew --no-daemon :app:testDebugUnitTest :app:lint :app:assembleDebug :app:assembleDebugAndroidTest` | `PASS`; 880 test, 0 failure/error, 5 skip; build exit `0`. |
| Lint canonico | `./gradlew --no-daemon lint` | `PASS`, 28 warning, 0 errori/fatal, exit `0`. |
| Emulator | `./gradlew ... :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.merchandisecontrolsplitview.ui.screens.CatalogTextRealFlowsDeviceTest` | `PASS`, 6 test, 0 failure/skip, API 35, exit `0`. |
| Visual state | rerun test TASK-141 dedicato + pull screenshot | `PASS`, stato malformed/invalid e draft visibili; valori negativi coperti dal test runtime, non da uno screenshot dedicato; artifact non versionato. |
| Diff hygiene | `git diff --check` | `PASS`, exit `0`. |

## Tentativi intermedi non finali

- Primo targeted run: `FAIL` in compilazione test perché `ApplicationProvider` non
  era disponibile nel classpath; il test è stato adattato al runner Robolectric già
  canonico (`RuntimeEnvironment`), senza cambio production.
- Secondo targeted run: 232 test, 1 assertion nuova errata sul default stock
  (`null` atteso contro `0.0` reale); corretta l'aspettativa per verificare il
  comportamento business esistente, senza indebolire codice o test.
- Primo screenshot instrumentation: il flusso funzionale passava ma la cattura usava
  un root ambiguo; aggiunto un tag stabile alla dialog e il rerun completo è 5/5.

## Limiti dichiarati

- Physical device, camera reale e TalkBack: `NOT_RUN`.
- Nessun security scan avviato; nessun dato reale o artifact runtime versionato.
- Re-review indipendente post-fix: `APPROVED`; task resta `ACTIVE / REVIEW` in
  attesa della conferma utente.

## Review indipendente — 2026-08-09

- Baseline: `4b2b4a93dd5d4db7d1cfb83e897aa5cbac40366e`.
- Digest dello snapshot completo revisionato:
  `e8171f13d6409f248bcdd7e1e837f47e9fcffd23704eb43ce67cc4fce3648c2e`.
- Ruolo: `CODEX_REVIEWER`, review-only; nessuna modifica a codice, test o risorse.

### Gate autonomi reviewer

| Gate | Esito reviewer |
|---|---|
| Targeted JVM esteso, inclusi repository/import/formatter/localizzazione | `PASS`, 266 test, 0 failure/error/skip, exit `0` |
| Full JVM/Robolectric | `PASS`, 879 test, 0 failure/error, 5 skip, exit `0` |
| `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` | `PASS`, exit `0` |
| Lint canonico | `PASS`, 28 warning, 0 error/fatal, exit `0` |
| Instrumentation `CatalogTextRealFlowsDeviceTest`, API 35 | `PASS`, 5/5, exit `0` |
| Visual QA stato invalido | `PASS`, nessun overlap/crop osservato; negative visual non verificato |
| `git diff --check` | `PASS`, exit `0` |
| File accidentali/artifact/pattern credenziali nel diff | `PASS`, nessun match, exit `0` |
| Physical device / TalkBack | `NOT_RUN` |

### Finding review

| ID | Severità | Evidenza sintetica | Stato |
|---|---|---|---|
| R141-01 | `S2 / P2` | Resolver history limitato a 60 probe e ritorno finale non verificato: add + 61 update con clock fisso può perdere l'ultimo punto pur aggiornando il prodotto. | `OPEN_TO_FIX` |
| R141-02 | `S2 / P2` | Nessun test esercita `ManualEntryDialog`/`task141.manual.*`; A-141-02 non è dimostrato end-to-end. | `OPEN_TO_FIX` |
| R141-03 | `S3 / P3` | Messaggio stock della riga compatta separato semanticamente dal relativo campo; TalkBack specifico non dimostrato. | `OPEN_TO_FIX` |
| R141-04 | `S3` | Claim no-API non coerente con nuovi simboli/costruttore pubblici; claim visual negative non supportato dal test/screenshot invalido. | `OPEN_TO_FIX` |

### Verdict

`CHANGES_REQUIRED` — `CODEX_REVIEW_CHANGES_REQUIRED_TO_FIX`.

La matrice executor precedente resta evidence dell'esecuzione, non costituisce
approvazione reviewer. Nessun Deep Security Scan o security scan sostitutivo è
stato avviato; Client TASK-033 e release train restano intatti.

## Fix evidence — 2026-08-09

| Finding | Stato fixer | Evidence |
|---|---|---|
| R141-01 | `FIXED_VERIFIED` | Loop sempre verificato + test oltre 60 collisioni; full suite 880 test. |
| R141-02 | `FIXED_VERIFIED` | Nuovo flusso Compose `ManualEntryDialog`; instrumentation 6/6. |
| R141-03 | `FIXED_VERIFIED` | Semantica `error` sul campo stock + assertion Compose. |
| R141-04 | `FIXED_VERIFIED` | API/test seam `internal`; wording visuale corretto. |

Handoff fixer: `CODEX_FIX_COMPLETE_TO_RE_REVIEW`.

## Re-review indipendente post-FIX — 2026-08-09

- Baseline/`HEAD`/`origin/main`:
  `4b2b4a93dd5d4db7d1cfb83e897aa5cbac40366e`.
- Digest snapshot completo prima del solo tracking re-review:
  `7ee2e133831d6288be1ffb46a4a47dc5decc35bf069b21f987cf5d03a334e5ff`.
- Digest codice/test/risorse:
  `3222585189db0a66deda692feca1b2d8a0573c898d4a6996c8a41306e1a0708a`.
- Ruolo: `CODEX_RE_REVIEWER`, review-only; nessun file applicativo modificato.

### Finding → risultato autonomo

| Finding | Esito | Evidence re-reviewer |
|---|---|---|
| R141-01 | `FIXED_VERIFIED` | `DefaultInventoryRepositoryTest`: 216/216; caso oltre 60 collisioni verifica 62 timestamp distinti, ultimo history point e prodotto finale. Resolver senza cap e sotto la transazione Room. |
| R141-02 | `FIXED_VERIFIED` | `manualEntryDialogBlocksInvalidNumbersAndPreservesBlankFallbackSemantics`: invalid/negative bloccati, Confirm disabled, nessuna riga emessa; blank corretti salvano purchase `50`, retail `100`, quantity blank e nessun raw invalido. |
| R141-03 | `FIXED_VERIFIED` | Assertion runtime `SemanticsProperties.Error` sul nodo `task141.edit.stock-quantity` con copy localizzata. |
| R141-04 | `FIXED_VERIFIED` | Tutte le nuove dichiarazioni production sono `internal`/`private`; nessun file dependency/schema modificato. Copy visuale/evidence malformed, negative e `NOT_RUN` ora coerente. |

### Gate autonomi re-reviewer

| Gate | Risultato |
|---|---|
| Targeted JVM esteso: formatter + repository + import analyzer + localizzazione | `PASS`, 267 test, 0 failure/error/skip, exit `0` |
| Full JVM/Robolectric | `PASS`, 880 test, 0 failure/error, 5 skip, exit `0` |
| `assembleDebug`, `assembleDebugAndroidTest`, `:app:lint` | `PASS`, exit `0` |
| Lint report | `PASS`, 28 warning, 0 error/fatal |
| Connected instrumentation API 35 | `PASS`, 6/6, 0 failure/error/skip, exit `0` |
| Focused instrumentation per visual QA | `PASS`, 1/1, exit `0` |
| Screenshot invalid/malformed | `PASS`, 1080×1454, messaggi leggibili e nessun overlap/crop osservato; artifact fuori repository |
| `git diff --check` | `PASS`, exit `0` |
| Hygiene file accidentali/artifact/pattern credenziali | `PASS`, nessun match, exit `0` |
| Physical device / TalkBack reale | `NOT_RUN`, hardware non disponibile; semantica coperta dal test Compose |

Tentativi ambientali non prodotto: `adb` non qualificato non era nel `PATH`
(exit `127`), poi il path SDK assoluto ha rilevato correttamente `emulator-5554`;
il primo pull screenshot dopo `connectedDebugAndroidTest` non trovava l'artifact
per il cleanup dell'app test (exit `1`), quindi install + focused instrumentation +
pull sono stati rieseguiti con exit `0`.

### Verdict re-review

`APPROVED` — `CODEX_REVIEW_APPROVED_AWAITING_USER_CONFIRMATION`.

TASK-141 resta `ACTIVE / REVIEW`, mai `DONE` senza conferma utente. Client
TASK-033, release train, Supabase e produzione risultano non modificati; nessun
Deep Security Scan o security scan sostitutivo è stato avviato.
