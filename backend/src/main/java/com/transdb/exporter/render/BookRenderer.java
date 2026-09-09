package com.transdb.exporter.render;

import com.transdb.exporter.BookDocument;
import com.transdb.exporter.ExportFormat;
import com.transdb.exporter.ExportMode;

/** 书稿 → 文件字节。实现类注册为 Spring Bean，按 format() 选择。 */
public interface BookRenderer {

    ExportFormat format();

    String contentType();

    String extension();

    byte[] render(BookDocument book, ExportMode mode);
}
