# Excel Recognition Audit

## Scopo

`ExcelRecognitionAuditTest` verifica il riconoscimento intestazioni Excel usando la stessa logica Android production:

- `readAndAnalyzeExcelDetailed`
- `analyzeRowsDetailed`
- alias/header source/trace prodotti da `ExcelUtils`

Il tool serve a diagnosticare velocemente file fornitori reali e a bloccare regressioni future su golden fixture.

Android resta la fonte di verita'. iOS e Admin Web potranno aggiungere adapter futuri usando gli stessi fixture ed expected JSON.

## Comandi

Loop orchestrato Oracle v2/v3 Android-first su golden + sample Drive:

```bash
./gradlew :app:excelRecognitionOracleLoop \
  -PexcelAudit.batchDirs=/tmp/excel-supplier-audit/combined-corpus-by-name
```

Oracle v2 indipendente su golden raw workbook:

```bash
./gradlew :app:excelRecognitionOracleV2 \
  -PexcelOracle.pinmarkFile=/Users/minxiang/Desktop/20260620-Pinmark.xlsx \
  -PexcelOracle.modalinaFile=/Users/minxiang/Desktop/Vs20260529-ModaLina.xlsx
```

Audit sui golden fixture inclusi:

```bash
./gradlew :app:testDebugUnitTest --tests "*ExcelRecognitionAudit*"
```

Audit rapido su uno o piu' file esterni:

```bash
./gradlew :app:excelRecognitionAudit -PexcelAudit.files="/path/file1.xlsx,/path/file2.xlsx"
```

Audit batch read-only su corpus fornitori locale:

```bash
./gradlew :app:excelRecognitionDriveBatchAudit -PexcelAudit.batchDirs=/tmp/excel-supplier-audit/combined-corpus-by-name
```

Esempio con i file Modalina/Pinmark:

```bash
./gradlew :app:excelRecognitionAudit -PexcelAudit.files="/Users/minxiang/Desktop/Vs20260529-ModaLina.xlsx,/Users/minxiang/Desktop/20260620-Pinmark.xlsx"
```

Il report viene scritto in:

- `app/build/reports/excelRecognitionAudit/excel-recognition-audit.md`
- `app/build/reports/excelRecognitionAudit/excel-recognition-audit.json`

Il batch Drive scrive:

- `app/build/reports/excelRecognitionAudit/drive-batch-audit.md`
- `app/build/reports/excelRecognitionAudit/drive-batch-audit.json`
- `app/build/reports/excelRecognitionAudit/drive-batch-audit.csv`

> **Importante:** il batch Drive v1 e' `INVALID_V1_SELF_REFERENTIAL_AUDIT`.
> Non usare i suoi verdict o `/Users/minxiang/Desktop/File testing` come base
> manuale. Prima usa l'oracle v2, che scrive:
>
> - `app/build/reports/excelRecognitionAudit/oracle-v2/raw-workbook-pinmark.md`
> - `app/build/reports/excelRecognitionAudit/oracle-v2/oracle-pinmark.expected.json`
> - `app/build/reports/excelRecognitionAudit/oracle-v2/platform-android-pinmark.json`
> - `app/build/reports/excelRecognitionAudit/oracle-v2/comparison-android-pinmark.md`
> - `app/build/reports/excelRecognitionAudit/oracle-v2/harness-v2-validation.md`
> - `app/build/reports/excelRecognitionAudit/INVALID_V1_SELF_REFERENTIAL_AUDIT.md`
>
> Il loop v2/v3 scrive invece:
>
> - `app/build/reports/excelRecognitionAudit/oracle-loop/final-harness-validation.md`
> - `app/build/reports/excelRecognitionAudit/oracle-loop/final-harness-validation.json`
> - `app/build/reports/excelRecognitionAudit/oracle-loop/manual-review-queue.csv`
> - `/Users/minxiang/Desktop/File testing v2/`

Dettagli e limiti del batch: `docs/EXCEL_RECOGNITION_BATCH_AUDIT.md`.

Evidenze storiche precedenti all'addendum harness-only:

- before parser change: `app/build/reports/excelRecognitionAudit/excel-recognition-audit-before-fix.md`
- after parser change: `app/build/reports/excelRecognitionAudit/excel-recognition-audit-after-fix.md`

Nota di scope: la fase corrente e' solo harness/comparator/report. Non aggiunge
alias production, non corregge parser Android/iOS/Admin Web e non va descritta
come fix del riconoscimento applicativo.

## Expected JSON

Ogni expected vive accanto al fixture:

- `app/src/test/resources/excel/Vs20260529-ModaLina.expected.json`
- `app/src/test/resources/excel/20260620-Pinmark.expected.json`

Per aggiungere un fornitore:

1. Copiare il file `.xlsx` in `app/src/test/resources/excel/`.
2. Creare `NomeFile.expected.json`.
3. Definire `expectedHeaderRow`, `expectedFirstDataRow`, `requiredFields`, `fields` e `ignoredColumns`.
4. Eseguire `./gradlew :app:testDebugUnitTest --tests "*ExcelRecognitionAudit*"`.

`source` deve restare `alias` quando una colonna attesa esiste nel file. Se una colonna reale viene generata artificialmente, il golden fallisce con `REQUIRED_GENERATED` o `HEADER_SOURCE_MISMATCH`.

## Report

Il report mostra per ogni file:

- nome file e sheet
- riga header e prima riga dati fisiche, 1-based
- header originali
- header canonici
- `headerSource` per colonna
- mapping dei campi principali
- sample values dalle prime righe dati
- colonne obbligatorie mancanti
- colonne sospette
- verdict `PASS`, `WARN` o `FAIL`
- motivi concreti del fail

Esempio sintetico:

```text
20260620-Pinmark.xlsx — PASS
Header row: 9
First data row: 10
itemNumber       A  REF\n货号              alias
barcode          B  EAN\n条码              alias
productName      C  DESCRIPCION\n品名      alias
secondProductName E SECUNDARIO\n第二名称   alias
quantity         G  CANT\n数量             alias
purchasePrice    H  PRE\n批发价            alias
discount         I  D.%\n折扣             alias
discountedPrice  J  P.desc\n折后价         alias
totalPrice       K  SUM\n折后合计          alias
```

## Golden Matrix Storica

Questa matrice descrive evidenze storiche gia' presenti prima dell'addendum
`SOLO HARNESS`. Non e' un invito a modificare parser production durante il loop
Oracle v2/v3.

| File | Before | After | Header row after | First data row after | Differenza |
|---|---|---|---:|---:|---|
| `Vs20260529-ModaLina.xlsx` | PASS | PASS | 10 | 11 | Mapping stabile; l'expected riga e' stato corretto da 9/10 a 10/11 perche' il foglio XML reale ha l'header su `ROW 10`. |
| `20260620-Pinmark.xlsx` | FAIL | PASS | 9 | 10 | Header compositi multilinea riconosciuti come alias; D vuota e F `IMAGEN/图片` restano unknown/ignored; `discountedPrice` e `totalPrice` non sono confusi. |

La correzione dell'expected Modalina non indebolisce il test: non cambia campi, colonne, alias o sample; corregge solo la riga fisica dimostrata dal workbook reale.
