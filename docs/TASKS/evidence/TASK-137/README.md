# TASK-137 Android Evidence

Mirror evidence Android. Il ledger cross-platform canonico e in:

`/Users/minxiang/Projects/merchandise-control-admin-web/docs/TASKS/EVIDENCE/TASK-137/README.md`

Regole: nessun secret/token/signed URL/byte/EXIF/path locale; solo risultati
eseguiti; fixture sintetiche; nessun claim production; Win7POS escluso.

## Risultati finali

- unit test mirati Room/sync/cache/processor/contratto: baseline `25/25 PASS`;
- nuovo test unitario origin binding: `PASS`;
- instrumentation emulator API 35: `3/3 PASS`;
- dopo gli ultimi hardening, rerun del solo caso invalidato
  `imageClientRunsUploadReadAndRemoveAgainstLoopback`: `1/1 PASS`, zero failure,
  `BUILD SUCCESSFUL`; AVD arrestato dopo il test;
- `assembleDebug` e `lintDebug`: `PASS`;
- high-res sintetico `8.000 x 6.000` (`48 MP`, input `1.258.536 B`):
  `41 ms`, main `165.769 B` (`1600 x 1200`), thumb `17.517 B`
  (`384 x 288`), delta PSS osservato `7.881 KiB`;
- nessun OOM; PSS prima/dopo `256.222 / 264.103 KiB`. Il delta PSS non e
  una misura del picco assoluto di memoria;
- APP1 rifiutato in processor, trasporto e cache;
- il primo run instrumentation era `2/3` per cleartext loopback bloccato;
  fix limitato ai manifest Debug/androidTest, Release resta HTTPS-only;
- Supabase live cross-client e device fisico: `NOT_RUN`.

Artefatti:

- `android-instrumentation.xml`;
- `android-summary.json`.

Il log instrumentation raw resta escluso dal consolidamento Git. Il test
loopback copre ora upload, signed read/download e remove; le URL Storage sono
accettate solo sull'origin Supabase configurato.

Commit locali: `d3b1d93` runtime/UI e `57befb2` test. I gate nel worktree
pulito da `origin/main` e la pubblicazione restano da eseguire; nessun claim
visuale corrente è dichiarato.
