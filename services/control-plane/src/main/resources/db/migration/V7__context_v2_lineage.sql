alter table context_snapshots
    add column context_mode varchar(20) not null default 'FULL',
    add column parent_context_id varchar(200),
    add column parent_context_digest varchar(80),
    add column parent_graph_revision integer,
    add column chain_depth integer not null default 0;

create index context_snapshots_lineage_idx
    on context_snapshots (
        tenant_id, project_id, task_id, agent_task_id,
        context_policy_version, prompt_version, graph_revision desc
    );

alter table context_snapshots
    add constraint context_snapshot_parent_shape check (
        (context_mode = 'FULL' and parent_context_id is null
            and parent_context_digest is null and parent_graph_revision is null)
        or
        (context_mode = 'DELTA' and parent_context_id is not null
            and parent_context_digest is not null and parent_graph_revision is not null)
    );
