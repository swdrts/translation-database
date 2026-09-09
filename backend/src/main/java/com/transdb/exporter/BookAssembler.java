package com.transdb.exporter;

import com.transdb.domain.Segment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 整本书段 → 书稿模型的装配器。入参必须已按 id 升序（导入顺序即书本顺序）。
 *
 * 章节顺序 = 各章最小段 id 的先后（LinkedHashMap 保持首现顺序）；章内顺序 = 段 id。
 * 配对算法（同章内）：
 * 1. 就位——按 id 顺序生成占位单元：完整段 (src,dst)、纯原文段 (src,null)、纯译文段 (null,dst)；
 * 2. 拉链——第 i 个纯原文单元与第 i 个纯译文单元合并，合并结果落在原文单元的位置
 *    （原文侧导入的 id 必然更小，位置即书本顺序），被合并的译文单元移除。
 * 已知边界（设计文档 §3）：混源章（完整段 + 单侧段并存）时拉链位置可能错位，输出警告请人工核对。
 */
@Component
public class BookAssembler {

    public AssembledBook assemble(List<Segment> segments) {
        Map<String, List<Segment>> byChapter = new LinkedHashMap<>();
        for (Segment s : segments) {
            byChapter.computeIfAbsent(normalize(s.getChapter()), k -> new ArrayList<>()).add(s);
        }

        List<BookDocument.BookChapter> chapters = new ArrayList<>();
        List<BookStats.ChapterStat> chapterStats = new ArrayList<>();
        for (Map.Entry<String, List<Segment>> e : byChapter.entrySet()) {
            List<Segment> members = e.getValue();

            // 阶段一：按 id 就位
            List<BookDocument.BookUnit> placed = new ArrayList<>(members.size());
            int full = 0, src = 0, dst = 0;
            for (Segment m : members) {
                String s = normalize(m.getSourceText());
                String t = normalize(m.getTranslatedText());
                if (s != null && t != null) {
                    placed.add(new BookDocument.BookUnit(s, t));
                    full++;
                } else if (s != null) {
                    placed.add(new BookDocument.BookUnit(s, null));
                    src++;
                } else if (t != null) {
                    placed.add(new BookDocument.BookUnit(null, t));
                    dst++;
                }
                // 双空段（理论上不存在）：跳过，不计入任何类别
            }

            // 阶段二：拉链合并——纯原文第 i 个 × 纯译文第 i 个
            List<Integer> srcOnlyIdx = new ArrayList<>();
            List<Integer> dstOnlyIdx = new ArrayList<>();
            for (int i = 0; i < placed.size(); i++) {
                BookDocument.BookUnit u = placed.get(i);
                if (u.translated() == null && u.source() != null) {
                    srcOnlyIdx.add(i);
                } else if (u.source() == null) {
                    dstOnlyIdx.add(i);
                }
            }
            int paired = Math.min(srcOnlyIdx.size(), dstOnlyIdx.size());
            Map<Integer, String> fillTranslated = new HashMap<>();
            Set<Integer> mergedDstIdx = new HashSet<>();
            for (int k = 0; k < paired; k++) {
                fillTranslated.put(srcOnlyIdx.get(k), placed.get(dstOnlyIdx.get(k)).translated());
                mergedDstIdx.add(dstOnlyIdx.get(k));
            }
            List<BookDocument.BookUnit> units = new ArrayList<>(placed.size() - paired);
            for (int i = 0; i < placed.size(); i++) {
                if (fillTranslated.containsKey(i)) {
                    units.add(new BookDocument.BookUnit(placed.get(i).source(), fillTranslated.get(i)));
                } else if (mergedDstIdx.contains(i)) {
                    continue; // 已被合并进原文单元的译文占位单元，移除
                } else {
                    units.add(placed.get(i));
                }
            }

            chapters.add(new BookDocument.BookChapter(e.getKey(), units));
            chapterStats.add(new BookStats.ChapterStat(e.getKey(), full, src, dst, paired,
                    warnings(full, src, dst, paired)));
        }

        String title = segments.isEmpty() ? null : segments.get(0).getWorkTitle();
        BookDocument doc = new BookDocument(title,
                segments.stream().map(Segment::getAuthor).map(this::normalize).filter(a -> a != null).findFirst().orElse(null),
                segments.stream().map(Segment::getTranslator).map(this::normalize).filter(t -> t != null).findFirst().orElse(null),
                chapters);
        int units = chapters.stream().mapToInt(c -> c.units().size()).sum();
        int pairedUnits = chapters.stream().flatMap(c -> c.units().stream())
                .mapToInt(u -> u.source() != null && u.translated() != null ? 1 : 0).sum();
        return new AssembledBook(doc, new BookStats(segments.size(), units, pairedUnits, chapterStats));
    }

    private List<String> warnings(int full, int src, int dst, int paired) {
        List<String> ws = new ArrayList<>();
        if (src != dst && src > 0 && dst > 0) {
            ws.add("原文 %d 段、译文 %d 段，配对 %d 段，未配上 %d 段".formatted(
                    src, dst, paired, src + dst - 2 * paired));
        }
        if (full > 0 && (src > 0 || dst > 0)) {
            ws.add("本章混有已译段与单侧导入段，配对位置可能错位，请人工核对");
        }
        return ws;
    }

    private String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
