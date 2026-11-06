create table routing_decisions (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    task_id varchar(160) not null,
    graph_revision integer not null,
    policy_version varchar(80) not null,
    provider varchar(40) not null,
    model_version varchar(120) not null,
    input_digest varchar(80) not null,
    decision_id varchar(160) not null,
    decision jsonb not null,
    compiled_dag jsonb not null,
    status varchar(40) not null,
    created_at timestamptz not null,
    version bigint not null default 0,
    unique (tenant_id, project_id, task_id, graph_revision, policy_version),
    unique (tenant_id, project_id, decision_id)
);

create index routing_decisions_task_idx
    on routing_decisions (tenant_id, project_id, task_id, graph_revision desc);
