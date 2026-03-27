# Protocollo di Esecuzione — MerchandiseControlSplitView (Android)

> Protocollo operativo per execution e fix nel progetto Android.
> Riferimento per l'esecutore (AGENTS.md) e per il reviewer (CLAUDE.md).

---

## Tipologie di verifica

Ogni verifica rientra in una di queste categorie:

| Tipo       | Codice | Descrizione                                                  | Quando richiesta                  |
|------------|--------|--------------------------------------------------------------|-----------------------------------|
| **BUILD**  | `B`    | Compilazione Gradle (`assembleDebug`)                        | **Sempre**                        |
| **STATIC** | `S`    | Lint, analisi statica, warning Kotlin                        | **Sempre** (se disponibile)       |
| **MANUAL** | `M`    | Test manuale su emulator o device                            | Solo se specificato nel task      |
| **EMULATOR** | `E`  | Test automatizzati su emulator (instrumented tests)          | Solo se specificato nel task      |

---

## Verifiche standard (sempre richieste)

Queste verifiche devono essere eseguite per ogni task che modifica codice, risorse o configurazione build.

> **Eccezione task solo documentazione/governance:** Se il task modifica esclusivamente file `.md`,
> configurazione governance o documentazione (nessun file `.kt`, risorse, o build config modificati),
> i check BUILD, STATIC e WARNING sono **non applicabili** (N/A). Restano obbligatori: Coerenza con
> planning e Criteri di accettazione.

### 1. BUILD — Compilazione Gradle

```bash
./gradlew assembleDebug
```

**Criterio di successo:** `BUILD SUCCESSFUL` senza errori.

**Come riportare:**
```
Build: ✅ ESEGUITO — BUILD SUCCESSFUL in Xs, 0 errors
```

Se fallisce:
```
Build: ❌ NON ESEGUITO — errore: [messaggio di errore sintetico]
```

### 2. STATIC — Lint e analisi statica

```bash
./gradlew lint
```

**Criterio di successo:** nessun warning nuovo introdotto rispetto allo stato pre-modifica.

**Come riportare:**
```
Lint: ✅ ESEGUITO — 0 nuovi warning introdotti
```

Se non disponibile o non applicabile:
```
Lint: ⚠️ NON ESEGUIBILE — [motivazione: es. ambiente CI non disponibile]
```

### 3. WARNING — Nessun warning Kotlin nuovo

Verificare nell'output di build che non ci siano nuovi warning Kotlin (deprecation, unchecked cast, ecc.).

**Come riportare:**
```
Warning Kotlin: ✅ ESEGUITO — nessun warning nuovo nel codice modificato
```

---

## Quando eseguire test emulator/device

I test su emulator o device fisico **NON** sono richiesti di default.

Sono richiesti **solo** quando il file task specifica esplicitamente una di queste condizioni:

1. Il criterio di accettazione include "verificare su emulator" o "test manuale su device".
2. Il task è classificato come `MANUAL_TEST_REQUIRED` nelle decisioni.
3. Il planner ha inserito nella review una richiesta di test manuale.

### Se richiesti, come eseguirli

```bash
# Test instrumentati
./gradlew connectedDebugAndroidTest

# Oppure test manuale: documentare i passi eseguiti
```

**Come riportare test manuali:**
```
Test manuale: ✅ ESEGUITO
- Passi: [elenco dei passi eseguiti]
- Risultato: [comportamento osservato]
- Device/Emulator: [modello o API level]
```

### Se non richiesti

Non menzionare test emulator/device nel report. Le verifiche BUILD + STATIC sono sufficienti.

---

## Quando bastano verifiche statiche/build

Per la maggior parte dei task, le verifiche statiche sono sufficienti:

- **Modifiche a codice Kotlin** (ViewModel, Repository, DAO, utility): BUILD + STATIC
- **Modifiche a Composable** (UI): BUILD + STATIC (le @Preview esistenti verificano la compilazione)
- **Modifiche a Room** (entity, DAO, migration): BUILD + STATIC
- **Modifiche a risorse** (strings.xml, colors.xml): BUILD
- **Modifiche a build config** (build.gradle.kts, libs.versions.toml): BUILD
- **Modifiche solo documentazione**: nessuna verifica tecnica richiesta

---

## Come riportare evidenze nel task

Le evidenze vanno nella sezione `Execution` del file task, nel formato standard:

```markdown
### Check obbligatori

| Check                    | Tipo | Stato | Evidenza                          |
|--------------------------|------|-------|-----------------------------------|
| Build Gradle             | B    | ✅    | BUILD SUCCESSFUL in 45s           |
| Lint                     | S    | ✅    | 0 nuovi warning                   |
| Warning Kotlin           | S    | ✅    | Nessun warning nel codice modificato |
| Coerenza con planning    | —    | ✅    | Tutte le azioni pianificate eseguite |
| Criteri di accettazione  | —    | ✅    | Tutti i criteri verificati (vedi sotto) |
```

### Dettaglio criteri di accettazione

Per ogni criterio nel file task:

```markdown
| # | Criterio                                    | Verifica | Stato | Evidenza |
|---|---------------------------------------------|----------|-------|----------|
| 1 | Il file X esiste e contiene Y               | S        | ✅    | Verificato: file presente, contenuto corretto |
| 2 | La build compila senza errori               | B        | ✅    | BUILD SUCCESSFUL |
| 3 | Test manuale su emulator mostra Z           | M        | ⚠️    | Non eseguibile: nessun emulator configurato |
```

---

## Come comportarsi se l'ambiente non permette una verifica

Se una verifica non è eseguibile per motivi ambientali (es. no emulator, no connessione, no SDK aggiornato):

1. **Non inventare il risultato.** Non scrivere "✅" se non hai eseguito.
2. **Usa lo stato ⚠️ NON ESEGUIBILE** con motivazione chiara.
3. **Proponi un'alternativa** se possibile (es. "verificabile con `./gradlew test` locale").
4. **Documenta nel Handoff** cosa andrà verificato manualmente.

Esempio:
```
Lint: ⚠️ NON ESEGUIBILE — l'ambiente corrente non ha Android SDK configurato.
Alternativa: eseguire `./gradlew lint` in Android Studio.
Handoff: verificare lint alla prossima sessione con ambiente completo.
```

---

## Distinzione BUILD / STATIC / MANUAL / EMULATOR — riepilogo decisionale

```
Modifico codice Kotlin?
  └─ SÌ → BUILD (sempre) + STATIC (sempre)
       └─ Il task richiede test manuali?
            └─ SÌ → MANUAL
            └─ NO → stop, verifiche sufficienti
       └─ Il task richiede test instrumentati?
            └─ SÌ → EMULATOR
            └─ NO → stop, verifiche sufficienti

Modifico solo documentazione/governance?
  └─ SÌ → BUILD/STATIC/WARNING = N/A; restano obbligatori Coerenza e Criteri
  └─ NO → (vedi sopra)
```

---

## Regola Android / iOS

- **Android repo** = fonte di verità per architettura, business logic, Room, repository, ViewModel, barcode, import/export Excel, navigation, integrazioni piattaforma.
- **iOS repo** = riferimento per UX/UI: gerarchia visiva, layout, spacing, stati, toolbar, dialog, bottom sheet, affordance.
- Vietato fare porting 1:1 da SwiftUI a Compose: adattare la UX in modo idiomatico Compose/Material3.
- Vietato rimuovere feature Android funzionanti solo perché la controparte iOS è più semplice.
- Se Android e iOS divergono, preservare la logica e le capacità Android; adottare solo il pattern UX/UI che migliora l'esperienza utente.

---

## Note specifiche per il progetto

### Room / Migrazioni database
- Se il task modifica entity o DAO, la build Gradle è la verifica primaria (Room genera codice a compile-time).
- Se il task aggiunge una migrazione, verificare che l'app non crashi con database esistente (richiede MANUAL o EMULATOR, da specificare nel task).

### Compose / UI
- Le `@Preview` compilano durante la build: se la build passa, le preview sono valide.
- Per verifiche visive (layout, colori, comportamento), serve MANUAL.
- Non sono richieste verifiche visive salvo indicazione esplicita nel task.

### Excel / Apache POI
- Le operazioni Excel sono verificabili con unit test o build.
- Se il task modifica import/export, considerare di suggerire test con file Excel campione nel handoff.

### Barcode / ZXing
- Il barcode scanner richiede device fisico o emulator con camera.
- Test barcode = MANUAL, mai richiesto di default.
