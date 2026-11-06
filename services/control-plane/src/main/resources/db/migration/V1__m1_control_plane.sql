create table projects (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    name varchar(240) not null,
    created_at timestamptz not null,
    version bigint not null default 0,
    unique (tenant_id, project_id)
);

create table source_events (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    event_id varchar(160) not null,
    event_type varchar(160) not null,
    occurred_at timestamptz not null,
    ingested_at timestamptz not null,
    attributes jsonb not null,
    version bigint not null default 0,
    unique (tenant_id, project_id, event_id)
);

create table risks (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    risk_id varchar(160) not null,
    source_event_id varchar(160) not null,
    title varchar(300) not null,
    status varchar(40) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    unique (tenant_id, project_id, risk_id),
    unique (tenant_id, project_id, source_event_id)
);

create table evidence (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    evidence_id varchar(160) not null,
    risk_ref varchar(160) not null,
    source_type varchar(120) not null,
    source_ref varchar(300) not null,
    source_version varchar(120),
    claim text not null,
    observed_at timestamptz not null,
    ingested_at timestamptz not null,
    valid_until timestamptz,
    digest varchar(256),
    quote_text text,
    structured_value jsonb,
    reliability varchar(80) not null,
    completeness varchar(80) not null,
    tool_provenance jsonb not null,
    semantic_hash varchar(64) not null,
    version bigint not null default 0,
    unique (tenant_id, project_id, evidence_id)
);

create table investigation_tasks (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    task_id varchar(160) not null,
    goal text not null,
    risk_ref varchar(160) not null,
    status varchar(80) not null,
    graph_revision integer not null,
    budgets jsonb not null,
    semantic_hash varchar(64) not null,
    version bigint not null default 0,
    unique (tenant_id, project_id, task_id)
);

create table audit_logs (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    actor varchar(160) not null,
    action varchar(160) not null,
    aggregate_type varchar(80) not null,
    aggregate_id varchar(160) not null,
    details jsonb not null,
    created_at timestamptz not null
);

create table outbox_events (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    event_id varchar(200) not null,
    aggregate_type varchar(80) not null,
    aggregate_id varchar(160) not null,
    event_type varchar(160) not null,
    payload jsonb not null,
    created_at timestamptz not null,
    published_at timestamptz,
    unique (tenant_id, project_id, event_id)
);
