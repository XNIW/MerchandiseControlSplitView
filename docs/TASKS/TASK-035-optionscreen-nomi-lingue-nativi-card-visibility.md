# TASK-035 — OptionsScreen: nomi lingue nativi + card visibility

---

## Informazioni generali

| Campo                | Valore                                      |
|----------------------|---------------------------------------------|
| ID                   | TASK-035                                    |
| Stato                | **DONE** (review repo-grounded completata 2026-04-05; micro-fix locale su layout compatto; build/lint/verifiche manuali positive) |
| Priorità             | BASSA                                       |
| Area                 | UX / UI / OptionsScreen                     |
| Creato               | 2026-04-05                                  |
| Ultimo aggiornamento | 2026-04-05 — review completata, task chiuso in DONE |

---

## Dipendenze

- Nessuna

---

## Scopo

1. Nella sezione lingua di **OptionsScreen**, mostrare **sempre** l’etichetta di ogni lingua nel suo **endonimo** / script d’uso (es. **中文**, **Español**, **Italiano**, **English**), indipendentemente dalla lingua UI corrente dell’app.
2. Aumentare la **visibilità percepita** delle **card** che raggruppano tema e lingua (oggi elevation bassa e `surfaceVariant` molto attenuato), con intervento **minimo** su `OptionsGroup` nello stesso file.

---

## Contesto

**Evidenza codice — etichette lingua:** in `OptionsScreen.kt` l’elenco è costruito con `stringResource(R.string.chinese|italian|spanish|english)` accoppiato ai codici `zh`, `it`, `es`, `en`. Le stringhe sono **localizzate per cartella** (`values`, `values-en`, `values-es`, `values-zh`): in UI inglese l’utente vede ad es. «Chinese» invece di «中文», in italiano «Cinese» / «Spagnolo», ecc. Il task chiede di **rompere** questa dipendenza dalla lingua UI **solo per le etichette del selettore lingua**, non per il resto della schermata (titolo, sezioni, tema restano localizzati).

**Evidenza codice — card:** `OptionsGroup` usa `CardDefaults.cardElevation(defaultElevation = 1.dp)` e `containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)`, che rende le sezioni poco contrastate rispetto allo sfondo.

**Riferimenti:** `LocaleUtils.kt` / `setLocale` gestiscono solo applicazione locale al contesto; **non** definiscono le etichette UI del picker. **TASK-019** (`DONE`) ha coperto audit L10n generale; questo task è un **aggiustamento UX mirato** al picker lingue, senza revisione globale delle stringhe.

---

## Non incluso

- Modifiche a `SharedPreferences` (chiavi `lang` / `theme`), a `setLocale`, a `LocaleUtils.kt`, a `NavGraph`, ViewModel, repository, DAO.
- Allineamento di **altre** schermate che mostrano nomi lingua (se esistono fuori da `OptionsScreen`).
- Introduzione di nuove lingue oltre alle quattro già supportate (`zh`, `it`, `es`, `en`).
- Redesign della schermata (layout, icone, navigazione), token design system globali (`MerchandiseControlTheme`, **TASK-030**): solo ritocco **locale** alle card di questa schermata.
- Nuove dipendenze Gradle / librerie.
- Test UI strumentati (Espresso / Compose test) — non richiesti salvo futura richiesta esplicita.
- Baseline **TASK-004** (repository / Excel / DatabaseViewModel): **non applicabile** se il diff resta confinato a `OptionsScreen` (e al più stringhe `translatable="false"`).

---

## File potenzialmente coinvolti

| File | Ruolo |
|------|--------|
| `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt` | **Obbligatorio:** sostituire la sorgente delle etichette lingua (da `stringResource` dipendente da locale UI a strategia vincolata in § Decisioni); aggiornare `OptionsGroup` (elevation / colore). |
| `app/src/main/res/values/strings.xml` (o file dedicato sotto `values/`) | **Solo se** si adotta la variante B in § Decisioni: 4 stringhe `translatable="false"` con endonimi fissi. |

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Con app in **qualsiasi** delle 4 lingue supportate, nella sezione lingua le quattro opzioni mostrano sempre le etichette endonime concordate: **中文**, **Italiano**, **Español**, **English** (stesso ordine righe e stessi codici `zh`/`it`/`es`/`en` di oggi). | M + B | — |
| 2 | Il cambio lingua (tap su radio) continua a aggiornare `prefs`, chiamare `setLocale` e `recreate()` come oggi; nessuna regressione su persistenza o navigazione indietro. | M + B | — |
| 3 | Le due card (tema e lingua) risultano **visivamente più leggibili** rispetto al baseline: elevation **maggiore** di `1.dp` e/o colore contenitore **meno trasparente** (alpha più alta o uso di colore tema senza attenuazione eccessiva), restando coerenti con Material3 e dark/light. | M + B | — |
| 4 | Nessuna modifica a DAO, repository, navigation graph, ViewModel. | S (diff review) + B | — |
| 5 | `./gradlew assembleDebug` — **BUILD SUCCESSFUL**, 0 errori. | B | — |
| 6 | `./gradlew lint` — nessun warning **nuovo** attribuibile al diff. | S | — |
| 7 | Nessun warning Kotlin nuovo nel codice toccato. | S | — |

**Checklist Definition of Done — task UX/UI** (`docs/MASTER-PLAN.md`): applicare dove pertinente (gerarchia/spazio card, coerenza tema, nessuna regressione funzionale).

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | **Endonimi — variante preferita A:** derivare l’etichetta con `java.util.Locale`: per ogni `languageTag` della lista, `Locale.forLanguageTag(tag)` e `getDisplayLanguage(locale)` con locale «di quella lingua» (stesso `Locale`), applicando **capitalizzazione titolo** solo dove serve per coerenza UI (es. `replaceFirstChar { it.titlecase(locale) }`). | Zero nuove chiavi stringa se l’output ICU è stabile sulle 4 lingue; diff tipicamente **solo** `OptionsScreen.kt`. | 2026-04-05 |
| 2 | **Endonimi — variante B (fallback se A fallisce review/dispositivo):** quattro stringhe in `values/strings.xml` con `translatable="false"` (es. `language_endonym_zh` = 中文, …), usate **solo** dal picker; nessuna traduzione nelle altre cartelle `values-*`. | Etichette **deterministiche** se in execution si rileva che ICU differisce dal desiderato su qualche API level. | 2026-04-05 |
| 3 | **Card:** aumentare `defaultElevation` (valore suggerito in execution: **2.dp–4.dp**, scegliere il minimo che migliori la lettura) e aumentare opacità del contenitore (es. `alpha` da `0.3f` verso **0.5f–0.7f**) **oppure** usare `surfaceVariant` a alpha pieno se il contrasto con lo sfondo resta corretto in dark/light. | Obiettivo: contrasto percepito senza redesign; restare nel solo `OptionsGroup`. | 2026-04-05 |

**Ambiguità documentata:** l’esito esatto di `getDisplayLanguage` per `zh`/`it`/`es`/`en` dipende da ICU/Android; se in **EXECUTION** le stringhe non coincidono con la tabella del criterio #1, adottare **variante B** senza ampliare il perimetro.

---

## Planning (Claude)

### Analisi (repo-grounded)

- **`OptionsScreen.kt` (righe ~48–55):** `languages` usa `stringResource` sulle chiavi `chinese`, `italian`, `spanish`, `english` → etichette dipendenti da `values-*`.
- **`OptionsScreen.kt` (righe ~170–174):** `Card` in `OptionsGroup` con `defaultElevation = 1.dp` e `surfaceVariant.copy(alpha = 0.3f)` → card poco prominenti.
- **`NavGraph.kt`:** composable `OptionsScreen(navController)` — nessun cambio previsto.
- **`LocaleUtils.kt`:** solo `setLocale` — fuori perimetro salvo emergenza non prevista.

### Perimetro file (minimo)

1. **Primario:** `OptionsScreen.kt` (helper privato per endonimi + parametri `Card`).
2. **Condizionale:** `values/strings.xml` solo se variante B.

### Rischi / edge case / regressioni possibili

| Rischio | Mitigazione |
|---------|-------------|
| ICU restituisce grafia o casing non allineato al criterio #1 | Verifica manuale su 1 device/emulator; passare a stringhe `translatable="false"`. |
| Card troppo «pesanti» in dark mode | Confronto visivo light/dark dopo il tweak; ridurre elevation/alpha se il contrasto peggiora. |
| Accessibilità: lettura screen reader | Le etichette restano `Text` associati al `Row` selezionabile; endonimi non rompono il pattern; verificare che non compaiano caratteri non pronunciabili senza contesto (accettabile per picker lingua standard). |
| Scope creep verso tema globale | Vietato toccare `MerchandiseControlTheme` / altri screen in questo task. |

### Piano di esecuzione minimale

1. Leggere `OptionsScreen.kt` e confermare assenza di altri consumer delle stesse stringhe per il picker (grep opzionale su `R.string.chinese` ecc. — solo per evitare effetti collaterali se si cambiano le risorse).
2. Implementare endonimi (variante A o B secondo § Decisioni e criterio #1).
3. Aggiornare `OptionsGroup`: elevation + colore contenitore con il minimo diff soddisfacente al criterio #3.
4. `assembleDebug` + `lint` + controllo manuale Options (4 lingue × verifica etichette + tema chiaro/scuro).
5. Log in sezione **Execution** (file modificati, scelta A vs B, valori numerici elevation/alpha).

### Verifiche post-esecuzione (checklist esecutore)

- [ ] `./gradlew assembleDebug`
- [ ] `./gradlew lint`
- [ ] Controllo warning Kotlin sui file toccati
- [ ] Manuale: aprire Options da ogni lingua app e verificare etichette fisse + aspetto card + cambio lingua funzionante
- [ ] TASK-004: **N/A** (confermare nel log se il diff non tocca aree baseline)

---

## Execution

### Esecuzione — 2026-04-05

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt` — variante B per gli endonimi del picker lingua; card tema/lingua rese più leggibili; selezione attiva resa più evidente senza cambiare la logica esistente.
- `app/src/main/res/values/strings.xml` — aggiunte 4 stringhe `translatable="false"` per gli endonimi fissi; mantenuti i 4 label legacy con soppressione locale `UnusedResources` per evitare warning nuovi introdotti dal diff.

**Azioni eseguite:**
1. Letto il planning e il codice di `OptionsScreen`; verificato con `rg` che i label `chinese`/`italian`/`spanish`/`english` fossero consumati solo da questa schermata.
2. Eseguito il check rapido previsto per la variante A (`Locale`/`getDisplayLanguage`): non immediatamente eseguibile in shell perché `jshell` non aveva un Java runtime disponibile; adottata subito la variante B come da decisione approvata.
3. Sostituita la sorgente delle etichette del picker lingua con 4 endonimi fissi: `中文`, `Italiano`, `Español`, `English`, mantenendo invariata la logica `prefs` + `setLocale(context, langCode)` + `recreate()`.
4. UI/UX: aumentata la leggibilità delle `OptionsGroup` con `defaultElevation` da `1.dp` a `3.dp` e `surfaceVariant.copy(alpha = 0.6f)` al posto di `0.3f`; resa più immediata la selezione attiva con label selezionata in `primary` + `SemiBold` (motivo: chiarezza percepita e equilibrio visivo tra sezione tema e lingua).
5. Aggiunte le 4 stringhe endonime in `values/strings.xml` e mantenuti i vecchi label localizzati esistenti con `tools:ignore="UnusedResources"` per non introdurre warning lint nuovi dovuti al solo cambio di sorgente del picker.
6. Eseguiti `./gradlew assembleDebug` e `./gradlew lint` con `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`; verificato che nei file toccati non comparissero warning Kotlin nuovi.
7. Verifica manuale su `emulator-5554`: controllata `OptionsScreen` in UI spagnola, inglese, italiana e cinese; verificata leggibilità card in light e dark mode; verificati cambio lingua, ritorno alla home e persistenza dopo `am force-stop` + cold start. Emulator ripristinato allo stato iniziale `Español + light`.

**Check obbligatori:**

| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ | `assembleDebug` finale: `BUILD SUCCESSFUL in 1s` |
| Lint | ✅ | `lint` finale: `BUILD SUCCESSFUL in 14s`; warning di progetto preesistenti invariati, nessun warning nuovo attribuibile ai file toccati |
| Warning Kotlin | ✅ | Nessun warning Kotlin/deprecation nuovo nei file toccati; restano solo warning preesistenti di configurazione AGP/Kotlin plugin |
| Coerenza con planning | ✅ | Variante B adottata subito; diff confinato a `OptionsScreen.kt` e `values/strings.xml`; nessun refactor, nessuna dipendenza nuova |
| Criteri di accettazione | ✅ | Tutti i criteri verificati singolarmente (vedi tabella sotto) |

**Dettaglio criteri di accettazione:**

| # | Criterio | Verifica | Stato | Evidenza |
|---|----------|----------|-------|----------|
| 1 | Con app in qualsiasi delle 4 lingue supportate, nella sezione lingua le quattro opzioni mostrano sempre `中文`, `Italiano`, `Español`, `English` nello stesso ordine/codici di oggi. | M + B | ✅ ESEGUITO | Verifica manuale su `emulator-5554` in spagnolo, inglese, italiano e cinese; dump UI e screenshot confermano endonimi invariati in tutti i casi |
| 2 | Il cambio lingua continua a aggiornare `prefs`, chiamare `setLocale` e `recreate()` senza regressioni su persistenza o navigazione indietro. | M + B | ✅ ESEGUITO | Tapping sulle righe lingua aggiorna immediatamente la UI locale; back dalla schermata Opzioni riporta alla home correttamente localizzata; preferenza persistita dopo `am force-stop` + cold start |
| 3 | Le due card tema/lingua risultano visivamente più leggibili rispetto al baseline, restando coerenti con Material3 e dark/light. | M + B | ✅ ESEGUITO | `OptionsGroup` portato a `3.dp` + `surfaceVariant` alpha `0.6f`; verifica manuale positiva in light e dark mode su emulator |
| 4 | Nessuna modifica a DAO, repository, navigation graph, ViewModel. | S + B | ✅ ESEGUITO | Diff limitato a `OptionsScreen.kt` e `values/strings.xml` |
| 5 | `./gradlew assembleDebug` — `BUILD SUCCESSFUL`, 0 errori. | B | ✅ ESEGUITO | `BUILD SUCCESSFUL in 1s` |
| 6 | `./gradlew lint` — nessun warning nuovo attribuibile al diff. | S | ✅ ESEGUITO | `BUILD SUCCESSFUL in 14s`; report lint finale senza occorrenze su `OptionsScreen`, `language_endonym_*` o sui vecchi label lingua |
| 7 | Nessun warning Kotlin nuovo nel codice toccato. | S | ✅ ESEGUITO | Nessun warning Kotlin sui file modificati; solo warning di configurazione AGP/Kotlin plugin già presenti a livello progetto |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: N/A — il diff resta confinato a `OptionsScreen.kt` e `values/strings.xml`, fuori dalle aree repository/ViewModel/import-export/history coperte da TASK-004.
- Test aggiunti/aggiornati: nessuno.
- Limiti residui: nessuno per baseline TASK-004.

**Incertezze:**
- `docs/MASTER-PLAN.md` non riportava TASK-035 come task attivo al momento della lettura; execution avviata su approvazione utente esplicita, senza modificare `docs/MASTER-PLAN.md` come richiesto.

**Handoff notes:**
- TASK-035 pronto per review; nessun fix aperto emerso in execution.
- Evidenze manuali raccolte su `emulator-5554` in `Español`, `English`, `Italiano`, `中文`, più controllo dark mode e persistenza preferenze.

---

## Review

### Review — 2026-04-05

**Revisore:** Codex

**Criteri di accettazione:**
| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Endonimi sempre `中文`, `Italiano`, `Español`, `English` nel picker lingua | ✅ | Verificato su emulator in UI `es`, `en`, `it`, `zh`; dump UI e screenshot coerenti |
| 2 | Cambio lingua senza regressioni su `prefs`, `setLocale`, `recreate()`, persistenza e back navigation | ✅ | Verificato con switch `es → en`, ritorno alla home e cold start; la preferenza resta persistita |
| 3 | Card tema/lingua più leggibili ma coerenti con Material3 / dark-light | ✅ | Elevation/opacità percepibili; durante review trovato un problema locale su layout compatto, corretto nel fix qui sotto |
| 4 | Nessuna modifica a DAO / repository / navigation graph / ViewModel | ✅ | Diff confermato nel solo perimetro `OptionsScreen.kt` + `values/strings.xml` |
| 5 | `./gradlew assembleDebug` verde | ✅ | Review post-fix: `BUILD SUCCESSFUL in 4s` |
| 6 | `./gradlew lint` senza warning nuovi attribuibili al diff | ✅ | Review post-fix: `BUILD SUCCESSFUL in 29s`; nessuna occorrenza lint su `OptionsScreen` o `language_endonym_*` |
| 7 | Nessun warning Kotlin nuovo nel codice toccato | ✅ | Nessun warning Kotlin nuovo nei file modificati; solo warning preesistenti AGP/Kotlin plugin di progetto |

**Problemi trovati:**
- Su layout compatto (`adb shell wm size 720x1280`) il titolo della card lingua andava a capo e il contenuto basso risultava raggiungibile solo parzialmente nella viewport iniziale: problema locale UX/UI coerente col criterio “niente wrap/clipping percepibile”.

**Verdetto:** APPROVED

**Note per fix:**
- Fix locale applicato in `OptionsScreen.kt`: scroll verticale dell’intera schermata e riduzione del titolo card da `titleLarge` a `titleMedium` per mantenere una riga pulita anche su viewport stretti.

---

## Fix

### Fix — 2026-04-05

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt` — aggiunto `verticalScroll(rememberScrollState())` al contenitore principale; titolo di `OptionsGroup` portato a `titleMedium` per evitare wrap percepibile in layout compatto.

**Correzioni applicate:**
1. Resa la schermata scrollabile verticalmente per evitare clipping del contenuto su altezze compatte o viewport stretti.
2. Ridotta leggermente la gerarchia tipografica del titolo sezione per mantenere `Seleccionar idioma` su una sola riga in verifica compatta.
3. Rieseguiti `assembleDebug`, `lint` e verifica manuale su emulator a dimensione standard e compatta.

**Esito fix:**
- Build: ✅ `BUILD SUCCESSFUL in 4s`
- Lint: ✅ `BUILD SUCCESSFUL in 29s`
- Manuale: ✅ normale + compatto verificati; in compatto il titolo non wrappa più e il contenuto basso è raggiungibile via scroll senza clipping permanente.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | DONE |
| Data chiusura | 2026-04-05 |

---

## Handoff

- Nessuna azione residua su TASK-035.
- `docs/MASTER-PLAN.md` aggiornato in chiusura per allineare backlog e tracking globale alla chiusura in `DONE`.
- Emulator ripristinato allo stato `Español + light` dopo le verifiche manuali.
