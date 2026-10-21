-- 0.1.10：超管种子口令收敛（幂等可重复）。
--
-- 背景：0.1.7 第 41 项对线上默认 admin 的口令更新只落了数据行（AdminP@ssw0rd），
-- 种子 V0.1.7 仍写 admin123，新装环境与线上口令不一致。
--
-- 口径：仅默认超管账号（tenant 0 / username=admin / is_admin=1）口令改为
-- AdminP@ssw0rd；演示普通用户口令保持 123456，演示租户管理员保持
-- AdminP@ssw0rd（各自种子不变）。已更新过口令的存量库再次执行为同值覆写。
-- 明文密码: AdminP@ssw0rd（仅 dev/种子口径；BCrypt strength=10）

update sys_user
set password = '$2a$10$U97f1Pe2S97SXZIHiD6LpeQbHBMLvq0ZhJOsdDe1OBw5EGXV6rc6W'
where deleted = 0
  and tenant_id = 0
  and username = 'admin'
  and is_admin = 1;
