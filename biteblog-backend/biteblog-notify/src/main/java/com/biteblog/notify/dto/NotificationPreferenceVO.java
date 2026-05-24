package com.biteblog.notify.dto;

import lombok.Data;

@Data
public class NotificationPreferenceVO {

    private Long id;
    private String prefType;
    private String prefValue;
    private String createdAt;
}
