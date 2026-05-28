# TASK-074 — Warning cleanup Android con migrazione toolchain

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-074 |
| Stato | DONE |
| Priorita | ALTA |
| Area | Build / Toolchain / Lint / Risorse |
| Creato | 2026-05-27 |
| Ultimo aggiornamento | 2026-05-27 — review approvata, DONE |

---

## Dipendenze

- **TASK-029** (`DONE`) — cleanup toolchain precedente; aveva rinviato `android.builtInKotlin=false`, `android.newDsl=false` e plugin `kotlin.android`.
- **TASK-012** (`DONE`) — baseline CI/build.

---

## Scopo

Ridurre warning Android/Lint reali emersi da Android Studio Inspect Code e da `lintDebug`, includendo la migrazione toolchain AGP built-in Kotlin/new DSL se resta sicura. Il task deve migliorare mantenibilita e import/build hygiene senza introdurre regressioni in app, sync cloud o flussi import/export.

---

## Contesto

La baseline `lintDebug` passa ma mostra warning di progetto: opt-out AGP built-in Kotlin/new DSL, legacy variant API causata dal plugin Kotlin Android, warning import performance su dependency constraints, stringhe/risorse e alcuni warning lint locali. Il piano approvato dall'utente include anche la toolchain, ma impone rollback/rinvio se un upgrade richiede refactor prodotto o rompe build/test.

---

## Non incluso

- Refactor di Room, DAO, Repository, ViewModel business logic, Navigation o Supabase.
- Nuove dipendenze.
- Modifiche ai flussi UI o UX.
- Porting iOS.
- Azzeramento di Proofreading/Markdown/grammar warning IDE.
- Soppressione cieca di warning da librerie esterne, in particolare `TrustAllX509TrustManager` da POI.

---

## File potenzialmente coinvolti

- `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties` — toolchain e warning Gradle/AGP.
- `app/src/main/AndroidManifest.xml` — warning scanner portrait, solo soppressione mirata se mantenere portrait resta necessario.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — warning Compose `ModifierParameter`.
- `app/src/main/res/values*/strings.xml`, `app/src/main/res/values/colors.xml` — warning stringhe/risorse low-risk.
- `docs/MASTER-PLAN.md`, questo file — governance ed evidenze.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | `android.builtInKotlin=false`, `android.newDsl=false`, plugin `kotlin.android` e legacy variant warnings sono rimossi oppure rinviati con evidenza di blocco reale | B/S | ESEGUITO |
| 2 | Warning `excludeLibraryComponentsFromConstraints` risolto senza peggiorare build/import | B/S | ESEGUITO |
| 3 | `ModifierParameter` risolto senza cambiare comportamento UI | B/S | ESEGUITO |
| 4 | Runtime locale warning risolto con `bundle.language.enableSplit=false` oppure documentato se non supportato | B/S | ESEGUITO |
| 5 | Warning scanner portrait mantenuto solo con soppressione mirata e motivazione | S | ESEGUITO |
| 6 | Typo spagnoli, ellipsis e `StringFormatCount` low-risk corretti senza cambiare significato utente | S | ESEGUITO |
| 7 | Risorse inutilizzate rimosse solo se confermate non referenziate staticamente e senza uso dinamico | S | ESEGUITO |
| 8 | Upgrade dependency/Gradle applicati solo se patch/minor sicuri; Supabase/Ktor o cambi rischiosi rinviati | B/S | ESEGUITO |
| 9 | `assembleDebug`, `lintDebug`, full `testDebugUnitTest` e `git diff --check` eseguiti e documentati | B/S | ESEGUITO |
| 10 | Nessuna modifica a Room/Repository/ViewModel business logic/Navigation/Supabase/UI flow | S | ESEGUITO |

Legenda: B=Build, S=Static.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Includere toolchain, ma in slice reversibili | `builtInKotlin`/`newDsl` sono warning che diventeranno blocchi con AGP 10; rischio da isolare | 2026-05-27 |
| 2 | Non inseguire warning dependency se richiedono refactor prodotto o test live | Evita regressioni su sync/rete/import-export | 2026-05-27 |
| 3 | Non convertire tutti i `PluralsCandidate` in questo task | Richiede plural resources e call-site diffuso; farlo solo dove e' strettamente low-risk | 2026-05-27 |

---

## Planning

### Analisi

Il progetto e' gia su AGP 9.2.1 e Gradle 9.4.1, con `ApplicationExtension` gia usato in `app/build.gradle.kts`. La baseline mostra che i warning legacy variant arrivano dal plugin `org.jetbrains.kotlin.android`; la migrazione ufficiale AGP built-in Kotlin richiede rimuovere quel plugin e gli opt-out in `gradle.properties`.

### Piano di esecuzione

1. Baseline pre-patch: `help -Pandroid.debug.obsoleteApi=true`, `lintDebug`, `assembleDebug`, `testDebugUnitTest`.
2. Migrazione toolchain: built-in Kotlin/new DSL, dependency constraints import performance, eventuale wrapper upgrade solo se sicuro.
3. Patch lint low-risk su Compose modifier, manifest scanner, locale bundle, stringhe e risorse confermate.
4. Rilanciare verifiche dopo le slice principali.
5. Documentare warning risolti, warning rinviati e motivazioni.

### Rischi identificati

- Migrazione built-in Kotlin/new DSL puo' rompere KSP o configurazione Gradle; mitigazione: slice isolata e verifica immediata.
- Rimozione risorse puo' rompere riferimenti dinamici non rilevati; mitigazione: rimuovere solo risorse senza `getIdentifier`/uso dinamico e con diff contenuto.
- Upgrade Supabase/Ktor puo' richiedere test live; mitigazione: rinvio se non chiaramente safe.

---

## Execution

### Esecuzione — 2026-05-27

**File modificati:**
- `build.gradle.kts` — rimosso plugin root `kotlin.android` non piu' necessario con AGP built-in Kotlin.
- `app/build.gradle.kts` — rimosso plugin app `kotlin.android`; aggiunto `bundle.language.enableSplit=false`; configurato `byte-buddy-agent` gia' transitivo come `-javaagent` per test JVM MockK; mantenuto `ApplicationExtension`.
- `gradle.properties` — rimossi opt-out `android.builtInKotlin=false` e `android.newDsl=false`; sostituito `android.dependency.useConstraints=true` con `false` per evitare il warning AGP su constraints/exclude.
- `gradle/libs.versions.toml` — rimosso alias `kotlin-android`; aggiornati Gradle-safe patch/minor (`kotlin`, `ksp`, Navigation Compose, Compose BOM/runtime-livedata, Paging, Gson, Material Components, jsoup, coroutines-test); lasciati Supabase/Ktor.
- `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` — wrapper aggiornato a Gradle `9.5.1`; `gradlew.bat` normalizzato LF per `git diff --check`.
- `app/src/main/AndroidManifest.xml` — soppressione mirata `DiscouragedApi` su `PortraitCaptureActivity`, mantenendo portrait per scanner.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — riordinato `modifier` come primo parametro opzionale del composable `DatabaseProductListSection`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` — rimossa argomentazione stringa non usata nella delete semplice catalogo.
- `app/src/main/res/values*/strings.xml` — corretto placeholder `%1$s`, ellipsis tipografici e typo/accenti spagnoli low-risk.
- `app/src/main/res/values/colors.xml` — rimosso vecchio file template colori inutilizzato, senza riferimenti statici o `getIdentifier`.
- `docs/MASTER-PLAN.md`, questo file — governance, task attivo ed evidenze.

**Azioni eseguite:**
1. Creato branch `codex/warning-cleanup-toolchain-android`.
2. Letti `docs/MASTER-PLAN.md`, `docs/CODEX-EXECUTION-PROTOCOL.md`, template task e file Gradle/Lint rilevanti.
3. Avviata governance `TASK-074` su richiesta esplicita utente.
4. Baseline pre-patch:
   - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew help -Pandroid.debug.obsoleteApi=true` -> `BUILD SUCCESSFUL`; warning `builtInKotlin=false`, `newDsl=false`, legacy variant API da `kotlin-android`, dependency constraints.
   - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` -> `BUILD SUCCESSFUL`; report lint baseline `0 errors, 169 warnings`.
   - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` -> `BUILD SUCCESSFUL`.
   - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --no-daemon --max-workers=1` -> fallimento ambientale noto `AttachNotSupportedException` MockK/ByteBuddy.
   - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true' ./gradlew testDebugUnitTest --no-daemon --max-workers=1` -> `BUILD SUCCESSFUL`.
5. Migrato ad AGP built-in Kotlin/new DSL rimuovendo plugin `kotlin.android` e opt-out Gradle; primo tentativo bloccato da KSP 2.3.2 (`kotlin.sourceSets` generati), risolto con patch KSP 2.3.4.
6. Applicato `android.dependency.useConstraints=false` invece di `android.dependency.excludeLibraryComponentsFromConstraints=true`, perche' AGP 9.2.1 segnala l'exclude come deprecato e suggerisce `useConstraints=false`.
7. Aggiunto `bundle.language.enableSplit=false`, verificato con `lintDebug`: warning `AppBundleLocaleChanges` rimosso.
8. Aggiornato Gradle wrapper a `9.5.1` dopo lint residuo `AndroidGradlePluginVersion`, con build/lint verdi.
9. Applicati upgrade patch/minor low-risk su dipendenze non-cloud; Supabase/Ktor rinviati per rischio sync/rete e bisogno di test live.
10. Corretti warning lint low-risk: `ModifierParameter`, `StringFormatCount`, `TypographyEllipsis`, typo spagnoli e soppressione mirata `DiscouragedApi` per scanner portrait.
11. Rimossa solo la risorsa `colors.xml` template, confermata inutilizzata e senza riferimenti dinamici; non rimossa la massa di stringhe unused per evitare regressioni su superfici storiche/localizzate.
12. Configurato il test JVM `testDebugUnitTest` con `byte-buddy-agent` gia' presente nel classpath MockK, per evitare l'auto-attach da thread Robolectric dopo la migrazione toolchain.
13. Risultato lint finale: `0 errors, 99 warnings` (baseline: `0 errors, 169 warnings`).

**Warning risolti/ridotti:**
- Toolchain: rimossi warning `android.builtInKotlin=false`, `android.newDsl=false`, plugin `org.jetbrains.kotlin.android`/legacy variant API e `AndroidGradlePluginVersion`.
- Lint app: rimossi `AppBundleLocaleChanges`, `ModifierParameter`, `DiscouragedApi` scanner, `StringFormatCount`, `TypographyEllipsis`, `Typos`.
- Dependency: rimossi warning per Kotlin/Compose plugin patch, Gradle, KSP compat, Navigation Compose, Compose BOM/runtime-livedata, Paging, Gson, Material Components, jsoup, coroutines-test.
- Risorse: rimossi 7 warning `UnusedResources` da colori template.

**Warning rinviati/documentati:**
- `OldTargetApi`: target SDK oltre 36 richiede SDK/API installata e retest compat; rinviato.
- `NewerVersionAvailable` Supabase/Ktor: rinviati per rischio su sync/rete e assenza test live in questo task.
- `TrustAllX509TrustManager`: proviene da jar esterno Apache POI (`poi-ooxml`), non soppresso alla cieca.
- `PluralsCandidate`: richiede plural resources e cambio call-site diffuso; rinviato come previsto dal planning.
- `UnusedResources` stringhe: molte stringhe storiche/localizzate; lasciate per evitare rimozioni aggressive non richieste.
- Warning Kotlin `rememberSwipeToDismissBoxState(confirmValueChange)`: residuo preesistente in UI, richiede refactor gesture/dynamic anchors; fuori scope.

**Check obbligatori:**

| Check | Tipo | Stato | Evidenza |
|-------|------|-------|----------|
| Build Gradle | B | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` -> `BUILD SUCCESSFUL` |
| Lint | S | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` -> `BUILD SUCCESSFUL`, report `0 errors, 99 warnings` |
| Warning nuovi | S | ✅ ESEGUITO | Nessun warning nuovo legato ai file prodotto; restano warning Kotlin preesistenti `rememberSwipeToDismissBoxState(confirmValueChange)` |
| Coerenza con planning | — | ✅ ESEGUITO | Scope limitato a toolchain/lint/resources; nessuna business logic modificata |
| Criteri di accettazione | — | ✅ ESEGUITO | 10/10 criteri marcati `ESEGUITO` |
| Unit test JVM | B | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --no-daemon --max-workers=1` -> `BUILD SUCCESSFUL` |
| Diff hygiene | S | ✅ ESEGUITO | `git diff --check` -> nessun output |

**Baseline regressione TASK-004 (se applicabile):**
- Applicabile come full JVM regression gate perche' il task tocca toolchain/build config e risorse condivise.
- Test baseline pre-patch eseguito con `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true'`: PASS.
- Test finale full `testDebugUnitTest`: PASS dopo configurazione `byte-buddy-agent` in `app/build.gradle.kts`.
- Test aggiunti/aggiornati: nessuno; nessuna logica dati/import/export/ViewModel e' stata modificata.
- Limiti residui: nessun test device/emulator richiesto dal task; Supabase/Ktor live non toccati.

**Incertezze:**
- Nessuna incertezza bloccante. Il residuo Supabase/Ktor e target SDK e' rinviato intenzionalmente per rischio/ambiente, non per fallimento della patch.

**Handoff notes:**
- Task pronto per REVIEW. Focus review consigliato: toolchain Gradle/KSP, `byte-buddy-agent` test JVM, e conferma che i warning residui documentati restino fuori scope.
- Non sono stati modificati Room, DAO, Repository, ViewModel business logic, Navigation, Supabase o flussi UI.

---

## Review

### Review repo-grounded — 2026-05-27

**Esito:** APPROVED, nessun finding bloccante.

**Verifiche review:**
1. Diff Gradle/toolchain ricontrollato: rimozione `kotlin.android`, opt-out AGP built-in Kotlin/new DSL e cambio constraints coerenti con planning e con le evidenze lint.
2. Diff risorse/stringhe ricontrollato: `StringFormatCount`, ellipsis e typo risolti senza cambiare semantica; rimozione `colors.xml` limitata a risorse template non referenziate.
3. Diff UI/manifest ricontrollato: `ModifierParameter` risolto senza call-site posizionali pericolosi; soppressione scanner portrait mirata e motivata.
4. Warning rinviati confermati fuori scope/rischio: `OldTargetApi`, Supabase/Ktor, `TrustAllX509TrustManager` da POI, `PluralsCandidate`, stringhe unused storiche e deprecazione swipe Compose preesistente.

**Check review rieseguiti:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew help -Pandroid.debug.obsoleteApi=true` -> `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` -> `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` -> `BUILD SUCCESSFUL`, report `0 errors, 99 warnings`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --no-daemon --max-workers=1` -> `BUILD SUCCESSFUL`.
- `git diff --check` -> nessun output.

---

## Fix

_(vuoto finche' non necessario)_

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | DONE |
| Data chiusura | 2026-05-27 |
| Tutti i criteri OK? | Si, 10/10 verificati e review approvata |
| Rischi residui | Warning residui documentati: `OldTargetApi`, Supabase/Ktor, POI jar trust manager, plurals/stringhe unused, deprecazione swipe Compose preesistente |

---

## Handoff

Task chiuso in DONE. Lint ridotto da `169` a `99` warning, build/lint/test/diff check passati anche in review. I warning residui sicuri da affrontare in una tranche separata sono plurals/stringhe unused e deprecazioni swipe Compose; target SDK, Supabase/Ktor e POI trust manager restano da trattare solo con task dedicati e test adeguati.
