# 成书导出（Book Export）设计文档

- 日期：2026-09-09
- 状态：已获用户批准的设计稿
- 阶段：导出功能首期，接续 2026-09-06 总体设计（该文档"范围外"中的"数据导出"自此进入范围）

## 1. 背景与目标

数据库以句段（`segment`）为最小存取单位：一段原文对应一段译文。翻译完成后需要把整本书的段按原始顺序合并成书稿文件，用于出版或校对。

本功能提供"按书名导出整本书"的管线：**分段存 → 按序合 → 成书导出**。

**需求：**

- 能单独导出译文（`TRANSLATION_ONLY`）
- 能同时导出原文与译文对照（`BILINGUAL`）
- 顺手支持单独导出原文（`SOURCE_ONLY`，管线对称、成本为零，供导出原稿校对）

**成功标准：**

- 原文侧、译文侧分两次导入的同一本书，能自动按章节配对合并为对照书稿
- 导出前可见完成度与逐章配对统计，配不上的段有明确占位符与警告
- 三种格式（TXT / Markdown / Word docx）输出即可用于后续排版/送印，不引入新后端依赖

**范围外（本期明确不做）：** EPUB/PDF 生成、拖拽调序、配对结果回写数据库、按标签/朝代/状态筛选导出、排序键迁移（见 §8 决策记录）。

## 2. 关键决策记录

**排序方案：不加排序键（用户已确认）。** 章节顺序按"该章最小 segment id"（即导入顺序）导出，章内按 id 升序。理由：

- 整本书一次性导入场景下，id 顺序即书本顺序，现有数据已含足够顺序信息
- 加 `chapter_ordinal` / `paragraph_ordinal` 列需 Flyway 迁移 + 改两条导入管线 + 存量回填，而中途补录的新段无论有无排序列都会排在章末（真正解决需拖拽 UI），边际收益≈0
- 已知代价：同章重名（如书中出现两个「注释」章）会被合并为一章；此场景出现时再升级为排序键/Work-Chapter 实体方案

**其他默认值（用户未反对，按推荐执行）：**

- 未译段：允许导出，译文位置放占位符 `〔未译〕`（对照模式保留原文），不阻止导出、不静默跳过
- 状态不筛选：DRAFT / PUBLISHED 均导出，以"译文是否为空"为准（自用出书场景）
- 权限：三个端点均 `hasAnyRole('EDITOR','ADMIN')`——导出是含 DRAFT 内容的数据外流操作，与导入对齐

## 3. 配对装配算法（核心）

新增纯逻辑类 `com.transdb.exporter.BookAssembler`，输入该书全部段（新增仓库方法 `SegmentRepository.findByWorkTitleOrderByIdAsc(String)`），输出装配好的书稿模型。

**段分类**（按章分组后，章内按 id 排序）：

| 类型 | 判定 | 来源场景 |
|---|---|---|
| 完整段 FULL | 原文、译文均非空 | 表格导入、手工录入后已译 |
| 原文段 SRC | 仅原文非空 | 文档导入·原文侧、待译段 |
| 译文段 DST | 仅译文非空 | 文档导入·译文侧 |

**配对规则：** 同一章内，SRC 队列 × DST 队列按各自 id 顺序拉链配对（zip）。这正是"原文侧导一本 + 译文侧导一本"后合并出书的主场景。FULL 段直接通过。

**残留段处理：**

- 剩余 SRC → 对照导出时原文照出、译文位置 `〔未译〕`；仅译文导出时输出 `〔未译〕`
- 剩余 DST → 译文照出、原文位置 `〔原文缺失〕`
- 占位符在 DOCX 中灰色、MD 中引用块内标注、TXT 中原样输出

**警告（预览接口返回，逐章给出）：**

- 同章 SRC 数 ≠ DST 数：如"第三章：原文 45 段 / 译文 43 段，2 段未配上"
- 同章同时存在 FULL 与（SRC 或 DST）：混源章，拉链位置可能错位，提示人工核对

**已知边界（文档化，不做防御性阻断）：** 拉链配对假设原文侧与译文侧文件的章节结构一致；若先手工译了某几段、又整侧导入译文，位置可能错位——预览警告是兜底手段。

**书稿模型：** `BookDocument { title, author, translator, chapters: [ { title, units: [ {source, translated} ] } ] }`。author/translator 取该书最早段的非空值；chapter 为 null 的段归入无标题章（全书仅一个无标题章时不输出章标题）。

## 4. API 设计

新增 `ExportController`，路由前缀 `/api/v1/export`，返回包装 `ApiResponse<T>`（文件端点除外）。

| 端点 | 权限 | 请求 | 响应 |
|---|---|---|---|
| `GET /works` | EDITOR+ | - | 书单：`[{workTitle, chapters, totalSegments, translatedSegments}]`，单条聚合查询（group by work_title）避免 N+1 |
| `POST /preview` | EDITOR+ | `{workTitle}` | `{totals: {segments, units, pairedUnits}, chapters: [{title, full, src, dst, paired, warnings[]}]}` |

完成度口径：**成书完成度 = pairedUnits / units**，其中 units = 完整段数 + 配对数 + 残留段数（成书后的"段"数），pairedUnits = 完整段数 + 配对数。不用"有译文段数/总段数"——分侧导入的书该口径恒为 50%，会误导。
| `POST /`（下载） | EDITOR+ | `{workTitle, mode, format}` | 文件字节流 |

- `mode`：`TRANSLATION_ONLY` / `BILINGUAL` / `SOURCE_ONLY`
- `format`：`TXT` / `MARKDOWN` / `DOCX`
- 文件端点是项目首个文件流响应：`Content-Type` 按格式（`text/plain; charset=utf-8`、`text/markdown; charset=utf-8`、`application/vnd.openxmlformats-officedocument.wordprocessingml.document`），`Content-Disposition: attachment; filename*=UTF-8''…`
- 文件名 `{书名}-{对照|译文|原文}.{txt|md|docx}`，清洗 Windows 非法字符
- 错误码：新增 `EXPORT_WORK_NOT_FOUND`（书名不存在或无可用段）
- 不做 ES 交互：导出只读 PG

## 5. 文件格式与排版

三个渲染器实现同一接口 `BookRenderer { extension(), contentType(), render(BookDocument, mode) → byte[] }`：

| 格式 | 结构 | 对照模式排版 | 编码/细节 |
|---|---|---|---|
| DOCX（POI XWPF，已有依赖） | 书名居中大字，作者/译者副行，章标题加粗大号（不用样式表，直接设字体） | 原文段灰色（#666666）小一号，译文段黑色常规；占位符灰色 | 二进制 |
| Markdown | `# 书名`、`## 章` | 原文用 `> ` 引用块，译文正文 | UTF-8 无 BOM，LF |
| TXT | 书名行、`【章名】`行 | 原文与译文紧凑成对（对内不空行），对与对之间空行 | UTF-8 带 BOM（防旧记事本乱码），CRLF |

仅译文 / 仅原文模式下三种格式均为常规段落输出，无对照标记。

## 6. 前端设计（Vue 3 + Element Plus）

新页面 `/export`「成书导出」：

- 路由守卫：在现有 `/admin → ADMIN` 规则旁增加 `/export → EDITOR/ADMIN`
- 导航菜单新增入口（EDITOR+ 可见），延续"典籍书斋"视觉风格
- 页面：左侧书单卡片（书名、章数、总段数、有译文段数——原始计数）→ 选中书弹出导出面板：
  - 顶部成书完成度进度条（pairedUnits / units 配对口径，见 §4）
  - 模式三选一（仅译文 / 原文译文对照 / 仅原文，默认对照）
  - 格式三选一（TXT / Markdown / Word，默认 Word）
  - 逐章配对统计表（三类段计数、配对数、警告以醒目色展示）
  - 「导出下载」按钮 → axios blob 请求 → 触发浏览器下载
- `src/api/http.ts` 为 blob 响应加旁路（现有拦截器默认解包 JSON，需按 `responseType` 跳过）

## 7. 测试计划

| 对象 | 用例 |
|---|---|
| `BookAssembler`（纯 JUnit） | 纯分侧拉链配对 / SRC-DST 计数不齐残留占位 / 混源章警告 / 全 FULL 直通 / 空书与无章节书 / 章序按最小 id / 同章重名合并（记录现行为） |
| 三渲染器 | 各模式输出内容断言；DOCX 用 XWPF 读回验证段落数与文本；TXT 验证 BOM 与 CRLF |
| `ExportService`（Mockito） | 书不存在抛 `EXPORT_WORK_NOT_FOUND`；权限由控制器注解保障（沿用现有模式，不做切片测试） |
| 手动冒烟 | 原文侧导入一本书 + 译文侧导入另一本 → 预览警告 → 三格式下载人工检查 |

## 8. 未来演进（不在本期）

1. **排序键 / Work-Chapter 实体**：出现中途补录、章节重名、多轮重导需求时升级；`BookAssembler` 是唯一顺序决策点，替换成本低
2. **导入即配对**：译文侧导入时直接回填既有原文段的 `translated_text`，从数据层消灭导出时配对错位问题
3. **EPUB 生成**：jsoup + ZipOutputStream 反向生成，无需新依赖
4. 拖拽调序、按筛选条件导出
