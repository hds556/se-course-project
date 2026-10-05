const $ = (s) => document.querySelector(s);
let currentCode = '';
const message = (text) => { $('#message').textContent = text || ''; };
const money = (cents) => `¥${(cents / 100).toFixed(2)}`;
async function request(url, options = {}) {
  const response = await fetch(url, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '操作失败');
  return data;
}
async function loadProduct() {
  try {
    const { product } = await request('/api/product');
    if (!product) { $('#product').innerHTML = '<section class="card">暂无在售商品</section>'; return; }
    const frozen = !product.buyable;
    $('#product').innerHTML = `<section class="card product"><img src="${product.image}" alt="${product.name}"><div><h2>${product.name}</h2><p>${product.description}</p><p><strong>${money(product.priceCents)}</strong></p><p class="badge">${frozen ? '已被预定/暂停接受意向' : '在售'}</p>${frozen ? '' : '<form id="intent"><input name="name" placeholder="姓名" required><input name="phone" placeholder="联系电话" required><button>提交购买意向</button></form>'}</div></section>`;
    if (!frozen) $('#intent').addEventListener('submit', async (event) => {
      event.preventDefault();
      try { const data = await request('/api/intents', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(Object.fromEntries(new FormData(event.target))) }); currentCode = data.passcode; message('请保存口令码：'); $('#product').insertAdjacentHTML('afterend', `<section class="card"><div class="code">${data.passcode}</div><p>口令码只展示一次，请妥善保存。</p></section>`); event.target.reset(); } catch (error) { message(error.message); }
    });
  } catch (error) { message(error.message); }
}
$('#query').addEventListener('submit', async (event) => { event.preventDefault(); currentCode = event.target.passcode.value.trim().toUpperCase(); try { const data = await request('/api/intents/query', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({passcode:currentCode}) }); $('#queryResult').textContent = data.stage === 'trading' ? '当前状态：交易中' : `当前排队位次：第 ${data.position} 位`; $('#edit').hidden = false; } catch (error) { $('#edit').hidden = true; message(error.message); } });
$('#edit').addEventListener('submit', async (event) => { event.preventDefault(); try { await request('/api/intents', {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({...Object.fromEntries(new FormData(event.target)),passcode:currentCode})}); message('信息已修改'); } catch(error){message(error.message);} });
$('#cancel').addEventListener('click', async () => { if (!confirm('确定撤销意向吗？')) return; try { await request('/api/intents/cancel',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({passcode:currentCode})}); $('#edit').hidden=true; $('#queryResult').textContent='意向已撤销'; } catch(error){message(error.message);} });
loadProduct();
