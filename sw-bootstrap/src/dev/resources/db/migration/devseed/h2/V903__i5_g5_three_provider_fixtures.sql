-- I5 复验 05（G5/G6）：dev 专用第三批夹具（仅 devseed location 装载）。
-- 为租户 100 增补 FEISHU/DINGTALK 启用配置（与 V901 WECOM 同一口径）：
-- appId 为哨兵测试值；secret 为 dev 密钥 AES-GCM 密文；明文不出现在任何仓库文件。
merge into sys_sso_provider_config (id, create_time, update_time, deleted, tenant_id, version,
                                    provider, enabled, app_id, app_secret_enc, extra_config, redirect_path)
key (id)
values (90002, current_timestamp, current_timestamp, 0, 100, 0,
        'FEISHU', 1, 'cli_sentinel_feishu_g5',
        'Y3Cj2xbiTI2xlg/0BbQRcjjt4ASrMpBGGVeVrWqDbCDhBbFNJGot9cJDEMaRHKdHuZQQ6rg=',
        '{}', '/workspace');

merge into sys_sso_provider_config (id, create_time, update_time, deleted, tenant_id, version,
                                    provider, enabled, app_id, app_secret_enc, extra_config, redirect_path)
key (id)
values (90003, current_timestamp, current_timestamp, 0, 100, 0,
        'DINGTALK', 1, 'ding_sentinel_g5',
        '7CQW6TUr6Ib3LJZBMRdF4+xZ/D5zA/J0PzN6N5C/Nfd4YAn9I96xYObGd4a6piKpyWxINrCL8A==',
        '{}', '/workspace');
