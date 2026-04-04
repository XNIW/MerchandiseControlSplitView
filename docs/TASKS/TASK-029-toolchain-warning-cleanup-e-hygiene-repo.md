# TASK-029 — Toolchain warning cleanup e hygiene repo

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-029                   |
| Stato              | DONE                       |
| Priorità           | MEDIA                      |
| Area               | Build / Governance / Toolchain |
| Creato             | 2026-04-03                 |
| Ultimo aggiornamento | 2026-04-03 (DONE — Review APPROVED) |
| Tracking `MASTER-PLAN` | **`DONE`**             |

---

## Dipendenze

- TASK-012 (DONE)

---

## Scopo

Ridurre il debito toolchain e di hygiene emerso dall’audit 2026-04-03: warning AGP/Kotlin preesistenti, flag deprecati in `gradle.properties`, dipendenze tooling ridondanti e artefatti `.DS_Store` dentro `app/src`. L’obiettivo è migliorare mantenibilità e readiness agli upgrade senza introdurre regressioni di build.

---

## Contesto

- `gradle.properties` contiene più flag AGP/Kotlin deprecati o in dismissione (righe 24–33, 10 flag AGP; versione corrente: AGP 9.1.0, Kotlin 2.3.20).
- `app/build.gradle.kts` dichiara `androidx.ui.tooling` sia in `implementation` (riga 60) sia in `debugImplementation` (riga 107): la prima è ridondante e include debug tooling nel release.
- **`.DS_Store` sotto `app/src`:** esistono su disco (10 file) ma **non** sono tracciati da git; `.gitignore` (riga 10) li copre già. Nessuna azione di rimozione dal versionamento è necessaria.
- Il progetto builda oggi, quindi il task è quality/toolchain, non bugfix utente immediato.

---

## Non incluso

- Upgrade grandi di librerie o AGP oltre il minimo necessario.
- Migrazione a AGP built-in Kotlin / new DSL (richiede task dedicato; `builtInKotlin=false` e `newDsl=false` rinviati).
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
| 1 | I flag AGP/Kotlin deprecati affrontabili senza regressioni vengono rimossi o sostituiti | B + S | ✅ 5/7 rimossi; 2 rinviati con motivazione (cascading) |
| 2 | Le dipendenze tooling ridondanti vengono pulite dove non necessarie | B + S | ✅ `implementation(ui.tooling)` rimossa |
| 3 | Gli artefatti `.DS_Store` sotto `app/src` non sono tracciati da git e `.gitignore` li previene (verificare) | S | ✅ Confermato: 0 tracciati, gitignore attivo |
| 4 | `./gradlew assembleDebug`, `./gradlew lint` e test JVM restano verdi dopo il cleanup | B + S | ✅ Tutti verdi |
| 5 | Ogni warning residuo non rimosso viene documentato con motivazione chiara | S | ✅ Classificazione completa in Execution §6 |

Legenda: B=Build, S=Static, M=Manual

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task separato dai fix prodotto | Oggi non blocca l’utente finale ma impatta upgrade futuri | 2026-04-03 |
| 2 | Nessun mega-upgrade di toolchain in un solo task | Ridurre rischio non necessario | 2026-04-03 |
| 3 | `builtInKotlin=false`, `newDsl=false` e plugin `kotlin.android` rinviati | Rimozione tentata in Execution: cascading (KSP sourceSets, class cast DSL). Stesso filone di migrazione AGP built-in Kotlin, richiede task dedicato | 2026-04-03 |

---

## Planning (Claude)

### Analisi

Il progetto è verde ma regge ancora su configurazioni AGP/Kotlin chiaramente temporanee. L’audit suggerisce un cleanup mirato prima di upgrade o regressioni più costose. Approccio conservativo: mappare warning e artefatti con baseline, intervenire solo dove beneficio e rischio sono chiari, documentare il resto.

### Validazione planning vs repo (2026-04-03)

**Verifiche eseguite:** `gradle.properties`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `.gitignore`, `git ls-files ‘*.DS_Store’`, struttura test JVM.

**Correzioni applicate al planning:**

1. **`.DS_Store` (criterio 3):** l’assunzione originale ("repo contiene .DS_Store sotto app/src" da rimuovere dal versionamento) era **errata**. `git ls-files ‘*.DS_Store’` restituisce 0 risultati. I 10 file `.DS_Store` esistono su disco ma `.gitignore` riga 10 (`.DS_Store`, senza `/`) li esclude già a qualsiasi profondità. **Nessuna azione di rimozione o modifica `.gitignore` è necessaria.** L’execution deve solo verificare e documentare.

2. **`gradle.properties` (criterio 1):** righe 24–33 contengono 10 flag AGP specifici (AGP 9.1.0, Kotlin 2.3.20, `compileSdk`/`targetSdk` = 36). Da distinguere:
   - **7 flag effettivamente deprecati** (confermato da `--warning-mode all`): `resvalues`, `defaultTargetSdkToCompileSdkIfUnset`, `enableAppCompileTimeRClass`, `usesSdkInManifest.disallowed`, `r8.optimizedResourceShrinking`, `builtInKotlin`, `newDsl`
   - **3 flag presenti ma non deprecati** (nessun warning): `uniquePackageNames`, `dependency.useConstraints`, `r8.strictFullModeForKeepRules`

3. **`app/build.gradle.kts` (criterio 2):** la ridondanza è confermata: riga 60 `implementation(libs.androidx.ui.tooling)` + riga 107 `debugImplementation(libs.androidx.ui.tooling)`. L’`implementation` include debug tooling nel release APK. Fix: rimuovere riga 60 (mantenere solo `debugImplementation`).

4. **Test JVM (criterio 4):** test esistono in `app/src/test/.../data/`, `testutil/`, `util/`, `viewmodel/`. Il task **non** tocca aree business logic coperte da TASK-004 (repository, ViewModel, import/export), quindi la baseline TASK-004 non è specificamente triggherata. `./gradlew testDebugUnitTest` è sufficiente come check di regressione generale.

5. **`.gitignore`:** nessuna modifica necessaria (regola `.DS_Store` già presente e funzionante).

### Assunzioni e precondizioni

- **Build di partenza:** il progetto è **buildabile** all’ingresso in Execution (`assembleDebug` OK), salvo stato `BLOCKED` documentato.
- **Warning:** si intendono **preesistenti** rispetto al task; presenza e messaggi vanno **riconfermati** con la baseline, non solo citati da audit passati.
- **Non-obiettivo:** il task **non** mira a cambiare **comportamento runtime** né **UX** dell’app; regressioni prodotto fuori perimetro vanno escalate, non assorbite come scope creep.
- **Perimetro:** tutto ciò che **non** è direttamente legato ai criteri **1–5** resta **fuori scope** (backlog separato se opportuno).

### Matrice rapida: file → verifica attesa → criteri

| File / artefatto | Verifica attesa (dopo intervento + vs baseline) | Criteri |
|------------------|--------------------------------------------------|---------|
| `gradle.properties` | Build OK; deprecazioni affrontate o classificate come residuo motivato; nessuna modifica senza traccia in Execution | **1**, **4**, **5** |
| `app/build.gradle.kts` | Tooling non ridondante sul classpath necessario; release vs debug coerenti; preview/debug non rotti | **2**, **4**, **5** |
| `.gitignore` | **Nessuna modifica prevista**: regola `.DS_Store` già presente (riga 10) e funzionante a qualsiasi profondità | **3**, **5** |
| `.DS_Store` sotto `app/src` | **Già non tracciati** da git; verifica in execution e documentazione (nessuna azione operativa) | **3** |
| Documentazione residui (se nel perimetro task) | Ogni warning accettato con **motivazione**; “fuori scope” distinto da “incompleto” | **5** |

*(Tabella di accelerazione per Review: mapping immediato verifica ↔ criterio.)*

### Obiettivi operativi

Ogni intervento deve mappare esplicitamente almeno un criterio di accettazione; modifiche senza tale legame restano fuori scope.

| Intervento (perimetro task) | Criteri collegati |
|-----------------------------|-------------------|
| Rimozione/sostituzione flag AGP/Kotlin deprecati in `gradle.properties` (solo dove sicuro) | **1**, **4**, **5** |
| Pulizia dipendenze tooling ridondanti in `app/build.gradle.kts` (es. `androidx.ui.tooling` solo dove serve) | **2**, **4**, **5** |
| Verifica che `.DS_Store` sotto `app/src` non siano tracciati (già confermato) e documentazione; nessuna modifica a `.gitignore` necessaria | **3** |
| Verifica build/lint/test JVM e confronto con baseline | **4**, **5** |
| Documentazione warning residui e motivazioni | **5** |

Obiettivo trasversale: **esecuzione a basso rischio** — blocchi revertibili, nessun mega-upgrade.

### Flusso operativo, evidenze e log vs task

**Diff ammesso:** `gradle.properties`, `app/build.gradle.kts`, doc governance **solo** se nel perimetro. Modifica → criterio **1–5**. **Non previste** modifiche a `.gitignore` né rimozioni `.DS_Store` (già non tracciati).

**A / B / C:** **A** = baseline pre-modifica; **B** = stesso schema comandi/JVM in chiusura; **C** = sintesi nel file task (OK/KO, mod→criterio, classificazioni, dubbi 1 riga). Log = dettaglio; **C** non ripete il log (anti-rumore).

**Fasi (ordine nel log):** **1·A** `assembleDebug` + `lint` + JVM (**Test JVM**). **2** `gradle.properties` (remov./sost./temp. + motivo), `build.gradle.kts` (tooling solo con evidenza), hygiene, opz. `assembleDebug`. **3** diff ⊆ ammessi. **4·B** ripeti comandi/JVM (sovrainsieme motivato); KO/instabilità → **KO / instabilità**. **5** tabella `voce|baseline|finale|nota`. **6** classificazione + residui (**5**).

### Test JVM (regole operative)

Suite **minima ma sufficiente**; se incerto → TASK-004 / `AGENTS.md` o `./gradlew test` con **motivo**. **No** test nuovi salvo necessità stretta.

### KO validazione e output instabili

Regola unica: **no scope creep.** **KO** — causa **non** dimostrabilmente dal diff → etichetta (preesistente | esterno | nesso non provato), documenta, **non** assorbire; `BLOCKED`/escalation se serve; causa **chiara** dal diff → fix in perimetro o revert. **Instabilità output** — rumore vs regressione (KO deterministico; warning nuovo stabile su più run); dubbio/rumore → **stop**, niente altri fix per “pulire” i warning.

### Classificazione finale dei warning

| Categoria | Uso |
|-----------|-----|
| **Risolto** | Sparito vs baseline dopo intervento nel perimetro. |
| **Residuo accettato** | Ancora lì; **1 riga motivo concreto**. *Parzialmente mitigato* se ridotto ma non eliminato (**non** Risolto): prima→dopo in una riga. |
| **Rinviato / fuori scope** | Non trattato qui; **1 riga motivo concreto** (mega-upgrade, refactor, fuori 1–5). |

### Checklist di implementazione (cockpit — ordine fisso)

- [x] **1·A** — `assembleDebug` OK · `lint` OK · `testDebugUnitTest` OK. Suite: JVM standard, no TASK-004 (nessuna area business toccata).
- [x] **2** — `gradle.properties`: 5 flag deprecati rimossi, 2 rinviati. `build.gradle.kts`: `implementation(ui.tooling)` rimossa.
- [x] **3** — diff ⊆ ammessi: solo `gradle.properties` e `build.gradle.kts`. Ogni modifica mappata a criteri 1–5.
- [x] **4·B** — `assembleDebug` / `lint` / `testDebugUnitTest` tutti OK post-modifica.
- [x] **5** — tabella pre/post presente in Execution §5.
- [x] **6** — classificazione completa in Execution §6: 6 Risolti, 3 Rinviati, 1 Residuo accettato, 1 Preesistente.
- [x] **C** — sintesi integrata nei criteri di accettazione e nella classificazione.
- [x] **Criteri** — soglia applicata: `builtInKotlin` e `newDsl` fermati dopo cascading (regola soglia), non inseguiti.

### Criteri decisionali, soglia e stop

- Modifica **minima**; a parità → coerenza progetto, minimo impatto build/release.
- **Soglia (no over-cleanup, no inseguimento):** se un warning **non** cade con **una** modifica **locale + sicura + nel perimetro** → **stop** ulteriori tentativi; **Residuo accettato** o **Rinviato** con **1 riga motivo concreto**. Mega-upgrade → non forzare (**5**).
- **Stop task:** **1–5** soddisfatti con evidenza **oppure** rischio/perimetro ↑ senza beneficio **oppure** residui richiedono upgrade/refactor fuori piano → classifica e chiudi.

### Perimetro UI/UX (guardrail)

Task **non** UI/UX. **Vietato:** abbellimenti, ritocchi estetici, ottimizzazioni UX generiche. **Eccezione unica:** Gradle/classpath **solo** per tooling/build/debug (es. preview), inequivocabile; dubbio → **no** UI.

### Guardrail fuori scope

Dipendenze generiche; refactor/ordine/format non richiesti; UI oltre eccezione sopra; bump opportunistici; fix speculativi senza baseline/criteri **1–5**.

### Rischi identificati

- Cleanup aggressivo → regressioni build. *Mitigato: A/B allineati, revert su cascading.*
- Warning legati ad AGP/versioni irrisolvibili nel perimetro. *Verificato: 2 flag rinviati con motivazione.*
- Tooling rimosso → preview/debug rotti. *Mitigato: solo `implementation` rimossa, `debugImplementation` intatta.*
- `.DS_Store` ignorati ma non tolti dall’indice. *Non applicabile: confermato che 0 file sono tracciati da git.*

### Note Planning → Execution

Transizione PLANNING → EXECUTION avvenuta il 2026-04-03 dopo validazione planning vs repo. Il planning sopra descrive l’approccio previsto; la sezione Execution documenta l’esecuzione reale e le decisioni operative prese durante l’implementazione.

## Execution

### 1·A — Baseline (2026-04-03)

| Comando | Esito |
|---------|-------|
| `assembleDebug` | BUILD SUCCESSFUL |
| `lint` | BUILD SUCCESSFUL |
| `testDebugUnitTest` | BUILD SUCCESSFUL |

**Warning baseline (--warning-mode all):**
- 7× flag deprecati in `gradle.properties`: `resvalues`, `defaultTargetSdkToCompileSdkIfUnset`, `enableAppCompileTimeRClass`, `usesSdkInManifest.disallowed`, `r8.optimizedResourceShrinking`, `builtInKotlin`, `newDsl`
- 4× warning `android.dependency.excludeLibraryComponentsFromConstraints` (legato a `useConstraints=true`)
- 1× `org.jetbrains.kotlin.android` plugin deprecated (AGP 9.0+ built-in Kotlin)
- 1× OpenJDK VM warning `Sharing is only supported for boot loader classes` (JVM-level, preesistente)

### 2 — Interventi applicati

**`gradle.properties` — 5 flag deprecati rimossi (criterio 1):**
- `android.defaults.buildfeatures.resvalues=true` — rimosso
- `android.sdk.defaultTargetSdkToCompileSdkIfUnset=false` — rimosso
- `android.enableAppCompileTimeRClass=false` — rimosso
- `android.usesSdkInManifest.disallowed=false` — rimosso
- `android.r8.optimizedResourceShrinking=false` — rimosso

**`gradle.properties` — 2 flag deprecati mantenuti (Rinviato, criterio 5):**
- `android.builtInKotlin=false` — rimozione tentata, causa cascading: richiede rimozione plugin `kotlin.android` + incompatibilità KSP/sourceSets con built-in Kotlin. Richiede task dedicato migrazione AGP built-in Kotlin.
- `android.newDsl=false` — rimozione tentata, causa class cast `ApplicationExtensionImpl` vs `BaseExtension`. Richiede migrazione DSL.

**`app/build.gradle.kts` — tooling ridondante rimosso (criterio 2):**
- Rimossa riga `implementation(libs.androidx.ui.tooling)` (era riga 60). Debug tooling resta solo in `debugImplementation` (riga 107 originale). Evita inclusione di debug tools nel release APK.

**`.gitignore` — nessuna modifica (criterio 3):**
- `.DS_Store` già ignorato (riga 10), nessun file tracciato da git.

**`.DS_Store` — nessuna azione (criterio 3):**
- Confermato: 0 file tracciati (`git ls-files '*.DS_Store'`), 10 file su disco ma correttamente ignorati.

### 3 — Diff ⊆ ammessi

| File | Modifica | Criterio |
|------|----------|----------|
| `gradle.properties` | Rimossi 5 flag deprecati | **1**, **4**, **5** |
| `app/build.gradle.kts` | Rimossa `implementation(libs.androidx.ui.tooling)` ridondante | **2**, **4** |

Nessun altro file toccato.

### 4·B — Validazione finale (2026-04-03)

| Comando | Esito |
|---------|-------|
| `assembleDebug` | BUILD SUCCESSFUL |
| `lint` | BUILD SUCCESSFUL |
| `testDebugUnitTest` | BUILD SUCCESSFUL |

### 5 — Tabella pre/post

| Voce | Baseline | Finale | Nota |
|------|----------|--------|------|
| Flag deprecati in `gradle.properties` | 7 warning | 2 warning | 5 rimossi; 2 rinviati (cascading) |
| `excludeLibraryComponentsFromConstraints` | 4× warning | 4× warning | Invariato (legato a `useConstraints`, non deprecato) |
| `kotlin.android` plugin deprecated | 1× warning | 1× warning | Rinviato (richiede migrazione built-in Kotlin) |
| `ui.tooling` in `implementation` | presente | rimosso | Debug tools non più nel release |
| `.DS_Store` tracciati | 0 | 0 | Già non tracciati |
| OpenJDK VM sharing warning | 1× | 1× | Preesistente, JVM-level |
| `assembleDebug` | OK | OK | — |
| `lint` | OK | OK | — |
| `testDebugUnitTest` | OK | OK | — |

### 6 — Classificazione warning

| Warning | Categoria | Motivazione |
|---------|-----------|-------------|
| `resvalues=true` | **Risolto** | Flag rimosso, warning sparito |
| `defaultTargetSdkToCompileSdkIfUnset=false` | **Risolto** | Flag rimosso, warning sparito |
| `enableAppCompileTimeRClass=false` | **Risolto** | Flag rimosso, warning sparito |
| `usesSdkInManifest.disallowed=false` | **Risolto** | Flag rimosso, warning sparito |
| `r8.optimizedResourceShrinking=false` | **Risolto** | Flag rimosso, warning sparito |
| `builtInKotlin=false` | **Rinviato** | Rimozione causa cascading: richiede migrazione da `kotlin.android` plugin a AGP built-in Kotlin + fix KSP sourceSets |
| `newDsl=false` | **Rinviato** | Rimozione causa class cast DSL; richiede migrazione completa a AGP 9.x new DSL |
| `excludeLibraryComponentsFromConstraints` (4×) | **Residuo accettato** | Warning informativo legato a `useConstraints=true`; non è un flag deprecato |
| `kotlin.android` plugin deprecated | **Rinviato** | Dipende da risoluzione `builtInKotlin=false` (stesso task futuro) |
| OpenJDK VM sharing | **Preesistente** | Warning JVM, non dal progetto |
| `ui.tooling` in `implementation` | **Risolto** | Dipendenza ridondante rimossa |

---

## Review — 2026-04-03

**Revisore:** Claude (planner)

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Flag AGP/Kotlin deprecati affrontabili rimossi o sostituiti | ✅ | 5/7 rimossi; 2 rinviati con motivazione (cascading → task dedicato migrazione AGP built-in Kotlin) |
| 2 | Dipendenze tooling ridondanti pulite | ✅ | `implementation(ui.tooling)` rimossa; `debugImplementation` intatta; `ui.tooling.preview` correttamente in `implementation` |
| 3 | `.DS_Store` non tracciati e prevenuti | ✅ | 0 file tracciati (confermato `git ls-files`); `.gitignore` copre già |
| 4 | Build/lint/test JVM verdi dopo cleanup | ✅ | `assembleDebug` OK, `lint` OK, `testDebugUnitTest` OK (verificati in Execution e confermati in Review) |
| 5 | Warning residui documentati con motivazione | ✅ | Classificazione completa in Execution §6; ogni residuo/rinviato con 1 riga motivo concreto |

**Verifica file modificati:**

| File | Verifica | Esito |
|------|----------|-------|
| `gradle.properties` | 5 flag deprecati rimossi; 2 rinviati (`builtInKotlin`, `newDsl`) mantenuti; 3 non-deprecati invariati | ✅ Corretto e minimale |
| `app/build.gradle.kts` | `implementation(ui.tooling)` rimossa; `debugImplementation(ui.tooling)` resta; nessuna modifica fuori scope | ✅ Corretto e minimale |
| `.gitignore` | Non toccato (coerente col planning) | ✅ |
| `.DS_Store` | Non toccati (non tracciati) | ✅ |

**Problemi trovati:** nessuno.

**Coerenza con il planning:** piena. Le deviazioni operative (rinvio `builtInKotlin`/`newDsl`) sono documentate con motivazione e coerenti con la regola soglia del planning.

**Perimetro:** rispettato. Nessun scope creep, nessun mega-upgrade, nessuna modifica UI/UX.

**Verdetto:** APPROVED

---

## Fix

Non necessario — Review APPROVED senza problemi.

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | DONE     |
| Data chiusura          | 2026-04-03 |
| Tutti i criteri ✅?    | Sì (5/5) |
| Rischi residui         | `builtInKotlin=false` e `newDsl=false` rinviati; richiederanno task dedicato migrazione AGP built-in Kotlin/new DSL |

---

## Riepilogo finale

**Risultato:** 5 flag AGP deprecati rimossi da `gradle.properties`, 1 dipendenza tooling ridondante rimossa da `build.gradle.kts`. Build/lint/test JVM tutti verdi. Warning baseline ridotti da 7 deprecati a 2 (rinviati con motivazione). Nessuna regressione.

**Rinviati (stesso filone — task futuro migrazione AGP built-in Kotlin):**
- `android.builtInKotlin=false` — cascading: plugin `kotlin.android` + KSP sourceSets
- `android.newDsl=false` — cascading: class cast DSL
- Deprecazione plugin `org.jetbrains.kotlin.android` — dipende da `builtInKotlin`

---

## Handoff

- **Migrazione AGP built-in Kotlin (task futuro):** `android.builtInKotlin=false`, `android.newDsl=false` e il plugin `org.jetbrains.kotlin.android` appartengono allo stesso filone. La rimozione richiede: (1) drop plugin `kotlin.android`, (2) migrazione KSP sourceSets da `kotlin.sourceSets` a `android.sourceSets`, (3) migrazione DSL da `BaseExtension` a new DSL AGP 9.x. Tentata e revertita in questo task per cascading.
- **3 flag non deprecati rimasti:** `uniquePackageNames=false`, `dependency.useConstraints=true`, `r8.strictFullModeForKeepRules=false` — nessun warning, non richiedono intervento immediato.
- **Warning `excludeLibraryComponentsFromConstraints` (4×):** warning informativo legato a `useConstraints=true`; non blocca build né è deprecato.
- Il task non ha toccato business logic, UI, Room, repository o navigation.
