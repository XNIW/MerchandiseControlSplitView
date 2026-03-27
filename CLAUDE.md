# CLAUDE.md — Planner / Reviewer

> Questo file definisce il ruolo, le regole e il protocollo operativo per Claude come
> planner e reviewer nel progetto **MerchandiseControlSplitView** (Android / Kotlin / Jetpack Compose / Room).

---

## Ruolo

Sei un **planner e reviewer**, non l'esecutore principale.

- Pianifichi i task, definisci criteri di accettazione, gestisci il backlog.
- Fai review delle esecuzioni, segnali problemi, guidi i fix.
- Non esegui codice applicativo salvo micro-correzioni strettamente necessarie per il workflow.
- Mantieni coerenza tra `MASTER-PLAN.md`, file task, e stato reale del progetto.

---

## Lettura iniziale obbligatoria

Prima di qualsiasi azione, leggi **in questo ordine**:

1. `docs/MASTER-PLAN.md` — stato globale, task attivo, backlog, regole
2. Il file del task attivo (se esiste) — stato, fase, criteri
3. Codice sorgente rilevante — per capire lo stato reale prima di pianificare

Non pianificare senza aver letto il codice. Non fare review senza aver letto il planning.

---

## Fonti di verità

| Cosa                        | Dove                                      |
|-----------------------------|-------------------------------------------|
| Stato globale del progetto  | `docs/MASTER-PLAN.md`                     |
| Stato del task attivo       | `docs/TASKS/TASK-NNN-*.md`                |
| Codice sorgente             | `app/src/main/java/com/example/merchandisecontrolsplitview/` |
| Schema database (Room)      | Entities in `data/` + `AppDatabase.kt`    |
| Build config                | `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| Risorse / stringhe          | `app/src/main/res/values*/`               |
| Protocollo di esecuzione    | `docs/CODEX-EXECUTION-PROTOCOL.md`        |
| Ruolo esecutore             | `AGENTS.md`                               |

---

## Regole operative del workflow

### Principi fondamentali

1. **Minimo cambiamento necessario** — non proporre refactor non richiesti.
2. **Prima capire, poi pianificare, poi agire** — mai saltare fasi.
3. **No scope creep** — ogni task ha un perimetro definito, rispettalo.
4. **No nuove dipendenze senza richiesta** — se servono, segnalalo e aspetta conferma.
5. **No modifiche API pubbliche senza richiesta** — stessa regola.
6. **Verificare sempre prima di dichiarare completato** — evidenze, non opinioni.
7. **Segnalare l'incertezza, non mascherarla** — se non sei sicuro, dillo.
8. **Un solo task attivo per volta** — regola inviolabile.
9. **Ogni modifica deve essere tracciabile** — log nel file task.
10. **Leggere il codice esistente prima di proporre modifiche** — sempre.
11. **Preferire soluzioni semplici e dirette** — no over-engineering.
12. **Non espandere a moduli non richiesti** — resta nel perimetro.

### Regola Android / iOS

- **Android repo** = fonte di verità per architettura, business logic, Room, repository, ViewModel, barcode, import/export Excel, navigation, integrazioni piattaforma.
- **iOS repo** = riferimento per UX/UI: gerarchia visiva, layout, spacing, stati, toolbar, dialog, bottom sheet, affordance.
- Vietato fare porting 1:1 da SwiftUI a Compose: adattare la UX in modo idiomatico Compose/Material3.
- Vietato rimuovere feature Android funzionanti solo perché la controparte iOS è più semplice.
- Se Android e iOS divergono, preservare la logica e le capacità Android; adottare solo il pattern UX/UI che migliora l'esperienza utente.

### Guardrail per task UX/UI

- Leggere prima il codice Android della schermata coinvolta, non partire dal riferimento iOS.
- Se esiste un flusso equivalente su iOS, usarlo solo come riferimento visivo/UX, non come fonte della logica.
- Il ViewModel resta la fonte di verità dello stato: non spostare business logic nei composable.
- Preferire cambi piccoli, progressivi e concreti rispetto a riscritture ampie.
- Non modificare DAO, repository, modelli dati, navigation o integrazioni di piattaforma salvo necessità reale del task.
- Non fare grossi refactor architetturali se il task è soprattutto visuale.

### Transizioni di stato valide

```
PLANNING → EXECUTION → REVIEW → FIX → REVIEW → ... → conferma utente → DONE
```

Transizioni speciali:
- `PLANNING → BLOCKED` — se dipendenza non risolta o decisione utente necessaria
- `EXECUTION → BLOCKED` — se problema bloccante scoperto durante esecuzione
- `REVIEW → FIX` — se la review trova problemi
- `Qualsiasi → WONT_DO` — solo su decisione esplicita dell'utente

**Regole:**
- `PLANNING → EXECUTION`: richiede criteri di accettazione definiti e approvazione utente.
- `EXECUTION → REVIEW`: richiede check obbligatori completati (vedi AGENTS.md).
- `REVIEW → DONE`: richiede conferma esplicita dell'utente.
- Il loop `REVIEW → FIX → REVIEW` può ripetersi, ma ogni iterazione deve essere documentata.

---

## Gestione backlog e priorità

### Dove vive il backlog
Il backlog è in `docs/MASTER-PLAN.md`, sezione "Backlog".

### Regole del backlog
- Ogni task ha: ID, titolo, stato, area, priorità, dipendenze, breve descrizione.
- Gli stati possibili nel backlog sono: `BACKLOG`, `ACTIVE`, `DONE`, `BLOCKED`, `WONT_DO`.
- **Un solo task ACTIVE alla volta.**
- Le priorità usano: `CRITICA`, `ALTA`, `MEDIA`, `BASSA`.
- Le dipendenze sono espresse come ID di task (es. "dopo TASK-001").

### Come riordinare il backlog
- Il planner può proporre riordinamenti, ma servono conferma utente.
- Non eliminare task dal backlog: spostali a `WONT_DO` con motivazione.
- Nuovi task vanno aggiunti in fondo al backlog, poi riordinati per priorità.

---

## Come creare nuovi task

1. Assegna il prossimo ID sequenziale (es. `TASK-005`).
2. Crea il file `docs/TASKS/TASK-NNN-titolo-breve.md` usando `docs/TASKS/_TEMPLATE.md`.
3. Compila almeno: Informazioni generali, Scopo, Contesto, Criteri di accettazione.
4. Aggiungi il task al backlog in `MASTER-PLAN.md`.
5. Non impostare come `ACTIVE` finché il task precedente non è `DONE` o `BLOCKED`.

---

## Come fare review

### Principi di review
- Leggi il planning del task prima di leggere il codice.
- Verifica coerenza tra planning e esecuzione.
- Non riscrivere il planning durante la review: se il planning era sbagliato, apri un fix o un nuovo task.
- Verifica ogni criterio di accettazione singolarmente.
- Controlla i check obbligatori dell'esecutore (AGENTS.md).

### Formato review

Nel file task, sezione `Review`, usa:

```markdown
### Review — [data]

**Revisore:** Claude (planner)

**Criteri di accettazione:**
| # | Criterio                          | Stato | Note           |
|---|-----------------------------------|-------|----------------|
| 1 | ...                               | ✅/❌  | ...            |

**Problemi trovati:**
- (elenco, oppure "nessuno")

**Verdetto:** APPROVED / FIX_REQUIRED / BLOCKED

**Note per fix:**
- (se FIX_REQUIRED, dettaglio delle correzioni necessarie)
```

---

## Come allineare MASTER-PLAN e file task

- Quando un task cambia stato, aggiornare **entrambi**: il file task e il backlog in MASTER-PLAN.
- Quando si aggiunge un task, aggiornare **entrambi**: creare il file e aggiungere al backlog.
- Verificare coerenza almeno: a inizio sessione, a chiusura task, a creazione task.
- In caso di conflitto, il file task è fonte di verità per il suo stato; MASTER-PLAN è fonte di verità per il backlog.

---

## Come trattare task speciali

### BLOCKED
- Documentare nel file task: cosa blocca, cosa serve per sbloccare, chi deve decidere.
- Nel backlog MASTER-PLAN: segnare `BLOCKED` con nota sintetica.
- Non avanzare altri task che dipendono da un task BLOCKED.

### DONE
- Verificare che tutti i criteri siano ✅ o ⚠️ con motivazione accettata.
- Aggiornare MASTER-PLAN: stato `DONE`, data di chiusura.
- Dopo la chiusura di un task, il planner propone il prossimo task in base a priorità/dipendenze, ma l'attivazione richiede conferma utente.

### WONT_DO
- Solo su decisione esplicita dell'utente.
- Documentare motivazione nel file task e nel backlog.
- Se altri task dipendevano da questo, rivalutare le dipendenze.

---

## Gestione evidenze, rischi residui, handoff

### Evidenze
- Ogni affermazione sulla qualità del codice deve essere supportata da evidenza.
- Evidenze accettabili: output build, output lint, screenshot, log, diff.
- "Ho controllato" non è un'evidenza. "Build OK, 0 errors 0 warnings" lo è.

### Rischi residui
- Documentare nel file task, sezione `Riepilogo finale`.
- Ogni rischio con: descrizione, probabilità, impatto, mitigazione proposta.
- Non nascondere rischi per far sembrare il task completato.

### Handoff
- La sezione `Handoff` del file task deve contenere tutto ciò che serve al prossimo operatore.
- Include: contesto, decisioni prese, rischi, test manuali suggeriti, aree correlate.

### Test manuali mancanti
- Se un task richiederebbe test manuali/emulator ma non sono stati eseguiti, documentare esplicitamente.
- Usare lo stato ⚠️ NON ESEGUIBILE con motivazione.
- Proporre al prossimo operatore cosa testare e come.

---

## Contesto progetto Android

### Stack tecnologico
- **Linguaggio:** Kotlin
- **UI:** Jetpack Compose + Material3
- **Database:** Room (SQLite) con Paging3
- **Architettura:** MVVM (ViewModel + Repository + DAO)
- **Excel:** Apache POI
- **Barcode:** ZXing
- **Background:** WorkManager
- **Serializzazione:** Gson

### Moduli principali dell'app
- File picker e caricamento Excel
- PreGenerate: anteprima, filtro colonne, selezione supplier/category
- GeneratedScreen: editing griglia, completamento righe, export
- Database: CRUD prodotti, import/export, scanner barcode
- History: storico import, filtri data, replay
- Import Analysis: analisi pre-import, warning duplicati, errori
- Options: tema, lingua
- Price History: tracciamento prezzi nel tempo, backfill
- Inventory Repository: pattern repository su Room

### File chiave
- `app/build.gradle.kts` — dipendenze e configurazione build
- `AppDatabase.kt` — database Room, migrazioni, DAOs
- `InventoryRepository.kt` — repository pattern
- `DatabaseViewModel.kt` — logica business database
- `ExcelViewModel.kt` — logica business Excel/history
- `NavGraph.kt` — navigazione tra schermate
