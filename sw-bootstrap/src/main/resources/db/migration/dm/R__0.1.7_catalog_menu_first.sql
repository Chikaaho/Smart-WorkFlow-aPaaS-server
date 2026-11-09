-- 0.1.7 #41（Owner）：流程中心（菜单 320 ProcessCatalog）在前台流程中心侧栏置顶（幂等可重复）。
-- 侧栏口径：门户区「流程管理」（父菜单 5）下进入前台 area 清单的 7 个页面，按 sort 升序渲染；
-- 原 sort=24 排在第 6 位（待办 10 / 已办 20 / 我发起的 21 / 我的草稿 22 / 我的已办 23 / 流程中心 24 / 抄送我的 25），
-- 置顶取值 < 10 即可 —— 这里取 5。
UPDATE sys_menu SET sort = 5, update_time = current_timestamp WHERE id = 320 AND deleted = 0;
