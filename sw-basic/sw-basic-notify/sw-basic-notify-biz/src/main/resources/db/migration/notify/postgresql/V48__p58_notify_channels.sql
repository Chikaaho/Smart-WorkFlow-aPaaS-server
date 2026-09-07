alter table sw_notify_message add column channel varchar(40) not null default 'IN_APP';
alter table sw_notify_message add column delivery_status varchar(20) not null default 'SUCCESS';
alter table sw_notify_message add column external_message_id varchar(200);
alter table sw_notify_message add column failure_reason varchar(500);
alter table sw_notify_message add column idempotency_key varchar(200);
create index idx_sw_notify_msg_delivery on sw_notify_message (tenant_id, delivery_status);
create unique index uk_sw_notify_msg_idempotency on sw_notify_message (tenant_id, idempotency_key);
