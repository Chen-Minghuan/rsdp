\set ON_ERROR_STOP on
\pset pager off
\if :{?apply}
\else
  \set apply false
\endif
\if :apply
  \if :{?expected_rows}
  \else
    DO $$ BEGIN RAISE EXCEPTION 'Set expected_rows to the reviewed preview count'; END $$;
  \endif
\endif

BEGIN;
SET LOCAL TIME ZONE 'Asia/Shanghai';
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
\if :apply
LOCK TABLE rspu_master, rsku_supply IN SHARE ROW EXCLUSIVE MODE;
\endif

CREATE TEMP TABLE verified_weihao_office_owner ON COMMIT DROP AS
SELECT m.rspu_id, m.rspu_code, m.entry_provenance, u.username
FROM rspu_master m
JOIN sys_user u ON u.user_id = m.created_by AND u.username = 'rsdp-office'
JOIN audit_log product_create ON product_create.id = CASE
    WHEN m.entry_provenance #>> '{evidence,createAuditId}' ~ '^[0-9]{1,18}$'
    THEN (m.entry_provenance #>> '{evidence,createAuditId}')::bigint END
JOIN audit_log first_quote ON first_quote.id = CASE
    WHEN m.entry_provenance #>> '{evidence,originalQuoteAuditId}' ~ '^[0-9]{1,18}$'
    THEN (m.entry_provenance #>> '{evidence,originalQuoteAuditId}')::bigint END
WHERE m.deleted_at IS NULL AND NOT m.owner_verified
  AND m.owner_type = 'FACTORY' AND m.owner_factory_code = 'B001'
  AND m.source_channel = 'OFFICE_LEGACY'
  AND m.entry_provenance->>'actorKind' = 'SERVICE_ACCOUNT'
  AND m.entry_provenance->>'actorId' = m.created_by
  AND m.entry_provenance->>'factoryCode' = 'B001'
  AND product_create.table_name = 'rspu_master' AND product_create.action = 'CREATE'
  AND product_create.record_id = m.rspu_id AND product_create.operator = u.username
  AND product_create.new_value->>'createdBy' = m.created_by
  AND product_create.created_at BETWEEN m.created_at - INTERVAL '20 seconds' AND m.created_at + INTERVAL '20 seconds'
  AND first_quote.table_name = 'rsku_supply' AND first_quote.action = 'CREATE'
  AND first_quote.operator = product_create.operator
  AND first_quote.new_value->>'rspuId' = m.rspu_id AND first_quote.new_value->>'factoryCode' = 'B001'
  AND first_quote.created_at BETWEEN m.created_at - INTERVAL '30 seconds' AND m.created_at + INTERVAL '30 seconds'
  AND EXISTS (SELECT 1 FROM rsku_supply q WHERE q.rspu_id = m.rspu_id
    AND q.deleted_at IS NULL AND q.factory_code = 'B001')
  AND NOT EXISTS (SELECT 1 FROM rsku_supply q WHERE q.rspu_id = m.rspu_id
    AND q.deleted_at IS NULL AND q.factory_code IS DISTINCT FROM 'B001');

SELECT count(*) AS eligible, min(rspu_code) AS first_code, max(rspu_code) AS last_code
FROM verified_weihao_office_owner;

\if :apply
SELECT (count(*) = :expected_rows) AS count_matches FROM verified_weihao_office_owner \gset
\if :count_matches
WITH updated AS (
    UPDATE rspu_master m
    SET owner_verified = true,
        entry_provenance = c.entry_provenance || jsonb_build_object('actorUsername', c.username)
    FROM verified_weihao_office_owner c WHERE m.rspu_id = c.rspu_id
    RETURNING m.rspu_id, m.owner_factory_code, m.entry_provenance,
        c.entry_provenance AS previous_provenance
)
INSERT INTO audit_log (table_name, record_id, action, old_value, new_value, operator)
SELECT 'rspu_master', rspu_id, 'OWNER_VERIFIED',
       jsonb_build_object('ownerVerified', false, 'factoryCode', owner_factory_code, 'entryProvenance', previous_provenance),
       jsonb_build_object('ownerVerified', true, 'factoryCode', owner_factory_code, 'entryProvenance', entry_provenance),
       'catalog-owner-verification'
FROM updated;
COMMIT;
\else
ROLLBACK;
DO $$ BEGIN RAISE EXCEPTION 'Ownership verification count changed'; END $$;
\endif
\else
ROLLBACK;
\endif
