-- V21：audit_log.action 扩列 varchar(16) → varchar(64)
-- 背景：Scene Revision 审计动作名 ACTIVATE_SCENE_REVISION(23)/TARGET_SCENE_SWITCH(19)
-- 超出原 varchar(16)，异步审计写入被静默吞掉（§8.5 步骤 12 要求激活必须留审计）。
-- 幂等：ALTER COLUMN TYPE 可重复执行。

ALTER TABLE audit_log ALTER COLUMN action TYPE VARCHAR(64);
