package com.transdb.importer.doc;

/**
 * 整本书/文档解析器：把 EPUB、PDF、Word、TXT 等格式抽成 {@link ParsedDocument}。
 * 实现须自行捕获解析异常并转成 IMPORT_FILE_UNREADABLE，不向外抛受检异常。
 */
public interface DocumentParser {

    boolean supports(String filename);

    ParsedDocument parse(String filename, byte[] content);
}
