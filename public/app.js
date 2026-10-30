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
      const frozen = product.status === 'frozen';
      $('#detail').innerHTML = `<div class="detail-top"><a href="/" id="back">← 返回商品列表</a><span class="status ${frozen ? 'frozen' : ''}">${frozen ? '交易中 · 已冻结' : '在售'}</span></div><div class="detail-layout"><div><img class="main-image" src="${escapeHtml(images[0])}" alt="${escapeHtml(product.name)}"><div class="thumbnails">${images.map((src,i)=>`<button type="button" aria-label="查看第 ${i+1} 张图片" aria-pressed="${i===0}" data-image="${escapeHtml(src)}"><img src="${escapeHtml(src)}" alt="商品图片 ${i+1}"></button>`).join('')}</div></div><div class="detail-copy"><p class="muted">${escapeHtml(product.category.parentName)} / ${escapeHtml(product.category.name)}</p><h2>${escapeHtml(product.name)}</h2><p class="money">${money(product.priceCents)}</p><p>库存：${product.stock} 件</p><div class="notice">${frozen ? '卖家正在与队首客户交易，当前商品只供查看，暂不接受购买意向。' : '购买意向须登录客户账号后提交。交易在线下完成。'}</div><div class="description"><h3>商品描述</h3>${product.description || '<p class="muted">暂无商品描述</p>'}</div></div></div>`;
      document.querySelectorAll('[data-image]').forEach(button=>button.onclick=()=>{ $('.main-image').src=button.dataset.image; document.querySelectorAll('[data-image]').forEach(b=>b.setAttribute('aria-pressed',String(b===button))); });
      $('#back').onclick = event => { event.preventDefault(); const next=new URLSearchParams(location.search);next.delete('product');navigate(next); };
      // 22303/22304 提交购买意向：必须登录；未登录一律不给可用入口
      const logged = await fetch('/api/customer/me').then(response => response.ok).catch(() => false);
      const buyable = product.status === 'active' || product.status === 'resumed';
      const stockLeft = Number(product.stock || 0);
      const loginUrl = `/customer-login.html?redirect=${encodeURIComponent(location.pathname + location.search)}`;
      let control;
      if (!logged) control = `<a class="button-link" href="${loginUrl}">登录后提交购买意向</a>`;
      else if (!buyable) control = `<button type="button" disabled>${frozen ? '交易中 · 暂不接受意向' : '商品已下架 · 不可提交'}</button>`;
      else if (stockLeft <= 0) control = '<button type="button" disabled>已售罄 · 不可提交</button>';
      else control = '<button type="button" id="submit-intent">提交购买意向（1 件）</button>';
      const panel = document.createElement('div');
      panel.className = 'intent-panel';
      panel.innerHTML = `<div class="notice">每次提交只买 1 件，提交不占用、不扣减库存；卖家按提交时间顺序联系买家。</div>${control}<p id="intent-message" role="status"></p>`;
      $('#detail').querySelector('.detail-copy').appendChild(panel);
      const submit = panel.querySelector('#submit-intent');
      if (submit) submit.onclick = async () => {
        const message = panel.querySelector('#intent-message');
        submit.disabled = true;
        try {
          const response = await fetch('/api/customer/intents', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ productId: Number(product.id) })
          });
          const body = await response.json().catch(() => ({}));
          if (response.status === 401) { location.href = loginUrl; return; }
          if (!response.ok) throw Error(body.error || '提交失败，请稍后重试');
          message.dataset.kind = 'success';
          message.textContent = `提交成功：当前排在第 ${body.position} 位，库存仍为 ${body.stock} 件。`;
          submit.textContent = '再次提交意向（可重复排队）';
          submit.disabled = false;
        } catch (error) {
          message.dataset.kind = 'error';
          message.textContent = error.message;
          submit.disabled = false;
        }
      };
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
function navigate(params) { history.pushState(null,'',`/?${params}`); render(); }
function changePage(page) { const params=new URLSearchParams(location.search);params.set('page',page);navigate(params); }
$('#search-form').onsubmit=event=>{event.preventDefault();const params=new URLSearchParams();if($('#query').value.trim())params.set('q',$('#query').value.trim());if($('#category').value)params.set('categoryId',$('#category').value);navigate(params);};
$('#category').onchange=()=>$('#search-form').requestSubmit();
$('#reset').onclick=()=>{navigate(new URLSearchParams());};
window.onpopstate=render;
api('/api/categories').then(tree=>{ $('#category').innerHTML='<option value="">全部类别</option>'+tree.map(parent=>`<optgroup label="${escapeHtml(parent.name)}">${parent.children.map(child=>`<option value="${child.id}">${escapeHtml(child.name)}</option>`).join('')}</optgroup>`).join('');render(); }).catch(error=>{$('#message').textContent=error.message;render();});
