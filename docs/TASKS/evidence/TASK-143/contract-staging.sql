-- Evidence script executed 2026-09-28 against verified TEST staging only.
-- Authenticated-role contract test; entire fixture and mutations roll back.
-- This does not establish fresh mobile authentication or HTTP ACK-loss recovery.
begin;
set local statement_timeout = '30s';
do $probe$
declare
  v_shop uuid; v_owner uuid; v_product uuid := gen_random_uuid();
  v_key uuid := gen_random_uuid(); v_next_key uuid := gen_random_uuid();
  v_bad_key uuid := gen_random_uuid();
  v_a jsonb; v_b jsonb; v_result jsonb; v_replay jsonb; v_errors jsonb;
  v_start timestamptz; v_times numeric[] := '{}'::numeric[];
  v_replay_times numeric[] := '{}'::numeric[];
  v_checks jsonb := '{}'::jsonb; v_version bigint := 0;
  v_before bigint; v_after bigint; v_count bigint; v_saved jsonb; v_i integer;
  v_prefix text := 'TASK143_MOBILE_PARITY_20260928_SQL';
begin
  select s.shop_id,m.profile_id into strict v_shop,v_owner
  from public.shops s join public.shop_members m on m.shop_id=s.shop_id
  join public.profiles p on p.profile_id=m.profile_id
  where s.shop_code='TASK068E_260618231325' and s.shop_status='active'
    and m.role_key='shop_owner' and m.membership_status='active'
    and p.profile_status='active';
  select count(*) into v_before from public.inventory_products where shop_id=v_shop;
  if exists(select 1 from public.inventory_products where barcode like v_prefix || '%') then
    raise exception 'Dedicated synthetic fixture prefix already exists; stop';
  end if;
  perform set_config('request.jwt.claims',jsonb_build_object('sub',v_owner,'role','authenticated')::text,true);
  execute 'set local role authenticated';
  insert into public.inventory_products(id,owner_user_id,shop_id,barcode,product_name,retail_price,stock_quantity)
  values(v_product,v_owner,v_shop,v_prefix,'Synthetic mobile parity SQL fixture',100,0);
  v_a := jsonb_build_object('sourceProductId',v_product,'publicName',v_prefix||'_A',
    'publicPrice',100,'priceSourceMode','override','featured',false,'homeOrder',0,
    'pickupEnabled',false,'deliveryEnabled',false,'reservationEnabled',false,'availability','available');
  v_b := v_a || jsonb_build_object('publicName',v_prefix||'_B');
  v_result := public.storefront_publication_authoring_mutate_v1(v_shop,'save_draft',v_a,v_key,0);
  if not coalesce((v_result->>'ok')::boolean,false) then raise exception 'Fixture initial save failed: %',v_result->>'code'; end if;
  v_saved := v_result;
  v_checks := v_checks || jsonb_build_object('save_A_applied_version_1',v_result->'payload'->>'version'='1');
  -- The first response is deliberately not used as the next attempt's base.
  -- This is response-discard simulation inside one rolled-back SQL transaction.
  v_replay := public.storefront_publication_authoring_mutate_v1(v_shop,'save_draft',v_a,v_key,0);
  v_checks := v_checks || jsonb_build_object('replay_A_old_expected_version_idempotent',
    (v_replay->>'idempotent')::boolean and v_replay->'payload'=v_saved->'payload'
    and v_replay->>'audit_event_id'=v_saved->>'audit_event_id');
  v_result := public.storefront_publication_authoring_mutate_v1(v_shop,'save_draft',v_b,v_key,0);
  v_checks := v_checks || jsonb_build_object('B_same_key_rejected',v_result->>'code'='idempotency_conflict');
  v_errors := jsonb_build_object('B_same_key',v_result->>'code');
  v_result := public.storefront_publication_authoring_mutate_v1(v_shop,'save_draft',v_b,v_next_key,0);
  v_checks := v_checks || jsonb_build_object('B_new_key_old_version_stale',v_result->>'code'='stale_revision');
  v_errors := v_errors || jsonb_build_object('B_new_key_old_version',v_result->>'code');
  v_result := public.storefront_publication_authoring_mutate_v1(v_shop,'save_draft',v_b,v_next_key,1);
  v_checks := v_checks || jsonb_build_object('B_new_key_current_version_applied',
    (v_result->>'ok')::boolean and v_result->'payload'->>'version'='2'
    and v_result->'payload'->>'publicName'=v_prefix||'_B');
  v_result := public.storefront_publication_authoring_mutate_v1(v_shop,'save_draft',v_b||jsonb_build_object('publicName',''),v_bad_key,2);
  v_checks := v_checks || jsonb_build_object('invalid_payload_rejected',v_result->>'code'='validation_failed');
  v_errors := v_errors || jsonb_build_object('invalid_payload',v_result->>'code');
  v_replay := public.storefront_publication_authoring_mutate_v1(v_shop,'save_draft',v_a,v_key,0);
  v_checks := v_checks || jsonb_build_object('A_replay_after_B_returns_original_ACK',
    (v_replay->>'idempotent')::boolean and v_replay->'payload'=v_saved->'payload');
  execute 'set local role postgres';
  select jsonb_build_object('version',catalog_version,'name_matches_B',public_name=v_prefix||'_B',
    'status',publication_status,'correlation_matches_B',last_correlation_id=v_next_key) into v_result
  from public.storefront_product_publications where shop_id=v_shop and source_product_id=v_product;
  v_checks := v_checks || jsonb_build_object('independent_record_readback_B_version_2',
    v_result=jsonb_build_object('version',2,'name_matches_B',true,'status','draft','correlation_matches_B',true));
  select count(*) into v_count from public.audit_logs where shop_id=v_shop
    and target_type='storefront_publication' and target_id=v_saved->>'target_id'
    and event_key='shop.storefront.authoring.save_draft.success';
  v_checks := v_checks || jsonb_build_object('exactly_two_success_audits_before_benchmark',v_count=2);
  v_version:=2;
  execute 'set local role authenticated';
  for v_i in 1..10 loop
    v_next_key:=gen_random_uuid();
    v_b:=v_a||jsonb_build_object('publicName',v_prefix||'_TIMING_'||v_i);
    v_start:=clock_timestamp();
    v_result:=public.storefront_publication_authoring_mutate_v1(v_shop,'save_draft',v_b,v_next_key,v_version);
    v_times:=array_append(v_times,1000*extract(epoch from clock_timestamp()-v_start));
    if not coalesce((v_result->>'ok')::boolean,false) then raise exception 'Timing save failed: %',v_result->>'code'; end if;
    v_start:=clock_timestamp();
    v_replay:=public.storefront_publication_authoring_mutate_v1(v_shop,'save_draft',v_b,v_next_key,v_version);
    v_replay_times:=array_append(v_replay_times,1000*extract(epoch from clock_timestamp()-v_start));
    if not coalesce((v_replay->>'idempotent')::boolean,false) or v_replay->'payload'<>v_result->'payload' then raise exception 'Timing replay failed'; end if;
    v_version:=v_version+1;
  end loop;
  execute 'set local role postgres';
  select count(*) into v_after from public.inventory_products where shop_id=v_shop;
  select count(*) into v_count from public.storefront_product_publications where shop_id=v_shop and source_product_id=v_product;
  v_checks:=v_checks||jsonb_build_object('one_fixture_product',v_after-v_before=1,'one_publication',v_count=1,'ten_timed_mutations_and_replays',v_version=12);
  perform set_config('mobile_parity.result',jsonb_build_object(
    'classification','SQL_CONTRACT_REAL_STAGING_TRANSACTION_ROLLBACK',
    'profile_hash',md5(v_owner::text),'shop_hash',md5(v_shop::text),'synthetic_product_hash',md5(v_product::text),
    'fixture_prefix',v_prefix,'checks',v_checks,'errors',v_errors,
    'before_product_count',v_before,'transaction_product_count',v_after,
    'mutation_ms',v_times,'replay_ms',v_replay_times,
    'writes_role','authenticated','published',false,'physical_commit_or_HTTP_loss_tested',false
  )::text,true);
end;
$probe$;
select current_setting('mobile_parity.result')::jsonb as result;
rollback;
