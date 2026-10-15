-- ===================================================================
-- 应用中心与表单分类统一（L）：应用（一级分类）→ 目录（二级分类）→ 表单
--
-- 背景：既有一级分类是流程事项分类（sw_bpm_category），表单分类由前端按事项
-- formKey 派生（同 key 取首项，属偶然结果）；无业务应用/目录实体。
-- 本迁移：
--   1. 新增 sw_form_app / sw_form_app_dir / sw_form_app_user 三表；
--      sw_form_def 增加 app_id / dir_id 归属列（目录可空=直接归属应用）。
--   2. 存量承接（幂等条件均为确定性判定，不取首项、不自动复制业务数据）：
--      a) 每个有效分类承接为一个应用（id = 'lc' || category.id，legacy_category_id 保留追溯）；
--      b) 表单归属：该 formKey 的全部有效事项（deleted=0）关联的有效分类去重后
--         恰为一个 → 归属该分类承接应用；为零个（分类缺失且无冲突）→ 归属本租户
--         「未分类」承接应用（id = 'uncat-t' || tenant_id，仅在该租户存在此类表单时创建）；
--         多于一个（跨分类共用表单=多归属歧义）→ 不自动转换，app_id 保持 NULL，
--         保留原可用路径，由正式冲突清单交 Planner 裁决后才转换。
--      c) 承接应用可见范围 = ALL（本租户全部用户），以保留既有合法访问；
--         数据读取仍受既有表单/数据权限约束，应用可见性不放大数据范围。
--   3. 回退：新代码回滚后旧代码不读写本三表与两列，存量数据保持原表原义，
--      不通过破坏性 DDL 删除事实。
-- 方言：H2/PostgreSQL 双份；本文件为 PostgreSQL 版（COMMENT ON），H2 版无 comment。
-- ===================================================================

-- ==================== 1. 应用表 ====================
CREATE TABLE sw_form_app (
    id                  VARCHAR(36)  PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL DEFAULT 0,
    name                VARCHAR(100) NOT NULL,
    icon                VARCHAR(64),
    color               VARCHAR(32),
    sort_no             INT          NOT NULL DEFAULT 0,
    visible_scope_type  VARCHAR(20)  NOT NULL DEFAULT 'SPECIFIED',
    status              VARCHAR(20)  NOT NULL DEFAULT 'ENABLED',
    source              VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    legacy_category_id  BIGINT,
    deleted             SMALLINT     NOT NULL DEFAULT 0,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by           BIGINT,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    version             BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_form_app_tenant ON sw_form_app (tenant_id, deleted, sort_no);

COMMENT ON TABLE  sw_form_app                    IS '业务应用（表单一级分类）';
COMMENT ON COLUMN sw_form_app.id                 IS '应用 ID（承接存量分类为 lc<categoryId>，未分类承接为 uncat-t<tenantId>，新建为 UUID）';
COMMENT ON COLUMN sw_form_app.name               IS '应用名称';
COMMENT ON COLUMN sw_form_app.icon               IS '图标标识（前端图标名，可空取默认）';
COMMENT ON COLUMN sw_form_app.color              IS '主题色（可空取默认）';
COMMENT ON COLUMN sw_form_app.sort_no            IS '排序号（升序）';
COMMENT ON COLUMN sw_form_app.visible_scope_type IS '可见范围：ALL=本租户全部用户（显式选择）/ SPECIFIED=指定用户（关联集合空=无人可见）';
COMMENT ON COLUMN sw_form_app.status             IS '状态：ENABLED=启用 / DISABLED=停用（阻止新增入口，保留数据与历史）';
COMMENT ON COLUMN sw_form_app.source             IS '来源：MANUAL=后台新建 / LEGACY_CATEGORY=存量分类承接 / LEGACY_UNCATEGORIZED=存量未分类承接';
COMMENT ON COLUMN sw_form_app.legacy_category_id IS '承接来源分类 ID（source=LEGACY_CATEGORY 时有效，可追溯）';

-- ==================== 2. 应用目录表（应用内二级分类，单层，只属于一个应用） ====================
CREATE TABLE sw_form_app_dir (
    id          VARCHAR(36)  PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    app_id      VARCHAR(36)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    sort_no     INT          NOT NULL DEFAULT 0,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by   BIGINT,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by   BIGINT,
    version     BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_form_app_dir_app ON sw_form_app_dir (tenant_id, app_id, deleted, sort_no);

COMMENT ON TABLE  sw_form_app_dir      IS '应用目录（应用内二级分类，单层）';
COMMENT ON COLUMN sw_form_app_dir.app_id IS '所属应用 ID（目录只属于一个应用）';

-- ==================== 3. 应用指定用户表（可见范围 = SPECIFIED 时生效） ====================
CREATE TABLE sw_form_app_user (
    id          VARCHAR(36) PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    app_id      VARCHAR(36) NOT NULL,
    user_id     BIGINT      NOT NULL,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by   BIGINT,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by   BIGINT,
    version     BIGINT      NOT NULL DEFAULT 0
);

-- 唯一键不带 deleted：关联行是纯配置关系，可见范围重配走物理删除重建
-- （与 sys_role_menu 同一口径，避免逻辑删行占用唯一键导致「移除后再加入」撞键）。
CREATE UNIQUE INDEX uk_sw_form_app_user ON sw_form_app_user (tenant_id, app_id, user_id);

COMMENT ON TABLE  sw_form_app_user         IS '应用指定可见用户（SPECIFIED 范围的关联集合）';
COMMENT ON COLUMN sw_form_app_user.user_id IS '用户 ID（sys_user.id）';

-- ==================== 4. 表单归属列 ====================
ALTER TABLE sw_form_def ADD COLUMN app_id VARCHAR(36);
ALTER TABLE sw_form_def ADD COLUMN dir_id VARCHAR(36);

CREATE INDEX idx_sw_form_def_app ON sw_form_def (tenant_id, app_id, deleted);

COMMENT ON COLUMN sw_form_def.app_id IS '归属应用 ID（sw_form_app.id；NULL=承接歧义对象，未转换）';
COMMENT ON COLUMN sw_form_def.dir_id IS '归属目录 ID（sw_form_app_dir.id；NULL=直接归属应用）';

-- ==================== 5. 存量承接：分类 → 应用 ====================
INSERT INTO sw_form_app (id, tenant_id, name, icon, color, sort_no, visible_scope_type, status, source,
                         legacy_category_id, deleted, create_time, update_time, version)
SELECT 'lc' || c.id, c.tenant_id, c.name, 'Folder', '#7e306b', c.sort_no, 'ALL', 'ENABLED', 'LEGACY_CATEGORY',
       c.id, 0, current_timestamp, current_timestamp, 0
FROM sw_bpm_category c
WHERE c.deleted = 0;

-- ==================== 6. 存量承接：唯一分类表单归属 ====================
UPDATE sw_form_def f SET app_id = (
    SELECT 'lc' || MIN(pd.category_id)
    FROM sw_bpm_process_def pd
    JOIN sw_bpm_category c ON c.id = pd.category_id AND c.deleted = 0
    WHERE pd.form_key = f.form_key AND pd.tenant_id = f.tenant_id AND pd.deleted = 0
)
WHERE f.deleted = 0
  AND (SELECT COUNT(DISTINCT pd.category_id)
       FROM sw_bpm_process_def pd
       JOIN sw_bpm_category c ON c.id = pd.category_id AND c.deleted = 0
       WHERE pd.form_key = f.form_key AND pd.tenant_id = f.tenant_id AND pd.deleted = 0) = 1;

-- ==================== 7. 存量承接：未分类应用（按需创建） ====================
INSERT INTO sw_form_app (id, tenant_id, name, icon, color, sort_no, visible_scope_type, status, source,
                         legacy_category_id, deleted, create_time, update_time, version)
SELECT 'uncat-t' || f.tenant_id, f.tenant_id, '未分类', 'Folder', '#909399', 9999, 'ALL', 'ENABLED',
       'LEGACY_UNCATEGORIZED', NULL, 0, current_timestamp, current_timestamp, 0
FROM sw_form_def f
WHERE f.deleted = 0
  AND (SELECT COUNT(DISTINCT pd.category_id)
       FROM sw_bpm_process_def pd
       JOIN sw_bpm_category c ON c.id = pd.category_id AND c.deleted = 0
       WHERE pd.form_key = f.form_key AND pd.tenant_id = f.tenant_id AND pd.deleted = 0) = 0
GROUP BY f.tenant_id;

-- ==================== 8. 存量承接：分类缺失表单归属未分类 ====================
UPDATE sw_form_def f SET app_id = 'uncat-t' || f.tenant_id
WHERE f.deleted = 0 AND f.app_id IS NULL
  AND (SELECT COUNT(DISTINCT pd.category_id)
       FROM sw_bpm_process_def pd
       JOIN sw_bpm_category c ON c.id = pd.category_id AND c.deleted = 0
       WHERE pd.form_key = f.form_key AND pd.tenant_id = f.tenant_id AND pd.deleted = 0) = 0;
