-- RSDP V17 数据库迁移：方案明细排序回填（RSDP × rooom 全量复现阶段 9）
-- 基于 docs/08-roadmap/RSDP-rooom全量复现与AI整合方案.md 阶段 9
-- scheme_item.sort_order 列自 V4 已存在，但存量数据全为 0，拖拽排序需要稳定初始序号。
-- 按 scheme 分组以 created_at, scheme_item_id 顺序回填 1..N。
-- 幂等：IS DISTINCT FROM 守卫，重复执行不重复写入。

WITH ranked AS (
    SELECT scheme_item_id,
           ROW_NUMBER() OVER (PARTITION BY scheme_id ORDER BY created_at, scheme_item_id) AS rn
    FROM scheme_item
    WHERE deleted_at IS NULL
)
UPDATE scheme_item si SET sort_order = r.rn
FROM ranked r
WHERE si.scheme_item_id = r.scheme_item_id
  AND si.sort_order IS DISTINCT FROM r.rn;
