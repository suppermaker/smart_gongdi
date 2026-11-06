alter table action_proposals add column approval_idempotency_key varchar(160);
create unique index action_approval_idempotency_idx
    on action_proposals (tenant_id, project_id, action_id, approval_idempotency_key)
    where approval_idempotency_key is not null;
