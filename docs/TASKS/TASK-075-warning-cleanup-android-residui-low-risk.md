# TASK-075 — Warning cleanup Android residui low-risk

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-075 |
| Stato | DONE |
| Priorita | ALTA |
| Area | Lint / Compose / Risorse / Localizzazione |
| Creato | 2026-05-27 |
| Ultimo aggiornamento | 2026-05-28 — review finale completata, DONE |

---

## Dipendenze

- **TASK-074** (`DONE`) — baseline toolchain/lint aggiornata: `lintDebug` `0 errors, 99 warnings`.

---

## Scopo

Ridurre ulteriormente warning Android/Kotlin residui a basso rischio dopo TASK-074, senza toccare sync cloud, business logic Room/Repository/ViewModel, Navigation o flussi import/export. Il task deve preferire fix reali e piccoli, lasciando documentati i warning che richiedono retest di prodotto o ambiente live.

---

## Contesto

Dopo TASK-074 il report `lintDebug` passa con `0 errors, 99 warnings`. I warning residui principali sono `PluralsCandidate`, `UnusedResources` su stringhe storiche, `OldTargetApi`, update Supabase/Ktor, `TrustAllX509TrustManager` da POI e deprecazioni Kotlin/Compose su `rememberSwipeToDismissBoxState(confirmValueChange)`.

---

## Non incluso

- Upgrade Supabase/Ktor o librerie di rete/cloud.
- Cambio `targetSdk`/`compileSdk` se richiede nuova SDK/API o retest compat.
- Soppressione cieca di `TrustAllX509TrustManager` da jar POI.
- Refactor Room, DAO, Repository, ViewModel business logic, Navigation o sync.
- Nuove dipendenze.
- Redesign UI o modifica dei flussi utente.
- Azzeramento warning IDE Proofreading/Markdown/grammar.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — deprecazione swipe Compose.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — deprecazione swipe Compose.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt`, `GeneratedScreen.kt`, `OptionsScreen.kt` — call-site plurals se limitati e staticamente chiari.
- `app/src/main/res/values*/strings.xml` — plurals e rimozione stringhe inutilizzate confermate.
- `docs/MASTER-PLAN.md`, questo file — governance ed evidenze.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Le deprecazioni Compose `rememberSwipeToDismissBoxState(confirmValueChange)` sono rimosse oppure rinviate con motivazione tecnica | B/S | ESEGUITO |
| 2 | I `PluralsCandidate` vengono convertiti solo dove il call-site e' staticamente sicuro; gli altri restano documentati | B/S | ESEGUITO |
| 3 | Le stringhe `UnusedResources` vengono rimosse solo se confermate non referenziate staticamente e senza uso dinamico | S | ESEGUITO |
| 4 | `OldTargetApi`, Supabase/Ktor e POI trust manager restano fuori scope salvo evidenza di fix sicuro senza refactor/live test | S | ESEGUITO |
| 5 | `assembleDebug`, `lintDebug`, `testDebugUnitTest` e `git diff --check` sono eseguiti e documentati | B/S | ESEGUITO |
| 6 | Nessuna modifica a Room/Repository/ViewModel business logic/Navigation/Supabase/import-export | S | ESEGUITO |

Legenda: B=Build, S=Static.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Trattare solo warning low-risk e reversibili | Il residuo contiene warning di natura diversa; quelli runtime/cloud/SDK richiedono task dedicato | 2026-05-27 |
| 2 | Non rincorrere warning IDE proofreading/Markdown | Non impattano build Android e generano rumore non utile per il prodotto | 2026-05-27 |

---

## Planning

### Analisi

Il task parte dalla baseline post-TASK-074. Le deprecazioni Compose sono warning di API, non necessariamente piu' efficienti, ma aggiornare al pattern corrente riduce debito tecnico. I `PluralsCandidate` migliorano correttezza l10n quando i call-site passano conteggi. Le stringhe unused sono candidate solo se non esistono riferimenti statici o dinamici.

### Piano di esecuzione

1. Rilevare baseline residua da `lint-results-debug.txt` e ricerca compiler warning.
2. Applicare fix Compose deprecation solo se il comportamento swipe resta equivalente.
3. Convertire plurals a risorse plurali dove il call-site e' locale e chiaro.
4. Rimuovere solo stringhe unused confermate dal lint e da ricerca statica.
5. Rilanciare `assembleDebug`, `lintDebug`, `testDebugUnitTest`, `git diff --check`.
6. Documentare warning rimossi e rinviati.

### Rischi identificati

- Swipe Compose: il nuovo pattern non usa piu' `confirmValueChange`; mitigazione con reset esplicito e verifica build/lint.
- Plurals: traduzioni possono richiedere forme `one/other`; mitigazione con risorse plurali minime e call-site dedicati.
- Rimozione stringhe: possibili usi dinamici futuri/storici; mitigazione rimuovendo solo nomi senza `getIdentifier`/riferimenti testuali.

---

## Execution

### Esecuzione — 2026-05-27

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — convertito count catalogo a `pluralStringResource`; deprecazione swipe prodotto rinviata nel fix finale con soppressione locale per regressione della nuova API.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — deprecazione swipe history rinviata nel fix finale con soppressione locale per regressione della nuova API.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — convertito warning righe duplicate troncate a plurals.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — convertito conteggio righe errore a plurals.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` — convertiti conteggi prodotti collegati/delete guidata a plurals.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — convertiti messaggi catalogo con conteggi a `getQuantityString`, senza modificare logica catalogo.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModel.kt` — aggiunto helper `quantityStr` per messaggi UI di sync con conteggi; nessuna modifica a sync/repository.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModelTest.kt` — aggiornate aspettative test per risorse plurali.
- `app/src/main/res/values*/strings.xml` — convertiti 11 gruppi stringa a `<plurals>`, aggiunta forma `many` spagnola, rimossa forma `one` cinese e rimosse stringhe unused confermate da lint.
- `docs/MASTER-PLAN.md`, questo file — governance ed evidenze.

**Azioni eseguite:**
1. Chiuso TASK-074 in `DONE` dopo review APPROVED e check rieseguiti.
2. Avviato TASK-075 come task attivo su richiesta utente per i warning residui low-risk.
3. Tentata la migrazione delle deprecazioni Compose `rememberSwipeToDismissBoxState(confirmValueChange)` in Database e History; il fix finale le ha rinviate con motivazione tecnica dopo regressione reale dello swipe.
4. Convertiti i `PluralsCandidate` staticamente sicuri a risorse plurali e aggiornati call-site Compose/ViewModel/test.
5. Rimossi i warning `UnusedResources` sulle stringhe lint-listed dopo verifica assenza `getIdentifier` e assenza riferimenti `R.string` residui.
6. Corrette forme plurali per lingua: spagnolo con `many`, cinese senza categoria `one`.
7. Risultato lint prima della review finale: `0 errors, 5 warnings` (baseline post-TASK-074: `0 errors, 99 warnings`); dopo conferma utente su Supabase/Ktor il gate finale scende a `0 errors, 3 warnings`.

**Warning risolti/ridotti:**
- Kotlin/Compose: deprecazione `rememberSwipeToDismissBoxState(confirmValueChange)` non rimossa; ripristinata con `@Suppress("DEPRECATION")` locale in `DatabaseScreenComponents.kt` e `HistoryScreen.kt` per preservare comportamento swipe corretto.
- Lint: rimossi `PluralsCandidate`, `UnusedResources`, `MissingQuantity` e `UnusedQuantity` introdotti durante la conversione plurals.
- Risorse: rimosse stringhe storiche non usate, senza riferimenti statici o dinamici rilevati.

**Warning rinviati/documentati:**
- `OldTargetApi`: resta a `targetSdk=36`; upgrade target richiede retest compat e/o SDK/API disponibile.
- `NewerVersionAvailable` Supabase/Ktor: resta fuori scope per rischio sync/rete e bisogno di test live.
- `TrustAllX509TrustManager`: proviene da jar esterno Apache POI (`poi-ooxml`), non soppresso alla cieca.

**Check obbligatori:**

| Check | Tipo | Stato | Evidenza |
|-------|------|-------|----------|
| Build Gradle | B | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` -> `BUILD SUCCESSFUL` |
| Lint | S | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` -> `BUILD SUCCESSFUL`, report `0 errors, 5 warnings` |
| Warning nuovi | S | ✅ ESEGUITO | Nessun warning nuovo nei file modificati; residui finali solo `OldTargetApi` e POI esterno |
| Coerenza con planning | — | ✅ ESEGUITO | Scope limitato a Compose API, plurals e risorse; SDK/cloud/POI non forzati |
| Criteri di accettazione | — | ✅ ESEGUITO | 6/6 criteri marcati `ESEGUITO` |
| Unit test JVM | B | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --no-daemon --max-workers=1` -> `BUILD SUCCESSFUL` |
| Diff hygiene | S | ✅ ESEGUITO | `git diff --check` -> nessun output |

**Baseline regressione TASK-004 (se applicabile):**
- Applicabile perche' il task tocca `DatabaseViewModel` e `CatalogSyncViewModel`, ma solo formattazione stringhe UI.
- Test eseguiti: full `testDebugUnitTest`.
- Test aggiunti/aggiornati: aggiornate aspettative `CatalogSyncViewModelTest` da `getString` a `getQuantityString` per risorse plurali.
- Limiti residui: nessun emulator/device richiesto; nessuna logica dati/import/export/sync modificata.

**Incertezze:**
- Nessuna incertezza bloccante. I 3 warning residui finali richiedono task dedicati, compat review o fix upstream.

**Handoff notes:**
- Task pronto per REVIEW. Lint reale Gradle e' sceso da `99` a `5` warning; non restano `PluralsCandidate` o `UnusedResources`.

---

## Review

### Review utente — 2026-05-27

**Finding:** lo swipe aggiornato senza `confirmValueChange` rimaneva visivamente bloccato dopo apertura dialog delete/rename in Cronologia; la riga tornava normale solo scrollando fuori e dentro viewport. Possibile impatto anche sullo swipe prodotto in Database, modificato nello stesso task.

**Esito:** FIX richiesto. Primo fix con nuova API non sufficiente al retest utente; fix finale applicato ripristinando il pattern stabile con soppressione locale della deprecazione.

---

## Fix

### Fix — 2026-05-27 (tentativo nuova API, superato dal fix finale)

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — lo swipe ora usa un collector stabile su `snapshotFlow { dismissState.currentValue }`, resetta lo stato con `dismissState.reset()` e solo dopo apre delete/rename dialog.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — applicato lo stesso pattern allo swipe delete prodotto, per evitare blocchi analoghi.

**Azioni eseguite:**
1. Analizzato il bug da screenshot: la card restava in stato dismiss dietro al dialog, con reset visivo solo dopo riciclo LazyColumn.
2. Sostituito il `LaunchedEffect` legato direttamente a `dismissState.currentValue` con `LaunchedEffect(dismissState, id)` + `snapshotFlow`, evitando cancellazioni del reset quando il valore cambia.
3. Invertito ordine azione/reset: prima `dismissState.reset()`, poi apertura dialog/azione.
4. Applicato fix sia a Cronologia sia a Database prodotti, gli unici due punti in cui TASK-075 aveva cambiato la swipe API.

**Check fix:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew compileDebugKotlin` -> `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` -> `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` -> `BUILD SUCCESSFUL`, report `0 errors, 5 warnings`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --no-daemon --max-workers=1` -> `BUILD SUCCESSFUL`.
- `git diff --check` -> nessun output.
- Verifica device automatica: non eseguibile da questa shell perche' `adb` non e' nel PATH.

**Limite residuo:**
- Superato dal retest utente: lo swipe restava bloccato e il dialog partiva solo dopo riciclo/scroll fuori viewport.

### Fix finale — 2026-05-27

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — ripristinato `rememberSwipeToDismissBoxState(confirmValueChange)` per delete/rename con `@Suppress("DEPRECATION")` locale.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — ripristinato `rememberSwipeToDismissBoxState(confirmValueChange)` per delete prodotto con `@Suppress("DEPRECATION")` locale.
- `docs/MASTER-PLAN.md`, questo file — governance aggiornata con motivazione del rinvio e smoke emulator.

**Azioni eseguite:**
1. Analizzato il secondo retest utente: con il pattern `snapshotFlow` l'azione veniva osservata tardi, quando la riga usciva dal viewport e la `LazyColumn` la riciclava.
2. Ripristinato il pattern precedente e stabile: `confirmValueChange` apre l'azione e ritorna `false`, impedendo allo stato dismiss di restare in posizione intermedia.
3. Limitata la soppressione a due call-site specifici, senza sopprimere lint globalmente e senza toccare ViewModel, Navigation, Repository, DAO o sync.

**Check fix finale:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew compileDebugKotlin` -> `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` -> `BUILD SUCCESSFUL`, report `0 errors, 5 warnings`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` -> `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --no-daemon --max-workers=1` -> `BUILD SUCCESSFUL`.
- `git diff --check` -> nessun output.
- Smoke emulator `emulator-5554`: install debug `Success`; Cronologia swipe delete apre subito `¿Seguro que deseas eliminar este archivo?` e `Cancelar` ripristina la riga; Cronologia swipe rename apre subito `Renombrar archivo` e `Cancelar` ripristina la riga; Database prodotti swipe delete apre subito `Confirmar eliminación` e `Cancelar` ripristina la riga.

**Limite residuo:**
- Utile conferma manuale utente su device fisico/reale, ma la riproduzione emulator con i dati presenti non mostra piu' il blocco.

### Audit supplementare warning/efficienza — 2026-05-28

**File modificati:**
- `gradle/libs.versions.toml` — mantenuti Supabase Kotlin `3.6.0` e Ktor `3.5.0` su conferma esplicita utente; rimossi i due warning dependency residui senza modifiche sorgente.
- `app/src/main/res/values/strings.xml` — rimosse le stringhe legacy `chinese`, `italian`, `spanish`, `english` e il namespace `tools` non piu' necessario.
- `app/src/main/res/values-en/strings.xml` — rimosse le stesse stringhe legacy non referenziate.
- `app/src/main/res/values-es/strings.xml` — rimosse le stesse stringhe legacy non referenziate.
- `app/src/main/res/values-zh/strings.xml` — rimosse le stesse stringhe legacy non referenziate.

**Azioni eseguite:**
1. Rigenerato `lintDebug --rerun-tasks` e ricontrollato il report testuale: dopo conferma utente su Supabase Kotlin `3.6.0` e Ktor `3.5.0`, restano solo `OldTargetApi` e POI `TrustAllX509TrustManager`.
2. Eseguito `lintVitalRelease`: nessun issue release bloccante.
3. Eseguito `help -Pandroid.debug.obsoleteApi=true --warning-mode all`: nessun warning obsolete API Gradle/AGP.
4. Eseguito `compileDebugKotlin compileReleaseKotlin --warning-mode all`: nessun warning Kotlin nuovo o deprecation non gestita.
5. Scandite le soppressioni `@Suppress`, `@SuppressLint` e `tools:ignore`; l'unico cleanup sicuro trovato era sulle quattro stringhe lingua legacy gia' sostituite dagli endonimi `language_endonym_*`.
6. Verificata assenza di riferimenti `R.string.chinese/italian/spanish/english` e assenza di `getIdentifier` o lookup dinamici prima della rimozione.

**Warning dependency risolti su conferma utente:**
- Supabase `3.5.0 -> 3.6.0` e Ktor `3.4.2 -> 3.5.0`: mantenuti aggiornati su richiesta esplicita utente; build/lint/test JVM coprono regressioni statiche, ma non sostituiscono smoke live cloud.

**Warning residui non rimossi:**
- `OldTargetApi`: non e' un warning di efficienza runtime; l'ambiente locale contiene piattaforme Android `34`, `35`, `36`, mentre il salto target richiede nuova SDK/compat review e retest prodotto.
- `TrustAllX509TrustManager`: proviene dal jar esterno `poi-ooxml-5.5.1`; sopprimerlo nasconderebbe un warning security senza correggere codice app.

**Check audit supplementare:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` -> `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug --rerun-tasks` -> `BUILD SUCCESSFUL`, report `0 errors, 3 warnings`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` -> `BUILD SUCCESSFUL`, report `0 errors, 3 warnings`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintVitalRelease` -> `BUILD SUCCESSFUL`, report `No issues found`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --no-daemon --max-workers=1` -> `BUILD SUCCESSFUL`.
- `git diff --check` -> nessun output.

### Review finale — 2026-05-28

**Finding corretto:**
- Il review gate ha rilevato una incoerenza documentale: Supabase/Ktor erano dichiarati rinviati, ma l'utente ha confermato esplicitamente di mantenere Supabase Kotlin `3.6.0` e Ktor `3.5.0`. Il version catalog resta quindi aggiornato e il rischio residuo e' documentato come assenza di smoke live cloud, non come warning lint residuo.

**Esito review:**
- Nessun finding bloccante su swipe, plurals, rimozione risorse o toolchain.
- `lintDebug --rerun-tasks` conferma il report finale `0 errors, 3 warnings`: `OldTargetApi` e due warning `TrustAllX509TrustManager` provenienti da POI esterno.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | DONE |
| Data chiusura | 2026-05-28 |
| Tutti i criteri OK? | Si, 6/6 verificati dopo review finale |
| Rischi residui | `OldTargetApi`, `TrustAllX509TrustManager` da POI esterno; upgrade Supabase/Ktor mantenuto su conferma utente senza smoke live cloud |

---

## Handoff

Task chiuso in DONE dopo review finale. `lintDebug` ridotto da `99` a `3` warning; la deprecazione swipe e' rinviata con soppressione locale perche' la nuova API ha creato regressione UX reale. Audit supplementare completato: rimossi 16 valori stringa legacy nascosti da `tools:ignore`; Supabase Kotlin `3.6.0` e Ktor `3.5.0` restano aggiornati su conferma utente. I 3 residui sono `OldTargetApi` e POI trust manager esterno.
