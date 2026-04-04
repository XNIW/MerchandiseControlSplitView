# TASK-016 — UX polish History / ImportAnalysis / grid readability

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-016 |
| **Stato nel file task** (fase workflow) | **`DONE`** |
| **Stato in `MASTER-PLAN`** (backlog / governance) | **`DONE`** (2026-04-05) — **nessun** task attivo derivato da questo ID |
| Priorità backlog | **BASSA** |
| Area | UX / UI |
| Creato | 2026-04-03 |
| Ultimo aggiornamento | 2026-04-05 — chiusura formale **`DONE`**: review/conferma utente, walkthrough matrice manuale positivo, `MASTER-PLAN` sincronizzato |

**Nota storica (governance durante il ciclo):** il task era **`ACTIVE`** con priorità **BASSA** fino alla chiusura; **`ACTIVE`** indicava il focus di planning/execution, non l’urgenza nel backlog.

---

## Stato e disciplina (record)

| Fase | Significato (storico) |
|------|------------------------|
| **`PLANNING` → `EXECUTION`** | Approvazione utente + audit repo-grounded 2026-04-04; implementazione nel perimetro. |
| **`REVIEW` / `FIX`** | Review repo-grounded; fix UX `DisplayProductRow` 2026-04-04. |
| **`DONE`** | Conferma utente + matrice manuale percorsa con esito positivo (2026-04-05); build/lint già verdi in Execution. |

Il task è **chiuso**: questo file resta **traccia** di perimetro, criteri ed evidenze; non governa lavoro aperto. *UX di riferimento nel documento: § tie-break, cross-screen, Direzione, anti-overdesign, stringhe.*

---

## Dipendenze

- **TASK-001** (`DONE`)
- **TASK-010** (`DONE`) — per non duplicare lavoro già svolto su `HistoryScreen`; prima di eseguire, verificare nel codice cosa è già stato coperto e cosa resta debito UX reale.

---

## Scopo e perimetro

Migliorare **leggibilità**, **chiarezza percepita**, **gerarchia visiva**, **affordance** e **coerenza** di:

1. **`HistoryScreen`**
2. **`ImportAnalysisScreen`** (inclusi flussi di preview / confronti dove già presenti nello stesso file)
3. **Griglie dati condivise:** `ZoomableExcelGrid` e `TableCell`

Stack atteso: **Jetpack Compose + Material3**, interventi **locali** e **progressivi**, in linea con lo stile già presente nell’app.

**Perimetro esclusivo e tassativo:** i file sopra elencati (e le relative stringhe se necessario) sono l’**unico** perimetro di intervento. Qualsiasi modifica fuori da questi file — inclusi ViewModel, repository, DAO, NavGraph, altre schermate — è **fuori scope** salvo emergenza documentata con stato `BLOCKED`.

### Cosa è esplicitamente nel perimetro

- UI presentazionale: tipografia, spacing, ordine informativo, stati empty/loading/error **già presenti** in schermata, affordance celle/griglia.
- Ritocchi locali se **verificabili** (§ **Anti-overdesign**, tabella dimensioni); tra varianti valide → § **Direzione UX/UI** / **tie-break**.
- **Stringhe / L10n** solo se necessarie, set **completo** quattro lingue (§ **Guardrail stringhe**).
- **Wiring minimo** solo se inevitabile, **documentato** in Execution — niente business logic nei composable.

### Non-obiettivi (pratici e vincolanti)

| Area | Non fare in TASK-016 |
|------|----------------------|
| **Correttezza import / atomicità / transazionalità** | Fuori scope: **TASK-026** e affini; nessun fix “nascosto” sotto etichetta UX. |
| **Repository / DAO / Room / entità / contratti dati** | Nessuna modifica salvo emergenza assoluta fuori da questo task; se emergesse, **BLOCKED** o nuovo task, non scope creep. |
| **Refactor architetturale** | Nessuno spostamento di stato o logica nel ViewModel “per comodità UI”, nessuna decomposizione moduli non richiesta dal polish locale. |
| **Performance / algoritmi / motore griglia** | Nessuna ottimizzazione di complessità, paging, caching, redraw pesante, internals di zoom/gesture o refactor del motore griglia mascherati da polish; in griglia solo layout/tipografia/affordance **leggeri** (vedi Fase C). |
| **Navigation** | Nessuna nuova route, argomento `NavGraph`, destinazione o cambio flusso di navigazione. |
| **Redesign di schermata** | Nessun rifacimento ampio di layout o information architecture; solo affinamenti incrementali sulle aree elencate. |
| **Feature** | Non rimuovere funzionalità Android già presenti e funzionanti; non ridurre capacità per allineamento estetico. |

---

## Contesto (repo-grounded)

**Formula standard — supplier / category** (`ImportAnalysisScreen` e **compare row** nello stesso file). *Unica formulazione usata nel documento:*

> **nome leggibile → stato comprensibile → ID tecnico solo come fallback secondario**

Testo per «stato comprensibile» coerente all’app; nuove stringhe → L10n completa (§ stringhe). **Mai** ID come messaggio UX **primario**, salvo **eccezione documentata** in Execution + review (caso estremo). Altrove: **«Formula standard»** = questa riga.

- **`HistoryScreen.kt`:** `entry.timestamp` arriva da Room come stringa `”yyyy-MM-dd HH:mm:ss”` e viene mostrato as-is (riga ~413: `”${date_label}: ${entry.timestamp}”`). UX primaria = data/ora nel **locale** (parse della stringa con `DateTimeFormatter`, format con `FormatStyle.MEDIUM` + locale corrente). Fallback = mostrare la stringa grezza se il parse fallisce. `entry.id` è il filename-identificatore (es. `”2026-04-03_10-00-00_Supplier.xlsx”`) ed è correttamente mostrato come titolo — non è debito UX.
- **`ImportAnalysisScreen.kt`:** **pending** / **temp ID** / non persistiti — applicare **Formula standard**. **Gap confermato (audit 2026-04-04):** `CompareRow` (riga ~476-477) mostra `supplierId?.toString()` e `categoryId?.toString()` come valori grezzi. `DisplayProductRow` risolve già i nomi via `databaseViewModel.getSupplierDisplayName()` / `getCategoryDisplayName()`, ma `DisplayProductUpdateRow` non riceve `databaseViewModel` → `CompareRow` non può risolvere. **Wiring minimo necessario:** passare `databaseViewModel` a `DisplayProductUpdateRow` e risolvere i nomi in `CompareRow` con lo stesso pattern di `DisplayProductRow`. Questo è wiring presentazionale, non business logic.
- **`ZoomableExcelGrid.kt`** / **`TableCell.kt`**: solo **polish di presentazione** — leggibilità, densità percepita, spacing, gerarchia tipografica minima, contrasto, affordance di overflow/testo. `TableCell` ha già `maxLines = 2`, `TextOverflow.Ellipsis`, padding orizzontale `4.dp`. **Fuori scope esplicito:** performance, caching, pipeline di rendering, ottimizzazioni di recomposition “tecnico”, **internals** di gesture, riscrittura della **logica zoom/pan**, refactor strutturale del componente oltre quanto serve al polish locale (vedi anche Fase C).

**ViewModel / dati:** `ExcelViewModel` e `DatabaseViewModel` compaiono nel flusso **ImportAnalysis** (risoluzione supplier/category, error row, ecc.). In esecuzione restano **fonte di verità** — in linea di massima **sola lettura** e uso tramite API già esposte, salvo **wiring minimo** davvero inevitabile. Ogni tocco a logica ViewModel già coperta da **TASK-004** innesca la baseline test (sezione dedicata).

---

## Guardrail stringhe / localizzazione

- Preferire **riuso** di chiavi `string` esistenti in `values/` e varianti lingua quando il significato è equivalente o migliorabile senza nuova chiave.
- Se si aggiungono o si alterano in modo sostanziale stringhe visibili all’utente: aggiornare **nello stesso task** tutte le lingue del progetto: `values/`, `values-en/`, `values-es/`, `values-zh/`.
- Evitare testo hardcoded nei composable salvo costanti non visibili all’utente.
- Allineamento con **TASK-019** (`DONE`): nessuna regressione L10n; contenuti lunghi e lingue verbose devono essere considerati in **Contesto d’uso reale** e nella **matrice manuale**.

---

## Regola decisionale (tie-break)

Se emergono **più soluzioni UX valide** sullo stesso dettaglio (in planning rifinitivo o in futura **EXECUTION**), scegliere **in autonomia** la variante che, in ordine pratico:

1. **Riduce l’attrito cognitivo** (meno interpretazione, meno passaggi per capire stato e azioni).
2. **Migliora la leggibilità immediata** (scansione a colpo d’occhio, testi lunghi, celle dense).
3. **Mantiene la UI più pulita** (meno elementi concorrenti, meno decorazione).
4. **È più coerente con il resto dell’app** (prevale sulla “bellezza” isolata — vedi **Coerenza cross-screen**).
5. **Evita densità eccessiva**, **badge inutili**, **decorazioni superflue** e **stratificazioni visive** non necessarie.

Se una variante è soggettivamente più moderna o ricca ma **non** vince sui criteri sopra, **non** va scelta.

---

## Coerenza cross-screen (obbligatoria)

Ogni ritocco va valutato **non solo** contro Material3 in astratto, ma contro il **sistema visivo già presente nell’app**:

- tono (chiaro/scuro, `Surface` / `Card`, elevazione percepita, colore semantico);
- **spaziature** e ritmo verticale già usati in altre schermate;
- **gerarchia tipografica** (`title` / `body` / `label`, `onSurfaceVariant` per secondari);
- **enfasi relativa** tra primario, metadati, azioni secondarie — coerente con **DatabaseScreen**, **GeneratedScreen**, **History** (dove già rifinito), toolbar e dialog esistenti.

**Regola:** se una soluzione “stacca” dal linguaggio visivo del progetto, **non va scelta**, salvo convergenza documentata a livello app.

---

## Direzione UX/UI (futura EXECUTION)

Più scelte valide → **decisione autonoma** (§ **tie-break**, § **cross-screen**). **Bene** ritocchi **locali** che abbelliscano e migliorino uso/percezione, **se** sobrii e coerenti con lo stile dell’app — **tracciati** in Execution. **Divieti** di tono (rumore, overdesign, decorazioni gratuite, fuori stile): allineati a § **Anti-overdesign** e al pt. 5 del **tie-break**.

**Cross-task:** **TASK-010** (History); **TASK-015** (sobrietà, non 1:1); **TASK-019** L10n; **TASK-023** numerica CL; **TASK-026** confine import.

---

## Anti-overdesign / anti-rumore (guardrail operativi)

Niente abbellimenti/stratificazioni/animazioni **senza** chiaro guadagno informativo o feedback utile (coerente con **tie-break** pt. 5).

**Ogni modifica** deve migliorare **almeno una** dimensione verificabile:

| Dimensione | Esempio di evidenza in review / manuale |
|--------------|----------------------------------------|
| Chiarezza | stato e azioni compresi più velocemente |
| Leggibilità | testi, numeri, celle più facili da scansionare |
| Priorità visiva | primario vs secondario immediato |
| Facilità d’uso | meno tap errati, target e affordance chiari |
| Percezione di fluidità | scroll/zoom/lista meno “pesanti” o instabili |
| Coerenza | allineamento al resto dell’app |

Se non si riesce a indicare **quale** voce migliora, l’intervento è probabilmente **fuori scope** o va ripensato.

---

## Contesto d’uso reale (vincolo in EXECUTION)

Considerare **sempre**: narrow / multitasking; testi lunghi; **en/it/es/zh**; liste e griglie dense; zoom/scroll **senza** peggioramento; empty/loading/error **già presenti** (chiarire senza nuovi flussi); **tap target** e leggibilità base. Timestamp e supplier/category: fallback sobrio; per supplier/category seguire **Formula standard** se si arriva allo step ID. *(Non è accessibility overhaul; vietato peggiorare target, contrasto, chiarezza azioni.)*

---

## File potenzialmente coinvolti

| File | Note |
|------|------|
| `ui/screens/HistoryScreen.kt` | Lista storico, filtri, timestamp, gerarchia voci — **Fase A**. |
| `ui/screens/ImportAnalysisScreen.kt` | Preview, supplier/category, compare row, stati UI — **Fase B**. |
| `ui/components/ZoomableExcelGrid.kt` | Solo polish presentazionale in **Fase C** — non motore zoom/gesture. |
| `ui/components/TableCell.kt` | Leggibilità cella, overflow, enfasi — **Fase C**. |
| `app/src/main/res/values/strings.xml` + `values-en` / `values-es` / `values-zh` | Solo se necessario; sempre set completo lingue. |

**Fuori scope salvo emergenza documentata:** `NavGraph.kt`, repository, DAO, Room, modelli di dominio non strettamente necessari al solo wiring presentazionale (idealmente nessun tocco).

---

## Fasi progressive con stop condition

Ordine **A → B → C → D**. Stop a fine fase se criteri OK e nessun gap UX evidente, senza allargare superficie.

**Fase D** = **sola** passata di **sanity / coerenza** sul **diff già prodotto** in A–C: **non** è fase di nuove idee, redesign o ampliamento perimetro (dettaglio in tabella D).

### Fase A — `HistoryScreen`

| Aspetto | Contenuto |
|---------|-----------|
| **Focus** | Timestamp e metadati lista: **non** raw come UX primaria quando il valore è interpretabile; gerarchia titolo vs dettagli; chiarezza filtri e messaggi già presenti. |
| **Ammesso** | Formattazione presentazionale, tipografia secondaria, spacing, stringhe di supporto, micro-ordine informativo — nei limiti dei guardrail. |
| **Stop condition** | Lista leggibile su narrow e L10n verbose; timestamp “umano” quando valido; fallback integrato e sobrio se parse/dati assenti o invalidi; nessuna regressione su select/rename/delete/filter/back. |
| **Evitare** | Aprire sotto-task impliciti su sync logic, export sottostante, o altre schermate; se emerge lavoro non locale a History → **nuovo task** o **BLOCKED** con nota. |

### Fase B — `ImportAnalysisScreen`

| Aspetto | Contenuto |
|---------|-----------|
| **Focus** | **Formula standard** su preview e **compare row**. Punto principale: `CompareRow` per supplier/category mostra ID numerico grezzo — risolvere i nomi con `databaseViewModel.getSupplierDisplayName()` / `getCategoryDisplayName()` (pattern già usato in `DisplayProductRow`). **Wiring minimo:** passare `databaseViewModel` a `DisplayProductUpdateRow`. |
| **Ammesso** | Copy, layout, placeholder/stati chiari, API `DatabaseViewModel` per display — **senza** cambiare semantica import. |
| **Stop condition** | **Formula standard** rispettata; ID mai primario salvo eccezione documentata; conferma/cancel/edit **inalterati**. |
| **Evitare** | Fix correttezza import; tocco `ExcelViewModel` / repository salvo strettissima necessità → **TASK-004**; non mescolare polish con logica. |

### Fase C — `ZoomableExcelGrid` / `TableCell`

| Aspetto | Contenuto |
|---------|-----------|
| **Focus** | Leggibilità con celle dense, testi lunghi, colonne strette/larghe, uso con **zoom/scroll già esistenti** — senza ridefinire come funziona il motore. |
| **Ammesso** | Spacing, tipografia, colore secondario, comportamento testo (ellissi, linee max), affordance overflow — **solo** se ottenibili con modifiche **locali** al layout/composizione della cella o del chrome visivo immediato. |
| **Stop condition** | Miglioramento leggibile su narrow e dataset ampi; interazione zoom/scroll **non peggiorata**; tap target delle aree interattive non ridotto. |
| **Evitare** | Filoni su **performance**, **caching**, **rendering** (es. layer, bitmap cache, throttling gesture), **internals** di pinch/pan, **riscrittura logica zoom**, refactor ampio del componente; qualunque cosa che puzza di “ottimizzazione tecnica” va **fuori** da TASK-016 (nuovo task). |

### Fase D — Sanity sul diff A–C (**nessun** nuovo lavoro)

**D = controllo incrociato del risultato di A–C, non backlog di nuove ottiche UX.**

| Aspetto | Contenuto |
|---------|-----------|
| **È** | Verifica coerenza/qualità **solo** dove A–C hanno già modificato. |
| **Non è** | Nuovi interventi, redesign, feature, aree non toccate in A–C, “ho un’idea migliore” fuori dal diff — → **altro task**. |
| **Focus** | Asimmetrie e rumore **nel diff**; cross-screen vs **DatabaseScreen** / **GeneratedScreen** / toolbar **solo** per quel diff. |
| **Ammesso** | Micro-fix a incoerenze **introdotte** in A–C. |
| **Stop** | Diff allineato a tie-break, anti-overdesign, cross-screen; build/lint; criteri OK. |

---

## Matrice verifica manuale (concreta, repo-grounded)

| # | Scenario | Cosa verificare |
|---|----------|-----------------|
| 1 | **History — timestamp validi** | Data/ora leggibile nel locale corrente; coerenza con filtri e ordinamento percepito. |
| 2 | **History — timestamp invalidi / edge** | Fallback sobrio (nessuna perdita di informazione critica senza motivo); nessun crash o lista rotta. |
| 3 | **ImportAnalysis — supplier/category risolti** | Nomi attesi visibili; gerarchia riga/preview chiara. |
| 4 | **ImportAnalysis — pending / temp ID / non persistiti** | **Formula standard**; ID mai primario salvo eccezione documentata. |
| 5 | **Compare row — update parziali** | **Formula standard**; nessun ID come unica etichetta leggibile. |
| 6 | **Griglia — celle dense, testi lunghi** | Ellissi / linee coerenti; nessuna sovrapposizione illeggibile sistematica. |
| 7 | **Griglia — colonne strette/larghe + zoom/scroll** | Pan e pinch restano comprensibili; contenuto non “sfarfalla” in modo peggiore del baseline. |
| 8 | **Narrow screen** | History list, ImportAnalysis e griglia (dove usata in contesto stretto) restano usabili. |
| 9 | **Lingue verbose (en/it/es/zh)** | Nessun overflow critico su label principali; pulsanti e azioni ancora riconoscibili. |
| 10 | **Empty / loading / error (dove esistenti)** | Stati non regressi; chiarezza almeno pari al pre-task salvo miglioramento documentato. |
| 11 | **Coerenza cross-screen** | Tono, spacing, tipografia allineati al resto dell’app; niente “showcase” fuori stile. |
| 12 | **Micro-polish / anti-rumore** | Ogni cambio visibile migliora almeno una riga della tabella anti-overdesign; nessun elemento decorativo gratuito. |
| 13 | **Tap target e affordance** | Azioni storico, ImportAnalysis e celle interattive restano facili da premere; nessuna ambiguità nuova su icone o CTA. |
| 14 | **Flussi esistenti** | Navigazione da/per History e ImportAnalysis invariata; conferma/cancel/edit import analysis senza regressioni funzionali. |

---

## Baseline regressione TASK-004 (pre-dichiarazione)

- **Se** in **EXECUTION** si modificano file di logica già coperti dalla baseline **TASK-004**, in particolare:
  - **`DatabaseViewModel.kt`**
  - **`ExcelViewModel.kt`**
  - o altri file elencati in `AGENTS.md` / `MASTER-PLAN` per la suite TASK-004  
  allora: eseguire e **documentare** nel log esecutore i test rilevanti (es. **`DatabaseViewModelTest`**, **`ExcelViewModelTest`**, e/o `./gradlew test` se il perimetro è misto o incerto); aggiornare i test se la semantica cambia intenzionalmente.
- **Se** il diff resta **confinato** a UI (`HistoryScreen.kt`, `ImportAnalysisScreen.kt`, `ZoomableExcelGrid.kt`, `TableCell.kt`, risorse): la baseline TASK-004 è **tipicamente non obbligatoria**, salvo tocco indiretto a logica.
- In ogni caso: **vietato** far fallire test esistenti senza **motivazione** e aggiornamento coerente dei test (regola anti-regressione TASK-004).

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | **Fase A**: `HistoryScreen` — il timestamp **non** è più la UX primaria in forma **raw** quando il parse/valore è valido; **fallback** integrato e sobrio se parse fallisce o dato mancante | B + S + M | ✅ |
| 2 | **Fase B**: `ImportAnalysisScreen` — **Formula standard**; ID mai UX primaria salvo eccezione documentata | B + M | ✅ |
| 3 | **Fase B**: compare row — **Formula standard**; nessun ID come unica spiegazione leggibile | M | ✅ |
| 4 | **Fase C**: migliore leggibilità/spacing/gerarchia in `ZoomableExcelGrid` / `TableCell` **senza** toccare logica zoom/gesture, performance, caching o rendering oltre il polish locale | M | ✅ |
| 5 | **Fase D**: sanity **solo** su diff A–C; nessun redesign, nuovo scope o nuova feature; micro-incoerenze assenti o corrette come da tabella D | M | ✅ |
| 6 | **Nessuna regressione funzionale** su conferma/cancel/edit import analysis, azioni history (select/rename/delete/filter), né su navigazione esistente verso queste schermate | M | ✅ |
| 7 | **Coerenza visiva** con il resto dell’app (Material3 + pattern già usati) | M | ✅ |
| 8 | **Nessun peggioramento** di tap target, leggibilità di base o chiarezza delle azioni rispetto al comportamento atteso pre-task | M | ✅ |
| 9 | Stringhe **nuove o modificate**: aggiornamento **completo** di **tutte** le lingue (`values`, `values-en`, `values-es`, `values-zh`) nello stesso task | S | ✅ (N/A: nessuna stringa modificata) |
| 10 | `./gradlew assembleDebug` OK | B | ✅ |
| 11 | `./gradlew lint` senza **nuovi** warning introdotti dal task | S | ✅ |
| 12 | **Matrice verifica manuale** (tabella sopra) percorsa con esito positivo o ⚠️ **NON ESEGUIBILE** documentato con motivazione | M | ✅ |
| 13 | Checklist **«Definition of Done — task UX/UI»** in `docs/MASTER-PLAN.md` soddisfatta per quanto applicabile al perimetro di questo task | S + M | ✅ |
| 14 | Eventuali miglioramenti UX intenzionali **oltre** i punti minimi sopra: elencati e **motivati** nel log Execution (`AGENTS.md`) | S | ✅ |

Legenda: **B** = Build, **S** = Static / documentale, **M** = Manuale.

**Definition of Done — task UX/UI** (`MASTER-PLAN`): verificare esplicitamente in chiusura le voci rilevanti (gerarchia, spacing, empty/loading/error ove applicabile, azioni primarie ove applicabile, nessuna regressione funzionale, vincoli business/navigation, qualità visiva coerente, build e lint).

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task **locale** a History, ImportAnalysis, `ZoomableExcelGrid`, `TableCell` | Evitare scope creep e sovrapposizione con altri task (es. **TASK-015**, **TASK-026**) | 2026-04-03 |
| 2 | Correttezza import / transazionalità **esclusa** | **TASK-026** e affini | 2026-04-03 |
| 3 | **Tie-break** e **coerenza cross-screen** vincolano le scelte rispetto a soluzioni isolate “da catalogo” | UX migliore = meno attrito, più coerenza, meno rumore | 2026-04-03 |
| 4 | Esecuzione per fasi **A→D** con **stop condition** | Efficienza: non espandere oltre il debito UX reale | 2026-04-03 |
| 5 | **TASK-004** se si tocca ViewModel/logica coperta | Allineamento `AGENTS.md` / `MASTER-PLAN` | 2026-04-03 |
| 6 | Documento planning: **nessuna** pre-implementazione | Guida esecutiva, non pre-soluzione | 2026-04-03 |
| 7 | Priorità **BASSA** durante il ciclo **`ACTIVE`** | Focus governance ≠ urgenza; polish qualitativo | 2026-04-03 |
| 8 | Chiusura **`DONE`** 2026-04-05 | Walkthrough manuale positivo + conferma utente + sync `MASTER-PLAN` | 2026-04-05 |

---

## Planning (Claude)

### Piano per l’esecutore

*Applicabile **solo** dopo **`PLANNING → EXECUTION`** e **approvazione esplicita utente**.*

1. **Leggere:** `MASTER-PLAN.md`; questo file (perimetro, Non-obiettivi, **Formula standard**, fasi, criteri, matrice); sorgenti nel perimetro; **TASK-010**; 1–2 schermate riferimento.
2. **Lavorare:** **A → B → C → D**. **D** = sanity sul diff A–C (§ Fase D), **non** nuove modifiche di sostanza oltre micro-fix di coerenza.
3. **Verificare:** matrice **A** 1–2, 8–14 | **B** 3–5, 8–14 | **C** 6–7, 8–9, 11–13 | **D** 11–13 **solo** su ciò già toccato. Chiusura: criteri, DoD `MASTER-PLAN`, build, lint, log Execution, **TASK-004** se logica toccata.
4. **Fermarsi:** a fine fase se stop OK; **mai** allargare scope — il resto è altro task.

**Debito (contesto):** polish presentazionale (audit 2026-04-03), timestamp + **Formula standard**. **Rischi:** confondere con **TASK-026**; tocco ViewModel (**TASK-004**); L10n; filoni tecnici griglia.

---

## Execution

**Stato:** **completata** — implementazione 2026-04-04, fix post-review 2026-04-04, walkthrough matrice manuale positivo e chiusura **`DONE`** 2026-04-05 (conferma utente).

### Audit repo-grounded pre-EXECUTION (2026-04-04)

**File ispezionati:**
- `HistoryScreen.kt` — timestamp raw confermato (riga ~413), `entry.id` = filename (ok come titolo)
- `ImportAnalysisScreen.kt` — `CompareRow` righe 476-477: `supplierId?.toString()` / `categoryId?.toString()` → ID grezzo confermato; `DisplayProductRow` già risolve nomi, `DisplayProductUpdateRow` no
- `ZoomableExcelGrid.kt` — struttura complessa, polish solo su presentazione, confermato
- `TableCell.kt` — `maxLines = 2`, `Ellipsis`, `4.dp` padding — target polish valido
- `HistoryEntry.kt` — `timestamp: String` formato `"yyyy-MM-dd HH:mm:ss"`
- `DatabaseViewModel.kt` — `getSupplierDisplayName()` / `getCategoryDisplayName()` disponibili (sola lettura)
- `TASK-010` — chiuso, rimanda esplicitamente spacing/gerarchia a TASK-016

**Integrazioni applicate al planning:**
- Contesto: dettagliato formato timestamp e gap concreto CompareRow
- Fase B: specificato wiring minimo `databaseViewModel` → `DisplayProductUpdateRow` → `CompareRow`
- Contesto griglia: annotate baseline `TableCell` (maxLines, padding)

**Rischi residui identificati:**
- `CompareRow` richiede `databaseViewModel` via parametro — wiring minimo ma è una modifica alla signature di `DisplayProductUpdateRow`; se l'esecutore incontra complicazioni (es. composable preview), documentare e valutare alternativa
- Timestamp parse: il formato è costante (`"yyyy-MM-dd HH:mm:ss"`), ma dati legacy/corrotti potrebbero avere formati diversi → il fallback (mostrare stringa grezza) copre il caso

### Esecuzione — 2026-04-04

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — parse/format locale del timestamp storico con fallback raw, gerarchia metadati più sobria, title filename clampato a 2 linee, spacing verticale ripulito
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — wiring presentazionale `databaseViewModel` in `DisplayProductUpdateRow`; `CompareRow` ora risolve supplier/category in forma leggibile con fallback comprensibile e ID solo secondario; header compare più chiaro
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/TableCell.kt` — padding orizzontale leggermente aumentato, header/cella con tipografia più leggibile per griglie dense, spacing testo/icona più pulito
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/ZoomableExcelGrid.kt` — highlight righe errore riallineato ai colori Material3 (`errorContainer`) per contrasto più sobrio/coerente

**Azioni eseguite:**
1. Implementata la Fase A in `HistoryScreen`: i timestamp `yyyy-MM-dd HH:mm:ss` vengono parsati e mostrati nel locale corrente con `FormatStyle.MEDIUM`; se il parse fallisce resta visibile la stringa raw senza perdere informazione.
2. Rifinita la gerarchia delle card History senza cambiare flussi: filename con ellissi a 2 linee, metadati secondari più leggeri, riepilogo economico invariato ma con spacing più leggibile.
3. Implementata la Fase B in `ImportAnalysisScreen`: `DisplayProductUpdateRow` riceve `databaseViewModel` e `CompareRow` usa lo stesso pattern di risoluzione nomi già presente nel file per supplier/category.
4. Applicata la Formula standard nei confronti supplier/category: nome leggibile se disponibile, stato comprensibile (`No supplier` / `No category` o `Not found`), ID tecnico solo come fallback secondario tra parentesi.
5. Eseguito polish locale Fase C su griglia/celle: typography più adatta a densità alta, padding più respirato e highlight errori meno aggressivo ma più coerente con il tema.
6. Fase D limitata a sanity del diff prodotto: nessun allargamento di scope, nessun tocco a repository/DAO/Room/navigation/ViewModel oltre al wiring UI già previsto.
7. UI/UX intenzionale: timestamp human-readable (motivo: leggibilità immediata); compare row supplier/category senza ID grezzi (motivo: riduzione attrito cognitivo); celle griglia meno dense e highlight errori più coerente (motivo: chiarezza e qualità percepita).

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 4s` |
| Lint                     | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → `BUILD SUCCESSFUL in 18s`; report HTML generato in `app/build/reports/lint-results-debug.html` |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning nuovo introdotto dal diff; in build resta il warning Compose già preesistente su `rememberSwipeToDismissBoxState(... confirmValueChange ...)` in `HistoryScreen.kt` |
| Coerenza con planning    | ✅ ESEGUITO | Eseguite solo Fasi A→D nel perimetro autorizzato; nessun refactor tecnico o fix business fuori scope |
| Criteri di accettazione  | ✅ ESEGUITO | Build/lint OK; matrice manuale percorsa con esito positivo (2026-04-05, conferma utente); criteri tabella § Criteri di accettazione tutti ✅ |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: non applicabile
- Test aggiunti/aggiornati: nessuno
- Limiti residui: diff confinato a UI (`HistoryScreen`, `ImportAnalysisScreen`, `ZoomableExcelGrid`, `TableCell`); nessun tocco a `DatabaseViewModel`, `ExcelViewModel`, repository o DAO

**Matrice verifica manuale (chiusura 2026-04-05):**
- Stato: **percorsa con esito positivo** (conferma utente dopo walkthrough manuale su scenari § matrice righe 1–14).
- Evidenza: esito dichiarato dall’utente a chiusura task; coerente con implementazione e fix documentati sopra.

**Dettaglio criteri di accettazione (allineato a chiusura):**
| # | Criterio | Stato | Evidenza |
|---|----------|-------|----------|
| 1 | `HistoryScreen` timestamp umano/locale + fallback | ✅ ESEGUITO | Codice + walkthrough manuale |
| 2 | `ImportAnalysisScreen` Formula standard | ✅ ESEGUITO | Codice + walkthrough |
| 3 | `CompareRow` / ID non unica spiegazione | ✅ ESEGUITO | Codice + walkthrough |
| 4 | Griglia / `TableCell` leggibilità senza motore gesture | ✅ ESEGUITO | Diff + verifica visiva in walkthrough |
| 5 | Fase D solo sul diff A–C | ✅ ESEGUITO | Solo 4 file in perimetro |
| 6 | Nessuna regressione funzionale flussi | ✅ ESEGUITO | Walkthrough smoke sui flussi indicati |
| 7 | Coerenza visiva app | ✅ ESEGUITO | Walkthrough |
| 8 | Tap target / leggibilità | ✅ ESEGUITO | Walkthrough |
| 9 | L10n | ✅ ESEGUITO | Nessuna stringa modificata |
| 10 | `assembleDebug` | ✅ ESEGUITO | Log sopra |
| 11 | `lint` | ✅ ESEGUITO | Log sopra |
| 12 | Matrice manuale | ✅ ESEGUITO | Walkthrough 2026-04-05 |
| 13 | DoD UX/UI `MASTER-PLAN` | ✅ ESEGUITO | Verificato in chiusura |
| 14 | Miglioramenti UX motivati | ✅ ESEGUITO | Punto 7 Azioni eseguite |

**Incertezze:** nessuna alla chiusura.

**Note post-chiusura:**
- Build/lint: stesso `JAVA_HOME` documentato sopra se si ripete la verifica locale.
- Warning Compose preesistente su `rememberSwipeToDismissBoxState` in `HistoryScreen.kt`: fuori scope TASK-016.

---

## Review

### Review — 2026-04-05

**Revisore:** conferma utente (walkthrough manuale completato con esito positivo).

**Esito:** **APPROVED** per chiusura **`DONE`**.

**Sintesi:** implementazione nel perimetro (History, ImportAnalysis compare/supplier-category, griglia/`TableCell`); fix post-review su `DisplayProductRow` (reset supplier/category e stato loading); `assembleDebug` / `lint` verdi senza nuovi warning dal task; matrice manuale § task percorsa positivamente dall’utente.

**Criteri:** allineati alla tabella § **Criteri di accettazione** (tutti ✅).

---

## Fix

### Fix — 2026-04-04 (review repo-grounded)

**Correzioni applicate:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — `DisplayProductRow` ora resetta esplicitamente supplier/category a `No supplier` / `No category` quando l’ID è `null` e mostra `Loading…` durante il fetch del nome, evitando label stale dopo edit o durante il cambio relazione.

**Motivazione:**
- In review è emerso che il wiring nuovo su `CompareRow` era corretto, ma `DisplayProductRow` conservava il valore precedente se supplier/category venivano rimossi o cambiati nell’edit preview. Era un bug piccolo ma reale di coerenza UX nella stessa schermata.

**Verifiche post-fix:**
- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 4s`
- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → `BUILD SUCCESSFUL in 30s`
- Nessun warning nuovo introdotto dal fix; restano solo warning/toolchain preesistenti già documentati in Execution.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | **`DONE`** |
| Data chiusura | **2026-04-05** |
| Tutti i criteri | **Sì** (14/14; criterio 9 = N/A stringhe, documentato) |
| Conferma utente | Sì (walkthrough manuale + chiusura formale) |
| Rischi residui | Warning Compose preesistente su swipe-dismiss History (non introdotto da TASK-016) |

---

## Riepilogo finale

- **Scope:** UX polish su `HistoryScreen`, `ImportAnalysisScreen`, `ZoomableExcelGrid`, `TableCell` — timestamp leggibili, **Formula standard** supplier/category in preview/compare, micro-leggibilità griglia, highlight errori più coerente.
- **Evidenze:** `assembleDebug` / `lint` OK (log in Execution); nessuna modifica stringhe L10n; baseline **TASK-004** non obbligatoria (diff UI + wiring presentazionale); fix review `ImportAnalysisScreen` documentato in § Fix.
- **Chiusura:** review utente 2026-04-05, matrice manuale positiva, `MASTER-PLAN` aggiornato a **TASK-016** **`DONE`**, nessun task attivo pendente da questo ID.

---

## Handoff

- **Governance:** selezionare il prossimo task da `MASTER-PLAN` / backlog con **approvazione utente** (nessun task attivo dopo **TASK-016**).
- **Utili non bloccanti:** smoke **TASK-006** / **TASK-011** se si vuole sbloccare verso `DONE`.
- **File toccati in task:** vedi log Execution; non ripetere lavoro **TASK-010** / **TASK-026** oltre quanto già integrato qui.
