# Supabase migrations

This directory contains versioned SQL artifacts for the Supabase backend used by
the Android client. A committed SQL file is evidence of repository intent, not
proof that the SQL was deployed to any live Supabase project.

## Current files and order

| Order | File | Type | Status |
|-------|------|------|--------|
| 1 | `20260424190000_harden_rls_and_sync_indexes.sql` | Incremental hardening artifact | Versioned in repo; live application not proven by this directory. |

The current file is not a full initial schema. It assumes existing backend
objects and adds/updates owner-scoped RLS policies, indexes, view/function
hardening and legacy policy removals.

## External local Supabase source observed in TASK-062 review

Final review also inspected the local workspace
`/Users/minxiang/Desktop/MerchandiseControlSupabase` in read-only mode. That
workspace contains a broader local migration chain, including base catalog DDL,
price history DDL, `shared_sheet_sessions` DDL/v2 changes and `sync_events` +
RPC `record_sync_event`.

Those files are useful provenance, but they are not part of this Android repo
until intentionally copied or re-authored here. Reading them is not a live
deployment and does not prove current remote state. If a future task imports any
SQL from that workspace, record:

- source path under `/Users/minxiang/Desktop/MerchandiseControlSupabase`;
- whether the file is copied verbatim or adapted;
- target environment;
- artifact status (`artifact only`, `reviewed`, `approved for apply`, `applied`);
- approval and verification evidence.

Do not run `supabase db push` or apply those local migrations to production from
this repository without a separate approval gate.

## Commit SQL is not deploy

- Commit in git means: the SQL artifact is reviewable and traceable.
- Deploy live means: an approved operator applied it to a named Supabase
  environment and recorded evidence.
- TASK-062 did not apply any SQL to Supabase.
- Do not run `supabase db push`, SQL editor commands, or production migrations
  from this documentation task without explicit approval.

## Prerequisites before applying any SQL

- Named target environment (`dev`, `staging`, `prod`, or another explicit name).
- Backup or rollback plan appropriate for that environment.
- Review of dependencies: tables, functions, views, extensions and grants used
  by the SQL must already exist or be created by earlier approved migrations.
- Explicit approval recorded in task, ticket, PR, or release notes.
- Captured execution evidence after apply: timestamp, operator, environment,
  command/tool used and result.

## Artifact types

| Type | Definition | Policy |
|------|------------|--------|
| Initial DDL | Creates base tables, views, functions, extensions, keys and constraints. | Must come from an authoritative source such as `pg_dump --schema-only` or a reviewed migration chain. Do not infer it only from Kotlin DTOs. |
| Incremental migration | Alters existing objects, adds policies/indexes/functions or changes grants. | Allowed as a repo artifact after review. Applying it live is a separate approved action. |
| Schema dump | Snapshot of an environment schema. | Must include source environment, date/time, command/export method and whether sensitive data was excluded. |
| Manual runbook | Human steps such as Dashboard Realtime toggles or verification queries. | Must state what is manual, what evidence to capture and what is not automated. |

## No invented SQL policy

Do not add definitive SQL just because Android code references a table or column.
Kotlin DTOs are useful evidence for the client contract, but they are not a full
database schema.

Examples:

- `sync_events` and RPC `record_sync_event` are used by Android code, but their
  DDL/function SQL is not present in this Android repo at TASK-062 time. A local
  source exists in
  `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260424021936_task045_sync_events.sql`.
- A future `sync_events` SQL file must come from a verified dump/export or a
  reviewed/imported local source, and must be labelled with its source and
  status.
- Draft SQL without an authoritative source must be labelled `ASSUMPTION` or
  `proposal`, not `applied`, `live`, or `definitive`.

## Required metadata for future SQL files

Every future `.sql` file should include a header comment or adjacent README entry
with:

| Field | Required content |
|-------|------------------|
| Source | Example: `pg_dump --schema-only from staging 2026-04-26`, Dashboard export, reviewed design ticket, or prior validated migration. |
| Target environment | `generic`, `dev`, `staging`, `prod`, or another explicit name. |
| Artifact status | `artifact only`, `reviewed`, `approved for apply`, or `applied`. |
| Live application | `no` by default; `yes` only with environment, timestamp and evidence. |
| Approval | Approver, date and task/ticket/PR reference. |
| Rollback | How to revert or mitigate if apply fails. |
| Verification | Queries/checks required after apply. |

Suggested header:

```sql
-- Source:
-- Target environment:
-- Artifact status: artifact only
-- Live application: no
-- Approval:
-- Rollback:
-- Verification:
```

## Existing artifact notes

### `20260424190000_harden_rls_and_sync_indexes.sql`

This file:

- creates owner-scoped RLS policies for `inventory_suppliers`,
  `inventory_categories`, `inventory_products`, `inventory_product_prices` and
  `shared_sheet_sessions`;
- drops `shared_sheet_sessions_select_public`;
- drops broad authenticated policies on legacy remote `history_entries`;
- sets `product_price_summary` to `security_invoker`;
- hardens function `search_path` for referenced functions;
- adds owner/id indexes for current Android sync paths.

This file does not:

- create the base tables;
- create `sync_events`;
- create RPC `record_sync_event`;
- enable Realtime publication;
- prove it was applied to any live environment.

Local Supabase workspace files may cover those missing items, but this directory
still does not until a future task imports reviewed artifacts.
