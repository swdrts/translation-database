package com.transdb.importer.doc;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * EPUB 解析：EPUB 本质是 ZIP + 一组 XHTML。按 container.xml → OPF 的 spine 顺序
 * 抽取正文，dc:title/dc:creator 作为书名/作者，正文 h1-h6 作为章节标题。
 */
@Component
public class EpubParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".epub");
    }

    @Override
    public ParsedDocument parse(String filename, byte[] content) {
        Map<String, byte[]> entries = unzip(content);
        String opfPath = findOpfPath(entries);
        if (opfPath == null) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE,
                    "这不是有效的 EPUB 文件（缺少 OPF 描述文件）");
        }
        Document opf = parseXml(entries.get(opfPath));
        String baseDir = opfPath.contains("/")
                ? opfPath.substring(0, opfPath.lastIndexOf('/')) : "";

        String title = metadataText(opf, "title");
        String author = metadataText(opf, "creator");

        // manifest：id → (zip 内路径, 媒体类型)
        Map<String, String[]> manifest = new HashMap<>();
        for (Element item : opf.select("manifest item")) {
            String href = item.attr("href");
            if (!href.isBlank()) {
                manifest.put(item.attr("id"),
                        new String[]{resolvePath(baseDir, href), item.attr("media-type")});
            }
        }

        // spine 顺序即阅读顺序
        List<String> docPaths = new ArrayList<>();
        for (Element ref : opf.select("spine itemref")) {
            String[] item = manifest.get(ref.attr("idref"));
            if (item != null && isHtmlDoc(item[0], item[1])) {
                docPaths.add(item[0]);
            }
        }
        if (docPaths.isEmpty()) {
            // 容错：spine 不可用时按文件名顺序读所有 XHTML
            entries.keySet().stream().filter(EpubParser::isHtmlPath).sorted().forEach(docPaths::add);
        }

        List<ParsedDocument.DocChapter> chapters = new ArrayList<>();
        for (String path : docPaths) {
            byte[] docBytes = entries.get(path);
            if (docBytes == null) {
                continue;
            }
            chapters.addAll(HtmlTextExtractor.chaptersFrom(parseHtml(docBytes)));
        }
        return new ParsedDocument(title, author, chapters);
    }

    private static String metadataText(Document opf, String localName) {
        Element metadata = opf.selectFirst("metadata");
        if (metadata == null) {
            return null;
        }
        for (Element child : metadata.children()) {
            String tag = child.tagName();
            if (tag.equalsIgnoreCase(localName) || tag.toLowerCase(Locale.ROOT).endsWith(":" + localName)) {
                String text = child.text().strip();
                return text.isEmpty() ? null : text;
            }
        }
        return null;
    }

    private static boolean isHtmlDoc(String path, String mediaType) {
        if (mediaType != null && mediaType.toLowerCase(Locale.ROOT).contains("html")) {
            return true;
        }
        return isHtmlPath(path);
    }

    private static boolean isHtmlPath(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".xhtml") || p.endsWith(".html") || p.endsWith(".htm");
    }

    private static String findOpfPath(Map<String, byte[]> entries) {
        byte[] container = entries.get("META-INF/container.xml");
        if (container == null) {
            return null;
        }
        Element rootfile = parseXml(container).selectFirst("rootfile");
        String fullPath = rootfile == null ? null : rootfile.attr("full-path");
        return fullPath == null || fullPath.isBlank() || !entries.containsKey(fullPath)
                ? null : fullPath;
    }

    private static String resolvePath(String baseDir, String href) {
        String h = href.strip();
        try {
            h = URLDecoder.decode(h, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // 含非法转义时按原样处理
        }
        int hash = h.indexOf('#');
        if (hash >= 0) {
            h = h.substring(0, hash);
        }
        if (h.startsWith("/")) {
            return h.substring(1);
        }
        if (baseDir.isEmpty()) {
            return h;
        }
        return java.nio.file.Paths.get(baseDir).resolve(h).normalize().toString().replace('\\', '/');
    }

    private static Document parseXml(byte[] bytes) {
        try {
            return Jsoup.parse(new ByteArrayInputStream(bytes), null, "", Parser.xmlParser());
        } catch (IOException e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "EPUB 内部 XML 解析失败");
        }
    }

    private static Document parseHtml(byte[] bytes) {
        try {
            return Jsoup.parse(new ByteArrayInputStream(bytes), null, "");
        } catch (IOException e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "EPUB 内部文档解析失败");
        }
    }

    private static Map<String, byte[]> unzip(byte[] content) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName().replace('\\', '/'), zip.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE,
                    "EPUB 无法解压，文件可能已损坏或不是 EPUB 格式");
        }
        return entries;
    }
}
