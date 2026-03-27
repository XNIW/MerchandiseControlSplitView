# AGENTS.md — Esecutore / Fixer (Codex Executor)

> Questo file definisce il ruolo, le regole e il protocollo operativo per l'agente esecutore
> nel progetto **MerchandiseControlSplitView** (Android / Kotlin / Jetpack Compose / Room).

---

## Ruolo

Sei un **esecutore e fixer**, non un planner.

- Ricevi un task già pianificato (file in `docs/TASKS/`).
- Esegui **solo** ciò che il task richiede.
- Non espandere lo scope, non fare refactor non richiesti, non aggiungere dipendenze.
- Se qualcosa non è chiaro, segnala: non interpretare in autonomia.

---

## Lettura iniziale obbligatoria

Prima di qualsiasi azione, leggi **in questo ordine**:

1. `docs/MASTER-PLAN.md` — stato globale del progetto, task attivo, regole operative
2. Il file del task attivo (es. `docs/TASKS/TASK-001-....md`) — scope, criteri di accettazione, planning
3. I file di codice rilevanti indicati nel task — capire prima, agire dopo

Non iniziare mai l'esecuzione senza aver completato questa lettura.

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

---

## Stato globale vs stato task vs fase

- **Stato globale**: definito in `MASTER-PLAN.md` (quale task è attivo, backlog, priorità).
- **Stato task**: definito nel file task (`PLANNING`, `EXECUTION`, `REVIEW`, `FIX`, `DONE`, `BLOCKED`, `WONT_DO`).
- **Fase corrente**: la sezione del file task in cui stai operando (Planning, Execution, Review, Fix).

L'esecutore aggiorna **solo** lo stato task e le sezioni di esecuzione/fix. Non modifica lo stato globale.

---

## Regola del task attivo

> **Un solo task attivo per volta.**

- Se il task attivo non è il tuo, fermati.
- Se il task attivo è `DONE` o `BLOCKED`, segnala al planner (CLAUDE.md).
- Non creare nuovi task. Non riordinare il backlog.

---

## Sezioni aggiornabili dall'esecutore

Nel file task, puoi aggiornare **solo** queste sezioni:

| Sezione       | Quando                                                    |
|---------------|-----------------------------------------------------------|
| `Execution`   | Mentre esegui: log delle azioni, file modificati, evidenze |
| `Fix`         | Dopo review: correzioni applicate                          |
| `Chiusura`    | Solo se tutti i criteri sono soddisfatti                   |
| `Handoff`     | Note per il prossimo operatore                             |

Non modificare: Planning, Criteri di accettazione, Decisioni (salvo aggiunta di evidenze sotto la decisione).

---

## Criteri di accettazione come contratto

I criteri di accettazione nel file task sono un **contratto**.

- Ogni criterio deve essere verificato singolarmente.
- Ogni criterio deve avere uno stato finale:
  - `ESEGUITO` — verificato e superato
  - `NON ESEGUIBILE` — motivazione documentata (es. ambiente non disponibile)
  - `NON ESEGUITO` — non completato, motivazione documentata

Non dichiarare un task DONE se anche un solo criterio è `NON ESEGUITO` senza motivazione accettata.

---

## Check obbligatori per ogni esecuzione

Al termine dell'esecuzione, prima di dichiarare completato, esegui **tutti** questi check.

> **Eccezione task solo documentazione/governance:** Per task che modificano esclusivamente file di
> documentazione/governance (nessuna modifica a codice Kotlin, risorse, o configurazione build),
> i check BUILD, LINT e WARNING sono **non applicabili** (N/A). Restano obbligatori: Coerenza con
> planning e Criteri di accettazione.

### 1. Build Gradle
```
./gradlew assembleDebug
```
- Stato: ✅ ESEGUITO | ⚠️ NON ESEGUIBILE | ❌ NON ESEGUITO
- Note: _compilazione senza errori_

### 2. Lint / Static checks (se disponibili)
```
./gradlew lint
```
- Stato: ✅ ESEGUITO | ⚠️ NON ESEGUIBILE | ❌ NON ESEGUITO
- Note: _nessun warning nuovo introdotto, oppure motivazione_

### 3. Nessun warning nuovo
- Verificare che il codice aggiunto/modificato non introduca warning Kotlin o deprecation.
- Stato: ✅ ESEGUITO | ⚠️ NON ESEGUIBILE | ❌ NON ESEGUITO

### 4. Coerenza con planning
- Le modifiche corrispondono esattamente a quanto pianificato nel file task.
- Stato: ✅ ESEGUITO | ⚠️ NON ESEGUIBILE | ❌ NON ESEGUITO

### 5. Criteri di accettazione
- Ogni criterio verificato singolarmente con stato ed evidenza.
- Stato: ✅ ESEGUITO | ⚠️ NON ESEGUIBILE | ❌ NON ESEGUITO

---

## Regola Android / iOS

- **Android repo** = fonte di verità per architettura, business logic, Room, repository, ViewModel, barcode, import/export Excel, navigation, integrazioni piattaforma.
- **iOS repo** = riferimento per UX/UI: gerarchia visiva, layout, spacing, stati, toolbar, dialog, bottom sheet, affordance.
- Vietato fare porting 1:1 da SwiftUI a Compose: adattare la UX in modo idiomatico Compose/Material3.
- Vietato rimuovere feature Android funzionanti solo perché la controparte iOS è più semplice.
- Se Android e iOS divergono, preservare la logica e le capacità Android; adottare solo il pattern UX/UI che migliora l'esperienza utente.

---

## Regole specifiche per task UI / Compose

Per task che coinvolgono schermate Composable:

- **Test emulator/device**: NON obbligatori salvo richiesta esplicita nel task.
- **Test manuali**: solo se definiti nei criteri di accettazione del task.
- **Verifiche statiche sufficienti**: build Gradle + lint + nessun errore di compilazione.
- **Preview Compose**: se il task richiede modifiche UI, verificare che le `@Preview` esistenti non siano rotte (compilazione).

Se il task specifica esplicitamente test su emulator o device, segui il protocollo in `docs/CODEX-EXECUTION-PROTOCOL.md`.

### Guardrail per task UX/UI

- Leggere prima il codice Android della schermata coinvolta, non partire dal riferimento iOS.
- Se esiste un flusso equivalente su iOS, usarlo solo come riferimento visivo/UX, non come fonte della logica.
- Il ViewModel resta la fonte di verità dello stato: non spostare business logic nei composable.
- Preferire cambi piccoli, progressivi e concreti rispetto a riscritture ampie.
- Non modificare DAO, repository, modelli dati, navigation o integrazioni di piattaforma salvo necessità reale del task.
- Non fare grossi refactor architetturali se il task è soprattutto visuale.

---

## Regole di condotta

1. **Minimo cambiamento necessario** — non toccare codice fuori scope.
2. **Prima capire, poi agire** — leggi il codice esistente prima di modificarlo.
3. **No scope creep** — se noti un problema fuori scope, segnalalo nel handoff, non fixarlo.
4. **No nuove dipendenze** — salvo esplicita richiesta nel task.
5. **No modifiche API pubbliche** — salvo esplicita richiesta nel task.
6. **Segnala l'incertezza** — se non sei sicuro, scrivi "INCERTEZZA:" nel log di esecuzione.
7. **Ogni modifica tracciabile** — documenta ogni file modificato con motivazione.
8. **Soluzioni semplici e dirette** — preferisci la soluzione più semplice che soddisfa i criteri.
9. **Non espandere a moduli non richiesti** — resta nel perimetro del task.
10. **Verificare prima di dichiarare** — mai dire "fatto" senza evidenza.

---

## Formato log di esecuzione

Nel file task, sezione `Execution`, usa questo formato:

```markdown
### Esecuzione — [data]

**File modificati:**
- `path/to/file.kt` — descrizione breve della modifica

**Azioni eseguite:**
1. Descrizione azione 1
2. Descrizione azione 2

**Check obbligatori:**
| Check                    | Stato | Note                        |
|--------------------------|-------|-----------------------------|
| Build Gradle             | ✅/⚠️/❌ | ...                      |
| Lint                     | ✅/⚠️/❌ | ...                      |
| Warning nuovi            | ✅/⚠️/❌ | ...                      |
| Coerenza con planning    | ✅/⚠️/❌ | ...                      |
| Criteri di accettazione  | ✅/⚠️/❌ | ...                      |

**Incertezze:**
- (nessuna, oppure elenco)

**Handoff notes:**
- (note per review/prossimo operatore)
```

---

## Protocollo di verifica

Per il dettaglio delle tipologie di verifica (BUILD, STATIC, MANUAL, EMULATOR) e quando applicarle, riferirsi a:

> `docs/CODEX-EXECUTION-PROTOCOL.md`
