# TASK-139 — Product Image Production Hardening e Cross-Platform Contract Parity (Android)

## Stato

- Stato: `DONE`
- Fase: `DONE`
- Responsabile: `USER / FINAL REVIEW APPROVED`
- Apertura: `2026-07-18`, brief esplicito utente cross-platform.
- Handoff: `DONE`; conferma utente ricevuta il `2026-07-25`.
- Baseline: `origin/main` `141ffa07b4ee2b556387ec194fb82b7b76e6a626`.

## Scope lane Android

- Consumare lo stesso contratto JSON e gli stessi vector Admin/iOS.
- Allineare la ladder di riduzione lato e quality senza dipendenze nuove.
- Verificare semantica API/errori/cache/retry/UI e assenza di blob/URL/path nel sync.
- Coprire processor, session/cache isolation, camera/file picker e input 48 MP.
- Eseguire unit, instrumentation Emulator, assemble, lint e diff check.

## Vincoli

Nessuna UI rewrite, terza variante, originale persistito, bucket pubblico,
dipendenza nuova, production, Win7POS, TASK-088, commit, push o merge.

## Acceptance criteria

| ID | Criterio |
|---|---|
| A-01 | Contratto/fixture Android identici byte-per-byte al canonico Admin. |
| A-02 | Test Kotlin consumano i vector JSON comuni e congelano ladder/quality/budget. |
| A-03 | API, errori, cache, retry e UI rispettano il contratto condiviso. |
| A-04 | Test unit/instrumentation, camera/file picker/48 MP, assemble/lint sono PASS oppure hanno blocker preciso. |
| A-05 | Nessun URL/path/blob immagini entra nel dominio/sync; production e Git remoti intatti. |

## Evidence

I log, i report runtime e le matrici di closeout sono conservati fuori
repository. Questo file mantiene l'handoff finale `DONE`; JVM, lint e build
post-fix sono registrati nell'archivio esterno del closeout e nella CI GitHub
indicata nella sezione finale.

## Fix post-review — recovery editor Android (2026-07-19)

Il test autenticato ha riprodotto un deadlock reale dopo un errore immagine:
un job progressivo rappresentato contemporaneamente da `MAIN` e `THUMB`
lasciava `THUMB=LOADING`, mentre la chiusura editor cancellava soltanto il job
main. La UI interpretava qualunque `LOADING` come mutation attiva e disabilitava
camera e libreria anche dopo chiusura/riapertura.

Fix scoped applicato:

- separazione tra read `LOADING` e mutation `UPLOADING`/`REMOVING`;
- cancellazione/invalidation dei read di entrambe le variant prima di una
  mutation e alla chiusura editor;
- rollback esatto allo stato stabile precedente, più CTA
  `Discard failed attempt` localizzata nelle quattro lingue;
- completamenti read stale ignorati durante una mutation;
- tracking e cleanup dei file camera temporanei app-scoped;
- logging solo debug e privacy-safe del fallimento upload:
  `errorCode/httpStatus/phase/retriable`, senza URL, token, ID o body.

I test JVM mirati post-fix sono `48/48 PASS` (`46` ViewModel + `2` capture file
tracker); il run Product Image esteso corrente è `89/89 PASS`. Build debug,
AndroidTest, lint e diff check restano verdi come dettagliato nell'evidence.

Sul simulatore autenticato, installato solo in-place con `adb install -r -t`,
database inode `33697`, prodotto QA barcode mascherato `9913…2329` e file sorgente
sono rimasti presenti; nessun uninstall, clear data, reset DB, remove immagine o
delete prodotto. L'editor ora espone e abilita `Retry image`,
`Discard failed attempt`, `Take new photo` e `Library`; una nuova selezione
entra realmente in `Preparing image...` e, dopo errore, torna utilizzabile.

L'acceptance upload/finalize/thumbnail non è però PASS: staging risponde
`401` a `POST /api/shop/product-images/intent` con JWT Supabase ancora valido.
Evidence canonica Android: `image_request_failed`, HTTP `401`, phase
`PREPROCESSING`, `retriable=false`, timestamp
`2026-07-19T05:10:08.020Z`. Il blocker è stato inoltrato alla lane server per
correlazione; non sono stati eseguiti retry ulteriori né cleanup distruttivi.

## Addendum P0 — auth persistita e binding account/shop fail-closed (2026-07-19)

La verifica della sessione Android persistita precedente ha restituito
`403 session_not_found`: il restore locale pubblicava `SignedIn` senza prima
confermare che il refresh token fosse ancora valido. Inoltre il logout Supabase
non specificava lo scope e il vecchio riallineamento account/shop poteva
cancellare automaticamente i dati business.

Correzioni applicate:

- restore autenticato con un solo refresh prima di pubblicare `SignedIn`;
- errore definitivo di revoca: clear locale best-effort e `SignedOut`;
- errore rete/5xx: identità locale preservata per l'offline-first;
- identità account vuota: fail-closed, mai `SignedIn` con owner vuoto;
- logout esplicitamente `SignOutScope.LOCAL`, senza revocare altri device;
- binding singleton nello stesso Room DB dei dati business, owner solo SHA-256,
  shop/local-shop/protocol/schema/epoch; migrazione additiva `20→21` senza wipe;
- auto-bind soltanto per database completamente vuoto; dati/pending/outbox
  unbound richiedono review esplicita; mismatch account/shop/schema bloccano;
- nessun reset automatico: il discard unbound conserva la conferma già
  esistente; account/shop mismatch usa invece una sola dialog Material 3 a due
  azioni, il cui tap distruttivo è la conferma esplicita. Entrambi riusano la
  transazione Room che preserva l'identità device; schema mismatch non abilita
  il replace;
- un solo gate TASK-126 condiviso da catalogo, history, sync manuale, realtime e
  immagini, ricontrollato dopo attese/single-flight e cambio shop/account;
- Options distingue checking, unbound, account/shop/schema mismatch e non mostra
  mai «sincronizzazione automatica attiva» fuori da `READY`/test unmanaged.

Evidence host reale:

- matrice P0 combinata: `146/146 PASS`, 0 failure/error/skip;
- regressivo Product Images: `89/89 PASS`, 0 failure/error/skip;
- regressivo JVM completo: `670` totali, `665` eseguiti PASS, `5` opt-in/live
  skipped; nessuno skip TASK-139/P0;
- `assembleDebug`, `assembleDebugAndroidTest`: PASS;
- `lintDebug`: PASS, `0` errori e `22` warning globali, nessun match sui file
  P0/TASK-139 toccati;
- migration `20→21`: prodotto v20 preservato e binding inizialmente assente;
- discard unbound e replace mismatch/rollback testati solo su Room in-memory
  sintetico: rollback completo, vecchio binding e device ID preservati; nessuna
  azione distruttiva sul database autenticato reale.

Il limite host-only di questo gate è stato successivamente superato dal run
autenticato in-place documentato sotto; resta valido come cronologia, non come
stato finale.

## Addendum P0 — runtime autenticato, cache offline e reconnect (2026-07-19)

APK finale installata solo in-place sull'emulatore autenticato, senza uninstall,
clear-data, reset, logout, discard/replace, remove immagine o delete prodotto.
Schema Room `21`, inode DB `33697`, conteggi business e prodotto QA mascherato
preservati; binding singleton owner-hash/shop/protocol/schema/epoch verificato.

Il run reale ha corretto e provato quattro gap runtime: stato rete immediato in
Options, soppressione del claim automatico offline, conservazione cache durante
il restore transitorio dello shop e retry automatico del contesto shop al
reconnect. La lettura cold-offline usa soltanto cache disco e un binding Room
owner-safe valido; un account diverso resta bloccato e non esegue rete.

Esito finale Android:

- warm online → thumbnail reale e cache `1` file: PASS;
- cold launch offline → thumbnail reale dalla cache, claim positivo `0`: PASS;
- reconnect → un retry, ritorno automatico a `READY`: PASS;
- outbox `0`, nessun upload duplicato avviato, nessun crash/ANR/errore Room:
  PASS;
- JVM forced `674` totali (`669 PASS`, `5` skip opt-in/live/fixture locale),
  `0` failure/error;
- assemble debug/test APK e lint: PASS.

Evidence runtime: archivio esterno non versionato del closeout.
A quel checkpoint TASK-139 restava `REVIEW` per blocker cross-platform/iOS poi
superseduti dall'integrazione finale.

## Addendum final review — quiescenza flight e ShopContext owner-safe (2026-07-19)

La review finale ha riprodotto due race ulteriori prima del fix:

- una risposta History/catalogo dello scope A poteva tornare dopo il replace e
  scrivere nel Room già associato allo scope B;
- `ShopContextRepository.refresh(A)` poteva pubblicare shop A dopo che l'auth
  corrente era già passata a B, creando il rischio di allineare B/shopA.

Il fix introduce lease generazionali condivise, una barriera transition
admission-closed con cancel/join prima di resolve/discard/replace, check
pre/post su tutte le chiamate remote e check immediati sugli apply Room.
Catalogo automatico, History, manual sync, realtime e smoke debug usano lo
stesso guard. ShopContext scarta response stale in base a generation e owner
auth corrente; l'Application rifiuta qualsiasi context con owner diverso.

Evidence deferred: response A non cooperativa rilasciata mentre il replace B
attende, binding finale B, `0` righe History A e `0` outbound successivi; owner
A rilasciato dopo switch B senza refresh B concorrente, `0` write preferenze A.
Gate finale: targeted final-review `124/124 PASS`; JVM `687` totali (`682 PASS`,
`5` skip opt-in/live); build app/test APK e lint verdi; ProductImageDevice
Emulator `3/3 PASS`; diff/secret/artifact scan verdi. La suite instrumentation
globale resta `BLOCKED_INPUT` soltanto sui
due harness live TASK-072C/D privi di prefisso esplicito; nessun input è stato
inventato. Evidence completa nell'archivio esterno non versionato del closeout.

A quel checkpoint TASK-139 restava `READY_FOR_REVIEW / REVIEW`; la conferma
utente è stata poi ricevuta nel final closeout.

## Addendum UX account/shop mismatch Android (2026-07-19)

Per autorizzazione esplicita dell'utente, la lane Android non è più
`review-only` esclusivamente per questa patch UI minima. Sync policy TASK-126,
coordinator, gate owner/shop, repository e transazioni Room non sono stati
modificati dall'addendum.

La card Options per `BLOCKED_ACCOUNT_MISMATCH` e
`BLOCKED_SHOP_MISMATCH` apre una sola `AlertDialog` Material 3 con titolo
`Scegli quali dati usare`, messaggio `Quali dati vuoi mantenere?` ed esattamente
due azioni: `Mantieni dati locali` (dismiss/secondary) e
`Sostituisci con dati cloud` (destructive). Back, dismiss esterno e azione
secondaria chiudono senza chiamare il coordinator; la CTA `Rivedi` permette la
riapertura manuale. La presentazione automatica usa una fingerprint SHA-256
privacy-safe persistita e avviene al massimo una volta per identità mismatch,
senza loop su recomposition, relaunch o reconnect.

L'azione distruttiva rimane disabilitata finché account autenticato, shop
remoto corrente, owner coerente e snapshot locale non sono verificati; non
esiste fallback a shop/store di default. Il tap richiama unicamente
`replaceMismatchedLocalBusinessDataAndBind()`, quindi la barriera owner-safe e
la transazione atomica esistenti, seguite dal bootstrap/pull/reconcile già
coordinato. Nessuna adozione o riscrittura owner è stata introdotta.

L'audit ha inoltre dimostrato un P1 nel boundary immagini: upload/remove già in
volo non erano registrati nella lease generazionale durante discovery o
transition di account/shop. Il fix minimo riusa esclusivamente il
`Task126BusinessDataScopeRuntimeGuard` esistente per racchiudere preprocessing,
intent, PUT/retry, finalize, remove e apply locale nello stesso flight, con
check pre/post. La cancellazione non diventa retry; `DatabaseViewModel`
ripristina lo snapshot UI remove solo se la generation è ancora corrente.
Coordinator, policy, repository, schema e Room restano invariati.

Evidence e gate reali: archivio esterno non versionato del closeout.
Targeted JVM `182/182 PASS`; JVM completo `697` totali (`692 PASS`, `5` skip
opt-in/live), `0` failure/error; device UI API 35 isolato `4/4 PASS`;
`assembleDebug`, `assembleDebugAndroidTest`, `lint` e `git diff --check` verdi.
Nessuna installazione, clear-data, reinstallazione o replace sul database
autenticato reale. La fase torna a `READY_FOR_REVIEW / REVIEW`, mai `DONE`.

## Addendum sync events e recovery atomico Android (2026-07-22)

La successiva esecuzione cross-platform ha autorizzato anche le correzioni
Android necessarie a sync events e recovery; non si tratta più della sola
eccezione UI mismatch. Policy e ownership restano centralizzate nei componenti
TASK-126 esistenti, senza nuove dipendenze o una seconda state machine.

Il candidato locale usa un Room staging non osservato, checkpoint A/B/C,
manifest canonico, verifica fisica/relazionale, lease account/shop/device e una
singola transazione di activation che pubblica insieme business data,
manifest, baseline, binding e watermark. Eventi incompleti o legacy richiedono
recovery durevole e non avanzano il watermark. Full/quick manuale ora svegliano
subito lo scheduler application-owned soltanto dopo aver persistito il latch e
rilasciato la flight manuale; lo scheduler resta single-flight. Errori VM non
vengono convertiti in retry automatici, inclusi decode baseline e cleanup
staging.

La re-review indipendente dell'ultimo delta locale conclude `P0=0`, `P1=0`,
`P2=0`. Gate correnti: JVM `784` totali (`779` pass, `5` skip intenzionali,
`0` failure/error), `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`
(`0` error, `24` warning globali) e `git diff --check` verdi. Questi risultati
restano intermedi: il contratto backend non è ancora congelato e sono ancora
aperti allineamento capability/event cursor/cutover/late marker, metadata
immagini verificabili e runtime autenticato cross-platform. Replace Android in
questa iterazione: `0`; nessuna installazione o mutazione del DB reale.

Evidence dettagliata nell'archivio esterno non versionato del closeout.
Quello stato intermedio era `REVIEW_WITH_SYNC_CONTRACT_BLOCKERS / REVIEW`; i
blocker sono stati superseduti dall'integrazione finale.

## Fix continuation — runtime prebound resource e matrice UI (2026-07-23)

La continuation cross-platform esplicita ha autorizzato un fix ristretto pur
con il task in `REVIEW`. È stata aggiunta instrumentation reale per il finding
deferred `sec-mobile-prebound-resource-003`:

- `64` flight A/G1 concorrenti non cooperative vengono quiesciute prima della
  transizione B/G2; nessun risultato stale raggiunge Room, watermark o file
  sink e non parte alcun retry loop;
- una V1 immagine tardiva dopo il binding V2 non entra in memoria, disco o UI;
  `100` consumer della stessa V3 condividono un solo download;
- prepare A/G1 e verify B/G2 sono stati eseguiti in processi instrumentation
  distinti con `am force-stop` intermedio. Il PID cambia, il vecchio store
  rimane leggibile e journal/watermark/cache G1 non vengono pubblicati nello
  scope B.

L'AVD API 35 era una copia effimera sotto `/tmp`, senza snapshot, wipe o
mutazione degli AVD esistenti; è stato spento e rimosso dopo l'estrazione delle
evidence. I gate owner finali sull'APK esatto sono `12/12` PASS, `0` skip:
scope 64-flight `1`, late-image/single-flight `1`, Product Image device `3`,
dialog account/shop `4`, UI production opt-in `2`, process-relaunch
prepare/verify `2`.

La prima prova UI opt-in ha evidenziato due problemi separati, entrambi
conservati nell'evidence: il selettore del test leggeva la Card semantica fusa
invece della preview da `80dp`, e la review dell'immagine mostrava clipping
reale di quantità/cronologia a font `1.6x`. Il selettore ora usa il nodo
semantico non fuso; la riga prodotto mette preview e dettagli in colonna e
lascia supplier/category/stock/history a larghezza piena. La matrice
`320/375/430dp × 1.0/1.3/1.6` è `9/9` PASS con screenshot reali, contenuti
terminali inclusi e zero overflow.

Gate finali dopo il fix UI:

- JVM completo: `811` totali, `806` pass, `0` failure, `0` error, `5` skip
  intenzionali e non owner TASK-139;
- `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`: PASS;
- `git diff --check`: PASS.

Evidence nell'archivio esterno non versionato del closeout.
La fase tornava allora a `REVIEW`; nessun deploy o write production.

## Handoff

L'handoff storico ha registrato baseline hardening, recovery editor, lease
owner/shop/device, quiescenza, recovery atomico e runtime pre-bound resource.
Il `401` del candidato era un blocker di staging storico ed è superseduto
dall'integrazione finale cross-repository; non resta un blocker Android
TASK-139. Device fisico e camera fisica restano `NOT_RUN` come evidence esterna
opzionale, non come requisito software di chiusura.

## Final review closeout — 2026-07-25

- Stato finale: `DONE`, con approvazione esplicita dell'utente.
- SHA codice verificata:
  `28f45bbfb34fd5771de8e964470d5de597588a11`.
- La SHA coincideva con `main`, `origin/main` e GitHub `main` al preflight ed è
  antenata del successivo commit documentale.
- CI GitHub `30174297767`: `SUCCESS` sull'esatta SHA.
- Report JUnit riusato dal run: `834` test totali, `829` eseguiti, `5` skipped,
  `0` failure e `0` error; assemble e lint verdi.
- Acceptance A-01…A-05: chiusa; P0/P1/P2 aperti `0/0/0`.
- Nessun replace autenticato, clear-data, deploy, write production, migrazione
  o nuovo scan Codex Security è stato eseguito nel closeout.
