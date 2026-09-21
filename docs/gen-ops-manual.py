# -*- coding: utf-8 -*-
"""生成《后台操作手册》PDF——面向零基础用户，图文并茂"""
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Image, PageBreak,
    Table, TableStyle, HRFlowable, KeepTogether,
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import KeepTogether as KT
import os

SHOT = "E:/workspace/git/java/translation-database/docs/manual-screenshots"
OUT = "E:/workspace/git/java/translation-database/docs/后台操作手册.pdf"

pdfmetrics.registerFont(TTFont("MSYH", "C:/Windows/Fonts/msyh.ttc"))
pdfmetrics.registerFont(TTFont("MSYH-B", "C:/Windows/Fonts/msyhbd.ttc"))

PAGE_W, PAGE_H = A4
MARGIN = 22 * mm
CONTENT_W = PAGE_W - 2 * MARGIN

PRIMARY = colors.HexColor("#1F5C73")
ACCENT = colors.HexColor("#C8A85A")
LIGHT_BG = colors.HexColor("#F2F7F9")
WARN_BG = colors.HexColor("#FDF6E3"); WARN_BORDER = colors.HexColor("#D9A93B")
DANGER_BG = colors.HexColor("#FBEDEC"); DANGER_BORDER = colors.HexColor("#C0504D")
TIP_BG = colors.HexColor("#EDF5F0"); TIP_BORDER = colors.HexColor("#4C8A64")

def st(name, **kw):
    base = dict(fontName="MSYH", fontSize=10.5, leading=17, textColor=colors.HexColor("#2E3A3F"), spaceAfter=5)
    base.update(kw)
    return ParagraphStyle(name, **base)

styles = {
    "cover_title": st("cover_title", fontName="MSYH-B", fontSize=30, leading=44, textColor=PRIMARY, alignment=TA_CENTER),
    "cover_sub": st("cover_sub", fontSize=14, leading=24, textColor=colors.HexColor("#5A6B73"), alignment=TA_CENTER),
    "h1": st("h1", fontName="MSYH-B", fontSize=17, leading=26, textColor=PRIMARY, spaceBefore=16, spaceAfter=8),
    "h2": st("h2", fontName="MSYH-B", fontSize=13.5, leading=20, textColor=colors.HexColor("#2B4A57"), spaceBefore=12, spaceAfter=6),
    "h3": st("h3", fontName="MSYH-B", fontSize=11.5, leading=17, textColor=colors.HexColor("#3A5560"), spaceBefore=8, spaceAfter=4),
    "body": st("body"),
    "li": st("li", leftIndent=16, spaceAfter=3),
    "caption": st("caption", fontSize=8.5, leading=12, textColor=colors.HexColor("#7A8A90"), alignment=TA_CENTER, spaceBefore=2, spaceAfter=8),
    "warn": st("warn", fontSize=10, leading=16, textColor=colors.HexColor("#7A5A14")),
    "danger": st("danger", fontSize=10, leading=16, textColor=colors.HexColor("#8A3230")),
    "tip": st("tip", fontSize=10, leading=16, textColor=colors.HexColor("#33614A")),
    "stepnum": st("stepnum", fontName="MSYH-B", textColor=colors.white),
    "th": st("th", fontName="MSYH-B", fontSize=10, leading=15, textColor=colors.white),
    "td": st("td", fontSize=10, leading=15),
}
# 中文排版：所有正文/列表/提示/表格样式按任意字符断行（CJK wordWrap），
# 避免「将新加入 / 将被替换 / 将跳过」「数据由 Docker 管理」这类短语在空格处硬断
for _s in styles.values():
    _s.wordWrap = "CJK"

def h1(t): return Paragraph(t, styles["h1"])
def h2(t): return Paragraph(t, styles["h2"])
def h3(t): return Paragraph(t, styles["h3"])
def body(t): return Paragraph(t, styles["body"])
def li(t): return Paragraph(t, styles["li"], bulletText="•")

def img(name, width=CONTENT_W, caption=None):
    path = os.path.join(SHOT, name)
    if not os.path.exists(path):
        return []
    from PIL import Image as PILImage
    with PILImage.open(path) as im:
        iw, ih = im.size
    w = width; h = w * ih / iw
    max_h = 225 * mm
    if h > max_h:
        h = max_h; w = h * iw / ih
    els = [Image(path, width=w, height=h), Spacer(1, 2)]
    if caption:
        els.append(Paragraph("图 " + caption, styles["caption"]))
    return els

def box(text, kind="warn", title=None):
    bg, border, icon = {"warn": (WARN_BG, WARN_BORDER, "【注意】 "), "danger": (DANGER_BG, DANGER_BORDER, "【危险】 "), "tip": (TIP_BG, TIP_BORDER, "【提示】 ")}[kind]
    head = f"<b>{icon}{title}</b><br/>" if title else icon
    p = Paragraph(head + text, styles[kind])
    tb = Table([[p]], colWidths=[CONTENT_W])
    tb.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,-1), bg), ("BOX", (0,0), (-1,-1), 0.8, border),
        ("LEFTPADDING", (0,0), (-1,-1), 10), ("RIGHTPADDING", (0,0), (-1,-1), 10),
        ("TOPPADDING", (0,0), (-1,-1), 7), ("BOTTOMPADDING", (0,0), (-1,-1), 7),
    ]))
    return [Spacer(1, 4), tb, Spacer(1, 6)]

def step_head(n, title):
    tb = Table([[Paragraph(str(n), styles["stepnum"]), Paragraph(f"<b>{title}</b>", st("sh", fontName="MSYH-B", fontSize=12, leading=16, textColor=PRIMARY))]],
               colWidths=[9*mm, CONTENT_W - 9*mm])
    tb.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (0,0), PRIMARY), ("VALIGN", (0,0), (-1,-1), "MIDDLE"),
        ("ALIGN", (0,0), (0,0), "CENTER"), ("TOPPADDING", (0,0), (-1,-1), 4), ("BOTTOMPADDING", (0,0), (-1,-1), 4),
    ]))
    return [Spacer(1, 8), tb, Spacer(1, 6)]

def table(headers, rows, col_ws=None):
    data = [[Paragraph(f"<b>{c}</b>", styles["th"]) for c in headers]]
    for r in rows:
        data.append([Paragraph(c, styles["td"]) for c in r])
    if col_ws:
        total = sum(col_ws)
        col_ws = [w / total * CONTENT_W for w in col_ws]
    else:
        col_ws = [CONTENT_W / len(headers)] * len(headers)
    tb = Table(data, colWidths=col_ws, repeatRows=1)
    tb.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,0), PRIMARY),
        ("GRID", (0,0), (-1,-1), 0.5, colors.HexColor("#C9D6DB")),
        ("ROWBACKGROUNDS", (0,1), (-1,-1), [colors.white, LIGHT_BG]),
        ("VALIGN", (0,0), (-1,-1), "MIDDLE"),
        ("LEFTPADDING", (0,0), (-1,-1), 7), ("RIGHTPADDING", (0,0), (-1,-1), 7),
        ("TOPPADDING", (0,0), (-1,-1), 4.5), ("BOTTOMPADDING", (0,0), (-1,-1), 4.5),
    ]))
    return [Spacer(1, 4), tb, Spacer(1, 6)]

def footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(colors.HexColor("#C9D6DB")); canvas.setLineWidth(0.5)
    canvas.line(MARGIN, 15*mm, PAGE_W - MARGIN, 15*mm)
    canvas.setFont("MSYH", 8); canvas.setFillColor(colors.HexColor("#7A8A90"))
    canvas.drawString(MARGIN, 10.5*mm, "翻译学术数据库 · 后台操作手册")
    canvas.drawRightString(PAGE_W - MARGIN, 10.5*mm, f"第 {doc.page} 页")
    canvas.restoreState()

doc = SimpleDocTemplate(OUT, pagesize=A4,
    leftMargin=MARGIN, rightMargin=MARGIN, topMargin=20*mm, bottomMargin=22*mm,
    title="翻译学术数据库 · 后台操作手册", author="翻译学术数据库")

story = []

# ============ 封面 ============
story.append(Spacer(1, 55*mm))
story.append(Paragraph("翻译学术数据库", styles["cover_title"]))
story.append(Spacer(1, 6*mm))
story.append(Paragraph("后 台 操 作 手 册", st("ct2", parent=styles["cover_title"], fontSize=22, leading=32)))
story.append(Spacer(1, 8*mm))
story.append(HRFlowable(width="40%", thickness=1, color=ACCENT, hAlign="CENTER"))
story.append(Spacer(1, 10*mm))
story.append(Paragraph("搜索 · 录入 · 导入 · 导出 · 账号管理 · 一步一步照着做", styles["cover_sub"]))
story.append(Spacer(1, 40*mm))
story.append(Paragraph("版本：v0.2.1　　2026 年 9 月", st("cv", parent=styles["cover_sub"], fontSize=10)))
story.append(PageBreak())

# ============ 认识后台 ============
story.append(h1("认识你的后台"))
story.append(body("安装完成后，在浏览器打开 <b>http://localhost</b>，用账号 <b>admin</b> 登录（密码为安装时所设）。"
    "登录后你会看到顶栏导航：找一找、录入资料、成书导出、系统管理。"))
story.extend(table(
    ["页面", "做什么用", "谁能用"],
    [
        ["找一找", "搜索和浏览所有「古文原文 + 英文译文」对照", "所有人"],
        ["录入资料", "添加新内容：导入整本书 / 导入对照表格 / 手动录入", "编辑者和管理员"],
        ["成书导出", "把整本书按章节顺序合并成 Word / Markdown / TXT 书稿", "编辑者和管理员"],
        ["系统管理", "用户账号、标签分类、搜索修复（仅管理员）", "仅管理员"],
    ],
    col_ws=[1.2, 2.4, 1.1],
))
story.extend(box("数据库内置三种角色：<b>查看者</b>（只能浏览）、<b>编辑者</b>（可录入和维护内容）、"
    "<b>管理员</b>（全部权限）。登录用的admin账号就是管理员，可以给同事开账号（见第 6 章）。", "tip", "角色与权限"))

# ============ 第 1 章 搜索 ============
story.extend(step_head(1, "找一找：搜索对照内容"))
story.append(body("首页就是搜索页。直接在搜索框输入，<b>中文、英文、拼音首字母都可以</b>。"))
story.extend(img("02-home-search.png", caption="首页搜索——输入中文、英文或拼音首字母"))
story.append(h2("怎么搜"))
story.append(li("输入古文原句（如「学而时习之」）、书名（如「论语」）、英文字词（如Confucius）都可以。"))
story.append(li("输入时下方会弹出联想（书名 / 作者 / 标签），点一下即可快速填入。"))
story.append(li("不想打字？点下方的标签、朝代、书目纸片按类别浏览。"))
story.extend(img("03-search-results.png", caption="搜索结果——关键词高亮，点卡片查看完整对照"))
story.append(h2("看结果"))
story.append(li("每张卡片显示原文和译文的对照片段，以及出处（《书名》 章节 朝代·作者 译者）和标签。"))
story.append(li("点卡片进入详情页，可查看完整的书页式对照排版。"))
story.extend(img("04-segment-detail.png", caption="条目详情——书页式原文/译文对照"))
story.extend(box("搜索不出来时：换更短的关键词；或清除筛选条件。"
    "若顶部出现黄色「简化搜索模式」横幅，说明高级搜索暂不可用，普通搜索不受影响。", "tip", "搜不到？"))
story.append(PageBreak())

# ============ 第 2 章 手动录入 ============
story.extend(step_head(2, "手动录入一条对照"))
story.append(body("只想补一两句话？在首页点「手动录入一条」，或在「录入资料」页选「手动录入」。"))
story.extend(img("05-segment-new.png", caption="手动录入——填上原文就能保存，译文可以先留空"))
story.append(h2("怎么填"))
story.append(li("<b>① 原文与译文</b>：「古文原文」必填；「英文译文」可以以后再补——留空保存会存为草稿。"))
story.append(li("<b>② 出处信息</b>（选填）：书名、章节、作者、朝代、译者。填了方便日后按书目查找。"))
story.append(li("<b>③ 标签与公开状态</b>：标签是分类小纸条（如：儒家、论语、修身）；「发布」需先填好译文，"
    "没译文时选「草稿」（只有自己能看到）。"))
story.extend(box("录入页没有「删除」按钮——要删除条目，先在搜索里找到它，进详情页由管理员删除。", "tip"))

# ============ 第 3 章 批量导入 ============
story.extend(step_head(3, "批量导入：整本书 / 对照表格"))
story.append(body("点顶栏「录入资料」，有三种方式。手里有现成电子书选「导入整本书」；"
    "已整理成表格选「导入对照表格」；只补一两句选「手动录入」。"))
story.extend(img("06-import-entry.png", caption="录入资料入口——三张方式卡片按需选择"))

story.append(h2("方式一：导入整本书 / 文档（推荐）"))
story.append(body("支持 EPUB、PDF、Word（docx/doc）、TXT、Markdown、HTML，单个文件不超过 50MB。"
    "系统自动按章节拆成一段一段原文，译文先留空，之后逐条补写。"))
story.extend(img("09-import-doc-step1.png", caption="整本书导入第 1 步——先选「原文侧」还是「译文侧」，再上传文件"))
story.append(li("<b>第 1 步</b>：先选这次导入的是「原文（古文）」还是「译文（英译本）」，再上传文件。"))
story.append(li("<b>第 2 步</b>：核对自动拆分结果、补书目信息（书名/作者/标签等已自动识别）。"))
story.append(li("<b>第 3 步</b>：预览每一段拆分结果，可合并、拆分、改字、改章节。"))
story.append(li("<b>第 4 步</b>：确认导入。导入后在「找一找」里能搜到，点进去逐条补写译文。"))
story.extend(box("扫描版 PDF（整页都是图片）里没有文字，无法导入；预览会话超过 30 分钟未操作会过期，重新上传即可。", "warn", "两个注意点"))

story.append(h2("方式二：导入对照表格（进阶）"))
story.append(body("适合已经把「原文—译文」一句一句整理成表格的情况。支持 json / csv / xlsx / xls，单文件 ≤50MB、≤10 万行。"
    "表格列建议：古文原文、英文译文、书名、章节、作者、朝代、译者、标签。"))
story.extend(img("07-import-table-step1.png", caption="对照表导入第 1 步——上传文件并选择重复内容的处理方式"))
story.append(li("<b>第 1 步</b>：上传文件；如果文件里有和数据库重复的内容，三选一：跳过重复的（推荐）、用文件里的替换、两份都保留。"))
story.append(li("<b>第 2 步</b>：核对预览——统计「将新加入 / 将被替换 / 将跳过」；有格式问题的行会列出原因，此时还没真正导入。"))
story.append(li("<b>第 3 步</b>：点「确认无误，开始导入」。"))
story.extend(img("08-import-table-step2.png", caption="导入预览——核对无误再确认，不会被导入"))
story.extend(box("导入是「两阶段」的：先预览再确认。预览页看到的一切都还没入库，点确认按钮才开始。", "tip", "放心核对"))
story.append(PageBreak())

# ============ 第 4 章 导出 ============
story.extend(step_head(4, "成书导出"))
story.append(body("翻译完成后，点顶栏「成书导出」，把整本书按原始章节顺序合并成书稿文件。"))
story.extend(img("10-export.png", caption="成书导出——点书卡开始导出"))
story.append(li("点一本书的卡片，弹出导出对话框。"))
story.append(li("选<b>对照方式</b>：原文译文对照 / 仅译文 / 仅原文。"))
story.append(li("选<b>文件格式</b>：Word（docx）/ Markdown / TXT。"))
story.append(li("点「导出下载」，文件名形如《论语》-对照.docx。"))
story.extend(img("11-export-dialog.png", caption="导出对话框——选对照方式和格式，逐章统计一目了然"))
story.append(h2("看懂逐章统计"))
story.append(li("<b>齐全</b>：这一章原文译文都有的段数；<b>待译</b>：只有原文的段数；<b>缺原文</b>：只有译文的段数。"))
story.append(li("<b>已配对</b>：原文侧与译文侧分两次导入后，按章节顺序自动对上的对数；没对上的会留占位符提示。"))
story.extend(box("导出前先把译文补齐，成书完成度会更高。原文侧和译文侧分开导入的内容，会自动按章节顺序拉链配对。", "tip"))

# ============ 第 5 章 标签与搜索修复 ============
story.extend(step_head(5, "标签分类与搜索修复（管理员）"))
story.append(h2("标签分类"))
story.append(body("点「系统管理→标签分类」，可以新建、改名、删除标签。标签是给内容贴的分类小纸条，方便按主题浏览。"))
story.extend(img("13-admin-tags.png", caption="标签分类——新建/编辑/删除"))
story.extend(box("删除标签不会删除内容本身，只是去掉这个分类纸条。", "tip"))

story.append(h2("搜索修复（重建索引）"))
story.append(body("「搜索修复」相当于把书重新整理一遍书架，让搜索又快又准。<b>一般不需要手动操作</b>，"
    "只有当搜索结果明显不对劲（如刚导入的内容搜不到）时才使用。"))
story.extend(img("14-admin-reindex.png", caption="搜索修复——点「开始重建」，页面自动刷新进度"))
story.extend(box("重建期间搜索结果可能不完整（几分钟到几十分钟），建议在没人使用的时候操作。", "warn", "什么时候能用"))
story.append(PageBreak())

# ============ 第 6 章 用户账号 ============
story.extend(step_head(6, "用户账号：给同事开账号"))
story.append(body("点「系统管理→用户账号」，可以新建账号、分配角色、临时禁用。"))
story.extend(img("12-admin-users.png", caption="用户账号——新建、改角色、禁用/恢复"))
story.append(h2("三种角色能做什么"))
story.extend(table(
    ["能做的事", "查看者", "编辑者", "管理员"],
    [
        ["搜索 / 浏览", "有", "有", "有"],
        ["手动录入 / 编辑条目", "—", "有", "有"],
        ["批量导入 / 成书导出", "—", "有", "有"],
        ["删除条目", "—", "—", "有"],
        ["用户账号 / 标签 / 搜索修复", "—", "—", "有"],
    ],
    col_ws=[2.2, 1, 1, 1],
))
story.append(h2("开账号三步"))
story.append(li("点「+ 新建账号」，填用户名（用于登录）、初始密码、显示名，选角色（默认查看者）。"))
story.append(li("把用户名和初始密码告诉同事，请他登录后即可使用。"))
story.append(li("同事离职或需要临时停用：点「暂时禁用」，他无法登录但数据不丢；点「恢复使用」可重新启用。"))
story.extend(box("新建账号时请让同事<b>首次登录后自行保管好密码</b>；目前系统不提供重置密码和删除账号功能，"
    "只能禁用。因此初始密码请设置得足够安全。", "warn", "密码与账号安全"))

# ============ 第 7 章 日常维护 ============
story.extend(step_head(7, "日常维护（部署器管理窗口）"))
story.append(body("后台之外的日常管理都在桌面「翻译数据库部署器」的管理窗口进行：双击托盘圆点图标，或右键托盘→「打开管理窗口」。"))
story.extend(img("20-deployer-status.png", caption="运行状态——四个容器卡片与启停按钮"))
story.append(h2("四个标签页"))
story.extend(table(
    ["标签页", "做什么"],
    [
        ["运行状态", "查看服务是否健康；启动 / 停止 / 重启服务；一键打开网站"],
        ["日志", "查看各服务最近 500 行日志，出问题时发给维护人员"],
        ["设置", "开机自启开关、修改网页端口、管理国内镜像加速、打开数据目录"],
        ["维护", "版本升级（点「检查并升级」，数据不受影响）；卸载（两步确认）"],
    ],
    col_ws=[1, 3],
))
story.extend(img("21-deployer-logs.png", caption="日志——选择服务并刷新查看"))
story.extend(img("22-deployer-settings.png", caption="设置——端口 / 自启 / 镜像加速 / 数据目录"))
story.extend(img("22b-deployer-settings-mirror.png", caption="镜像加速——可检测每个地址的可用性"))
story.extend(img("23-deployer-maintain.png", caption="维护——升级与卸载"))
story.extend(box("改端口、重启、升级都不会丢失数据；数据由 Docker 管理。"
    "彻底清除数据只能通过「维护→卸载」并勾选「同时删除全部数据」。", "tip", "数据安全"))
story.append(h2("托盘图标速查"))
story.extend(table(
    ["图标颜色", "含义", "要做什么"],
    [
        ["绿色", "一切正常", "无需操作"],
        ["黄色", "服务启动中 / 尚未部署", "稍等 1–2 分钟"],
        ["红色", "有容器停止或 Docker 未运行", "右键托盘→「尝试启动 Docker」"],
    ],
    col_ws=[1, 2, 2.2],
))
story.append(Spacer(1, 10))
story.append(HRFlowable(width="100%", thickness=0.8, color=ACCENT))
story.append(Spacer(1, 6))
story.append(Paragraph("句句对照，温故知新。祝你使用顺利！", st("end", parent=styles["cover_sub"], fontSize=11)))

doc.build(story, onFirstPage=footer, onLaterPages=footer)
print("OK:", OUT)
