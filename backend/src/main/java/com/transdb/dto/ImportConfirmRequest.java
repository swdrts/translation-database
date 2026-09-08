package com.transdb.dto;

import com.transdb.domain.SegmentStatus;

/**
 * 确认导入时的可选请求体（整本书导入专用）：非空字段覆盖所有预览行的对应属性，
 * 用于用户修正/补充识别到的书名、作者、朝代、译者、标签与发布状态。
 */
public record ImportConfirmRequest(String workTitle, String author, String dynasty,
                                   String translator, String tags, SegmentStatus status) {
}
