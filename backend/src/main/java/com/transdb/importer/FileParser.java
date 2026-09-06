package com.transdb.importer;

import java.io.InputStream;
import java.util.List;

public interface FileParser {

    boolean supports(String filename);

    List<ParsedRow> parse(InputStream in);
}
