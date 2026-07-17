# TASK-137 - Product Catalog Images cross-platform (Android)

## Informazioni generali

- ID: `TASK-137`
- Stato: `REVIEW_WITH_BLOCKERS`
- Fase: `REVIEW`
- Responsabile: `CLAUDE/CHATGPT_REVIEWER`
- Data apertura: `2026-07-16`
- Contratto canonico:
  `/Users/minxiang/Projects/merchandise-control-admin-web/docs/TASKS/TASK-137-product-catalog-images-cross-platform.md`
- Evidence Android: `docs/TASKS/evidence/TASK-137/README.md`
- Win7POS escluso.

## Obiettivo Android

Integrare una sola immagine primaria versionata nel catalogo Android senza
modificare il contratto backend congelato: preprocessing JPEG nativo,
intent/upload diretto/finalize, signed read URL effimero, cache offline isolata
per account/shop e propagazione incrementale del solo UUID versione.

## Vincoli obbligatori

- nessun blob/Base64/path/URL/token in Room, outbox o sync event;
- nessuna service role e nessun secret nel client;
- nessuna nuova dipendenza immagini salvo blocker dimostrato e aggiornamento
  preventivo del contratto canonico;
- input JPEG/PNG, output main max 1600 px e thumb max 384 px, senza upscale,
  metadata rimossi, budget e quality definiti nel task canonico;
- product edit indipendente: un errore upload non annulla l'edit prodotto;
- upload richiede rete in v1 e non entra nell'outbox normale;
- cache key `(account, shop, product UUID, version UUID, variant)` e nessuna
  lettura cache cross-account/cross-shop;
- owner/manager write; viewer read-only; cashier/revoked/cross-shop non hanno
  write o accesso non autorizzato;
- nessun full pull per finalize/remove;
- nessun force push, release/deploy production o modifica Win7POS; commit e
  push restano vincolati ai gate del consolidamento finale.

## Superficie Android congelata

- `docs/MASTER-PLAN.md`, questo file ed evidence TASK-137;
- `Product.kt`, `ProductRemoteRef.kt`, `AppDatabase.kt` e migration Room
  strettamente necessaria ai campi opzionali versione/timestamp;
- DTO/fetch/apply catalogo in `SupabaseCatalogRemoteDataSource.kt`,
  `InventoryRepository.kt` e helper esistenti;
- nuovo package ristretto `productimage` per preprocess, API, upload e cache;
- `DatabaseScreenComponents.kt` e wiring/editor prodotto strettamente
  necessario;
- risorse localizzate en/it/es/zh;
- test JVM/instrumentation mirati TASK-137.

File aggiuntivi devono essere registrati prima nel task canonico. Il worktree
Android era pulito alla baseline TASK-137.

## Criteri di accettazione Android

- [x] DTO/apply persiste solo `primary_image_version_id` e timestamp opzionale.
- [x] Duplicate/no-op/stale/checkpoint/tombstone/offline-reconnect/account switch
  restano idempotenti e senza full pull.
- [x] Preprocess produce JPEG main/thumb nei budget, senza upscale/metadata.
- [x] Intent, due PUT diretti, finalize e remove rispettano il contratto API.
- [x] Thumbnail/lista e main/dettaglio hanno placeholder, loading/error e cache
  offline senza leakage di scope.
- [x] Edit prodotto resta valido quando upload fallisce.
- [x] Test mirati, `assembleDebug`, test contratto equivalente, lint se
  applicabile e `git diff --check` passano realmente.
- [x] Runtime emulator/device non-production e cleanup sono documentati senza
  dichiarare parity fisica non dimostrata.

## Execution

- Room v20, targeted apply, picker/camera, preprocess, API, cache e UI
  implementati senza dipendenze nuove;
- JVM mirati baseline `25/25`, nuovo test origin binding `PASS`,
  instrumentation baseline `3/3`, `assembleDebug` e `lintDebug` `PASS`;
- fixture sintetica Android `8.000 x 6.000` (`48 MP`) processata in `41 ms`
  senza OOM; main `165.769 B`, thumb `17.517 B`, delta PSS osservato
  `7.881 KiB`;
- metriche e artefatti durevoli in `docs/TASKS/evidence/TASK-137/`;
- primo run instrumentation `2/3` risolto con policy cleartext solo Debug/test;
- blocker review: nessun workflow Android contro Supabase locale/staging reale
  e nessun device fisico; non viene dichiarata parity live.
- consolidamento Mac: URL Storage firmate vincolate all'origin Supabase
  configurato; test unitario origin binding e instrumentation upload/read/remove
  aggiunti senza dipendenze nuove.
- commit locali creati: `d3b1d93` (runtime/UI) e `57befb2` (test); rerun del
  solo instrumentation test invalidato `1/1 PASS`. Validazione pulita e
  pubblicazione ancora pendenti.

## Handoff -> Review

- prossima fase: review del contratto e, separatamente, harness mobile auth per
  una matrice live cross-client non-production;
- nessun push o deploy production;
- il solo harness TASK-088B con hash K125 resta preservato nel checkout
  secondario `/Users/minxiang/AndroidStudioProjects/MerchandiseControlSplitView`:
  copiato isolatamente nel canonico non compila senza modifiche cumulative
  fuori scope, quindi non e stato integrato automaticamente e il file canonico
  e stato ripristinato dal relativo `HEAD`.
