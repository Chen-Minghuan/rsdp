\set ON_ERROR_STOP on
\pset pager off
\if :{?apply}
\else
  \set apply false
\endif
\if :{?office_actor_id}
\else
  \echo 'Set -v office_actor_id to the verified historical Office service account ID.'
  DO $$ BEGIN RAISE EXCEPTION 'Missing backfill parameter'; END $$;
\endif
\if :{?office_factory_code}
\else
  \echo 'Set -v office_factory_code to the factory verified by original Office entry records.'
  DO $$ BEGIN RAISE EXCEPTION 'Missing backfill parameter'; END $$;
\endif
\if :apply
  \if :{?expected_rows}
  \else
    \echo 'Set -v expected_rows to the reviewed preview count before applying.'
    DO $$ BEGIN RAISE EXCEPTION 'Missing backfill parameter'; END $$;
  \endif
  \if :{?run_id}
  \else
    \echo 'Set -v run_id to a stable audit ID for this apply.'
    DO $$ BEGIN RAISE EXCEPTION 'Missing backfill parameter'; END $$;
  \endif
\endif

BEGIN;
-- Legacy product creation times were stored as Asia/Shanghai wall time.
SET LOCAL TIME ZONE 'Asia/Shanghai';
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '90s';
\if :apply
LOCK TABLE rspu_master IN SHARE ROW EXCLUSIVE MODE;
\endif

CREATE TEMP TABLE entry_source_backfill ON COMMIT DROP AS
WITH initial_create AS (
    SELECT DISTINCT ON (record_id) record_id, id, operator, created_at, new_value
    FROM audit_log
    WHERE table_name = 'rspu_master' AND action = 'CREATE'
    ORDER BY record_id, created_at, id
), evidence AS (
    SELECT m.rspu_id, m.created_by, m.created_at, m.deleted_at,
           u.nickname, u.username, original.id AS create_audit_id,
           batches.batch_count, batches.batch_id,
           first_task.task_id,
           office_quote.quote_audit_id, office_quote.factory_code, office_quote.factory_name
    FROM rspu_master m
    JOIN sys_user u ON u.user_id = m.created_by
    JOIN initial_create original ON original.record_id = m.rspu_id
       AND original.operator = u.username AND original.new_value->>'createdBy' = m.created_by
       AND original.created_at BETWEEN m.created_at - INTERVAL '20 seconds' AND m.created_at + INTERVAL '20 seconds'
    LEFT JOIN LATERAL (
        SELECT count(*) AS batch_count, min(candidate.batch_id) AS batch_id
        FROM (
            SELECT b.batch_id
            FROM excel_import_row r
            JOIN excel_import_batch b ON b.batch_id = r.batch_id AND b.created_by = m.created_by
            WHERE r.generated_rspu_id = m.rspu_id AND r.status = 'success'
              AND r.created_at BETWEEN m.created_at - INTERVAL '2 minutes' AND m.created_at + INTERVAL '2 minutes'
            UNION
            SELECT b.batch_id
            FROM supplier_product_ref ref
            JOIN excel_import_batch b ON b.batch_id = ref.first_import_batch_id AND b.created_by = m.created_by
            WHERE ref.rspu_id = m.rspu_id AND ref.first_seen_source_system = 'excel-ai-import'
              AND ref.created_at BETWEEN m.created_at - INTERVAL '2 minutes' AND m.created_at + INTERVAL '2 minutes'
        ) candidate
    ) batches ON true
    LEFT JOIN LATERAL (
        SELECT t.task_id
        FROM async_task t
        WHERE t.task_type = 'product_entry' AND t.input_data->>'source' = 'excel_import'
          AND t.input_data->>'rspuId' = m.rspu_id AND t.created_by = u.username
          AND t.created_at BETWEEN m.created_at - INTERVAL '2 minutes' AND m.created_at + INTERVAL '2 minutes'
        ORDER BY t.created_at, t.task_id
        LIMIT 1
    ) first_task ON true
    LEFT JOIN LATERAL (
        SELECT min(quote.id) AS quote_audit_id, min(factory.factory_code) AS factory_code,
               min(factory.factory_name) AS factory_name
        FROM audit_log quote
        JOIN factory_master factory ON factory.factory_code = quote.new_value->>'factoryCode'
        WHERE m.created_by = :'office_actor_id'
          AND quote.table_name = 'rsku_supply' AND quote.action = 'CREATE'
          AND quote.operator = original.operator AND quote.new_value->>'rspuId' = m.rspu_id
          AND quote.created_at BETWEEN m.created_at - INTERVAL '30 seconds' AND m.created_at + INTERVAL '30 seconds'
        HAVING count(DISTINCT factory.factory_code) = 1
           AND min(factory.factory_code) = :'office_factory_code'
    ) office_quote ON true
    WHERE to_jsonb(m)->>'source_channel' IS NULL AND to_jsonb(m)->>'entry_provenance' IS NULL
), classified AS (
    SELECT *, CASE
        WHEN created_by = :'office_actor_id' AND quote_audit_id IS NOT NULL
            AND batch_count = 0 AND task_id IS NULL THEN 'OFFICE_LEGACY'
        WHEN created_by <> :'office_actor_id' AND batch_count <= 1
            AND (batch_count = 1 OR task_id IS NOT NULL) THEN 'ROOMVIP_EXCEL'
    END AS channel
    FROM evidence
)
SELECT rspu_id, deleted_at, channel,
       jsonb_strip_nulls(jsonb_build_object(
           'actorId', created_by,
           'actorName', COALESCE(nullif(nickname,''), username),
           'actorKind', CASE WHEN channel = 'OFFICE_LEGACY' THEN 'SERVICE_ACCOUNT' ELSE 'ACCOUNT' END,
           'recordedAt', to_char(created_at, 'YYYY-MM-DD"T"HH24:MI:SS.US'),
           'batchId', CASE WHEN batch_count = 1 THEN batch_id END,
           'factoryCode', CASE WHEN channel = 'OFFICE_LEGACY' THEN factory_code END,
           'factoryName', CASE WHEN channel = 'OFFICE_LEGACY' THEN factory_name END,
           'evidence', jsonb_strip_nulls(jsonb_build_object(
               'createAuditId', create_audit_id, 'originalQuoteAuditId', quote_audit_id,
               'excelTaskId', task_id, 'excelBatchId', CASE WHEN batch_count = 1 THEN batch_id END))
       )) AS provenance
FROM classified
WHERE channel IS NOT NULL;

SELECT channel, count(*) FILTER (WHERE deleted_at IS NULL) AS current_products,
       count(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted_products,
       count(*) FILTER (WHERE provenance ? 'batchId') AS with_batch
FROM entry_source_backfill GROUP BY channel ORDER BY channel;

SELECT count(*) FILTER (WHERE m.deleted_at IS NULL) AS current_unresolved,
       count(*) FILTER (WHERE m.deleted_at IS NOT NULL) AS deleted_unresolved
FROM rspu_master m LEFT JOIN entry_source_backfill b USING (rspu_id)
WHERE to_jsonb(m)->>'source_channel' IS NULL AND b.rspu_id IS NULL;

\if :apply
SELECT (count(*) = :expected_rows) AS row_count_matches FROM entry_source_backfill \gset
\if :row_count_matches
WITH updated AS (
    UPDATE rspu_master m
    SET source_channel = b.channel, entry_provenance = b.provenance
    FROM entry_source_backfill b
    WHERE b.rspu_id = m.rspu_id AND m.source_channel IS NULL AND m.entry_provenance IS NULL
    RETURNING m.rspu_id, m.source_channel, m.entry_provenance
)
INSERT INTO audit_log(table_name, record_id, action, old_value, new_value, operator)
SELECT 'rspu_master', rspu_id, 'SOURCE_BACKFILL',
       jsonb_build_object('sourceChannel', NULL, 'entryProvenance', NULL),
       jsonb_build_object('sourceChannel', source_channel, 'entryProvenance', entry_provenance, 'runId', :'run_id'),
       'catalog-source-backfill'
FROM updated;
COMMIT;
\else
    ROLLBACK;
    \echo 'Preview count changed. Review before applying.'
    DO $$ BEGIN RAISE EXCEPTION 'Backfill count mismatch'; END $$;
\endif
\else
ROLLBACK;
\endif
