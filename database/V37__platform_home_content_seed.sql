-- V37：官网首页内容种子（home_trio_cards / home_service_cards）
-- 背景：官网首页区块 4「必逛好物」/ 区块 5「服务卡」请求 /api/v1/public/content/{code}，
-- platform_content 缺这两条种子时 PlatformPublicService.getContentByCode 抛
-- ResourceNotFoundException（"内容不存在或已停用"），每次首页加载刷 WARN 日志。
-- 前端 website/pages/index.vue 有静态兜底，不影响展示；本种子内容与该静态兜底保持一致，
-- 运营可在管理端「官网 CMS-内容管理」中修改。service_cards 不含 v1 遗留 icon 字段（v2 组件不渲染图标）。

INSERT INTO platform_content (content_id, code, title, content_type, content) VALUES
('CONT-HOME-TRIO-CARDS', 'home_trio_cards', '首页必逛好物', 'rich_text',
 '[{"title":"大减价","desc":"百余款商品 5 折起 · 即日至 8 月 31 日"},{"title":"当季新品","desc":"秋冬系列全新上市 · 探索新材质"},{"title":"更低价格","desc":"同样的设计 · 更可持续的价格"}]'),
('CONT-HOME-SERVICE-CARDS', 'home_service_cards', '首页服务卡', 'rich_text',
 '[{"title":"送货服务","desc":"珠三角 48 小时达，全国物流可追踪"},{"title":"安装服务","desc":"专业师傅上门，安装完毕清理现场"},{"title":"退换保障","desc":"30 天无理由退换（定制款除外）"},{"title":"免费设计","desc":"AI 户型搭配 + 设计师 1v1 复核"}]')
ON CONFLICT (code) DO NOTHING;
