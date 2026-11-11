'use strict';
const $ = selector => document.querySelector(selector);
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const money = value => `¥${(value / 100).toFixed(2)}`;
async function api(url) { const response = await fetch(url); const body = await response.json(); if (!response.ok) throw Error(body.error || '暂时无法加载，请重试'); return body; }
let requestId = 0;
async function render() {
  const sequence = ++requestId;
  const params = new URLSearchParams(location.search);
  $('.intro').hidden = params.has('product');
  $('#message').textContent = '';
  $('#catalog').hidden = params.has('product'); $('#detail').hidden = !params.has('product');
  try {
    if (params.has('product')) {
      $('#detail').innerHTML = '<p class="muted">正在加载商品详情…</p>';
      const product = await api(`/api/products/${encodeURIComponent(params.get('product'))}`);
      if (sequence !== requestId) return;
      const images = product.images.length ? product.images : ['/placeholder.svg'];
      const sellable = product.status === 'active' || product.status === 'resumed';
      const stockLeft = Number(product.stock || 0);
      $('#detail').innerHTML = `<div class="detail-top"><a href="/" id="back">← 返回商品列表</a><span class="status">${sellable ? '在售' : '已下架'}</span></div><div class="detail-layout"><div><img class="main-image" src="${escapeHtml(images[0])}" alt="${escapeHtml(product.name)}"><div class="thumbnails">${images.map((src,i)=>`<button type="button" aria-label="查看第 ${i+1} 张图片" aria-pressed="${i===0}" data-image="${escapeHtml(src)}"><img src="${escapeHtml(src)}" alt="商品图片 ${i+1}"></button>`).join('')}</div></div><div class="detail-copy"><p class="muted">${escapeHtml(product.category.parentName)} / ${escapeHtml(product.category.name)}</p><h2>${escapeHtml(product.name)}</h2><p class="money">${money(product.priceCents)}</p><p>可售库存：${stockLeft} 件</p><div class="description"><h3>商品描述</h3>${product.description || '<p class="muted">暂无商品描述</p>'}</div></div></div>`;
      document.querySelectorAll('[data-image]').forEach(button=>button.onclick=()=>{ $('.main-image').src=button.dataset.image; document.querySelectorAll('[data-image]').forEach(b=>b.setAttribute('aria-pressed',String(b===button))); });
      $('#back').onclick = event => { event.preventDefault(); const next=new URLSearchParams(location.search);next.delete('product');navigate(next); };
      await mountActions(product, sellable, stockLeft);
      return;
    }
    $('#query').value = params.get('q') || ''; $('#category').value = params.get('categoryId') || '';
    const query = new URLSearchParams();
    for (const key of ['q','categoryId','page']) if (params.get(key)) query.set(key,params.get(key));
    $('#list').innerHTML = '<p class="muted">正在加载在售商品…</p>';
    const data = await api(`/api/products?${query}`);
    if (sequence !== requestId) return;
    $('#result-count').textContent = `共 ${data.total} 件在售商品`;
    $('#list').innerHTML = data.items.length ? data.items.map(p=>`<a class="listing" href="?product=${p.id}"><img src="${escapeHtml(p.image)}" alt="${escapeHtml(p.name)}" loading="lazy"><div class="listing-body"><span class="category">${escapeHtml(p.category.parentName)} / ${escapeHtml(p.category.name)}</span><h2>${escapeHtml(p.name)}</h2><span class="money">${money(p.priceCents)}</span></div></a>`).join('') : '<div class="empty">没有找到在售商品，请调整关键词或类别后重试。</div>';
    document.querySelectorAll('.listing').forEach(link=>link.onclick=event=>{ event.preventDefault(); const next=new URLSearchParams(location.search); next.set('product',new URL(link.href).searchParams.get('product')); navigate(next); });
    $('#pagination').innerHTML = `<button class="secondary" id="previous" ${data.page<=1?'disabled':''}>上一页</button><span>第 ${data.page} / ${Math.max(1,data.totalPages)} 页</span><button class="secondary" id="next" ${data.page>=data.totalPages?'disabled':''}>下一页</button>`;
    $('#previous').onclick=()=>changePage(data.page-1); $('#next').onclick=()=>changePage(data.page+1);
  } catch (error) {
    if (sequence !== requestId) return;
    $('#message').textContent=error.message;
    if (params.has('product')) $('#detail').innerHTML='<div class="empty"><h2>商品不存在或已下架</h2><a href="/">返回商品列表</a></div>';
    else $('#list').innerHTML='<div class="empty">商品加载失败，请稍后重试。</div>';
  }
}

/**
 * 详情页操作区（14201 收藏 / 14202 加入购物车）。
 *
 * 口径：详情页<b>不提供</b>「立即购买」，下单唯一入口是购物车（BR-22 / EX-08）；
 * 已下架商品经列表、搜索、直链都进不来，只有从收藏点开时本页才可能不可售——
 * 此时只展示只读信息与「已下架」标记，并隐藏加购按钮（FR-C-03 例外条款）。
 * 未登录时按钮一律引导去登录页（BR-40）。
 */
async function mountActions(product, sellable, stockLeft) {
  const me = await fetch('/api/customer/me').then(response => response.ok).catch(() => false);
  const loginUrl = `/customer-login.html?redirect=${encodeURIComponent(location.pathname + location.search)}`;
  const panel = document.createElement('div');
  panel.className = 'intent-panel';
  const unavailableReason = !sellable ? '商品已下架，仅可从收藏查看详情' : (stockLeft <= 0 ? '库存不足，暂时无法加入购物车' : '');
  panel.innerHTML = `<div class="notice">${unavailableReason
      ? escapeHtml(unavailableReason) + '。'
      : '把商品加入购物车后，在购物车勾选并一次性提交订单；提交成功即占用库存。'}</div>
    <div class="detail-actions">
      <button type="button" id="toggle-favorite" class="secondary" ${me ? '' : 'data-login="1"'}>收藏</button>
      <button type="button" id="add-to-cart" ${me && sellable && stockLeft > 0 ? '' : 'disabled'}>加入购物车</button>
      <a class="button-link" href="/cart.html">去购物车下单</a>
    </div>
    <p id="action-message" role="status"></p>`;
  $('#detail').querySelector('.detail-copy').appendChild(panel);
  const message = panel.querySelector('#action-message');
  const favoriteButton = panel.querySelector('#toggle-favorite');
  const cartButton = panel.querySelector('#add-to-cart');
  const say = (text, kind) => { message.textContent = text; message.dataset.kind = kind || 'success'; };

  if (!me) {
    favoriteButton.onclick = () => { location.href = loginUrl; };
    return;
  }

  let favorited = false;
  try {
    const mark = await api(`/api/cart/marks/${product.id}`);
    favorited = Boolean(mark.favorited);
  } catch (error) { /* 状态读取失败不阻塞页面，按钮保持默认文案 */ }
  const paintFavorite = () => {
    favoriteButton.textContent = favorited ? '取消收藏' : '收藏';
    favoriteButton.classList.toggle('secondary', !favorited);
    favoriteButton.dataset.favorited = String(favorited);
  };
  paintFavorite();

  favoriteButton.onclick = async () => {
    favoriteButton.disabled = true;
    try {
      if (favorited) {
        await fetch(`/api/favorites/${product.id}`, { method: 'DELETE' });
        favorited = false; say('已取消收藏');
      } else {
        const response = await fetch(`/api/favorites/${product.id}`, { method: 'POST' });
        // 重复收藏不产生第二条记录，后端幂等返回
        if (!response.ok) { const body = await response.json().catch(() => ({})); throw Error(body.error || '收藏失败'); }
        favorited = true; say('已加入我的收藏');
      }
      paintFavorite();
    } catch (error) { say(error.message, 'error'); }
    finally { favoriteButton.disabled = false; }
  };

  cartButton.onclick = async () => {
    cartButton.disabled = true;
    try {
      const response = await fetch('/api/cart', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ productId: Number(product.id), quantity: 1 })
      });
      const body = await response.json().catch(() => ({}));
      if (response.status === 401) { location.href = loginUrl; return; }
      if (!response.ok) throw Error(body.error || '加入购物车失败');
      say(`已加入购物车，当前购物车共 ${body.quantity} 件该商品`);
    } catch (error) { say(error.message, 'error'); }
    finally { cartButton.disabled = !(sellable && stockLeft > 0); }
  };
}

function navigate(params) { history.pushState(null,'',`/?${params}`); render(); }
function changePage(page) { const params=new URLSearchParams(location.search);params.set('page',page);navigate(params); }
$('#search-form').onsubmit=event=>{event.preventDefault();const params=new URLSearchParams();if($('#query').value.trim())params.set('q',$('#query').value.trim());if($('#category').value)params.set('categoryId',$('#category').value);navigate(params);};
$('#category').onchange=()=>$('#search-form').requestSubmit();
$('#reset').onclick=()=>{navigate(new URLSearchParams());};
window.onpopstate=render;
api('/api/categories').then(tree=>{ $('#category').innerHTML='<option value="">全部类别</option>'+tree.map(parent=>`<optgroup label="${escapeHtml(parent.name)}">${parent.children.map(child=>`<option value="${child.id}">${escapeHtml(child.name)}</option>`).join('')}</optgroup>`).join('');render(); }).catch(error=>{$('#message').textContent=error.message;render();});
