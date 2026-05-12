package com.biteblog.notify.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationVO {

    private Long notificationId;

    private Long senderId;

    private String senderUsername;

    private String type;

    private Long bizId;

    private String content;

    private Integer readStatus;

    private LocalDateTime createdAt;
}
