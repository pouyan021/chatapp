import { ulid } from './vendor/ulid.js';

const $ = id => document.getElementById(id);
const identities = JSON.parse($('identities').textContent);
const deviceId = `tab-${crypto.randomUUID()}`;
let identity = 'alice', token = null, client = null, connecting = false, generation = 0;
let selected = null, lastSend = null;
const chats = new Map(), histories = new Map(), pending = new Map(), requests = new Map();
$('device').textContent = `Device: ${deviceId}`;
const userId = () => identities[identity];
const history = id => {
  if (!histories.has(id)) histories.set(id, { messages: new Map(), cursor: null, loaded: false });
  return histories.get(id);
};
function log(kind, data) {
  const entry = `${new Date().toLocaleTimeString()} ${kind}\n${typeof data === 'string' ? data : JSON.stringify(data, null, 2)}\n`;
  $('log').textContent = (entry + $('log').textContent).slice(0, 60000);
}
function notice(message = '') { $('notice').textContent = message; }
function action(fn) { return async event => { event?.preventDefault(); notice(); try { await fn(); } catch (e) { notice(e.message); log('ERROR', e.message); } }; }
function update() {
  const connected = Boolean(client?.connected);
  $('identity').disabled = connected || connecting;
  $('connect').disabled = connected || connecting;
  $('disconnect').disabled = !connected && !connecting;
  $('send').disabled = !connected || !selected;
  $('message').disabled = !connected || !selected;
  $('retryLast').disabled = !connected || !lastSend || lastSend.chatId !== selected;
  $('older').disabled = !selected || !history(selected).cursor;
  $('identityInfo').textContent = `User: ${userId()}`;
  $('status').className = connected ? 'connected' : '';
}
async function credentials() {
  if (token && Date.parse(token.expiresAt) > Date.now() + 30000) return token;
  const who = identity;
  const response = await fetch('/dev/token', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ identity: who }) });
  if (!response.ok) throw new Error('Could not obtain a development token. Is the dev profile active?');
  const result = await response.json();
  if (who !== identity) throw new Error('Identity changed; try again.');
  token = result;
  return token;
}
async function api(path, method = 'GET', body) {
  const auth = await credentials();
  log(`REST → ${method} ${path}`, body ?? '');
  const response = await fetch(path, { method, headers: { Authorization: `Bearer ${auth.token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await response.text();
  let result; try { result = text ? JSON.parse(text) : null; } catch { result = text; }
  log(`REST ← ${response.status} ${path}`, result);
  if (!response.ok) throw new Error(result?.message || `HTTP ${response.status}`);
  return result;
}
function renderChats() {
  $('chats').replaceChildren();
  for (const chat of chats.values()) {
    const button = document.createElement('button');
    button.textContent = chat.chatName;
    button.className = chat.chatId === selected ? 'selected' : '';
    button.onclick = action(() => selectChat(chat.chatId));
    $('chats').append(button);
  }
  if (!chats.size) $('chats').textContent = 'No conversations yet.';
}
async function refresh() {
  const epoch = generation;
  const results = await api('/api/chats');
  if (epoch !== generation) return;
  chats.clear(); results.forEach(chat => chats.set(chat.chatId, chat)); renderChats();
}
function renderMessages() {
  const area = $('messages'); area.replaceChildren();
  if (!selected) { area.textContent = 'Select or create a conversation.'; update(); return; }
  const messages = [...history(selected).messages.values()].sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.messageId.localeCompare(b.messageId));
  const add = (message, state, retry) => {
    const bubble = document.createElement('article');
    bubble.className = `bubble${message.senderId === userId() ? ' own' : ''}${retry ? ' pending' : ''}`;
    const author = document.createElement('small');
    author.textContent = `${Object.keys(identities).find(name => identities[name] === message.senderId) || message.senderId} · ${new Date(message.createdAt).toLocaleTimeString()}`;
    const text = document.createElement('p'); text.textContent = message.text ?? '[Media message — inspect through the REST API]';
    const status = document.createElement('small'); status.textContent = state;
    bubble.append(author, text, status);
    if (retry) {
      const button = document.createElement('button'); button.className = 'link'; button.textContent = ' Retry';
      button.disabled = !client?.connected || retry.state === 'Pending'; button.onclick = action(() => publish(retry.command)); bubble.append(button);
    }
    area.append(bubble);
  };
  messages.forEach(message => add(message, message.source || 'Saved'));
  for (const entry of pending.values()) {
    if (entry.command.chatId === selected) add({ ...entry.command, senderId: userId(), createdAt: entry.createdAt }, entry.state, entry);
  }
  if (!area.childNodes.length) { const empty = document.createElement('p'); empty.className = 'empty'; empty.textContent = 'No messages yet. Say hello.'; area.append(empty); }
  area.scrollTop = area.scrollHeight; update();
}
async function loadHistory(chatId, older = false) {
  const epoch = generation, state = history(chatId);
  // Snapshot the boundary before fetching; live events can arrive while history is loading.
  const boundary = !older && Object.hasOwn(state, 'recoveryBoundary') ? state.recoveryBoundary : state.loaded && !older ? [...state.messages.values()].sort((a,b) => b.createdAt.localeCompare(a.createdAt) || b.messageId.localeCompare(a.messageId))[0]?.messageId : null;
  let cursor = older ? state.cursor : null;
  do {
    const page = await api(`/api/chats/${chatId}/messages?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);
    if (epoch !== generation) return;
    const reached = boundary && page.messages.some(message => message.messageId === boundary);
    page.messages.forEach(message => state.messages.set(message.messageId, { ...message, source: state.messages.get(message.messageId)?.source || 'History · saved' }));
    if (!boundary || older) state.cursor = page.nextCursor;
    state.loaded = true; cursor = page.nextCursor;
    if (selected === chatId) renderMessages();
    if (older || !boundary || reached) break;
  } while (cursor);
  if (!older) delete state.recoveryBoundary;
}
async function selectChat(id) {
  selected = id;
  $('chatTitle').textContent = chats.get(id)?.chatName || 'Conversation'; $('chatId').textContent = id;
  renderChats(); renderMessages(); await loadHistory(id);
}
async function createChat() {
  const name = $('chatName').value.trim(); if (!name) throw new Error('Give the conversation a name.');
  const epoch = generation;
  const chat = await api('/api/chats', 'POST', { name, type: 'DIRECT', members: [identities[identity === 'alice' ? 'bob' : 'alice']] });
  if (epoch !== generation) return;
  chats.set(chat.chatId, { chatId: chat.chatId, chatName: chat.name }); await selectChat(chat.chatId);
}
function uncertain() {
  for (const request of requests.values()) clearTimeout(request.timer);
  requests.clear();
  for (const entry of pending.values()) if (entry.state === 'Pending') entry.state = 'Uncertain — reconnect and retry the same message';
  renderMessages();
}
async function connect() {
  if (client) await client.deactivate();
  connecting = true; $('status').textContent = 'Connecting…'; update(); token = null;
  const epoch = generation;
  try {
    const auth = await credentials();
    if (epoch !== generation) return;
    for (const state of histories.values()) {
      if (state.loaded && !Object.hasOwn(state, 'recoveryBoundary')) {
        state.recoveryBoundary = [...state.messages.values()].sort((a,b) => b.createdAt.localeCompare(a.createdAt) || b.messageId.localeCompare(a.messageId))[0]?.messageId ?? null;
      }
    }
    const socket = new StompJs.Client({
      brokerURL: `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/chat`,
      connectHeaders: { Authorization: `Bearer ${auth.token}`, 'device-id': deviceId },
      heartbeatIncoming: 10000, heartbeatOutgoing: 10000, reconnectDelay: 0, connectionTimeout: 10000,
      onConnect: () => {
        if (client !== socket) return;
        connecting = false; $('status').textContent = `Connected as ${identity} · token expires ${new Date(auth.expiresAt).toLocaleTimeString()}`;
        socket.subscribe('/user/queue/results', frame => {
          const result = JSON.parse(frame.body), requestId = frame.headers['request-id']; log('STOMP ← result', { requestId, ...result });
          const request = requests.get(requestId);
          if (!request) return;
          clearTimeout(request.timer); requests.delete(requestId);
          const entry = pending.get(request.command.clientMessageId);
          if (result.acceptance) {
            const accepted = result.acceptance, state = history(accepted.chatId);
            state.messages.set(accepted.messageId, { ...request.command, ...accepted, senderId: userId(), source: state.messages.get(accepted.messageId)?.source || 'Accepted · saved' });
            pending.delete(request.command.clientMessageId);
          } else if (entry) { entry.state = `${result.error.code}: ${result.error.message}`; }
          renderMessages();
        });
        socket.subscribe('/user/queue/connection', frame => log('STOMP ← connection', JSON.parse(frame.body)));
        socket.subscribe('/user/queue/messages', frame => {
          const message = JSON.parse(frame.body); log('STOMP ← live message', message);
          history(message.chatId).messages.set(message.messageId, { ...message, source: 'Live · saved' });
          if (selected === message.chatId) renderMessages();
        });
        const requestId = crypto.randomUUID();
        socket.publish({ destination: '/app/v1/connection.info', headers: { 'request-id': requestId }, body: '' });
        log('STOMP → connection.info', { requestId }); update(); renderMessages();
        action(async () => { await refresh(); if (selected) await loadHistory(selected); })();
      },
      onStompError: () => { notice('Socket protocol or authentication error. Reconnect to obtain a fresh token.'); log('STOMP ERROR', 'Connection rejected; credentials omitted.'); },
      onWebSocketError: () => notice('WebSocket connection failed. Check the server and reconnect.'),
      onWebSocketClose: event => {
        if (client !== socket) return;
        connecting = false; $('status').textContent = `Disconnected (${event.code}) · reconnect to catch up`; log('SOCKET closed', { code: event.code }); uncertain(); update();
      }
    });
    client = socket; socket.activate();
  } catch (error) { connecting = false; $('status').textContent = 'Disconnected'; update(); throw error; }
}
async function disconnect() {
  generation++; connecting = false;
  if (client) await client.deactivate();
  client = null; $('status').textContent = 'Disconnected · reconnect to catch up'; uncertain(); update();
}
function publish(command) {
  if (!client?.connected) throw new Error('Connect before sending.');
  if ([...requests.values()].some(request => request.command.clientMessageId === command.clientMessageId && !request.timedOut)) throw new Error('This message is still awaiting a reply.');
  const requestId = crypto.randomUUID();
  pending.set(command.clientMessageId, { command, state: 'Pending', createdAt: new Date().toISOString() });
  const timer = setTimeout(() => {
    const entry = pending.get(command.clientMessageId);
    if (entry) entry.state = 'Uncertain — no reply; retry uses the same ID';
    // Retain correlation so a late acceptance can still settle the message.
    const request = requests.get(requestId); if (request) request.timedOut = true;
    renderMessages();
  }, 10000);
  // A timed-out attempt is replaced on explicit retry, with a fresh request-id.
  requests.set(requestId, { command, timer }); lastSend = command;
  try {
    client.publish({ destination: '/app/v1/message.send', headers: { 'request-id': requestId, 'content-type': 'application/json' }, body: JSON.stringify(command) });
    log('STOMP → message.send', { requestId, ...command });
  } catch (error) { clearTimeout(timer); requests.delete(requestId); pending.get(command.clientMessageId).state = 'Uncertain — send failed'; throw error; }
  finally { renderMessages(); }
}
$('identity').onchange = action(async () => {
  await disconnect(); identity = $('identity').value; token = null; selected = null; lastSend = null; chats.clear(); histories.clear(); pending.clear();
  $('chatTitle').textContent = 'Choose a conversation'; $('chatId').textContent = ''; renderChats(); renderMessages(); await refresh();
});
$('connect').onclick = action(connect);
$('disconnect').onclick = action(disconnect);
$('refresh').onclick = action(refresh);
$('create').onclick = action(createChat);
$('older').onclick = action(() => loadHistory(selected, true));
$('copyToken').onclick = action(async () => { await navigator.clipboard.writeText((await credentials()).token); notice('Token copied. Paste it into Swagger’s Authorize dialog.'); });
$('composer').onsubmit = action(() => {
  const text = $('message').value;
  if (!text.trim()) throw new Error('Write a message first.');
  if (new TextEncoder().encode(text).length > 8192) throw new Error('Text must fit within 8,192 UTF-8 bytes.');
  publish({ clientMessageId: ulid(), chatId: selected, text, media: null }); $('message').value = '';
});
$('retryLast').onclick = action(() => publish(lastSend));
$('clearLog').onclick = () => { $('log').textContent = ''; };
update(); action(refresh)();
