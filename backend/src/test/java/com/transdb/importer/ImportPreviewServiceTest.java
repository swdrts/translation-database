package com.transdb.importer;

import com.transdb.AbstractIntegrationTest;
import com.transdb.common.ContentHash;
import com.transdb.domain.Role;
import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.dto.ImportPreviewVO;
import com.transdb.repository.SegmentRepository;
import com.transdb.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ImportPreviewServiceTest extends AbstractIntegrationTest {

    @Autowired ImportPreviewService previewService;
    @Autowired SegmentRepository segmentRepository;

    private static final String TWO_ROWS_JSON = """
            [
              {"source_text":"%s","translated_text":"%s"},
              {"source_text":"%s","translated_text":"%s"}
            ]
            """;

    private ByteArrayInputStream rows(String... values) {
        return new ByteArrayInputStream(TWO_ROWS_JSON.formatted(values).getBytes(StandardCharsets.UTF_8));
    }

    private LoginUser principal(com.transdb.domain.SysUser user) {
        return new LoginUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }

    /** 模拟首次导入已确认执行：把两条语料直接落库，制造后续文件的库内重复。 */
    private void persistRows(com.transdb.domain.SysUser creator, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            Segment s = new Segment();
            s.setSourceText(pairs[i]);
            s.setTranslatedText(pairs[i + 1]);
            s.setStatus(SegmentStatus.PUBLISHED);
            s.setContentHash(ContentHash.sha256(pairs[i], pairs[i + 1]));
            s.setCreatedBy(creator);
            segmentRepository.save(s);
        }
    }

    @Test
    void duplicateStrategiesProduceCorrectPlans() {
        var editor = createUser(Role.EDITOR);

        // 首次预览（库内为空）：两行均为新数据
        ImportPreviewVO first = previewService.buildPreview("a.json",
                rows("仁者爱人X", "The benevolent", "仁者爱人Y", "love others"),
                DuplicateStrategy.SKIP, principal(editor));
        assertThat(first.willImportRows()).isEqualTo(2);

        // 模拟首次导入确认执行后，库内已有这两条 → 制造库内重复
        persistRows(editor, "仁者爱人X", "The benevolent", "仁者爱人Y", "love others");

        // SKIP：库内重复 + 文件内重复 → 第二个文件全部视为重复
        ImportPreviewVO skip = previewService.buildPreview("b.json",
                rows("仁者爱人X", "The benevolent", "仁者爱人X", "The benevolent"),
                DuplicateStrategy.SKIP, principal(editor));
        assertThat(skip.skippedRows()).isEqualTo(2);
        assertThat(skip.willImportRows()).isZero();

        // KEEP：重复照常导入
        ImportPreviewVO keep = previewService.buildPreview("c.json",
                rows("仁者爱人X", "The benevolent", "仁者爱人X", "The benevolent"),
                DuplicateStrategy.KEEP, principal(editor));
        assertThat(keep.willImportRows()).isEqualTo(2);

        // OVERWRITE：两条都覆盖库内（X/Y 均已在库，且 hash 互不相同，不构成文件内重复）
        ImportPreviewVO overwrite = previewService.buildPreview("d.json",
                rows("仁者爱人X", "The benevolent", "仁者爱人Y", "love others"),
                DuplicateStrategy.OVERWRITE, principal(editor));
        assertThat(overwrite.overwriteRows()).isEqualTo(2);
        assertThat(overwrite.duplicates().toString()).contains("existingId=");
    }
}
