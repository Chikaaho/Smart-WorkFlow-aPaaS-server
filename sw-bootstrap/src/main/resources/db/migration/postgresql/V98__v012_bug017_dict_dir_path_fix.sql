-- ===================================================================
-- V98: V012-BUG-017 修正 —— 字典管理目录 path 与子项去重
--
-- V97 将 id=10「字典管理」转为目录（menu_type=0），但其 path 仍是 V95
-- 设置的 'system/dict'，与子项 400（字典类型，path='system/dict'）完全
-- 相同：目录 redirect 注册在 /system/dict 且首叶也是 /system/dict，
-- 构成自指重定向（路由无限循环，初始导航永不就绪）。
-- 本迁移把目录 path 改为独立前缀 'system/dict-manage'；子项 400 保持
-- 'system/dict'（既有深链继续可达字典类型页），401 保持 'system/dict-data'。
-- 幂等：仅当当前为冲突值时更新。
-- ===================================================================
UPDATE sys_menu SET path = 'system/dict-manage', update_time = current_timestamp
WHERE id = 10 AND path = 'system/dict' AND menu_type = 0;
