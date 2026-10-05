# 在线购物系统 MVP

实现依据 `se-course-project.wiki/Design.md`、`SRS.md` 和 `Requirements‐Analysis.md`。

## 环境与安装

- Node.js 22（Node.js 18 以上也可运行）
- npm（随 Node.js 安装）
- 不需要数据库或其他外部服务

在本目录执行：

```powershell
npm install
npm start
```

浏览器访问：

- 买家端：http://localhost:3000/
- 卖家后台：http://localhost:3000/admin.html

首次启动会自动创建 `data.json` 和 `uploads/`。默认卖家账号为 `admin`，默认密码为 `admin1234`；首次启动前可通过环境变量 `DEFAULT_SELLER_PASSWORD` 指定初始密码。

## 开发模式

```powershell
npm run dev
```

开发模式使用 Node.js 原生 watch，修改 `server.js` 后会自动重启。

## 业务流程

买家提交意向后获得一次性口令码；卖家从队首开始交易，可标记成功，或选择作废/重新排队。交易失败时会自动递补下一位；队列为空时商品恢复在售。所有状态和口令码写入 `data.json`，图片写入 `uploads/`。
