/* SitePilot Operations Desk
 * Dependency-free console. Two views share one trusted-context header set:
 *   1. Risk investigation  (read models + approval write)
 *   2. Contract platform   (platform task create / read / execute)
 * No build step: this file is loaded directly by index.html.
 */
(function () {
  'use strict';

  /* ------------------------------------------------------------------ context */

  // localStorage is optional: private modes and file:// origins can throw on access.
  function stored(key, fallback) {
    try {
      var value = window.localStorage.getItem(key);
      return value === null || value === undefined || value === '' ? fallback : value;
    } catch (error) {
      return fallback;
    }
  }

  var namespace = {
    tenant: stored('tenant', 'demo-tenant'),
    project: stored('project', 'demo-project'),
    actor: stored('actor', 'operator')
  };

  function requestHeaders() {
    return {
      'X-SitePilot-Tenant': namespace.tenant,
      'X-SitePilot-Project': namespace.project,
      'X-SitePilot-Actor': namespace.actor,
      'X-Correlation-ID': crypto.randomUUID()
    };
  }

  /* ---------------------------------------------------------------------- http */

  function ApiError(status, code, message) {
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.message = message || ('request failed with ' + status);
  }
  ApiError.prototype = Object.create(Error.prototype);

  function request(path, options) {
    options = options || {};
    var headers = Object.assign({}, requestHeaders(), options.headers || {});
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    return fetch(path, {
      method: options.method || 'GET',
      headers: headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body)
    }).then(function (response) {
      if (response.ok) {
        return response.status === 204 ? null : response.json();
      }
      return response.text().then(function (text) {
        var code = 'HTTP_' + response.status;
        var message = text;
        try {
          var parsed = JSON.parse(text);
          code = parsed.code || code;
          message = parsed.message || text;
        } catch (ignored) { /* non-JSON error body keeps raw text */ }
        throw new ApiError(response.status, code, message);
      });
    });
  }

  /* ------------------------------------------------------------------ dom utils */

  function $(id) { return document.getElementById(id); }

  function esc(value) {
    var node = document.createElement('span');
    node.textContent = value === null || value === undefined ? '' : String(value);
    return node.innerHTML;
  }

  function escAttr(value) {
    return esc(value).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function muted(text) { return '<p class="muted">' + esc(text) + '</p>'; }

  function asText(value) {
    if (value === null || value === undefined) return '';
    if (typeof value === 'string') return value;
    return JSON.stringify(value, null, 2);
  }

  function kvList(pairs) {
    var rows = pairs.filter(function (pair) { return pair && pair[1] !== undefined; });
    if (!rows.length) return muted('No fields returned.');
    return '<dl class="kv">' + rows.map(function (pair) {
      return '<dt>' + esc(pair[0]) + '</dt><dd>' + esc(asText(pair[1])) + '</dd>';
    }).join('') + '</dl>';
  }

  function table(columns, rows) {
    if (!rows || !rows.length) return muted('No rows.');
    var head = '<thead><tr>' + columns.map(function (column) {
      return '<th scope="col">' + esc(column.label) + '</th>';
    }).join('') + '</tr></thead>';
    var body = '<tbody>' + rows.map(function (row) {
      return '<tr>' + columns.map(function (column) {
        var value = column.get(row);
        return '<td>' + (column.render ? column.render(value, row) : esc(asText(value))) + '</td>';
      }).join('') + '</tr>';
    }).join('') + '</tbody>';
    return '<div class="table-wrap"><table>' + head + body + '</table></div>';
  }

  function setMessage(node, tone, text) {
    if (!node) return;
    node.textContent = text || '';
    if (tone) node.setAttribute('data-tone', tone);
    else node.removeAttribute('data-tone');
  }

  function errorText(error) {
    if (error && error.code) return error.code + ' · ' + (error.message || '');
    return (error && error.message) || 'request failed';
  }

  function skeletons(count) {
    var out = '';
    for (var i = 0; i < (count || 3); i += 1) out += '<div class="skeleton"></div>';
    return out;
  }

  function toneForStatus(status) {
    var value = String(status || '').toUpperCase();
    if (/COMPLETED|ANSWERED|PASSED|CLOSED|VERIFIED|ACTIVE|INDEXED/.test(value)) return 'ok';
    if (/PLANNED|WAITING|NEEDS_HUMAN|OCR_REQUIRED|PARTIAL|PENDING/.test(value)) return 'warn';
    if (/FAILED|ERROR|REJECTED|FORBIDDEN|CONFLICT|REOPENED/.test(value)) return 'danger';
    if (/INVESTIGATING|RUNNING|EXECUTING/.test(value)) return 'info';
    return 'muted';
  }

  function badge(label, value, tone) {
    return '<span class="badge" data-tone="' + escAttr(tone || toneForStatus(value)) + '">' +
      esc(label === null ? value : label + ' ' + value) + '</span>';
  }

  /* ================================================================ risk view */

  var selectedRisk = null;
  var lastEventId = '0';

  function renderRiskList(risks) {
    var list = $('risk-list');
    if (!risks.length) { list.innerHTML = muted('No risks.'); return; }
    list.innerHTML = risks.map(function (risk) {
      var active = selectedRisk === risk.riskId;
      return '<button class="risk' + (active ? ' active' : '') + '" data-id="' + escAttr(risk.riskId) + '"' +
        (active ? ' aria-current="true"' : '') + '>' +
        '<strong>' + esc(risk.title) + '</strong>' +
        '<small>' + esc(risk.status) + ' · v' + esc(risk.version) + '</small></button>';
    }).join('');
    Array.prototype.forEach.call(list.querySelectorAll('button'), function (button) {
      button.addEventListener('click', function () { selectRisk(button.dataset.id); });
    });
  }

  function loadRisks() {
    return request('/api/v1/read/risks').then(renderRiskList);
  }

  function renderApprovals(pending, taskIds) {
    var approvals = (pending.approvals || []).filter(function (item) {
      return taskIds.has(item.taskId);
    });
    var questions = (pending.questions || []).filter(function (item) {
      return taskIds.has(item.taskId);
    });
    var html = '';
    if (questions.length) {
      html += questions.map(function (question) {
        return '<div class="event"><strong>Question</strong> ' + esc(question.questionId) +
          '<br><small>' + esc(question.taskId) + '</small></div>';
      }).join('');
    }
    if (approvals.length) {
      html += approvals.map(function (approval) {
        return '<div class="approval"><strong>' + esc(approval.actionType) + '</strong>' +
          '<p class="muted small">' + esc(approval.level) + ' · version ' + esc(approval.version) + '</p>' +
          '<button type="button" data-action="' + escAttr(approval.actionId) + '" data-version="' +
          escAttr(approval.version) + '">Approve</button></div>';
      }).join('');
    }
    var node = $('pending-approval');
    node.innerHTML = html || muted('Nothing awaiting approval.');
    Array.prototype.forEach.call(node.querySelectorAll('button[data-action]'), function (button) {
      button.addEventListener('click', function () {
        approve(button.dataset.action, Number(button.dataset.version), button);
      });
    });
  }

  function renderRisk(risk, graph, timeline, pending, result) {
    $('risk-title').textContent = risk.title;
    $('risk-status').textContent = risk.status;

    var nodes = graph.nodes || [];
    $('investigation-graph').innerHTML = nodes.map(function (node) {
      return '<span class="node ' + escAttr(node.nodeType) + '">' + esc(node.nodeType) +
        '<br>' + esc(node.nodeId) + '</span>';
    }).join('') || muted('Graph has no nodes yet.');

    $('evidence').innerHTML = nodes.filter(function (node) {
      return node.nodeType === 'EVIDENCE';
    }).map(function (node) {
      return '<div class="event">' + esc(node.nodeId) + '<br><small>' + esc(node.payload) + '</small></div>';
    }).join('') || muted('No evidence.');

    $('conflicts').innerHTML = (graph.conflicts || []).map(function (node) {
      return '<div class="event">' + esc(node.nodeId) + '<br><small>' + esc(node.payload) + '</small></div>';
    }).join('') || muted('No open conflicts.');

    $('timeline').innerHTML = (timeline || []).map(function (item) {
      return '<div class="event"><strong>' + esc(item.action) + '</strong> · ' + esc(item.actor) +
        '<br><small>' + esc(item.createdAt) + '</small></div>';
    }).join('') || muted('No audit events.');

    var taskIds = new Set((graph.tasks || []).map(function (task) { return task.taskId; }));
    renderApprovals(pending || {}, taskIds);

    $('final-result').innerHTML = (result.results || []).map(function (item) {
      return '<div class="event"><strong>' + esc(item.status) + '</strong><br>' + esc(item.actionId) + '</div>';
    }).join('') || muted('Investigation remains open.');
  }

  function selectRisk(id) {
    selectedRisk = id;
    $('empty').hidden = true;
    $('detail').hidden = false;
    $('investigation-graph').innerHTML = skeletons(2);
    $('evidence').innerHTML = skeletons(2);
    $('conflicts').innerHTML = skeletons(2);
    $('timeline').innerHTML = skeletons(3);
    $('final-result').innerHTML = skeletons(1);

    return Promise.all([
      request('/api/v1/read/risks/' + encodeURIComponent(id)),
      request('/api/v1/read/risks/' + encodeURIComponent(id) + '/task-graph'),
      request('/api/v1/read/risks/' + encodeURIComponent(id) + '/audit-timeline'),
      request('/api/v1/read/pending'),
      request('/api/v1/read/risks/' + encodeURIComponent(id) + '/final-result')
    ]).then(function (payload) {
      renderRisk(payload[0], payload[1], payload[2], payload[3], payload[4]);
      return loadRisks();
    }).catch(function (error) {
      $('investigation-graph').innerHTML = '<p class="form-message" data-tone="error">' + esc(errorText(error)) + '</p>';
      $('evidence').innerHTML = muted('Read model unavailable.');
      $('conflicts').innerHTML = muted('Read model unavailable.');
      $('timeline').innerHTML = muted('Read model unavailable.');
      $('final-result').innerHTML = muted('Read model unavailable.');
    });
  }

  function approve(actionId, expectedVersion, button) {
    var original = button.textContent;
    button.disabled = true;
    button.setAttribute('aria-busy', 'true');
    button.textContent = 'Approving…';
    request('/api/v1/read/actions/' + encodeURIComponent(actionId) + '/approval', {
      method: 'POST',
      body: { expectedVersion: expectedVersion, idempotencyKey: crypto.randomUUID() }
    }).then(function () {
      return selectedRisk ? selectRisk(selectedRisk) : loadRisks();
    }).catch(function (error) {
      button.disabled = false;
      button.removeAttribute('aria-busy');
      button.textContent = original;
      $('pending-approval').insertAdjacentHTML('afterbegin',
        '<p class="form-message" data-tone="error">Approval failed: ' + esc(errorText(error)) + '</p>');
    });
  }

  /* ============================================================ platform view */

  var REGISTRY_KEY = 'sitepilot.platform.tasks';

  var INTENTS = {
    KNOWLEDGE_QA: { label: 'Knowledge QA', fields: [] },
    DATABASE_QA: { label: 'Database QA', fields: ['dataSourceId'] },
    HYBRID_QA: { label: 'Hybrid QA', fields: ['dataSourceId'] },
    DOCUMENT_REVIEW: { label: 'Document review', fields: ['templateId', 'templateVersion', 'documentId'] },
    REPORT_GENERATION: {
      label: 'Report generation',
      fields: ['templateId', 'templateVersion', 'outputFormat', 'narrative', 'bindings', 'citations']
    },
    OCR_EXTRACTION: {
      label: 'OCR extraction',
      fields: ['documentType', 'sourceObjectRef', 'inputText', 'extractionSchema']
    },
    RISK_INVESTIGATION: { label: 'Risk investigation', fields: ['riskTaskId'] },
    HUMAN_CLARIFICATION: { label: 'Human clarification', fields: [] }
  };

  var currentTask = null;

  function registryKey() {
    return REGISTRY_KEY + ':' + namespace.tenant + ':' + namespace.project;
  }

  function readRegistry() {
    try {
      var parsed = JSON.parse(stored(registryKey(), '[]'));
      return Array.isArray(parsed) ? parsed : [];
    } catch (ignored) { return []; }
  }

  function writeRegistry(entries) {
    try {
      window.localStorage.setItem(registryKey(), JSON.stringify(entries.slice(0, 50)));
    } catch (ignored) { /* storage unavailable: registry stays in-memory for this page */ }
  }

  function rememberTask(task) {
    var entries = readRegistry().filter(function (entry) { return entry.taskId !== task.taskId; });
    entries.unshift({
      taskId: task.taskId,
      intent: task.intent,
      status: task.status,
      requestedOutput: task.requestedOutput,
      savedAt: new Date().toISOString()
    });
    writeRegistry(entries);
    renderTaskList();
  }

  function renderTaskList() {
    var entries = readRegistry();
    var node = $('platform-task-list');
    if (!entries.length) {
      node.innerHTML = muted('No platform tasks stored for this tenant/project yet.');
      return;
    }
    node.innerHTML = entries.map(function (entry) {
      var active = currentTask && currentTask.taskId === entry.taskId;
      return '<button class="risk' + (active ? ' active' : '') + '" data-task="' + escAttr(entry.taskId) + '"' +
        (active ? ' aria-current="true"' : '') + '>' +
        '<strong>' + esc(entry.taskId) + '</strong>' +
        '<small>' + esc(entry.intent || 'intent unknown') + ' · ' + esc(entry.status || 'status unknown') +
        (entry.requestedOutput ? ' · ' + esc(entry.requestedOutput) : '') + '</small></button>';
    }).join('');
    Array.prototype.forEach.call(node.querySelectorAll('button[data-task]'), function (button) {
      button.addEventListener('click', function () { openTask(button.dataset.task); });
    });
  }

  function newTaskId() {
    return 'task-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 6);
  }

  function createTask(form) {
    var submit = $('create-submit');
    var message = $('create-message');
    var fields = form.elements;
    var attachments = fields.attachmentRefs.value.split(',').map(function (item) {
      return item.trim();
    }).filter(Boolean);
    var taskId = newTaskId();

    setMessage(message, null, 'Creating ' + taskId + '…');
    submit.disabled = true;
    submit.setAttribute('aria-busy', 'true');

    request('/api/v1/platform/tasks', {
      method: 'POST',
      body: {
        taskId: taskId,
        conversationId: fields.conversationId.value.trim() || null,
        userInput: fields.userInput.value.trim(),
        requestedOutput: fields.requestedOutput.value.trim(),
        attachmentRefs: attachments,
        idempotencyKey: taskId + ':create'
      }
    }).then(function (task) {
      setMessage(message, 'ok', 'Created ' + task.taskId + ' → intent ' + task.intent + '.');
      form.reset();
      updateTaskIdHint();
      rememberTask(task);
      renderTask(task);
      return openTask(task.taskId);
    }).catch(function (error) {
      setMessage(message, 'error', errorText(error));
    }).then(function () {
      submit.disabled = false;
      submit.removeAttribute('aria-busy');
    });
  }

  function updateTaskIdHint() {
    var hint = $('create-taskid-hint');
    if (hint) hint.textContent = 'Task id will be generated as task-<time>-<random>; the idempotency key is bound to it.';
  }

  function openTask(taskId) {
    $('platform-empty').hidden = true;
    $('platform-detail').hidden = false;
    $('pt-result').innerHTML = skeletons(4);
    $('pt-plan').innerHTML = skeletons(3);
    return request('/api/v1/platform/tasks/' + encodeURIComponent(taskId)).then(function (task) {
      renderTask(task);
      rememberTask(task);
      return task;
    }).catch(function (error) {
      $('platform-detail').hidden = true;
      $('platform-empty').hidden = false;
      $('platform-empty').innerHTML = '<p class="form-message" data-tone="error">' + esc(errorText(error)) + '</p>' +
        '<p class="muted small">Task ' + esc(taskId) + ' could not be read for this tenant/project.</p>';
      throw error;
    });
  }

  function renderTask(task) {
    currentTask = task;
    $('platform-empty').hidden = true;
    $('platform-detail').hidden = false;

    $('pt-task-id').textContent = task.taskId;
    $('pt-summary').textContent = task.userInput || '(no request text)';
    $('pt-intent').textContent = task.intent || 'intent unknown';
    $('pt-intent').setAttribute('data-tone', 'info');
    $('pt-status').textContent = task.status || 'status unknown';
    $('pt-status').setAttribute('data-tone', toneForStatus(task.status));
    $('pt-version').textContent = 'version ' + task.version;
    $('pt-version').setAttribute('data-tone', 'muted');

    $('pt-request').innerHTML = kvList([
      ['Requested output', task.requestedOutput],
      ['Conversation', task.conversationId || '—'],
      ['Attachments', Array.isArray(task.attachmentRefs) && task.attachmentRefs.length ? task.attachmentRefs.join(', ') : '—'],
      ['Version', task.version]
    ]);

    $('pt-plan').innerHTML = renderPlan(task);
    renderExecuteForm(task);
    $('pt-result').innerHTML = renderResult(task);
  }

  function renderPlan(task) {
    var plan = task.plan;
    if (!plan || typeof plan !== 'object' || !Object.keys(plan).length) {
      return '<p class="plan-note">The control plane returned no plan for this task.</p>';
    }
    var html = '<div class="badges" style="margin:0 0 12px">' +
      badge('orchestration', plan.orchestration || 'unspecified', 'info') +
      badge('intent', task.intent || 'unknown', 'info') +
      badge('status', task.status || 'unknown', toneForStatus(task.status)) + '</div>';

    var nodes = Array.isArray(plan.tasks) ? plan.tasks : (Array.isArray(plan.nodes) ? plan.nodes : null);
    if (nodes && nodes.length) {
      html += '<ul class="dag">' + nodes.map(function (node) {
        var id = node.taskId || node.nodeId || node.id || node.step || 'node';
        var role = node.role || node.agent || node.owner || '';
        var deps = node.dependsOn || node.dependencies || [];
        var tools = node.allowedTools || node.allowed_tools || [];
        return '<li><strong>' + esc(id) + '</strong>' + (role ? ' · ' + esc(role) : '') +
          (node.status ? ' · ' + esc(node.status) : '') +
          (deps.length ? '<div class="dep">after: ' + esc(deps.join(', ')) + '</div>' : '') +
          (tools.length ? '<div class="dep">tools: ' + esc(tools.join(', ')) + '</div>' : '') +
          (node.objective ? '<div class="dep">' + esc(node.objective) + '</div>' : '') + '</li>';
      }).join('') + '</ul>';
      if (Array.isArray(plan.levels) && plan.levels.length) {
        html += '<p class="plan-note">Parallel levels: ' + esc(plan.levels.map(function (level) {
          return '[' + level.join(', ') + ']';
        }).join(' → ')) + '</p>';
      }
      if (Array.isArray(plan.edges) && plan.edges.length) {
        html += '<ul class="dag">' + plan.edges.map(function (edge) {
          return '<li>' + esc(edge.from || edge.source) + ' → ' + esc(edge.to || edge.target) + '</li>';
        }).join('') + '</ul>';
      }
      return html;
    }

    html += '<p class="plan-note">The response contains no node-level DAG.</p>';
    html += kvList(Object.keys(plan).map(function (key) { return [key, plan[key]]; }));
    return html;
  }

  var FIELD_SPECS = {
    dataSourceId: { label: 'Data source id', placeholder: 'ds-a', required: true },
    templateId: { label: 'Template id', placeholder: 'review-template-v1', required: true },
    templateVersion: { label: 'Template version', placeholder: '1.0', required: true },
    documentId: { label: 'Document id', placeholder: 'doc-a (knowledge document)' },
    outputFormat: { label: 'Output format', type: 'select', options: ['DOCX', 'PDF'], value: 'DOCX' },
    narrative: { label: 'Narrative', type: 'textarea', rows: 3, placeholder: 'Report opening narrative' },
    bindings: { label: 'Bindings (JSON)', type: 'json', placeholder: '{"project":"A"}' },
    citations: { label: 'Citations (JSON array)', type: 'json', placeholder: '[{"documentId":"doc-a"}]' },
    documentType: { label: 'Document type', placeholder: 'CUSTOM_SCHEMA', value: 'CUSTOM_SCHEMA' },
    sourceObjectRef: { label: 'Source object ref', placeholder: 'platform:upload-1' },
    inputText: { label: 'Input text', type: 'textarea', rows: 4, placeholder: 'Contract text to extract fields from' },
    extractionSchema: { label: 'Extraction schema (JSON)', type: 'json', placeholder: '{"properties":{"party_a":{}}}' },
    riskTaskId: { label: 'Investigation task id', placeholder: 'task-risk-1', required: true }
  };

  function fieldMarkup(name) {
    var spec = FIELD_SPECS[name];
    var id = 'pt-field-' + name;
    var required = spec.required ? ' required' : '';
    var html = '<div class="field' + (spec.type === 'json' || spec.type === 'textarea' ? ' full' : '') + '">' +
      '<label for="' + escAttr(id) + '">' + esc(spec.label) + '</label>';
    if (spec.type === 'select') {
      html += '<select id="' + escAttr(id) + '" name="' + escAttr(name) + '">' +
        spec.options.map(function (option) {
          return '<option value="' + escAttr(option) + '">' + esc(option) + '</option>';
        }).join('') + '</select>';
    } else if (spec.type === 'textarea' || spec.type === 'json') {
      html += '<textarea id="' + escAttr(id) + '" name="' + escAttr(name) + '" rows="' +
        (spec.rows || 3) + '" placeholder="' + escAttr(spec.placeholder || '') + '"' + required +
        (spec.type === 'json' ? ' spellcheck="false"' : '') + '></textarea>';
    } else {
      html += '<input id="' + escAttr(id) + '" name="' + escAttr(name) + '" placeholder="' +
        escAttr(spec.placeholder || '') + '" value="' + escAttr(spec.value || '') + '"' +
        (spec.required ? ' aria-required="true"' : '') + required + ' autocomplete="off">';
    }
    return html + '</div>';
  }

  function renderExecuteForm(task) {
    var intent = INTENTS[task.intent];
    var fieldsNode = $('pt-execute-fields');
    var submit = $('pt-execute-submit');
    var note = $('pt-execute-note');

    if (!intent) {
      fieldsNode.innerHTML = '<p class="plan-note">Unknown intent "' + esc(task.intent) +
        '". Execution parameters cannot be derived; the control plane rejects unsupported intents.</p>';
      submit.disabled = true;
      note.textContent = '';
      return;
    }

    fieldsNode.innerHTML = intent.fields.length
      ? intent.fields.map(fieldMarkup).join('')
      : '<p class="plan-note">This intent needs no execution parameters.</p>';

    submit.disabled = false;
    submit.textContent = 'Execute ' + intent.label;

    if (intent.fields.length) {
      note.textContent = 'Execution reuses server-side templates, data sources and documents; identifiers above must already exist for this project.';
    } else if (task.intent === 'HUMAN_CLARIFICATION') {
      note.textContent = 'The router asked for clarification: execution returns a clarification prompt instead of a result.';
    } else {
      note.textContent = 'Knowledge answers are non-authoritative and always carry citations.';
    }

    var form = $('pt-execute-form');
    form.onsubmit = function (event) {
      event.preventDefault();
      executeTask(task, form);
    };
  }

  function collectExecutionBody(form) {
    var intent = INTENTS[currentTask.intent];
    var body = {};
    for (var i = 0; i < intent.fields.length; i += 1) {
      var name = intent.fields[i];
      var spec = FIELD_SPECS[name];
      var element = form.elements[name];
      if (!element) continue;
      var raw = element.value.trim();
      if (!raw) continue;
      if (spec.type === 'json') {
        try {
          body[name] = JSON.parse(raw);
        } catch (error) {
          throw new ApiError(0, 'CLIENT_JSON_INVALID', spec.label + ' is not valid JSON: ' + error.message);
        }
      } else {
        body[name] = raw;
      }
    }
    return body;
  }

  function executeTask(task, form) {
    var submit = $('pt-execute-submit');
    var message = $('pt-execute-message');
    var body;
    try {
      body = collectExecutionBody(form);
    } catch (error) {
      setMessage(message, 'error', errorText(error));
      return;
    }
    setMessage(message, null, 'Executing…');
    submit.disabled = true;
    submit.setAttribute('aria-busy', 'true');
    request('/api/v1/platform/tasks/' + encodeURIComponent(task.taskId) + '/execute', {
      method: 'POST',
      body: body
    }).then(function (updated) {
      setMessage(message, 'ok', 'Executed. Status ' + updated.status + ', version ' + updated.version + '.');
      renderTask(updated);
      rememberTask(updated);
    }).catch(function (error) {
      setMessage(message, 'error', errorText(error));
    }).then(function () {
      var current = $('pt-execute-submit');
      current.disabled = false;
      current.removeAttribute('aria-busy');
    });
  }

  /* ------------------------------------------------------------- result views */

  function section(title, inner) {
    return '<section class="result-section"><h4>' + esc(title) + '</h4>' + inner + '</section>';
  }

  function renderCitations(citations) {
    if (!Array.isArray(citations) || !citations.length) return muted('No citations returned.');
    return table([
      { label: 'Document', get: function (row) { return row.documentId; } },
      { label: 'Location', get: function (row) { return row.sourceLocation || row.chunkId; } },
      { label: 'Version', get: function (row) { return row.documentVersion; } },
      { label: 'Score', get: function (row) {
        return typeof row.score === 'number' ? row.score.toFixed(3) : row.score;
      } },
      { label: 'Quote', get: function (row) { return row.quote; } }
    ], citations);
  }

  function renderKnowledge(knowledge) {
    var note = knowledge.authoritative === false
      ? '<p class="plan-note">Knowledge answers are non-authoritative (authoritative=false); every statement must be read against its citations.</p>'
      : '';
    return section('Knowledge answer',
      kvList([['Status', knowledge.status], ['Authoritative', knowledge.authoritative]]) +
      '<div class="blockquote">' + esc(knowledge.answer || '(empty answer)') + '</div>' + note +
      '<h4 style="margin-top:14px">Citations</h4>' + renderCitations(knowledge.citations));
  }

  function renderDatabase(database) {
    var rows = Array.isArray(database.rows) ? database.rows : [];
    var columns = [];
    rows.slice(0, 20).forEach(function (row) {
      Object.keys(row).forEach(function (key) {
        if (columns.indexOf(key) < 0) columns.push(key);
      });
    });
    return section('Database answer',
      kvList([
        ['Data source', database.dataSourceId],
        ['Row count', database.rowCount],
        ['Tables', Array.isArray(database.tables) ? database.tables.join(', ') : database.tables],
        ['Authoritative', database.authoritative]
      ]) +
      (database.sql ? '<div class="blockquote"><code>' + esc(database.sql) + '</code></div>' : '') +
      (columns.length ? table(columns.map(function (key) {
        return { label: key, get: function (row) { return row[key]; } };
      }), rows) : muted('Query returned no rows.')));
  }

  function renderReview(review) {
    var findings = Array.isArray(review.findings) ? review.findings : [];
    var body = findings.length ? findings.map(function (finding) {
      return '<div class="finding">' +
        '<div class="sev" data-sev="' + escAttr(finding.severity) + '">' + esc(finding.severity || 'UNKNOWN') + '</div>' +
        '<div><strong>' + esc(finding.problem) + '</strong></div>' +
        (finding.standardRef ? '<div class="small">Standard: ' + esc(finding.standardRef) + '</div>' : '') +
        (finding.documentLocation ? '<div class="small">Location: ' + esc(asText(finding.documentLocation)) + '</div>' : '') +
        (finding.quote ? '<div class="blockquote">' + esc(finding.quote) + '</div>' : '') +
        (finding.suggestion ? '<div class="small">Suggestion: ' + esc(finding.suggestion) + '</div>' : '') +
        (typeof finding.confidence === 'number'
          ? '<div class="small muted">Confidence ' + finding.confidence.toFixed(2) + '</div>' : '') +
        '</div>';
    }).join('') : muted('No rule violations found.');
    return section('Review findings',
      kvList([['Job', review.jobId], ['Template', review.templateId + ' @ ' + review.templateVersion],
        ['Status', review.status], ['Findings', findings.length]]) + body);
  }

  function renderReport(report) {
    var jobId = report.jobId;
    var download = jobId
      ? '<p style="margin-top:12px"><button type="button" class="ghost" data-artifact="' + escAttr(jobId) +
        '">Download ' + esc(report.artifactName || report.outputFormat || 'artifact') + '</button></p>'
      : '';
    return section('Report',
      kvList([
        ['Job', jobId],
        ['Status', report.status],
        ['Format', report.outputFormat],
        ['Artifact', report.artifactName],
        ['Error code', report.errorCode]
      ]) + download + '<h4 style="margin-top:14px">Citations</h4>' + renderCitations(report.citations));
  }

  function renderOcr(ocr) {
    var payload = ocr.result || {};
    var fields = payload.fields || {};
    var rows = Object.keys(fields).map(function (name) {
      var field = fields[name] || {};
      return { name: name, value: field.value, confidence: field.confidence, page: field.page, bbox: field.bbox };
    });
    return section('OCR extraction',
      kvList([
        ['Job', ocr.jobId],
        ['Document type', ocr.documentType],
        ['Adapter', ocr.adapter],
        ['Status', ocr.status],
        ['Source object', ocr.sourceObjectRef],
        ['Source digest', payload.sourceTextDigest]
      ]) +
      (rows.length ? table([
        { label: 'Field', get: function (row) { return row.name; } },
        { label: 'Value', get: function (row) { return row.value; } },
        { label: 'Confidence', get: function (row) {
          return typeof row.confidence === 'number' ? row.confidence.toFixed(2) : row.confidence;
        } },
        { label: 'Page', get: function (row) { return row.page; } },
        { label: 'BBox', get: function (row) {
          return Array.isArray(row.bbox) ? row.bbox.join(', ') : row.bbox;
        } }
      ], rows) : muted('No fields extracted for the requested schema.')));
  }

  function renderRiskLink(investigation) {
    return section('Risk investigation',
      kvList([
        ['Investigation task', investigation.taskId],
        ['Risk reference', investigation.riskRef],
        ['Status', investigation.status],
        ['Graph revision', investigation.graphRevision]
      ]) +
      '<p><button type="button" class="ghost" data-open-risk="' + escAttr(investigation.riskRef) +
      '">Open in risk view</button></p>');
  }

  function renderGeneric(key, value) {
    return section(key, value && typeof value === 'object'
      ? kvList(Object.keys(value).map(function (field) { return [field, value[field]]; }))
      : '<div class="blockquote">' + esc(asText(value)) + '</div>');
  }

  function renderResult(task) {
    if (!task.result) {
      return '<p class="plan-note">Task is ' + esc(task.status) +
        ' and has no result yet. Executing the task populates citations, findings, report or OCR output.</p>';
    }
    var result = task.result;
    var order = ['knowledge', 'database', 'review', 'report', 'ocr', 'riskInvestigation'];
    var handled = order.concat(['synthesis', 'verification']);
    var html = '';
    order.forEach(function (key) {
      if (!result[key]) return;
      if (key === 'knowledge') html += renderKnowledge(result.knowledge);
      if (key === 'database') html += renderDatabase(result.database);
      if (key === 'review') html += renderReview(result.review);
      if (key === 'report') html += renderReport(result.report);
      if (key === 'ocr') html += renderOcr(result.ocr);
      if (key === 'riskInvestigation') html += renderRiskLink(result.riskInvestigation);
    });
    if (result.synthesis) {
      html += section('Synthesis', '<div class="blockquote">' + esc(asText(result.synthesis)) + '</div>');
    }
    if (result.verification) {
      html += section('Verification',
        badge('verification', result.verification.status, toneForStatus(result.verification.status)) +
        '<div class="small" style="margin-top:8px">Checked: ' +
        esc((result.verification.checked || []).join(', ')) + '</div>');
    }
    Object.keys(result).forEach(function (key) {
      if (handled.indexOf(key) < 0) html += renderGeneric(key, result[key]);
    });
    return html || muted('Result object is empty.');
  }

  function downloadArtifact(jobId, button) {
    var original = button.textContent;
    button.disabled = true;
    button.textContent = 'Preparing…';
    fetch('/api/v1/reports/jobs/' + encodeURIComponent(jobId) + '/artifact', { headers: requestHeaders() })
      .then(function (response) {
        if (!response.ok) throw new ApiError(response.status, 'ARTIFACT_FAILED', 'artifact download failed');
        return response.blob();
      })
      .then(function (blob) {
        var link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        link.download = jobId;
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(function () { URL.revokeObjectURL(link.href); }, 4000);
      })
      .catch(function (error) {
        $('pt-result').insertAdjacentHTML('afterbegin',
          '<p class="form-message" data-tone="error">' + esc(errorText(error)) + '</p>');
      })
      .then(function () {
        button.disabled = false;
        button.textContent = original;
      });
  }

  /* ------------------------------------------------------------ result actions */

  document.addEventListener('click', function (event) {
    var artifact = event.target.closest('[data-artifact]');
    if (artifact) { downloadArtifact(artifact.dataset.artifact, artifact); return; }
    var risk = event.target.closest('[data-open-risk]');
    if (risk) {
      activateView('risk');
      selectRisk(risk.dataset.openRisk);
    }
  });

  /* ==================================================================== stream */

  function connect() {
    fetch('/api/v1/read/stream?tenant=' + encodeURIComponent(namespace.tenant) +
      '&project=' + encodeURIComponent(namespace.project), {
      headers: Object.assign({}, requestHeaders(), { 'Last-Event-ID': lastEventId })
    }).then(function (response) {
      if (!response.ok) throw new Error('stream rejected');
      $('connection').textContent = 'live';
      var reader = response.body.getReader();
      var decoder = new TextDecoder();
      var buffer = '';
      function pump() {
        return reader.read().then(function (chunk) {
          if (chunk.done) return null;
          buffer += decoder.decode(chunk.value, { stream: true });
          var blocks = buffer.split('\n\n');
          buffer = blocks.pop();
          return blocks.reduce(function (chain, block) {
            return chain.then(function () {
              var id = block.match(/^id:(.+)$/m);
              var data = block.match(/^data:(.+)$/m);
              if (id) lastEventId = id[1].trim();
              if (!data) return null;
              return refreshVisibleView();
            });
          }, Promise.resolve()).then(pump);
        });
      }
      return pump();
    }).catch(function () {
      $('connection').textContent = 'reconnecting';
      setTimeout(connect, 2000);
    });
  }

  function refreshVisibleView() {
    var platformVisible = !$('view-platform').hidden;
    if (platformVisible) {
      if (currentTask) {
        return request('/api/v1/platform/tasks/' + encodeURIComponent(currentTask.taskId))
          .then(renderTask).catch(function () { return null; });
      }
      return Promise.resolve();
    }
    return loadRisks().then(function () {
      return selectedRisk ? selectRisk(selectedRisk) : null;
    }).catch(function () { return null; });
  }

  /* ====================================================================== tabs */

  function activateView(view) {
    var risk = view === 'risk';
    $('tab-risk').setAttribute('aria-selected', String(risk));
    $('tab-platform').setAttribute('aria-selected', String(!risk));
    $('view-risk').hidden = !risk;
    $('view-platform').hidden = risk;
    if (!risk) renderTaskList();
    if (window.location.hash !== '#' + view) {
      history.replaceState(null, '', '#' + view);
    }
  }

  Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (tab) {
    tab.addEventListener('click', function () { activateView(tab.dataset.view); });
    tab.addEventListener('keydown', function (event) {
      if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
      event.preventDefault();
      var next = tab.dataset.view === 'risk' ? 'platform' : 'risk';
      activateView(next);
      $('tab-' + next).focus();
    });
  });

  /* ====================================================================== boot */

  $('namespace').textContent = namespace.tenant + ' / ' + namespace.project + ' / ' + namespace.actor;
  $('refresh').addEventListener('click', function () { if (selectedRisk) selectRisk(selectedRisk); });
  $('pt-refresh').addEventListener('click', function () {
    if (currentTask) openTask(currentTask.taskId);
  });

  $('create-task-form').addEventListener('submit', function (event) {
    event.preventDefault();
    createTask(event.target);
  });

  $('open-task-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var id = $('open-task-id').value.trim();
    if (id) openTask(id).catch(function () { /* surfaced in #platform-empty */ });
  });

  updateTaskIdHint();
  renderTaskList();
  if (window.location.hash === '#platform') activateView('platform');

  loadRisks().catch(function (error) {
    $('risk-list').innerHTML = '<p class="form-message" data-tone="error">' + esc(errorText(error)) + '</p>';
  });
  connect();
})();
