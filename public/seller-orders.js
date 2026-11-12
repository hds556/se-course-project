'use strict';
/*
 * 卖家订单处理页（挑战需求B · 开发B）
 *
 * 覆盖四条需求：
 *   14209 查看商品订单记录 —— 「按商品」维度，列出涉及该商品的全部订单
 *   14214 查看客户购买历史 —— 「按客户」维度，列出该客户的全部订单
 *   14207 推进订单状态     —— 每条订单只给「推进到下一状态」一个按钮
 *   14208 卖家取消订单     —— 交易完成前（含已发货）可取消，取消后回补库存
 *
 * 设计原则：页面不做任何状态判断。能不能推进、能不能取消、下一步是哪个状态，
 * 全部由后端算好放在响应里（advanceable / cancellable / nextStatusLabel），
 * 前端只负责按这些字段渲染按钮，避免前后端各写一套状态机而逐渐走偏。
 * 本文件不依赖 shop.js / admin.js，自带请求封装与格式化，避免与它们重名冲突。
 */
(() => {
  const $ = selector => document.querySelector(selector);

  const esc = value => String(value ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  /** 分转元，固定两位小数 */
  const money = cents => `¥${(Number(cents || 0) / 100).toFixed(2)}`;

  const fmtTime = value => {
    if (!value) return '—';
    const date = new Date(value);
    return isNaN(date) ? value : date.toLocaleString('zh-CN', { hour12: false });
  };

  /**
   * 统一请求：401 视为卖家登录失效，直接回登录页（与服务端 requireSession 的语义一致）；
   * 其他非 2xx 一律抛出带字段明细的错误，页面只负责显示。
   */
  async function api(url, options = {}) {
    const response = await fetch(url, options);
    let body = null;
    try { body = await response.json(); } catch (e) { body = null; }
    if (response.status === 401) {
      location.href = '/login.html';
      throw new Error('登录已失效');
    }
    if (!response.ok) {
      const error = new Error((body && body.error) || `请求失败（${response.status}）`);
      error.status = response.status;
      error.fieldErrors = (body && body.fieldErrors) || {};
      throw error;
    }
    return body;
  }

  const postJson = (url, body) => api(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body === undefined ? {} : body),
  });

  /* 页面状态：维度 + 当前选中的商品 / 客户 + 页码 */
  const state = { dim: 'product', productId: null, customerId: null, page: 1 };
  const cache = { products: null, customers: null };

  function setNotice(message, kind = 'info') {
    const node = $('#notice');
    node.textContent = message || '';
    node.dataset.kind = kind;
    node.hidden = !message;
  }

  /* ==================== 维度选择器 ==================== */

  async function loadProducts() {
    if (cache.products) return cache.products;
    const items = [];
    for (let page = 1; page <= 10; page++) {
      const data = await api(`/api/admin/products?page=${page}&size=60`);
      items.push(...data.items);
      if (page >= data.totalPages || !data.items.length) break;
    }
    cache.products = items;
    return items;
  }

  async function loadCustomers() {
    if (cache.customers) return cache.customers;
    const items = [];
    for (let page = 1; page <= 10; page++) {
      const data = await api(`/api/admin/customers?page=${page}&size=50`);
      items.push(...data.items);
      if (page >= data.totalPages || !data.items.length) break;
    }
    cache.customers = items;
    return items;
  }

  async function renderPicker() {
    const box = $('#picker');
    try {
      if (state.dim === 'product') {
        const products = await loadProducts();
        if (!products.length) {
          box.innerHTML = '<p class="muted">还没有发布过商品，无法按商品查看订单记录。</p>';
          state.productId = null;
          return;
        }
        if (state.productId == null) state.productId = products[0].id;
        box.innerHTML = `<label>选择商品<select id="pick-product">${products.map(p =>
          `<option value="${p.id}" ${p.id === state.productId ? 'selected' : ''}>`
          + `#${p.id} ${esc(p.name)}（库存 ${p.stock} · ${esc(statusLabel(p.status))}）</option>`).join('')}`
          + '</select></label><p class="muted">按商品维度只统计订单明细里出现过该商品的订单，不限订单状态。</p>';
        $('#pick-product').onchange = event => {
          state.productId = Number(event.target.value);
          state.page = 1;
          load();
        };
      } else if (state.dim === 'customer') {
        const customers = await loadCustomers();
        if (!customers.length) {
          box.innerHTML = '<p class="muted">还没有客户注册，无法按客户查看购买历史。</p>';
          state.customerId = null;
          return;
        }
        if (state.customerId == null) state.customerId = customers[0].id;
        box.innerHTML = `<label>选择客户<select id="pick-customer">${customers.map(c =>
          `<option value="${c.id}" ${c.id === state.customerId ? 'selected' : ''}>`
          + `${esc(c.username)}（${esc(c.phone)}）</option>`).join('')}`
          + `</select></label><p class="muted">按客户维度只查询该客户自己的订单，客户之间互不可见。</p>`;
        $('#pick-customer').onchange = event => {
          state.customerId = Number(event.target.value);
          state.page = 1;
          load();
        };
      } else {
        box.innerHTML = '<p class="muted">全部订单按下单时间倒序排列，最新在最上面；需要定位到某件商品或某位客户时，请切换上方页签。</p>';
      }
    } catch (error) {
      box.innerHTML = `<p class="error">${esc(error.message)}</p>`;
    }
  }

  const PRODUCT_STATES = {
    active: '在售', resumed: '在售', frozen: '已冻结',
    sold: '已售（终态）', depleted: '售罄下架', withdrawn: '手动下架',
  };
  const statusLabel = code => PRODUCT_STATES[code] || code || '未知';

  /* ==================== 订单渲染 ==================== */

  function statusClass(order) {
    if (order.status === 'completed') return 'order-status completed';
    if (order.status === 'cancelled') return 'order-status cancelled';
    return 'order-status progress';
  }

  function detailTable(order) {
    const rows = order.items.map(line => `<tr class="${line.highlight ? 'hit' : ''}">
        <td>${esc(line.productName)}${line.highlight ? ' <span class="badge">本商品</span>' : ''}</td>
        <td>${money(line.unitPriceCents)}</td>
        <td>${line.quantity}</td>
        <td>${money(line.subtotalCents)}</td>
      </tr>`).join('');
    return `<table class="orders-table">
        <thead><tr><th>商品名称</th><th>下单单价</th><th>数量</th><th>小计</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
      <p class="muted">明细为下单时的快照，卖家后续改名或改价不影响本单。订单合计 ${money(order.totalCents)}。</p>`;
  }

  /**
   * 单条订单：摘要行 + 行内明细。
   * 操作按钮只按后端给出的 advanceable / cancellable 渲染，前端不推断状态机。
   */
  function orderCard(order) {
    const advance = order.advanceable
      ? `<button type="button" data-advance="${order.id}">推进到「${esc(order.nextStatusLabel)}」</button>`
      : '<span class="muted">已到终态</span>';
    const cancel = order.cancellable
      ? `<button type="button" class="secondary" data-cancel="${order.id}">取消订单</button>`
      : '';
    return `<details class="order-card" data-order="${order.id}">
      <summary class="seller-summary">
        <span class="order-no">订单号 ${esc(order.orderNo)}</span>
        <span>${esc(order.customerName)}<span class="muted"> · ${esc(order.customerPhone)}</span></span>
        <span class="muted">${esc(fmtTime(order.createdAt))}</span>
        <span class="money">${money(order.totalCents)}</span>
        <span class="${statusClass(order)}">${esc(order.statusLabel)}</span>
        <span class="order-ops">${advance}${cancel}</span>
      </summary>
      <div class="order-detail">
        ${detailTable(order)}
        <p class="muted">状态流转：客户下单 → 商家确认 → 备货完成 → 开始发货 → 交易完成。当前为「${esc(order.statusLabel)}」${
          order.advanceable ? `，下一步只能推进到「${esc(order.nextStatusLabel)}」` : '，已是终态'}。</p>
      </div>
    </details>`;
  }

  function renderHead(data) {
    const box = $('#dim-head');
    if (data.product) {
      const p = data.product;
      box.innerHTML = `<div class="seller-meta">
        <span>商品 <strong>#${p.id} ${esc(p.name)}</strong></span>
        <span>当前库存 <strong>${p.stock}</strong></span>
        <span>商品状态 <strong>${esc(p.statusLabel)}</strong></span>
        <span>涉及订单 <strong>${p.orderCount}</strong> 笔</span>
        <span>累计下单 <strong>${p.totalQuantity}</strong> 件</span>
      </div>`;
    } else if (data.customer) {
      const c = data.customer;
      box.innerHTML = `<div class="seller-meta">
        <span>客户 <strong>${esc(c.username)}</strong></span>
        <span>电话 <strong>${esc(c.phone)}</strong></span>
        <span>默认交易地点 <strong>${esc(c.defaultPlace)}</strong></span>
        <span>注册时间 <strong>${esc(fmtTime(c.registeredAt))}</strong></span>
        <span>订单 <strong>${data.total}</strong> 笔</span>
      </div>`;
    } else {
      box.innerHTML = `<div class="seller-meta"><span>全部订单 <strong>${data.total}</strong> 笔</span></div>`;
    }
  }

  function renderPaging(data) {
    const node = $('#pagination');
    const totalPages = Math.max(1, data.totalPages || 1);
    node.innerHTML = `<button class="secondary" data-page="${data.page - 1}" ${data.page <= 1 ? 'disabled' : ''}>上一页</button>`
      + `<span>第 ${data.page} / ${totalPages} 页（共 ${data.total || 0} 笔）</span>`
      + `<button class="secondary" data-page="${data.page + 1}" ${data.page >= totalPages ? 'disabled' : ''}>下一页</button>`;
    node.querySelectorAll('[data-page]').forEach(button => {
      button.onclick = () => { state.page = Number(button.dataset.page); load(); };
    });
  }

  const emptyHint = () => {
    if (state.dim === 'product') return '<div class="empty"><h2>这件商品还没有订单</h2><p>买家从购物车下单后，涉及该商品的订单会出现在这里。</p></div>';
    if (state.dim === 'customer') return '<div class="empty"><h2>这位客户还没有订单</h2><p>该客户下单后，其全部订单会出现在这里。</p></div>';
    return '<div class="empty"><h2>还没有任何订单</h2><p>买家提交订单后会出现在这里。</p></div>';
  };

  /* ==================== 数据加载 ==================== */

  async function load(keepNotice) {
    if (!keepNotice) setNotice('');
    if (state.dim === 'product' && state.productId == null) { $('#content').innerHTML = emptyHint(); $('#pagination').innerHTML = ''; $('#dim-head').innerHTML = ''; return; }
    if (state.dim === 'customer' && state.customerId == null) { $('#content').innerHTML = emptyHint(); $('#pagination').innerHTML = ''; $('#dim-head').innerHTML = ''; return; }

    const params = new URLSearchParams({ page: String(state.page), size: '10' });
    if (state.dim === 'product') params.set('productId', String(state.productId));
    if (state.dim === 'customer') params.set('customerId', String(state.customerId));

    try {
      const data = await api(`/api/admin/orders?${params}`);
      renderHead(data);
      if (!data.items.length) {
        $('#content').innerHTML = emptyHint();
        $('#pagination').innerHTML = '';
        return;
      }
      $('#content').innerHTML = data.items.map(orderCard).join('');
      renderPaging(data);
    } catch (error) {
      $('#content').innerHTML = `<div class="empty">${esc(error.message)}</div>`;
      $('#pagination').innerHTML = '';
    }
  }

  /* ==================== 交互 ==================== */

  document.querySelectorAll('[data-dim]').forEach(button => {
    button.onclick = () => {
      state.dim = button.dataset.dim;
      state.page = 1;
      document.querySelectorAll('[data-dim]').forEach(other =>
        other.classList.toggle('secondary', other !== button));
      setNotice('');
      renderPicker().then(() => load());
    };
  });

  /* 按钮位于 <summary> 内，点击会顺带折叠/展开订单，因此同时拦掉 click 与 mousedown 的默认行为 */
  $('#content').addEventListener('mousedown', event => {
    if (event.target.closest('[data-advance],[data-cancel]')) event.preventDefault();
  });

  $('#content').addEventListener('click', async event => {
    const advanceButton = event.target.closest('[data-advance]');
    const cancelButton = event.target.closest('[data-cancel]');
    if (!advanceButton && !cancelButton) return;
    event.preventDefault();
    event.stopPropagation();

    const button = advanceButton || cancelButton;
    const orderId = button.dataset.advance || button.dataset.cancel;
    button.disabled = true;
    try {
      if (advanceButton) {
        const result = await postJson(`/api/admin/orders/${orderId}/advance`);
        await load(true);
        setNotice(`订单 ${orderId} 已推进：${result.statusLabel}`, 'success');
      } else {
        if (!confirm(`确认取消订单 ${orderId}？取消后不可撤销，并按明细数量回补库存。`)) {
          button.disabled = false;
          return;
        }
        const result = await postJson(`/api/admin/orders/${orderId}/cancel`);
        await load(true);
        setNotice(`订单 ${orderId} 已取消，共回补 ${result.restoredQuantity} 件商品库存`, 'success');
      }
    } catch (error) {
      button.disabled = false;
      setNotice(error.message, 'error');
    }
  });

  $('#logout').onclick = async () => {
    try { await api('/api/auth/logout', { method: 'POST' }); } catch (e) { /* 已经失效也照样回登录页 */ }
    location.href = '/login.html';
  };

  renderPicker().then(() => load());
})();
