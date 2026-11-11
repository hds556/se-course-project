# -*- coding: utf-8 -*-
"""
挑战需求B迭代汇报 PPT（三页）

版式原则：白底、单一强调色、固定字号层级、左对齐栅格，不使用装饰性图形与动画，
保证投影与打印时文字清晰、结构可读。

第 1 页 我负责的部分
第 2 页 实现过程（对前端 / 后端 / 数据库做了什么）
第 3 页 成果
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
OUT = os.path.join(BASE, '挑战需求B迭代汇报-范君豪.pptx')

FONT = 'Microsoft YaHei'
INK = RGBColor(0x1F, 0x1B, 0x17)
MUTED = RGBColor(0x6B, 0x61, 0x56)
ACCENT = RGBColor(0xB4, 0x47, 0x2A)
LINE = RGBColor(0xDE, 0xD6, 0xCB)
TINT = RGBColor(0xFA, 0xF6, 0xF1)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)

W_IN, H_IN = 10.0, 5.625      # 16:9
MARGIN = 0.6

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
    textbox(slide, MARGIN, 5.16, 4, 0.24, '挑战需求B迭代 · 范君豪', 9, False, MUTED)
    textbox(slide, W_IN - MARGIN - 1.0, 5.16, 1.0, 0.24, f'{page} / 3', 9, False, MUTED,
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
header(s1, '我负责的部分：买家端收藏 · 购物车 · 订单闭环',
       '需求条目 14201～14205｜范围限定在买家侧，不含卖家端订单状态推进')

ROWS = [
    ('14201', '收藏商品',
     '登录后收藏 / 取消收藏（幂等）；已下架商品保留在收藏中并标注，可点开只读详情'),
    ('14202', '购物车管理',
     '加入购物车（同商品合并）、改数量（≥1）、移除；加购不占用也不锁定库存'),
    ('14203', '提交订单',
     '购物车为唯一下单入口；勾选多件生成 1 笔订单，逐商品校验库存并原子扣减'),
    ('14204', '客户取消订单',
     '发货前可取消，点击即生效、不填原因、不二次确认；取消时回补库存'),
    ('14205', '查看我的订单',
     '本人全部订单（进行中 / 交易完成 / 已取消），倒序分页、明细只读'),
]

top = 1.38
for code, name, detail in ROWS:
    tag = panel(s1, MARGIN, top, 1.02, 0.50)
    textbox(s1, MARGIN, top, 1.02, 0.50, code, 12, True, ACCENT,
            align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    textbox(s1, MARGIN + 1.18, top, 1.42, 0.50, name, 13, True, INK,
            anchor=MSO_ANCHOR.MIDDLE)
    textbox(s1, MARGIN + 2.66, top, W_IN - MARGIN * 2 - 2.66, 0.50, detail, 10.5, False, MUTED,
            anchor=MSO_ANCHOR.MIDDLE)
    top += 0.62

rich_box(s1, MARGIN, 4.56, W_IN - MARGIN * 2, 0.44,
         [[('范围边界：', True, INK),
           ('卖家端订单状态推进（确认 / 备货 / 发货 / 完成）、卖家取消订单与后台订单列表属其他成员职责，'
            '本次未实现，仅调用其已有的库存字段与登录会话。', False, MUTED)]], size=10)
footer(s1, 1)

# ==================== 第 2 页：实现过程 ====================
s2 = prs.slides.add_slide(BLANK)
header(s2, '实现过程：前端 · 后端 · 数据库',
       '新增订单域模块；库存以「条件更新」保证原子扣减，下单逐商品校验')

COLUMNS = [
    ('数据库', MARGIN, 3.05, [
        ('新增 4 张表', 'orders、order_item、cart_item、favorite'),
        ('唯一索引', '(customer_id, product_id) 保证收藏与购物车同商品只有一条'),
        ('订单主表', '只存下单时间、无地址；状态整单流转'),
        ('明细快照', 'order_item 固化下单时的商品名称与单价'),
        ('原子扣减', 'update … where stock >= 数量，不足时影响行数为 0'),
    ]),
    ('后端', MARGIN + 3.28, 2.90, [
        ('新增 order 模块', 'OrderService / OrderController / OrderException'),
        ('事务 + 写锁', '锁覆盖事务提交，数据库条件更新兜底，防超卖'),
        ('逐商品校验', '缺货件剔除并提示，其余照常下单'),
        ('取消回补库存', '售罄下架的商品自动恢复在售'),
        ('越权防护', '只按「本人 + 订单号」查询，他人订单 404'),
    ]),
    ('前端', MARGIN + 6.42, W_IN - MARGIN - (MARGIN + 6.42), [
        ('新建 购物车页', '勾选 / 改数量 / 移除 / 提交订单'),
        ('新建 我的收藏页', '下架商品标红，仅查看与取消收藏'),
        ('改造 我的订单页', '原「历史下单记录」页换订单制视图'),
        ('改造 商品详情页', '原购买意向面板换成收藏 / 加购'),
        ('改造 首页导航样式', '补购物车 / 收藏 / 订单入口与样式'),
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
         [[('关键取舍：', True, INK),
           ('选「条件更新」而非「先查再改」，是因为后者在并发下会读到过期库存并扣成负数；'
            '逐商品校验而非整单拒绝，是因为 1 件缺货不应让其余商品白忙一场。', False, MUTED)]], size=10)
footer(s2, 2)

# ==================== 第 3 页：成果 ====================
s3 = prs.slides.add_slide(BLANK)
header(s3, '成果：五条需求全部交付，测试与实测双验证',
       'OrderIntegrationTest 20 条用例全通过｜浏览器端到端走通完整下单链路')

STATS = [('5 / 5', '需求条目已完成'),
         ('20 / 20', '集成测试用例通过'),
         ('2 / 1', '新建页面 / 改造页面'),
         ('4', '新增数据表'),
         ('13', '新增接口')]

top = 1.32
for number, label in STATS:
    textbox(s3, MARGIN, top, 1.42, 0.38, number, 17, True, ACCENT, anchor=MSO_ANCHOR.MIDDLE)
    textbox(s3, MARGIN + 1.48, top, 2.30, 0.38, label, 10, False, MUTED, anchor=MSO_ANCHOR.MIDDLE)
    top += 0.44

textbox(s3, MARGIN, 3.62, 4.0, 0.26, '已验证的关键行为', 11.5, True, INK)
rich_box(s3, MARGIN, 3.92, 3.72, 1.10, [
    [('• 8 个并发买家抢 5 件库存，恰好 5 单成功', False, MUTED)],
    [('• 库存归零不为负，无超卖', False, MUTED)],
    [('• 部分缺货：缺货件标红，其余照常成单', False, MUTED)],
    [('• 改价后回看订单仍显示下单时单价', False, MUTED)],
    [('• 发货后取消 / 取消他人订单均被拒绝', False, MUTED)],
], size=9, spacing=1.0, space_after=2)

# 右侧截图：上二下一。原图为 16:10 整页截图，顶部有一段空白页眉，
# 用 crop 裁掉页眉后再按框内等比缩放，避免整图缩小导致文字看不清。
SHOTS = [
    ('02_购物车_勾选与已下架置灰.png', '购物车：勾选提交，下架商品置灰', 4.86, 1.26, 2.40, 1.44),
    ('04_我的订单_按时间倒序与明细展开.png', '我的订单：状态标签与行内明细', 4.86, 3.06, 2.40, 1.44),
    ('06_提交订单_缺货逐项提示.png', '缺货逐项提示，其余商品照常成单', 7.44, 3.06, 2.40, 1.44),
]

CROP_TOP = 0.11   # 裁掉顶部 11% 的空白页眉

for file_name, caption, x, y, w, h in SHOTS:
    slide_path = os.path.join(SHOT, file_name)
    if not os.path.exists(slide_path):
        continue
    frame = s3.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    frame.fill.solid()
    frame.fill.fore_color.rgb = WHITE
    frame.line.color.rgb = LINE
    frame.line.width = Pt(0.75)
    frame.shadow.inherit = False
    picture = s3.shapes.add_picture(slide_path, Inches(x), Inches(y), Inches(w), Inches(h))
    picture.crop_top = CROP_TOP
    textbox(s3, x, y + h + 0.02, w, 0.20, caption, 8, False, MUTED)

footer(s3, 3)

prs.save(OUT)
print('saved:', OUT, '| slides:', len(prs.slides._sldIdLst))
