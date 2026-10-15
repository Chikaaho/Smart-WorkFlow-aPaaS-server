-- BF20261013-020：低代码表单分类型（元数据区分）。
-- form_type：BUSINESS（业务表单，存量默认）/ APPROVAL_COMMENT（审批意见表单，流程节点审批意见专用，
-- 在「流程管理 → 审批意见表单」单独维护；流程节点选择器只列该类型）。

ALTER TABLE sw_form_def ADD COLUMN form_type VARCHAR(20) NOT NULL DEFAULT 'BUSINESS';

COMMENT ON COLUMN sw_form_def.form_type IS '表单类型：BUSINESS(业务表单)/APPROVAL_COMMENT(审批意见表单，流程节点专用)';
