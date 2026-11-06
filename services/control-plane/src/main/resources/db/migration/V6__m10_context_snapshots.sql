create table context_snapshots (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    task_id varchar(160) not null,
    agent_task_id varchar(160) not null,
    context_id varchar(200) not null,
    graph_revision integer not null,
    role varchar(40) not null,
    verification_stage varchar(40),
    context_policy_version varchar(80) not null,
    prompt_version varchar(80) not null,
    objective_digest varchar(80) not null,
    context_digest varchar(80) not null,
    context_payload jsonb not null,
    manifest jsonb not null,
    estimated_tokens integer not null,
    status varchar(40) not null,
    created_at timestamptz not null,
    expires_at timestamptz,
    version bigint not null default 0,
    unique (
        tenant_id, project_id, task_id, agent_task_id, graph_revision,
        context_policy_version, prompt_version
    ),
    unique (tenant_id, project_id, context_id)
);

create index context_snapshots_task_idx
    on context_snapshots (tenant_id, project_id, task_id, graph_revision desc);
