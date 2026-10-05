const express = require('express');
const multer = require('multer');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const app = express();
const PORT = Number(process.env.PORT || 3000);
const ROOT = __dirname;
const DATA_FILE = path.join(ROOT, 'data.json');
const UPLOAD_DIR = path.join(ROOT, 'uploads');
const SESSION_COOKIE = 'seller_session';
const sessions = new Map();
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

fs.mkdirSync(UPLOAD_DIR, { recursive: true });
app.use(express.json({ limit: '100kb' }));
app.use(express.urlencoded({ extended: false }));

const upload = multer({
  storage: multer.diskStorage({
    destination: UPLOAD_DIR,
    filename: (_req, file, cb) => {
      const ext = path.extname(file.originalname).toLowerCase();
      cb(null, `p-${Date.now()}-${crypto.randomBytes(6).toString('hex')}${ext}`);
    }
  }),
  limits: { fileSize: 5 * 1024 * 1024, files: 1 },
  fileFilter: (_req, file, cb) => {
    const accepted = ['image/jpeg', 'image/png'];
    cb(null, accepted.includes(file.mimetype));
  }
});

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(password, salt, 64).toString('hex');
  return `scrypt:${salt}:${hash}`;
}

function verifyPassword(password, encoded) {
  const [, salt, expected] = String(encoded || '').split(':');
  if (!salt || !expected) return false;
  const actual = crypto.scryptSync(password, salt, 64).toString('hex');
  return crypto.timingSafeEqual(Buffer.from(actual, 'hex'), Buffer.from(expected, 'hex'));
}

function createInitialDb() {
  return {
    seller: {
      username: 'admin',
      passwordHash: hashPassword(process.env.DEFAULT_SELLER_PASSWORD || 'admin1234'),
      passwordChangedAt: null
    },
    products: [],
    intents: [],
    nextProductId: 1,
    nextIntentId: 1
  };
}

function loadDb() {
  if (!fs.existsSync(DATA_FILE)) {
    const db = createInitialDb();
    saveDb(db);
    return db;
  }
  try {
    return JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
  } catch (error) {
    throw new Error(`无法读取 data.json：${error.message}`);
  }
}

let db = loadDb();

function saveDb(nextDb = db) {
  const tmp = `${DATA_FILE}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(nextDb, null, 2), 'utf8');
  fs.renameSync(tmp, DATA_FILE);
}

function now() {
  return new Date().toISOString();
}

function sendError(res, status, error) {
  return res.status(status).json({ error });
}

function currentProduct() {
  return db.products.find((product) => product.status !== 'sold');
}

function isBuyable(product) {
  return product && (product.status === 'active' || product.status === 'resumed');
}

function currentQueue(productId) {
  return db.intents
    .filter((intent) => intent.productId === productId && intent.stage === 'queued')
    .sort((a, b) => new Date(a.submittedAt) - new Date(b.submittedAt));
}

function positionOf(intent) {
  return currentQueue(intent.productId).findIndex((item) => item.id === intent.id) + 1;
}

function generatePasscode() {
  let code;
  do {
    code = Array.from({ length: 8 }, () => CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)]).join('');
  } while (db.intents.some((intent) => intent.passcode === code));
  return code;
}

function validatePerson(name, phone) {
  return typeof name === 'string' && name.trim() && typeof phone === 'string' && phone.trim();
}

function parsePrice(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return null;
  const text = String(value).trim();
  if (!/^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(text)) return null;
  const cents = Math.round(Number(text) * 100);
  return Number.isSafeInteger(cents) && cents > 0 ? cents : null;
}

function requireAuth(req, res, next) {
  const token = req.headers.cookie?.split(';').map((part) => part.trim())
    .find((part) => part.startsWith(`${SESSION_COOKIE}=`))?.split('=')[1];
  if (!token || !sessions.has(token)) return sendError(res, 401, '未登录');
  next();
}

function activeIntentByCode(passcode) {
  const intent = db.intents.find((item) => item.passcode === String(passcode || '').trim().toUpperCase());
  const product = intent && db.products.find((item) => item.id === intent.productId);
  if (!intent || !product || intent.stage === 'closed' || product.status === 'sold') return null;
  return { intent, product };
}

function startNextTrade(product) {
  const next = currentQueue(product.id)[0];
  if (!next) {
    product.status = 'resumed';
    product.tradingIntentId = null;
    return null;
  }
  product.status = 'frozen';
  product.frozenAt = now();
  product.tradingIntentId = next.id;
  next.stage = 'trading';
  return next;
}

app.use(express.static(path.join(ROOT, 'public')));
app.use('/uploads', express.static(UPLOAD_DIR));

app.get('/api/product', (_req, res) => {
  const product = currentProduct();
  if (!product) return res.json({ product: null });
  res.json({
    product: {
      id: product.id, name: product.name, description: product.description,
      image: product.image, priceCents: product.priceCents,
      status: product.status, buyable: isBuyable(product)
    }
  });
});

app.post('/api/intents', (req, res) => {
  const product = currentProduct();
  if (!isBuyable(product)) return sendError(res, 409, '商品当前不接受购买意向');
  const { name, phone } = req.body;
  if (!validatePerson(name, phone)) return sendError(res, 400, '姓名和联系电话均为必填');
  const intent = {
    id: db.nextIntentId++, productId: product.id, name: name.trim(), phone: phone.trim(),
    passcode: generatePasscode(), submittedAt: now(), stage: 'queued', outcome: null
  };
  db.intents.push(intent);
  saveDb();
  res.status(201).json({ passcode: intent.passcode });
});

app.post('/api/intents/query', (req, res) => {
  const result = activeIntentByCode(req.body.passcode);
  if (!result) return sendError(res, 403, '口令码无效或已失效');
  res.json({ stage: result.intent.stage, position: result.intent.stage === 'queued' ? positionOf(result.intent) : null });
});

app.put('/api/intents', (req, res) => {
  const result = activeIntentByCode(req.body.passcode);
  if (!result) return sendError(res, 403, '口令码无效或已失效');
  if (!validatePerson(req.body.name, req.body.phone)) return sendError(res, 400, '姓名和联系电话均为必填');
  result.intent.name = req.body.name.trim();
  result.intent.phone = req.body.phone.trim();
  saveDb();
  res.json({ ok: true });
});

app.post('/api/intents/cancel', (req, res) => {
  const result = activeIntentByCode(req.body.passcode);
  if (!result) return sendError(res, 403, '口令码无效或已失效');
  result.intent.stage = 'closed';
  result.intent.outcome = 'cancelled';
  saveDb();
  res.json({ ok: true });
});

app.post('/api/auth/login', (req, res) => {
  const { username, password } = req.body;
  if (username !== db.seller.username || typeof password !== 'string' || !verifyPassword(password, db.seller.passwordHash)) {
    return sendError(res, 401, '用户名或密码错误');
  }
  const token = crypto.randomBytes(32).toString('hex');
  sessions.set(token, true);
  res.setHeader('Set-Cookie', `${SESSION_COOKIE}=${token}; HttpOnly; SameSite=Strict; Path=/`);
  res.json({ ok: true });
});

app.post('/api/auth/password', requireAuth, (req, res) => {
  const { oldPassword, newPassword } = req.body;
  if (!verifyPassword(oldPassword, db.seller.passwordHash)) return sendError(res, 400, '原密码错误');
  if (typeof newPassword !== 'string' || newPassword.length < 8 || !/[A-Za-z]/.test(newPassword) || !/\d/.test(newPassword)) {
    return sendError(res, 400, '新密码至少 8 位且必须同时包含字母和数字');
  }
  db.seller.passwordHash = hashPassword(newPassword);
  db.seller.passwordChangedAt = now();
  saveDb();
  res.json({ ok: true });
});

app.post('/api/products', requireAuth, upload.single('image'), (req, res) => {
  const existing = currentProduct();
  if (existing) return sendError(res, 409, '当前已有未售出商品');
  if (!req.file) return sendError(res, 400, '必须上传 JPG 或 PNG 图片');
  const { name, description } = req.body;
  const priceCents = parsePrice(req.body.price);
  if (!name?.trim() || !description?.trim() || priceCents === null) {
    fs.rmSync(req.file.path, { force: true });
    return sendError(res, 400, '商品名称、描述和有效价格均为必填');
  }
  const product = {
    id: db.nextProductId++, name: name.trim(), description: description.trim(),
    image: `/uploads/${req.file.filename}`, priceCents, status: 'active',
    tradingIntentId: null, createdAt: now(), frozenAt: null, result: null, resultAt: null
  };
  db.products.push(product);
  saveDb();
  res.status(201).json({ id: product.id });
});

app.get('/api/admin/product', requireAuth, (_req, res) => {
  const product = currentProduct();
  if (!product) return res.json({ product: null, tradingIntent: null });
  const tradingIntent = db.intents.find((intent) => intent.id === product.tradingIntentId) || null;
  res.json({ product, tradingIntent });
});

app.get('/api/admin/intents', requireAuth, (_req, res) => {
  const product = currentProduct();
  if (!product) return res.json({ intents: [] });
  res.json({
    intents: db.intents.filter((intent) => intent.productId === product.id && intent.stage !== 'closed')
      .sort((a, b) => new Date(a.submittedAt) - new Date(b.submittedAt))
      .map((intent) => ({ ...intent, position: intent.stage === 'queued' ? positionOf(intent) : null }))
  });
});

app.post('/api/admin/freeze', requireAuth, (_req, res) => {
  const product = currentProduct();
  if (!product || !isBuyable(product)) return sendError(res, 409, '当前商品不能手动冻结');
  product.status = 'frozen';
  product.tradingIntentId = null;
  product.frozenAt = now();
  saveDb();
  res.json({ ok: true });
});

app.post('/api/admin/unfreeze', requireAuth, (_req, res) => {
  const product = currentProduct();
  if (!product || product.status !== 'frozen' || product.tradingIntentId !== null) return sendError(res, 409, '当前商品不能手动解冻');
  product.status = 'active';
  saveDb();
  res.json({ ok: true });
});

app.post('/api/admin/trade/start', requireAuth, (_req, res) => {
  const product = currentProduct();
  if (!product || !isBuyable(product)) return sendError(res, 409, '商品当前不能开始交易');
  const intent = startNextTrade(product);
  if (!intent) return sendError(res, 409, '当前没有排队中的购买意向');
  saveDb();
  res.json({ intentId: intent.id, name: intent.name, phone: intent.phone });
});

app.post('/api/admin/trade/success', requireAuth, (_req, res) => {
  const product = currentProduct();
  const trading = product && db.intents.find((intent) => intent.id === product.tradingIntentId && intent.stage === 'trading');
  if (!trading) return sendError(res, 409, '当前没有交易中的意向');
  trading.stage = 'closed';
  trading.outcome = 'success';
  db.intents.filter((intent) => intent.productId === product.id && intent.stage !== 'closed').forEach((intent) => {
    intent.stage = 'closed';
    intent.outcome = 'unsold';
  });
  product.status = 'sold';
  product.result = 'success';
  product.resultAt = now();
  product.tradingIntentId = null;
  saveDb();
  res.json({ ok: true });
});

app.post('/api/admin/trade/fail', requireAuth, (req, res) => {
  const product = currentProduct();
  const trading = product && db.intents.find((intent) => intent.id === product.tradingIntentId && intent.stage === 'trading');
  if (!trading) return sendError(res, 409, '当前没有交易中的意向');
  const disposition = req.body.disposition;
  if (!['void', 'requeue'].includes(disposition)) return sendError(res, 400, '无效的失败处置方式');
  let newPasscode = null;
  if (disposition === 'requeue') {
    trading.stage = 'closed';
    trading.outcome = 'requeued';
    const replacement = {
      id: db.nextIntentId++, productId: product.id, name: trading.name, phone: trading.phone,
      passcode: generatePasscode(), submittedAt: now(), stage: 'queued', outcome: null
    };
    db.intents.push(replacement);
    newPasscode = replacement.passcode;
  } else {
    trading.stage = 'closed';
    trading.outcome = 'voided';
  }
  product.resultAt = now();
  product.tradingIntentId = null;
  const next = startNextTrade(product);
  saveDb();
  res.json({ ok: true, newPasscode, nextIntentId: next?.id || null });
});

app.get('/api/admin/history', requireAuth, (_req, res) => {
  res.json({
    products: db.products.filter((product) => product.status === 'sold').map((product) => ({
      ...product,
      intents: db.intents.filter((intent) => intent.productId === product.id)
    }))
  });
});

app.use((error, _req, res, _next) => {
  if (error instanceof multer.MulterError || error.message === 'LIMIT_FILE_SIZE') return sendError(res, 400, '图片必须为 JPG/PNG 且不超过 5MB');
  console.error(error);
  return sendError(res, 500, '服务器内部错误');
});

app.listen(PORT, () => {
  console.log(`在线购物系统已启动：http://localhost:${PORT}`);
  console.log('卖家后台：http://localhost:%d/admin.html；默认账号：admin / admin1234', PORT);
});
