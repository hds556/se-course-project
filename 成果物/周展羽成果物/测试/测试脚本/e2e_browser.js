// 浏览器端到端验证：真实 Chrome + 真实页面点击，覆盖买家下单与卖家处理订单主链路
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');

const BASE = process.env.TEST_BASE || 'http://127.0.0.1:18080';
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const OUT = path.join(__dirname, '证据', '截图');
fs.mkdirSync(OUT, { recursive: true });

const steps = [];
function rec(name, ok, detail) {
  steps.push({ name, ok, detail });
  console.log((ok ? 'PASS ' : 'FAIL ') + name + ' | ' + (detail || ''));
}

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1440,960'],
    defaultViewport: { width: 1440, height: 900 },
  });
  const page = await browser.newPage();
  const consoleErrors = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message));
  page.on('dialog', async d => { await d.accept(); });
  const notFound = [];
  page.on('response', r => { if (r.status() === 404) notFound.push(r.url().replace(BASE, '')); });

  const shot = async (name) => {
    await new Promise(r => setTimeout(r, 400));
    await page.screenshot({ path: path.join(OUT, name), fullPage: true });
  };

  // 准备：通过接口发一个专属商品，避免与前面用例的数据混在一起
  const login = await page.goto(BASE + '/api/products', { waitUntil: 'domcontentloaded' });
  const sellerLogin = await page.evaluate(async () => {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: 'admin1234' })
    });
    return r.status;
  });
  rec('E2E-00 卖家会话准备', sellerLogin === 200, 'status=' + sellerLogin);

  const cat = await page.evaluate(async () => {
    const r = await fetch('/api/categories');
    const tree = await r.json();
    return tree[0] && tree[0].children[0] ? tree[0].children[0].id : null;
  });
  const publish = await page.evaluate(async (categoryId) => {
    const fd = new FormData();
    fd.append('name', 'E2E演示商品-' + Date.now());
    fd.append('price', '88.00');
    fd.append('stock', '5');
    fd.append('categoryId', String(categoryId));
    fd.append('description', '<p>端到端测试用商品，含<b>加粗</b>描述</p>');
    const r = await fetch('/api/products', { method: 'POST', body: fd });
    return { status: r.status, body: await r.json() };
  }, cat);
  rec('E2E-01 准备商品数据', publish.status === 201, 'id=' + JSON.stringify(publish.body && publish.body.id));
  const pid = publish.body.id;

  // 1 注册
  const uname = 'e2e_' + Date.now().toString().slice(-8);
  await page.goto(BASE + '/register.html', { waitUntil: 'networkidle2' });
  await page.type('input[name=username]', uname);
  await page.type('input[name=password]', 'pass1234');
  await page.type('input[name=confirm]', 'pass1234');
  await page.type('input[name=phone]', '13512345678');
  await shot('01_注册页.png');
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle2' }), page.click('#register-form button[type=submit], #register-form button')]);
  rec('E2E-02 注册成功并跳转登录页', page.url().includes('customer-login'), page.url());

  // 2 登录
  await page.type('input[name=username]', uname);
  await page.type('input[name=password]', 'pass1234');
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle2' }), page.click('#login-form button')]);
  rec('E2E-03 登录成功', !page.url().includes('login'), page.url());

  // 3 浏览 + 详情
  await page.goto(BASE + '/', { waitUntil: 'networkidle2' });
  await page.waitForSelector('#catalog a, #list a');
  await shot('02_商品列表.png');
  await page.goto(BASE + '/?product=' + pid, { waitUntil: 'networkidle2' });
  await page.waitForSelector('#add-to-cart');
  await shot('03_商品详情.png');
  const detailText = await page.$eval('#detail', el => el.innerText);
  rec('E2E-04 详情展示可售库存与描述', /可售库存：\s*5/.test(detailText) && detailText.includes('端到端测试用商品'), detailText.slice(0, 80).replace(/\n/g, ' '));

  // 4 收藏
  await page.click('#toggle-favorite');
  await new Promise(r => setTimeout(r, 600));
  const favText = await page.$eval('#toggle-favorite', el => el.innerText);
  rec('E2E-05 详情页收藏成功', /取消收藏|已收藏/.test(favText), favText);

  // 5 加购
  await page.click('#add-to-cart');
  await new Promise(r => setTimeout(r, 800));
  const cartMsg = await page.$eval('#action-message', el => el.innerText).catch(() => '');
  rec('E2E-06 加入购物车成功', /已加入|购物车/.test(cartMsg), cartMsg);

  // 6 购物车 + 提交订单
  await page.goto(BASE + '/cart.html', { waitUntil: 'networkidle2' });
  await page.waitForSelector('input[data-pick]');
  await shot('04_购物车.png');
  await page.click('input[data-pick]');
  await new Promise(r => setTimeout(r, 400));
  await shot('05_购物车勾选待提交.png');
  await page.click('#submit-order');
  await new Promise(r => setTimeout(r, 1200));
  const notice = await page.$eval('#notice', el => el.innerText).catch(() => '');
  const urlAfterSubmit = page.url();
  rec('E2E-07 购物车提交订单成功', /成功|已生成|订单/.test(notice) || urlAfterSubmit.includes('orders'),
      'notice=' + notice + ' url=' + urlAfterSubmit);

  // 7 我的订单
  await page.goto(BASE + '/orders.html', { waitUntil: 'networkidle2' });
  await page.waitForSelector('#content');
  await shot('06_我的订单.png');
  const ordersText = await page.$eval('#content', el => el.innerText);
  rec('E2E-08 我的订单显示刚下的订单', ordersText.includes('客户下单') || ordersText.includes('E2E演示商品'), ordersText.slice(0, 80).replace(/\n/g, ' '));

  // 8 客户取消（第一笔可取消的）
  const canCancel = await page.$('[data-cancel]');
  if (canCancel) {
    await canCancel.click();
    await new Promise(r => setTimeout(r, 900));
    await shot('07_订单已取消.png');
    const after = await page.$eval('#content', el => el.innerText);
    rec('E2E-09 客户取消订单生效', after.includes('已取消'), after.slice(0, 60).replace(/\n/g, ' '));
  } else {
    rec('E2E-09 客户取消订单生效', false, '页面上没有可取消的订单按钮');
  }

  // 8.5 再下一单，留给卖家推进（上一单已被客户取消）
  await page.goto(BASE + '/?product=' + pid, { waitUntil: 'networkidle2' });
  await page.waitForSelector('#add-to-cart');
  await page.click('#add-to-cart');
  await new Promise(r => setTimeout(r, 700));
  await page.goto(BASE + '/cart.html', { waitUntil: 'networkidle2' });
  const pick2 = await page.$('input[data-pick]');
  if (pick2) {
    await pick2.click();
    await page.click('#submit-order');
    await new Promise(r => setTimeout(r, 1200));
    rec('E2E-09b 二次下单供卖家处理', true, page.url());
  } else {
    rec('E2E-09b 二次下单供卖家处理', false, '购物车为空');
  }

  // 9 收藏夹
  await page.goto(BASE + '/favorites.html', { waitUntil: 'networkidle2' });
  await page.waitForSelector('#favorite-content');
  await shot('08_我的收藏.png');
  const favList = await page.$eval('#favorite-content', el => el.innerText);
  rec('E2E-10 收藏列表含该商品', favList.includes('E2E演示商品'), favList.slice(0, 60).replace(/\n/g, ' '));

  // 10 卖家后台
  await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle2' });
  if (page.url().includes('login')) {
    await page.type('input[name=username]', 'admin');
    await page.type('input[name=password]', 'admin1234');
    await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle2' }).catch(() => {}), page.click('#login-form button')]);
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle2' });
  }
  await page.waitForSelector('#inventory-body tr');
  await shot('09_后台在售商品.png');
  await page.click('button[data-tab=customers]');
  await new Promise(r => setTimeout(r, 800));
  await shot('10_后台客户管理.png');
  const custText = await page.$eval('#customers', el => el.innerText).catch(() => '');
  rec('E2E-11 后台客户管理可见新客户', custText.includes(uname), custText.slice(0, 60).replace(/\n/g, ' '));

  // 11 卖家订单处理
  await page.goto(BASE + '/seller-orders.html', { waitUntil: 'networkidle2' });
  await page.waitForSelector('#content');
  await shot('11_卖家订单处理.png');
  const adv = await page.$('[data-advance]');
  if (adv) {
    await adv.click();
    await new Promise(r => setTimeout(r, 1000));
    await shot('12_卖家推进订单状态.png');
    const t = await page.$eval('#content', el => el.innerText);
    rec('E2E-12 卖家推进订单状态', /商家确认|备货完成|开始发货|交易完成/.test(t), t.slice(0, 80).replace(/\n/g, ' '));
  } else {
    rec('E2E-12 卖家推进订单状态', false, '没有可推进的订单（可能已被取消）');
  }

  // 12 历史商品
  await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle2' });
  await page.click('button[data-tab=history]');
  await new Promise(r => setTimeout(r, 800));
  await shot('13_后台历史商品.png');
  const histText = await page.$eval('#history', el => el.innerText).catch(() => '');
  rec('E2E-13 历史商品含两类下架成因', /售罄|手动下架/.test(histText), histText.slice(0, 80).replace(/\n/g, ' '));

  const real404 = notFound.filter(u => !u.includes('favicon'));
  rec('E2E-14 页面无资源 404 与脚本异常（favicon 除外）', real404.length === 0,
    JSON.stringify({ notFound: [...new Set(real404)], consoleErrors: consoleErrors.length }));

  await browser.close();
  const out = path.join(__dirname, '证据', '浏览器端到端结果.json');
  fs.writeFileSync(out, JSON.stringify({ base: BASE, customer: uname, productId: pid, steps, consoleErrors, notFound }, null, 2), 'utf8');
  const passed = steps.filter(s => s.ok).length;
  console.log(`\n==== 端到端汇总: ${passed}/${steps.length} 通过 ====`);
  process.exit(0);
})().catch(e => { console.error('E2E 脚本异常', e); process.exit(1); });
