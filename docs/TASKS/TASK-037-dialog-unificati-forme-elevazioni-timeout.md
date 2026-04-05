# TASK-037 — Dialog unificati: forme, elevazioni, timeout

---

## Informazioni generali

| Campo                | Valore |
|----------------------|--------|
| ID                   | TASK-037 |
| Stato                | **DONE** (2026-04-05 — review repo-grounded APPROVED senza fix; chiusura autorizzata dall'utente) |
| Priorità             | MEDIA |
| Area                 | UX / UI / Dialogs |
| Creato               | 2026-04-05 |
| Ultimo aggiornamento | 2026-04-05 — review repo-grounded APPROVED senza fix; task chiuso in `DONE` |

---

## Dipendenze

- **TASK-030** (`DONE`) — design system (`AppShapes`, `appSpacing`, `appColors`, tema Material3). **Nota repo:** `AppShapes.extraLarge` è **24.dp**, non 28.dp; questo task impone **28.dp** per i modali nel perimetro come richiesto dal backlog, **senza** obbligo di estendere `AppShapes` salvo decisione esplicita in Execution (preferenza: literal o costante `private` locale per minimo cambiamento).

---

## Scopo

1. Allineare **shape** e **elevazione percepita** dei dialog modali in `DatabaseScreenDialogs.kt` e `GeneratedScreenDialogs.kt` a un pattern unico (**angoli 28.dp**, elevazione coerente con Material3 e con lo stile già usato in Generated).
2. Introdurre un **timeout di sicurezza** (failsafe) sui dialog **non dismissibili** (oggi: `LoadingDialog` con `dismissOnBackPress` / `dismissOnClickOutside` disabilitati), per ridurre il rischio di UI bloccata in caso di stato `Loading` orfano o operazione prolungata oltre soglia ragionevole.
3. Intervento **minimo**: nessun refactor architetturale, nessuna modifica a DAO / repository / navigazione salvo wiring strettamente necessario per il callback di timeout (vedi § Ambiguità / file aggiuntivi).

---

## Contesto

### Evidenza codice — `GeneratedScreenDialogs.kt`

- **Quattro** `AlertDialog`: `GeneratedScreenDiscardDraftDialog`, `GeneratedScreenExitFromHistoryDialog`, `GeneratedScreenExitToHomeDialog`, `GeneratedScreenSearchDialog`.
- Tutti espongono esplicitamente `shape = RoundedCornerShape(28.dp)` (coerenti tra loro).
- **Nessun** `tonalElevation` / `shadowElevation` esplicito su `AlertDialog` → valgono i default Material3 del componente / tema.
- Comportamento dismiss: `onDismissRequest` sempre fornito; dialog **standard** (back / tap fuori secondo default M3 del `AlertDialog`).

### Evidenza codice — `DatabaseScreenDialogs.kt`

| Componente | Tipo | Shape / elevazione | Dismiss |
|------------|------|--------------------|---------|
| `LoadingDialog` | `Dialog` + `Surface` interno | `RoundedCornerShape(24.dp)`, `tonalElevation = 6.dp`, `shadowElevation = 12.dp` | `DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)`, `onDismissRequest = {}` — **nessun timeout** |
| `DatabaseExportDialog` | `AlertDialog` | Nessuno `shape` esplicito → default tema (`AppShapes` / M3; `extraLarge` in `Theme.kt` = **24.dp**) | `onDismissRequest = onDismiss` |
| `DeleteProductConfirmationDialog` | `AlertDialog` | Stesso: shape implicita (stima: allineata a tema ~24.dp, non 28.dp) | `onDismissRequest = onDismiss` |
| `PriceHistoryBottomSheet` | `ModalBottomSheet` | Non è un dialog modale «card»; shape regolata dal bottom sheet M3 | `onDismissRequest = onDismiss` |

### Riuso di `LoadingDialog` (rilevante per timeout)

`LoadingDialog` è richiamato anche da **`DatabaseScreen.kt`**, **`GeneratedScreen.kt`**, **`PreGenerateScreen.kt`** (oltre all’overload `LoadingDialog(UiState.Loading)` nello stesso file). Qualsiasi **timeout comportamentale** (es. reset stato / `consumeUiState`) richiede un **callback** passato dal parent o logica nel ViewModel — non realizzabile solo dentro il composable con `onDismissRequest = {}` senza estendere la firma o i call site.

---

## Non incluso

- Unificare dialog di **altre** schermate (es. `HistoryScreen`, `EditProductDialog`, `ManualEntry`, ecc.) — fuori dal perimetro dichiarato nel backlog.
- Trattare **`ModalBottomSheet`** (`PriceHistoryBottomSheet`) come dialog 28.dp: componente diverso; intervento solo se strettamente necessario per coerenza **locale** nel file (da evitare se possibile).
- Modifiche a **business logic** import/export/delete, repository, DAO, `NavGraph`, ViewModel **salvo** wiring minimo per callback timeout (vedi sotto).
- Nuove dipendenze Gradle.
- Test UI strumentati (Espresso / Compose test) — non richiesti salvo decisione futura.
- Baseline **TASK-004**: **non applicabile** se il diff resta su soli composable UI dialog senza toccare repository/ViewModel dati.

---

## File potenzialmente coinvolti

| File | Ruolo |
|------|--------|
| `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` | `LoadingDialog` (shape 28.dp, elevazione allineata; timeout failsafe); `DatabaseExportDialog` + `DeleteProductConfirmationDialog` (shape 28.dp, elevazione esplicita se necessario per parità con Generated). |
| `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreenDialogs.kt` | Verifica parità elevazione con Database; eventuale estrazione costante shape condivisa **solo se** si riduce duplicazione senza nuovo modulo (stesso package `ui.screens`). |

**File aggiuntivi (solo se il timeout deve avere effetto reale sullo stato — raccomandato):**

| File | Ruolo |
|------|--------|
| `DatabaseScreen.kt` | Passa callback timeout a `LoadingDialog` (es. `consumeUiState` / clear loading coerente con pattern esistente). |
| `GeneratedScreen.kt` | Idem per `LoadingDialog` export / DB loading. |
| `PreGenerateScreen.kt` | Idem per loading Excel / DB. |

**Assunzione conservativa documentata:** se l’utente vuole **rigorosamente** solo i due file del backlog, il timeout può restare **infrastruttura** (parametro opzionale default no-op) **senza** soddisfare pienamente l’intento UX «uscita da stallo» finché i call site non vengono aggiornati — da evitare; in Execution va chiarito con l’utente o esteso il perimetro file nella nota MASTER-PLAN.

---

## Perimetro incluso / escluso (sintesi)

| Incluso | Escluso |
|---------|---------|
| `AlertDialog` in Database: `shape = RoundedCornerShape(28.dp)` allineato a Generated | Redesign contenuto export/delete |
| `LoadingDialog` `Surface`: angoli **28.dp**; elevazione **coerente** con target scelto (es. stessi dp di Generated o coppia tonal/shadow esplicita su `AlertDialog` lato Database) | Cambiare testi, flussi conferma, checkbox export |
| Timeout failsafe su `LoadingDialog` con soglia **costante nominata** (es. `private const val LOADING_SAFETY_TIMEOUT_MS`) e documentazione | Timeout su `AlertDialog` dismissibili (export/delete/search) |
| Wiring minimo nei parent se necessario per timeout | Modifiche a `PriceHistoryBottomSheet` salvo necessità marginale |

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Tutti gli `AlertDialog` in **`DatabaseScreenDialogs.kt`** (`DatabaseExportDialog`, `DeleteProductConfirmationDialog`) usano **shape 28.dp** esplicita (o equivalente semanticamente identico), allineata a **`GeneratedScreenDialogs.kt`**. | S | ✅ ESEGUITO |
| 2 | Il contenitore principale di **`LoadingDialog`** (`Surface` interno) usa **angoli 28.dp** (non 24.dp). | S | ✅ ESEGUITO |
| 3 | **Elevazione:** `DatabaseExportDialog` e `DeleteProductConfirmationDialog` hanno **tonalElevation** (e se applicabile **shadowElevation**) **espliciti** e **uguali tra loro** e **coerenti** con le quattro `AlertDialog` di Generated (stessi valori dp, verificabili nel diff). | S | ✅ ESEGUITO |
| 4 | **`LoadingDialog`:** dopo una soglia di tempo documentata (costante nel file), viene invocato un **callback** `onSafetyTimeout` (o nome equivalente) che i call site possono usare per **sbloccare** lo stallo (es. allineato a `consumeUiState` / reset già esistente). I tre call site principali (`DatabaseScreen`, `GeneratedScreen`, `PreGenerateScreen`) **passano** un’implementazione non vuota **oppure** documentano in Execution perché non applicabile (⚠️ con motivazione). | S + M | ✅ ESEGUITO |
| 5 | Nessuna regressione funzionale: export, delete conferma, discard/exit/search su Generated, loading import/export — flussi invariati salvo chiusura **solo** in caso timeout su loading (comportamento nuovo voluto). | M | ✅ ESEGUITO |
| 6 | `./gradlew assembleDebug` — **BUILD SUCCESSFUL**, 0 errori. | B | ✅ ESEGUITO |
| 7 | `./gradlew lint` — nessun warning **nuovo** attribuibile al diff. | S | ✅ ESEGUITO |

**Definition of Done — task UX/UI** (da `MASTER-PLAN.md`, adattato): build/lint verdi; smoke manuale su almeno un dialog per file (export, delete, uno Generated, loading con operazione breve); verifica light/dark opzionale ma consigliata per scrim + surface dialog.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | **28.dp** è il riferimento unico di shape modale nel perimetro, anche se `AppShapes.extraLarge` = 24.dp. | Allineamento al backlog e al pattern già presente in `GeneratedScreenDialogs.kt`. | 2026-04-05 |
| 2 | **`PriceHistoryBottomSheet`** resta fuori dall’unificazione 28.dp salvo emergenza locale. | È bottom sheet, non dialog card; riduce scope creep. | 2026-04-05 |
| 3 | **Timeout** interpretato come **failsafe anti-stallo** su `LoadingDialog`, non come auto-dismiss di dialog conferma. | Unico dialog non dismissibile nel perimetro. | 2026-04-05 |
| 4 | **Soglia timeout** (es. 60s vs 120s) non fissata nel planning: va scelta in Execution con valore **conservativo** (lungo abbastanza da non interrompere import legittimi grandi). | Richiede giudizio prodotto; documentare valore scelto nel log Execution. | 2026-04-05 |

---

## Planning (Claude)

### Analisi (repo-grounded)

1. **Shape:** oggi **divergenza chiara** — Generated **28.dp** esplicito; Database `LoadingDialog` **24.dp**; `AlertDialog` Database senza override → probabile **24.dp** da tema (`Theme.kt` `AppShapes.extraLarge`).
2. **Elevazione:** `LoadingDialog` ha **6.dp / 12.dp** espliciti su `Surface`; `AlertDialog` in entrambi i file usano **default impliciti** → rischio di **gerarchia visiva diversa** tra loading card e alert.
3. **Dismiss / timeout:** solo `LoadingDialog` è **bloccante**; assenza di timeout = rischio UX se `UiState.Loading` non viene mai pulito (bug preesistente o edge case rete/file).
4. **Dipendenza TASK-030:** colori/spacing già centralizzati; **nessun** token `dialogCorner28` oggi — duplicazione letterale `28.dp` accettata per minimo cambiamento, oppure costante `private` per file.

### Componenti da uniformare

- `DatabaseExportDialog` — `AlertDialog`
- `DeleteProductConfirmationDialog` — `AlertDialog`
- `LoadingDialog` — `Dialog` + `Surface`
- (Verifica) `GeneratedScreenDiscardDraftDialog`, `GeneratedScreenExitFromHistoryDialog`, `GeneratedScreenExitToHomeDialog`, `GeneratedScreenSearchDialog` — già 28.dp; aggiungere **stessa** `tonalElevation` / `shadowElevation` scelta per Database alert se oggi assenti.

### Divergenze rilevate

| Aspetto | Generated `AlertDialog` | Database `AlertDialog` | `LoadingDialog` |
|---------|-------------------------|-------------------------|-----------------|
| Shape | 28.dp esplicito | Default (≈24.dp tema) | 24.dp `Surface` |
| Elevazione | Default M3 | Default M3 | 6 / 12 dp espliciti |
| Dismiss | Standard | Standard | **Non** dismissibile, no timeout |

### Piano di esecuzione (per l’esecutore, post-approvazione)

1. Scegliere una **coppia** `tonalElevation` / `shadowElevation` (o solo `tonalElevation` se allineato a M3 dialog standard) **unica** per tutti gli `AlertDialog` nei due file; applicarla a `DatabaseExportDialog`, `DeleteProductConfirmationDialog` e alle quattro dialog Generated **se** il diff resta minimo.
2. Impostare `shape = RoundedCornerShape(28.dp)` sui due `AlertDialog` Database; aggiornare `LoadingDialog` `Surface` a **28.dp**.
3. Aggiungere a `LoadingDialog` parametro `onSafetyTimeout: () -> Unit` (o overload) + `LaunchedEffect` con delay da costante; aggiornare **DatabaseScreen**, **GeneratedScreen**, **PreGenerateScreen** per passare callback che riusano pattern esistente di reset stato (senza nuova logica repository).
4. Smoke manuale: aprire export dialog, delete dialog, un dialog Generated, forzare loading breve; opzionalmente simulare stallo lungo in debug per verificare timeout.
5. `assembleDebug` + `lint`; documentare in Execution file toccati e valore ms timeout.

### Rischi / edge case

- **Timeout troppo breve:** interrompe import/export realmente lunghi → mitigazione: soglia alta (es. 3–5 minuti) o basata su evidenza; documentare.
- **Callback timeout:** doppio `consumeUiState` o race con completamento normale → mitigazione: idempotenza lato ViewModel o `if (stillLoading)` nel parent.
- **Perimetro file:** limitarsi a due file lascerebbe timeout **inefficace** → mitigazione: estendere call site (§ File potenzialmente coinvolti).
- **Allineamento elevazione:** eccesso di ombra su tutti i dialog può aumentare rumore visivo → mantenere valori M3 tipici (bassa priorità rispetto a coerenza numerica).

---

## Execution

### Esecuzione — 2026-04-05

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` — `LoadingDialog` portato a shape 28.dp, aggiunto `onSafetyTimeout` con soglia conservativa, `DatabaseExportDialog` e `DeleteProductConfirmationDialog` allineati con shape 28.dp + `tonalElevation` esplicita.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreenDialogs.kt` — rese esplicite le `tonalElevation` dei quattro `AlertDialog` già corretti come shape, per convergenza numerica con Database.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — wiring minimo del failsafe: reset locale dell'overlay export e `consumeUiState()` per i loading `UiState`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — wiring minimo del failsafe: reset locale dell'overlay export e `consumeUiState()` per i loading DB.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/PreGenerateScreen.kt` — wiring minimo del failsafe: `resetState()` per loading Excel e `consumeUiState()` per loading DB; reset locale della guardia alla chiusura del loading.

**Azioni eseguite:**
1. Allineati i dialog Database al target del task con `shape = RoundedCornerShape(28.dp)` e `tonalElevation = 6.dp`, lasciando invariati testi, CTA e flussi.
2. Portato `LoadingDialog` a 28.dp e aggiunto `onSafetyTimeout` con `LaunchedEffect(Unit)` + `rememberUpdatedState`, così il timer parte una sola volta per apertura reale del dialog e non viene duplicato dalle recomposition.
3. Cablate implementazioni non vuote di `onSafetyTimeout` nei tre parent richiesti, riusando solo pattern già esistenti (`consumeUiState`, `resetState`) o, per gli export, un rilascio locale del solo overlay per evitare nuovi flussi di errore.
4. UI/UX: mantenuta l'elevazione custom già percepita corretta di `LoadingDialog` (`tonalElevation = 6.dp`, `shadowElevation = 12.dp`) e reso esplicito solo il `tonalElevation = 6.dp` sugli `AlertDialog`, evitando override di shadow superflui dove il default Material3 restava il target migliore.
5. UI/UX: nessun intervento su `PriceHistoryBottomSheet`, nessun redesign dei contenuti dialog, nessun tocco a DAO / repository / navigation / business logic.

**Decisioni UX/UI locali:**
- `AlertDialog`: scelto `tonalElevation = 6.dp` come valore unico nel perimetro, coerente con la profondità già percepita dei dialog Generated senza aggiungere ombra extra rumorosa.
- `LoadingDialog`: mantenuta la coppia esistente `6.dp / 12.dp` e uniformata solo la shape a 28.dp, privilegiando la coerenza percepita invece della simmetria artificiale.
- `LOADING_SAFETY_TIMEOUT_MS = 180_000L` (3 minuti): scelto come failsafe conservativo, abbastanza lungo da non disturbare import/export legittimi ma finito in caso di loading orfano.
- Micro-polish introdotti: esplicitazione locale della tonal elevation nei dialog del perimetro e rilascio pulito dell'overlay export al timeout senza toast o nuovi error state.

**Smoke manuale mirato (emulator `Medium_Phone_API_35`):**
- `DatabaseScreen` export dialog: aperto correttamente in light e dark mode.
- `DatabaseScreen` delete dialog: aperto via swipe `EndToStart` su una riga prodotto; nome e barcode mostrati correttamente.
- `GeneratedScreen` dialog: aperto `GeneratedScreenDiscardDraftDialog` da entry manuale vuota + back.
- `LoadingDialog` breve: due export database consecutivi completati con overlay reale e progress (`Scrittura Prodotti…`, poi `Scrittura Storico prezzi…`), con ritorno pulito alla schermata Database.
- Riapertura/recomposition loading: il secondo export ha riaperto regolarmente il `LoadingDialog` dopo il completamento del primo, senza overlay bloccato o chiusure spurie.
- Limite residuo: il ramo di timeout effettivo a 180s non è stato atteso end-to-end manualmente per evitare una verifica artificiale fuori valore.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 3s` |
| Lint                     | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → `BUILD SUCCESSFUL in 14s` |
| Warning nuovi            | ✅ ESEGUITO | Rimosso l'unico warning Kotlin introdotto (`No cast needed`); restano solo warning/tooling AGP-Kotlin preesistenti fuori scope |
| Coerenza con planning    | ✅ ESEGUITO | Diff limitato ai 5 file del perimetro esecutivo; nessun refactor architetturale, nessun intervento su DAO / repository / navigation / bottom sheet |
| Criteri di accettazione  | ✅ ESEGUITO | Tutti i criteri verificati singolarmente (vedi tabella sotto) |

**Verifica criteri di accettazione:**
| # | Criterio | Stato | Evidenza |
|---|----------|-------|----------|
| 1 | `DatabaseExportDialog` e `DeleteProductConfirmationDialog` usano shape 28.dp esplicita | ✅ ESEGUITO | Override espliciti aggiunti in `DatabaseScreenDialogs.kt` |
| 2 | `LoadingDialog` usa angoli 28.dp | ✅ ESEGUITO | `Surface` interno aggiornato da 24.dp a 28.dp in `DatabaseScreenDialogs.kt` |
| 3 | Elevazione esplicita e coerente tra Database e Generated | ✅ ESEGUITO | `tonalElevation = 6.dp` resa esplicita sui 2 dialog Database e sui 4 dialog Generated; nessun `shadowElevation` applicabile su `AlertDialog` M3 |
| 4 | `LoadingDialog` invoca un callback `onSafetyTimeout` con wiring reale nei 3 parent | ✅ ESEGUITO | `onSafetyTimeout` aggiunto al composable; `DatabaseScreen`, `GeneratedScreen`, `PreGenerateScreen` passano callback non vuoti |
| 5 | Nessuna regressione funzionale nei flussi export/delete/Generated/loading | ✅ ESEGUITO | Smoke emulator guidato positivo su export dialog, delete dialog, Generated discard dialog, loading breve e riapertura del loading; timeout lungo non atteso ma flusso normale invariato |
| 6 | `./gradlew assembleDebug` verde | ✅ ESEGUITO | `BUILD SUCCESSFUL in 3s` |
| 7 | `./gradlew lint` senza warning nuovi del diff | ✅ ESEGUITO | `BUILD SUCCESSFUL in 14s`; nessuna issue nuova attribuibile al diff |

**Baseline regressione TASK-004 (se applicabile):**
- Non applicabile: il diff resta confinato a composable/dialog e wiring UI locale; nessun cambiamento a repository, ViewModel dati, import/export logic o baseline JVM/Robolectric di TASK-004.

**Incertezze:**
- Il callback di timeout reale a 180s non è stato lasciato scadere manualmente; la protezione contro timer duplicati è stata verificata indirettamente tramite due aperture consecutive del `LoadingDialog` e tramite l'uso di `LaunchedEffect(Unit)` + reset locali all'uscita dal loading.

**Handoff notes:**
- Il file `docs/MASTER-PLAN.md` risultava già dirty prima dell'execution e non è stato toccato, in coerenza con `AGENTS.md`; l'eventuale riallineamento del backlog globale / task active resta al planner.
- Se in review serve una prova esplicita del ramo timeout, conviene usare una build/debug session dedicata con soglia temporaneamente ridotta o delay controllato; questo task ha mantenuto la soglia produzione conservativa per non alterare i flussi reali.

---

## Review

### Review — 2026-04-05

**Revisore:** Claude (planner)

**Criteri di accettazione:**
| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Shape 28.dp esplicita sui due `AlertDialog` Database | ✅ | Verificato in `DatabaseScreenDialogs.kt`; allineamento coerente con Generated |
| 2 | `LoadingDialog` a 28.dp | ✅ | `Surface` interno aggiornato a `RoundedCornerShape(28.dp)` |
| 3 | Elevazione coerente e senza override superflui | ✅ | `tonalElevation = 6.dp` resa esplicita sui dialog del perimetro; nessun `shadowElevation` disponibile su `AlertDialog` M3 |
| 4 | `onSafetyTimeout` cablato e realmente usabile nei 3 parent | ✅ | Wiring non vuoto in `DatabaseScreen.kt`, `GeneratedScreen.kt`, `PreGenerateScreen.kt`; callback idempotenti e locali |
| 5 | Nessuna regressione funzionale nei flussi del perimetro | ✅ | Smoke execution coerente con il codice; nessuna contraddizione trovata nella review repo-grounded |
| 6 | `assembleDebug` verde | ✅ | Evidenza execution valida; nessuna modifica codice aggiuntiva in review |
| 7 | `lint` verde e nessun warning nuovo dal diff | ✅ | Evidenza execution valida; nessuna modifica codice aggiuntiva in review |

**Problemi trovati:**
- Nessuno.

**Verdetto:** APPROVED

**Note per fix:**
- Nessun fix necessario. In review non sono stati applicati ulteriori cambi al codice.

---

## Fix

Nessun fix necessario.

---

## Chiusura

### Chiusura — 2026-04-05

- Stato finale: `DONE`
- Esito review: `APPROVED` senza fix
- Chiusura consentita dall'utente nel prompt di review, con istruzione esplicita di portare il task a `DONE` se positivo
- Coerenza repo/task verificata: nessuna contraddizione sostanziale tra planning, execution e codice finale

---

## Riepilogo finale

La TASK-037 chiude con diff minimo e coerente col planning: shape 28.dp unificata nel perimetro, `tonalElevation = 6.dp` esplicita e uniforme sui dialog modali coinvolti, `LoadingDialog` con failsafe a 180s e wiring locale nei tre parent richiesti.

Rischi residui:
- Basso: il ramo timeout reale a 180s non è stato atteso end-to-end manualmente. Mitigazione: implementazione repo-grounded con `LaunchedEffect(Unit)` cancellato alla chiusura del dialog, `rememberUpdatedState` per il callback aggiornato e guardie locali che evitano timer duplicati o callback multipli alla riapertura.

---

## Handoff

- Nessuno. Task chiuso in `DONE`.

---

**Nota stato:** l'utente ha autorizzato l'avvio e, in review, ha richiesto esplicitamente la chiusura in `DONE` in assenza di problemi sostanziali. Review completata e backlog globale riallineato dal planner.
