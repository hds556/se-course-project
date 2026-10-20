# Java 重构记录

> 分支说明：`main` 分支为原 Python（Flask）后端版本；`java_rebuild` 分支在其基础上将后端重构为 Java（Spring Boot），对应本地 `se-course-project-v5` 目录。
>
> 重构提交：`df09696`（junhaoFan5，2026-10-08），"重构后端为 Spring Boot Java"。

## 一、重构目标

1. 将后端语言由 **Python 3.12 + Flask + Pillow** 重构为 **Java 17 + Spring Boot 3.4 + Maven**。
2. **保持对外行为完全不变**：前端页面、CSS、JS 逻辑零改动；`/api/*` 接口路径、请求/响应 JSON 结构、HTTP 状态码（含价格非法返回 400 等）与原版一致。
3. 保留原有运行特性：默认监听 `127.0.0.1:3000`、默认卖家 `admin`、继续使用 `data.json` + `uploads/` 本地文件存储（无数据库）、拷走源码即可运行。

## 二、重构前后技术栈对比

| 类别 | 重构前（main） | 重构后（java_rebuild） |
|---|---|---|
| 语言/运行时 | Python 3.12 | Java 17 |
| Web 框架 | Flask | Spring Boot 3.4（spring-boot-starter-web） |
| 构建/依赖 | requirements.txt + pip | Maven（pom.xml），含 mvnw 包装器 |
| 数据存储 | `data.json`（JSON 文件 + 原子写入）+ `uploads/` 图片目录 | **不变**：沿用 `data.json` + `uploads/`，首次启动自动创建，无数据库 |
| 图片处理 | Pillow（PIL 校验真实格式） | 基于 Spring 的 multipart + 文件头校验 |
| 密码哈希 | hashlib.scrypt（兼容 Node scryptSync） | spring-security-crypto `SCryptPasswordEncoder`（v5_8 默认参数） |
| 静态资源 | Flask 静态路由 | 控制器路由 + `public/` 打包进 jar（`BOOT-INF/classes/static/`） |
| 并发控制 | `threading.RLock` | Spring 单例 Bean + 事务（`@Transactional`） |

## 三、代码结构映射

重构后包结构：`com.coursewise.shopping`

```
src/main/java/com/coursewise/shopping/
├── ShoppingApplication.java        # 应用入口（对应 app.py 的 main/create_app）
├── ShoppingController.java         # 路由层（对应 app.py 的全部 @app.route）
└── ShoppingService.java            # 业务逻辑（对应 queue/transition/price_cents/读写 data.json 等）
```

原 `app.py`（约单文件近千行）拆分为 控制器 / 服务 两层，符合典型 Spring 分层架构。

## 四、接口保持不变

以下 API 与 main 分支完全一致（路径、方法、JSON 结构均未改动）：

- 买家端：`GET /api/product`、`POST /api/intents`（返回 8 位口令码）、`POST /api/intents/query`、`PUT /api/intents`、`POST /api/intents/cancel`
- 认证：`POST /api/auth/login`（HttpOnly + SameSite=Strict 会话 Cookie）、`POST /api/auth/password`
- 卖家端：`POST /api/products`（multipart 上架）、`GET /api/admin/product`、`GET /api/admin/intents`、`POST /api/admin/freeze`、`POST /api/admin/unfreeze`、`POST /api/admin/trade/start`、`POST /api/admin/trade/success`、`POST /api/admin/trade/fail`（void/requeue）、`POST /api/admin/trade/advance`（递补）、`GET /api/admin/history`
- 页面与静态文件：`/`（买家页）、`/admin.html`、`/style.css`、`/app.js`、`/admin.js`、`/uploads/<file>`

业务规则同步保留：

- 同一时间仅一件未售出商品；卖家从队首开始交易，商品进入冻结状态
- 口令码 8 位随机、去除易混淆字符，仅展示一次；交易成功后全部失效
- 价格以"分"为整数存储，上限 2^53−1 分（避免 JS 浮点精度问题），非法输入返回 HTTP 400
- 图片限一张 JPG/PNG、≤ 5MB；multipart 总请求限 10MB

## 五、关键实现点

### 1. 密码哈希兼容

原 Python 端使用 `hashlib.scrypt`（n=16384, dklen=64），重构后改用 Spring Security 的 `SCryptPasswordEncoder`（`defaultsForSpringSecurity_v5_8`），哈希算法与参数一致，已有 `data.json` 中的密码哈希可直接沿用。

### 2. 静态资源打包

`pom.xml` 中将根目录 `public/` 作为额外 resource 打进 jar 的 `BOOT-INF/classes/static/`，使打包产物（`target/shopping-system-1.0.0.jar`）不依赖外部 public 目录即可独立运行。

### 3. 环境变量与命名规避

沿用原环境变量语义但统一加 `SHOP_` 前缀，避免 `PORT`/`HOST` 等裸变量名被某些宿主环境注入的同义变量经 Spring 宽松绑定意外覆盖：

- `SHOP_HOST` / `SHOP_PORT`（默认 `127.0.0.1:3000`）
- `SHOP_DATA_FILE`（data.json 路径）、`SHOP_UPLOAD_DIR`（图片目录）、`DEFAULT_SELLER_PASSWORD`

### 4. 编码

`pom.xml` 显式设置 `project.build.sourceEncoding=UTF-8`，避免中文在 Windows GBK 默认编码下乱码。

## 六、构建与运行方式变化

```powershell
# 开发模式
mvn spring-boot:run

# 打包运行
mvn clean package
java -jar target\shopping-system-1.0.0.jar
```

- 前端文件仍为原生 HTML/CSS/JS，无需构建；开发模式修改 `public/` 后刷新即生效，Java 代码改动由 DevTools 自动重启。
- 访问地址不变：买家页 `http://localhost:3000/`，卖家后台 `http://localhost:3000/admin.html`。
- 首次启动自动创建 `data.json` 和 `uploads/`，原有数据文件可直接沿用，数据重启后保留。

## 七、重构验证清单

- [x] 11 个买家/卖家 API 路径与 JSON 响应结构与 main 分支一致
- [x] 原有 `data.json` / `uploads/` 数据无需任何转换即可继续使用
- [x] 价格越界/非法输入返回 HTTP 400（对应 DEF-001 缺陷修复在重构版中保持）
- [x] 图片格式（JPG/PNG）与大小（≤5MB）校验行为一致
- [x] 商品状态机（active/resumed/frozen/sold）与递补、重新排队逻辑一致
- [x] 默认卖家 `admin` / `admin1234`（可用 `DEFAULT_SELLER_PASSWORD` 覆盖）
