# TASK-008 — Gestione errori e UX feedback

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-008                   |
| Stato              | PLANNING                   |
| Priorità           | BASSA                      |
| Area               | UX / Error handling        |
| Creato             | 2026-03-28                 |
| Ultimo aggiornamento | 2026-03-28 (attivazione da backlog dopo **TASK-007** `DONE`; planning iniziale consolidato) |

---

## Dipendenze

- **TASK-001** (`DONE`) — bootstrap governance
- **TASK-007** (`DONE`, 2026-03-28) — precedente task attivo; nessuna dipendenza tecnica obbligatoria oltre governance

---

## Scopo

Eseguire un **audit** dei messaggi e dei pattern di feedback **visibili all’utente** quando qualcosa va storto: **Snackbar**, **dialog**, stati **errore** collegati a `UiState` (o equivalenti), testi **hardcoded** vs **stringhe risorse**, **localizzazione** (en / it / es / zh dove l’app è già tradotta).

Obiettivo: **chiarezza**, **coerenza** del tono e **parità di localizzazione** laddove mancante — **senza** redesign di schermate e **senza** cambiare architettura dati.

---

## Contesto

L’app usa Compose + Material3 e diversi ViewModel che espongono `UiState` con messaggi d’errore. Alcuni percorsi (import Excel, database, export, history) sono complessi; messaggi generici, duplicati o solo in inglese degradano la fiducia utente. **iOS** (`iOSMerchandiseControl`) resta solo **riferimento visivo/UX** per tono e gerarchia del feedback, non fonte di logica.

---

## Non incluso

- Redesign layout / nuovi flussi / navigation
- Modifiche a **DAO**, **repository**, **schema Room**, **NavGraph** salvo emergenza dimostrata (es. messaggio errato causato da eccezione non mappata — valutare caso per caso in Execution)
- Task **TASK-006** (robustezza errori *generici* import Excel a livello `ExcelUtils` / `ImportAnalysis`) — resta backlog separato; TASK-008 può incrociare ma non assorbirne lo scope
- **TASK-019** (localizzazione messaggi PriceHistory full-import) — backlog dedicato

---

## File potenzialmente coinvolti (indicativi — da confermare in Execution)

- `app/src/main/java/.../ui/screens/*.kt` — `DatabaseScreen`, `GeneratedScreen`, `HistoryScreen`, `ImportAnalysisScreen`, `FilePickerScreen`, `PreGenerateScreen`, `OptionsScreen`, ecc.
- `app/src/main/java/.../viewmodel/*ViewModel.kt` — origine di stringhe passate a UI
- `app/src/main/res/values/strings.xml`, `values-it/`, `values-es/`, `values-zh/` (o path effettivi nel repo)
- Eventuali `Theme` / componenti dialog riusati

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | Inventario documentato in Execution: schermate / flussi con errori user-visible (Snackbar, dialog, banner, `UiState.Error`) e problemi trovati (vago, EN-only, inconsistente) | S | — |
| 2 | Correzioni **minime** applicate: messaggi resi comprensibili e coerenti; stringhe spostate in risorse dove mancava; traduzioni allineate per le lingue supportate **nel perimetro toccato** | B/S | — |
| 3 | `./gradlew assembleDebug` OK; `./gradlew lint` senza nuovi warning non motivati sulle aree modificate | B/S | — |
| 4 | Nessuna regressione funzionale documentata; nessun cambio a DAO/repository/navigation salvo motivazione esplicita nel log Execution | S | — |

Legenda: B=Build, S=Static, M=Manual, E=Emulator

> Riferimento: checklist **Definition of Done — task UX/UI** in `docs/MASTER-PLAN.md` dove applicabile.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task attivato come unico **ACTIVE** dopo chiusura **TASK-007** `DONE` | Ordine backlog / priorità prodotto | 2026-03-28 |

---

## Planning (Claude)

### 1. Mappa concreta (prima di EXECUTION)

1. **Cercare nel codice** (grep / lettura mirata): `UiState.Error`, `Snackbar`, `showSnackbar`, `AlertDialog`, `stringResource(R.string.`, concatenazioni con `message`, `Toast` (se presente).
2. **Per ogni schermata principale** (Database, Generated, History, Import analysis, file picker / pre-generate, options): elencare *come* l’errore arriva all’utente (snackbar vs dialog vs stato inline) e se il testo viene da `strings.xml` o hardcoded.
3. **Confronto locale:** per le stringhe toccate, verificare presenza in `values-it`, `values-es`, `values-zh` (stessi `name`); segnare gap.

### 2. Regole di intervento (Execution)

- **Preferenza:** riusare pattern già presenti (es. stesso `SnackbarHost` / durata / azione dismiss coerente con `DatabaseScreen`).
- **Messaggi:** utente finale — evitare stack trace o dettagli interni; dove serve dettaglio tecnico, tenere log in `Log` e messaggio UI generico + codice opzionale solo se già pattern in app.
- **iOS:** solo confronto qualitativo (chiarezza, brevità), nessun porting 1:1 obbligato.
- **Non** allargare il task a refactor ViewModel o spostamento logica errori salvo micro-accoppiamento stringa ↔ risorsa.

### 3. Esclusioni esplicite

- Non riscrivere schermate per “bellezza”.
- Non unificare tutti gli errori in un unico componente globale salvo evidenza che sia il modo più piccolo per rispettare i criteri.

### 4. Rischi residui attesi

- Copertura **non** esaustiva di ogni singola riga in un solo task: documentare in Handoff ciò che resta (es. schermate non toccate).
- Alcuni messaggi potrebbero dipendere da eccezioni propagate dal repository — se il fix richiede touch repository, documentare e valutare se spezzare in task tecnico.

---

## Execution

_(Da compilare dall'esecutore)_

---

## Review

_(Da compilare dal reviewer)_

---

## Fix

_(Da compilare dall'esecutore se necessario)_

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | —        |
| Data chiusura          | —        |
| Tutti i criteri ✅?    | —        |
| Rischi residui         | —        |

---

## Riepilogo finale

_(Da compilare a chiusura)_

---

## Handoff

_(Da compilare a chiusura)_
