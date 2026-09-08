package com.transdb.importer.doc;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** PDF 文本抽取（PDFBox）。PDF 无段落结构，交给通用启发式切分器按句末标点/空行合并段落。 */
@Component
public class PdfParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    @Override
    public ParsedDocument parse(String filename, byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            String text = new PDFTextStripper().getText(doc);
            // 换页符视同空行；页眉/页脚/页码在合并段落前先剔除
            List<String> lines = text.lines().flatMap(l -> java.util.Arrays.stream(l.split("\f")))
                    .toList();
            List<String> clean = TextSegmenter.dropNoiseLines(lines);
            return new ParsedDocument(trimToNull(info.getTitle()), trimToNull(info.getAuthor()),
                    TextSegmenter.segmentLines(clean, true));
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE,
                    "PDF 解析失败: " + rootMessage(e));
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
