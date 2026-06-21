# Excel Recognition Harness V2/V3 - WIP Handoff

Data handoff: 2026-06-21

Branch WIP consigliata: `wip/excel-recognition-harness-v2-oracle-loop`

## Stato Attuale

- Harness V2/V3 creato per audit Excel Recognition.
- Oracle raw workbook inspector presente.
- Oracle candidate generator presente.
- Comparator Android read-only presente.
- Manual review queue V2 generata.
- Batch audit v1 marcato `INVALID_V1_SELF_REFERENTIAL_AUDIT` / superseded.
- Nessun nuovo fix production parser dopo hard-stop harness-only.
- Il lavoro corrente riguarda solo harness, oracle inspector, oracle candidate, comparator, report, manual review queue, documentazione e task Gradle/CLI.

## Comandi Utili

```bash
./gradlew :app:excelRecognitionOracleLoop -PexcelAudit.batchDirs=/tmp/excel-supplier-audit/combined-corpus-by-name
./gradlew :app:excelRecognitionOracleV2 -PexcelOracle.pinmarkFile=/Users/minxiang/Desktop/20260620-Pinmark.xlsx -PexcelOracle.modalinaFile=/Users/minxiang/Desktop/Vs20260529-ModaLina.xlsx
./gradlew :app:testDebugUnitTest --tests "*ExcelRecognitionAudit*"
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
git diff --check
```

## Report Generati

- `app/build/reports/excelRecognitionAudit/oracle-loop/final-harness-validation.md`
- `app/build/reports/excelRecognitionAudit/oracle-loop/final-harness-validation.json`
- `app/build/reports/excelRecognitionAudit/oracle-loop/manual-review-queue.csv`
- `app/build/reports/excelRecognitionAudit/oracle-v2/`
- `/Users/minxiang/Desktop/File testing v2/`

Nota: i report in `app/build/` sono output generati e non sono stati pensati come sorgenti versionati.

## Golden Control

- Modalina: `PASS_CONFIRMED`.
- Pinmark: `PASS_CONFIRMED`.
- Pinmark deve restare controllo per:
  - dirty row / metadata row;
  - header row;
  - first data row;
  - `productName` mapping;
  - `discount` / `discountedPrice` / `totalPrice`.

## Cosa NON Fare Alla Ripresa

- Non patchare subito Android `ExcelUtils`.
- Non patchare iOS Excel parser.
- Non patchare Admin Web import parser.
- Non aggiungere alias production.
- Non correggere i file fornitori prima della review manuale.
- Non usare batch v1 come verita'.
- Non usare `/Users/minxiang/Desktop/File testing/` come verita' del nuovo harness.
- Non committare report generati in `app/build/`.
- Non committare cartelle Desktop, screenshot, file temporanei o file Excel fornitori del Drive.

## Prossimi Step Consigliati

- Validare manualmente alcuni file in `/Users/minxiang/Desktop/File testing v2/`.
- Migliorare oracle candidate sui file `NEEDS_HUMAN_REVIEW`.
- Aggiungere adapter read-only iOS.
- Aggiungere adapter read-only Admin Web.
- Solo dopo review manuale aprire task separati di fix parser per piattaforma.

## Rischi Residui

- iOS/Admin adapter non ancora completi.
- Controlli `discount` / `discountedPrice` / `totalPrice` ancora parziali.
- Corpus Drive completo dipende dai file materializzati localmente in `/tmp/excel-supplier-audit/combined-corpus-by-name`.
- File Desktop e test resources possono avere hash diversi: documentare sempre quali file fisici sono stati usati.
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` risulta dirty da lavoro precedente/pre-existing e non appartiene al checkpoint harness-only.
- `.idea/deploymentTargetSelector.xml` risulta cancellato nel worktree ed e' fuori scope harness.
- `/Users/minxiang/Desktop/File testing/` e `/Users/minxiang/Desktop/File testing v2/` sono fuori repo e non vanno versionati.

## Ripresa Rapida

1. Verificare branch e worktree:

```bash
git status --short --branch
git diff --name-only
```

2. Rieseguire il loop harness:

```bash
./gradlew :app:excelRecognitionOracleLoop -PexcelAudit.batchDirs=/tmp/excel-supplier-audit/combined-corpus-by-name
```

3. Aprire la queue manuale:

```bash
open "/Users/minxiang/Desktop/File testing v2"
```

4. Non promuovere `FAIL_SUSPECT` a `FAIL_CONFIRMED` senza oracle umano confermato.
