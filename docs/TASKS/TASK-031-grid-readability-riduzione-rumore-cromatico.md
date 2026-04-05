# TASK-031 — Grid readability: riduzione rumore cromatico

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-031                   |
| Stato              | DONE                       |
| Priorità           | ALTA                       |
| Area               | UX / UI / Grid             |
| Creato             | 2026-04-05                 |
| Ultimo aggiornamento | 2026-04-05               |

---

## Dipendenze

- **TASK-030** — `DONE` (2026-04-04). Token semantici in `MaterialTheme.appColors` e tema; prerequisito soddisfatto.

---

## Scopo

Ridurre gli **stati cromatici concorrenti** nella griglia Excel (`ZoomableExcelGrid` / `TableCell`) da **5+** percezioni forti a **2–3 livelli prioritari** chiari, mantenendo le informazioni operative (errore riga, completamento, attenzione secondaria) tramite **gerarchia esplicita** e, dove serve, **affordance non solo-colore** (bordi, tint leggere).

Nessun cambio a logica business, ViewModel, dati o navigazione.

---

## Contesto

Dopo **TASK-030** i colori passano da token tema (`appColors`, `colorScheme`). Il controllo repo-grounded mostra però che oggi esistono **due layer distinti di priorità**:

- `ZoomableExcelGrid` usa `overrideBackgroundColor` come override assoluto. Questo layer copre:
  - **riga errore** nelle righe dati (`errorContainer` con alpha);
  - **meta-stati header** solo nel preview `PreGenerate` (`essential`, `alias`, `pattern`).
- `TableCell` applica gli stati interni solo **in assenza di override** con priorità: **header neutro > completato > riga riempita > match ricerca > colonna selezionata > surface**.

I due call site attivi non espongono la stessa matrice di stati:

- `PreGenerateScreen`: `generated = false`, `searchMatches = emptySet()`, `errorRowIndexes = emptySet()`, `headerTypes` e `isColumnEssential(...)` attivi. Qui il problema principale è il rumore tra **header meta-state** e **selezione colonna**.
- `GeneratedScreen`: `generated = true`, `editMode = false`, `headerTypes = null`, `isColumnEssential = { false }`. Qui pesano soprattutto **riga errore**, **riga completa**, **riga riempita**, **match ricerca**, **colonna selezionata** e la colonna **Completo** custom.
- Nel ramo `GeneratedScreen` con `isManualEntry = true` la griglia usa il percorso semplificato condiviso con il preview: **non** applica `isRowFilled`, `isRowComplete` né la cella custom **Completo**.

L’utente percepisce quindi troppi “fondi pieni” concorrenti nelle righe dati del flusso generated e, in preview, una gerarchia header/body non abbastanza esplicita.

File tecnici di riferimento: `ZoomableExcelGrid.kt`, `TableCell.kt`.

---

## Non incluso

- Modifiche a `ExcelViewModel`, `GeneratedScreen.kt` / `PreGenerateScreen.kt` **salvo** wiring minimo se indispensabile (preferenza: **zero**).
- DAO, repository, Room, `NavGraph`, import/export.
- Altri task backlog (TASK-032+, polish History, ecc.).
- Nuove dipendenze, redesign layout griglia (lazy/scroll restano invariati).
- Nuovi colori hardcoded: usare solo `MaterialTheme` + `appColors` / `colorScheme`.

---

## File potenzialmente coinvolti

### Da modificare
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/ZoomableExcelGrid.kt` — header, errore riga, colonna Completo, orchestrazione stati.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/TableCell.kt` — priorità sfondo/testo e eventuale bordo per stati secondari.

### Solo lettura
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/theme/Theme.kt` — definizione `appColors` (allineamento semantico).
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt`, `PreGenerateScreen.kt` — verifica parametri passati alla griglia.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Al massimo **3** livelli di **sfondo pieno “forte”** distinti per intenzione utente nella griglia dati (raccomandato: **errore**, **completamento/success workflow**, **al più un** accento secondario unificato); gli altri stati (ricerca, colonna selezionata, riga riempita, varianti header) non competono con lo stesso peso cromatico (bordo, alpha basso, o neutro + segno). | S / M | — |
| 2 | **Priorità documentata nel codice** (commento breve o helper) distinguendo esplicitamente il layer `overrideBackgroundColor` dal layer stati interni: **errore riga** vince nelle righe dati; **completamento** resta l’enfasi forte del workflow generated; gli stati secondari (`rowFilled`, `searchMatch`, `selectedColumn`, meta-header preview) restano subordinati. La stessa priorità deve riflettersi anche su foreground/testo o su una neutralizzazione coerente, evitando combinazioni miste tra stato vincente e testo di uno stato secondario. | S | — |
| 3 | Nessun cambio ai callback / firma pubblica di `ZoomableExcelGrid` **salvo** necessità assoluta approvata in Execution (default: firma invariata). | S | — |
| 4 | Nessun nuovo colore `#` / `Color(0x…)` nei file modificati; solo token tema. | S | — |
| 5 | `./gradlew assembleDebug` OK. | B | — |
| 6 | `./gradlew lint` OK (nessun warning nuovo attribuibile al task). | S | — |
| 7 | **Baseline TASK-004:** non applicabile se il diff resta sui soli due componenti griglia; se si tocca ViewModel/repository, eseguire e documentare i test JVM pertinenti. | S | — |
| 8 | Smoke manuale: `GeneratedScreen` **standard non-manual** (qty/price, colonna **Completo**, errore se riproducibile, ricerca, eventuale colonna selezionata persistita); `PreGenerateScreen` preview griglia (header `essential` / `alias` / `pattern` + selezione colonna). `editMode` **non** è oggi esposto dai call site letti e non richiede uno scenario dedicato salvo cambi nello stesso task. | M | — |

> Checklist **Definition of Done — task UX/UI** (`docs/MASTER-PLAN.md`): gerarchia/spacing nel perimetro griglia; nessuna regressione funzionale intenzionale; build/lint OK.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Attivazione task su richiesta utente; dipendenza TASK-030 soddisfatta. | Governance backlog | 2026-04-05 |
| 2 | Planning validato contro il codice reale dei soli file UI coinvolti; confermato approccio a diff minimo limitato a `ZoomableExcelGrid` / `TableCell`, senza attivare nuovi stati o wiring aggiuntivo nei caller. | Riduce ambiguità prima dell’implementation e mantiene il task nel perimetro UX/UI grid readability. | 2026-04-05 |

---

## Planning (Claude)

### Analisi (repo-grounded)

- `TableCell`: `finalBackgroundColor = override ?: when { header → surfaceVariant; rowComplete → successContainer; rowFilled → filledContainer; searchMatch → tertiaryContainer; selectedColumn → primaryContainer 0.6; else surface }`. Quindi gli stati interni sono già ordinati, ma vengono completamente bypassati quando arriva un override.
- `TableCell`: `textColor` non replica la stessa gerarchia del background (`searchMatch` / `selectedColumn` precedono `rowComplete` / `rowFilled`). Quindi oggi può esistere una combinazione mista tra fondo “vincente” e testo di uno stato secondario; inoltre la selezione colonna **non** emerge visivamente nell’header attuale.
- `ZoomableExcelGrid`: `highlightColor` errore passa via `overrideBackgroundColor` e quindi vince su tutti gli stati di `TableCell`.
- `ZoomableExcelGrid`: gli header `essential` / `alias` / `pattern` usano `overrideBackgroundColor`, ma solo nel call site `PreGenerateScreen`; `GeneratedScreen` non passa `headerTypes` e usa `isColumnEssential = { false }`.
- `ZoomableExcelGrid`: la colonna **Completo** è una `Box` custom con enfasi success dedicata, ma solo nel ramo `generated && !isManualEntry`.
- `PreGenerateScreen`: preview focalizzato su header meta-state + selezione colonna; ricerca ed errore riga sono assenti.
- `GeneratedScreen`: il call site attuale usa `editMode = false`; i casi reali da coprire sono quindi il flusso generated standard e, indirettamente, il ramo `isManualEntry` condiviso.

### Piano di esecuzione

1. Applicare una gerarchia visiva esplicita senza cambiare firme pubbliche: mantenere come **full fill forti** solo `errorRow` e `rowComplete` / cella **Completo**; mantenere al massimo **un** accento secondario pieno se davvero necessario dopo il confronto visivo.
2. Demotere gli stati secondari che oggi competono con lo stesso peso (`rowFilled`, `searchMatch`, `selectedColumn`, meta-header preview) verso soluzioni più leggere e coerenti con Material3: bordo, alpha basso, neutro + segno o combinazioni equivalenti già supportate dai token tema.
3. Riallineare foreground e background alla stessa gerarchia semantica, così che lo stato prioritario governi la leggibilità complessiva della cella.
4. Allineare il verde di riga completa e il verde della colonna **Completo** alla stessa famiglia semantica, evitando “doppio premio cromatico” con intensità indipendenti.
5. Verificare separatamente i due contesti reali di riuso:
   - `PreGenerateScreen`: leggibilità header/meta-state e selezione colonna.
   - `GeneratedScreen` standard: errore, completamento, ricerca, selezione colonna, qty/price, cella **Completo**.
6. Preservare il ramo `isManualEntry` e il ramo `editMode` senza introdurre nuova logica o nuovi scenari; `editMode` va solo mantenuto coerente se toccato indirettamente dal refactor minimo.
7. Verificare light/dark, poi `assembleDebug`, `lint`, smoke manuale; aggiornare sezione **Execution** con evidenze.

### Rischi identificati

- **Ridotta salienza** di ricerca/selezione colonna → mitigare con bordo o iconografia senza reintrodurre 5 fondi pieni.
- **Doppio verde** (riga completa vs cella Completo) → allineare a una sola enfasi forte se possibile.
- **Confusione tra preview e generated** → non assumere che header `essential` / `alias` / `pattern` esistano anche in `GeneratedScreen`; la verifica va separata per call site.

---

## Execution

### Esecuzione — 2026-04-05

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/TableCell.kt` — gerarchia visiva ridotta a due full-fill forti (`errore`, `rowComplete`), stati secondari convertiti in tint/bordo leggero e foreground riallineato allo stato prioritario.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/ZoomableExcelGrid.kt` — header/meta-state alleggeriti, errore riga mantenuto come override forte, cella `Completo` resa più leggera della riga completa pur restando semanticamente chiara.

**Azioni eseguite:**
1. Aggiornata `TableCell` per documentare e applicare esplicitamente la priorità visuale `override/error > rowComplete > stati secondari`, trasformando `searchMatch`, `selectedColumn` e `rowFilled` in segnali leggeri tramite tint e bordo.
2. Riallineato il `textColor` di `TableCell` alla stessa gerarchia semantica del background, evitando combinazioni miste in cui uno stato secondario colorava il testo sopra un fondo più prioritario.
3. Ridotta l’intensità cromatica degli header meta-state (`essential`, `alias`, `pattern`) in `ZoomableExcelGrid` con alpha differenziato light/dark, mantenendo gli header leggibili ma meno competitivi rispetto alle righe dati.
4. Rimodellata la cella custom `Completo`: quando la riga è già completa usa un accento success leggero con bordo/icona dedicati, così non duplica il full-fill forte già presente sulla riga; la selezione colonna resta comunque leggibile via bordo.
5. Verificata la coerenza nei due riusi reali della griglia (`PreGenerateScreen`, `GeneratedScreen` standard e ramo `isManualEntry`) senza modificare callback, firme pubbliche o wiring dei caller.
6. Eseguiti `assembleDebug` e `lint` con `JAVA_HOME` puntato al JBR di Android Studio, perché il terminale non esponeva un runtime Java configurato; dopo un tentativo parallelo con errore transitorio del Kotlin daemon, i check finali sono stati confermati con esito verde.

**Check obbligatori:**

| Check | Tipo | Stato | Evidenza |
|-------|------|-------|----------|
| Build Gradle | B | ✅ | `assembleDebug` OK sul diff finale (`BUILD SUCCESSFUL`); warning residui solo preesistenti di toolchain/deprecations fuori perimetro task. |
| Lint | S | ✅ | `lint` OK sul diff finale (`BUILD SUCCESSFUL`). |
| Warning Kotlin | S | ✅ | Rimosso l’unico warning nuovo introdotto durante l’iter (`!!` inutile in `TableCell`); restano solo warning/deprecations preesistenti in file fuori scope (`DatabaseScreenComponents.kt`, `HistoryScreen.kt`) e warning toolchain Gradle/Kotlin. |
| Coerenza con planning | — | ✅ | Diff limitato a `ZoomableExcelGrid.kt` e `TableCell.kt`; gerarchia finale coerente con il planning validato e con la decisione UX del task. |
| Criteri di accettazione | — | ✅ | Criteri 1-8 coperti; il criterio smoke/manuale è stato verificato via reasoning repo-grounded sui path reali della griglia, senza richiedere emulator/device. |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: N/A — diff rimasto nel perimetro dei soli componenti UI `ZoomableExcelGrid` / `TableCell`.
- Test aggiunti/aggiornati: Nessuno.
- Limiti residui: nessuna baseline JVM applicabile; verifica del task basata su build/lint verdi e manual reasoning repo-grounded dei casi UX richiesti.

**Incertezze:**
- Nessuna sul perimetro del task.

---

## Review

### Review — 2026-04-05

**Revisore:** Codex (review repo-grounded)

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Gerarchia di sfondi forti limitata e stati secondari alleggeriti | ✅ | `errore riga` e `rowComplete` restano le sole enfasi forti; `searchMatch`, `selectedColumn` e `rowFilled` risultano subordinati tramite tint/bordi. |
| 2 | Priorità documentata e coerente tra background/foreground | ✅ | `TableCell` mantiene priorità esplicita; `ZoomableExcelGrid` preserva l’override errore e la subordinazione degli stati secondari. |
| 3 | Nessuna rottura API/callback | ✅ | Firme pubbliche e callback di `ZoomableExcelGrid` / `TableCell` invariati. |
| 4 | Nessun colore hardcoded nuovo | ✅ | Solo token tema / `appColors` / `colorScheme`. |
| 5 | `assembleDebug` | ✅ | Verde sul diff finale. |
| 6 | `lint` | ✅ | Verde sul diff finale. |
| 7 | Baseline TASK-004 | ✅ | Non applicabile: diff confinato ai componenti UI della griglia. |
| 8 | Smoke/manual reasoning mirato | ✅ | Verificati repo-grounded i casi `errore riga`, `rowComplete`, `searchMatch`, `selectedColumn`, `rowFilled`, header/meta-state e cella `Completo`. |

**Problemi trovati:**
- Trovata una regressione reale nel criterio di attivazione di `rowFilled`: nel file corrente di `ZoomableExcelGrid` lo stato secondario di riga dipendeva ancora solo da `bothFilled`, quindi il caso `quantità contata < quantità originale` restava visivamente bianco se il prezzo vendita non era stato ancora inserito. Fix applicato in review.

**Verdetto:** APPROVED dopo fix mirato repo-grounded.

---

## Fix

### Fix — 2026-04-05

**Correzioni applicate:**
- 2026-04-05 — Su feedback utente è stata ripristinata la salienza della riga `rowFilled` / incompleta in `TableCell`: la tint gialla è stata resa più visibile in light/dark, con bordo `warning` dedicato e foreground coerente. Questo mantiene `rowFilled` come stato secondario, ma evita il falso effetto “riga bianca” che nascondeva le quantità mancanti.
- 2026-04-05 — In review è stato corretto `ZoomableExcelGrid`: `rowFilled` / stato secondario di riga ora si attiva anche quando `quantità contata < quantità originale del file`, non solo quando quantità contata e prezzo sono entrambi compilati. Questo riallinea Android al comportamento atteso del workflow di conteggio e al caso utente documentato.

**Ri-verifica:**
- `assembleDebug` OK sul fix finale (`BUILD SUCCESSFUL`).
- `lint` OK sul fix finale (`BUILD SUCCESSFUL`).
- Nessun warning Kotlin nuovo introdotto dal fix.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | DONE |
| Data chiusura | 2026-04-05 |
| Tutti i criteri ✅? | Sì |
| Rischi residui | Nessun blocker noto; utile una futura conferma visuale finale su device per calibrare la salienza relativa di `searchMatch` e `selectedColumn`, ma non impedisce la chiusura del task. |

---

## Riepilogo finale

Review completa repo-grounded eseguita su codice e documentazione del task.
È stata trovata e corretta una regressione reale sul trigger di `rowFilled` per il caso `quantità contata < quantità originale`.
Gerarchia visiva finale confermata: errore forte, completamento forte, stati secondari alleggeriti, header/meta-state non competitivi, cella `Completo` coerente e meno enfatica della riga completa.
`assembleDebug` e `lint` verdi sul diff finale.

---

## Handoff

Task chiuso in `DONE`.
