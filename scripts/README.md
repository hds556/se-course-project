# 本地演示辅助脚本

这些脚本只用于隔离验收，正式运行按项目根目录 README 操作。

- `prepare-catalog-demo.mjs`：对固定的本机 3100 新版、3101 旧版演示环境创建数据，要求新版列表为空，拒绝重复灌入。演示数据库与上传目录须先配置到 `target/`，不要把正式服务配置到这些演示端口。图片复用项目原有素材，副本位于 `fixtures/catalog-demo.jpg`。
- `set-catalog-demo-state.py`：仅修改固定路径 `target/catalog-demo/data/shop.db`，建立冻结商品状态夹具，不调用真实交易接口。
- `build-catalog-evidence-index.py`：根据已保存的 22 张真实截图生成 Markdown 索引和离线画册，并检查成果物文档的本地链接。

旧版源码基线为 Git 提交 `382a974`，本次从该提交单独打包运行。上述脚本不启动服务、不修改正式库、不修改禅道任务状态。
