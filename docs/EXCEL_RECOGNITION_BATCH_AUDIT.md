# Excel Recognition Batch Audit

> **INVALID_V1_SELF_REFERENTIAL_AUDIT / SUPERSEDED_BY_ORACLE_V2**
>
> Il batch v1 prodotto da `ExcelRecognitionDriveBatchAuditTest` non e' una base
> affidabile per testing manuale: confronta troppo vicino all'output del parser
> Android e puo' quindi confermare errori di header row, row boundary o mapping.
> Non usare `drive-batch-audit.*` ne' `/Users/minxiang/Desktop/File testing`
> come verita'. Usa prima l'oracle v2:
>
> ```bash
> ./gradlew :app:excelRecognitionOracleV2 \
>   -PexcelOracle.pinmarkFile=/Users/minxiang/Desktop/20260620-Pinmark.xlsx \
>   -PexcelOracle.modalinaFile=/Users/minxiang/Desktop/Vs20260529-ModaLina.xlsx
> ```
>
> Per la review manuale usare il loop v2/v3:
>
> ```bash
> ./gradlew :app:excelRecognitionOracleLoop \
>   -PexcelAudit.batchDirs=/tmp/excel-supplier-audit/combined-corpus-by-name
> ```

## Scopo

Questo audit v1 misura storicamente il riconoscimento intestazioni Excel su un
corpus reale di file fornitori, senza modificare parser, alias o logica di
import production. Il suo output resta invalidato/superseded perche' non usa un
oracle indipendente completo.

Il batch Drive v1 e' diagnostico storico: i file senza expected JSON usano verdict euristici
(`PASS_LIKELY`, `WARN_REVIEW`, `FAIL_SUSPECT`) e non devono essere trattati come fail
confermati finche' non vengono verificati manualmente.

Il comando v1 continua a scrivere `v1InvalidationStatus=INVALID_V1_SELF_REFERENTIAL_AUDIT`
in Markdown/JSON/CSV anche quando viene rigenerato. La coda manuale utilizzabile
deve provenire da `oracle-loop/manual-review-queue.csv` e da
`/Users/minxiang/Desktop/File testing v2/`.

## Comando Android

```bash
./gradlew :app:excelRecognitionDriveBatchAudit \
  -PexcelAudit.batchDirs=/tmp/excel-supplier-audit/combined-corpus-by-name
```

Il task usa `readAndAnalyzeExcelDetailed` e scrive:

- `app/build/reports/excelRecognitionAudit/drive-batch-audit.md`
- `app/build/reports/excelRecognitionAudit/drive-batch-audit.json`
- `app/build/reports/excelRecognitionAudit/drive-batch-audit.csv`

## Corpus 2026-06-21

- Drive folder: `https://drive.google.com/drive/folders/1aRsUPNPygXEa5BhHe3d_YMuqvwrIqLE8`
- File materializzati localmente: `77`
- Fonte locale combinata: `gdown` public folder page (`50` file) + `~/Desktop/Cartella Excel` + fixture/golden Desktop Modalina/Pinmark.
- Limite noto: il connector Google Drive mostra piu' file del limite pubblico `gdown` da `50`; i file non materializzati localmente non sono marcati come PASS/FAIL.

## Stato piattaforme

- Android: batch completo sul corpus locale materializzato.
- iOS: `PARTIAL/NOT_RUN`; parser trovato in `ExcelSessionViewModel.swift`, ma manca un adapter batch XCTest/report.
- Admin Web: `PARTIAL/NOT_RUN`; header detector trovato e probe read-only su 50 file eseguito, ma manca un adapter batch completo per preview/mapping.

## Regola di stop

Dopo questo batch audit non applicare fix algoritmo automatici:

- nessun alias nuovo per file Drive;
- nessuna patch a `ExcelUtils.kt` guidata dai risultati batch;
- nessun expected indebolito;
- eventuali fix vanno aperti come task separati dopo review manuale utente.
