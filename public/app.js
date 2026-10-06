const $ = (selector) => document.querySelector(selector);
let currentCode = '';
const money = (cents) => `¥${(cents / 100).toFixed(2)}`;
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, (char) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[char]);

function message(text, tone = 'error') {
  const element = $('#message');
  element.textContent = text || '';
  element.dataset.tone = tone;
}

async function request(url, options = {}) {
  const response = await fetch(url, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '操作失败');
  return data;
}

async function loadProduct() {
  try {
    const { product } = await request('/api/product');
    if (!product) {
      $('#product').innerHTML = '<div class="card empty-state"><span class="empty-mark" aria-hidden="true">✳</span><p class="eyebrow">好物筹备中</p><h2>暂无在售商品</h2><p>下一件好物还在准备中，欢迎稍后再来看看。</p></div>';
      return;
    }

    const frozen = !product.buyable;
    $('#product').innerHTML = `
      <article class="card product-card">
        <div class="product-media"><img src="${escapeHtml(product.image)}" alt="${escapeHtml(product.name)}"></div>
        <div class="product-content">
          <div class="product-topline"><span class="status-pill ${frozen ? 'status-paused' : 'status-active'}"><span class="status-dot" aria-hidden="true"></span>${frozen ? '暂停接受意向' : '正在接受意向'}</span><span class="product-edition">本期好物</span></div>
          <h2>${escapeHtml(product.name)}</h2>
          <p class="product-description">${escapeHtml(product.description)}</p>
          <div class="product-price"><span>售价</span><strong>${money(product.priceCents)}</strong></div>
          ${frozen ? '<div class="product-notice"><span aria-hidden="true">✳</span><p>这件商品暂时不接受新的购买意向。已有口令码的朋友，仍可在下方查看进度。</p></div>' : `
            <div class="product-form-intro"><h3>想把它带回家？</h3><p>留下姓名和电话，我们会按提交顺序联系你。</p></div>
            <form id="intent" class="stack-form">
              <div class="form-grid">
                <label class="field" for="intent-name"><span class="field-label">怎么称呼你</span><input id="intent-name" name="name" placeholder="你的姓名" autocomplete="name" required></label>
                <label class="field" for="intent-phone"><span class="field-label">联系电话</span><input id="intent-phone" name="phone" type="tel" placeholder="方便联系你的电话" autocomplete="tel" required></label>
              </div>
              <button class="button" type="submit">提交购买意向 <span aria-hidden="true">→</span></button>
              <p class="form-note">提交后会得到一个口令码，记得保存好。</p>
            </form>`}
        </div>
      </article>`;

    if (!frozen) $('#intent').addEventListener('submit', async (event) => {
      event.preventDefault();
      const submitButton = event.target.querySelector('button[type="submit"]');
      submitButton.disabled = true;
      try {
        const data = await request('/api/intents', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(Object.fromEntries(new FormData(event.target)))
        });
        currentCode = data.passcode;
        $('#passcodeValue').textContent = data.passcode;
        $('#passcodeCard').hidden = false;
        message('');
        event.target.reset();
        $('#passcodeCard').focus();
        $('#passcodeCard').scrollIntoView({
          behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
          block: 'center'
        });
      } catch (error) { message(error.message); }
      finally { submitButton.disabled = false; }
    });
  } catch (error) {
    $('#product').innerHTML = '<div class="card empty-state"><span class="empty-mark" aria-hidden="true">✳</span><h2>这件好物暂时没加载出来</h2><p>请稍后刷新页面再试。</p></div>';
    message(error.message);
  }
}

$('#query').addEventListener('submit', async (event) => {
  event.preventDefault();
  const submitButton = event.target.querySelector('button[type="submit"]');
  submitButton.disabled = true;
  currentCode = new FormData(event.target).get('passcode').trim().toUpperCase();
  $('#edit').hidden = true;
  $('#queryResult').textContent = '';
  try {
    const data = await request('/api/intents/query', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ passcode: currentCode })
    });
    $('#queryResult').textContent = data.stage === 'trading' ? '当前进度：正在交易中' : `当前排队进度：第 ${data.position} 位`;
    $('#edit').reset();
    $('#edit').hidden = false;
    message('');
  } catch (error) {
    $('#queryResult').textContent = '';
    $('#edit').hidden = true;
    message(error.message);
  } finally { submitButton.disabled = false; }
});

$('#edit').addEventListener('submit', async (event) => {
  event.preventDefault();
  const submitButton = event.target.querySelector('button[type="submit"]');
  submitButton.disabled = true;
  try {
    await request('/api/intents', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...Object.fromEntries(new FormData(event.target)), passcode: currentCode })
    });
    message('联系信息已更新。', 'success');
  } catch (error) { message(error.message); }
  finally { submitButton.disabled = false; }
});

$('#cancel').addEventListener('click', async () => {
  if (!confirm('确定撤销意向吗？')) return;
  const button = $('#cancel');
  button.disabled = true;
  try {
    await request('/api/intents/cancel', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ passcode: currentCode })
    });
    $('#edit').hidden = true;
    $('#queryResult').textContent = '意向已撤销。';
    message('');
  } catch (error) { message(error.message); }
  finally { button.disabled = false; }
});

loadProduct();
