create table graph_revisions (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    task_id varchar(160) not null,
    revision integer not null,
    parent_revision integer not null,
    delta_id varchar(160) not null,
    delta_hash varchar(64) not null,
    delta jsonb not null,
    actor varchar(160) not null,
    created_at timestamptz not null,
    unique (tenant_id, project_id, task_id, revision),
    unique (tenant_id, project_id, task_id, delta_id)
);

create table graph_nodes (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    task_id varchar(160) not null,
    node_id varchar(160) not null,
    node_type varchar(40) not null,
    semantic_hash varchar(64) not null,
    payload jsonb not null,
    created_revision integer not null,
    created_at timestamptz not null,
    unique (tenant_id, project_id, task_id, node_type, node_id)
);

create table evidence_edges (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    task_id varchar(160) not null,
    evidence_id varchar(160) not null,
    hypothesis_id varchar(160) not null,
    relation varchar(20) not null check (relation in ('SUPPORTS', 'CONTRADICTS')),
    created_revision integer not null,
    unique (tenant_id, project_id, task_id, evidence_id, hypothesis_id, relation)
);

create index graph_nodes_task_idx on graph_nodes (tenant_id, project_id, task_id, node_type);
