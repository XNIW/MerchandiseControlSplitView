-- TASK-056 live Supabase remediation.
--
-- Goals:
-- - remove anonymous access to shared sheet sessions;
-- - keep Android/iOS/POS clients owner-scoped through auth.uid();
-- - avoid per-row auth.uid() RLS initplan overhead;
-- - add owner-scoped indexes for full/bootstrap and targeted sync paths;
-- - harden legacy/public objects reported by Supabase advisors.

begin;

-- ---------------------------------------------------------------------------
-- Inventory catalog RLS: owner-scoped and initplan-friendly.
-- ---------------------------------------------------------------------------

drop policy if exists inventory_suppliers_select_owner on public.inventory_suppliers;
create policy inventory_suppliers_select_owner
on public.inventory_suppliers
for select
to authenticated
using ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_suppliers_insert_owner on public.inventory_suppliers;
create policy inventory_suppliers_insert_owner
on public.inventory_suppliers
for insert
to authenticated
with check ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_suppliers_update_owner on public.inventory_suppliers;
create policy inventory_suppliers_update_owner
on public.inventory_suppliers
for update
to authenticated
using ((select auth.uid()) = owner_user_id)
with check ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_categories_select_owner on public.inventory_categories;
create policy inventory_categories_select_owner
on public.inventory_categories
for select
to authenticated
using ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_categories_insert_owner on public.inventory_categories;
create policy inventory_categories_insert_owner
on public.inventory_categories
for insert
to authenticated
with check ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_categories_update_owner on public.inventory_categories;
create policy inventory_categories_update_owner
on public.inventory_categories
for update
to authenticated
using ((select auth.uid()) = owner_user_id)
with check ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_products_select_owner on public.inventory_products;
create policy inventory_products_select_owner
on public.inventory_products
for select
to authenticated
using ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_products_insert_owner on public.inventory_products;
create policy inventory_products_insert_owner
on public.inventory_products
for insert
to authenticated
with check ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_products_update_owner on public.inventory_products;
create policy inventory_products_update_owner
on public.inventory_products
for update
to authenticated
using ((select auth.uid()) = owner_user_id)
with check ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_product_prices_select_owner on public.inventory_product_prices;
create policy inventory_product_prices_select_owner
on public.inventory_product_prices
for select
to authenticated
using ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_product_prices_insert_owner on public.inventory_product_prices;
create policy inventory_product_prices_insert_owner
on public.inventory_product_prices
for insert
to authenticated
with check ((select auth.uid()) = owner_user_id);

drop policy if exists inventory_product_prices_update_owner on public.inventory_product_prices;
create policy inventory_product_prices_update_owner
on public.inventory_product_prices
for update
to authenticated
using ((select auth.uid()) = owner_user_id)
with check ((select auth.uid()) = owner_user_id);

-- ---------------------------------------------------------------------------
-- Shared sheet sessions: private by default.
-- ---------------------------------------------------------------------------

drop policy if exists shared_sheet_sessions_select_public on public.shared_sheet_sessions;

drop policy if exists shared_sheet_sessions_select_owner on public.shared_sheet_sessions;
create policy shared_sheet_sessions_select_owner
on public.shared_sheet_sessions
for select
to authenticated
using ((select auth.uid()) = owner_user_id);

drop policy if exists shared_sheet_sessions_insert_owner on public.shared_sheet_sessions;
create policy shared_sheet_sessions_insert_owner
on public.shared_sheet_sessions
for insert
to authenticated
with check ((select auth.uid()) = owner_user_id);

drop policy if exists shared_sheet_sessions_update_owner on public.shared_sheet_sessions;
create policy shared_sheet_sessions_update_owner
on public.shared_sheet_sessions
for update
to authenticated
using ((select auth.uid()) = owner_user_id)
with check ((select auth.uid()) = owner_user_id);

drop policy if exists shared_sheet_sessions_delete_owner on public.shared_sheet_sessions;
create policy shared_sheet_sessions_delete_owner
on public.shared_sheet_sessions
for delete
to authenticated
using ((select auth.uid()) = owner_user_id);

-- ---------------------------------------------------------------------------
-- Legacy remote history_entries table.
--
-- Current Android sync uses shared_sheet_sessions, not public.history_entries.
-- This legacy table has no owner_user_id column, so owner-scoped RLS cannot be
-- expressed safely. Remove broad authenticated policies instead of leaving a
-- cross-user table open for future clients.
-- ---------------------------------------------------------------------------

drop policy if exists "authenticated can read history_entries" on public.history_entries;
drop policy if exists "authenticated can insert history_entries" on public.history_entries;
drop policy if exists "authenticated can update history_entries" on public.history_entries;
drop policy if exists "authenticated can delete history_entries" on public.history_entries;

-- ---------------------------------------------------------------------------
-- Advisor hardening.
-- ---------------------------------------------------------------------------

alter view public.product_price_summary set (security_invoker = true);

alter function public.inventory_catalog_block_update_when_tombstoned()
set search_path = public, pg_temp;

alter function public.set_updated_at()
set search_path = public, pg_temp;

-- ---------------------------------------------------------------------------
-- Sync/path indexes for current Android and future iOS/POS clients.
-- ---------------------------------------------------------------------------

create index if not exists inventory_suppliers_owner_id_idx
on public.inventory_suppliers(owner_user_id, id);

create index if not exists inventory_categories_owner_id_idx
on public.inventory_categories(owner_user_id, id);

create index if not exists inventory_products_owner_id_idx
on public.inventory_products(owner_user_id, id);

create index if not exists inventory_product_prices_owner_id_idx
on public.inventory_product_prices(owner_user_id, id);

create index if not exists shared_sheet_sessions_owner_remote_id_idx
on public.shared_sheet_sessions(owner_user_id, remote_id);

commit;
