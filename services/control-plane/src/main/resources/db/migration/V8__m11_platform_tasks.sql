create table platform_tasks (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    task_id varchar(160) not null,
    conversation_id varchar(160),
    intent varchar(80) not null,
    user_input text not null,
    requested_output varchar(40) not null,
    attachment_refs jsonb not null,
    plan jsonb not null,
    status varchar(40) not null,
    idempotency_key varchar(160) not null,
    semantic_hash varchar(64) not null,
    result jsonb,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    unique (tenant_id, project_id, task_id),
    unique (tenant_id, project_id, idempotency_key)
);

create index platform_tasks_project_idx
    on platform_tasks (tenant_id, project_id, created_at desc);
