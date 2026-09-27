const state = {
  terms: [],
  conversations: [],
  activeConversationId: null,
  activeStream: null
};
const $ = selector => document.querySelector(selector);

function createId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function toast(message, error = false) {
  const item = document.createElement('div');
  item.textContent = message;
  if (error) item.classList.add('error');
  $('#toast').appendChild(item);
  setTimeout(() => item.remove(), 4000);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));
}

function renderMarkdown(text) {
  if (window.marked && window.DOMPurify) {
    return DOMPurify.sanitize(marked.parse(text));
  }
  return escapeHtml(text);
}

function setStreamStatus(text, visible = true) {
  $('#streamStatus').classList.toggle('hidden', !visible);
  $('#streamStatusText').innerHTML = `<span class="status-spinner"></span>${escapeHtml(text)}`;
}

function setBusy(busy) {
  $('#sendMessage').disabled = busy;
  $('#chatInput').disabled = busy;
  if (!busy) setStreamStatus('', false);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(body?.message || `请求失败 (${response.status})`);
  return body;
}

// ---------- conversations ----------

function newConversation() {
  const conversation = { id: createId(), title: '新对话', messages: [], updatedAt: new Date().toISOString() };
  state.conversations.unshift(conversation);
  state.activeConversationId = conversation.id;
  saveConversations();
  renderConversations();
  renderChatMessages();
}

function saveConversations() {
  localStorage.setItem('dsh.glossary.conversations', JSON.stringify(state.conversations.slice(0, 50)));
}

function loadConversations() {
  try { state.conversations = JSON.parse(localStorage.getItem('dsh.glossary.conversations') || '[]'); }
  catch { state.conversations = []; }
}

function renderConversations() {
  const list = $('#conversationList');
  list.innerHTML = '';
  state.conversations.forEach(conversation => {
    const button = document.createElement('button');
    button.className = 'conv-item' + (conversation.id === state.activeConversationId ? ' active' : '');
    button.textContent = conversation.title;
    button.onclick = () => {
      state.activeConversationId = conversation.id;
      renderConversations();
      renderChatMessages();
    };
    list.appendChild(button);
  });
}

function renderChatMessages() {
  const container = $('#chatMessages');
  container.innerHTML = '';
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (!conversation) return;
  conversation.messages.forEach(message => {
    const div = document.createElement('div');
    if (message.role === 'user') {
      div.className = 'msg user';
      div.textContent = message.content;
    } else {
      div.className = 'msg assistant';
      div.innerHTML = assistantHtml(message);
    }
    container.appendChild(div);
  });
  container.scrollTop = container.scrollHeight;
}

function assistantHtml(message) {
  let html = '';
  if (message.reasoning) html += `<div class="reasoning">${escapeHtml(message.reasoning.slice(-600))}</div>`;
  if (message.steps?.length) {
    html += '<div>' + message.steps.map((step, index) => `
      <details class="step-item">
        <summary>
          <span class="step-name">${escapeHtml(step.toolName || '工具 ' + (index + 1))}</span>
          <span class="step-state ${step.status === 'result' ? 'ok' : 'run'}">${step.status === 'result' ? '✓ 完成' : '… 执行中'}</span>
        </summary>
        <div class="step-detail">
          ${step.argsJson ? `<pre>${escapeHtml(step.argsJson)}</pre>` : ''}
          ${step.resultJson ? `<pre>${escapeHtml(String(step.resultJson).slice(0, 1200))}</pre>` : ''}
        </div>
      </details>`).join('') + '</div>';
  }
  html += renderMarkdown(message.content || (message.streaming ? '…' : '（本轮无内容返回）'));
  return html;
}

// ---------- glossary panels ----------

async function loadTerms(keyword) {
  let data;
  if (keyword) {
    data = await request('/api/terms/lookup', { method: 'POST', body: JSON.stringify({ keyword }) });
  } else {
    data = { terms: await request('/api/terms'), hitCount: (await request('/api/terms')).length };
  }
  const terms = data.terms || [];
  $('#termsCard').innerHTML =
    `<div class="small muted">${keyword ? `命中 ${data.hitCount} 条` : `共 ${terms.length} 条词条`}</div>` +
    terms.map(t => `
      <div class="branch-row">
        <b>${escapeHtml(t.en)}</b> ${t.zh ? `<span class="badge info">${escapeHtml(t.zh)}</span>` : ''}
        <span class="badge ok">${escapeHtml(t.tag || '未分类')}</span>
        <div class="muted small">${escapeHtml(t.definition)}</div>
        ${t.aliases?.length ? `<div class="small muted">别名：${escapeHtml(t.aliases.join('、'))}</div>` : ''}
      </div>`).join('') || '<div class="muted small">术语库为空，点"＋ 收录术语"添加</div>';
}

async function loadCard() {
  const keyword = $('#cardInput').value.trim();
  if (!keyword) return toast('输入术语关键词', true);
  const data = await request('/api/terms/bilingual-card', { method: 'POST', body: JSON.stringify({ keyword }) });
  $('#cardResult').innerHTML =
    `<div class="small muted"><b>${escapeHtml(data.en)}</b> ⇄ <b>${escapeHtml(data.zh)}</b> · ${data.cardCount} 面</div>` +
    (data.cards || []).map(c => `
      <div class="branch-row"><span class="badge info">${escapeHtml(c.side)}</span>
        <div>${renderMarkdown(c.content)}</div>
      </div>`).join('');
}

async function loadConflicts() {
  const data = await request('/api/terms/conflicts', { method: 'POST' });
  $('#conflictsCard').innerHTML =
    `<div class="metric-grid">
      <div class="metric"><b>${data.termCount}</b><span>词条</span></div>
      <div class="metric"><b>${data.conflictCount}</b><span>冲突</span></div>
      <div class="metric"><b>${data.pendingReviews}</b><span>待评审</span></div>
    </div>` +
    (data.conflicts || []).map(c => `
      <div class="branch-row"><span class="badge warn">${escapeHtml(c.type)}</span>
        <b>${escapeHtml(String(c.key))}</b>
        <div class="muted small">${escapeHtml(c.message)}</div>
      </div>`).join('') || '<div class="muted small" style="margin-top:8px">✓ 无冲突</div>';
}

async function loadStats() {
  const data = await request('/api/terms/stats');
  const max = Math.max(1, ...Object.values(data.byTag || {}));
  $('#statsCard').innerHTML =
    `<div class="small muted">共 ${data.termCount} 条词条 · ${data.reviewCount} 条评审意见</div>
    <h4 style="margin:8px 0 4px;font-size:12.5px">按分类</h4>` +
    Object.entries(data.byTag || {}).map(([tag, count]) => `
      <div class="bar-row"><span class="label">${escapeHtml(tag)}</span>
        <span class="bar" style="width:${Math.max(4, count / max * 90)}px"></span>
        <span class="num">${count}</span></div>`).join('');
}

async function refreshAll() {
  await Promise.all([loadTerms(), loadConflicts(), loadStats()]
    .map(p => p.catch(e => toast(e.message, true))));
}

// ---------- chat ----------

async function sendMessage() {
  const input = $('#chatInput');
  const text = input.value.trim();
  if (!text) return;
  if (!state.activeConversationId) newConversation();
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (conversation.title === '新对话') conversation.title = text.slice(0, 30);
  conversation.messages.push({ role: 'user', content: text });
  const pending = { id: createId(), role: 'assistant', content: '', reasoning: '', steps: [], streaming: true };
  conversation.messages.push(pending);
  input.value = '';
  saveConversations();
  renderConversations();
  renderChatMessages();
  setBusy(true);
  setStreamStatus('AI 正在思考');
  try {
    await streamAssistant(text, pending);
    setStreamStatus('已完成');
    setTimeout(() => setBusy(false), 450);
  } catch (error) {
    pending.content = error.name === 'AbortError' ? '**已停止生成。**' : error.message;
    setStreamStatus('执行失败');
    setTimeout(() => setBusy(false), 900);
  }
  saveConversations();
  renderChatMessages();
}

async function streamAssistant(message, pending) {
  const controller = new AbortController();
  state.activeStream = controller;
  const response = await fetch('/api/assistant/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId: state.activeConversationId, message }),
    signal: controller.signal
  });
  if (!response.ok || !response.body) throw new Error(`请求失败 (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data = '';
  let finished = false;
  let renderQueued = false;
  const render = () => {
    if (renderQueued) return;
    renderQueued = true;
    setTimeout(() => { renderQueued = false; renderChatMessages(); }, 60);
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();
    lines.forEach(line => {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += `${line.slice(5).trim()}\n`;
      else if (!line && data) {
        try {
          const payload = JSON.parse(data);
          if (event === 'chunk') pending.content += payload.content || '';
          if (event === 'chunk' && pending.content) setStreamStatus('正在回复');
          if (event === 'reasoning') pending.reasoning += payload.content || '';
          if (event === 'step_break') {
            const exists = payload.callId && pending.steps.some(item => item.callId === payload.callId);
            if (!exists) pending.steps.push({ ...payload, status: 'running' });
            setStreamStatus(`正在执行 ${payload.toolName || '工具'}`);
          }
          if (event === 'tool_result') {
            const step = [...pending.steps].reverse().find(item => item.callId === payload.callId)
              || [...pending.steps].reverse().find(item => item.status === 'running');
            if (step) Object.assign(step, payload, { status: 'result' });
            else pending.steps.push({ ...payload, status: 'result' });
            setStreamStatus('正在整理结果');
          }
          if (event === 'finish' || event === 'done') {
            pending.steps.forEach(item => { if (item.status === 'running') item.status = 'result'; });
            pending.streaming = false;
            finished = true;
          }
          if (event === 'error') pending.content += `\n\n**错误：** ${payload.message || '未知错误'}`;
          render();
        } catch { }
        event = 'message';
        data = '';
      }
    });
    if (finished) break;
  }
  if (!finished) pending.streaming = false;
  state.activeStream = null;
  renderChatMessages();
}

// ---------- events ----------

$('#sendMessage').onclick = sendMessage;
$('#chatInput').addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    sendMessage();
  }
});
$('#stopStream').onclick = () => state.activeStream?.abort();
$('#newConversation').onclick = () => { newConversation(); };
$('#addTerm').onclick = () => $('#termModal').classList.remove('hidden');
$('#cancelModal').onclick = () => $('#termModal').classList.add('hidden');
$('#termForm').onsubmit = async event => {
  event.preventDefault();
  const form = new FormData(event.target);
  const aliases = String(form.get('aliases') || '').split(/[,，]/).map(s => s.trim()).filter(Boolean);
  try {
    const result = await request('/api/terms', {
      method: 'POST',
      body: JSON.stringify({
        en: form.get('en'), zh: form.get('zh'), aliases,
        tag: form.get('tag'), definition: form.get('definition'),
        example: form.get('example'), source: 'ui'
      })
    });
    $('#termModal').classList.add('hidden');
    event.target.reset();
    toast(result.duplicated ? '已存在，转评审意见：' + result.reviewId : '已收录：' + result.en);
    await refreshAll();
  } catch (error) {
    toast(error.message, true);
  }
};
$('#mineDoc').onclick = () => $('#mineModal').classList.remove('hidden');
$('#cancelMineModal').onclick = () => $('#mineModal').classList.add('hidden');
$('#mineForm').onsubmit = async event => {
  event.preventDefault();
  const text = new FormData(event.target).get('text');
  try {
    const data = await request('/api/terms/mine', { method: 'POST', body: JSON.stringify({ text }) });
    $('#mineModal').classList.add('hidden');
    event.target.reset();
    $('#mineResult')?.remove();
    const card = document.createElement('div');
    card.id = 'mineResult';
    card.className = 'panel';
    card.innerHTML = `<div class="panel-title"><span>挖到 ${data.candidateCount} 个候选（新词 ${data.newCount}）</span></div>` +
      (data.candidates || []).map(c => `
        <div class="branch-row">
          <b>${escapeHtml(c.en)}</b> ${c.known ? '<span class="badge ok">已收录</span>' : '<span class="badge warn">新词</span>'}
          <span class="small muted">×${c.freq}</span>
          ${c.zhHint ? `<div class="muted small">中文提示：${escapeHtml(c.zhHint)}</div>` : ''}
        </div>`).join('');
    document.querySelector('[data-tab="terms"]').appendChild(card);
    document.querySelector('[data-resource-tab="terms"]').click();
    toast(`挖掘完成：${data.candidateCount} 候选 / ${data.newCount} 新词`);
  } catch (error) {
    toast(error.message, true);
  }
};
$('#lookupBtn').onclick = async () => {
  const keyword = $('#lookupInput').value.trim();
  await loadTerms(keyword || undefined).catch(e => toast(e.message, true));
};
$('#lookupInput').addEventListener('keydown', e => { if (e.key === 'Enter') $('#lookupBtn').click(); });
$('#cardBtn').onclick = () => loadCard().catch(e => toast(e.message, true));
$('#cardInput').addEventListener('keydown', e => { if (e.key === 'Enter') $('#cardBtn').click(); });
$('#refreshResource').onclick = async () => {
  await refreshAll();
  toast('已刷新');
};
document.querySelectorAll('[data-resource-tab]').forEach(button => {
  button.onclick = () => {
    document.querySelectorAll('[data-resource-tab]').forEach(item => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-tab]').forEach(panel => panel.classList.toggle('hidden', panel.dataset.tab !== button.dataset.resourceTab));
  };
});
$('#toggleTheme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('dsh.glossary.theme', next);
  $('#toggleTheme').textContent = next === 'dark' ? '☀️' : '🌙';
};
document.documentElement.dataset.theme = localStorage.getItem('dsh.glossary.theme') || 'light';
$('#toggleTheme').textContent = document.documentElement.dataset.theme === 'dark' ? '☀️' : '🌙';

// ---------- init ----------

(async function init() {
  loadConversations();
  if (!state.conversations.length) newConversation();
  else { state.activeConversationId = state.conversations[0].id; }
  renderConversations();
  renderChatMessages();
  try {
    await refreshAll();
  } catch (error) {
    toast(error.message, true);
  }
})();
