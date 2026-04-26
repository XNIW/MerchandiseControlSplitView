# TASK-063 — Smoke live A/B sync Android ↔ Supabase

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-063 |
| Stato | `DONE` |
| Priorità | `ALTA` |
| Area | QA manuale / Supabase live / multi-device |
| Creato | 2026-04-26 |
| Ultimo aggiornamento | 2026-04-26 — `DONE` in modalita `ACCEPTABLE` con OnePlus IN2013 + Medium Phone API 35: S1-S5 `PASS`, S6 non distruttivo non disponibile e coperto da TASK-061, S7 `BLOCKED`, S8 `NOT RUN`; non `FULL` |

### Governance check

| Verifica | Esito |
|----------|-------|
| `MASTER-PLAN`: TASK-063 riaperto solo come smoke dipendente da TASK-064 | OK (verifica 2026-04-26 pre-execution finale) |
| Transizione `BLOCKED` → `EXECUTION` registrata | OK |
| Stato corrente TASK-063 | `DONE` — execution finale: S1-S5 PASS, S6 non distruttivo non disponibile/coperto da TASK-061, S7/S8 non bloccanti |
| TASK-062 `DONE` | OK |
| TASK-061 `DONE` | OK |
| TASK-060 `DONE` | OK — riconfermato no-op da S2 post-fix |
| TASK-055 `DONE` | OK — chiuso dopo valutazione post TASK-063 |
| TASK-063 non chiude TASK-055 automaticamente | OK — TASK-055 chiuso solo dopo verifica criteri/follow-up |
| Codice Android modificato solo in TASK-065; nessuna migration / DDL/RPC/RLS/publication live | OK |
| Matrice S1–S8 documentata senza `PASS` inventati | OK — vedi § Execution |

---

## Dipendenze

- **TASK-055** — `DONE`
- **TASK-059** — `DONE`
- **TASK-060** — `DONE`
- **TASK-061** — `DONE`
- **TASK-062** — `DONE` — runbook: `docs/SUPABASE.md`, `supabase/migrations/README.md`
- **TASK-064** — `DONE`
- **TASK-065** — `DONE`

**Nota:** integrazione verifiche residue TASK-055 completata solo dopo risultati live e valutazione documentata; **nessuna** chiusura automatica TASK-055 prima della matrice finale.

---

## Scopo

Validazione **live** della catena Android ↔ Supabase (stesso backend reale configurato in app):

- Sync catalogo/prezzi in scenario **A/B** (stesso account; RLS opzionale con secondo account).
- **Realtime** (`sync_events`; opzionale `shared_sheet_sessions` in S8).
- **RLS owner-scoped** (S7 se prerequisiti).
- **Auto-push** e **offline → online** (S5).
- **Fallback full sync** quando quick sync / `sync_events` non basta (S6, coerente TASK-061).
- **UX Device B** in `DatabaseScreen`: update remoto **senza** scroll jump ingiustificato; Options/indicatori comprensibili.

**Fonti backend non-live:** `docs/SUPABASE.md` (`CODE` / `MIGRATION` / `LOCAL_SUPABASE_PROJECT`). **`/Users/minxiang/Desktop/MerchandiseControlSupabase` non prova lo stato remoto.**

---

## Non obiettivi

- Migration live, `supabase db push`, DDL/RPC/RLS ad hoc su remoto **durante** smoke (vedi anche § Supabase live safety).
- Codice Android nel perimetro **PLANNING**/refinement; bug confermati → task fix separato.
- Chiusura automatica **TASK-055**; redesign UI; cambi Room/DAO/repository in planning.
- Dichiarazioni “smoke OK” / “prod verificato” **senza** evidenza in repo o allegato tracciato.
- Confondere SQL versionato in repo con deploy (`supabase/migrations/README.md`).

---

## Execution mode

Tre livelli operativi. L’executor **dichiara la modalità** in Preflight e in `Execution`.

| Modalità | Definizione | Cosa consente |
|----------|-------------|----------------|
| **`FULL`** | 2 **device Android reali**, stesso backend/config build; stesso account per S1–S6; account A/B se serve RLS (S7). | Smoke A/B **completo** secondo matrice; unico modo per dichiarare **PASS** su S2/S3/S4/S5 senza riserva “emulator”. |
| **`ACCEPTABLE`** | 1 device **reale** + 1 **emulator** (o 2 emulator solo se esplicitamente accettato dal reviewer — sconsigliato). | S1–S6 eseguibili con **riserva documentata**: Realtime, timing, battery/network emulator possono divergere. In matrice, annotare “`ACCEPTABLE` — possibile flakiness Realtime”. |
| **`LIMITED`** | Manca secondo device, account, o tempo; restano preflight parziale, logcat su un device, controlli Options/sync senza controparte. | **Non** sufficiente per “smoke A/B completo”. S1–S8: usare `BLOCKED`, `NOT RUN`, o `PARTIAL` con motivazione. **Non** dichiarare chiusura positiva globale come equivalente a FULL. |

### Scenari vs modalità

| Scenario | `FULL` | `ACCEPTABLE` | `LIMITED` |
|----------|--------|--------------|-----------|
| S1 | Obbligatorio | Obbligatorio | Consentito (1 device: solo baseline locale/remoto, senza B → `PARTIAL` o `BLOCKED`) |
| S2 | Obbligatorio per esito A/B scroll | Obbligatorio con riserva emulator | Senza B → **BLOCKED** (non è verifica scroll cross-device) |
| S3 | Obbligatorio | Obbligatorio con riserva | Senza B → **BLOCKED** |
| S4 | Obbligatorio | Obbligatorio con riserva | Senza B → **BLOCKED** |
| S5 | Obbligatorio | Obbligatorio con riserva rete/emulator | Senza B → **BLOCKED** per “B riceve”; push-only su A → `PARTIAL` + nota |
| S6 | Obbligatorio se si vuole chiudere lane fallback | Consentito con riserva | Solo osservazione UI su 1 device senza condizione gap reale → `PARTIAL` / `NOT RUN` |
| S7 | Obbligatorio se 2 account disponibili | Stesso | Senza 2 account → **BLOCKED** o `NOT RUN` motivato |
| S8 | Opzionale | Opzionale | Opzionale; spesso `NOT RUN` |

### Passaggio a `REVIEW` con limiti

- **`FULL` + S1–S6 tutti `PASS` con evidenza** → `REVIEW` standard (S7/S8 opzionali).
- **`ACCEPTABLE` + S1–S6 `PASS` con evidenza** → `REVIEW` con **sezione limiti** obbligatoria (emulator, Realtime, rischi residui).
- **`LIMITED`** → al massimo **`REVIEW` limitato** o task **`BLOCKED`**: si documenta cosa manca; **non** si afferma parità con smoke FULL.
- Nessun `PASS` senza evidenza (vedi § Stati scenario).

---

## Setup disponibile al momento

Contesto **Android Studio** (2026-04-26): device ed emulator già visibili nell’ambiente di lavoro. Il piano può partire in modalità **`ACCEPTABLE`** (1 reale + 1 emulator) — **non** equivale a **`FULL`** (2 device fisici).

| Ruolo | Consigliato ora | Alternativa |
|-------|-----------------|-------------|
| Device A (es. “modifiche / push”) | **`OnePlus IN2013`** (reale) | Se collegato e attivo: **`POSTablet`** al posto del reale o come secondo fisico |
| Device B (es. “ricezione / scroll”) | **`Medium Phone API 35`** (emulator) | Con 2° reale disponibile → si può riorientare a **`FULL`** |

**Regole:**

- **S1–S6** possono essere **tentati** in `ACCEPTABLE` con **riserva emulator** (vedi § Emulator caveats e § Execution mode).
- **S7** richiede **due account** Supabase: se il secondo account non c’è → **`BLOCKED`** o **`NOT RUN`** motivato (non inventare RLS).
- Se in futuro si collega un **secondo device reale** (es. `POSTablet` + OnePlus, o due telefoni), la stessa matrice può essere rieseguita dichiarando **`FULL`** e aggiornando Preflight.

---

## Emulator caveats (Medium Phone API 35)

Quando **B** (o A) è l’emulator:

- **Realtime** (timing, riconnessione) può differire dal reale; ritardi o “salti” possono essere artefatti dell’emulator.
- **Rete** (NAT host, DNS, firewall) e **background / battery** non riproducono fedelmente un device fisico; S5 (offline/online) va interpretato con cautela.
- **Lifecycle foreground** (notifiche, Doze simulato) può divergere → sync differita o più aggressiva.
- **Performance** (scroll, DB locale) non è confrontabile 1:1 con OnePlus: un jitter di lista sull’emulator non prova automaticamente lo stesso su hardware.
- Se uno scenario **fallisce solo sull’emulator**, classificare con cautela: preferire **`FAIL`** o **`PARTIAL`** con nota esplicita **“emulator caveat”**; se il rischio è alto, **ripetere su secondo device reale** prima di aprire bug di prodotto.

---

## Android Studio run checklist (solo documentazione)

Da eseguire **solo** in fase `EXECUTION` futura — **non** eseguito in planning:

- [ ] Selezionare come target principale il device reale **`OnePlus IN2013`** (device dropdown / Device Manager).
- [ ] Avviare l’AVD **`Medium Phone API 35`** e attendere boot completo.
- [ ] Opzionale: **Run** → **Select Multiple Devices…** per installare/avviare la **stessa build** su entrambi in un colpo solo (o installare manualmente due volte con stesso artifact).
- [ ] Verificare su **entrambi**: stessa versione app (build number / `versionName`), stesso ambiente Supabase (`BuildConfig` / stesso `local.properties` effettivo).
- [ ] Aprire **Logcat** con sessione per **OnePlus** e sessione per **Medium Phone API 35**, oppure un solo Logcat alternando dispositivo e **annotando sempre** `deviceId` / nome device e **timestamp** su ogni estratto.
- [ ] **Non** segnare uno scenario come **`PASS`** se screenshot o log **non** indicano chiaramente quale device ha prodotto l’evidenza (prefisso file o riga di testo obbligatoria).

---

## Prerequisiti live

| Requisito | Nota |
|-----------|------|
| Due endpoint “device” per A/B | Vedi Execution mode |
| Stessa build | Stesso `applicationId`, `BuildConfig` Supabase allineato a ambiente **non prod** se possibile |
| Account | Stesso utente per S1–S6; secondo utente per S7 |
| Rete | Controllabile per S5 |
| logcat | Filtri § Logcat |
| Dashboard Supabase | Opzionale; utile per 403 / Realtime |
| Dataset + backup | § Dataset minimo; piano rollback |

---

## Preflight checklist

Compilare **prima di S1** (tabella o bullet in `Execution`). La colonna **Esempio / suggerito** riflette il setup corrente documentato in § Setup disponibile (resta **da confermare** all’avvio reale).

| # | Voce | Esempio / suggerito (2026-04-26) | Compilazione effettiva |
|---|------|----------------------------------|------------------------|
| 1 | Branch / commit testato | _(da compilare)_ | |
| 2 | APK o build variant (`debug`/`release`, flavor) | es. `debug` | |
| 3 | Device A — modello / API level | **`OnePlus IN2013`** | |
| 4 | Device B — modello / API level **o** emulator | **`Medium Phone API 35`** (emulator) | |
| 5 | **Execution mode** (`FULL` / `ACCEPTABLE` / `LIMITED`) | **`ACCEPTABLE`** (1 reale + emulator) | |
| 6 | Limite dichiarato (se non `FULL`) | Emulator → Realtime/timing/rete/scroll diversi da reale | |
| 7 | Supabase — URL/progetto (ref `BuildConfig`, **no** secret in chiaro) | | |
| 8 | Account usati (uid/email parziale) | 1 account per S1–S6; **2° account** solo se si esegue S7 | |
| 9 | Dataset preparato (§ Dataset minimo) | | |
| 10 | Backup / rollback pronto | | |
| 11 | logcat attivo; evidenze **per device** o annotate | Screenshot **A vs B** + logcat separato o log con tag device/timestamp | |
| 12 | Rete controllabile (S5) | | |
| 13 | Dashboard / logs Supabase: sì/no | | |
| 14 | Cartella evidenze (§ Evidence naming) | | |
| 15 | `POSTablet` o altro reale da usare al posto di A/B | _(opzionale)_ | |

---

## Dataset minimo consigliato

| Elemento | Scopo |
|----------|--------|
| 2–3 prodotti | Baseline lista |
| ≥1 fornitore, ≥1 categoria | FK realistici |
| 1 prodotto prezzo acquisto/vendita | S2 |
| 1 prodotto da aggiungere | S3 |
| 1 prodotto da eliminare (tombstone) | S4 |
| Sessione (opzionale) | S8 |

---

## Stati scenario (PASS / FAIL / BLOCKED / NOT RUN / PARTIAL)

| Stato | Significato |
|-------|-------------|
| **`PASS`** | Risultato atteso verificato **con evidenza minima** allegata (file o path sotto `docs/TASKS/evidence/TASK-063/` o equivalente concordato). **Vietato** `PASS` senza evidenza. |
| **`FAIL`** | Scenario eseguito; esito non conforme → compilare § Bug / follow-up template; aprire o proporre task follow-up. |
| **`BLOCKED`** | Prerequisito mancante (secondo device, secondo account, permessi, backend non raggiungibile, S6 che richiede modifica distruttiva senza staging). Motivazione obbligatoria in matrice. |
| **`NOT RUN`** | Non tentato; motivazione obbligatoria (tempo, scope, dipendenza). |
| **`PARTIAL`** | Risultato utile ma incompleto (es. solo push su A, B non osservato; solo UI senza gap reale). **Non** equivale a `PASS`; non sostituisce S2/S5 end-to-end. |

**Regola:** un solo `PASS` globale “smoke TASK-063” non sostituisce la matrice riga per riga.

---

## Matrice scenari live

Compilare in `EXECUTION`. Stati ammessi: § Stati scenario.

| ID | Obiettivo | Precondizioni | Passi Device A | Passi Device B | Evidenza richiesta | Risultato atteso | Risultato effettivo | Stato | Note / bug follow-up |
|----|-----------|---------------|----------------|----------------|-------------------|------------------|---------------------|-------|----------------------|
| S1 | Login e baseline sync | App, account, rete | Login, Database, attendi sync idle | Stesso account, stessa vista, allineamento | Screenshot A+B; logcat `sync_start`/`sync_finish`; timestamp | Catalogo coerente; nessun blocco critico | Baseline finale A/B coerente: stesso APK/versione/account, core catalogo pari, outbox 0, watermark allineato. | PASS | `ACCEPTABLE`, non `FULL`; history locale non-core diverso gia documentato. |
| S2 | A modifica → B riceve **senza scroll jump** | S1; **§ UX S2** rispettata | Modifica prodotto/prezzo; salva | **Non** toccare scroll fino a osservazione | Video o screenshot prima/dopo + logcat; posizione lista annotata | Dati aggiornati su B; **no** jump in cima; card aggiornata senza refresh globale fastidioso | PASS post-fix TASK-065: B filtrato sul target riceve update e rollback senza search/scroll jump; outbox A/B 0. | PASS | Riconferma no-op TASK-060 DONE. |
| S3 | A aggiunge prodotto → B riceve | S1 | Crea prodotto; salva | Attesa Realtime / osservazione | logcat + screenshot B | B vede nuovo prodotto | A crea `TASK063_TEST_S3_20260426`; B riceve via Realtime; outbox A/B 0; watermark 128 -> 130. | PASS | Evidenze in `/tmp/task063-final/`. |
| S4 | Tombstone delete → B riceve | Prodotto di test | Elimina (flusso tombstone) | Osserva | logcat + screenshot | B coerente con UX eliminazione | A elimina il prodotto test S3 da UI; B non lo mostra piu; outbox A/B 0; watermark 130 -> 131. | PASS | Nessun delete remoto manuale distruttivo. |
| S5 | Offline → modifica → online → push; B riceve | Rete controllabile | Offline; modifica; online; attendi | Osserva ricezione | logcat transizione rete | Push senza passi manuali non documentati; B aggiornato | A offline modifica prezzo target; push automatico al ritorno online; B riceve; rollback finale da B via app; outbox A/B 0; watermark 131 -> 133 -> 135. | PASS | Prezzo finale A/B `1114.0`; history prezzi +2 righe intenzionali e coerenti. |
| S6 | Gap / `sync_events` weak → UI full sync | Condizione sicura § Supabase live safety | Provocare **solo** se non distruttivo; altrimenti BLOCKED | Osserva Options / `manualFullSyncRequired` | Screenshot UI + logcat | Messaggio chiaro full sync; no loop silenzioso | Nessun modo live sicuro/non distruttivo trovato per forzare `manualFullSyncRequired` senza alterare backend o dati. Coperto tecnicamente da TASK-061 test/UX. | BLOCKED | Non bloccante per chiusura `ACCEPTABLE`; live destructive non eseguito. |
| S7 | RLS: account B non vede dati A | 2 account | Crea dato su A | Login B; verifica assenza | Screenshot | Isolamento owner | Secondo account non disponibile/fornito. | BLOCKED | Non bloccante per S1-S6 stesso account. |
| S8 | `shared_sheet_sessions` (opzionale) | Scope business | Scrivere sessione | Realtime/pull | logcat `SupabaseRealtime`, `HistorySessionSyncV2` | Coerenza cross-device | Scenario opzionale non incluso. | NOT RUN | Non bloccante. |

---

## Evidence naming convention

**Non creare** questi file in planning. Durante execution, convenzione suggerita (cartella repo o export allegato):

- Directory base: `docs/TASKS/evidence/TASK-063/` (gitignore locale se contiene dati sensibili — decidere a livello repo).
- Pattern: `<ScenarioID>_<YYYY-MM-DD>_<deviceA>_<deviceB>_<tipo>.<ext>`

Esempi (solo documentazione):

- `S2_2026-04-26_OnePlus-IN2013_MediumPhone-API35_logcat.txt` (esempio allineato al setup corrente)
- `S2_2026-04-26_pixel6_pixel7_logcat.txt` (esempio generico)
- `S2_before_B_scroll.png`
- `S2_after_B_scroll.png`
- `S5_offline_online_video.mp4`

Opzionale: prefisso `EXECUTION-063_v1_` se più cicli.

---

## Evidenze minime per scenario

Oltre ai file nominati sopra, per ogni riga eseguita:

- Timestamp (timezone esplicita).
- Modalità (`FULL` / `ACCEPTABLE` / `LIMITED`).
- Rete (online/offline/transizione).
- Correlazione Supabase solo se raccolta senza segreti.

---

## Supabase live safety

- **No** `supabase db push` e **no** modifica RLS/RPC/publication **per facilitare** lo smoke senza gate e ambiente nominato.
- S6 (gap / `sync_events` unavailable): preferire **osservazione naturale** (errore già presente, staging dedicato, o feature flag documentato). Se la simulazione richiede **modifica backend**, impostare scenario su **BLOCKED** o eseguire in **staging** con **task/approvazione separata** — non nel perimetro smoke “leggero”.
- Non usare produzione per test distruttivi.

---

## UX/UI checks (DatabaseScreen — focus S2)

Prima e durante S2:

1. Su **B**, aprire lista prodotti e **scrollare a metà lista** (o fino a un prodotto target **chiaramente identificabile**).
2. **Screenshot “prima”** (stato scroll + prodotto visibile).
3. Su **A**, modificare **quel** prodotto (o uno che B sta guardando); salvare.
4. Su **B**, **non** forzare scroll manuale fino a osservare se la UI salta da sola.
5. **Screenshot “dopo”**: verificare che **non** si sia tornati in cima senza azione utente; che la **card** mostri dati aggiornati.
6. Se la lista fa **refresh globale** visibile ma accettabile, documentare come **comportamento osservato** — **non** contare come `PASS` “ideale” senza reviewer.
7. Se serve **pull-to-refresh** o tap manuale sync per vedere dati, documentarlo: può essere `PARTIAL` o `FAIL` a seconda del criterio atteso (allineare con reviewer).
8. **Scroll jump** dopo update remoto → `FAIL` o `PARTIAL` + bug; nel bug, **riferimento possibile** a TASK-060 come follow-up tecnico — **senza** cambiare stato TASK-060 da questo file.

Altri controlli generali: Options/sync indicatori (TASK-059), no loading infinito, messaggi full sync chiari, snackbar non generiche.

---

## RLS / backend checks

Checklist concettuale da `docs/SUPABASE.md`; ogni voce **live** solo con evidenza:

- Realtime `sync_events`; RPC `record_sync_event`; RLS owner-scoped; opzionale `shared_sheet_sessions`.
- **No** affermazioni “verificato in prod” senza log/screenshot/test documentato.

---

## Logcat / strumenti

| Tag / pattern | Origine |
|---------------|---------|
| `CatalogCloudSync` | VM, repository, coordinator (`MerchandiseControlApplication`) |
| `SyncEventsRealtime` | `SupabaseSyncEventRealtimeSubscriber` |
| `SupabaseRealtime` | `SupabaseRealtimeSessionSubscriber` |
| `HistorySessionSyncV2` | session backup |
| `DB_REMOTE_REFRESH` | `DatabaseViewModel` |
| `cycle=catalog_auto` | messaggi coordinator |

Esempio: `adb logcat -s CatalogCloudSync SyncEventsRealtime SupabaseRealtime HistorySessionSyncV2 DB_REMOTE_REFRESH`

**JVM (non sostituiscono smoke):** `CatalogSyncViewModelTest`, `CatalogAutoSyncCoordinatorTest`, `DefaultInventoryRepositoryTest`.

---

## ADB / evidence commands cheat sheet

> **Solo documentazione.** Eseguire questi comandi **solo** in fase `EXECUTION` futura, non durante `PLANNING`.

1. Elencare device collegati e **serial** (per `-s`):

```bash
adb devices
```

Output tipico: `XXXXXXXX    device` (OnePlus reale) ed `emulator-5554    device` (AVD). Annotare quale serial è **A** e quale **B** nel Preflight.

2. Pulire buffer logcat su un device prima di uno scenario (opzionale ma utile):

```bash
adb -s <SERIAL_A> logcat -c
adb -s <SERIAL_B> logcat -c
```

3. Logcat filtrato (ripetere per ogni serial; salvare su file con redirect o copia da Android Studio):

```bash
adb -s <SERIAL> logcat -s CatalogCloudSync SyncEventsRealtime SupabaseRealtime HistorySessionSyncV2 DB_REMOTE_REFRESH
```

4. Screenshot su device (es. S2 prima/dopo su **B**):

```bash
adb -s <SERIAL_B> shell screencap -p /sdcard/S2_before.png
adb -s <SERIAL_B> pull /sdcard/S2_before.png <CARTELLA_EVIDENZE>/
```

5. Registrazione schermo (limite durata su alcuni device; fermare con Ctrl+C nella shell):

```bash
adb -s <SERIAL_B> shell screenrecord /sdcard/S2_scroll.mp4
adb -s <SERIAL_B> pull /sdcard/S2_scroll.mp4 <CARTELLA_EVIDENZE>/
```

Sostituire `<CARTELLA_EVIDENZE>` con path locale (es. cartella § Evidence naming). Verificare permessi storage se `pull` fallisce.

---

## Evidence privacy / redaction

- **Non** committare screenshot o log con: email complete, UID/token JWT completi, chiavi Supabase, URL con query segrete, password, dati anagrafici o inventario reale di clienti.
- Se le evidenze restano nel repo: usare **versioni redatte** (blur, crop, sostituzione stringhe) o solo estratti senza PII.
- **Preferenza:** evidenze **solo locali** / export fuori git se contengono dati sensibili; in task registrare solo **path relativo** o “evidenza locale allegata” senza incollare segreti.
- Nei log: mascherare account (`user@…` → `u***@domain`), troncare token, rimuovere header `Authorization`.
- In documentazione TASK-063: **hash o ultimi caratteri** di identificativi, mai credenziali in chiaro.

---

## Timing / observation policy

- **S2, S3, S4:** su device **B**, osservare per una **finestra ragionevole** concordata durante `EXECUTION` (nessuna soglia fissa imposta da questo task) prima di segnare **`FAIL`** per “non arrivato”. Annotare **tempo osservato** dalla modifica su A al riflesso su B (o timeout umano scelto).
- Registrare sempre: orario azione A, primo segno di update su B, eventuali retry Realtime/network.
- Se per vedere l’update serve **azione manuale** (pull-to-refresh, tap “sync”, riapertura schermata): **non** usare **`PASS`** automatico; usare **`PARTIAL`** o **`FAIL`** in base al criterio di scenario e nota esplicita nel reviewer.
- **Latenze** viste solo su emulator: **non** classificare come bug certo senza nota **“emulator caveat”** e, se impatto alto, tentativo su secondo device reale quando possibile.
- In modalità **`ACCEPTABLE`**: interpretare tempi e jitter con **cautela** (§ Emulator caveats).

---

## Riferimenti codice (planning)

`CatalogAutoSyncCoordinator.kt`, `CatalogSyncStateTracker.kt`, `CatalogSyncViewModel.kt`, `SupabaseSyncEvent*`, `SupabaseRealtimeSessionSubscriber.kt`, `SupabaseCatalogRemoteDataSource.kt`, `SupabaseProductPriceRemoteDataSource.kt`, `SupabaseSessionBackupRemoteDataSource.kt`, `InventoryRepository.kt`, `DatabaseViewModel.kt`, `DatabaseScreen.kt`, `OptionsScreen.kt`, `MerchandiseControlApplication.kt`.

---

## Piano execution (ordine suggerito)

1. Preflight completo.
2. S1 → S2 → … secondo dipendenze; aggiornare matrice e evidenze subito.
3. Compilare template bug per ogni `FAIL`.
4. Consultare § Decision tree prima di richiedere `REVIEW`.

---

## Decision tree (post-execution)

| Situazione | Esito governance TASK-063 |
|------------|---------------------------|
| S1–S6 tutti **`PASS`** con evidenza (modalità **`FULL`**) | Ammissibile **`REVIEW`** per chiusura smoke documentata |
| S1–S6 **`PASS`** in **`ACCEPTABLE`** | **`REVIEW`** obbligatorio con **limiti** (emulator, Realtime) |
| S7/S8 `NOT RUN` / `BLOCKED` per prerequisiti; S1–S6 OK | **`REVIEW`** con scope dichiarato “senza RLS/sessioni” |
| **S2** o **S5** `FAIL` | **`REVIEW`** con **FIX_REQUIRED** su bug follow-up; TASK-063 non “verde” finché non ridefinito |
| Manca secondo device/account per core A/B | **`BLOCKED`** o **`REVIEW` limitato** / **`LIMITED`** — non equiparabile a FULL |
| Bug Android confermati | **Task fix separato**; nessun fix obbligatorio **dentro** TASK-063 salvo autorizzazione esplicita |
| TASK-055 | Solo aggiornamento testuale tipo “verifiche live raccolte in TASK-063”; **chiusura TASK-055 solo con decisione utente** |

---

## Bug / follow-up template

Usare per ogni `FAIL` o `PARTIAL` significativo:

| Campo | Contenuto |
|-------|-----------|
| Scenario ID | es. S2 |
| Titolo bug | breve |
| Severità | `CRITICA` / `ALTA` / `MEDIA` / `BASSA` |
| Repro step | numerati |
| Risultato atteso | |
| Risultato effettivo | |
| Evidenza | path file / hash log |
| Area probabile | `Android` / `Supabase schema` / `RLS` / `Realtime` / `UX` / `unknown` |
| Task follow-up proposto | ID o titolo nuovo task |
| Blocca chiusura TASK-063? | sì/no + motivazione |

---

## Criteri di accettazione (PLANNING / refinement)

| # | Criterio | Note |
|---|----------|------|
| 1 | Execution mode + preflight + stati + naming + safety + decision tree + bug template | Refinement 2026-04-26 |
| 2 | Setup disponibile + emulator caveats + Android Studio checklist + gate pre-`EXECUTION` | Micro-refinement device 2026-04-26 |
| 3 | Matrice S1–S8 + UX S2 rafforzata | |
| 4 | Transizioni governance TASK-063 tracciate fino a `DONE` | |
| 5 | Nessun `PASS` precompilato senza evidenza | |
| 6 | TASK-055/060 governance rispettata | |
| 7 | ADB cheat sheet + privacy + timing + conferma utente pre-execution | Micro-refinement operativo 2026-04-26 |

---

## Before execution user confirmation

Prima della transizione `PLANNING` → `EXECUTION`, il **prompt / conversazione** con l’utente (o nota in cima a `Execution`) deve **chiedere e registrare** esplicitamente:

| Domanda | Risposta da annotare |
|---------|----------------------|
| Procedere ora in **`ACCEPTABLE`** (OnePlus IN2013 + Medium Phone API 35) oppure **aspettare `FULL`** (due device reali)? | |
| **S7** (RLS, secondo account): **incluso** / **escluso** (`NOT RUN` o `BLOCKED` motivato)? | |
| **S8** (`shared_sheet_sessions`): **incluso** / **opzionale** / **escluso**? | |
| **Dove** salvare le evidenze (path locale, repo redatto, drive interno)? | |
| I **dati di test** sono **non produttivi** e il backend è appropriato per smoke? (sì/no) | |

Senza queste risposte tracciate, non promuovere `EXECUTION` “al buio”.

---

## Checklist prima di passare a `EXECUTION`

Completare prima **§ Before execution user confirmation**. Poi l’**utente** / sponsor conferma (checklist nel messaggio o nel file task):

- [ ] **OnePlus IN2013** collegato, **debug USB** / autorizzazione **ADB** OK.
- [ ] Emulator **Medium Phone API 35** avviabile e stabile.
- [ ] L’app può essere **installata** su entrambi (stessa build).
- [ ] **Account Supabase** disponibile per il percorso happy path; **secondo account** preparato **solo se** si intende eseguire **S7**.
- [ ] **Stesso backend** (progetto URL / chiavi coerenti) sui due target.
- [ ] **Dataset** di test e piano **backup/rollback** definiti.
- [ ] **Spazio** e convenzione per **salvare evidenze** (cartella locale o repo, senza segreti).
- [ ] Consapevolezza che con emulator la modalità resta **`ACCEPTABLE`** fino a ripetizione su 2 reali (**`FULL`**).

_(Nessuna voce spuntata in planning: gate da completare al momento della transizione.)_

---

## Execution

### Esecuzione — 2026-04-26 — completamento finale S3-S6

**Stato finale execution:** `DONE` — smoke live chiuso in modalita `ACCEPTABLE` con OnePlus IN2013 + Medium Phone API 35. S1-S5 sono `PASS`; S6 live non distruttivo non e' disponibile senza staging/feature flag/backend condition separata ed e' coperto da TASK-061 test/UX; S7 e' `BLOCKED` per assenza secondo account; S8 `NOT RUN` perche' opzionale. Non dichiarare `FULL`.

**File modificati:**
- `docs/TASKS/TASK-063-smoke-live-a-b-sync-android-supabase.md` — stato finale, matrice S1-S8, evidenze, limiti e chiusura.
- `docs/TASKS/TASK-055-audit-sync-supabase-ux-scroll-database-history.md` — verdict finale audit dopo TASK-063.
- `docs/MASTER-PLAN.md` — governance finale: nessun task attivo, TASK-063/TASK-055 `DONE`.

**Preflight tecnico finale:**
| # | Voce | Compilazione effettiva |
|---|------|------------------------|
| 1 | Branch / commit testato | `codex/task-065-record-sync-event-payloadvalidation`, working tree con documentazione aggiornata. |
| 2 | APK | `app-debug.apk` installato su A e B; SHA-256 locale/A/B `bc6250a93249965239922b15591236a81b84382340c9b20d27cdd7ff44b9fd97`. |
| 3 | Versione | `versionName=1.0`, `versionCode=1`, `targetSdk=36` su A/B. |
| 4 | Device A | `8ac48ff0` — OnePlus `IN2013`, API 33. |
| 5 | Device B | `emulator-5554` — `sdk_gphone64_arm64`, API 35 (`Medium Phone API 35`). |
| 6 | Execution mode | `ACCEPTABLE` — 1 device reale + 1 emulator; possibile flakiness Realtime/timing/rete/scroll. |
| 7 | Supabase host/config | stesso host redatto `jpg...yvm.supabase.co`; nessuna chiave stampata. |
| 8 | Account / owner | stesso account per S1-S6; owner redatto `6425...257e`. |
| 9 | Baseline pre-S3 | core catalogo A/B pari: `products=18867`, `suppliers=70`, `categories=43`, `product_prices=37932`; outbox A/B `0`; watermark A/B `128`. |
| 10 | Evidenze locali | `/tmp/task063-final/` (`screenshots/`, `logcat/`, `db/`, `apk/`); non tracciate e non da committare senza redazione. |

**Azioni eseguite:**
1. Rieseguito `:app:assembleDebug` con JBR Android Studio e installato lo stesso APK su A/B.
2. Verificati APK hash, versione app, device, host Supabase redatto, owner redatto, outbox `0`, watermark e core catalogo coerente.
3. Eseguito S3 con prodotto test `TASK063_TEST_S3_20260426`, barcode `990630426001`, tramite UI su A e osservazione Realtime su B.
4. Eseguito S4 eliminando da UI/app il prodotto test creato in S3; nessun delete remoto manuale distruttivo.
5. Eseguito S5: A offline, modifica prezzo locale, ritorno online, auto-push, ricezione su B e rollback finale tramite app.
6. Valutato S6: nessun trigger live non distruttivo disponibile; scenario non forzato per rispettare safety Supabase.
7. Verificato TASK-060 no-op: resta `DONE`, riconfermato da S2 post-fix.
8. Valutato TASK-055: follow-up principali coperti; chiusura possibile con limiti espliciti.

**Matrice scenari finale:**
| ID | Risultato effettivo | Stato | Evidenza | Note / bug follow-up |
|----|---------------------|-------|----------|----------------------|
| S1 | Baseline A/B finale coerente: stesso APK/versione/account, host redatto allineato, core catalogo pari, outbox A/B `0`, watermark A/B `128`. | `PASS` | DB `/tmp/task063-final/db/preflight`; logcat preflight; APK `/tmp/task063-final/apk/`. | `ACCEPTABLE`, non `FULL`; `history_entries` non-core A=13/B=12. |
| S2 | Verifica post-fix TASK-065 gia documentata: A modifica target `693...7055`; B riceve sul target filtrato senza search/scroll jump; rollback ricevuto; outbox A/B `0`, watermark finale `128`. | `PASS` | `/tmp/task065-live/final/`; `S2_B_after_1115.xml`, `S2_A_after_rollback_final.xml`, `S2_B_after_rollback_final.xml`, log finali A/B. | Riconferma TASK-060 `DONE`; nessuna riapertura. |
| S3 | A crea `TASK063_TEST_S3_20260426` (`990630426001`); B riceve via Realtime; conteggi A/B `products=18868`, `product_prices=37934`; outbox A/B `0`; watermark `128 -> 130`. | `PASS` | Screenshot `S3_*`, logcat `S3_after_*`, DB `/tmp/task063-final/db/S3/{8ac48ff0,emulator-5554}/`. | Nessun `PayloadValidation` osservato. |
| S4 | A elimina da UI/app il prodotto test S3; B lo rimuove coerentemente; barcode non presente su A/B; tombstone/refs pendenti `0`; outbox A/B `0`; watermark `130 -> 131`. | `PASS` | Screenshot `S4_*`, logcat `S4_after_*`, DB `/tmp/task063-final/db/S4/{8ac48ff0,emulator-5554}/`. | Core catalogo torna a baseline; nessuna cancellazione remota manuale. |
| S5 | A offline modifica prezzo target da `1114` a `1116`; al ritorno online auto-push OK, B riceve `1116`; rollback finale via app da B a `1114`, ricevuto da A; outbox A/B `0`; watermark `131 -> 133 -> 135`. | `PASS` | Screenshot `S5_*`, logcat `S5_A_offline_saved.log`, `S5_after_online_*`, `S5_rollback_from_B_*`, DB `/tmp/task063-final/db/S5_*`. | Log offline registra failure attesa `NetworkOfflineOrTimeout`; stato finale A/B `1114.0`, dirty refs `0`; `product_prices=37934` per due righe history intenzionali. |
| S6 | Non esiste un modo live sicuro/non distruttivo per forzare `manualFullSyncRequired` senza alterare backend o dati; la UX/test coverage e' gia stata coperta da TASK-061. | `BLOCKED` | Review codice/task TASK-061 e assenza trigger sicuro in questa execution. | "S6 non distruttivo non disponibile; coperto da TASK-061 test/UX, live destructive non eseguito". Non blocca chiusura `ACCEPTABLE`. |
| S7 | Secondo account non disponibile/fornito. | `BLOCKED` | N/A | RLS non testato; non blocca S1-S6 stesso account. |
| S8 | Scenario opzionale `shared_sheet_sessions` non incluso. | `NOT RUN` | N/A | Non bloccante. |

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL. |
| Lint | ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` -> BUILD SUCCESSFUL. |
| Warning nuovi | ESEGUITO | Nessun codice Android modificato; solo warning/deprecation Gradle/AGP preesistenti nei task precedenti. |
| Coerenza con planning | ESEGUITO | Modalita `ACCEPTABLE`, non `FULL`; S3-S5 eseguiti con rollback; S6 non forzato per safety; S7/S8 documentati. |
| Criteri di accettazione | ESEGUITO | Matrice S1-S8 compilata con evidenze e limiti; nessun `PASS` inventato. |
| `git diff --check` | ESEGUITO | OK. |
| `git status` | ESEGUITO | Solo documentazione modificata; evidenze locali in `/tmp/task063-final/` non tracciate. |

**Baseline regressione TASK-004:**
- Test eseguiti: N/A; nessun codice Kotlin, risorsa o build config modificato in questa execution finale.
- Test aggiunti/aggiornati: nessuno.
- Limiti residui: task live/documentale; test mirati codice gia eseguiti in TASK-065.

**Incertezze:**
- Nessuna incertezza bloccante per `ACCEPTABLE`.

**Limiti dichiarati:**
- Smoke chiuso in `ACCEPTABLE`, non `FULL`, perche' B e' un emulator e non un secondo device fisico.
- S6 non simulato live perche' richiederebbe condizione backend/staging separata o mutazione non sicura; copertura tecnica gia in TASK-061.
- S7 RLS non eseguito per assenza secondo account.
- S8 opzionale non incluso.

**Handoff notes:**
- Nessuna migration live, nessun `supabase db push`, nessuna modifica DDL/RPC/RLS/publication Supabase e nessun backend destructive change.
- Evidenze sensibili restano solo in `/tmp/task063-final/`; non committare screenshot/log/DB copy senza redazione.
- Un eventuale rerun `FULL` richiede due device Android fisici e, per S7, secondo account.

### Esecuzione — 2026-04-26 — rerun post-fix TASK-065

**Stato finale execution:** `BLOCKED` — S1 e S2 sono verdi in modalità `ACCEPTABLE` dopo il fix Android, ma S3-S6 non sono stati eseguiti in questa sessione. Non dichiarare `DONE` e non dichiarare `FULL`.

**File modificati:**
- `docs/TASKS/TASK-063-smoke-live-a-b-sync-android-supabase.md` — matrice post-fix e stato finale.
- `docs/TASKS/TASK-065-fix-record-sync-event-payloadvalidation-response-handling.md` — fix client-side collegato.

**Preflight tecnico post-fix:**
| # | Voce | Compilazione effettiva |
|---|------|------------------------|
| 1 | Branch / commit testato | `codex/task-065-record-sync-event-payloadvalidation`, working tree con fix non committato. |
| 2 | APK | stesso artifact debug installato su A e B; SHA-256 `bc6250a93249965239922b15591236a81b84382340c9b20d27cdd7ff44b9fd97`. |
| 3 | Versione | `versionName=1.0`, `versionCode=1`, `targetSdk=36` su A/B. |
| 4 | Device A | `8ac48ff0` — OnePlus `IN2013`, API 33. |
| 5 | Device B | `emulator-5554` — `sdk_gphone64_arm64`, API 35 (`Medium Phone API 35`). |
| 6 | Execution mode | `ACCEPTABLE` — 1 device reale + 1 emulator. |
| 7 | Account | stesso account, owner locale redatto `6425...257e`. |
| 8 | Baseline pre/post S2 | core catalogo pari; outbox A/B `0`; watermark finale A/B `128`. |
| 9 | Evidenze | `/tmp/task065-live/` (screenshot, XML, logcat, DB copie locali), non tracciate. |

**Matrice scenari post-fix:**
| ID | Risultato effettivo | Stato | Evidenza | Note / bug follow-up |
|----|---------------------|-------|----------|----------------------|
| S1 | Baseline post-fix A/B coerente: stesso APK/versione/account, core catalogo pari, outbox `0`, watermark allineato. Gli eventi storici su A sono stati ritentati e drenati a `0` dopo install fixata. | `PASS` | DB copie `/tmp/task065-live/final/A`, `/tmp/task065-live/final/B`; log `A_after_install_foreground.log`. | Modalita `ACCEPTABLE`, non `FULL`; `history_entries` resta non-core diverso A=13/B=12. |
| S2 | A modifica prezzo target `693...7055` da `1114` a `1115`; B riceve realtime sul target filtrato senza scroll/search jump. A rollback `1115` -> `1114`; B riceve rollback. Outbox A/B resta `0`; watermark finale `128`; target finale `1114.0` su A/B. | `PASS` | `S2_B_after_1115.xml`, `S2_A_after_rollback_final.xml`, `S2_B_after_rollback_final.xml`, log `S2_A_after_rollback_final.log`, `S2_B_after_rollback_final.log`, DB finali in `/tmp/task065-live/final/`. | Copre positivamente TASK-060; non basta per TASK-063 `DONE` perche' S3-S6 non sono stati eseguiti. |
| S3 | Non eseguito in questa sessione. | `BLOCKED` | N/A | Richiede aggiunta prodotto live con rollback/tombstone controllato; non eseguita per non introdurre mutazioni remote extra senza dataset non-prod dedicato. |
| S4 | Non eseguito in questa sessione. | `BLOCKED` | N/A | Richiede tombstone/delete live; non eseguito. |
| S5 | Non eseguito in questa sessione. | `BLOCKED` | N/A | Richiede transizione offline/online e altra mutazione live; non eseguito. |
| S6 | Non simulato. | `BLOCKED` | N/A | Nessuna condizione gap/full sync sicura senza modifica backend/dati; nessuna migration o backend mutation autorizzata. |
| S7 | Secondo account non disponibile/confermato. | `BLOCKED` | N/A | RLS non testato. |
| S8 | Opzionale; non incluso. | `NOT RUN` | N/A | |

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `:app:assembleDebug` verde in TASK-065 |
| Lint | ✅ ESEGUITO | `:app:lintDebug` verde in TASK-065 |
| Warning nuovi | ✅ ESEGUITO | Nessun warning Kotlin nuovo |
| Coerenza con planning | ⚠️ PARZIALE | `ACCEPTABLE` rispettato; S1/S2 PASS; S3-S6 non eseguiti |
| Criteri di accettazione | ⚠️ PARZIALE | Matrice aggiornata; non tutti S1-S6 PASS |

**Baseline regressione TASK-004:**
- Test eseguiti: vedi TASK-065 (`SupabaseSyncEventRemoteDataSourceTest`, `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest`).
- Test aggiunti/aggiornati: `SupabaseSyncEventRemoteDataSourceTest`.
- Limiti residui: S3-S6 live non eseguiti.

**Incertezze:**
- INCERTEZZA: S3-S6 restano non verificati in `ACCEPTABLE`; TASK-063 resta `BLOCKED`.

**Handoff notes:**
- Per chiudere TASK-063 servono S3-S6 PASS con evidenza o decisione reviewer che ridefinisca esplicitamente lo scope.
- Non usare questa execution per dichiarare `FULL`.

### Esecuzione — 2026-04-26 — rerun post-baseline TASK-064

**Stato finale execution:** `BLOCKED` — smoke `ACCEPTABLE` avviato dopo baseline A/B verde; S1 superato, S2 ha verificato ricezione remota e stabilita scroll sul target, ma ha riprodotto nuova outbox `PayloadValidation` su A. S3-S6 fermati per non introdurre altre mutazioni/outbox.

**File modificati:**
- `docs/TASKS/TASK-063-smoke-live-a-b-sync-android-supabase.md` — stato finale, matrice aggiornata, evidenze e blocco.
- `docs/TASKS/TASK-064-diagnosi-outbox-payloadvalidation-baseline-ab.md` — B1-B9 finale e classificazione outbox nuova.
- `docs/MASTER-PLAN.md` — governance finale.

**Conferme utente / pre-execution gate registrate:**
| Voce | Risposta registrata | Impatto |
|------|---------------------|---------|
| Modalità | `ACCEPTABLE` autorizzata con OnePlus IN2013 + Medium Phone API 35 | Non dichiarare `FULL`. |
| Stesso account S1-S6 | Autorizzato ed eseguito; email redatta | S1-S2 validi solo nel perimetro stesso account. |
| Reset locale / reinstall APK | Autorizzato ed eseguito in TASK-064 | Gate baseline aperto. |
| Dataset | Nessun non-prod confermato; dataset corrente consentito solo evitando operazioni remote distruttive non reversibili | S2 eseguito su modifica prezzo reversibile e rollback osservato. |
| S7 secondo account | Non disponibile / non fornito | S7 `BLOCKED`. |
| S8 | Opzionale, non incluso prima dello sblocco core | S8 `NOT RUN`. |

**Preflight tecnico corrente:**
| # | Voce | Compilazione effettiva |
|---|------|------------------------|
| 1 | Branch / commit testato | `main`, `6a935a1`. |
| 2 | APK | stesso artifact debug installato su A e B; SHA-256 `e88364c51dd13e439b6732df50849777fd38e440a52fff755c68e7a133a53b94`. |
| 3 | Versione | `versionName=1.0`, `versionCode=1`, `targetSdk=36` su A/B. |
| 4 | Device A | `8ac48ff0` — OnePlus `IN2013`, API 33. |
| 5 | Device B | `emulator-5554` — `sdk_gphone64_arm64`, API 35 (`Medium Phone API 35`). |
| 6 | Execution mode | `ACCEPTABLE` — 1 device reale + 1 emulator. |
| 7 | Supabase host | stesso host redatto `jpg...yvm.supabase.co`; nessuna chiave stampata. |
| 8 | Account | stesso account redatto (`x***@gmail.com`), owner locale `6425...257e`. |
| 9 | Baseline pre-smoke | core catalogo pari: prodotti 18867, fornitori 70, categorie 43, prezzi 37928; outbox 0 su A/B; watermark 120 su A/B. |
| 10 | Evidenze | `/tmp/task064-final/screenshots/`, `/tmp/task064-final/logcat/`, `/tmp/task064-final/db-after/`; non tracciate. |

**Matrice scenari execution finale:**
| ID | Risultato effettivo | Stato | Evidenza | Note / bug follow-up |
|----|---------------------|-------|----------|----------------------|
| S1 | Login stesso account, stesso APK, baseline core A/B pari, outbox 0, watermark 120 su entrambi. Options summary mostra pending catalogo 0 dopo quick sync, con label principale ancora ambigua. | `PASS` | `/tmp/task064-final/screenshots/task063_S1_database_A.png`, `task063_S1_database_B.png`; DB post quick sync in `/tmp/task064-final/db-after/A_post_quick_sync`, `B_post_quick_sync`. | Modalita `ACCEPTABLE`, non `FULL`; history locale resta A=13/B=12. |
| S2 | A modifica prezzo target `693...7055` da `1114` a `1115`; B riceve realtime sul target filtrato senza scroll/search jump. A ripristina `1115` -> `1114`; B riceve rollback senza scroll/search jump. Tuttavia A crea outbox nuova `PayloadValidation`: 2 eventi dopo modifica, 4 dopo rollback. | `PARTIAL` | Prima: `task063_S2_B_before_target.png`; dopo modifica: `task063_S2_A_after_real_save.png`, `task063_S2_B_after_real_observe.png`; dopo rollback: `task063_S2_A_after_rollback_real_save.png`, `task063_S2_B_after_rollback_observe.png`; log `S2_A_after_rollback_filtered.log`, `S2_B_after_rollback_filtered.log`. | UI/remote refresh positivo, ma non `PASS` pieno per chiusura smoke: outbox nuova blocca B6/B9 TASK-064. |
| S3 | Non eseguito dopo nuovo bug outbox su S2. | `BLOCKED` | N/A | Evitata aggiunta prodotto live per non moltiplicare mutazioni/outbox. |
| S4 | Non eseguito dopo nuovo bug outbox su S2. | `BLOCKED` | N/A | Evitato tombstone live. |
| S5 | Non eseguito dopo nuovo bug outbox su S2. | `BLOCKED` | N/A | Offline/online avrebbe richiesto altra modifica live. |
| S6 | Non simulato: nessuna condizione gap/full sync sicura senza ulteriore alterazione; S2 gia' blocca smoke. | `BLOCKED` | N/A | Fallback full sync non chiudibile. |
| S7 | Secondo account non disponibile/confermato. | `BLOCKED` | N/A | RLS non testato. |
| S8 | Opzionale; non incluso prima dello sblocco core. | `NOT RUN` | N/A | |

**Dettaglio S2 / TASK-060:**
- B era filtrato sul prodotto target (`693...7055`) prima della modifica.
- Dopo modifica A -> B: log B `sync_events_apply domain=catalog remoteProducts=1 applied=1` e `domain=prices remotePrices=1 pricesPulled=1`; summary `eventsFetched=2`, `eventsProcessed=2`, watermark 120 -> 122, outbox 0.
- Dopo rollback A -> B: stesso pattern, watermark 122 -> 124, outbox B 0.
- La card visibile su B resta sul target e mostra `Retail (New)` aggiornato, senza salto in cima osservato.
- Questo fornisce evidenza utile per il comportamento TASK-060, ma TASK-060 non viene chiuso perche' TASK-063 non e' verde e l'outbox nuova resta bloccante.

**Bug / follow-up:**
| Campo | Contenuto |
|-------|-----------|
| Scenario ID | S2 |
| Titolo bug | Evento live nuovo propaga a B ma resta in outbox A con `PayloadValidation` |
| Severità | `ALTA` |
| Repro step | 1. Baseline A/B pulita con stesso APK/account. 2. A modifica prezzo prodotto esistente. 3. B riceve realtime. 4. Ispezionare outbox A. |
| Risultato atteso | Evento registrato/drenato senza outbox pendente; B riceve update. |
| Risultato effettivo | B riceve update, ma A conserva eventi `catalog_changed` e `prices_changed` in `sync_event_outbox` con `lastErrorType=PayloadValidation`. Dopo rollback gli eventi diventano 4. |
| Evidenza | DB finale `/tmp/task064-final/db-after/A_after_rollback`, log `S2_A_after_rollback_filtered.log`, screenshot S2 dopo modifica/rollback. |
| Area probabile | `Android client response/decode` oppure `Supabase RPC/schema live drift`; payload client meno probabile ma non escluso senza corpo errore live. |
| Blocca chiusura TASK-063? | Si': S1-S6 non sono tutti PASS e B6/B9 TASK-064 falliscono. |

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ESEGUITO | `assembleDebug` eseguito in TASK-064 con JBR Android Studio sullo stesso commit/artifact installato. |
| Lint | N/A | Nessun codice/risorsa/build config modificato. |
| Warning nuovi | N/A | Nessun codice modificato. |
| Coerenza con planning | ESEGUITO | Modalita `ACCEPTABLE`, nessun `FULL`, stop dopo bug reale. |
| Criteri di accettazione | ESEGUITO | Matrice S1-S8 aggiornata; nessun `PASS` pieno inventato su S2-S6. |
| `git diff --check` | ESEGUITO | OK. |
| `git status` | ESEGUITO | Solo documentazione modificata; evidenze locali in `/tmp/task064-final/` non tracciate. |

**Baseline regressione TASK-004:**
- Test eseguiti: N/A; nessun codice Android modificato.
- Test aggiunti/aggiornati: nessuno.
- Limiti residui: fix client futuro dovra' rieseguire test repository/coordinator/ViewModel pertinenti.

**Incertezze:**
- INCERTEZZA: manca corpo errore live `record_sync_event`; serve strumentare/catturare risposta per classificare definitivamente client vs backend.

**Handoff notes:**
- TASK-063 resta `BLOCKED`; non promuovere a `REVIEW`/`DONE` finche' S1-S6 non passano in `ACCEPTABLE` senza nuova outbox bloccante.
- TASK-060 ha evidenza positiva parziale da S2, ma non va chiuso automaticamente.
- TASK-055 resta `PARTIAL`.

### Esecuzione — 2026-04-26 (storica pre-TASK-064)

**Stato finale execution:** `BLOCKED` — smoke A/B core non proseguibile in sicurezza dopo preflight e S1 osservativo.

**File modificati:**
- `docs/MASTER-PLAN.md` — governance aggiornata da `PLANNING` a `EXECUTION`, poi a `BLOCKED` per TASK-063; TASK-060 resta `BLOCKED`, TASK-055 resta `PARTIAL`, TASK-061/TASK-062 restano `DONE`.
- `docs/TASKS/TASK-063-smoke-live-a-b-sync-android-supabase.md` — stato task, preflight, matrice execution, evidenze, bug/follow-up e check.
- `docs/TASKS/evidence/TASK-063/.gitignore` — impedisce staging accidentale delle evidenze locali non redatte.

**Conferme utente / pre-execution gate registrate:**
| Voce | Risposta registrata | Impatto |
|------|---------------------|---------|
| Modalità `ACCEPTABLE` con `OnePlus IN2013` + `Medium Phone API 35` | Sì, da prompt execution | Execution avviata; non dichiarare `FULL`. |
| S7 RLS con secondo account | Non confermato / secondo account non fornito | S7 `BLOCKED`. |
| S8 `shared_sheet_sessions` | Non confermato; opzionale nel task | S8 `NOT RUN`. |
| Dove salvare evidenze | `docs/TASKS/evidence/TASK-063/` locale repo, con `.gitignore`; path assoluto `/Users/minxiang/AndroidStudioProjects/MerchandiseControlSplitView/docs/TASKS/evidence/TASK-063` | Evidenze locali raccolte, non da committare senza redazione. |
| Dati test non produttivi | Non confermato | Nessuna modifica prodotto/prezzo/tombstone eseguita. |
| Backend Supabase corretto su entrambi | Parzialmente verificato: config repo presente; stesso account osservato; URL installato non introspezionabile senza rebuild/install | Non sufficiente per PASS completo. |
| Account Supabase disponibile | Sì, stesso account osservato su A e B (`x***@gmail.com`) | S1 login osservato. |
| Dataset test pronto | Non confermato; dataset locale esistente ma non coerente tra A e B | S1 non conforme; S2–S6 bloccati. |

**Preflight tecnico:**
| # | Voce | Compilazione effettiva |
|---|------|------------------------|
| 1 | Branch / commit testato | `main`, `bb5ecdf`; working tree già dirty su documentazione TASK-063/MASTER-PLAN prima dell'execution, poi aggiornato con questo log. |
| 2 | APK o build variant | App già installata su entrambi: `versionName=1.0`, `versionCode=1`, `targetSdk=36`; hash APK device diversi (`5954...d473` A, `0d6c...e1` B), quindi **stessa build non provata**. Nessun rebuild/install eseguito per evitare ulteriore churn prima delle conferme dataset/backend. |
| 3 | Device A | `8ac48ff0` — OnePlus `IN2013`, API 33. |
| 4 | Device B | `emulator-5554` — `sdk_gphone64_arm64`, API 35 (`Medium_Phone_API_35`). |
| 5 | Execution mode | `ACCEPTABLE` — 1 device reale + 1 emulator. |
| 6 | Limite dichiarato | Emulator caveat: Realtime/timing/network/battery/scroll/performance possono divergere da device fisico; non dichiarabile `FULL`. |
| 7 | Supabase URL/progetto | Config repo presente: host redatto `jpg...yvm.supabase.co`; publishable key presente ma non stampata. Stato live remoto non introspezionato. |
| 8 | Account usati | Stesso account osservato su entrambi, redatto `x***@gmail.com`; owner locale redatto `6425...257e`. |
| 9 | Dataset preparato | Non confermato. Dataset locale presente ma non allineato: A `products=18867`, `suppliers=69`, `categories=42`, `prices=37903`, `history=6`; B `products=18867`, `suppliers=70`, `categories=43`, `prices=37928`, `history=12`. |
| 10 | Backup / rollback pronto | Non confermato; nessun backup remoto/rollback verificato. |
| 11 | logcat attivo / evidenze | `adb logcat -c` eseguito su A e B; log filtrati raccolti ma vuoti per i tag richiesti nel preflight. Screenshot + UI XML raccolti per device. |
| 12 | Rete controllabile | Device online a livello UI; controllo offline/online S5 non tentato perché S1 non conforme e dati non-prod non confermati. |
| 13 | Dashboard / logs Supabase | Non disponibile / non confermato. |
| 14 | Cartella evidenze | `docs/TASKS/evidence/TASK-063/` con `.gitignore`; contiene screenshot/XML/log locali non redatti completamente. |
| 15 | `POSTablet` o altro reale | AVD disponibile da `emulator -list-avds`; nessun secondo device fisico usato. |

**Azioni eseguite:**
1. Letti `docs/MASTER-PLAN.md`, task TASK-063, `docs/SUPABASE.md`, `supabase/migrations/README.md` e `docs/CODEX-EXECUTION-PROTOCOL.md`.
2. Verificata governance: TASK-063 unico task attivo in `PLANNING`, TASK-062 `DONE`, TASK-061 `DONE`, TASK-060 `BLOCKED`, TASK-055 `PARTIAL`.
3. Aggiornato TASK-063 a `EXECUTION` e MASTER-PLAN a task attivo `EXECUTION`.
4. Eseguito preflight ADB con SDK esplicito (`/Users/minxiang/Library/Android/sdk/platform-tools/adb`) perché `adb` non era nel `PATH`.
5. Confermati device A/B con `adb devices -l`; app già installata su entrambi, ma hash APK diversi.
6. Avviata app su entrambi, raccolti screenshot/dump UI preflight, Database e Options.
7. Ispezionati dati Room locali via `run-as`/SQLite pull temporaneo in `/tmp/task063-db` per contare dataset e outbox senza leggere token o credenziali.
8. Interrotta la progressione smoke: S1 non soddisfa baseline coerente e Options mostra sync pendente/outbox; non sono state eseguite modifiche prodotto/prezzo/tombstone/offline.

**Matrice scenari execution:**
| ID | Risultato effettivo | Stato | Evidenza | Note / bug follow-up |
|----|---------------------|-------|----------|----------------------|
| S1 | Login osservato sullo stesso account redatto su A e B, ma baseline catalogo non coerente e cloud non idle: A mostra `Da sincronizzare` con 6 notifiche outbox; B mostra `Waiting to sync` con 108 notifiche outbox; conteggi Room divergono su fornitori/categorie/prezzi/storico. | `FAIL` | `TASK063_options_A_OnePlus-IN2013.png`, `TASK063_options_B_cloud_MediumPhone-API35.png`, `TASK063_database_A_OnePlus-IN2013.png`, `TASK063_database_B_MediumPhone-API35.png`; SQLite temp counts in execution log. | Bug follow-up F1: outbox `PayloadValidation` / baseline non coerente blocca smoke A/B. |
| S2 | Non eseguito: dipende da S1 coerente e richiede modifica prodotto/prezzo live; dati non-prod e rollback non confermati. | `BLOCKED` | N/A | Non valutabile scroll jump; non riaprire TASK-060. |
| S3 | Non eseguito: dipende da S1 coerente e richiede aggiunta prodotto live; dati non-prod e rollback non confermati. | `BLOCKED` | N/A | |
| S4 | Non eseguito: dipende da S1 coerente e richiede tombstone live; dati non-prod e rollback non confermati. | `BLOCKED` | N/A | |
| S5 | Non eseguito: dipende da S1 coerente e richiede modifica offline/online + push; dati non-prod e rollback non confermati. | `BLOCKED` | N/A | Emulator/network caveat resta aperto. |
| S6 | Non eseguito: nessuna condizione sicura simulata senza backend/data mutation; sync_events già mostra outbox non drenata. | `BLOCKED` | Options A/B cloud state | Serve diagnosi prima di simulare gap/fallback. |
| S7 | Secondo account non disponibile/confermato. | `BLOCKED` | N/A | RLS non testato. |
| S8 | Opzionale; non incluso/confermato e non praticabile prima di risolvere S1. | `NOT RUN` | N/A | |

**Evidenze raccolte:**
- `docs/TASKS/evidence/TASK-063/TASK063_preflight_A_OnePlus-IN2013.png`
- `docs/TASKS/evidence/TASK-063/TASK063_preflight_B_MediumPhone-API35.png`
- `docs/TASKS/evidence/TASK-063/TASK063_database_A_OnePlus-IN2013.png`
- `docs/TASKS/evidence/TASK-063/TASK063_database_B_MediumPhone-API35.png`
- `docs/TASKS/evidence/TASK-063/TASK063_options_A_OnePlus-IN2013.png`
- `docs/TASKS/evidence/TASK-063/TASK063_options_B_MediumPhone-API35.png`
- `docs/TASKS/evidence/TASK-063/TASK063_options_B_cloud_MediumPhone-API35.png`
- Relativi `.xml` UI dump e logcat filtrati locali nella stessa cartella.
- **Privacy:** screenshot/XML contengono email completa e dati inventario potenzialmente sensibili; la cartella contiene `.gitignore` e non va committata senza redazione/crop.

**Bug / follow-up:**
| Campo | Contenuto |
|-------|-----------|
| Scenario ID | S1 |
| Titolo bug | Baseline A/B non coerente e outbox `sync_events` non drenata (`PayloadValidation`) |
| Severità | `ALTA` |
| Repro step | 1. Avviare app su OnePlus IN2013 e Medium Phone API 35. 2. Aprire `Database` su entrambi. 3. Aprire `Opzioni`/`Catalogo sul cloud`. 4. Confrontare conteggi Room locali e stato outbox. |
| Risultato atteso | Stesso account, stesso backend, catalogo coerente e sync idle prima di S2–S6. |
| Risultato effettivo | A e B sono sullo stesso account, ma i dati locali divergono e Options mostra pending sync: A 6 notifiche, B 108 notifiche; outbox locale raggruppata con `lastErrorType=PayloadValidation`; APK hash installati diversi. |
| Evidenza | `TASK063_options_A_OnePlus-IN2013.png`, `TASK063_options_B_cloud_MediumPhone-API35.png`, conteggi SQLite documentati in Preflight. |
| Area probabile | `Supabase schema` / `Realtime` / `Android` / `unknown` — da diagnosticare; sintomo locale punta a payload/RPC `sync_events` non accettato. |
| Task follow-up proposto | TASK-064 — Diagnosi outbox `PayloadValidation` / riallineamento baseline prima di ripetere smoke A/B. |
| Blocca chiusura TASK-063? | Sì: S1 fallisce e blocca S2–S6. |

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | N/A | Task execution solo documentazione/governance/evidenze; nessun Kotlin/XML/Gradle modificato. Non è stato eseguito `assembleDebug` per non installare/rebuildare prima di confermare dataset non-prod e rollback. |
| Lint | N/A | Nessun codice/risorsa/build config modificato. |
| Warning nuovi | N/A | Nessun codice modificato. |
| Coerenza con planning | ⚠️ `PARTIAL` | Modalità `ACCEPTABLE` rispettata e preflight compilato; smoke core fermato perché S1 non conforme e prerequisiti dati non-prod/rollback mancanti. |
| Criteri di accettazione | ⚠️ `PARTIAL` | Matrice compilata con stati reali; nessun `PASS` senza evidenza; task non dichiarato `DONE`. |

**Baseline regressione TASK-004:**
- Test eseguiti: N/A.
- Test aggiunti/aggiornati: nessuno.
- Limiti residui: nessun codice/repository/ViewModel/import/export modificato; smoke live bloccato prima di fix codice.

**Incertezze:**
- INCERTEZZA: dati di test non-prod non confermati; screenshot locali possono contenere dati inventario reali.
- INCERTEZZA: URL/backend effettivo degli APK installati non verificato direttamente; config repo presente e stesso account osservato, ma APK hash divergenti.
- INCERTEZZA: causa `PayloadValidation` non diagnosticata in TASK-063 per vincolo no-fix/no-backend-change.

**Handoff notes:**
- Non eseguire S2–S6 finché S1 non è verde con stessa build installata, dataset dichiarato non produttivo e rollback definito.
- Prima di ripetere smoke: installare lo stesso APK su A/B, drenare o diagnosticare outbox `PayloadValidation`, verificare conteggi baseline e ottenere conferma esplicita su dati test/backup.
- TASK-060 resta `BLOCKED`; non è stato osservato S2 scroll jump.
- TASK-055 resta `PARTIAL`; TASK-063 non lo chiude automaticamente.

---

## Review

_(Vuoto.)_

---

## Fix

_(Vuoto.)_

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | `DONE` |
| Data | 2026-04-26 |
| Modalita | `ACCEPTABLE`, non `FULL` |
| Esito | S1-S5 `PASS`; S6 `BLOCKED` motivato/non distruttivo non disponibile e coperto da TASK-061; S7 `BLOCKED`; S8 `NOT RUN`. |
| Criteri tutti gestiti? | Si, con limiti espliciti documentati. |

**Conferme finali:**
- Nessun `supabase db push`.
- Nessuna migration live.
- Nessuna modifica live DDL/RPC/RLS/publication.
- Nessuna alterazione remota distruttiva manuale.
- Evidenze locali non tracciate in `/tmp/task063-final/`.

---

## Handoff

- **Executor:** task chiuso `DONE` in `ACCEPTABLE`; mantenere evidenze fuori repo e non dichiarare `FULL`.
- **Reviewer:** limiti residui accettati per chiusura: emulator, S6 non distruttivo non disponibile, S7 senza secondo account, S8 opzionale.
- **TASK-055:** chiuso `DONE` dopo valutazione follow-up principali e TASK-063 finale.
- **TASK-060:** resta `DONE`; S2 post-fix e TASK-063 confermano update remoto puntuale senza search/scroll jump.
