package com.transdb.exporter;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.domain.Segment;
import com.transdb.dto.ExportPreviewVO;
import com.transdb.dto.ExportWorksItemVO;
import com.transdb.exporter.render.BookRenderer;
import com.transdb.repository.SegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 成书导出：书单统计、配对预览、文件生成。只读 PG，不触碰 ES。
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private final SegmentRepository segmentRepository;
    private final BookAssembler assembler;
    private final List<BookRenderer> renderers;

    @Transactional(readOnly = true)
    public List<ExportWorksItemVO> listWorks() {
        return segmentRepository.aggregateWorkStats().stream()
                .map(p -> new ExportWorksItemVO(p.getWorkTitle(), p.getChapters(),
                        p.getTotalSegments(), p.getTranslatedSegments()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExportPreviewVO preview(String workTitle) {
        BookStats s = assembleBook(workTitle).stats();
        return new ExportPreviewVO(s.segments(), s.units(), s.pairedUnits(),
                s.chapters().stream()
                        .map(c -> new ExportPreviewVO.ChapterStatVO(c.title(), c.full(), c.src(),
                                c.dst(), c.paired(), c.warnings()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public ExportedFile generate(String workTitle, ExportMode mode, ExportFormat format) {
        AssembledBook book = assembleBook(workTitle);
        BookRenderer renderer = renderers.stream()
                .filter(r -> r.format() == format).findFirst()
                .orElseThrow(() -> BusinessException.of(ErrorCode.VALIDATION_FAILED,
                        "不支持的导出格式: " + format));
        String filename = sanitize(book.document().title()) + "-" + mode.label() + "." + renderer.extension();
        return new ExportedFile(filename, renderer.contentType(),
                renderer.render(book.document(), mode));
    }

    private AssembledBook assembleBook(String workTitle) {
        if (workTitle == null || workTitle.isBlank()) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "workTitle 不能为空");
        }
        List<Segment> segments = segmentRepository.findByWorkTitleOrderByIdAsc(workTitle.strip());
        if (segments.isEmpty()) {
            throw BusinessException.of(ErrorCode.EXPORT_WORK_NOT_FOUND);
        }
        return assembler.assemble(segments);
    }

    private String sanitize(String name) {
        return name == null ? "book" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
