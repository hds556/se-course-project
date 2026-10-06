const $ = (selector) => document.querySelector(selector);
const money = (cents) => `¥${(cents / 100).toFixed(2)}`;
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (character) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character]);

function message(text, kind = 'error') {
  const element = $('#message');
  element.textContent = text || '';
  element.dataset.kind = text ? kind : '';
  element.setAttribute('role', kind === 'error' ? 'alert' : 'status');
}

async function request(url, options = {}) {
  const response = await fetch(url, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '操作失败，请稍后重试');
  return data;
}

const productStatus = {
  active: '正在接受意向',
  resumed: '已恢复接收',
  frozen: '暂缓接收意向'
};

const intentStage = {
  queued: '排队中',
  trading: '交易中'
};

const intentOutcome = {
  success: '成功',
  unsold: '未成交',
  cancelled: '撤销',
  requeued: '重新排队',
  voided: '作废'
};

function formatDate(value) {
  if (!value) return '时间待更新';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '时间待更新' : date.toLocaleString('zh-CN', { hour12: false });
}

function renderCurrent(product, tradingIntent) {
  const heading = '<div class="admin-section-head"><div><p class="admin-kicker">正在照看的好物</p><h2>当前商品</h2></div></div>';
  if (!product) {
    return `${heading}<div class="empty-state"><span class="empty-state-icon" aria-hidden="true">✳</span><h3>现在没有在售商品</h3><p>准备好下一件好物后，可在“发布商品”区域上架。</p></div>`;
  }

  const isOpen = product.status === 'active' || product.status === 'resumed';
  const activeTrading = tradingIntent?.stage === 'trading';
  const cancelledTrading = tradingIntent?.stage === 'closed' && tradingIntent.outcome === 'cancelled';
  const status = cancelledTrading ? '交易买家已撤销' : activeTrading ? '交易中' : (productStatus[product.status] || '状态待更新');
  const statusClass = activeTrading ? 'status-trading' : (isOpen ? 'status-active' : 'status-paused');
  const image = product.image
    ? `<img class="admin-product-image" src="${escapeHtml(product.image)}" alt="${escapeHtml(product.name)}的商品图片">`
    : '';
  const tradingNote = activeTrading
    ? `<div class="admin-trading-note"><span>当前交易：</span><strong>${escapeHtml(tradingIntent.name)}</strong><span> · ${escapeHtml(tradingIntent.phone)}</span></div>`
    : cancelledTrading
      ? `<div class="admin-trading-note admin-trading-cancelled"><strong>${escapeHtml(tradingIntent.name)} 已撤销意向</strong><span>商品仍处于冻结状态。请处理撤销，再由系统自动递补下一位；若队列已空，商品将恢复在售。</span></div>`
      : '';
  const actions = [
    isOpen ? '<button class="button" type="button" data-action="start">开始下一笔交易</button><button class="button button-quiet" type="button" data-action="freeze">暂缓接收意向</button>' : '',
    product.status === 'frozen' && !tradingIntent ? '<button class="button" type="button" data-action="unfreeze">恢复接收意向</button>' : '',
    activeTrading ? '<button class="button" type="button" data-action="success">标记交易成功</button><button class="button button-secondary" type="button" data-action="requeue">交易失败 · 重新排队</button><button class="button button-danger" type="button" data-action="void">交易失败 · 作废</button>' : '',
    cancelledTrading ? '<button class="button" type="button" data-action="advance">处理撤销并递补</button>' : ''
  ].join('');

  return `${heading}
    <div class="admin-product-body">
      ${image}
      <div class="admin-product-content">
        <div class="admin-current-top"><h3>${escapeHtml(product.name)}</h3><span class="status-pill ${statusClass}"><span class="status-dot" aria-hidden="true"></span>${status}</span></div>
        <p class="admin-price">${money(product.priceCents)}</p>
        <p class="muted product-description">${escapeHtml(product.description)}</p>
        ${tradingNote}
      </div>
    </div>
    ${actions ? `<div class="admin-actions">${actions}</div>` : ''}`;
}

function renderIntents(intents) {
  if (!intents.length) return '<div class="empty-state"><span class="empty-state-icon" aria-hidden="true">♡</span><h3>还没有购买意向</h3><p>有人留下意向后，会按顺序出现在这里。</p></div>';
  return `<ol class="admin-intent-list">${intents.map((intent) => `
    <li class="admin-intent-item ${intent.outcome === 'cancelled' ? 'admin-intent-cancelled' : ''}">
      <span class="admin-intent-position">${intent.outcome === 'cancelled' ? '已撤销' : intent.stage === 'queued' ? `第 ${escapeHtml(intent.position || '—')} 位` : '交易中'}</span>
      <div class="admin-intent-person"><strong>${escapeHtml(intent.name)}</strong><span>${escapeHtml(intent.phone)}</span></div>
      <span class="admin-intent-meta"><strong>${intent.outcome === 'cancelled' ? '交易买家已撤销' : intentStage[intent.stage] || '待更新'}</strong><time datetime="${escapeHtml(intent.submittedAt)}">${escapeHtml(formatDate(intent.submittedAt))}</time></span>
    </li>`).join('')}</ol>`;
}

function renderHistory(products) {
  if (!products.length) return '<div class="empty-state"><span class="empty-state-icon" aria-hidden="true">✳</span><h3>还没有历史商品</h3><p>完成第一笔交易后，记录会保存在这里。</p></div>';
  return `<div class="admin-history-list">${products.map((product) => `
    <details class="admin-history-item">
      <summary class="admin-history-summary"><span><strong>${escapeHtml(product.name)}</strong><small>发布于 ${escapeHtml(formatDate(product.createdAt))}</small></span><span class="status-pill status-active">交易成功</span></summary>
      <div class="admin-history-detail">
        <div class="admin-history-product ${product.image ? '' : 'admin-history-product-no-image'}">
          ${product.image ? `<img src="${escapeHtml(product.image)}" alt="${escapeHtml(product.name)}的商品图片">` : ''}
          <div>
            <p class="admin-history-description">${escapeHtml(product.description)}</p>
            <div class="admin-history-facts"><span>价格 <strong>${money(product.priceCents)}</strong></span><span>发布时间 <strong>${escapeHtml(formatDate(product.createdAt))}</strong></span><span>交易结果 <strong>${product.result === 'success' ? '成功' : escapeHtml(product.result || '待更新')}</strong></span><span>交易时间 <strong>${escapeHtml(formatDate(product.resultAt))}</strong></span></div>
          </div>
        </div>
        <div class="admin-history-intents"><h3>完整意向记录</h3>${product.intents.length ? `<ul>${product.intents.map((intent) => `
          <li><span><strong>${escapeHtml(intent.name)}</strong><small>${escapeHtml(intent.phone)}</small></span><span><strong>${intentOutcome[intent.outcome] || '待更新'}</strong><time datetime="${escapeHtml(intent.submittedAt)}">${escapeHtml(formatDate(intent.submittedAt))}</time></span></li>`).join('')}</ul>` : '<p class="muted">这件商品没有意向记录。</p>'}</div>
      </div>
    </details>`).join('')}</div>`;
}

async function load() {
  const [{ product, tradingIntent }, { intents }, history] = await Promise.all([
    request('/api/admin/product'),
    request('/api/admin/intents'),
    request('/api/admin/history')
  ]);
  $('#current').innerHTML = renderCurrent(product, tradingIntent);
  $('#intents').innerHTML = renderIntents(intents);
  $('#history').innerHTML = renderHistory(history.products);
  $('#publish').hidden = Boolean(product);
  $('#publishNotice').hidden = !product;
  $('#current').querySelectorAll('[data-action]').forEach((button) => {
    button.onclick = () => action(button.dataset.action, button);
  });
}

async function action(type, button) {
  if (type === 'success' && !confirm('标记交易成功后不可撤销，确定继续吗？')) return;
  message('');
  button.disabled = true;
  try {
    let newPasscode = null;
    if (type === 'start') await request('/api/admin/trade/start', { method: 'POST' });
    else if (type === 'freeze') await request('/api/admin/freeze', { method: 'POST' });
    else if (type === 'unfreeze') await request('/api/admin/unfreeze', { method: 'POST' });
    else if (type === 'success') await request('/api/admin/trade/success', { method: 'POST' });
    else if (type === 'advance') await request('/api/admin/trade/advance', { method: 'POST' });
    else {
      const result = await request('/api/admin/trade/fail', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ disposition: type === 'requeue' ? 'requeue' : 'void' })
      });
      newPasscode = result.newPasscode;
    }
    if (newPasscode) {
      $('#sellerPasscodeValue').textContent = newPasscode;
      $('#sellerPasscodeCard').hidden = false;
      $('#sellerPasscodeCard').focus();
    }
    await load();
    const actionMessages = {
      start: '已开始联系下一位买家。',
      freeze: '已暂缓接收新的购买意向。',
      unfreeze: '已恢复接收购买意向。',
      success: '交易已标记成功，这件好物已移入历史记录。',
      void: '本次意向已结束，队列已更新。',
      requeue: '买家已重新排队，新口令码已展示，请告知买家。',
      advance: '撤销已处理，队列和商品状态已更新。'
    };
    message(actionMessages[type], 'success');
  } catch (error) {
    message(error.message);
    button.disabled = false;
  }
}

$('#loginForm').onsubmit = async (event) => {
  event.preventDefault();
  message('');
  const button = event.target.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    await request('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(Object.fromEntries(new FormData(event.target)))
    });
    await load();
    $('#login').hidden = true;
    $('#dashboard').hidden = false;
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
  }
};

$('#dismissSellerPasscode').onclick = () => {
  $('#sellerPasscodeCard').hidden = true;
  $('#sellerPasscodeValue').textContent = '';
};

$('#publish').onsubmit = async (event) => {
  event.preventDefault();
  message('');
  const button = event.target.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    await request('/api/products', { method: 'POST', body: new FormData(event.target) });
    event.target.reset();
    await load();
    message('商品已发布，正在等待第一份购买意向。', 'success');
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
  }
};

$('#password').onsubmit = async (event) => {
  event.preventDefault();
  message('');
  const button = event.target.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    await request('/api/auth/password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(Object.fromEntries(new FormData(event.target)))
    });
    event.target.reset();
    message('密码已修改，请妥善保存新密码。', 'success');
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
  }
};
