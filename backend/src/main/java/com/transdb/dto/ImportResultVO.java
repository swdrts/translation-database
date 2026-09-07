package com.transdb.dto;

import java.util.List;

public record ImportResultVO(int imported, int overwritten, int skipped, List<LineError> failed) {
}
