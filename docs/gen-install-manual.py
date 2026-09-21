# -*- coding: utf-8 -*-
"""生成《安装部署手册》PDF——面向零基础用户，图文并茂"""
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Image, PageBreak,
    Table, TableStyle, KeepTogether, HRFlowable,
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
import os

SHOT = "E:/workspace/git/java/translation-database/docs/manual-screenshots"
OUT = "E:/workspace/git/java/translation-database/docs/安装部署手册.pdf"

pdfmetrics.registerFont(TTFont("MSYH", "C:/Windows/Fonts/msyh.ttc"))
pdfmetrics.registerFont(TTFont("MSYH-B", "C:/Windows/Fonts/msyhbd.ttc"))

PAGE_W, PAGE_H = A4
MARGIN = 22 * mm
CONTENT_W = PAGE_W - 2 * MARGIN

# 主题色（书斋青蓝）
PRIMARY = colors.HexColor("#1F5C73")
ACCENT = colors.HexColor("#C8A85A")
LIGHT_BG = colors.HexColor("#F2F7F9")
WARN_BG = colors.HexColor("#FDF6E3")
WARN_BORDER = colors.HexColor("#D9A93B")
DANGER_BG = colors.HexColor("#FBEDEC")
DANGER_BORDER = colors.HexColor("#C0504D")
TIP_BG = colors.HexColor("#EDF5F0")
TIP_BORDER = colors.HexColor("#4C8A64")

styles = {
    "cover_title": ParagraphStyle("cover_title", fontName="MSYH-B", fontSize=30, leading=44, textColor=PRIMARY, alignment=TA_CENTER),
    "cover_sub": ParagraphStyle("cover_sub", fontName="MSYH", fontSize=14, leading=24, textColor=colors.HexColor("#5A6B73"), alignment=TA_CENTER),
    "h1": ParagraphStyle("h1", fontName="MSYH-B", fontSize=17, leading=26, textColor=PRIMARY, spaceBefore=16, spaceAfter=8),
    "h2": ParagraphStyle("h2", fontName="MSYH-B", fontSize=13.5, leading=20, textColor=colors.HexColor("#2B4A57"), spaceBefore=12, spaceAfter=6),
    "h3": ParagraphStyle("h3", fontName="MSYH-B", fontSize=11.5, leading=17, textColor=colors.HexColor("#3A5560"), spaceBefore=8, spaceAfter=4),
    "body": ParagraphStyle("body", fontName="MSYH", fontSize=10.5, leading=17.5, textColor=colors.HexColor("#2E3A3F"), spaceAfter=5),
    "li": ParagraphStyle("li", fontName="MSYH", fontSize=10.5, leading=17, leftIndent=16, bulletIndent=4, spaceAfter=3, textColor=colors.HexColor("#2E3A3F")),
    "num": ParagraphStyle("num", fontName="MSYH", fontSize=10.5, leading=17, leftIndent=18, spaceAfter=3, textColor=colors.HexColor("#2E3A3F")),
    "caption": ParagraphStyle("caption", fontName="MSYH", fontSize=8.5, leading=12, textColor=colors.HexColor("#7A8A90"), alignment=TA_CENTER, spaceBefore=2, spaceAfter=8),
    "warn": ParagraphStyle("warn", fontName="MSYH", fontSize=10, leading=16, textColor=colors.HexColor("#7A5A14")),
    "danger": ParagraphStyle("danger", fontName="MSYH", fontSize=10, leading=16, textColor=colors.HexColor("#8A3230")),
    "tip": ParagraphStyle("tip", fontName="MSYH", fontSize=10, leading=16, textColor=colors.HexColor("#33614A")),
    "stepnum": ParagraphStyle("stepnum", fontName="MSYH-B", fontSize=10.5, leading=16, textColor=colors.white),
}
# 中文排版：所有正文/列表/提示样式按任意字符断行（CJK wordWrap），
# 避免英文词/网址前的空格把「改用端口 / 8080」这类短语在空格处硬断
for _s in styles.values():
    _s.wordWrap = "CJK"

def h1(t): return Paragraph(t, styles["h1"])
def h2(t): return Paragraph(t, styles["h2"])
def h3(t): return Paragraph(t, styles["h3"])
def body(t): return Paragraph(t, styles["body"])
def li(t): return Paragraph(t, styles["li"], bulletText="•")
def num(t): return Paragraph(t, styles["num"])

def img(name, width=CONTENT_W, caption=None):
    path = os.path.join(SHOT, name)
    if not os.path.exists(path):
        return []
    iw, ih = 1366, 900  # 截图统一视口
    from PIL import Image as PILImage
    with PILImage.open(path) as im:
        iw, ih = im.size
    w = width
    h = w * ih / iw
    # 图过高则缩到一页内（页高约 250mm 可用）
    max_h = 225 * mm
    if h > max_h:
        h = max_h
        w = h * iw / ih
    els = [Image(path, width=w, height=h)]
    els.append(Spacer(1, 2))
    if caption:
        els.append(Paragraph("图 " + caption, styles["caption"]))
    return els

def box(text, kind="warn", title=None):
    st = {"warn": (WARN_BG, WARN_BORDER, "【注意】 "), "danger": (DANGER_BG, DANGER_BORDER, "【危险】 "), "tip": (TIP_BG, TIP_BORDER, "【提示】 ")}[kind]
    bg, border, icon = st
    head = f"<b>{icon}{title}</b><br/>" if title else icon
    p = Paragraph(head + text, styles[kind])
    tb = Table([[p]], colWidths=[CONTENT_W])
    tb.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,-1), bg),
        ("BOX", (0,0), (-1,-1), 0.8, border),
        ("LEFTPADDING", (0,0), (-1,-1), 10),
        ("RIGHTPADDING", (0,0), (-1,-1), 10),
        ("TOPPADDING", (0,0), (-1,-1), 7),
        ("BOTTOMPADDING", (0,0), (-1,-1), 7),
    ]))
    return [Spacer(1, 4), tb, Spacer(1, 6)]

def step_head(n, title):
    tb = Table([[Paragraph(str(n), styles["stepnum"]), Paragraph(f"<b>{title}</b>", ParagraphStyle("sh", fontName="MSYH-B", fontSize=12, leading=16, textColor=PRIMARY))]],
               colWidths=[9*mm, CONTENT_W - 9*mm])
    tb.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (0,0), PRIMARY),
        ("VALIGN", (0,0), (-1,-1), "MIDDLE"),
        ("ALIGN", (0,0), (0,0), "CENTER"),
        ("TOPPADDING", (0,0), (-1,-1), 4),
        ("BOTTOMPADDING", (0,0), (-1,-1), 4),
    ]))
    return [Spacer(1, 8), tb, Spacer(1, 6)]

def kv_table(rows, col1_w=42*mm):
    data = [[Paragraph(f"<b>{a}</b>", styles["body"]), Paragraph(b, styles["body"])] for a, b in rows]
    tb = Table(data, colWidths=[col1_w, CONTENT_W - col1_w])
    tb.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (0,-1), LIGHT_BG),
        ("GRID", (0,0), (-1,-1), 0.5, colors.HexColor("#C9D6DB")),
        ("VALIGN", (0,0), (-1,-1), "MIDDLE"),
        ("LEFTPADDING", (0,0), (-1,-1), 8),
        ("RIGHTPADDING", (0,0), (-1,-1), 8),
        ("TOPPADDING", (0,0), (-1,-1), 5),
        ("BOTTOMPADDING", (0,0), (-1,-1), 5),
    ]))
    return tb

def footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(colors.HexColor("#C9D6DB"))
    canvas.setLineWidth(0.5)
    canvas.line(MARGIN, 15*mm, PAGE_W - MARGIN, 15*mm)
    canvas.setFont("MSYH", 8)
    canvas.setFillColor(colors.HexColor("#7A8A90"))
    canvas.drawString(MARGIN, 10.5*mm, "翻译学术数据库 · 安装部署手册")
    canvas.drawRightString(PAGE_W - MARGIN, 10.5*mm, f"第 {doc.page} 页")
    canvas.restoreState()

def cover_footer(canvas, doc):
    pass

doc = SimpleDocTemplate(OUT, pagesize=A4,
    leftMargin=MARGIN, rightMargin=MARGIN, topMargin=20*mm, bottomMargin=22*mm,
    title="翻译学术数据库 · 安装部署手册", author="翻译学术数据库")

story = []

# ============ 封面 ============
story.append(Spacer(1, 55*mm))
story.append(Paragraph("翻译学术数据库", styles["cover_title"]))
story.append(Spacer(1, 6*mm))
story.append(Paragraph("安 装 部 署 手 册", ParagraphStyle("ct2", parent=styles["cover_title"], fontSize=22, leading=32)))
story.append(Spacer(1, 8*mm))
story.append(HRFlowable(width="40%", thickness=1, color=ACCENT, hAlign="CENTER"))
story.append(Spacer(1, 10*mm))
story.append(Paragraph("面向零基础用户 · 跟着做就能装好", styles["cover_sub"]))
story.append(Spacer(1, 3*mm))
story.append(Paragraph("古文 · 英译 · 一搜即得", styles["cover_sub"]))
story.append(Spacer(1, 40*mm))
story.append(Paragraph("适用系统：Windows 10 及以上 / macOS　　版本：v0.2.1　　2026 年 9 月",
    ParagraphStyle("cv", parent=styles["cover_sub"], fontSize=10)))
story.append(PageBreak())

# ============ 开始之前 ============
story.append(h1("开始之前：这本手册能帮你做什么"))
story.append(body("这份手册带你把「翻译学术数据库」装到自己的电脑上。整个过程不需要任何编程知识，"
    "只需要：能上网、会用鼠标双击、会在弹出窗口点按钮。"))
story.append(Spacer(1, 4))
story.append(h2("你的电脑需要满足这些条件"))
story.append(kv_table([
    ("操作系统", "Windows 10 及以上（64 位）或 macOS（近三年的大版本，Intel 与 Apple 芯片都支持）"),
    ("内存", "至少 8GB（不足也能装，但运行可能较慢）"),
    ("磁盘空余", "至少 15GB（程序文件约 1.7GB，加上运行空间）"),
    ("网络", "能连接互联网（安装过程中要下载文件）"),
]))
story.append(Spacer(1, 8))
story.append(h2("装好之后你能得到什么"))
story.append(li("一个自己的「古文英译对照图书馆」网站，打开浏览器就能用。"))
story.append(li("数据全部存在你自己电脑里，不经过任何第三方服务器。"))
story.append(li("电脑重启后服务自动恢复，不需要每次手动启动。"))
story.append(Spacer(1, 4))
story.extend(box("整个安装大约需要 <b>5–15 分钟</b>（取决于网速）。期间请勿关闭电脑或安装窗口。", "tip", "时间预期"))

story.append(PageBreak())

# ============ 第一步：下载安装包 ============
story.extend(step_head(1, "下载安装包"))
story.append(h2("从哪里下载"))
story.append(body("在浏览器打开GitHub发布页，下载与你的电脑对应的安装包："))
story.append(kv_table([
    ("GitHub发布页", "github.com/swdrts/translation-database/releases"),
]))
story.append(Spacer(1, 6))
story.append(body("进入发布页后，在Assets（附件）列表里按系统下载对应文件："))
story.append(kv_table([
    ("Windows", "transdb-deployer_0.2.1_x64-setup.exe（64位安装程序）"),
    ("Mac（Apple芯片M系列）", "transdb-deployer_0.2.1_aarch64.dmg"),
    ("Mac（Intel芯片）", "transdb-deployer_0.2.1_x64.dmg"),
], col1_w=56*mm))
story.extend(box("文件名中的版本号（如 0.2.1）会随版本更新变化，认准文件名结尾：Windows 选 <b>-setup.exe</b>，"
    "Mac（M系列芯片）选 <b>aarch64.dmg</b>，Mac（Intel芯片）选 <b>x64.dmg</b>。", "tip"))
story.append(Spacer(1, 8))

story.append(h2("双击安装"))
story.append(body("下载完成后，双击安装包，按提示一路「下一步」即可，与安装普通软件完全一样。"))

story.extend(box("Windows 首次运行时，可能弹出蓝色的 <b>SmartScreen</b> 警告：「Windows 已保护你的电脑」。"
    "这是因为本软件暂未购买代码签名证书，属正常现象。处理方法：点击「更多信息」→「仍要运行」。", "warn", "Windows 蓝色警告不用慌"))
story.extend(box("macOS 首次打开时若提示「无法验证开发者」：在安装包上<b>右键→打开</b>，再点一次「打开」即可。", "warn", "Mac 无法打开？"))
story.append(PageBreak())

# ============ 第二步：跟着向导走 ============
story.extend(step_head(2, "打开部署器，跟着向导走"))
story.append(body("安装完成后打开「翻译数据库部署器」，会看到一个五步向导。每一步都自动完成大部分工作，你只需要点几次按钮。"))

story.append(h2("第 1 小步：检查电脑（自动）"))
story.append(body("向导会自动检查操作系统、内存、磁盘、网络和端口，几秒钟完成。全绿即通过。"))
story.extend(img("15-wizard-step1-envcheck.png", caption="向导第 1 步：环境检测——自动完成，全部通过后点「下一步」"))
story.extend(box("如果某一项是红色（如磁盘不足、端口被占用），必须先解决才能继续：磁盘不足请清理空间；"
    "端口 80 被占用时，向导会出现「改用端口 8080」按钮，点它即可自动换端口。黄色项（内存偏低）可以忽略继续。", "tip", "有红色项怎么办"))

story.append(h2("第 2 小步：准备运行环境（自动安装 Docker）"))
story.append(body("「翻译学术数据库」需要一个叫Docker的运行引擎。如果你电脑上还没有，向导会自动下载并安装它，"
    "大约 3–10 分钟（视网速）。"))
story.extend(img("16-wizard-step2-docker.png", caption="向导第 2 步：自动检查/准备 Docker 运行环境"))
story.extend(box("Windows安装Docker时会弹出一次系统确认框（UAC），请点「是」；部分电脑会要求<b>重启电脑</b>，"
    "重启后重新打开部署器即可从断点继续。Mac会弹出Docker服务条款窗口，点一次「接受」。", "warn", "期间可能弹出的窗口"))

story.append(h3("配置国内镜像加速（推荐）"))
story.append(body("Docker 就绪后，向导会显示「国内镜像仓库配置」。由于国内访问 Docker 官方仓库较慢，"
    "向导已预填4个实测可用的国内镜像地址，直接点「应用镜像配置并继续」即可加快后续下载。"))
story.extend(img("17-wizard-step2-mirror.png", caption="镜像加速配置——已预填推荐地址，点「应用」或「跳过」"))
story.extend(box("以后也可以在管理窗口的「设置」页随时修改镜像地址；应用镜像配置时会自动重启 Docker（约 30–60 秒）。", "tip"))
story.append(PageBreak())

story.append(h2("第 3 小步：设置管理员密码"))
story.append(body("这是你以后登录网站时用的密码（账号固定为admin）。输入两次，三条校验规则全部变绿即可点「开始部署」。"))
story.extend(img("18-wizard-step3-config.png", caption="设置管理员密码——默认端口 80，其他设置无需修改"))
story.extend(img("18-wizard-step3-filled.png", caption="密码合法时三条规则全部打绿勾"))
story.extend(box("密码至少8位，不能包含空格、#、$或引号。"
    "<b>请务必记牢这个密码</b>：忘记后无法找回，只能清空全部数据重新安装。", "danger", "重要：密码丢失无法找回"))
story.append(h3("更多设置（一般不用动）"))
story.append(body("点开「高级选项」可以看到网页端口（默认 80）、内存分配等，保持默认即可。"))
story.extend(img("19-wizard-step3-more.png", caption="高级选项——一般无需修改"))

story.append(h2("第 4 小步：等待安装（约 5–15 分钟）"))
story.append(body("点「开始部署」后进入安装页。三个子任务依次打勾：下载组件（约 1.7GB）→ 启动服务 → 健康检查。"
    "期间请勿关闭电脑或本窗口。"))
story.extend(box("如果中途失败：别担心，已下载的部分不会重来。页面上会给出中文建议（如「端口被占用，请更换端口」），"
    "按建议处理后点「重试本步」即可。", "tip", "安装失败了？"))

story.append(h2("第 5 小步：安装完成"))
story.append(body("看到绿色对勾即安装成功。页面上列出了访问地址、账号和密码，建议立刻把密码记到安全的地方。"))
story.extend(img("24-wizard-done.png", caption="安装完成页——点「打开网页」即可进入你的数据库"))
story.append(PageBreak())

# ============ 第三步：登录使用 ============
story.extend(step_head(3, "登录并开始使用"))
story.append(body("点完成页的「打开网页」（或自己在浏览器输入http://localhost），用账号admin和你刚设的密码登录。"))
story.extend(img("01-login.png", caption="登录页——账号 admin，密码为安装时所设"))

story.append(h2("装好之后就不用管了"))
story.append(li("<b>开机自动恢复</b>：电脑重启后服务自动启动，无需任何操作。"))
story.append(li("<b>托盘图标</b>：屏幕右下角（Mac为右上角菜单栏）常驻一个圆点图标——<b>绿色</b>=一切正常，黄色=启动中，红色=有异常。"))
story.append(li("<b>日常管理</b>：右键托盘图标可以启动/停止/重启服务、打开管理窗口。"))
story.append(Spacer(1, 4))
story.extend(box("密码务必记牢；托盘图标绿色即一切正常；有任何问题先看「管理窗口→日志」，"
    "把日志发给维护人员即可。", "tip", "三句话记住日常使用"))

# ============ 常见问题 ============
story.append(h1("常见问题"))
qa = [
    ("安装时弹出蓝色 SmartScreen 警告？",
     "点「更多信息」→「仍要运行」。原因是软件暂无代码签名证书，不影响使用。"),
    ("要求重启电脑怎么办？",
     "这是安装 Docker 的正常步骤。重启后重新打开部署器，会自动从断点继续安装。"),
    ("提示「端口 80 已被占用」？",
     "点向导给出的「改用端口 8080」按钮即可。以后访问地址会变成 http://localhost:8080。"),
    ("镜像下载很慢或失败？",
     "在向导或管理窗口「设置」页配置国内镜像地址（已预填推荐值），稍后再试。"),
    ("内存不足警告？",
     "关闭其他大型程序；或在配置步的「高级选项」里调小内存分配。"),
    ("忘了管理员密码？",
     "很遗憾，密码无法找回。唯一办法：先导出重要内容，再到部署器管理窗口的维护页卸载，并勾选「同时删除全部数据」，然后重新安装（数据会丢失）。"),
    ("电脑重启后网站打不开？",
     "托盘图标若为红色，右键托盘图标选「尝试启动 Docker」，等待 1–2 分钟即可。"),
    ("想彻底卸载？",
     "托盘右键→管理窗口→「维护」页→卸载。默认保留数据；勾选「同时删除全部数据」则彻底清除。"),
]
for q, a in qa:
    story.append(h3("Q：" + q))
    story.append(body("A：" + a))

story.append(Spacer(1, 10))
story.append(HRFlowable(width="100%", thickness=0.8, color=ACCENT))
story.append(Spacer(1, 6))
story.append(Paragraph("祝你使用顺利，古文 · 英译 · 一搜即得。",
    ParagraphStyle("end", parent=styles["cover_sub"], fontSize=11)))

doc.build(story, onFirstPage=footer, onLaterPages=footer)
print("OK:", OUT)
