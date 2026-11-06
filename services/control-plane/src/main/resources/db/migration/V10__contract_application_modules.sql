alter table knowledge_documents
    add column object_content bytea,
    add column extracted_text text,
    add column summary text;

alter table platform_data_sources
    add column allowed_columns jsonb not null default '{}'::jsonb,
    add column synonyms jsonb not null default '{}'::jsonb,
    add column project_column varchar(120) not null default 'project_id',
    add column query_timeout_seconds integer not null default 5,
    add column max_rows integer not null default 200;

create table conversations (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    conversation_id varchar(160) not null,
    title varchar(300) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    unique (tenant_id, project_id, conversation_id)
);

create table conversation_messages (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    conversation_id varchar(160) not null,
    message_id varchar(160) not null,
    message_role varchar(40) not null,
    content text not null,
    citations jsonb not null,
    created_at timestamptz not null,
    unique (tenant_id, project_id, message_id)
);

create table review_templates (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    template_id varchar(160) not null,
    template_version varchar(120) not null,
    name varchar(240) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    rules jsonb not null,
    created_at timestamptz not null,
    unique (tenant_id, project_id, template_id, template_version)
);

create table review_jobs (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    job_id varchar(160) not null,
    template_id varchar(160) not null,
    template_version varchar(120) not null,
    document_id varchar(160),
    structured_input jsonb not null,
    findings jsonb not null,
    status varchar(40) not null,
    idempotency_key varchar(160) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (tenant_id, project_id, job_id),
    unique (tenant_id, project_id, idempotency_key)
);

create table report_templates (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    template_id varchar(160) not null,
    template_version varchar(120) not null,
    name varchar(240) not null,
    template_text text not null,
    variables jsonb not null,
    sections jsonb not null,
    created_at timestamptz not null,
    unique (tenant_id, project_id, template_id, template_version)
);

create table report_jobs (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    job_id varchar(160) not null,
    template_id varchar(160) not null,
    template_version varchar(120) not null,
    output_format varchar(20) not null,
    bindings jsonb not null,
    citations jsonb not null,
    narrative text,
    artifact_name varchar(300),
    artifact_content bytea,
    status varchar(40) not null,
    error_code varchar(120),
    idempotency_key varchar(160) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (tenant_id, project_id, job_id),
    unique (tenant_id, project_id, idempotency_key)
);

create table ocr_jobs (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    job_id varchar(160) not null,
    document_type varchar(40) not null,
    source_object_ref varchar(300) not null,
    input_text text,
    extraction_schema jsonb not null,
    result jsonb not null,
    adapter varchar(80) not null,
    status varchar(40) not null,
    idempotency_key varchar(160) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (tenant_id, project_id, job_id),
    unique (tenant_id, project_id, idempotency_key)
);

create index conversation_messages_conversation_idx
    on conversation_messages (tenant_id, project_id, conversation_id, created_at);
create index review_jobs_project_idx on review_jobs (tenant_id, project_id, created_at desc);
create index report_jobs_project_idx on report_jobs (tenant_id, project_id, created_at desc);
create index ocr_jobs_project_idx on ocr_jobs (tenant_id, project_id, created_at desc);
