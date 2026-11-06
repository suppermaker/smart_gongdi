create table knowledge_documents (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    document_id varchar(160) not null,
    file_name varchar(300) not null,
    media_type varchar(120) not null,
    source_ref varchar(500) not null,
    document_version varchar(120) not null,
    content_digest varchar(80) not null,
    status varchar(40) not null,
    metadata jsonb not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    unique (tenant_id, project_id, document_id),
    unique (tenant_id, project_id, content_digest)
);

create table knowledge_chunks (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    document_id varchar(160) not null,
    chunk_id varchar(200) not null,
    source_location varchar(300) not null,
    content text not null,
    token_estimate integer not null,
    embedding jsonb,
    embedding_model varchar(120),
    metadata jsonb not null,
    created_at timestamptz not null,
    unique (tenant_id, project_id, chunk_id)
);

create index knowledge_chunks_document_idx
    on knowledge_chunks (tenant_id, project_id, document_id);

create table platform_data_sources (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    data_source_id varchar(160) not null,
    name varchar(240) not null,
    source_type varchar(40) not null,
    credential_ref varchar(240) not null,
    read_only boolean not null,
    allowed_tables jsonb not null,
    status varchar(40) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    unique (tenant_id, project_id, data_source_id)
);

create table schema_snapshots (
    id bigserial primary key,
    tenant_id varchar(120) not null,
    project_id varchar(120) not null,
    data_source_id varchar(160) not null,
    snapshot_version varchar(120) not null,
    schema_payload jsonb not null,
    content_digest varchar(80) not null,
    created_at timestamptz not null,
    unique (tenant_id, project_id, data_source_id, snapshot_version)
);
