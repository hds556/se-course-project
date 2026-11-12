# -*- coding: utf-8 -*-
"""
挑战需求B迭代汇报 PPT（三页）

版式与同组开发 A 的汇报保持一致：白底、单一强调色、固定字号层级、左对齐栅格，
不使用装饰性图形与动画，保证投影与打印时文字清晰、结构可读。

第 1 页 我负责的部分
第 2 页 实现过程（对前端 / 后端 / 数据库做了什么）
第 3 页 成果

第 3 页会从 ../截图/ 里取用截图：若截图尚未补齐，则自动退回纯文字版式（同样完整可讲），
补齐后重新执行本脚本即可把图嵌进去，无需改代码。
"""
import os

from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn

BASE = os.path.dirname(os.path.abspath(__file__))
SHOT = os.path.join(BASE, '..', '截图')
OUT = os.path.join(BASE, '挑战需求B迭代汇报-陈心冉.pptx')

FONT = 'Microsoft YaHei'
INK = RGBColor(0x1F, 0x1B, 0x17)
MUTED = RGBColor(0x6B, 0x61, 0x56)
ACCENT = RGBColor(0xB4, 0x47, 0x2A)
LINE = RGBColor(0xDE, 0xD6, 0xCB)
TINT = RGBColor(0xFA, 0xF6, 0xF1)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)

W_IN, H_IN = 10.0, 5.625      # 16:9
MARGIN = 0.6
PAGES = 3

prs = Presentation()
prs.slide_width = Inches(W_IN)
prs.slide_height = Inches(H_IN)
BLANK = prs.slide_layouts[6]


def set_font(run, size, bold=False, color=INK):
    """统一字体：同时写入 latin / east-asian 字体名，避免中文被替换。"""
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = color
    run.font.name = FONT
    rpr = run._r.get_or_add_rPr()
    for tag in ('a:latin', 'a:ea', 'a:cs'):
        node = rpr.find(qn(tag))
        if node is None:
            node = rpr.makeelement(qn(tag), {})
            rpr.append(node)
        node.set('typeface', FONT)


def textbox(slide, x, y, w, h, text, size, bold=False, color=INK,
            align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, spacing=1.0, space_after=0):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    frame = box.text_frame
    frame.word_wrap = True
    frame.vertical_anchor = anchor
    frame.margin_left = frame.margin_right = 0
    frame.margin_top = frame.margin_bottom = 0
    para = frame.paragraphs[0]
    para.alignment = align
    para.line_spacing = spacing
    para.space_after = Pt(space_after)
    run = para.add_run()
    run.text = text
    set_font(run, size, bold, color)
    return box


def rich_box(slide, x, y, w, h, segments, size=10, spacing=1.15, space_after=7):
    """多段落富文本：segments 为若干段落，每段是 [(文本, 是否加粗, 颜色), ...]。"""
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    frame = box.text_frame
    frame.word_wrap = True
    frame.vertical_anchor = MSO_ANCHOR.TOP
    frame.margin_left = frame.margin_right = 0
    frame.margin_top = frame.margin_bottom = 0
    for index, segment in enumerate(segments):
        para = frame.paragraphs[0] if index == 0 else frame.add_paragraph()
        para.alignment = PP_ALIGN.LEFT
        para.line_spacing = spacing
        para.space_after = Pt(space_after)
        for text, bold, color in segment:
            run = para.add_run()
            run.text = text
            set_font(run, size, bold, color)
    return box


def header(slide, title, subtitle):
    slide.background.fill.solid()
    slide.background.fill.fore_color.rgb = WHITE
    textbox(slide, MARGIN, 0.34, W_IN - MARGIN * 2, 0.46, title, 25, True, INK)
    textbox(slide, MARGIN, 0.82, W_IN - MARGIN * 2, 0.26, subtitle, 11, False, MUTED)
    rule = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(MARGIN), Inches(1.16),
                                  Inches(W_IN - MARGIN * 2), Pt(1.6))
    rule.fill.solid()
    rule.fill.fore_color.rgb = ACCENT
    rule.line.fill.background()
    rule.shadow.inherit = False


def footer(slide, page):
    textbox(slide, MARGIN, 5.16, 4, 0.24, '挑战需求B迭代 · 陈心冉', 9, False, MUTED)
    textbox(slide, W_IN - MARGIN - 1.0, 5.16, 1.0, 0.24, f'{page} / {PAGES}', 9, False, MUTED,
            align=PP_ALIGN.RIGHT)


def panel(slide, x, y, w, h, fill=TINT):
    shape = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    shape.fill.solid()
    shape.fill.fore_color.rgb = fill
    shape.line.color.rgb = LINE
    shape.line.width = Pt(0.75)
    shape.shadow.inherit = False
    return shape


# ==================== 第 1 页：我负责的部分 ====================
s1 = prs.slides.add_slide(BLANK)
header(s1, '我负责的部分：卖家端订单推进 · 取消 · 两个查询入口',
       '需求条目 14207 / 14208 / 14209 / 14214｜范围限定在卖家侧，订单的创建与客户取消由开发 A 负责')

ROWS = [
    ('14207', '推进订单状态',
     '五状态顺序推进，一次一格；不可跳过、不可回退，交易完成不可撤销'),
    ('14208', '卖家取消订单',
     '交易完成前（含已发货）可取消；按明细回补库存，售罄下架商品自动恢复在售'),
    ('14209', '查看商品订单记录',
     '按商品维度列出涉及该商品的全部订单；带订单笔数、累计件数、当前库存与状态'),
    ('14214', '查看客户购买历史',
     '按客户维度列出该客户的全部订单，可在同一界面直接推进或取消'),
]

top = 1.36
for code, name, detail in ROWS:
    panel(s1, MARGIN, top, 1.02, 0.56)
    textbox(s1, MARGIN, top, 1.02, 0.56, code, 12, True, ACCENT,
            align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    textbox(s1, MARGIN + 1.18, top, 1.52, 0.56, name, 13, True, INK,
            anchor=MSO_ANCHOR.MIDDLE)
    textbox(s1, MARGIN + 2.76, top, W_IN - MARGIN * 2 - 2.76, 0.56, detail, 10.5, False, MUTED,
            anchor=MSO_ANCHOR.MIDDLE)
    top += 0.68

rich_box(s1, MARGIN, 4.50, W_IN - MARGIN * 2, 0.52,
         [[('与其他人怎么分开：', True, INK),
           ('订单表与明细表由开发 A 建表，本次只写 order.status 一个字段；'
            '库存回补复用既有的商品更新接口，不新增库存写入口（库存归开发 D）。', False, MUTED)]], size=10)
footer(s1, 1)

# ==================== 第 2 页：实现过程 ====================
s2 = prs.slides.add_slide(BLANK)
header(s2, '实现过程：前端 · 后端 · 数据库',
       '新增卖家订单域；状态推进用「条件更新」保证不跳格、不重复推进')

COLUMNS = [
    ('数据库', MARGIN, 2.88, [
        ('不新建表', '复用开发 A 的 orders / order_item，只写 status'),
        ('条件更新', 'where id=? and status=?，影响 0 行即判定已被改动'),
        ('显式推进表', 'placed → confirmed → packed → shipped → completed'),
        ('商品维度子查询', '按 order_item.product_id 取订单，避免 join 重复'),
        ('聚合查询', '涉及订单笔数、累计下单件数'),
    ]),
    ('后端', MARGIN + 2.98, 2.88, [
        ('新增 seller 模块', 'Service / Controller / 两个仓储'),
        ('14207 推进', '一次一格；跳过、回退、原地、终态一律 409'),
        ('14208 取消', '先改状态、成功后才回补，只回补一次'),
        ('14209 / 14214', '一个资源两个过滤参数，返回结构一致'),
        ('错误同构', '复用订单域异常，响应与买家侧一致'),
    ]),
    ('前端', MARGIN + 5.96, W_IN - MARGIN * 2 - 5.96, [
        ('新建 订单处理页', '按商品 / 按客户 / 全部订单三个页签'),
        ('商品维度', '顶部汇总 + 明细里该商品高亮'),
        ('客户维度', '客户信息条 + 该客户全部订单'),
        ('动作入口', '每单一个「推进到「下一状态」」+ 取消'),
        ('改造 工作台入口', 'admin.html 头部加入口，样式追加进 catalog.css'),
    ]),
]

for title, x, width, items in COLUMNS:
    panel(s2, x, 1.34, width, 3.22)
    textbox(s2, x + 0.16, 1.44, width - 0.32, 0.30, title, 14, True, ACCENT)
    segments = []
    for tag, detail in items:
        segments.append([(f'{tag}  ', True, ACCENT), (detail, False, INK)])
    rich_box(s2, x + 0.16, 1.80, width - 0.32, 2.64, segments, size=10)

rich_box(s2, MARGIN, 4.68, W_IN - MARGIN * 2, 0.40,
         [[('两处关键取舍：', True, INK),
           ('状态写入用条件更新而非「先查再改」，否则并发或重复点击会把状态推进两格；'
            '取消时先改状态再回补库存，否则两次取消会把库存回补两遍。', False, MUTED)]], size=10)
footer(s2, 2)

# ==================== 第 3 页：成果 ====================
s3 = prs.slides.add_slide(BLANK)
header(s3, '成果：四条需求全部交付，测试与实测双验证',
       '本人需求的集成测试 14 条全部通过｜四条需求逐条打真实接口验证，含全部拒绝路径')

STATS = [('4 / 4', '需求条目已完成'),
         ('14 / 14', '本人需求用例通过'),
         ('1 / 1', '新建页面 / 改造页面'),
         ('4', '新增接口'),
         ('0', '被修改的共用类')]

top = 1.32
for number, label in STATS:
    textbox(s3, MARGIN, top, 1.42, 0.38, number, 17, True, ACCENT, anchor=MSO_ANCHOR.MIDDLE)
    textbox(s3, MARGIN + 1.48, top, 2.20, 0.38, label, 10, False, MUTED, anchor=MSO_ANCHOR.MIDDLE)
    top += 0.44

# 选定要展示的截图（截图齐全时自动嵌入；缺图则走纯文字版式）
# 三张各证明一条需求：14209 商品维度 / 14207 推进成功 / 14208 取消回补
SHOTS = [
    ('02_按商品维度_涉及订单与汇总.png', '14209 按商品：汇总 + 涉及该商品的全部订单'),
    ('06_推进订单状态_成功提示.png', '14207 推进：只进一格，状态与按钮同步变化'),
    ('09_卖家取消_库存回补提示.png', '14208 取消：交易完成前均可取消，按明细回补库存'),
]
available = [shot for shot in SHOTS if os.path.exists(os.path.join(SHOT, shot[0]))]

textbox(s3, MARGIN, 3.62, 4.2, 0.26, '已验证的关键行为', 11.5, True, INK)


def add_fitted_picture(slide, path, x, y, w, h):
    """按原图比例缩放并居中放进 (x, y, w, h) 框内，避免拉伸变形。"""
    picture = slide.shapes.add_picture(path, Inches(x), Inches(y))
    scale = min(Inches(w) / picture.width, Inches(h) / picture.height)
    picture.width = int(picture.width * scale)
    picture.height = int(picture.height * scale)
    picture.left = Inches(x) + int((Inches(w) - picture.width) / 2)
    picture.top = Inches(y) + int((Inches(h) - picture.height) / 2)
    return picture


if available:
    rich_box(s3, MARGIN, 3.90, 3.55, 1.20, [
        [('• 逐格推进，跳过 / 回退都被 409 拒绝', False, MUTED)],
        [('• 交易完成既不能推进也不能取消', False, MUTED)],
        [('• 已发货的订单卖家仍可取消', False, MUTED)],
        [('• 重复取消不再二次回补库存', False, MUTED)],
        [('• 商品 / 客户维度只返回该维度的订单', False, MUTED)],
    ], size=9, spacing=1.0, space_after=2)

    # 右侧截图：上二下一（严格落在 0.6 ~ 9.4 的内容区里）
    LAYOUT = [(4.42, 1.26), (4.42, 3.06), (7.00, 3.06)]
    W, H = 2.40, 1.50

    for index, (file_name, caption) in enumerate(available[:3]):
        x, y = LAYOUT[index]
        frame = s3.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(W), Inches(H))
        frame.fill.solid()
        frame.fill.fore_color.rgb = WHITE
        frame.line.color.rgb = LINE
        frame.line.width = Pt(0.75)
        frame.shadow.inherit = False
        # 这几张是页面内容区截图（不含浏览器地址栏），无需裁切，直接等比放入
        add_fitted_picture(s3, os.path.join(SHOT, file_name), x, y, W, H)
        textbox(s3, x, y + H + 0.02, W, 0.20, caption, 8, False, MUTED)
else:
    # 截图尚未补齐时的完整版式：把实测结论铺满整行，不出现空洞的图片框
    FINDINGS = [
        ('14207 推进', '逐格推进正常；跳过、回退、原地推进、终态一律 409，且状态不变'),
        ('14208 取消', '客户下单与开始发货均可取消；按明细回补库存，重复取消被拒绝'),
        ('14209 商品维度', '只返回涉及该商品的订单，附订单笔数、累计件数、当前库存与状态'),
        ('14214 客户维度', '只返回该客户自己的订单，可就地推进或取消，客户之间互不可见'),
    ]
    for index, (tag, detail) in enumerate(FINDINGS):
        rich_box(s3, MARGIN + index * 2.16, 3.90, 2.04, 1.10,
                 [[(tag, True, ACCENT)], [(detail, False, MUTED)]],
                 size=9, spacing=1.05, space_after=4)

footer(s3, 3)

prs.save(OUT)
print('saved:', OUT, '| slides:', len(prs.slides._sldIdLst), '| shots embedded:', len(available))
