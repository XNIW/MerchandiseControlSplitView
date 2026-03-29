# TASK-012 — CI/CD — setup base

---

## Informazioni generali

| Campo                | Valore                                      |
|----------------------|---------------------------------------------|
| ID                   | TASK-012                                    |
| Stato (backlog / `MASTER-PLAN`) | **`ACTIVE`** (unico task attivo; 2026-03-29) |
| Fase workflow        | **PLANNING** — in attesa di approvazione utente per `PLANNING → EXECUTION` |
| Priorità             | BASSA                                       |
| Area                 | Infrastruttura / CI                         |
| Creato               | 2026-03-29                                  |
| Ultimo aggiornamento | 2026-03-29 — file task creato; task attivato come unico `ACTIVE`; **TASK-011** → `BLOCKED` (smoke manuali pendenti) |

### Allineamento tracking / governance

- **Fonte backlog globale:** `docs/MASTER-PLAN.md` — **TASK-012** = **`ACTIVE`** (unico); **TASK-011** è **`BLOCKED`**, non `ACTIVE`.
- **Fonte perimetro / piano:** questo file (`Fase workflow` = **PLANNING** finché l’utente non approva l’avvio di **EXECUTION**).
- **Regola:** un solo `ACTIVE` alla volta; nessuna **EXECUTION** senza criteri di accettazione approvati + transizione esplicita.

---

## Dipendenze

- **TASK-001** — `DONE` (governance / baseline)
- **TASK-004** — `DONE` (test unitari — contesto qualità prima di automatizzare CI)
- **TASK-005** — `DONE` (test aggiuntivi — stesso contesto)

---

## Scopo

Introdurre una **pipeline CI di base** (tipicamente **GitHub Actions**) che esegua su ogni push/PR i comandi Gradle essenziali per il modulo Android: almeno **`assembleDebug`**, **`lint`** e **`test`** (unit test / JVM), in modo ripetibile e visibile in repository. Il task è **infrastrutturale**: nessun cambio alla logica applicativa salvo quanto strettamente necessario per far passare la CI (es. configurazione JDK, path, permessi workflow).

---

## Contesto

- Oggi non risulta una workflow CI versionata nel repo (verifica in **EXECUTION**).
- Il progetto usa **Gradle Kotlin DSL** (`app/build.gradle.kts`, `gradle/libs.versions.toml`).
- **TASK-004** / **TASK-005** hanno già consolidato una baseline di test JVM: la CI deve poter eseguire `./gradlew test` senza regressioni ingiustificate.
- **TASK-011** è **`BLOCKED`** per smoke manuali; **non** dipende da TASK-012 per lo sblocco.

---

## Non incluso

- Pubblicazione su **Play Store**, **signing** release, **secrets** complessi (keystore) salvo decisione esplicita successiva.
- **Espresso / UI test** su emulator nella CI (costo e fragilità — task futuro se richiesto).
- Risoluzione sistemica del **debito lint** preesistente fuori dal diff del task (eventuale `continue-on-error` o job separato da documentare in Planning se necessario).
- Modifiche funzionali all’app oltre il minimo indispensabile per far girare la pipeline.

---

## File potenzialmente coinvolti (Android / repo)

| File / percorso | Ruolo |
|-----------------|--------|
| `.github/workflows/*.yml` (nuovo) | Definizione pipeline (trigger, job, step Gradle) |
| `gradle/wrapper/gradle-wrapper.properties` | Solo lettura salvo aggiornamento wrapper concordato |
| `app/build.gradle.kts`, `build.gradle.kts` | Solo se servono proprietà esplicite per CI (es. JVM toolchain) — minimo necessario |
| `docs/MASTER-PLAN.md`, questo file | Tracking a fine task |

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Workflow CI versionato (es. sotto `.github/workflows/`) che si attiva su eventi definiti in Planning (es. `push`/`pull_request` su `main` o come deciso) | S + B | — |
| 2 | Job che esegue **`./gradlew assembleDebug`** con esito documentato (verde su branch di prova o PR di test) | B | — |
| 3 | Job che esegue **`./gradlew lint`** — se il lint globale fallisce per debito preesistente, la decisione (fallire sempre vs. tolleranza documentata) deve essere **esplicita** nel file task e in commento/workflow | B / S | — |
| 4 | Job che esegue **`./gradlew test`** (unit test JVM) — esito verde salvo nuove regressioni introdotte dal task | B | — |
| 5 | Documentazione minima nel file task: come interpretare i fallimenti, JDK usato, eventuali variabili `GRADLE_OPTS` / cache | S | — |
| 6 | Nessun secret obbligatorio per la sola pipeline **debug/lint/test** salvo quanto richiesto dall’hosting (es. token solo se necessario — da evitare se possibile) | S | — |

Legenda: B=Build/CI run, S=Static/review documentale

> Checklist aggiuntiva: rispettare **Definition of Done** e governance in `docs/MASTER-PLAN.md` ove applicabile.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Piattaforma CI prescelta: **GitHub Actions** (allineata al tipico hosting Git del repo) | Standard, integrazione nativa | 2026-03-29 |
| 2 | Scope MVP: **assembleDebug** + **lint** + **test** | Copre build, static analysis, regressione JVM | 2026-03-29 |
| 3 | **EXECUTION** solo dopo approvazione utente del Planning in questo file | `AGENTS.md` / `CLAUDE.md` | 2026-03-29 |

---

## Planning (Claude) — piano iniziale

### Analisi

- Il repository è un progetto **Android Gradle** standard; la CI dovrà usare un’azione o immagine con **JDK** compatibile con il wrapper Gradle (versione da verificare in `gradle-wrapper.properties` in EXECUTION).
- I comandi locali noti da altri task usano talvolta **`JAVA_HOME`** esplicito (Android Studio JBR); in CI si userà il JDK fornito dall’runner o da **`actions/setup-java`** con distribuzione concordata (es. Temurin).

### Piano di esecuzione (bozza — da rifinire in EXECUTION)

1. Aggiungere workflow sotto **`.github/workflows/`** (nome file coerente, es. `ci.yml`).
2. Trigger: almeno **`pull_request`** e/o **`push`** sui branch principali (da confermare con il team).
3. Step: checkout, **setup-java** (versione allineata al progetto), cache Gradle opzionale, **`./gradlew assembleDebug`**, **`./gradlew lint`**, **`./gradlew test`** (ordine e job unico vs. matrice da decidere).
4. Se **`lint`** fallisce per errori preesistenti: documentare in Execution scelta (fix minimo nel perimetro vs. `continue-on-error` temporaneo con issue di follow-up — **no** silenzio non documentato).
5. Aggiornare **Execution** / **MASTER-PLAN** a chiusura con evidenze (link run o output).

### Rischi identificati

- **Lint rosso globale:** mitigazione = policy esplicita nel workflow + documentazione.
- **RAM / timeout su runner gratuiti:** mitigazione = JVM opts ridotti se necessario, documentati.
- **Divergenza JDK locale vs CI:** mitigazione = versione JDK fissata nel workflow e nota nel file task.

---

## Execution

_(Non avviata — solo Planning.)_

---

## Review

_(Dopo EXECUTION.)_

---

## Fix

_(Vuoto)_

---

## Chiusura

_(Vuoto)_

---

## Riepilogo finale

_(Da compilare a fine task)_

---

## Handoff

_(Da compilare a fine task)_
