# TASK-036 — HistoryScreen: colori tematizzati + padding uniforme

---

## Informazioni generali

| Campo                | Valore                                      |
|----------------------|---------------------------------------------|
| ID                   | TASK-036                                    |
| Stato                | **DONE** (review repo-grounded APPROVED 2026-04-05; micro-fix locale applicato e verificato) |
| Priorità             | BASSA                                       |
| Area                 | UX / UI / HistoryScreen                     |
| Creato               | 2026-04-05                                  |
| Ultimo aggiornamento | 2026-04-05 — review repo-grounded APPROVED, micro-fix locale su spacing summary, `assembleDebug` / `lint` verdi, task chiusa in `DONE` |

---

## Dipendenze

- **TASK-030** (`DONE`) — design system (`appSpacing`, `appColors`, tema Material3): questo task si appoggia ai token già disponibili; **non** estende il design system salvo evidenza nuova e decisione esplicita fuori perimetro.

---

## Scopo

1. Rimuovere o giustificare **valori visivi “magici”** in `HistoryScreen.kt` (padding / spacing / elevation dove incoerenti con il resto dello screen e con **TASK-030**), migliorando la **uniformità percepita** delle card lista.
2. Verificare e, se necessario, correggere la **compliance al dark theme** (contrasti superficie/contenuto, stati swipe, badge icone) usando **MaterialTheme.colorScheme** e **MaterialTheme.appColors** già in uso.
3. Mantenere l’intervento **strettamente locale** a `HistoryScreen.kt` e ai suoi composable privati, senza redesign della schermata.

---

## Contesto

**Evidenza codice — file unico:** tutta l’UI History è in `HistoryScreen.kt` (~593 righe): `HistoryScreen`, `HistoryRow`, `HistoryEmptyState`, `StatusIcon`, enum `BadgeType`. Il wiring da `NavGraph.kt` resta invariato (fuori perimetro).

**Evidenza codice — colori:** non compaiono literal RGB/`Color(0x…)` nel file. Sono già usati `MaterialTheme.colorScheme` (es. `onSurfaceVariant`, `primary`, `secondary`, `error`, `inverseSurface`, `onError`) e `MaterialTheme.appColors` per `success` / `warning` nei badge. Resta **`Color.Transparent`** nello stato `Settled` dello swipe-dismiss (neutro, tipico pattern Compose). Il “debito” non è tanto colore RGB hardcoded quanto **coerenza tema** (card default vs. elevazione, eventuali contrasti in dark) e **magic number** su dimensioni.

**Evidenza codice — padding / spacing misti:**
- `LazyColumn`: `padding(horizontal = spacing.sm)`.
- `HistoryRow` → `Card`: `padding(vertical = spacing.xxs)` tra le righe.
- Contenuto card — `Column`: `padding(top = spacing.md, bottom = 32.dp, start = spacing.lg, end = 56.dp)` → **32.dp** e **56.dp** non sono token `AppSpacing` (vedi `Theme.kt`: `xxs`…`xxl`).
- Blocco dettagli: `Arrangement.spacedBy(2.dp)` — gap più stretto dei token (`xxs` = 4.dp).
- Icone: `16.dp`, `20.dp`, `12.dp` fissi (dimensioni componente, accettabili se documentate o allineate a pattern esistenti altrove).
- `CardDefaults.cardElevation(defaultElevation = 2.dp)` — stesso valore già usato in `DatabaseScreenComponents.kt` (coerenza parziale con altre card); `OptionsScreen` usa 3.dp (divergenza minima intra-app).

**Precedenza UX:** **TASK-016** ha già rifinito timestamp, gerarchia testi e spacing verticale generale; **TASK-010** ha coperto filtri/performance. Questo task è **polish residuo** su tokenizzazione padding e verifica tema scuro, non duplicazione di quel lavoro.

---

## Non incluso

- Modifiche a `ExcelViewModel`, repository, DAO, modelli dati, `NavGraph.kt`, `Screen.kt`, logica filtri/data picker, swipe actions (comportamento conferma rename/delete).
- Nuove dipendenze Gradle o librerie.
- Estrazione di `HistoryScreen` in nuovi file / refactor architetturale.
- **TASK-037** (dialog unificati): i `AlertDialog` / `DatePickerDialog` in History restano fuori perimetro salvo micro-aggiustamento **locale** strettamente necessario per coerenza padding interno già toccato nello stesso composable (da evitare se possibile).
- Introduzione di nuovi token globali in `Theme.kt` / estensione `AppSpacing` — **vietata** salvo emergenza documentata e nuovo task; preferire **composizione** dei token esistenti o costanti `private` nominate nello stesso file.
- Test UI strumentati (Espresso / Compose test) — non richiesti.
- Baseline **TASK-004**: **non applicabile** se il diff resta confinato a UI `HistoryScreen.kt` senza toccare ViewModel/repository/import/history logic.

---

## File potenzialmente coinvolti

| File | Ruolo |
|------|--------|
| `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` | **Unico file atteso:** `HistoryRow` (card, padding, eventuale `CardDefaults.cardColors` / elevation), `HistoryEmptyState`, `StatusIcon`, `HistoryScreen` (scaffold/list/dialog interni solo se necessario per coerenza padding). |

**Fuori scope salvo evidenza in execution:** `NavGraph.kt`, `ExcelViewModel.kt`, risorse stringhe (nessun cambio copy previsto), `Theme.kt`.

---

## Perimetro incluso / escluso (sintesi)

| Incluso | Escluso |
|---------|---------|
| Sostituzione o riduzione di **magic number** `*.dp` su padding/spacing della card e sotto-blocchi, usando **`MaterialTheme.appSpacing`** e/o **costanti private** con nome semantico (es. inset per area icone) | Business logic, navigazione, persistenza storico |
| Verifica visiva / correzione minima **light + dark** per card, swipe background, badge | Redesign layout lista, nuove azioni, animazioni |
| Allineamento **opzionale** dell’elevazione card a un pattern già presente nell’app (es. 2.dp come Database) **solo** se migliora coerenza senza scope creep | Modifica globale di tutte le card dell’app |
| `Color.Transparent` per swipe settled: **lasciare** salvo review che dimostri alternativa tematizzata necessaria | Nuovi colori brand fuori da `colorScheme` / `appColors` |

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Nella lista storico, **nessun padding/margin della card contenuto** usa coppie opache `32.dp` / `56.dp` / `2.dp` senza sostituzione tramite **`appSpacing` composito** o **`private val`** documentato nel file (obiettivo: leggibilità del diff e uniformità con design system). | S (code review) + M | — |
| 2 | **Light e dark theme:** le card storico, lo stato empty, gli indicatori sync/export (badge) e gli sfondi swipe (rename/delete) risultano leggibili, senza testo/icona che scompaiono per contrasto insufficiente rispetto al baseline pre-task. | M | — |
| 3 | **Nessun** nuovo colore RGB hardcoded (`Color(0x…)`, hex in codice); restano ammessi `Color.Transparent` dove semanticamente corretto e i riferimenti a `colorScheme` / `appColors`. | S | — |
| 4 | Comportamento invariato: tap riga, filtri, date range, rename/delete dialog, swipe-to-reveal azioni, snackbar messaggi, navigazione a `Generated` — **nessuna regressione funzionale**. | M | — |
| 5 | `./gradlew assembleDebug` — **BUILD SUCCESSFUL**, 0 errori. | B | — |
| 6 | `./gradlew lint` — nessun warning **nuovo** attribuibile al diff. | S | — |
| 7 | Nessun warning Kotlin **nuovo** nel codice toccato (nota: warning Compose preesistenti su `rememberSwipeToDismissBoxState` restano fuori scope salvo fix banale e locale). | S | — |

**Checklist Definition of Done — task UX/UI** (`docs/MASTER-PLAN.md`): applicare dove pertinente (spacing card, coerenza tema, nessuna regressione funzionale).

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | **Perimetro file = solo `HistoryScreen.kt`** | Allinea a richiesta utente e a **AGENTS.md** (task UX locale); riduce rischio di scope creep. | 2026-04-05 |
| 2 | **Magic padding:** preferire `spacing.*` combinati (es. `lg * 2` per 32.dp se equivalente) o `private val HistoryCardBottomInset = …` con commento che lega l’inset all’area icone in basso a destra | Evita ambiguità in review e mantiene assenza di nuovi token globali. | 2026-04-05 |
| 3 | **`spacedBy(2.dp)`:** in execution valutare se sostituire con `spacing.xxs` (4.dp) accettando micro-variazione visiva **oppure** nominare costante `private` se 2.dp è intenzionale per compattezza | Il planning non impone il valore finale: deve essere scelta consapevole e tracciata nel log Execution. | 2026-04-05 |
| 4 | **Card elevation:** default 2.dp è già allineato a `DatabaseScreenComponents`; non alzare a 3.dp **solo** per uniformare a Options se non c’è beneficio per History | Evita redesign implicito; eventuale tweak solo se documentato come miglioramento locale coerente. | 2026-04-05 |
| 5 | **Dark theme:** intervenire solo se la verifica manuale mostra problema reale (es. card/surface); evitare cambi preemptive a `CardDefaults.cardColors` senza evidenza | Minimo cambiamento necessario. | 2026-04-05 |

---

## Planning (Claude)

### Analisi (repo-grounded)

- **Problema reale:** non è una lista di colori hex residuali, ma **incoerenza tra token `appSpacing` e valori `dp` fissi** nella card (`32`, `56`, `2`), che complica manutenzione e allineamento al design system introdotto con **TASK-030**.
- **Dark theme:** il codice usa già primitive M3 appropriate per molti stati; il rischio principale è **contrasto percepito** su card/icone in dark, da validare empiricamente piuttosto che assumere difetto.
- **Dipendenza TASK-030:** soddisfatta (`DONE`); nessun blocco.

### Piano di esecuzione (minimo)

1. Aprire `HistoryScreen.kt` e mappare tutti i `*.dp` non derivati da `MaterialTheme.appSpacing`.
2. Sostituire i padding della `Column` interna a `HistoryRow` con combinazioni di `spacing` e/o costanti `private` nominate (obiettivo criterio #1).
3. Decidere e applicare la scelta su `spacedBy(2.dp)` (vedi Decisione #3), documentando nel log Execution.
4. Eseguire controllo visivo **light + dark** su lista popolata, lista filtrata vuota, stato completamente vuoto, swipe rename/delete.
5. `assembleDebug` + `lint`; aggiornare sezione **Execution** nel file task (non ora — fase post-approvazione).

### Rischi identificati

| Rischio | Mitigazione |
|---------|-------------|
| Cambio micro-spacing altera il layout (icone sovrapposte al testo) | Preservare inset funzionale all’area icone; verificare su narrow width |
| “Uniformare” troppo → perdita gerarchia TASK-016 | Non modificare tipografia né logica timestamp/metadati |
| Escalation a refactor tema globale | Rifiutare: restare in `HistoryScreen.kt` |

### Checklist verifiche post-execution (per esecutore)

- [ ] `./gradlew assembleDebug`
- [ ] `./gradlew lint`
- [ ] Manuale: History in **light** — lista, empty, filtro attivo, swipe
- [ ] Manuale: History in **dark** — stessi casi
- [ ] Conferma assenza regressioni tap/navigazione/dialog
- [ ] Aggiornare tabella criteri di accettazione nel file task con esito

---

## Execution

### Esecuzione — 2026-04-05

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — sostituiti `32.dp` / `56.dp` con inset semantici derivati da `appSpacing`; la summary densa usa ora uno spacing compatto esplicito e nominato (`private val`) da `2.dp`, preservando la resa visiva senza lasciare un valore opaco inline.
- `docs/TASKS/TASK-036-historyscreen-colori-tematizzati-padding-uniforme.md` — aggiornato log di execution, evidenze verifiche e handoff per review.

**Azioni eseguite:**
1. Verificato che il debito reale fosse confinato a `HistoryRow` in `HistoryScreen.kt`: `32.dp`, `56.dp` e `2.dp`; nessun colore hardcoded da correggere e nessuna evidenza repo-grounded per toccare elevation o `CardDefaults.cardColors`.
2. Introdotti in `HistoryRow` due inset semantici derivati da `MaterialTheme.appSpacing` (`statusIconsBottomInset`, `statusIconsEndInset`) per rendere leggibile l’intento del layout mantenendo gli stessi ingombri funzionali dell’area badge in basso a destra.
3. UI/UX locale intenzionale: mantenuta la summary compatta a `2.dp` invece di uniformare forzatamente a `spacing.xxs`, così le righe dense restano rapide da scansionare su larghezze compatte; in review la scelta è stata resa esplicita con un `private val` semantico.
4. Lasciati invariati `CardDefaults.cardElevation(defaultElevation = 2.dp)`, `Color.Transparent` nello stato `Settled` dello swipe e i colori `colorScheme` / `appColors`, perché la verifica manuale light/dark non ha mostrato problemi reali di contrasto o affordance.
5. Installato il nuovo `app-debug.apk` sull’emulator con `adb install -r` prima delle verifiche manuali, così tutti i controlli visivi/funzionali sono stati eseguiti sul binario aggiornato.
6. Verifiche manuali su emulator `emulator-5554` (`Medium_Phone_API_35`, compact width phone): lista popolata light, swipe rename/delete light, empty filtrato light, opzione tema scuro, empty filtrato dark, lista popolata dark, swipe delete dark, tap riga verso `Generated`, snackbar di rename (`File renamed.`) e assenza di overlap/clipping osservabile su nomi lunghi, righe dense e badge.
7. Per coprire anche l’empty totale senza perdere il dataset esistente, eseguito backup temporaneo di `databases/` e `shared_prefs/` del debug build, `pm clear` dell’app, verifica dello stato empty totale, quindi ripristino completo dei dati/preferenze e smoke finale su History dark popolata.

**Check obbligatori:**

| Check                    | Tipo | Stato | Evidenza |
|--------------------------|------|-------|----------|
| Build Gradle             | B    | ✅    | `assembleDebug` → `BUILD SUCCESSFUL in 10s` usando il JBR bundled di Android Studio (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) |
| Lint                     | S    | ✅    | `lint` → `BUILD SUCCESSFUL in 15s`, nessun warning lint nuovo attribuibile al diff |
| Warning Kotlin           | S    | ✅    | Nessun warning Kotlin nuovo nel codice toccato; resta solo il warning Compose preesistente su `rememberSwipeToDismissBoxState`, già noto e fuori scope |
| Coerenza con planning    | —    | ✅    | Perimetro rispettato: diff confinato a `HistoryScreen.kt`, nessun intervento su navigation / ViewModel / repository / risorse |
| Criteri di accettazione  | —    | ✅    | 7/7 verificati (vedi tabella dettagliata sotto) |

**Baseline regressione TASK-004 (se applicabile):**
- Non applicabile: diff confinato a UI `HistoryScreen.kt`, senza modifiche a ViewModel / repository / import-export / history logic.

**Esito criteri di accettazione:**

| # | Criterio | Verifica | Stato | Evidenza |
|---|----------|----------|-------|----------|
| 1 | Nessun padding/margin opaco `32.dp` / `56.dp` / `2.dp` resta nel contenuto card senza sostituzione semantica | S + M | ✅ ESEGUITO | `HistoryRow` usa ora `statusIconsBottomInset`, `statusIconsEndInset` e `historyCompactSummarySpacing`; i primi due sono composti da `appSpacing`, il terzo è un `private val` semantico da `2.dp` |
| 2 | Light e dark theme leggibili per card, empty state, badge e swipe background | M | ✅ ESEGUITO | Verificati su emulator `emulator-5554`: lista popolata light/dark, empty filtrato light/dark, empty totale, badge sync/export, dialog rename/delete via swipe; nessuna perdita di contrasto osservata |
| 3 | Nessun nuovo colore RGB hardcoded | S | ✅ ESEGUITO | Il diff non introduce `Color(0x…)` né hex; lasciati invariati `colorScheme`, `appColors` e `Color.Transparent` nello swipe settled |
| 4 | Comportamento invariato: tap riga, filtri, date range, rename/delete dialog, swipe, snackbar, navigazione a `Generated` | M | ✅ ESEGUITO | Verificati su binario aggiornato: tap riga verso `Generated`, filtro custom range con empty filtrato, swipe rename/delete con dialog, snackbar di rename confermato, nessuna regressione osservata nei flussi toccati |
| 5 | `./gradlew assembleDebug` verde | B | ✅ ESEGUITO | `BUILD SUCCESSFUL in 10s` |
| 6 | `./gradlew lint` senza warning nuovi da diff | S | ✅ ESEGUITO | `BUILD SUCCESSFUL in 15s`; nessun warning lint nuovo attribuibile alle modifiche |
| 7 | Nessun warning Kotlin nuovo nel codice toccato | S | ✅ ESEGUITO | Solo warning preesistente di deprecazione Compose su `rememberSwipeToDismissBoxState`; nessun warning aggiuntivo introdotto dal diff |

**Incertezze:**
- Nessuna sul diff applicato.
- Nota operativa ambiente: i check Gradle richiedono il JBR bundled di Android Studio perché il `java` globale non è configurato in shell.

**Handoff notes:**
- La verifica manuale ha incluso anche `empty totale` con backup/restore reversibile del sandbox dell’app sull’emulator; dataset e preferenze sono stati ripristinati prima di chiudere l’execution.
- Nessun cambio cromatico o di elevation è stato introdotto: la review dovrebbe concentrarsi sul fatto che il beneficio del task è la leggibilità/manutenibilità del layout, non un redesign visivo.
- Warning Compose preesistente su `rememberSwipeToDismissBoxState` resta fuori scope, coerentemente con planning e criterio #7.

---

## Review

*(Vuoto in fase PLANNING.)*

---

## Fix

### Fix — 2026-04-05

**Correzioni applicate dopo review repo-grounded:**
1. Sostituito `spacing.xxs / 2` con `private val historyCompactSummarySpacing = 2.dp` in `HistoryScreen.kt`, perché la scelta di compattezza era corretta sul piano UX ma risultava troppo implicita e dipendente da futuri cambi del token `xxs`.

**Verifiche rieseguite:**
- `assembleDebug` → `BUILD SUCCESSFUL in 3s`
- `lint` → `BUILD SUCCESSFUL in 29s`
- Nessun warning Kotlin nuovo; resta solo il warning Compose preesistente su `rememberSwipeToDismissBoxState`
- Nessuna nuova verifica manuale necessaria: la resa visiva resta invariata perché il valore finale rimane `2.dp`

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | `DONE` |
| Data chiusura          | 2026-04-05 |
| Tutti i criteri ✅?    | Sì — 7/7 verificati |
| Rischi residui         | Nessuno rilevante nel perimetro del task; resta solo il warning Compose preesistente fuori scope su swipe-dismiss |

---

## Riepilogo finale

- Review repo-grounded conclusa con esito **APPROVED**.
- Diff finale confermato strettamente locale a `HistoryScreen.kt`; nessun cambio a business logic, navigation, ViewModel, repository, DAO, copy o tema globale.
- Miglioramento finale applicato in review: spacing compatto della summary reso esplicito con `private val historyCompactSummarySpacing = 2.dp`, più chiaro e stabile di `spacing.xxs / 2`.
- `assembleDebug` e `lint` verdi dopo il fix; verifiche manuali light/dark dell’execution restano valide perché la resa visiva è invariata.

---

## Handoff

Task chiusa: nessun handoff operativo residuo.
