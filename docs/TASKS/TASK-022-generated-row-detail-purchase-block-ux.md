# TASK-022 — GeneratedScreen: dettaglio riga — blocco prezzo acquisto (layout + vecchio prezzo)

---

## Informazioni generali

| Campo                | Valore |
|----------------------|--------|
| ID                   | TASK-022 |
| Stato                | **DONE** |
| Priorità             | MEDIA |
| Area                 | UX / UI / GeneratedScreen |
| Creato               | 2026-03-30 |
| Ultimo aggiornamento | 2026-03-30 — review repo-grounded finale + conferma utente; `assembleDebug`/`lint` OK; task chiuso in `DONE`. |

---

## Dipendenze

- TASK-014 (`DONE`) — UX modernization GeneratedScreen (follow-up mirato, non riapertura TASK-014)

---

## Scopo

Nel bottom sheet “Informazioni riga” di `GeneratedScreen`: mettere il blocco **prezzo acquisto** sopra quantità contata / prezzo vendita, a **larghezza piena**; mostrare **Acq. vecchio** (barrato) solo se diverso dal prezzo acquisto attuale, con gestione trim / vuoti / uguaglianza numerica dove applicabile. Nessun cambio alla logica business o al ViewModel.

---

## Contesto

Dopo TASK-014 restava un layout a due colonne che collocava il prezzo acquisto nella colonna destra sotto il prezzo vendita, con spazio vuoto a sinistra. L’utente richiede gerarchia e leggibilità migliori senza toccare persistenza, `editableValues`, calcolatrice, focus IME, mark complete.

---

## Non incluso

- DAO, repository, Room, navigation, modelli dati
- Manual add screen (salvo condivisione codice senza rischio — qui perimetro solo dialog dettaglio riga)
- Porting iOS

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — `GeneratedScreenInfoDialog`

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Prezzo acquisto sopra quantità contata e prezzo vendita | M | — |
| 2 | Blocco prezzo acquisto full width (no colonna vuota a sinistra in layout wide) | M / S | — |
| 3 | “Acq. vecchio” visibile solo se vecchio ≠ attuale (blank/trim; uguaglianza numerica se entrambi parsabili) | M / S | — |
| 4 | Invariati: salvataggio riga, `updateHistoryEntry`, calcolatrice acquisto, focus Next tra qty e vendita, mark complete su Done | B / M | — |
| 5 | Build Gradle OK; lint senza nuovi problemi sul file toccato | B / S | — |

Legenda: B=Build, S=Static, M=Manual, E=Emulator

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Nuovo task TASK-022 invece di riaprire TASK-014 | Governance: TASK-014 `DONE`, nessun `ACTIVE`; micro-follow-up dedicato | 2026-03-30 |

---

## Planning (Claude)

### Analisi

`GeneratedScreenInfoDialog` seconda card: `retailField` era una `Column` con vendita + `purchaseReference()`, e in wide `Row(counted, retailField)` lasciava il prezzo acquisto solo nella metà destra.

### Piano di esecuzione

1. Aggiungere helper `oldPurchasePriceDiffersFromCurrent` (trim, empty, confronto stringa e opzionale `Double`).
2. Riordinare: `Column { purchaseBlock(); Row/Column qty+retail }` dentro la stessa card.
3. `purchaseBlock` edit: `GeneratedScreenCompactInputField` con `Modifier.fillMaxWidth()` e supporting text condizionato.
4. Check build/lint; log in Execution.

---

## Execution (esecutore)

### Esecuzione — 2026-03-30

**File modificati:**
- `GeneratedScreen.kt` — riordino blocco prezzo acquisto (full width sopra), helper visibilità “vecchio acquisto”, micro-spacing read-only (`Column` + `spacedBy` sotto la Surface).

**Azioni eseguite:**
1. Introdotto `oldPurchasePriceDiffersFromCurrent` per mostrare il vecchio prezzo solo se semanticamente diverso dall’attuale.
2. Refactor seconda `GeneratedScreenDetailCard`: `purchaseBlock()` prima; sotto solo `countedField` + `retailField` (due colonne solo tra qty e vendita).
3. Eseguiti review repo-grounded del diff e check tecnici reali con JBR di Android Studio: `./gradlew assembleDebug`, `./gradlew lint`.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` → `BUILD SUCCESSFUL` |
| Lint | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lint` → `BUILD SUCCESSFUL`; report in `app/build/reports/lint-results-debug.*` |
| Warning nuovi | ✅ ESEGUITO | Nessun warning sul file toccato (`GeneratedScreen.kt`) nel report lint; restano warning globali preesistenti fuori scope |
| Coerenza con planning | ✅ | Perimetro rispettato |
| Criteri di accettazione | ✅ | Review finale repo-grounded + conferma utente: nessun finding bloccante, criteri soddisfatti |

**Baseline regressione TASK-004 (se applicabile):**
- Non applicabile: solo UI Compose in `GeneratedScreen`, nessuna modifica a repository/ViewModel logica dati.

**Incertezze:**
- (nessuna)

**Handoff notes:**
- Smoke consigliato in caso di futuri ritocchi UI: aprire dettaglio riga in wide e narrow; edit/read; verificare IME Next qty→vendita; calcolatrice acquisto; riga con old purchase = current (non deve mostrare barrato).

---

## Review (Claude)

_(vuoto finché non in REVIEW)_

---

## Fix (esecutore)

_(vuoto)_

---

## Chiusura

### Chiusura / conferma utente — 2026-03-30

**Review tecnica finale:** controllo repo-grounded del codice reale in `GeneratedScreen.kt`, confronto con la versione `main` su GitHub e verifica del diff locale. **Nessun finding bloccante**: la modifica resta nel perimetro richiesto, non tocca ViewModel/DAO/repository/navigation, e preserva i wiring di `editableValues`, `completeStates`, `updateHistoryEntry(entryUid)`, calcolatrice prezzo acquisto, focus IME e mark-as-complete.

**Conferma utente:** l’utente ha richiesto la chiusura del task dopo review, indicando che dai test/verifiche la modifica risulta corretta.

| Criterio | Stato | Evidenza |
|----------|-------|----------|
| 1. Prezzo acquisto sopra quantità contata e prezzo vendita | **ESEGUITO** | `purchaseBlock()` reso primo elemento della card operativa in `GeneratedScreenInfoDialog`; review statica del layout |
| 2. Blocco prezzo acquisto full width | **ESEGUITO** | `purchaseBlock` usa `Modifier.fillMaxWidth()` e rimane fuori dalla `Row` wide di qty/vendita |
| 3. “Acq. vecchio” visibile solo se diverso dall’attuale | **ESEGUITO** | Helper `oldPurchasePriceDiffersFromCurrent(current, old)` con trim/vuoti/confronto numerico; usato sia in edit sia in read-only |
| 4. Logica funzionale invariata | **ESEGUITO** | `persistRowChanges`, `editableValues`, `completeStates`, `updateHistoryEntry(entryUid)`, `ImeAction.Next/Done`, calcolatrice acquisto e toggle complete invariati nel diff |
| 5. Build Gradle OK; lint senza nuovi problemi sul file toccato | **ESEGUITO** | `assembleDebug` e `lint` verdi; nessuna entry `GeneratedScreen.kt` nel report lint |

| Campo | Valore |
|-------|--------|
| Stato finale | **`DONE`** |
| Data chiusura | **2026-03-30** |
| Tutti i criteri ✅? | **Sì** |
| Rischi residui | Bassi: smoke manuale wide/narrow utile come controllo visivo finale in caso di futuri ritocchi, ma nessun blocco aperto per questo task |

**Testo di chiusura (sintesi):** micro-follow-up UX eseguito senza scope creep. Il blocco prezzo acquisto ora è sopra qty/vendita, full width e più leggibile; “Acq. vecchio” appare solo quando semanticamente diverso dal prezzo attuale. Build e lint risultano verdi, e la review tecnica non ha rilevato regressioni funzionali.

---

## Handoff (riepilogo)

- **Stato:** `TASK-022` è **`DONE`** (2026-03-30). Nessun nuovo task `ACTIVE` impostato automaticamente.
- **Perimetro toccato:** solo `GeneratedScreen.kt` + tracking (`MASTER-PLAN`, file task). Nessun tocco a DAO, repository, Room, navigation o modelli dati.
- **Check eseguiti:** `assembleDebug` ✅, `lint` ✅ con JBR di Android Studio; baseline TASK-004 non applicabile.
- **Smoke consigliato solo come prudenza futura:** verificare sheet dettaglio riga in wide/narrow, stato read/edit e caso old purchase uguale al current.
