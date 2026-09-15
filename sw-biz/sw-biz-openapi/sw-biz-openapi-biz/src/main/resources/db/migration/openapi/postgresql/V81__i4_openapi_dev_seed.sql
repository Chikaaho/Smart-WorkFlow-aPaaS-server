-- V81 (I4 §3.4): dev 演示开放应用种子（仅本机联调用，prod 不引用本迁移）。
-- secret 原文仅存 SHA-256 摘要：i4-dev-openapi-secret
-- callback_url 指向本机受控对端接收器。
insert into sw_openapi_app (id, create_time, update_time, deleted, tenant_id, version,
                            app_id, app_name, secret_hash, scopes, status, act_as_user_id,
                            callback_url, callback_secret_hash)
select 1, current_timestamp, current_timestamp, 0, 0, 0,
       'i4-demo-app', 'I4 演示外部应用', 'fcba56c261010a10a795ef40b347938165fcf6b69cbe9ab60eded236849dd49a',
       'PROCESS_START,PROCESS_QUERY,TASK_HANDLE', 'ENABLED', 1,
       'http://localhost:9999/i4/callback', null
where not exists (select 1 from sw_openapi_app where app_id = 'i4-demo-app');
