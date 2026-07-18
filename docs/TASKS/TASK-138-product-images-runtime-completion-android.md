# TASK-138 - Product Images Runtime Completion, UX e Live Parity (Android)

## Tracking

- Stato: `DONE`
- Fase: `DONE_RECONCILED`
- Data apertura: `2026-07-18`
- Responsabile: `USER_CONFIRMED_RELEASE`
- Contratto canonico: Admin Web
  `docs/TASKS/TASK-138-product-images-runtime-completion-ux-live-parity.md`
- Evidence locale: `docs/TASKS/evidence/TASK-138/README.md`
- Base locale: `69c36c2c4e3e331da4ca6ce76524cf766d0a36f1`
- Dipendenza: backend TASK-137/TASK-138 verificato localmente dal coordinatore
  prima della patch Kotlin (`reset`, pgTAP `149/149`, foundation `20/20`,
  route/lifecycle E2E `PASS`).
- TASK-137 resta `REVIEW_WITH_BLOCKERS`; non viene riaperto o chiuso.

Il prompt utente del `2026-07-18` autorizza esplicitamente questo mirror e la
lane Android in execution. L'autorizzazione specifica prevale sulla regola
generica che riserva la creazione dei task al planner.

## Obiettivo Android

Chiudere soltanto i gap runtime Android rilevati prima della modifica:

- provare placeholder senza `versionID` con zero chiamate rete e zero entry;
- mantenere thumb 1:1 crop in lista e usare contain/fit per main editor;
- batch `read-urls` per shop con dedup e chunk massimi di 100;
- coalescing per chiave e limite esplicito ai download concorrenti;
- cancellare/ignorare job offscreen e non accumulare `ByteArray` nella lista;
- cache account/shop scoped, offline-first e purge su logout/switch;
- un solo retry per URL scaduta; decode/MIME invalido mai in cache;
- replace/remove selettivi e completion stale incapace di sovrascrivere;
- preprocess fuori main thread con budget TASK-137 invariati;
- progress preprocess/main/thumb/finalize e cancellazione end-to-end;
- test lista 200 prodotti, suite fixture, screenshot e misure riproducibili.

## File candidati

- `app/src/main/java/**/productimage/ProductImageApiClient.kt`
- `app/src/main/java/**/productimage/ProductImageService.kt`
- `app/src/main/java/**/productimage/ProductImageCache.kt`
- `app/src/main/java/**/productimage/ProductImageProcessor.kt`
- `app/src/main/java/**/ui/screens/DatabaseViewModel.kt`
- `app/src/main/java/**/ui/screens/DatabaseScreenComponents.kt`
- `app/src/main/java/**/ui/components/EditProductDialog.kt`
- test mirati e sole stringhe localizzate necessarie.

L'elenco e indicativo: nessun file deve essere modificato se il requisito e gia
soddisfatto e provabile.

## Vincoli

- un solo writer nel repository Android;
- nessuna nuova dipendenza senza motivazione esplicita;
- nessun blob, Base64, URL firmata, token o path Storage in Room/outbox/log;
- nessuna service role client-side;
- nessuna modifica Supabase live dalla lane Android;
- nessun Win7POS, production, commit, push, merge, reset o clean;
- emulator e fixture condivisa usati in modo seriale, non concorrente.

## Gate e check

La lane resta ferma sui documenti finche il gate backend locale non e `PASS`.
Dopo il gate:

1. test service/API per batch ≤100, dedup, retry unico e coalescing;
2. test cache per decode invalido, isolation, logout/switch e purge selettivo;
3. test ViewModel/UI per cancel, stale result e 200 prodotti visible-only;
4. processor suite, incluso 48 MP e input invalidi;
5. baseline JVM pertinente, `assembleDebug`, `lintDebug`;
6. instrumentation/emulator serializzato e screenshot;
7. parity sul medesimo shop non-production soltanto se sessione disponibile.

Ogni check deve riportare risultato reale o `NOT_RUN`/`BLOCKED_ENV`.

## Criteri di accettazione Android

- Product A non genera rete/cache;
- Product B usa thumb lista e main fit editor;
- 200 prodotti non producono fan-out o crescita non bounded;
- batch/dedup/coalescing/limite download sono verificati;
- offline cache e account/shop isolation sono verificati;
- invalid response non resta in cache e URL scaduta ha un solo retry;
- upload/replace/noop/remove, progress e cancel sono coerenti col backend;
- nessuna regressione catalogo/sync e nessun dato sensibile persistito;
- evidence sufficiente per handoff `REVIEW`, mai `DONE`.

## Handoff executor Android

- Esito: `READY_FOR_REVIEW`, non `DONE`.
- Runtime richiesto implementato senza nuove dipendenze e senza modifica
  Supabase live.
- Evidence JVM/build/lint e matrice criteri:
  `docs/TASKS/evidence/TASK-138/README.md`.
- Evidence optimization finale:
  `docs/TASKS/evidence/TASK-138/09-optimization-review.md` (il segmento
  `EVIDENCE` richiesto collassa qui sul path Git canonico lowercase perche il
  workspace e case-insensitive).
- Instrumentation mirata su emulatore API 35: `3/3 PASS`, incluso 48 MP,
  picker/camera contract, cache scoped e lifecycle HTTP loopback.
- JVM optimization mirata finale: `74/74 PASS`; lease signed URL LRU capped a
  `256`; `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` e
  compilazione androidTest `PASS`.
- Gate locale opt-in read-only `1/1 PASS`: no-image, rete thumb -> main e hit
  disk; loopback con `adb reverse`, config privata `0600` eliminata e contratto
  `sameOrigin` production invariato.
- Gate visuale/performance `2/2 PASS` dopo un fix debug-only di testability:
  sei PNG, `200` righe, `20` editor, massimo `12` immagini composte e cache
  entro `8/64 MiB`.
- Gate mutativo Android `replace` `1/1 PASS` dopo il fix reale del campo MIME
  required omesso dal serializer; backend `3` versioni/`6` oggetti/`1` current
  e cross-reader Admin `1/1 PASS`.
- Cross-reader iOS e successivo `remove`: `PENDING_CROSS_PLATFORM`; device
  fisico e parity staging/dev restano `NOT_RUN`, mai dedotti.

## Chiusura finale 2026-07-18

La conferma esplicita dell'utente e stata ricevuta dopo review/fix e gate finali.
Android ha letto la versione iOS, l'ha rimossa (`1/1 PASS`), Admin ha confermato
lo stato assente (`1/1 PASS`), poi Android ha ricreato una versione che iOS ha
letto e rimosso. La matrice cross-platform e quindi completa.

Gate Android finali: JVM `74/74 PASS`, `assembleDebug PASS`,
`assembleDebugAndroidTest PASS`, `lintDebug PASS` con `0` errori e `23` warning
storici/fuori scope; visual/performance emulator `2/2 PASS`, parity read/replace/
remove `PASS`. Cleanup coordinato DB/Storage/Auth `0`; nessun emulator attivo.

Verdict: `RELEASE_READY_WITH_MEASURED_GATES`. Stato: `DONE`, fase
`DONE_RECONCILED`. Device fisico e staging/dev autenticato restano
`BLOCKED_EXTERNAL_PRECONDITION`, non PASS inventati e non blocker del perimetro
locale accettato dall'utente.
