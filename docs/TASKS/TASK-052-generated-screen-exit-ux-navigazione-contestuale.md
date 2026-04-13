# TASK-052 — GeneratedScreen: uscita semplificata (Fine unico, navigazione contestuale)

---

## Informazioni generali

| Campo                | Valore                                      |
|----------------------|---------------------------------------------|
| ID                   | TASK-052                                    |
| Stato                | DONE                                        |
| Priorità             | ALTA                                        |
| Area                 | UX/UI — GeneratedScreen; Navigation |
| Creato               | 2026-04-12                                  |
| Ultimo aggiornamento | 2026-04-12                                  |

---

## Dipendenze

- Nessuna bloccante. Coerenza consigliata con **TASK-047** `DONE` (top bar Generated) e **TASK-045** `DONE` (tab root / `navigateToRootTab`).

---

## Scopo

Ridurre ridondanza e attrito nell’uscita da `GeneratedScreen`: un’unica azione primaria visibile (“Fine”), allineamento del back di sistema a tale azione, eliminazione del dialog di conferma uscita “standard”, navigazione di chiusura dipendente dal punto di ingresso (PreGenerate → Inventario/Home; Cronologia → Cronologia), mantenendo un’unica eccezione motivata per bozza manuale vuota. Nessun refactor architetturale gratuito; persistenza, cronologia, export/sync e manual entry non devono regressare. La chiusura deve essere percepita come rapida, silenziosa e affidabile, senza introdurre attese inutili o ambiguità tra “torna indietro” e “ho finito”.

---

## Contesto (prodotto)

L’utente percepisce ridondante la conferma d’uscita perché lo stato della entry è già aggiornato in modo persistente durante il lavoro (pattern `updateHistoryEntry` su molte azioni). La schermata è raggiungibile da **PreGenerate**, da **Cronologia** e da **aggiunta manuale** (FilePicker). Serve un comportamento lineare: stessa semantica per “Fine” e back di sistema, senza doppio canale visivo (freccia + Fine).

A livello di UX il significato di “Fine” non deve essere “chiudi l’app” ma **termina questo flusso e torna al contesto corretto**. Questo va esplicitato nel task per evitare implementazioni ambigue: il ritorno a “Home” significa ritorno alla root section **Inventario / FilePicker**, non uscita dall’applicazione. Inoltre la schermata non deve mostrare affordance concorrenti che suggeriscono gerarchie diverse di uscita.


Va mantenuta anche coerenza con il linguaggio dell’app: `Fine` deve essere percepito come primary action calma e conclusiva, non come CTA aggressiva. La top bar finale dovrebbe quindi risultare più leggera e ordinata, con titolo leggibile, overflow non invasivo e assenza di elementi concorrenti che aumentano il rumore visivo.

Va preservata anche la leggibilità operativa della top bar introdotta con i refinement recenti: rimuovere il back non deve far perdere chiarezza alle azioni secondarie utili (export, share, rename), ma solo eliminare la ridondanza dell’uscita. Se una scelta UI richiede una priorità, va favorita una gerarchia semplice: titolo → Fine → overflow secondario.

---

## Comportamento attuale (verificato su codice — 2026-04-12)

### Top bar (`GeneratedScreenTopBar` in `GeneratedScreen.kt`)

- **Navigation icon**: `ArrowBack` → `onNavigateBack` → **`handleBackPress`**.
- **Azioni (se `isGenerated`)**: menu overflow + **`TextButton` “Fine” (`R.string.finish`)** → **`onFinish` = `handleBackPress`** (stesso handler del back visibile).
- **Menu overflow**: include tra l’altro **“Vai alla home”** (`R.string.go_to_home`) → `onNavigateHome` → **`showExitToHomeDialog = true`** (dialog “salva e torna alla home”).

Quindi oggi **back visibile** e **Fine** sono già equivalenti; la ridondanza UX è la **doppia affordance** (freccia + Fine) e i **dialog** su alcuni percorsi.

### `handleBackPress` (stesso file)

```254:268:app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt val handleBackPress = {
        val isManualDraftEmpty = isManualEntry && excelViewModel.excelData.size <= 1

        if (isManualDraftEmpty) {
            showExitDialog = true // "Elimina bozza?" → GeneratedScreenDiscardDraftDialog
        } else if (isNewEntry) {
            onBackToStart() // popBackStack — senza dialog
        } else {
            showExitFromHistoryDialog = true // Salva / Esci senza salvare / Annulla
        }
    }
```

- **`BackHandler`** invoca **`handleBackPress`** (back di sistema = stesso handler).

### Dialoghi (`GeneratedScreenDialogs.kt`)

| Dialog | Quando | Effetto principale |
|--------|--------|-------------------|
| `GeneratedScreenDiscardDraftDialog` | bozza manuale vuota (`isManualEntry` && `excelData.size <= 1`) | discard: `deleteHistoryEntry` + `revertToPreGenerateState` + `onBackToStart()` |
| `GeneratedScreenExitFromHistoryDialog` | entry da cronologia (`isNewEntry == false`) | opzioni: revert DB + reload entry + pop; oppure `saveCurrentStateToHistory` + pop |
| `GeneratedScreenExitToHomeDialog` | da overflow “Vai alla home” | `saveCurrentStateToHome` + `onNavigateToHome()` |

### Navigazione (`NavGraph.kt`)

- `onBackToStart = { navController.popBackStack() }` → torna alla schermata precedente nello stack (**PreGenerate** se il flusso era FilePicker → PreGenerate → Generated; **History** se History → Generated; **FilePicker** se ingresso diretto manuale FilePicker → Generated).
- `onNavigateToHome` → **`navigateToRootTab(FilePicker)`** (tab Inventario / home root con `popUpTo(startDestination)` + stato).

### Parametri route `generated/{entryUid}/{isNew}/{isManualEntry}`

- Da **PreGenerate** (`generateFilteredWithOldPrices`): `isNew = true`, `isManualEntry` default **false**.
- Da **FilePicker** (manuale): `isNew = true`, **`isManualEntry = true`**.
- Da **History**: **`isNew = false`**, `isManualEntry` dalla entry.

**Implicazione:** la coppia **`isNewEntry && !isManualEntry`** identifica in modo stabile l’ingresso da flusso **PreGenerate → Generated** senza nuovi argomenti di navigazione (euristica utile per pianificare il percorso “Fine → Home”).

---

## Comportamento desiderato

1. **Rimuovere** il pulsante **back visibile** dalla top bar; restano **“Fine”** come azione primaria di chiusura e il menu overflow solo per azioni realmente secondarie/non ridondanti.

2. **Back di sistema** e **“Fine”** devono restare **equivalenti** (stesso handler unico di uscita “standard”).

3. **Nessun dialog di conferma uscita “standard”** per percorsi con dati / entry non vuota: niente `GeneratedScreenExitFromHistoryDialog` come comportamento predefinito se la decisione prodotto è che il persist è già sufficiente.

4. **Eccezione:** mantenere (se ancora necessaria dopo verifica) **solo** la conferma per **bozza manuale vuota** (`GeneratedScreenDiscardDraftDialog` / equivalente), per evitare discard accidentale.

5. **Navigazione contestuale alla chiusura “normale”** (dopo eventuale flush salvataggio — vedi note tecniche):
   - **Ingresso da PreGenerate** (`isNewEntry && !isManualEntry`): **Fine / back sistema → Inventario/Home** (stesso obiettivo di oggi `onNavigateToHome`: tab `FilePicker`), **non** solo `popBackStack()` verso PreGenerate.
   - **Ingresso da Cronologia** (`!isNewEntry`): **Fine / back sistema → Cronologia** (equivalente a `popBackStack()` verso `History`).
   - **Ingresso manuale nuova da FilePicker** (`isNewEntry && isManualEntry`): **Fine / back sistema →** tornare al tab/file picker coerente con stack attuale (oggi `popBackStack()` è corretto se lo stack è FilePicker → Generated).
   - La transizione deve risultare **immediata e silenziosa**: niente toast/dialog di conferma in uscita standard salvo caso di errore reale sul flush finale.

6. **Non introdurre regressioni** su: persistenza stato griglia, aggiornamento cronologia, manual entry, export, sync/analisi import legati alla entry.

7. La top bar finale deve risultare più chiara anche visivamente: migliore priorità tra titolo, overflow e Fine; niente azioni che duplicano il comportamento di chiusura già espresso da “Fine”.

8. L’affordance di chiusura deve restare coerente anche nella forma: default consigliato in planning = mantenere `Fine` come `TextButton` testuale chiaro in top bar, evitando sostituzioni con icone ambigue o CTA visivamente troppo aggressive rispetto al tono dell’app.

9. L’uscita non deve introdurre una UX di attesa pesante: default consigliato in planning = nessun full-screen loading dialog dedicato alla chiusura, ma eventuale disabilitazione leggera delle action top bar e feedback non bloccante solo in caso di errore reale.

---

## Non incluso

- Redesign della griglia, del progress card o del summary footer.
- Modifiche a DAO, schema Room, repository (salvo emergenza documentata).
- Cambiamenti al parser Excel o al flusso PreGenerate oltre al wiring navigate verso Generated se strettamente necessario (preferenza: **no**).
- Task iOS / porting logica da SwiftUI.
- Rework del contenuto funzionale dell’overflow oltre al cleanup delle sole azioni ridondanti rispetto a `Fine` (export/share/rename restano fuori scope salvo micro-riordino visivo non regressivo).

---

## File potenzialmente coinvolti

| File | Ruolo |
|------|--------|
| `app/src/main/java/.../ui/screens/GeneratedScreen.kt` | handleBackPress/handler unico di uscita, BackHandler, top bar (rimozione navigation icon e pulizia affordance), wiring dialog residui, eventuale coroutine di flush prima della navigazione, eventuale semplificazione overflow. |
| `app/src/main/java/.../ui/screens/GeneratedScreenDialogs.kt` | Rimuovere o deprecare uso standard di `GeneratedScreenExitFromHistoryDialog`; valutare `GeneratedScreenExitToHomeDialog` vs overflow “Vai alla home”. |
| `app/src/main/java/.../ui/navigation/NavGraph.kt` | Eventuale affinamento lambda passate a `GeneratedScreen` se serve distinguere esplicitamente “pop vs home” (potrebbe restare invariato se la logica vive tutta in `GeneratedScreen` usando `onNavigateToHome` + `onBackToStart`). |
| `app/src/main/java/.../ui/navigation/Screen.kt` | Solo se si opta per **argomento route esplicito** (`fromPreGenerate` / `exitTarget`) invece dell’euristica `isNew` + `isManualEntry` (non obbligatorio). |
| `app/src/main/res/values*/strings.xml` | Solo se si rimuovono voci menu / dialog e restano stringhe orfane (cleanup mirato). |

---

## Note tecniche (strategia consigliata — minimo cambiamento)

1. **Handler unico `performGeneratedExit()`** (o rinominare `handleBackPress`) che:
   - se **bozza manuale vuota** → mostra **solo** `GeneratedScreenDiscardDraftDialog` (comportamento attuale);
   - se è già in corso una chiusura (`isExiting == true`) → ignorare nuovi trigger da Fine/back gesture/back button;
   - altrimenti avvia **`scope.launch`** che:
     - imposta un piccolo stato UI locale di uscita in corso (per disabilitare tap multipli su Fine/back durante il flush finale);
     - chiama **`excelViewModel.saveCurrentStateToHistory(entryUid)`** prima di navigare (parità col percorso “Salva ed esci” del dialog cronologia; mitiga race rispetto a `updateHistoryEntry` lanciato con `viewModelScope.launch` senza attesa);
     - poi in base al contesto:
       - **`!isNewEntry`** → `onBackToStart()` (torna a History);
       - **`isNewEntry && !isManualEntry`** (PreGenerate) → `onNavigateToHome()`;
       - **`isNewEntry && isManualEntry`** → `onBackToStart()` (FilePicker).
   - In caso di errore nel flush finale: non uscire, mostrare feedback non bloccante (snackbar) e lasciare l’utente nella schermata, senza aprire loading dialog full-screen o nuovi dialog distruttivi.

2. **Rimuovere** `navigationIcon` dalla `CenterAlignedTopAppBar` / top bar usata dalla schermata per eliminare il back visibile; **`BackHandler`** continua a chiamare lo stesso handler di “Fine”. Valutare anche il comportamento con gesture back Android come parte delle verifiche manuali.

3. **Rimuovere** dal flusso standard `showExitFromHistoryDialog` e relativi trigger; eliminare il composable/dialog se non più usato, evitando codice morto e stato UI non necessario.

4. **Menu overflow “Vai alla home”**: dopo il punto (1), per il flusso PreGenerate **Fine** già porta a Home, quindi la voce rischia di essere ridondante. Default consigliato in planning: **rimuoverla** oppure mostrarla solo dove ha una semantica davvero diversa da Fine. Evitare duplicazioni non necessarie in top bar.
   In particolare, se l’entry proviene da Cronologia, evitare una voce overflow che mandi a Home come scorciatoia predefinita se non esiste una chiara utilità distinta per l’utente.

5. **Non introdurre** nuovi argomenti `NavType` se l’euristica `isNew`/`isManualEntry` è sufficiente; aggiungere argomento solo se in review emerge ambiguità futura (deep link, nuovi ingressi, flussi esterni).

6. Valutare se rendere “Fine” temporaneamente disabilitato o sostituibile con un piccolo progress/alpha durante il flush finale, ma senza appesantire la UI: priorità a percezione di rapidità e prevenzione dei doppio tap. Le altre azioni della top bar non devono restare attive in modo incoerente durante una chiusura già avviata.
7. Se viene rimossa la voce overflow “Vai alla home”, verificare il riordino finale delle voci residue (`export`, `share`, `rename`) per mantenere una sequenza più pulita e coerente con l’importanza reale delle azioni.

---

## Rischi di regressione

| Rischio | Mitigazione |
|---------|-------------|
| Doppio tap su Fine / back gesture ripetuto durante il flush finale | Stato locale “uscita in corso”, disabilitazione temporanea dell’azione e navigazione singola. |
| Perdita ultimi edit se uscita rapida senza attendere `updateHistoryEntry` asincrono | Flush con `saveCurrentStateToHistory` (suspend) nel `scope.launch` prima della navigazione; in caso di errore, restare in schermata con feedback non bloccante. |
| Doppio salvataggio o race con altre coroutine VM | Verificare in execution che `saveCurrentStateToHistory` sia idempotente e sicuro (stesso body di aggiornamento già usato). |
| Stack Nav errato dopo `onNavigateToHome` vs `popBackStack` | Smoke manuale: PreGenerate → Generated → Fine → si atterra su **FilePicker** con bottom bar corretta; History → Generated → Fine → **History**. |
| Rimozione “Esci senza salvare” / revert (`revertDatabaseToOriginalState`) | Accettazione prodotto: niente revert esplicito su uscita standard; documentare se resta accessibile altrove (non in scope se assente). |
| Percezione di uscita troppo “rigida” senza back visibile | Verificare che `Fine` sia sempre ben evidente, che il back di sistema/gesture resti coerente e che il titolo/top bar non risultino sbilanciati. |
| Azioni overflow ancora attivabili durante una chiusura già partita | Durante `isExiting`, disabilitare o ignorare trigger incoerenti anche da overflow/top bar. |
| `Fine` troppo poco evidente dopo la rimozione del back o troppo aggressivo se ridisegnato | Verificare equilibrio visivo reale in top bar: `Fine` ben leggibile come azione primaria ma coerente con il tono sobrio dell’app. |
| Stringhe / talkback | Aggiornare `contentDescription` se sparisce il back; verificare che “Fine” resti primary chiara. |

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Top bar **senza** icona back visibile; **”Fine”** resta l’unica azione primaria di chiusura visibile. | M | ✅ |
| 2 | La top bar resta visivamente equilibrata dopo la rimozione del back: titolo leggibile, `Fine` evidente, overflow non dominante. | M | ✅ (staticam.) |
| 3 | `Fine` resta chiaramente riconoscibile come azione primaria di chiusura senza ricorrere a icone o styling incoerenti con la top bar attuale. | M | ✅ |
| 4 | **Back di sistema** e tap **”Fine”** eseguono lo **stesso** percorso di uscita (nessuna divergenza). | M | ✅ |
| 5 | Da **PreGenerate** → Generated: **Fine/back** portano a **Inventario/Home** (`FilePicker` / tab root come oggi `onNavigateToHome`), non alla sola PreGenerate nello stack. | M | ✅ |
| 6 | Da **Cronologia** → Generated: **Fine/back** tornano a **Cronologia** senza dialog di conferma standard. | M | ✅ |
| 7 | Da **manual entry nuova** aperta da FilePicker: **Fine/back** tornano coerentemente al contesto Inventario/FilePicker senza dialog standard su entry non vuota. | M | ✅ |
| 8 | Su entry non vuote non compaiono dialog di conferma uscita standard; l’unica eccezione ammessa è la bozza manuale vuota, se confermata in execution. | M | ✅ |
| 9 | Bozza **manuale vuota**: resta conferma di **discard** (o equivalente documentato) se ancora necessaria; nessun “buco” che lasci entry fantasma senza intenzione. | M | ✅ |
| 10 | Nessuna regressione evidente su: modifiche griglia persistite in History, manual entry con righe, export da Generated, sync/analisi import legate alla entry. | M/B | ✅ (staticam.) |
| 11 | Le azioni overflow residue restano disponibili e coerenti, senza duplicare la semantica di `Fine`. | M | ✅ |
| 12 | Durante una chiusura già avviata non si verificano doppie navigazioni né trigger incoerenti da top bar/overflow. | M | — |
| 13 | `assembleDebug` OK; `lint` senza nuovi warning rilevanti. | B/S | — |

Legenda: B=Build, S=Static, M=Manuale, E=Emulator

### Definition of Done — task UX/UI (`MASTER-PLAN.md`)

- [x] Primary action di chiusura chiara (solo `Fine` visibile come affordance primaria di exit).
- [x] Top bar più pulita e visivamente equilibrata dopo la rimozione del back.
- [x] `Fine` resta una primary action chiara ma visivamente sobria e coerente con la top bar.
- [x] Nessuna regressione funzionale intenzionale su persistenza/cronologia/export/sync/manual entry.
- [x] Nessuna affordance ridondante in top bar per l’uscita standard.
- [x] Coerenza con Material3 / pattern TASK-047.
- [x] Nessuna regressione o incoerenza nelle azioni secondarie residue della top bar/overflow.
- [x] Build/lint OK.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Navigazione PreGenerate → exit verso **Home** via `onNavigateToHome` / `navigateToRootTab(FilePicker)` invece di semplice `popBackStack()`. | Richiesta UX esplicita utente; il flusso va chiuso tornando al root context Inventario. | 2026-04-12 |
| 2 | Euristica **`isNewEntry && !isManualEntry`** = ingresso PreGenerate, senza nuovi argomenti route. | Minimo cambiamento, sufficiente con i call site attuali, evita complessità inutile. | 2026-04-12 |
| 3 | L’uscita standard da GeneratedScreen non offre più un percorso “Esci senza salvare” per entry non vuote. | Coerenza con il salvataggio persistente già presente e con l’obiettivo di eliminare dialog ridondanti. | 2026-04-12 |
| 4 | Il menu overflow non deve duplicare la semantica di `Fine`; eventuali azioni ridondanti vanno rimosse o condizionate. | Pulizia UX/UI della top bar e gerarchia visiva più chiara. | 2026-04-12 |
| 5 | Export/share/rename restano azioni secondarie disponibili; il cleanup riguarda solo le affordance di uscita ridondanti. | Evitare regressioni funzionali e preservare la maturità operativa della GeneratedScreen. | 2026-04-12 |
| 6 | `Fine` resta preferibilmente una `TextButton` testuale chiara, non sostituita da icone o CTA più aggressive salvo necessità emersa in execution. | Preservare chiarezza semantica e coerenza con il tono UX della schermata. | 2026-04-12 |

---

## Planning (Claude)

### Analisi

Comportamento attuale ricostruito da `GeneratedScreen.kt`, `GeneratedScreenDialogs.kt`, `NavGraph.kt`, `Screen.kt`, `ExcelViewModel.kt` (`updateHistoryEntry` / `saveCurrentStateToHistory`). La ridondanza principale per l’utente è **dialog cronologia** + **back visibile** accanto a **Fine**; il percorso PreGenerate oggi **non** torna alla home ma a PreGenerate su `popBackStack()`.

### Piano di esecuzione (per esecutore, post-approvazione)

1. Implementare uscita unificata con flush `saveCurrentStateToHistory`, protezione da doppio tap e ramificazione navigazione per contesto d’ingresso.
2. Rimuovere navigation icon; ripulire stato/dialog di uscita non più necessari.
3. Ripulire l’overflow eliminando o condizionando azioni ridondanti rispetto a `Fine`, verificare l’equilibrio visivo finale della top bar e preservare coerenza/ordine delle azioni secondarie residue (`export`, `share`, `rename`).
4. Verifiche manuali sui tre ingressi, inclusa gesture back Android, bozza manuale vuota, stato di chiusura già avviato, assenza di loading UX pesante in uscita e leggibilità reale di `Fine` nella top bar finale.
5. Build/lint; se si tocca logica VM oltre il minimo, valutare test mirati, altrimenti documentare N/A.

### Rischi identificati

- Principale: garantire uscita percepita come immediata senza perdere gli ultimi edit e senza riaprire complessità tramite nuovi dialog o affordance duplicate.
- Execution approvata: procedere con priorità al minimo cambiamento e alle verifiche manuali sui tre ingressi prima di eventuali rifiniture ulteriori.

---

## Execution

### Esecuzione — 2026-04-12

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — handler unico `performGeneratedExit()`, flush finale `saveCurrentStateToHistory`, guardia `isExiting`, top bar senza back visibile, overflow ripulito dalla voce ridondante verso Home.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreenDialogs.kt` — rimossi i dialog standard di uscita non più usati (`GeneratedScreenExitFromHistoryDialog`, `GeneratedScreenExitToHomeDialog`); mantenuto solo `GeneratedScreenDiscardDraftDialog`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — `onNavigateToHome` reso esplicito verso `Screen.FilePicker.route` con `popUpTo(FilePicker) { inclusive = false } + launchSingleTop = true` invece del helper generico `navigateToRootTab`.
- `app/src/main/res/values/strings.xml` — cleanup stringhe orfane dei dialog rimossi + nuova stringa `generated_exit_save_failed`.
- `app/src/main/res/values-en/strings.xml` — idem.
- `app/src/main/res/values-es/strings.xml` — idem.
- `app/src/main/res/values-zh/strings.xml` — idem.

**Azioni eseguite:**
1. Sostituito il vecchio `handleBackPress` con `performGeneratedExit()`: stesso percorso per `Fine` e `BackHandler`, flush `saveCurrentStateToHistory` prima della navigazione, ramificazione per contesto (`!isNewEntry` → History, `isManualEntry` → FilePicker, altrimenti → Home).
2. Mantenuta come unica eccezione la bozza manuale vuota: dialog di discard intatto; per tutte le entry non vuote uscita standard senza dialog.
3. Rimossa l’icona back dalla top bar (`CenterAlignedTopAppBar` senza `navigationIcon`); `Fine` resta come `TextButton` testuale nell’area `actions`.
4. Aggiunta guardia locale `isExiting` per ignorare doppi trigger; `isActionEnabled = !isExiting && !isSavingOrReverting` disabilita top bar/overflow durante flush.
5. In caso di errore sul flush finale: schermata resta aperta, snackbar non bloccante; nessun dialog pesante.
6. Rimossa voce overflow "Vai alla home" (duplicava semantica di `Fine`); preservate `sync`, `export`, `share`, `rename`, `column mapping`.

**Fix successivo applicato in NavGraph.kt:**
- Isolato il problema: il ramo PreGenerate passava da `navigateToRootTab(FilePicker)` che non rimuoveva correttamente lo stack intermedio (PreGenerate → Generated).
- Fix: `onNavigateToHome` ora chiama `navigate(FilePicker) { popUpTo(FilePicker) { inclusive = false }; launchSingleTop = true }` direttamente, pulendo lo stack fino a FilePicker senza duplicarlo.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle (`assembleDebug`) | ✅ | Verde |
| Lint | ✅ | Verde — nessun warning nuovo |
| Coerenza con planning | ✅ | Handler unico, navigazione contestuale, rimozione back, cleanup overflow/dialog secondo planning |
| Baseline regressione TASK-004 | N/A | Modifica confinata a screen/dialog/nav/resources; nessun tocco a ViewModel/repository/DAO |

---

## Review

### Review — 2026-04-12

**Revisore:** Claude (planner)

**Criteri di accettazione:**
| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Top bar senza back visibile; `Fine` unica affordance primaria | ✅ | `CenterAlignedTopAppBar` senza `navigationIcon`; `Fine` in `actions` |
| 2 | Top bar visivamente equilibrata | ✅ | Layout title + pill (overflow + Fine) invariato da TASK-047; solo back rimosso |
| 3 | `Fine` come `TextButton` testuale sobrio | ✅ | `TextButton` con `labelLarge` SemiBold, nessuna icona aggressiva |
| 4 | BackHandler e Fine stesso handler | ✅ | Entrambi chiamano `performGeneratedExit()` |
| 5 | PreGenerate → Generated → Fine → Home | ✅ | `isNewEntry && !isManualEntry` → `onNavigateToHome()` → FilePicker con stack pulito |
| 6 | History → Generated → Fine → History (senza dialog) | ✅ | `!isNewEntry` → `onBackToStart()` = `popBackStack()` |
| 7 | Manual → Generated → Fine → FilePicker (senza dialog) | ✅ | `isManualEntry && isNewEntry` → `onBackToStart()` |
| 8 | Nessun dialog su entry non vuote | ✅ | `showExitFromHistoryDialog` / `showExitToHomeDialog` rimossi completamente |
| 9 | Bozza manuale vuota: discard dialog presente | ✅ | `isManualDraftEmpty` → `showExitDialog = true` → `GeneratedScreenDiscardDraftDialog` |
| 10 | No regressioni evidenti (build/lint OK, no tocchi DAO/repo/VM) | ✅ | `assembleDebug` + `lint` verdi; perimetro confinato |
| 11 | Overflow senza semantica Fine duplicata | ✅ | Rimossa voce "Vai alla home"; restano sync/export/share/rename/column mapping |
| 12 | `isExiting` blocca doppi trigger | ✅ | Guardia `if (isSavingOrReverting || isExiting) return@exit` + menu chiuso su `isActionEnabled = false` |
| 13 | `assembleDebug` + `lint` OK | ✅ | Verificati dopo ogni modifica |

**Problemi trovati:**
- Commento stale `// Legacy escape hatch + navigazione` prima della voce `column_mapping_overflow` nell’overflow — la voce "navigazione" (Vai alla home) era stata rimossa ma il commento era rimasto. **Rimosso in review.**

**Micro-fix applicato in review:**
- `GeneratedScreen.kt`: rimosso commento stale `// Legacy escape hatch + navigazione` prima di `column_mapping_overflow` nel dropdown menu. Build confermata verde post-fix.

**Verdetto:** APPROVED

**Note per fix:** nessun fix strutturale necessario; micro-cleanup commento già applicato.

---

## Chiusura / Handoff

**Chiusura:** 2026-04-12 — DONE.

**Rischi residui:**
- Smoke manuali di navigazione (gesture back, tre percorsi, doppio tap su Fine) non eseguibili in ambiente CLI. Verifiche manuali consigliate al prossimo test in device/emulator.
- Race condition teorica tra `saveCurrentStateToHistory` e `updateHistoryEntry` asincrono — mitigata dal flush esplicito; non in scope baseline TASK-004 per assenza di tocco al ViewModel.

**Handoff:**
- Verificare manualmente i tre percorsi: `PreGenerate → Generated → Fine/back`, `History → Generated → Fine/back`, `FilePicker manual entry → Generated → Fine/back`.
- Verificare la bozza manuale vuota: conferma discard presente, nessuna entry fantasma in cronologia.
- Verificare gesture back Android e doppio tap su `Fine` in device/emulator.
