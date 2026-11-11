'use strict';
/*
 * 买家端公共脚本（挑战需求B · 开发A）
 *
 * 收藏、购物车、订单三个页面与详情页共用：统一的请求封装、金额与时间格式化、
 * 登录态判定与顶部导航渲染。任何页面都不在这里做业务判断，业务判定一律以后端为准。
 */

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
 * 统一请求：非 2xx 抛出带 error / itemErrors 字段的错误对象，
 * 401 表示未登录，由调用方决定跳转还是提示。
 */
async function request(url, options = {}) {
  const response = await fetch(url, options);
  let body = null;
  try { body = await response.json(); } catch (e) { body = null; }
  if (!response.ok) {
    const error = new Error((body && body.error) || `请求失败（${response.status}）`);
    error.status = response.status;
    error.itemErrors = (body && body.itemErrors) || {};
    error.fieldErrors = (body && body.fieldErrors) || {};
    throw error;
  }
  return body;
}

const postJson = (url, body) => request(url, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body === undefined ? {} : body),
});

const patchJson = (url, body) => request(url, {
  method: 'PATCH',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body === undefined ? {} : body),
});

const del = url => request(url, { method: 'DELETE' });

const LOGIN_PAGE = '/customer-login.html';

/** 当前页面地址，登录后原路返回 */
const here = () => encodeURIComponent(location.pathname + location.search);

const toLogin = () => { location.href = `${LOGIN_PAGE}?redirect=${here()}`; };

/** 取当前登录客户；未登录返回 null（不跳转） */
async function currentCustomer() {
  try {
    const response = await fetch('/api/customer/me');
    return response.ok ? await response.json() : null;
  } catch (e) {
    return null;
  }
}

/** 需要登录的页面在初始化时调用：未登录直接跳登录页 */
async function requireCustomer() {
  const me = await currentCustomer();
  if (!me) { toLogin(); throw new Error('未登录'); }
  return me;
}

/** 顶部导航：根据登录态切换「我的订单 / 我的收藏 / 购物车」与登录入口 */
async function mountUserNav(selector = '#user-nav') {
  const nav = document.querySelector(selector);
  if (!nav) return;
  const me = await currentCustomer();
  nav.innerHTML = me
    ? `<a href="/cart.html">购物车</a><a href="/favorites.html">我的收藏</a><a href="/orders.html">我的订单</a>`
      + `<span class="muted">当前用户：${esc(me.username)}</span>`
      + `<a href="#" id="nav-logout">退出登录</a><a href="/admin.html">卖家后台 ↗</a>`
    : `<a href="/cart.html">购物车</a><a href="/favorites.html">我的收藏</a><a href="/orders.html">我的订单</a>`
      + `<a href="${LOGIN_PAGE}">客户登录</a><a href="/register.html">注册</a><a href="/admin.html">卖家后台 ↗</a>`;
  const logout = document.querySelector('#nav-logout');
  if (logout) {
    logout.onclick = async event => {
      event.preventDefault();
      await fetch('/api/customer/logout', { method: 'POST' });
      location.href = '/';
    };
  }
}

/** 页内提示条 */
function setNotice(tag, message, kind = 'info') {
  const node = typeof tag === 'string' ? document.querySelector(tag) : tag;
  if (!node) return;
  node.textContent = message || '';
  node.dataset.kind = kind;
  node.hidden = !message;
}

/** 页码条：onChange(page) */
function renderPaging(tag, data, onChange) {
  const node = typeof tag === 'string' ? document.querySelector(tag) : tag;
  if (!node) return;
  const totalPages = Math.max(1, data.totalPages || 1);
  node.innerHTML = `<button class="secondary" data-page="${data.page - 1}" ${data.page <= 1 ? 'disabled' : ''}>上一页</button>`
    + `<span>第 ${data.page} / ${totalPages} 页（共 ${data.total || 0} 条）</span>`
    + `<button class="secondary" data-page="${data.page + 1}" ${data.page >= totalPages ? 'disabled' : ''}>下一页</button>`;
  node.querySelectorAll('[data-page]').forEach(button => {
    button.onclick = () => onChange(Number(button.dataset.page));
  });
}
