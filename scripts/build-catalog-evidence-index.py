"""生成真实页面截图的 Markdown 与离线 HTML 索引，核对成果链接。"""
from pathlib import Path
from html import escape
import re

root = Path(__file__).resolve().parent.parent
personal = root / "成果物/我的任务"
photos = sorted((personal / "截图").glob("*.jpg"))
descriptions = [
    "无商品时的明确空状态", "六件在售商品，冻结与售罄不在列表", "CLASSIC 匹配描述里的 Classic",
    "保留关键词并给出无结果提示", "选择相机二级类别", "库存、富媒体描述和三张商品图",
    "第二张图处于选中状态，三张商品图复用同一演示素材", "冻结夹具的直链只读详情，非真实交易发起",
    "下架商品详情不可访问", "修改前 Git HEAD 382a974 的单商品首页", "后台未登录跳转登录页",
    "新版多商品管理与状态", "旧版当前商品工作台及单商品发布限制", "一级类别与二级类别树",
    "重命名弹窗不提供换父类", "仅库存和类别的编辑弹窗", "相机库存由 3 改为 5、类别改为配件",
    "新版完整发布表单", "价格填 0 后服务端字段错误，输入保留", "无图片商品发布成功",
    "新发布玻璃杯库存由 2 改为 0，状态转售罄", "相机详情同步显示库存 5 与配件类别",
]
assert len(photos) == len(descriptions) == 22
intro = "全部图片来自隔离本地环境的真实浏览器截图。记录的是操作过程中的各时刻，因此发布、改挂前后的商品数量与类别会不同。旧版基线为 Git HEAD 382a974；冻结状态使用数据库验收夹具，未接入真实交易。商品照片沿用项目原有演示素材。\n\n"
markdown = "# 页面截图索引\n\n" + intro
markdown += "[打开离线截图画册](截图画册.html)\n\n"
for image, description in zip(photos, descriptions):
    markdown += f"## {image.stem}\n\n{description}。\n\n![{description}](截图/{image.name})\n\n"
(personal / "截图索引.md").write_text(markdown, encoding="utf-8")
html = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>商品模块截图画册</title><style>body{font:16px/1.7 Microsoft YaHei,sans-serif;margin:40px auto;max-width:1240px;background:#f8f5ef;color:#302c29}figure{margin:30px 0 60px}img{max-width:100%;height:auto;border:1px solid #ddd}h1{color:#a64f37}a{color:#a64f37}nav{display:flex;gap:12px;flex-wrap:wrap}figcaption{margin:12px 0}</style><h1>商品模块截图画册</h1><p>' + escape(intro) + '</p><nav>'
html += ''.join(f'<a href="#shot{i}">{i+1:02d}</a>' for i in range(len(photos))) + '</nav>'
for i, (image, description) in enumerate(zip(photos, descriptions)):
    html += f'<figure id="shot{i}"><h2>{escape(image.stem)}</h2><figcaption>{escape(description)}</figcaption><a href="截图/{escape(image.name)}"><img src="截图/{escape(image.name)}" alt="{escape(description)}" loading="lazy"></a></figure>'
(personal / "截图画册.html").write_text(html + '</html>', encoding="utf-8")
missing = []
for document in (root / "成果物").rglob("*.md"):
    if "公共资料" in document.parts:
        continue
    for destination in re.findall(r'\]\(([^)]+)\)', document.read_text(encoding="utf-8")):
        if "://" in destination or destination.startswith("#"):
            continue
        if not (document.parent / destination.split("#")[0]).exists():
            missing.append((str(document.relative_to(root)), destination))
if missing:
    raise SystemExit(f"Broken links: {missing}")
print(f"已生成 {len(photos)} 张截图的索引与离线画册；成果物文档链接检查通过")
