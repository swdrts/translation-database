# 批量导入（Batch Import）实施计划 — Plan 3/4

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 两阶段批量导入（上传校验预览 → 确认执行），支持 JSON/CSV/Excel，含库内/文件内重复策略（skip/overwrite/keep）、逐行校验报告、按批事务写入、导入后批量 ES 同步——支撑 50 万条语料入库目标。

**Architecture:** 新代码集中在 `com.transdb.importer` 包。阶段一：解析（按文件扩展名选择 parser）→ 逐行校验 → `ContentHash` 查重（库内分块批查 + 文件内去重）→ 按策略生成行计划 → 内存预览会话（UUID，属主绑定，30 分钟 TTL）。阶段二：确认后 JDBC 批量插入（SimpleJdbcInsert 每批 1000，TransactionTemplate 每批短事务）、OVERWRITE 走 JPA 更新、标签 resolve-or-create；**JDBC 路径不经过 SegmentService，因此不发 SegmentChangedEvent，由 `EsSyncService.bulkUpsert(ids)` 按批显式同步**（同步失败仅告警，由对账兜底）。

**Tech Stack:** Apache POI 5.3.0（.xlsx）、commons-csv 1.11.0（RFC4180 引号）、Jackson（JSON，已有）、SimpleJdbcInsert/TransactionTemplate（已有设施）、Testcontainers。

## Global Constraints

- 设计文档 §8 批量导入设计为绑定章节；Excel/CSV 列头约定：`source_text, translated_text, work_title, chapter, author, dynasty, translator, notes, tags`（tags 为 `|` 分隔）；JSON 键接受 snake_case 与 camelCase 两种别名
- API（§7）：`POST /api/v1/segments/import`（multipart，参数 `file` + `duplicateStrategy`，EDITOR+）→ `ImportPreviewVO`；`POST /api/v1/segments/import/{previewId}/confirm`（EDITOR+，仅会话创建者）→ `ImportResultVO`
- 文件 ≤50MB（multipart 配置）、单文件行数 ≤100000（超出 → 400/3002 整文件拒绝）；预览 TTL 30 分钟（惰性过期 → 404/3003）
- 新错误码（3xxx 导入段，插入在 `SEGMENT_FORBIDDEN` 与 `USERNAME_EXISTS` 之间）：`IMPORT_FILE_UNREADABLE(400,3001,"导入文件无法解析或格式不受支持")`、`IMPORT_FILE_TOO_LARGE(400,3002,"导入文件超出大小或行数上限")`、`IMPORT_PREVIEW_NOT_FOUND(404,3003,"导入预览不存在或已过期")`、`IMPORT_PREVIEW_FORBIDDEN(403,3004,"只能确认自己创建的导入预览")`、`IMPORT_NO_ROWS(400,3005,"导入文件中没有数据行")`
- 重复策略：`skip`（默认，跳过库内重复与文件内重复的后出现行）、`overwrite`（用行内容覆盖库内条目——JPA 更新含标签替换；文件内重复为后写胜）、`keep`（重复照常导入）
- 行校验：source_text/translated_text 非空；work_title/chapter/author/translator ≤255；dynasty ≤64；标签按 `|` 切分、trim、非空、每个 ≤64；违规行进错误报告不阻断他行
- 同步纪律：绝不把 ES I/O 包进 DB 事务；`BulkResponseGuard` 从 ReindexService 提取为共享工具（ReindexService 调用点同步更新，既有测试 `bulkItemErrorsAreDetectedAndReported` 改调新位置）
- 测试纪律：唯一 nanoTime 数据；Awaitility 轮询 ES；既有 68 测试不回退；用户已批准本项目全部 git 操作（子代理不执行 push，由主控推送）

---

### Task 1: 依赖、配置、错误码、模型与 JSON 解析器

**Files:**
- Modify: `backend/pom.xml`（POI 5.3.0 + commons-csv 1.11.0）
- Modify: `backend/src/main/resources/application.yml`（multipart + transdb.import 配置）
- Modify: `backend/src/main/java/com/transdb/common/ErrorCode.java`（5 个 3xxx 错误码）
- Create: `backend/src/main/java/com/transdb/importer/ImportProperties.java`
- Create: `backend/src/main/java/com/transdb/importer/DuplicateStrategy.java`
- Create: `backend/src/main/java/com/transdb/importer/ParsedRow.java`
- Create: `backend/src/main/java/com/transdb/importer/ColumnNormalizer.java`
- Create: `backend/src/main/java/com/transdb/importer/JsonImporter.java`
- Create: `backend/src/main/java/com/transdb/importer/FileParser.java`
- Modify: `backend/src/main/java/com/transdb/config/AsyncSchedulingConfig.java`（@EnableConfigurationProperties 追加 ImportProperties）
- Test: `backend/src/test/java/com/transdb/common/ContentHashGoldenTest.java`
- Test: `backend/src/test/java/com/transdb/importer/JsonImporterTest.java`

**Interfaces:**
- Produces: `DuplicateStrategy { SKIP, OVERWRITE, KEEP }`
- Produces: `record ParsedRow(int lineNumber, Map<String, String> fields)`——字段键为规范化列名（`source_text` 等），值为字符串（无字段时缺键）；`ParsedRow.get(String column)` 便捷取值（null 安全）
- Produces: `ColumnNormalizer.normalize(String raw)`——trim、小写、空格/连字符转下划线、camelCase 转 snake_case
- Produces: `FileParser { boolean supports(String filename); List<ParsedRow> parse(InputStream in) }`；`JsonImporter.supports` = 文件名小写以 `.json` 结尾；解析结果非对象数组或元素非对象 → `BusinessException(IMPORT_FILE_UNREADABLE)`
- Produces: `ImportProperties(maxRows long, previewTtlMinutes long)`（`transdb.import.*`）
- Produces: `ContentHash` 金标值锁定（分隔符字节序列防回归）

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/common/ContentHashGoldenTest.java`：

```java
package com.transdb.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class ContentHashGoldenTest {

    @Test
    void goldenVectorPinsExactByteSequence() throws Exception {
        // 金标：独立构造期望值（sourceText + '\u0000' + translatedText 的 UTF-8 字节），
        // 钉死分隔符与拼接顺序——防止未来实现悄悄改变字节序列
        byte[] expected = MessageDigest.getInstance("SHA-256")
                .digest("学而\u0000To learn".getBytes(StandardCharsets.UTF_8));
        assertThat(ContentHash.sha256("学而", "To learn"))
                .isEqualTo(HexFormat.of().formatHex(expected));
    }
}
```

`backend/src/test/java/com/transdb/importer/JsonImporterTest.java`：

```java
package com.transdb.importer;

import com.transdb.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonImporterTest {

    private final JsonImporter importer = new JsonImporter();

    @Test
    void supportsJsonExtensionOnly() {
        assertThat(importer.supports("corpus.json")).isTrue();
        assertThat(importer.supports("CORpus.JSON")).isTrue();
        assertThat(importer.supports("corpus.csv")).isFalse();
        assertThat(importer.supports("no-extension")).isFalse();
    }

    @Test
    void parsesArrayWithSnakeAndCamelKeys() {
        String json = """
                [
                  {"source_text":"学而时习之","translatedText":"To learn","tags":["儒家","教育"]},
                  {"source_text":"有朋自远方来","translated_text":"Friends from afar","work_title":"论语"}
                ]
                """;
        List<ParsedRow> rows = importer.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).lineNumber()).isEqualTo(1);
        assertThat(rows.get(0).get("source_text")).isEqualTo("学而时习之");
        assertThat(rows.get(0).get("translated_text")).isEqualTo("To learn");
        assertThat(rows.get(0).get("tags")).isEqualTo("儒家|教育");
        assertThat(rows.get(1).get("work_title")).isEqualTo("论语");
        assertThat(rows.get(1).get("chapter")).isNull();
    }

    @Test
    void jsonTagsListBecomesPipeJoined() {
        String json = """
                [{"source_text":"a","translated_text":"b","tags":["儒家","道家"]}]
                """;
        List<ParsedRow> rows = importer.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(rows.get(0).get("tags")).isEqualTo("儒家|道家");
    }

    @Test
    void nonArrayOrNonObjectRejectedAsUnreadable() {
        assertThatThrownBy(() -> importer.parse(new ByteArrayInputStream(
                "{\"not\":\"an array\"}".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo(3001);
        assertThatThrownBy(() -> importer.parse(new ByteArrayInputStream(
                "[\"just\",\"strings\"]".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest='ContentHashGoldenTest,JsonImporterTest'`
Expected: 编译失败（importer 包不存在；ContentHashGoldenTest 的金标本应 GREEN——ContentHash 已是 '\u0000' 语义，若 RED 说明 Plan 2 Task 2 有回归，停下排查）

- [ ] **Step 3: 实现**

`backend/pom.xml` 在 jjwt 依赖块之后追加：

```xml
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
            <version>5.3.0</version>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-csv</artifactId>
            <version>1.11.0</version>
        </dependency>
```

`backend/src/main/resources/application.yml` 在 `spring:` 块内（elasticsearch 之后）追加：

```yaml
  servlet:
    multipart:
      max-file-size: ${TRANSDB_IMPORT_MAX_FILE_SIZE:50MB}
      max-request-size: ${TRANSDB_IMPORT_MAX_REQUEST_SIZE:55MB}
```

在 `transdb:` 块内（es 之后）追加：

```yaml
  import:
    max-rows: ${TRANSDB_IMPORT_MAX_ROWS:100000}
    preview-ttl-minutes: ${TRANSDB_IMPORT_PREVIEW_TTL_MINUTES:30}
```

`backend/src/main/java/com/transdb/common/ErrorCode.java`：在 `SEGMENT_FORBIDDEN` 与 `USERNAME_EXISTS` 之间插入：

```java
    IMPORT_FILE_UNREADABLE(HttpStatus.BAD_REQUEST, 3001, "导入文件无法解析或格式不受支持"),
    IMPORT_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, 3002, "导入文件超出大小或行数上限"),
    IMPORT_PREVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, 3003, "导入预览不存在或已过期"),
    IMPORT_PREVIEW_FORBIDDEN(HttpStatus.FORBIDDEN, 3004, "只能确认自己创建的导入预览"),
    IMPORT_NO_ROWS(HttpStatus.BAD_REQUEST, 3005, "导入文件中没有数据行"),
```

`backend/src/main/java/com/transdb/importer/ImportProperties.java`：

```java
package com.transdb.importer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transdb.import")
public record ImportProperties(long maxRows, long previewTtlMinutes) {
}
```

`backend/src/main/java/com/transdb/importer/DuplicateStrategy.java`：

```java
package com.transdb.importer;

public enum DuplicateStrategy { SKIP, OVERWRITE, KEEP }
```

`backend/src/main/java/com/transdb/importer/ParsedRow.java`：

```java
package com.transdb.importer;

import java.util.Map;

/** 一条解析后的数据行；fields 的键为规范化列名（如 source_text）。lineNumber 从 1 计（不含表头）。 */
public record ParsedRow(int lineNumber, Map<String, String> fields) {

    public String get(String column) {
        return fields.get(column);
    }
}
```

`backend/src/main/java/com/transdb/importer/ColumnNormalizer.java`：

```java
package com.transdb.importer;

import java.util.Locale;

public final class ColumnNormalizer {

    private ColumnNormalizer() {
    }

    /** 表头/JSON 键 → 规范列名：trim、小写、空格/连字符转下划线、camelCase 转 snake_case。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace(' ', '_').replace('-', '_');
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return s.toLowerCase(Locale.ROOT);
    }
}
```

`backend/src/main/java/com/transdb/importer/FileParser.java`：

```java
package com.transdb.importer;

import java.io.InputStream;
import java.util.List;

public interface FileParser {

    boolean supports(String filename);

    List<ParsedRow> parse(InputStream in);
}
```

`backend/src/main/java/com/transdb/importer/JsonImporter.java`：

```java
package com.transdb.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JsonImporter implements FileParser {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) {
        JsonNode root;
        try {
            root = objectMapper.readTree(in);
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "JSON 解析失败: " + e.getMessage());
        }
        if (!root.isArray()) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "JSON 顶层必须是对象数组");
        }
        List<ParsedRow> rows = new ArrayList<>();
        int lineNumber = 0;
        for (JsonNode element : root) {
            lineNumber++;
            if (!element.isObject()) {
                throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "第 " + lineNumber + " 个元素不是对象");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            element.fields().forEachRemaining(e -> fields.put(ColumnNormalizer.normalize(e.getKey()),
                    e.getValue().isNull() ? null : e.getValue().asText()));
            if (element.has("tags") && element.get("tags").isArray()) {
                List<String> tags = new ArrayList<>();
                element.get("tags").forEach(t -> tags.add(t.asText()));
                fields.put("tags", String.join("|", tags));
            }
            rows.add(new ParsedRow(lineNumber, fields));
        }
        return rows;
    }
}
```

`backend/src/main/java/com/transdb/config/AsyncSchedulingConfig.java` 的 `@EnableConfigurationProperties` 改为：

```java
@EnableConfigurationProperties({EsProperties.class, com.transdb.importer.ImportProperties.class})
```

（import 语句按文件风格补齐。）

- [ ] **Step 4: 运行测试确认通过 + 全量**

Run: `cd backend && mvn test`
Expected: 69/69 PASS（68 + ContentHashGoldenTest 1 + JsonImporterTest 4，总计以实际为准）

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): 导入基础——POI/CSV 依赖、3xxx 错误码、模型与 JSON 解析器"
```

---

### Task 2: CSV 与 Excel 解析器

**Files:**
- Create: `backend/src/main/java/com/transdb/importer/CsvImporter.java`
- Create: `backend/src/main/java/com/transdb/importer/ExcelImporter.java`
- Test: `backend/src/test/java/com/transdb/importer/CsvImporterTest.java`
- Test: `backend/src/test/java/com/transdb/importer/ExcelImporterTest.java`

**Interfaces:**
- Produces: `CsvImporter`（commons-csv RFC4180：引号/转义/内嵌换行；首行为表头；表头经 ColumnNormalizer 规范化；`.csv` 后缀）
- Produces: `ExcelImporter`（POI WorkbookFactory，首个 sheet，第 1 行为表头，DataFormatter 取显示值；`.xlsx`/`.xls` 后缀）
- 两者均：空文件/无表头/无数据行 → 返回空列表（由上层预览服务统一判 IMPORT_NO_ROWS）；单元格 null → 缺键

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/importer/CsvImporterTest.java`：

```java
package com.transdb.importer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvImporterTest {

    private final CsvImporter importer = new CsvImporter();

    @Test
    void supportsCsvExtensionOnly() {
        assertThat(importer.supports("a.csv")).isTrue();
        assertThat(importer.supports("a.CSV")).isTrue();
        assertThat(importer.supports("a.json")).isFalse();
    }

    @Test
    void parsesHeaderAndQuotedFields() {
        String csv = """
                source_text,translated_text,work_title,tags
                "学而时习之，不亦说乎？","Is it not pleasant to learn, and practice?",论语,儒家|教育
                "有朋自远方来","He said: ""welcome""",论语,儒家
                """;
        List<ParsedRow> rows = importer.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("translated_text")).isEqualTo("Is it not pleasant to learn, and practice?");
        assertThat(rows.get(1).get("translated_text")).isEqualTo("He said: \"welcome\"");
        assertThat(rows.get(0).get("tags")).isEqualTo("儒家|教育");
        assertThat(rows.get(0).lineNumber()).isEqualTo(1);
    }

    @Test
    void normalizesCamelHeaderAndMissingCells() {
        String csv = """
                sourceText,Translated Text,chapter
                a,b,1
                """;
        List<ParsedRow> rows = importer.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(rows.get(0).get("source_text")).isEqualTo("a");
        assertThat(rows.get(0).get("translated_text")).isEqualTo("b");
        assertThat(rows.get(0).get("chapter")).isEqualTo("1");
        assertThat(rows.get(0).get("tags")).isNull();
    }

    @Test
    void headerOnlyYieldsEmptyList() {
        String csv = "source_text,translated_text\n";
        assertThat(importer.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)))).isEmpty();
    }
}
```

`backend/src/test/java/com/transdb/importer/ExcelImporterTest.java`：

```java
package com.transdb.importer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelImporterTest {

    private final ExcelImporter importer = new ExcelImporter();

    @Test
    void supportsExcelExtensions() {
        assertThat(importer.supports("a.xlsx")).isTrue();
        assertThat(importer.supports("a.XLS")).isTrue();
        assertThat(importer.supports("a.csv")).isFalse();
    }

    @Test
    void parsesHeaderRowAndDataCells() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("语料");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("source_text");
            header.createCell(1).setCellValue("translatedText");
            header.createCell(2).setCellValue("dynasty");
            header.createCell(3).setCellValue("tags");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("学而时习之");
            data.createCell(1).setCellValue("To learn");
            data.createCell(2).setCellValue(476); // 数值单元格按显示值取
            data.createCell(3).setCellValue("儒家|教育");
            wb.write(out);
            bytes = out.toByteArray();
        }

        List<ParsedRow> rows = importer.parse(new ByteArrayInputStream(bytes));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("source_text")).isEqualTo("学而时习之");
        assertThat(rows.get(0).get("translated_text")).isEqualTo("To learn");
        assertThat(rows.get(0).get("dynasty")).isEqualTo("476");
        assertThat(rows.get(0).get("tags")).isEqualTo("儒家|教育");
    }

    @Test
    void emptySheetYieldsEmptyList() {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("空表");
            wb.write(out);
            bytes = out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(importer.parse(new ByteArrayInputStream(bytes))).isEmpty();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest='CsvImporterTest,ExcelImporterTest'`
Expected: 编译失败

- [ ] **Step 3: 实现**

`backend/src/main/java/com/transdb/importer/CsvImporter.java`：

```java
package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class CsvImporter implements FileParser {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) {
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .build();
            List<String> header = format.parse(reader).getHeaderNames().stream()
                    .map(ColumnNormalizer::normalize).toList();
            Iterable<CSVRecord> records = format.parse(new InputStreamReader(
                    new java.io.ByteArrayInputStream(readAll(in)), StandardCharsets.UTF_8));
            List<ParsedRow> rows = new ArrayList<>();
            int lineNumber = 0;
            for (CSVRecord record : records) {
                lineNumber++;
                Map<String, String> fields = new LinkedHashMap<>();
                for (int i = 0; i < header.size() && i < record.size(); i++) {
                    fields.put(header.get(i), record.get(i));
                }
                rows.add(new ParsedRow(lineNumber, fields));
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "CSV 解析失败: " + e.getMessage());
        }
    }

    private byte[] readAll(InputStream in) throws java.io.IOException {
        return in.readAllBytes();
    }
}
```

（实现者注意：上面 `parse` 里对同一流读了两次——`format.parse(reader)` 只为取表头、再对字节数组二次 parse 取数据是浪费且有 bug 风险。以一次 parse 为准实现：先把流读进 byte[]，用 `CSVFormat.Builder` 的 `setHeader()` 让 commons-csv 自动以首记录为表头，遍历 `records` 时通过 `record.getParser()`/`record.toList()` 配合 header 名组装。保持行为与测试一致即可；上图为意图示意，若 commons-csv API 细节不同（如 `getHeaderNames` 需在 parse 后获取），按其真实 API 调整并在报告说明。）

`backend/src/main/java/com/transdb/importer/ExcelImporter.java`：

```java
package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ExcelImporter implements FileParser {

    private final DataFormatter formatter = new DataFormatter();

    @Override
    public boolean supports(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) {
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<ParsedRow> rows = new ArrayList<>();
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                return rows;
            }
            int columns = headerRow.getLastCellNum();
            List<String> header = new ArrayList<>();
            for (int c = 0; c < columns; c++) {
                header.add(ColumnNormalizer.normalize(formatter.formatCellValue(headerRow.getCell(c))));
            }
            int lineNumber = 0;
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                lineNumber++;
                Map<String, String> fields = new LinkedHashMap<>();
                for (int c = 0; c < columns; c++) {
                    String value = formatter.formatCellValue(row.getCell(c));
                    if (value != null && !value.isBlank()) {
                        fields.put(header.get(c), value);
                    }
                }
                rows.add(new ParsedRow(lineNumber, fields));
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "Excel 解析失败: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过 + 全量**

Run: `cd backend && mvn test`
Expected: 75/75 PASS（69 + 4 + 2... 以实际方法数为准，全绿即可）

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): CSV 与 Excel 导入解析器"
```

---

### Task 3: 行校验、重复策略与预览会话

**Files:**
- Create: `backend/src/main/java/com/transdb/importer/ParsedRowValidator.java`
- Create: `backend/src/main/java/com/transdb/importer/ImportRowPlan.java`
- Create: `backend/src/main/java/com/transdb/importer/ImportPreviewStore.java`
- Create: `backend/src/main/java/com/transdb/importer/ImportPreviewService.java`
- Test: `backend/src/test/java/com/transdb/importer/ParsedRowValidatorTest.java`
- Test: `backend/src/test/java/com/transdb/importer/ImportPreviewServiceTest.java`

**Interfaces:**
- Produces: `ParsedRowValidator.validate(ParsedRow) → List<String> errors`（空 = 通过）：source_text/translated_text 非空；work_title/chapter/author/translator ≤255；dynasty ≤64；tags 按 `|` 切分 trim、跳过空 token、非空 token ≤64（违规即行错误）
- Produces: `record ImportRowPlan(PlanType type, ParsedRow row, Long existingSegmentId, String contentHash)`，`enum PlanType { IMPORT, OVERWRITE, SKIP }`
- Produces: `ImportPreviewStore`（@Component）：`String create(long operatorId, DuplicateStrategy strategy, List<ImportRowPlan> rows, int totalRows, List<LineError> errors)` → UUID；`ImportPreviewSession get(String id)`（惰性过期：TTL 外视为不存在并移除 → null）；`ImportPreviewSession`（公开 record 式访问：id/operatorId/strategy/rows/totalRows/errors/createdAt）
- Produces: `ImportPreviewService.buildPreview(filename, InputStream, DuplicateStrategy, LoginUser) → ImportPreviewVO`：
  1. 选择 parser（遍历注入的 `List<FileParser>`，无匹配 → 3001）
  2. 解析 → 空数据行 → 3005；行数 > maxRows → 3002
  3. 逐行校验 → 错误行进 errors（不阻断）
  4. 有效行算 `ContentHash.sha256(source, translated)`；文件内去重（同 hash 后出现行，SKIP/OVERWRITE 策略下标记为文件内重复、KEEP 下保留）；库内批查（分块 1000 `WHERE content_hash IN`）→ 命中策略：SKIP→SKIP 行计划、OVERWRITE→OVERWRITE（带 existingId）、KEEP→IMPORT
  5. 组装 `ImportPreviewVO{previewId, strategy, totalRows, willImportRows, overwriteRows, skippedRows, errors: List<LineError>, duplicates: List<LineError>}`（duplicates 的 reason 含 `existingId=` 或 `文件内重复`）并存会话
- Consumes: `dto.LineError(int line, String reason)`（本任务创建于 dto 包）

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/importer/ParsedRowValidatorTest.java`：

```java
package com.transdb.importer;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedRowValidatorTest {

    private ParsedRow row(Map<String, String> fields) {
        return new ParsedRow(1, fields);
    }

    @Test
    void validRowHasNoErrors() {
        Map<String, String> f = new HashMap<>();
        f.put("source_text", "学而时习之");
        f.put("translated_text", "To learn");
        f.put("dynasty", "先秦");
        f.put("tags", "儒家|教育|");
        assertThat(ParsedRowValidator.validate(row(f))).isEmpty();
    }

    @Test
    void blankRequiredFieldsReported() {
        Map<String, String> f = new HashMap<>();
        f.put("source_text", "  ");
        List<String> errors = ParsedRowValidator.validate(row(f));
        assertThat(errors).hasSize(2); // source_text 与 translated_text 都缺
        assertThat(errors.get(0)).contains("source_text");
    }

    @Test
    void overlongFieldsReported() {
        Map<String, String> f = new HashMap<>();
        f.put("source_text", "s");
        f.put("translated_text", "t");
        f.put("dynasty", "朝".repeat(65));
        f.put("work_title", "书".repeat(256));
        List<String> errors = ParsedRowValidator.validate(row(f));
        assertThat(errors).hasSize(2);
        assertThat(errors.toString()).contains("dynasty").contains("work_title");
    }

    @Test
    void overlongTagReportedButValidTagsSplit() {
        Map<String, String> f = new HashMap<>();
        f.put("source_text", "s");
        f.put("translated_text", "t");
        f.put("tags", "ok|" + "长".repeat(65));
        List<String> errors = ParsedRowValidator.validate(row(f));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("tags");
    }
}
```

`backend/src/test/java/com/transdb/importer/ImportPreviewServiceTest.java`：

```java
package com.transdb.importer;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import com.transdb.dto.ImportPreviewVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ImportPreviewServiceTest extends AbstractIntegrationTest {

    @Autowired ImportPreviewService previewService;

    private ByteArrayInputStream json(String template, String source, String translated) {
        String body = template.formatted(source, translated);
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void duplicateStrategiesProduceCorrectPlans() {
        var editor = createUser(Role.EDITOR);
        String json = """
                [
                  {"source_text":"%s","translated_text":"%s"},
                  {"source_text":"%s","translated_text":"%s"}
                ]
                """;
        // 先导入一次，制造库内重复
        ImportPreviewVO first = previewService.buildPreview("a.json",
                json.formatted("仁者爱人X", "The benevolent", "仁者爱人Y", "love others"),
                DuplicateStrategy.SKIP, new com.transdb.security.LoginUser(editor.getId(),
                        editor.getUsername(), editor.getDisplayName(), editor.getRole()));
        previewService.get(first.previewId()).ifPresent(s ->
                new com.transdb.importer.ImportPreviewServiceTestSupport().noop());

        // SKIP：库内重复 + 文件内重复 → 第二个文件全部视为重复
        ImportPreviewVO skip = previewService.buildPreview("b.json",
                json.formatted("仁者爱人X", "The benevolent", "仁者爱人X", "The benevolent"),
                DuplicateStrategy.SKIP, principal(editor));
        assertThat(skip.skippedRows()).isEqualTo(2);
        assertThat(skip.willImportRows()).isZero();

        // KEEP：重复照常导入
        ImportPreviewVO keep = previewService.buildPreview("c.json",
                json.formatted("仁者爱人X", "The benevolent", "仁者爱人X", "The benevolent"),
                DuplicateStrategy.KEEP, principal(editor));
        assertThat(keep.willImportRows()).isEqualTo(2);

        // OVERWRITE：两条都覆盖库内
        ImportPreviewVO overwrite = previewService.buildPreview("d.json",
                json.formatted("仁者爱人X", "The benevolent", "仁者爱人X", "The benevolent"),
                DuplicateStrategy.OVERWRITE, principal(editor));
        assertThat(overwrite.overwriteRows()).isEqualTo(2);
        assertThat(overwrite.duplicates().toString()).contains("existingId=");
    }

    private com.transdb.security.LoginUser principal(var user) {
        return new com.transdb.security.LoginUser(user.getId(), user.getUsername(),
                user.getDisplayName(), user.getRole());
    }
}
```

（实现者注意：`ImportPreviewServiceTestSupport.noop()` 是上面草稿的残留噪音——删掉那两行 `previewService.get(...).ifPresent(...)` 与该内部类引用，直接断言 first 预览的 willImportRows == 2 即可。）

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest='ParsedRowValidatorTest,ImportPreviewServiceTest'`
Expected: 编译失败

- [ ] **Step 3: 实现**

`backend/src/main/java/com/transdb/dto/LineError.java`：

```java
package com.transdb.dto;

public record LineError(int line, String reason) {
}
```

`backend/src/main/java/com/transdb/dto/ImportPreviewVO.java`：

```java
package com.transdb.dto;

import java.util.List;

public record ImportPreviewVO(String previewId, String strategy, int totalRows,
                              int willImportRows, int overwriteRows, int skippedRows,
                              List<LineError> errors, List<LineError> duplicates) {
}
```

`backend/src/main/java/com/transdb/importer/ParsedRowValidator.java`：

```java
package com.transdb.importer;

import java.util.ArrayList;
import java.util.List;

public final class ParsedRowValidator {

    private ParsedRowValidator() {
    }

    public static List<String> validate(ParsedRow row) {
        List<String> errors = new ArrayList<>();
        requireNonBlank(row, "source_text", errors);
        requireNonBlank(row, "translated_text", errors);
        requireMaxLen(row, "work_title", 255, errors);
        requireMaxLen(row, "chapter", 255, errors);
        requireMaxLen(row, "author", 255, errors);
        requireMaxLen(row, "translator", 255, errors);
        requireMaxLen(row, "dynasty", 64, errors);
        String tags = row.get("tags");
        if (tags != null && !tags.isBlank()) {
            for (String tag : tags.split("\\|")) {
                String t = tag.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.length() > 64) {
                    errors.add("tags 标签超长（≤64）: " + t);
                }
            }
        }
        return errors;
    }

    private static void requireNonBlank(ParsedRow row, String column, List<String> errors) {
        String v = row.get(column);
        if (v == null || v.isBlank()) {
            errors.add(column + " 不能为空");
        }
    }

    private static void requireMaxLen(ParsedRow row, String column, int max, List<String> errors) {
        String v = row.get(column);
        if (v != null && v.length() > max) {
            errors.add(column + " 超长（≤" + max + "）");
        }
    }
}
```

`backend/src/main/java/com/transdb/importer/ImportRowPlan.java`：

```java
package com.transdb.importer;

public record ImportRowPlan(PlanType type, ParsedRow row, Long existingSegmentId, String contentHash) {

    public enum PlanType { IMPORT, OVERWRITE, SKIP }
}
```

`backend/src/main/java/com/transdb/importer/ImportPreviewStore.java`：

```java
package com.transdb.importer;

import com.transdb.dto.LineError;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ImportPreviewStore {

    public record ImportPreviewSession(String id, long operatorId, DuplicateStrategy strategy,
                                       List<ImportRowPlan> rows, int totalRows,
                                       List<LineError> errors, Instant createdAt) {
    }

    private final Map<String, ImportPreviewSession> sessions = new ConcurrentHashMap<>();
    private final ImportProperties properties;

    public ImportPreviewStore(ImportProperties properties) {
        this.properties = properties;
    }

    public String create(long operatorId, DuplicateStrategy strategy, List<ImportRowPlan> rows,
                         int totalRows, List<LineError> errors) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new ImportPreviewSession(id, operatorId, strategy, rows, totalRows,
                errors, Instant.now()));
        return id;
    }

    /** 惰性过期：TTL 外视为不存在并移除。 */
    public ImportPreviewSession get(String id) {
        if (id == null) {
            return null;
        }
        ImportPreviewSession session = sessions.get(id);
        if (session == null) {
            return null;
        }
        long ttlMs = properties.previewTtlMinutes() * 60_000;
        if (Instant.now().toEpochMilli() - session.createdAt().toEpochMilli() > ttlMs) {
            sessions.remove(id);
            return null;
        }
        return session;
    }
}
```

`backend/src/main/java/com/transdb/importer/ImportPreviewService.java`：

```java
package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ContentHash;
import com.transdb.common.ErrorCode;
import com.transdb.domain.Segment;
import com.transdb.dto.ImportPreviewVO;
import com.transdb.dto.LineError;
import com.transdb.repository.SegmentRepository;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImportPreviewService {

    private final List<FileParser> parsers;
    private final SegmentRepository segmentRepository;
    private final ImportPreviewStore previewStore;
    private final ImportProperties importProperties;

    public ImportPreviewVO buildPreview(String filename, InputStream in,
                                        DuplicateStrategy strategy, LoginUser operator) {
        FileParser parser = parsers.stream().filter(p -> p.supports(filename)).findFirst()
                .orElseThrow(() -> BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE,
                        "不支持的文件类型: " + filename));
        List<ParsedRow> parsed = parser.parse(in);
        if (parsed.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS);
        }
        if (parsed.size() > importProperties.maxRows()) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_TOO_LARGE,
                    "行数 " + parsed.size() + " 超过单次导入上限 " + importProperties.maxRows());
        }

        List<LineError> errors = new ArrayList<>();
        List<ParsedRow> validRows = new ArrayList<>();
        for (ParsedRow row : parsed) {
            List<String> rowErrors = ParsedRowValidator.validate(row);
            if (rowErrors.isEmpty()) {
                validRows.add(row);
            } else {
                rowErrors.forEach(reason -> errors.add(new LineError(row.lineNumber(), reason)));
            }
        }

        // 文件内去重：同 hash 的后出现行（SKIP/OVERWRITE 策略下）视为文件内重复
        Map<String, ParsedRow> seenInFile = new LinkedHashMap<>();
        List<ParsedRow> deduped = new ArrayList<>();
        List<LineError> duplicates = new ArrayList<>();
        boolean dedupeInFile = strategy != DuplicateStrategy.KEEP;
        Set<Integer> inFileDupLines = new HashSet<>();
        if (dedupeInFile) {
            for (ParsedRow row : validRows) {
                String hash = ContentHash.sha256(row.get("source_text"), row.get("translated_text"));
                ParsedRow first = seenInFile.putIfAbsent(hash, row);
                if (first != null) {
                    inFileDupLines.add(row.lineNumber());
                    duplicates.add(new LineError(row.lineNumber(), "文件内重复（与第 " + first.lineNumber() + " 行相同）"));
                } else {
                    deduped.add(row);
                }
            }
        } else {
            deduped.addAll(validRows);
        }

        // 库内批查
        Map<String, Long> existingByHash = findExistingByHash(deduped);

        List<ImportRowPlan> plans = new ArrayList<>();
        int willImport = 0;
        int overwrite = 0;
        int skipped = inFileDupLines.size();
        for (ParsedRow row : deduped) {
            String hash = ContentHash.sha256(row.get("source_text"), row.get("translated_text"));
            Long existingId = existingByHash.get(hash);
            if (existingId == null) {
                plans.add(new ImportRowPlan(ImportRowPlan.PlanType.IMPORT, row, null, hash));
                willImport++;
            } else {
                duplicates.add(new LineError(row.lineNumber(), "库内重复（existingId=" + existingId + "）"));
                switch (strategy) {
                    case SKIP -> {
                        plans.add(new ImportRowPlan(ImportRowPlan.PlanType.SKIP, row, existingId, hash));
                        skipped++;
                    }
                    case OVERWRITE -> {
                        plans.add(new ImportRowPlan(ImportRowPlan.PlanType.OVERWRITE, row, existingId, hash));
                        overwrite++;
                    }
                    case KEEP -> {
                        plans.add(new ImportRowPlan(ImportRowPlan.PlanType.IMPORT, row, existingId, hash));
                        willImport++;
                    }
                }
            }
        }

        String previewId = previewStore.create(operator.id(), strategy, plans, parsed.size(), errors);
        return new ImportPreviewVO(previewId, strategy.name(), parsed.size(), willImport,
                overwrite, skipped, errors, duplicates);
    }

    private Map<String, Long> findExistingByHash(List<ParsedRow> rows) {
        Map<String, Long> result = new HashMap<>();
        List<String> hashes = rows.stream()
                .map(r -> ContentHash.sha256(r.get("source_text"), r.get("translated_text")))
                .toList();
        for (int i = 0; i < hashes.size(); i += 1000) {
            List<String> chunk = hashes.subList(i, Math.min(i + 1000, hashes.size()));
            for (Segment s : segmentRepository.findByContentHashIn(chunk)) {
                result.put(s.getContentHash(), s.getId());
            }
        }
        return result;
    }
}
```

`backend/src/main/java/com/transdb/repository/SegmentRepository.java` 接口体追加：

```java
    java.util.List<Segment> findByContentHashIn(java.util.List<String> hashes);
```

同时 `AsyncSchedulingConfig` 的 `@EnableConfigurationProperties` 已在 Task 1 覆盖 `ImportProperties`（若实现者把 `ImportProperties` 从 `@ConfigurationProperties` 扫描中遗漏，此处补上）。

- [ ] **Step 4: 运行测试确认通过 + 全量**

Run: `cd backend && mvn test`
Expected: 全绿（78+，以实际为准；ImportPreviewServiceTest 是首个需要 PG 的 importer 测试）

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): 行校验、重复策略与导入预览会话"
```

---

### Task 4: 上传预览端点（multipart 接入）

**Files:**
- Create: `backend/src/main/java/com/transdb/controller/ImportController.java`
- Test: `backend/src/test/java/com/transdb/controller/ImportControllerTest.java`

**Interfaces:**
- Consumes: Task 2/3 的 parser 与预览服务
- Produces: `POST /api/v1/segments/import`（multipart：`file` 必填 + `duplicateStrategy` 可选默认 SKIP；EDITOR+）→ `ApiResponse<ImportPreviewVO>`；不支持的策略值 → Spring 400（MethodArgumentTypeMismatch）；无 token → 401/1002；VIEWER → 403/9004
- Produces: `DuplicateStrategy` 的 Spring 转换——枚举名大小写不敏感（`@RequestParam` 默认 `ConversionService` 对枚举做 `value_of`，需大写；为友好起见在 Controller 手动 `DuplicateStrategy.valueOf(upper)`，非法值 → 400/9001）

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/controller/ImportControllerTest.java`：

```java
package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ImportControllerTest extends AbstractIntegrationTest {

    private HttpEntity<MultiValueMap<String, Object>> multipart(String token, String filename,
                                                                String content, String strategy) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.substring(7));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource file = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("file", file);
        if (strategy != null) {
            body.add("duplicateStrategy", strategy);
        }
        return new HttpEntity<>(body, headers);
    }

    @Test
    void editorUploadsJsonPreview() {
        var editor = createUser(Role.EDITOR);
        String json = """
                [{"source_text":"导入预览测试%s","translated_text":"preview","tags":["儒家"]}]
                """.formatted(System.nanoTime());
        ResponseEntity<String> res = rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(bearer(editor), "corpus.json", json, null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"previewId\"");
        assertThat(res.getBody()).contains("\"willImportRows\":1");
        assertThat(res.getBody()).contains("\"strategy\":\"SKIP\"");
    }

    @Test
    void blankFileRejected3005() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(bearer(editor), "empty.json", "[]", null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("\"code\":3005");
    }

    @Test
    void unsupportedExtensionRejected3001() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(bearer(editor), "corpus.txt", "hello", null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("\"code\":3001");
    }

    @Test
    void viewerForbidden9004AndAnonymous401() {
        var viewer = createUser(Role.VIEWER);
        ResponseEntity<String> viewerRes = rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(bearer(viewer), "a.json", "[]", null), String.class);
        assertThat(viewerRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewerRes.getBody()).contains("\"code\":9004");

        ResponseEntity<String> anon = rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(null, "a.json", "[]", null), String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anon.getBody()).contains("\"code\":1002");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=ImportControllerTest`
Expected: FAIL（404 / 编译失败）

- [ ] **Step 3: 实现 ImportController（仅上传预览阶段；confirm 在 Task 5 追加）**

`backend/src/main/java/com/transdb/controller/ImportController.java`：

```java
package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.dto.ImportPreviewVO;
import com.transdb.importer.DuplicateStrategy;
import com.transdb.importer.ImportPreviewService;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/segments/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportPreviewService importPreviewService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportPreviewVO> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "SKIP") String duplicateStrategy,
            @AuthenticationPrincipal LoginUser operator) throws IOException {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS);
        }
        DuplicateStrategy strategy;
        try {
            strategy = DuplicateStrategy.valueOf(duplicateStrategy.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED,
                    "duplicateStrategy 只支持 SKIP/OVERWRITE/KEEP");
        }
        ImportPreviewVO preview = importPreviewService.buildPreview(
                file.getOriginalFilename(), file.getInputStream(), strategy, operator);
        return ApiResponse.ok(preview);
    }
}
```

- [ ] **Step 4: 运行测试确认通过 + 全量**

Run: `cd backend && mvn test`
Expected: 全绿（82+）

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): 导入上传预览端点"
```

---

### Task 5: 确认执行引擎与批量 ES 同步

**Files:**
- Create: `backend/src/main/java/com/transdb/search/BulkResponseGuard.java`
- Modify: `backend/src/main/java/com/transdb/search/ReindexService.java`（requireNoBulkErrors 移至 BulkResponseGuard，调用点更新）
- Modify: `backend/src/test/java/com/transdb/search/ReindexTest.java`（`bulkItemErrorsAreDetectedAndReported` 改调 `BulkResponseGuard.requireNoErrors`）
- Modify: `backend/src/main/java/com/transdb/search/EsSyncService.java`（追加 bulkUpsert）
- Modify: `backend/src/main/java/com/transdb/repository/SegmentRepository.java`（追加 findByIdForSyncIn fetch-join）
- Create: `backend/src/main/java/com/transdb/importer/ImportExecutor.java`
- Modify: `backend/src/main/java/com/transdb/controller/ImportController.java`（追加 confirm 端点）
- Test: `backend/src/test/java/com/transdb/controller/ImportConfirmTest.java`

**Interfaces:**
- Produces: `BulkResponseGuard.requireNoErrors(JsonNode bulkResult, int expectedDocs)`（原 ReindexService.requireNoBulkErrors 语义平移：`errors:true` 时抛 IllegalStateException，含 failed 计数）
- Produces: `EsSyncService.bulkUpsert(List<Long> segmentIds) → int`（每批 500：`findByIdForSyncIn` fetch-join 组装 → `POST /_bulk` → `BulkResponseGuard.requireNoErrors`；批失败仅 WARN 由对账兜底；disabled/空 → 0）
- Produces: `ImportExecutor.execute(ImportPreviewStore.ImportPreviewSession, LoginUser) → ImportResultVO`：
  - IMPORT 行：每批 1000，`TransactionTemplate` 短事务内 `SimpleJdbcInsert`（列含 status='PUBLISHED'、version=0、content_hash、created_by=operator、created_at/updated_at=now()，setGeneratedKeyName("id") 取回主键）+ 标签 resolve-or-create（TagRepository.findByName → create，缓存 Map）+ `segment_tag` 批插（`ON CONFLICT DO NOTHING`）；批失败 → 整批行进 failed 报告，继续后续批
  - OVERWRITE 行：每批 100，TransactionTemplate 内 `segmentRepository.findById`（JPA 更新：字段覆盖 + 标签替换 + version 由 @Version 自增）→ 计 overwritten
  - 全部成功/失败行收集完后：`esSyncService.bulkUpsert(所有写入的 id)`（同步失败仅告警）
  - 返回 `ImportResultVO(imported, overwritten, skipped, failed)`
- Produces: `POST /api/v1/segments/import/{previewId}/confirm`（EDITOR+；会话不存在/过期 → 404/3003；非创建者 → 403/3004；确认后会话移除防重放）

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/controller/ImportConfirmTest.java`：

```java
package com.transdb.controller;

import com.jayway.jsonpath.JsonPath;
import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ImportConfirmTest extends AbstractIntegrationTest {

    private HttpEntity<MultiValueMap<String, Object>> multipart(String token, String filename,
                                                                String content, String strategy) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token.substring(7));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        if (strategy != null) body.add("duplicateStrategy", strategy);
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> upload(String token, String json, String strategy) {
        return rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(token, "corpus.json", json, strategy), String.class);
    }

    private ResponseEntity<String> confirm(String token, String previewId) {
        return rest.exchange("/api/v1/segments/import/" + previewId + "/confirm",
                HttpMethod.POST, new HttpEntity<Void>(auth(token)), String.class);
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.substring(7));
        return headers;
    }

    @Test
    void fullFlowImportThenSearchableInEs() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "导入端到端_" + System.nanoTime();
        String json = """
                [
                  {"source_text":"%s","translated_text":"imported one","work_title":"论语导入","tags":["儒家导入标签|教育"]},
                  {"source_text":"%s","translated_text":"imported two"},
                  {"source_text":"","translated_text":"bad row"}
                ]
                """.formatted(marker, marker + "_2");

        ResponseEntity<String> previewRes = upload(token, json, null);
        assertThat(previewRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        String previewId = JsonPath.read(previewRes.getBody(), "$.data.previewId").toString();
        assertThat((Integer) JsonPath.read(previewRes.getBody(), "$.data.willImportRows")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(previewRes.getBody(), "$.data.errors.size()")).isEqualTo(1);

        ResponseEntity<String> confirmRes = confirm(token, previewId);
        assertThat(confirmRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(confirmRes.getBody(), "$.data.imported")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(confirmRes.getBody(), "$.data.failed.size()")).isEqualTo(1);
        assertThat(confirmRes.getBody()).contains("source_text 不能为空");

        // PG 落库
        ResponseEntity<String> list = rest.exchange("/api/v1/segments?q=&size=50", HttpMethod.GET,
                new HttpEntity<Void>(auth(token)), String.class);
        assertThat(list.getBody()).contains(marker);

        // ES 可搜（导入路径显式批量同步）
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ResponseEntity<String> search = rest.exchange("/api/v1/search?q=" + marker,
                    HttpMethod.GET, new HttpEntity<Void>(auth(token)), String.class);
            assertThat(search.getBody()).contains(marker).doesNotContain("\"degraded\":true");
        });

        // 标签自动创建
        ResponseEntity<String> tags = rest.exchange("/api/v1/tags", HttpMethod.GET,
                new HttpEntity<Void>(auth(token)), String.class);
        assertThat(tags.getBody()).contains("儒家导入标签");

        // 确认后预览被消费：重复确认 → 404/3003
        ResponseEntity<String> replay = confirm(token, previewId);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(replay.getBody()).contains("\"code\":3003");
    }

    @Test
    void skipStrategySkipsDatabaseDuplicates() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String json = """
                [{"source_text":"重复跳过%s","translated_text":"dup test"}]
                """.formatted(System.nanoTime());

        String previewId = JsonPath.read(upload(token, json, null).getBody(), "$.data.previewId").toString();
        assertThat((Integer) JsonPath.read(confirm(token, previewId).getBody(), "$.data.imported")).isEqualTo(1);

        // 再导同一内容，SKIP → 全部跳过
        AtomicReference<String> secondPreview = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            // 等 ES/PG 稳定后再传第二份
            secondPreview.set(JsonPath.read(upload(token, json, null).getBody(), "$.data.previewId").toString());
            assertThat(secondPreview.get()).isNotEmpty();
        });
        ResponseEntity<String> secondConfirm = confirm(token, secondPreview.get());
        assertThat((Integer) JsonPath.read(secondConfirm.getBody(), "$.data.skipped")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(secondConfirm.getBody(), "$.data.imported")).isZero();
    }

    @Test
    void overwriteStrategyUpdatesExistingSegments() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String json1 = """
                [{"source_text":"覆盖前%s","translated_text":"before overwrite","work_title":"旧书名"}]
                """.formatted(System.nanoTime());
        String previewId1 = JsonPath.read(upload(token, json1, null).getBody(), "$.data.previewId").toString();
        confirm(token, previewId1);

        String json2 = """
                [{"source_text":"覆盖前%s","translated_text":"after overwrite","work_title":"新书名"}]
                """.formatted(jsonMarker(json1));
        String previewId2 = JsonPath.read(upload(token, json2, "OVERWRITE").getBody(), "$.data.previewId").toString();
        ResponseEntity<String> confirmRes = confirm(token, previewId2);
        assertThat((Integer) JsonPath.read(confirmRes.getBody(), "$.data.overwritten")).isEqualTo(1);

        ResponseEntity<String> list = rest.exchange("/api/v1/segments?work=新书名", HttpMethod.GET,
                new HttpEntity<Void>(auth(token)), String.class);
        assertThat(list.getBody()).contains("after overwrite").contains("新书名");
    }

    private String jsonMarker(String json1) {
        // 提取 json1 的 nanoTime 标记，使第二份文件的 source_text 与第一份完全一致（同 hash）
        return json1.substring(json1.indexOf("覆盖前") + 3, json1.indexOf("\"", json1.indexOf("覆盖前")));
    }

    @Test
    void foreignUserCannotConfirmOthersPreview() {
        var editorA = createUser(Role.EDITOR);
        var editorB = createUser(Role.EDITOR);
        String json = """
                [{"source_text":"他人预览%s","translated_text":"x"}]
                """.formatted(System.nanoTime());
        String previewId = JsonPath.read(upload(bearer(editorA), json, null).getBody(), "$.data.previewId").toString();

        ResponseEntity<String> res = confirm(bearer(editorB), previewId);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("\"code\":3004");
    }

    @Test
    void unknownPreviewReturns404Code3003() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = confirm(bearer(editor), "nonexistent-preview-id");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("\"code\":3003");
    }
}
```

（实现者注意：`jsonMarker` 辅助依赖 JSON 里源文本以 `覆盖前` 开头后接 nanoTime——若你觉得脆弱，可改为把 marker 存局部变量拼两个 JSON，语义等价、更清晰；二选一，测试意图不变：两份文件 source_text+translated_text 完全一致 → 同 content_hash。）

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=ImportConfirmTest`
Expected: FAIL（confirm 端点 404）

- [ ] **Step 3: 实现**

`backend/src/main/java/com/transdb/search/BulkResponseGuard.java`（从 ReindexService 平移；ReindexService 内原 `requireNoBulkErrors` 静态方法删除、调用点改为 `BulkResponseGuard.requireNoErrors(...)`，ReindexTest 对应断言调用点同步更新）：

```java
package com.transdb.search;

import com.fasterxml.jackson.databind.JsonNode;

public final class BulkResponseGuard {

    private BulkResponseGuard() {
    }

    /** ES /_bulk 对单项失败返回 HTTP 200 + errors:true——必须显式校验，否则静默丢文档。 */
    public static void requireNoErrors(JsonNode bulkResult, int expectedDocs) {
        if (bulkResult.path("errors").asBoolean(false)) {
            int failed = 0;
            String firstError = "";
            for (JsonNode item : bulkResult.path("items")) {
                JsonNode err = item.path("index").path("error");
                if (err.isObject()) {
                    failed++;
                    if (firstError.isEmpty()) {
                        firstError = err.path("type").asText();
                    }
                }
            }
            throw new IllegalStateException("bulk 部分失败：items=" + expectedDocs
                    + " failed=" + failed + " firstError=" + firstError);
        }
    }
}
```

`backend/src/main/java/com/transdb/repository/SegmentRepository.java` 接口体追加：

```java
    @org.springframework.data.jpa.repository.Query(
            "select s from Segment s left join fetch s.tags where s.id in :ids")
    java.util.List<Segment> findByIdForSyncIn(@org.springframework.data.repository.query.Param("ids")
                                              java.util.List<Long> ids);
```

`backend/src/main/java/com/transdb/search/EsSyncService.java` 追加方法（import 补 `org.elasticsearch.client.Response`）：

```java
    /** 导入路径的批量同步：JDBC 写入不产生事件，须显式调用。失败仅告警（对账兜底）。 */
    public int bulkUpsert(List<Long> segmentIds) {
        if (!esProperties.enabled() || segmentIds == null || segmentIds.isEmpty()) {
            return 0;
        }
        int indexed = 0;
        for (int i = 0; i < segmentIds.size(); i += 500) {
            List<Long> chunk = segmentIds.subList(i, Math.min(i + 500, segmentIds.size()));
            try {
                indexed += bulkUpsertChunk(chunk);
            } catch (Exception e) {
                log.warn("导入批量同步失败 chunkSize={}: {}", chunk.size(), e.getMessage());
            }
        }
        return indexed;
    }

    private int bulkUpsertChunk(List<Long> ids) {
        List<Segment> segments = segmentRepository.findByIdForSyncIn(ids);
        if (segments.isEmpty()) {
            return 0;
        }
        try {
            StringBuilder ndjson = new StringBuilder();
            for (Segment s : segments) {
                ndjson.append("{\"index\":{\"_index\":\"").append(EsIndexAdminService.ALIAS)
                        .append("\",\"_id\":\"").append(s.getId()).append("\"}}\n")
                        .append(objectMapper.writeValueAsString(assembler.toDoc(s))).append('\n');
            }
            Request bulk = new Request("POST", "/_bulk");
            bulk.setJsonEntity(ndjson.toString());
            Response resp = restClient.performRequest(bulk);
            JsonNode result = objectMapper.readTree(resp.getEntity().getContent());
            com.transdb.search.BulkResponseGuard.requireNoErrors(result, segments.size());
            return segments.size();
        } catch (Exception e) {
            throw new IllegalStateException("bulk upsert 失败", e);
        }
    }
```

（import 补 `com.transdb.domain.Segment`、`com.fasterxml.jackson.databind.JsonNode`、`java.util.List` 按文件现状。）

`backend/src/main/java/com/transdb/importer/ImportExecutor.java`：

```java
package com.transdb.importer;

import com.transdb.common.ApiResponse;
import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.domain.Tag;
import com.transdb.dto.ImportResultVO;
import com.transdb.dto.LineError;
import com.transdb.repository.SegmentRepository;
import com.transdb.repository.TagRepository;
import com.transdb.search.EsSyncService;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportExecutor {

    private static final int BATCH_SIZE = 1000;

    private final ImportPreviewStore previewStore;
    private final SegmentRepository segmentRepository;
    private final TagRepository tagRepository;
    private final TransactionTemplate transactionTemplate;
    private final EsSyncService esSyncService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public ImportResultVO execute(ImportPreviewStore.ImportPreviewSession session, LoginUser operator) {
        List<LineError> failed = new ArrayList<>();
        List<Long> writtenIds = new ArrayList<>();
        int imported = 0;
        int overwritten = 0;

        List<ImportRowPlan> importRows = session.rows().stream()
                .filter(p -> p.type() == ImportRowPlan.PlanType.IMPORT).toList();
        for (int i = 0; i < importRows.size(); i += BATCH_SIZE) {
            List<ImportRowPlan> batch = importRows.subList(i, Math.min(i + BATCH_SIZE, importRows.size()));
            try {
                List<Long> ids = transactionTemplate.execute(tx ->
                        insertBatch(batch, operator, failed));
                imported += ids == null ? 0 : ids.size();
                if (ids != null) {
                    writtenIds.addAll(ids);
                }
            } catch (Exception e) {
                log.warn("导入批次失败（{} 行）: {}", batch.size(), e.getMessage());
                batch.forEach(plan -> failed.add(new LineError(plan.row().lineNumber(),
                        "写入失败: " + rootMessage(e))));
            }
        }

        List<ImportRowPlan> overwriteRows = session.rows().stream()
                .filter(p -> p.type() == ImportRowPlan.PlanType.OVERWRITE).toList();
        for (int i = 0; i < overwriteRows.size(); i += 100) {
            List<ImportRowPlan> batch = overwriteRows.subList(i, Math.min(i + 100, overwriteRows.size()));
            try {
                List<Long> ids = transactionTemplate.execute(tx ->
                        overwriteBatch(batch, operator, failed));
                overwritten += ids == null ? 0 : ids.size();
                if (ids != null) {
                    writtenIds.addAll(ids);
                }
            } catch (Exception e) {
                log.warn("覆盖批次失败（{} 行）: {}", batch.size(), e.getMessage());
                batch.forEach(plan -> failed.add(new LineError(plan.row().lineNumber(),
                        "覆盖失败: " + rootMessage(e))));
            }
        }

        int skipped = (int) session.rows().stream()
                .filter(p -> p.type() == ImportRowPlan.PlanType.SKIP).count();
        esSyncService.bulkUpsert(writtenIds);
        return new ImportResultVO(imported, overwritten, skipped, failed);
    }

    /** 批插入 + 标签关联；在同一短事务内完成。返回生成的 segment id。 */
    private List<Long> insertBatch(List<ImportRowPlan> batch, LoginUser operator, List<LineError> failed) {
        Map<String, Tag> tagCache = new HashMap<>();
        SimpleJdbcInsert insert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("segment")
                .usingColumns("source_text", "translated_text", "work_title", "chapter", "author",
                        "dynasty", "translator", "notes", "status", "version", "content_hash",
                        "created_by", "created_at", "updated_at")
                .usingGeneratedKeyColumns("id");

        List<Long> ids = new ArrayList<>();
        List<long[]> segmentTagPairs = new ArrayList<>();
        for (ImportRowPlan plan : batch) {
            var row = plan.row();
            Timestamp now = Timestamp.from(Instant.now());
            Number id = insert.executeAndReturnKey(Map.ofEntries(
                    Map.entry("source_text", row.get("source_text")),
                    Map.entry("translated_text", row.get("translated_text")),
                    Map.entry("work_title", blankToNull(row.get("work_title"))),
                    Map.entry("chapter", blankToNull(row.get("chapter"))),
                    Map.entry("author", blankToNull(row.get("author"))),
                    Map.entry("dynasty", blankToNull(row.get("dynasty"))),
                    Map.entry("translator", blankToNull(row.get("translator"))),
                    Map.entry("notes", blankToNull(row.get("notes"))),
                    Map.entry("status", SegmentStatus.PUBLISHED.name()),
                    Map.entry("version", 0),
                    Map.entry("content_hash", plan.contentHash()),
                    Map.entry("created_by", operator.id()),
                    Map.entry("created_at", now),
                    Map.entry("updated_at", now)));
            long segmentId = id.longValue();
            ids.add(segmentId);
            for (String tagName : splitTags(row.get("tags"))) {
                Tag tag = resolveOrCreateTag(tagName, tagCache);
                segmentTagPairs.add(new long[]{segmentId, tag.getId()});
            }
        }
        if (!segmentTagPairs.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO segment_tag(segment_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    segmentTagPairs, Math.max(1, segmentTagPairs.size()),
                    (ps, pair) -> {
                        ps.setLong(1, pair[0]);
                        ps.setLong(2, pair[1]);
                    });
        }
        return ids;
    }

    private List<Long> overwriteBatch(List<ImportRowPlan> batch, LoginUser operator, List<LineError> failed) {
        List<Long> ids = new ArrayList<>();
        Map<String, Tag> tagCache = new HashMap<>();
        for (ImportRowPlan plan : batch) {
            var row = plan.row();
            Segment s = segmentRepository.findById(plan.existingSegmentId())
                    .orElseThrow(() -> new IllegalStateException(
                            "库内条目已不存在 id=" + plan.existingSegmentId()));
            s.setSourceText(row.get("source_text"));
            s.setTranslatedText(row.get("translated_text"));
            s.setWorkTitle(blankToNull(row.get("work_title")));
            s.setChapter(blankToNull(row.get("chapter")));
            s.setAuthor(blankToNull(row.get("author")));
            s.setDynasty(blankToNull(row.get("dynasty")));
            s.setTranslator(blankToNull(row.get("translator")));
            s.setNotes(blankToNull(row.get("notes")));
            s.setContentHash(plan.contentHash());
            s.getTags().clear();
            for (String tagName : splitTags(row.get("tags"))) {
                s.getTags().add(resolveOrCreateTag(tagName, tagCache));
            }
            segmentRepository.save(s);
            ids.add(s.getId());
        }
        return ids;
    }

    private Tag resolveOrCreateTag(String name, Map<String, Tag> cache) {
        Tag cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        Tag tag = tagRepository.findByName(name).orElseGet(() -> {
            Tag t = new Tag();
            t.setName(name);
            return tagRepository.save(t);
        });
        cache.put(name, tag);
        return tag;
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String t : tags.split("\\|")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
```

（`ImportResultVO` 创建于 dto 包：

```java
package com.transdb.dto;

import java.util.List;

public record ImportResultVO(int imported, int overwritten, int skipped, List<LineError> failed) {
}
```

`SimpleJdbcInsert.executeAndReturnKey(Map.ofEntries(...))` 中 `version` 为 int 装箱、时间戳为 Timestamp——PostgreSQL 驱动均接受。`executeAndReturnKey` 逐行执行（非真批），10 万行约数分钟——如需更快可在评审后改 `batchUpdate`+KeyHolder，当前正确性优先并在报告注明。）

`backend/src/main/java/com/transdb/controller/ImportController.java` 追加 confirm 端点与依赖：

```java
    private final ImportExecutor importExecutor;
    private final ImportPreviewStore previewStore;

    @PostMapping("/{previewId}/confirm")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportResultVO> confirm(@PathVariable String previewId,
                                               @AuthenticationPrincipal LoginUser operator) {
        ImportPreviewStore.ImportPreviewSession session = previewStore.get(previewId);
        if (session == null) {
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_NOT_FOUND);
        }
        if (session.operatorId() != operator.id()) {
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_FORBIDDEN);
        }
        // 确认后立即移除，防重放；失败需重新上传预览
        ImportPreviewStore.ImportPreviewSession owned = previewStore.remove(previewId);
        return ApiResponse.ok(importExecutor.execute(owned, operator));
    }
```

（`ImportPreviewStore` 追加 `remove`：

```java
    public ImportPreviewSession remove(String id) {
        ImportPreviewSession session = get(id);
        if (session != null) {
            sessions.remove(id);
        }
        return session;
    }
```

confirm 的属主校验在 remove 之前用 `get` 完成；`remove` 内部再次 get（等价校验）。注意 `execute` 不再依赖 store 中的会话存续。）

- [ ] **Step 4: 运行测试确认通过 + 全量**

Run: `cd backend && mvn test`
Expected: 全绿（88+，以实际为准；既有 ReindexTest 亦须绿——requireNoBulkErrors 迁移后）

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): 导入确认引擎、批量 ES 同步与 BulkResponseGuard 提取"
```

---

### Task 6: 全量回归、README 导入章节与推送

**Files:**
- Modify: `README.md`

**Interfaces:**
- Produces: 里程碑完成

- [ ] **Step 1: 全量回归**

Run: `cd backend && mvn test`
Expected: BUILD SUCCESS 全绿。失败则 TDD 修复后重跑。

- [ ] **Step 2: README 更新**

在 API 表的 reindex 行之后追加：

```markdown
| POST /api/v1/segments/import、POST .../{previewId}/confirm | 两阶段批量导入（JSON/CSV/Excel，≤50MB/≤10 万行；重复策略 skip/overwrite/keep） | EDITOR+ |
```

- [ ] **Step 3: 提交并推送**

```bash
git add README.md && git commit -m "docs: README 补充批量导入 API"
git push
```

（push 由主控执行——实现者只做提交。）

---

## Self-Review 记录

1. **规格覆盖（Plan 3 范围）**：§8 两阶段→Task 4/5；§8 格式（JSON/CSV/Excel）→Task 1/2；§8 校验与重复检测（skip/overwrite/keep、文件内/库内）→Task 3；§7 端点与权限→Task 4/5；§3.2 content_hash 去重→Task 3（金标测试在 Task 1 补齐 Plan 2 终审移交项）；§9 逐行错误不整体回滚（按批提交）→Task 5；ES 同步（JDBC 不发事件→显式 bulkUpsert）→Task 5；BulkResponseGuard 提取（Plan 2 收尾项）→Task 5。
2. **占位符扫描**：无 TBD/TODO；CsvImporter 草图的双读流问题以内联"实现者注意"标注（一次解析为准）；ImportPreviewServiceTest 草稿残留噪音以"实现者注意"标注删除方式；其余代码完整。
3. **类型一致性**：`ParsedRow(lineNumber, fields)` 全文一致（测试的 `row.get(...)`、validator、executor 的 `plan.row()` 一致）；`ImportRowPlan(PlanType, row, existingSegmentId, contentHash)` 与 preview/executor 一致；`ImportPreviewStore.ImportPreviewSession` 与 controller/executor 一致（remove 语义防重放）；`LineError(line, reason)`、`ImportPreviewVO`、`ImportResultVO` 字段与测试断言逐一对齐；`BulkResponseGuard.requireNoErrors` 与 ReindexService/EsSyncService 调用一致；错误码 3001-3005 与测试断言一致。
4. **测试隔离**：全部沿用 nanoTime 唯一数据 + Awaitility；重复策略测试用 marker 字符串制造同 hash（不依赖跨测试状态）；覆盖测试路径：端到端（JSON 预览→确认→PG→ES→标签自动建→防重放）、skip 重复、overwrite 更新、属主 403、未知预览 404、权限矩阵、解析器单测（引号/camelCase/数值单元格）。
5. **已知取舍**：SimpleJdbcInsert 逐行 executeAndReturnKey（正确性优先，速度评审后可换 batchUpdate+KeyHolder）；确认后移除会话（失败需重新上传预览——报告语义已含失败行）；OVERWRITE 并发用户编辑为后写胜（内部导入操作，可接受）；导入同步失败仅告警（对账兜底）。
