# 浏览器验证脚本复现

从仓库根目录运行。需要Node.js、Playwright模块和本机Edge，以及Python标准库；这些属于验证工具环境，不是应用运行依赖。截图与JSON会写入个人成果物目录，复现时建议先备份原证据。

1. `./mvnw.cmd package`，准备JDK17。
2. 创建target/projectc-work目录，用单独终端启动隔离服务：

```powershell
java -jar target/shopping-system-1.0.0.jar --server.port=3220 --spring.datasource.url=jdbc:sqlite:./target/projectc-work/demo.db --shopping.upload-dir=./target/projectc-work/uploads --shopping.legacy-data-file=./target/projectc-work/absent.json --shopping.payment.timeout-scan-initial-delay-ms=3600000
```

3. 用可解析Playwright包的Node环境，依次运行：

```powershell
node 成果物/戚航瑞成果物/挑战需求C/验证脚本/browser.mjs
python 成果物/戚航瑞成果物/挑战需求C/验证脚本/browser-fixtures.py
node 成果物/戚航瑞成果物/挑战需求C/验证脚本/browser-more.mjs
```

中间Python步骤只对隔离demo.db构造发货与地址修改夹具，不允许替换为正式数据库路径；这部分用于验证B的展示与快照，不是C的卖家发货或地址管理端到端验收。脚本使用随机测试客户，首次初始化卖家默认为admin/admin1234。
核心自动化无需浏览器：`./mvnw.cmd -Dtest=OrderCIntegrationTest test`。
