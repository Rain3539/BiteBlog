package com.biteblog.notify.dto;

import lombok.Data;

@Data
public class DndTimeRequest {
    /** 勿扰时段，格式 HH:mm-HH:mm，如 22:00-08:00 */
    private String timeRange;
}
