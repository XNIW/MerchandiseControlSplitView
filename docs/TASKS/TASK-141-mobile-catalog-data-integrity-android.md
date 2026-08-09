# TASK-141 — Mobile catalog data integrity e import truthfulness (Android)

## Stato

- Stato: `ACTIVE`
- Fase: `REVIEW`
- Coordination key: `MOBILE-CATALOG-INTEGRITY-001`
- Repository: `XNIW/MerchandiseControlSplitView`
- Baseline: `origin/main` `4b2b4a93dd5d4db7d1cfb83e897aa5cbac40366e`
- Branch: `agent/mobile-catalog-data-integrity-20260809`
- Apertura: `2026-08-09`
- Responsabile attuale: `USER_APPROVER`
- Autorizzazione: prompt utente `PRODUCT COMPLETION + MODERN UI/UX OPTIMIZATION`
  del 2026-08-09, che autorizza audit, planning, execution, review e integrazione
  condizionata mantenendo separati i ruoli logici.

## Obiettivo

Impedire perdita silenziosa di prezzi e quantità nei flussi di modifica e
inserimento manuale, rendere atomiche le mutazioni prodotto locali con la loro
cronologia/dirty marker, preservare ogni cambio prezzo rapido e comunicare in
modo veritiero la policy dei barcode duplicati nell'import supplier.

## Scope

- validazione condivisa degli input numerici opzionali: vuoto intenzionale,
  valore valido non negativo, input invalido e valore negativo;
- errori inline Material/semantici per prezzo acquisto e quantità in
  `EditProductDialog` e `ManualEntryDialog`;
- transazione Room unica per product insert/update, price history e dirty marker;
- timestamp collision-safe per price history create/update;
- copy duplicate barcode coerente con `last row wins`, senza somma quantità, in
  IT/EN/ES/ZH;
- test di regressione parser/policy, repository e risorse/localizzazione.

## Non incluso

- cambi a Client, TASK-032/TASK-033, security review o release PR;
- cambi schema Room/Supabase, migration, RLS, RPC, produzione o sync manuale;
- ridisegno generale, error recovery paging, lifecycle async del dialog,
  image upload retry o refactor architetturali.

## Criteri di accettazione

| ID | Criterio |
|---|---|
| A-141-01 | In `EditProductDialog`, blank resta clear opzionale; purchase/stock non blank invalidi o negativi bloccano save con errore inline localizzato; retail conserva la policy required `> 0`. |
| A-141-02 | `ManualEntryDialog` non applica fallback o conserva raw per purchase/quantity non blank invalidi/negativi; blank purchase conserva il fallback business esistente e blank quantity conserva la semantica preesistente. |
| A-141-03 | Add/update prodotto, price history e dirty marker sono atomici: un fault intermedio lascia il database invariato e nessuna notifica post-commit falsa. |
| A-141-04 | Due update prezzo nello stesso secondo producono due punti distinti e la cronologia più recente rappresenta il valore finale. |
| A-141-05 | La preview import comunica in quattro lingue che l'ultima riga viene usata e la quantità non viene sommata; contratto, codice e test restano allineati. |
| A-141-06 | Nessuna nuova dipendenza/API/schema; target Client TASK-033 e release train restano intatti. |
| A-141-07 | Test mirati, baseline repository/import, full unit, lint, build e visual QA disponibile hanno evidence reale; ogni limite device è dichiarato. |

## Decisioni

| # | Decisione | Motivazione |
|---|---|---|
| 1 | Batch P0/P1 data-integrity prima del polish. | Il comportamento corrente può cancellare valori o perdere history; priorità richiesta dall'utente. |
| 2 | Validazione numerica pura e condivisa sopra i parser CL esistenti. | Evita business logic duplicata e preserva i formatter centrali Chile. |
| 3 | Nessun cambio semantico per i campi realmente vuoti. | Il fix distingue cancellazione intenzionale da input non parsabile. |
| 4 | `db.withTransaction` per l'intera mutazione locale. | Prodotto, history e dirty marker sono una sola unità di correttezza. |
| 5 | Il lifecycle del dialog async resta follow-up. | Richiede un contratto di saving/error state separato e non è necessario al fix dati atomico. |

## Planning

1. Aggiungere outcome numerico puro e test per empty/valid/invalid/negative.
2. Collegare gli outcome ai due editor con errori inline e CTA disabilitata/bloccata.
3. Racchiudere add/update in transazione e usare il resolver timestamp già
   esistente; aggiungere fault-injection e same-second tests.
4. Correggere le quattro risorse duplicate-policy e aggiungere un guard test.
5. Eseguire gate mirati e canonici, QA visuale quando l'ambiente lo consente.
6. Consegnare a review indipendente; non marcare `DONE` senza conferma utente.

### Handoff → Execution

- Handoff: `CODEX_PLAN_READY_AWAITING_USER_AUTHORIZATION` soddisfatto dal prompt
  esplicito del 2026-08-09.
- Prossima fase: `EXECUTION`.
- Azione: implementare soltanto A-141-01…A-141-07.

## Execution

### Esecuzione — 2026-08-09

**File modificati:**

- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ClNumberFormatters.kt`
  — outcome tipizzato e parsing lossless degli input numerici opzionali;
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/EditProductDialog.kt`
  — errori inline purchase/stock, focus/keyboard recovery e stato errore visibile;
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt`
  — validazione Manual Entry e purchase price incluso in prefill/copy/comparison/reset;
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
  — transazioni Room atomiche e timestamp history collision-safe;
- `app/src/main/res/values*/strings.xml` — errori numerici e copy duplicate policy
  IT/EN/ES/ZH;
- `app/src/test/**/ClNumberFormattersTest.kt`,
  `DefaultInventoryRepositoryTest.kt`,
  `Task141ImportPolicyLocalizationTest.kt` e
  `app/src/androidTest/**/CatalogTextRealFlowsDeviceTest.kt` — regressioni JVM,
  Robolectric e runtime emulator;
- `docs/MASTER-PLAN.md`, questo task ed evidence TASK-141 — tracking e handoff.

**Azioni eseguite:**

1. Distinto empty/valid/invalid/negative senza cambiare la semantica dei campi
   realmente vuoti.
2. Bloccato il salvataggio prima della mutazione e mantenuto il draft correggibile.
3. Racchiuso product/history/dirty marker nella stessa transazione Room; la notifica
   catalogo avviene solo dopo commit.
4. Riutilizzato il resolver timestamp esistente anche per create/update manuale.
5. Allineata la copy import a `last row wins`, quantità non sommata, in quattro lingue.
6. UI/UX: su Save il focus viene chiuso e la tastiera nascosta; l'errore stock resta
   visibile anche nella riga compatta con item code (motivo: feedback immediato e
   nessun errore nascosto dalla tastiera).

**Check obbligatori:**

| Check | Stato | Note |
|---|---|---|
| Build Gradle | ✅ ESEGUITO | `assembleDebug` e `assembleDebugAndroidTest`, exit `0`. |
| Lint | ✅ ESEGUITO | `./gradlew --no-daemon lint`, exit `0`; 28 warning, 0 errori/fatal. |
| Warning nuovi | ✅ ESEGUITO | Nessun warning lint riferito ai Kotlin modificati o alle nuove risorse; i 28 warning riguardano configurazione/dipendenze, risorse e sorgenti esistenti. |
| Coerenza con planning | ✅ ESEGUITO | Diff limitata ad A-141-01…A-141-07. |
| Criteri di accettazione | ✅ ESEGUITO | Matrice CA → evidence in `docs/TASKS/evidence/TASK-141/README.md`. |

**Baseline regressione TASK-004:**

- suite completa JVM/Robolectric: 879 test, 0 failure/error, 5 skip;
- suite repository/formatter/localizzazione mirata: 232 test finali, 0 failure;
- test aggiunti/aggiornati: rollback add/update, history same-second, parser e copy
  locale; test device invalid → correzione → persistenza;
- emulator API 35: 5/5 test strumentati; nessun claim physical-device.

**Incertezze / limiti:**

- TalkBack e hardware fisico non verificati; la semantica è coperta staticamente e
  il flusso visuale è stato eseguito su emulator;
- il lifecycle async del dialog e gli errori quick-create supplier/category restano
  follow-up esplicitamente fuori scope.

**Handoff notes:**

- diff codice congelata per review indipendente;
- gli esiti intermedi falliti e le correzioni test-only sono registrati nelle
  evidence, senza occultarli;
- Client TASK-033, release PR, produzione, Supabase e secrets non sono stati toccati.

## Review

### Review indipendente — 2026-08-09

- Esito: `CHANGES_REQUIRED`.
- Snapshot revisionato: branch `agent/mobile-catalog-data-integrity-20260809`,
  baseline `4b2b4a93dd5d4db7d1cfb83e897aa5cbac40366e`, digest diff finale
  `e8171f13d6409f248bcdd7e1e837f47e9fcffd23704eb43ce67cc4fce3648c2e`.
- Separazione logica: review-only; il reviewer non ha modificato codice, test,
  risorse, Planning, Decisioni o criteri.

**Finding da correggere:**

| ID | Severità | Finding | Correzione e regressione richieste |
|---|---|---|---|
| R141-01 | `S2 / P2` | `uniquePriceEffectiveAtLocked` verifica soltanto 60 candidate e poi restituisce la successiva senza controllarla. Con clock fisso, add + 61 update distinti può aggiornare il prodotto ma perdere l'ultimo punto history perché l'indice univoco collidente viene ignorato. | Allocare sempre una chiave libera nella transazione, senza limite non sicuro; aggiungere un test deterministico oltre 60 collisioni che confronti prodotto finale e history completa. |
| R141-02 | `S2 / P2` | A-141-02 non ha una regressione del flusso reale `ManualEntryDialog`; i nuovi tag `task141.manual.*` non sono usati dai test e l'instrumentation aggiunta copre soltanto `EditProductDialog`. | Verificare invalid/negative bloccati, blank purchase con fallback, blank quantity invariata, correzione valida e assenza di raw invalido persistito. |
| R141-03 | `S3 / P3` | Nella riga compatta item code/stock il campo quantità usa `isError`, ma il messaggio localizzato è separato dal campo dopo il contenuto scrollabile; l'associazione TalkBack al motivo specifico non è garantita. | Associare il messaggio tramite supporting text o semantica `error` e aggiungere un'assertion Compose; TalkBack reale resta un limite dichiarabile. |
| R141-04 | `S3` | A-141-06/evidence dichiarano nessuna nuova API, ma la diff aggiunge tipi/funzioni Kotlin pubblici e un parametro al costruttore pubblico del repository. L'evidence visuale dichiara inoltre negative, mentre il test/screenshot usa soltanto input invalidi. | Internalizzare la superficie/test seam non necessaria oppure ottenere emendamento esplicito; correggere l'evidence affinché distingua verifiche invalid, negative e non eseguite. |

**Gate autonomi reviewer:**

- targeted JVM esteso: 266 test, 0 failure/error/skip, exit `0`;
- full JVM/Robolectric: 879 test, 0 failure/error, 5 skip, exit `0`;
- `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: exit `0`;
- lint canonico: 28 warning, 0 error/fatal, exit `0`;
- instrumentation completa API 35: 5/5, exit `0`;
- visual QA emulator: stato invalido leggibile, senza overlap/crop osservato;
- `git diff --check` e controlli mirati su file accidentali/artifact/pattern
  credenziali: exit `0`, nessun match;
- physical device e TalkBack: `NOT_RUN`; nessun security scan eseguito.

I gate verdi non compensano i finding di integrità, coverage e contratto. TASK-141
resta `ACTIVE`, passa a `FIX` e richiede una re-review indipendente prima di qualunque
approvazione, commit, PR o merge.

## Fix

### Fix R141-01…R141-04 — 2026-08-09

| Finding | Correzione | Regressione finale |
|---|---|---|
| R141-01 | Il resolver price-history continua a interrogare l'indice univoco finché trova una chiave realmente libera, senza fallback non verificato. | Test deterministico con add + 61 update allo stesso clock: 62 punti distinti e prodotto finale coerente. |
| R141-02 | `ManualEntryDialog` usa la stessa validazione tipizzata di purchase/quantity; invalid/negative restano correggibili e non arrivano alla callback, mentre i blank preservano fallback e semantica esistenti. | Test Compose reale su invalid, negative, blank purchase `50`, blank quantity e assenza di raw invalido persistito. |
| R141-03 | Il campo stock compatto espone il motivo tramite semantica `error`, oltre al testo visibile localizzato. | Assertion Compose sul nodo stock e sulla relativa semantica di errore. |
| R141-04 | Outcome/validator e clock hook sono `internal`; non è stata aggiunta superficie API pubblica. L'evidence visuale distingue lo screenshot malformed/invalid dalla copertura runtime dei valori negativi. | Audit diff/API, full gate e `git diff --check`. |

**Gate post-fix:**

- targeted JVM/repository/localizzazione: `PASS`, 233 test, 0 failure/error/skip;
- full JVM/Robolectric: `PASS`, 880 test, 0 failure/error, 5 skip;
- `assembleDebug`, `assembleDebugAndroidTest` e lint: `PASS`, exit `0`;
- lint: `PASS`, 28 warning preesistenti, 0 error/fatal;
- instrumentation `CatalogTextRealFlowsDeviceTest` su emulator API 35:
  `PASS`, 6/6, 0 failure/skip;
- visual QA: `PASS` sullo stato malformed/invalid; valori negativi verificati dal
  test runtime, non dichiarati come screenshot dedicato;
- physical device e TalkBack reale: `NOT_RUN` (hardware non disponibile; copertura
  Compose/semantica presente).

I fix restano nello scope approvato; nessun Client, release PR, Supabase,
produzione, secret o security scan è stato toccato.

## Re-review

### Re-review indipendente post-FIX — 2026-08-09

- Esito: `APPROVED`.
- Snapshot post-fix revisionato contro baseline
  `4b2b4a93dd5d4db7d1cfb83e897aa5cbac40366e`: digest completo prima del solo
  tracking re-review
  `7ee2e133831d6288be1ffb46a4a47dc5decc35bf069b21f987cf5d03a334e5ff`;
  digest codice/test/risorse
  `3222585189db0a66deda692feca1b2d8a0573c898d4a6996c8a41306e1a0708a`.
- Separazione logica: `CODEX_RE_REVIEWER`; nessuna modifica a codice, test,
  risorse, Planning, Decisioni o criteri.

**Risoluzione finding:**

| Finding | Esito re-review | Verifica indipendente |
|---|---|---|
| R141-01 | `FIXED_VERIFIED` | Resolver `while` senza cap nella transazione; add + 61 update con clock fisso produce 62 chiavi/history point distinti e prezzo finale `71`. |
| R141-02 | `FIXED_VERIFIED` | Flusso Compose reale: invalid purchase e negative quantity mostrano errori e disabilitano Confirm; correzione a blank abilita il salvataggio, produce fallback purchase `50`, quantity blank e nessun raw invalido. |
| R141-03 | `FIXED_VERIFIED` | Il campo stock compatto espone `SemanticsProperties.Error` con messaggio localizzato; assertion eseguita nel test production screen. |
| R141-04 | `FIXED_VERIFIED` | Nuovi outcome/validator e clock hook sono `internal`; nessun cambio a dipendenze/schema/API pubblica. Evidence visuale distingue correttamente malformed/invalid, runtime negative e limiti reali. |

**Gate autonomi re-reviewer:**

- targeted JVM esteso: `PASS`, 267 test, 0 failure/error/skip, exit `0`;
- full JVM/Robolectric: `PASS`, 880 test, 0 failure/error, 5 skip, exit `0`;
- `assembleDebug`, `assembleDebugAndroidTest` e lint canonico: `PASS`, exit `0`;
- lint: 28 warning preesistenti, 0 error/fatal;
- instrumentation completa `CatalogTextRealFlowsDeviceTest`, emulator API 35:
  `PASS`, 6/6, 0 failure/skip, exit `0`;
- rerun visuale focalizzato: `PASS`, 1/1, exit `0`; screenshot 1080×1454
  dello stato malformed/invalid ispezionato, senza overlap/crop osservato;
- `git diff --check` e hygiene mirata su file accidentali, artifact e pattern
  credenziali: `PASS`, nessun match;
- physical device e TalkBack reale: `NOT_RUN`; copertura Compose/semantica presente;
- nessun Deep Security Scan o security scan sostitutivo eseguito.

Nessun nuovo finding. TASK-141 resta `ACTIVE / REVIEW`: `APPROVED` non equivale a
`DONE`, non autorizza merge e richiede conferma esplicita del `USER_APPROVER`.

## Handoff

`CODEX_REVIEW_APPROVED_AWAITING_USER_CONFIRMATION`.

- Fase corrente: `REVIEW`.
- Azione: attendere la conferma esplicita del `USER_APPROVER`; nessun passaggio a
  `DONE`, commit, PR o merge è stato eseguito dal re-reviewer.
