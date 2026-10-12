# 在线购物系统 MVP

这是一个一次只展示一件商品的简易购物意向系统。买家无需注册即可提交意向并凭口令码查看进度；卖家登录后发布商品、按顺序处理交易。交易在线下完成，系统不处理支付或物流。

## Java 重构版

后端已重构为 Java 17 + Spring Boot，保留原有前端页面、CSS 样式、JavaScript 逻辑、`/api/*` 接口路径和 JSON 响应结构。数据存储从本地 `data.json` 换成 **SQLite 数据库**（单文件、免安装、免账号密码），原有 `data.json` 会在首次启动时自动迁移进数据库。

## 技术环境

- Java 17（唯一需要预装的东西）
- Spring Boot 3.4
- Maven（无需单独安装，项目自带 Maven Wrapper `mvnw` / `mvnw.cmd`）
- Spring Data JPA + Hibernate 6
- 数据库：SQLite（`org.xerial:sqlite-jdbc` + `hibernate-community-dialects`）
- 前端：原生 HTML、CSS、JavaScript，无需前端构建工具

> 如果你用 Navicat 看数据：新建连接选 **SQLite**，数据库文件指向项目根目录下的 `data/shop.db` 即可。

## 安装与运行

### 前置条件

只需要 **JDK 17**。在命令行确认：

```powershell
java -version
```

### 启动

在项目根目录执行（Windows PowerShell）：

```powershell
.\mvnw.cmd spring-boot:run
```

Mac / Linux：

```bash
./mvnw spring-boot:run
```

首次执行会自动下载 Maven 与依赖（需要联网，仅第一次），之后可离线运行。开发模式下修改 `public/` 里的 HTML、CSS、JavaScript 后刷新浏览器即可；修改 Java 后重新执行该命令。

打包成可提交 / 部署的 `jar`：

```powershell
.\mvnw.cmd clean package
java -jar target\shopping-system-1.0.0.jar
```

启动后访问：

- 买家页面：<http://localhost:3000/>
- 卖家后台：<http://localhost:3000/admin.html>

默认卖家账号为 `admin`，初始密码为 `admin1234`。首次登录后请到后台修改密码。

## 数据库

首次启动会自动在项目根目录创建：

```
data/shop.db        SQLite 数据库文件（所有业务数据）
uploads/            商品图片
```

三张表：

| 表名 | 说明 |
| --- | --- |
| `seller` | 卖家账号（单条记录），密码只存哈希 |
| `product` | 商品，含状态、交易中的意向 id、交易结果 |
| `intent` | 购买意向，含口令码、阶段与结局 |

数据全部落在这个 `.db` 文件里，重启后保留，拷贝文件即可整体迁移。

## 环境变量

全部可选，不配置就用默认值：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SHOP_HOST` | `127.0.0.1` | 监听地址 |
| `SHOP_PORT` | `3000` | 监听端口 |
| `SHOP_DB_FILE` | `./data/shop.db` | SQLite 数据库文件路径 |
| `SHOP_UPLOAD_DIR` | `./uploads` | 图片上传目录 |
| `SHOP_DATA_FILE` | `./data.json` | 旧数据文件路径，仅用于首次迁移 |
| `DEFAULT_SELLER_PASSWORD` | `admin1234` | 无卖家账号时创建的初始密码 |

## 业务流程

1. 卖家发布商品；系统同一时间只允许一件未售出商品。
2. 买家填写姓名和电话提交购买意向，获得仅展示一次的 8 位口令码。相同联系方式的多次提交仍是独立意向。
3. 卖家从队首开始交易，商品随之暂停接收新意向。交易失败时可将当前意向作废或重新排到队尾；系统自动递补下一位。队列为空时，商品恢复在售。
4. 买家可凭口令码查询排队进度、修改联系信息或撤销意向。若交易中的买家撤销，卖家需在后台确认处理，系统再递补下一位。
5. 交易成功后商品进入历史记录，其余未成交意向结案，全部相关口令码失效。

上传图片限一张 JPG/PNG，大小不超过 5 MB。
商品价格必须大于 0、最多两位小数，最高为 `90071992547409.91` 元（以分保存时不超过 JavaScript 安全整数上限）。超长或超出范围的价格会返回 HTTP 400 JSON 错误，不会保存商品或图片。

## 从旧版本迁移

如果项目根目录存在旧的 `data.json`，且数据库三张表都为空，首次启动会自动导入其中的卖家、商品和意向数据（含口令码与历史结局），随后即可删除 `data.json`。若数据库已有数据，则跳过迁移，不会覆盖。
