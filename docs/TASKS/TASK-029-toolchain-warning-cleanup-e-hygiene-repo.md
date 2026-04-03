# TASK-029 — Toolchain warning cleanup e hygiene repo

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-029                   |
| Stato              | PLANNING                   |
| Priorità           | MEDIA                      |
| Area               | Build / Governance / Toolchain |
| Creato             | 2026-04-03                 |
| Ultimo aggiornamento | 2026-04-03               |
| Tracking `MASTER-PLAN` | **`BACKLOG`**          |

---

## Dipendenze

- TASK-012 (DONE)

---

## Scopo

Ridurre il debito toolchain e di hygiene emerso dall’audit 2026-04-03: warning AGP/Kotlin preesistenti, flag deprecati in `gradle.properties`, dipendenze tooling ridondanti e artefatti `.DS_Store` dentro `app/src`. L’obiettivo è migliorare mantenibilità e readiness agli upgrade senza introdurre regressioni di build.

---

## Contesto

- `gradle.properties` contiene più flag AGP/Kotlin deprecati o in dismissione.
- `app/build.gradle.kts` dichiara `androidx.ui.tooling` anche in `implementation`, oltre che in `debugImplementation`.
- La repo contiene `.DS_Store` sotto `app/src`.
- Il progetto builda oggi, quindi il task è quality/toolchain, non bugfix utente immediato.

---

## Non incluso

- Upgrade grandi di librerie o AGP oltre il minimo necessario.
- Refactor di business logic o UI.
- Modifiche speculative non motivate da warning reali o hygiene concreta.

---

## File potenzialmente coinvolti

- `gradle.properties`
- `app/build.gradle.kts`
- `.gitignore` — solo se utile per prevenire recidive
- file `.DS_Store` in repo
- documentazione governance minima se serve annotare warning residui accettati

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | I flag AGP/Kotlin deprecati affrontabili senza regressioni vengono rimossi o sostituiti | B + S | — |
| 2 | Le dipendenze tooling ridondanti vengono pulite dove non necessarie | B + S | — |
| 3 | Gli artefatti `.DS_Store` sotto `app/src` vengono rimossi e prevenuti ove opportuno | S | — |
| 4 | `./gradlew assembleDebug`, `./gradlew lint` e test JVM restano verdi dopo il cleanup | B + S | — |
| 5 | Ogni warning residuo non rimosso viene documentato con motivazione chiara | S | — |

Legenda: B=Build, S=Static, M=Manual

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task separato dai fix prodotto | Oggi non blocca l’utente finale ma impatta upgrade futuri | 2026-04-03 |
| 2 | Nessun mega-upgrade di toolchain in un solo task | Ridurre rischio non necessario | 2026-04-03 |

---

## Planning (Claude)

### Analisi

Il progetto è verde ma regge ancora su configurazioni AGP/Kotlin chiaramente temporanee. L’audit mostra che vale la pena fare un cleanup mirato prima di futuri upgrade o regressioni più costose.

### Piano di esecuzione

1. Rimuovere o sostituire i flag deprecati sicuri.
2. Pulire dipendenze tooling e hygiene repo.
3. Rieseguire build/lint/test e documentare gli eventuali warning residui accettati.

### Rischi identificati

- Un cleanup troppo aggressivo può introdurre regressioni di build.
- Alcuni warning potrebbero dipendere da combinazioni AGP/versioni non risolvibili nel perimetro minimo del task.

---

## Execution

_(Non avviata — in PLANNING.)_

---

## Review

_(Dopo EXECUTION.)_

---

## Fix

_(Se necessario.)_

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

_(Al termine.)_

---

## Handoff

- Nuovo task post-audit 2026-04-03.
- Tenere il perimetro stretto: warning reali e hygiene concreta, non upgrade di piattaforma indiscriminati.
