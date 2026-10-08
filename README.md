# 在线购物系统 MVP

这是一个一次只展示一件商品的简易购物意向系统。买家无需注册即可提交意向并凭口令码查看进度；卖家登录后发布商品、按顺序处理交易。交易在线下完成，系统不处理支付或物流。

## Java 重构版

后端已重构为 Java 17 + Spring Boot，保留原有前端页面、CSS 样式、JavaScript 逻辑、`/api/*` 接口路径和 JSON 响应结构。数据仍使用本地 `data.json` 与 `uploads/`，原有数据可以继续使用。

## 技术环境

- Java 17
- Spring Boot 3.4
- Maven
- 前端：原生 HTML、CSS、JavaScript，无需前端构建工具
- 数据：本地 `data.json` 与 `uploads/`，无需数据库

## 安装与运行

在项目根目录执行。Windows PowerShell：

```powershell
mvn clean package
java -jar target\shopping-system-1.0.0.jar
```

开发时可以使用：

```powershell
mvn spring-boot:run
```

开发模式下，修改 `public/` 里的 HTML、CSS、JavaScript 后刷新浏览器即可；修改 Java 后需要让 IDE 重新编译并由 Spring Boot DevTools 自动重启，或者手动停止后再次执行 `mvn spring-boot:run`。只有需要生成可提交或部署的 `jar` 时，才执行 `mvn clean package`。

启动后访问：

- 买家页面：<http://localhost:3000/>
- 卖家后台：<http://localhost:3000/admin.html>

默认卖家账号为 `admin`，初始密码为 `admin1234`。首次启动前可设置环境变量 `DEFAULT_SELLER_PASSWORD` 更换初始密码；首次登录后也可在后台修改密码。服务默认仅监听本机 `127.0.0.1:3000`，可通过 `HOST`、`PORT` 环境变量调整。

首次启动会创建 `data.json` 和 `uploads/`。可通过 `HOST`、`PORT`、`SHOP_DATA_FILE` 和 `SHOP_UPLOAD_DIR` 环境变量配置监听地址与存储位置。

## 业务流程

1. 卖家发布商品；系统同一时间只允许一件未售出商品。
2. 买家填写姓名和电话提交购买意向，获得仅展示一次的 8 位口令码。相同联系方式的多次提交仍是独立意向。
3. 卖家从队首开始交易，商品随之暂停接收新意向。交易失败时可将当前意向作废或重新排到队尾；系统自动递补下一位。队列为空时，商品恢复在售。
4. 买家可凭口令码查询排队进度、修改联系信息或撤销意向。若交易中的买家撤销，卖家需在后台确认处理，系统再递补下一位。
5. 交易成功后商品进入历史记录，其余未成交意向结案，全部相关口令码失效。

所有数据写入本地文件，重启后保留。上传图片限一张 JPG/PNG，大小不超过 5 MB。
商品价格必须大于 0、最多两位小数，最高为 `90071992547409.91` 元（以分保存时不超过 JavaScript 安全整数上限）。超长或超出范围的价格会返回 HTTP 400 JSON 错误，不会保存商品或图片。
