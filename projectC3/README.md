# 在线购物系统 · 挑战需求 C（陈心冉）

本项目包含陈心冉负责的四条需求实现：**14207 推进订单状态**、**14208 卖家取消订单**、**14260 收货地址管理**、**14213 手动下架商品**。成果物见[陈心冉成果物](成果物/陈心冉成果物/挑战需求C/README.md)。

## 实现内容

- **推进订单状态**：卖家只受理已付款订单，按「已支付 → 商家确认 → 备货完成 → 开始发货」逐格推进；推进到「开始发货」必须录入物流单号，状态与物流字段原子写入；卖家不标记交易完成。
- **卖家取消订单**：交易完成前（含已发货）可取消；已付款段按原支付渠道写退款回执并回补库存，未付款段无退款；不填原因、不二次确认，重复取消不会二次回补。
- **收货地址管理**：完整地址簿增删改与设默认、注册地址入簿、删除默认自动落到最近一条；他人地址一律 404；新增买家页面 `/addresses.html`。
- **手动下架商品**：存在「已支付 / 商家确认 / 备货完成」订单时禁止下架与调库存 0；「开始发货」及之后不再阻塞；在途订单照常处理。

技术栈：Java 17 + Spring Boot 3.4 + SQLite，前端为原生 HTML / CSS / JavaScript。

## 运行

需要 JDK 17；Maven 无需单独安装，项目自带 Wrapper。

```powershell
.\mvnw.cmd package
java -jar target\shopping-system-1.0.0.jar
```

买家页面：<http://localhost:3000/>，地址簿：<http://localhost:3000/addresses.html>（需客户登录）；卖家后台订单页：<http://localhost:3000/seller-orders.html>（需后台登录）。初始卖家账号 `admin`，默认初始密码 `admin1234`，可通过 `DEFAULT_SELLER_PASSWORD` 在首次初始化时修改。

## 配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| SHOP_HOST | 127.0.0.1 | 监听地址 |
| SHOP_PORT | 3000 | 监听端口 |
| SHOP_DB_FILE | ./data/shop.db | SQLite 文件 |
| SHOP_UPLOAD_DIR | ./uploads | 商品图和描述插图 |
| SHOP_DATA_FILE | ./data.json | 旧 JSON 数据来源 |
| DEFAULT_SELLER_PASSWORD | admin1234 | 首次创建卖家账号时的密码 |

数据库与上传图片共同构成持久化数据，卖家会话在服务重启后失效。
