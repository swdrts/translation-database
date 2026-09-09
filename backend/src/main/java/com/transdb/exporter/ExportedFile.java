package com.transdb.exporter;

public record ExportedFile(String filename, String contentType, byte[] content) {
}
