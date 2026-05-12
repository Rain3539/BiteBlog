package com.biteblog.notify.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 推送载荷。createdAt 使用 ISO-8601 字符串，避免 STOMP 默认序列化
 * {@link java.time.LocalDateTime} 在无 JavaTimeModule 时抛错导致 MQ 无法 ack、消息反复 Unacked。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyPushPayload {

    private Long notificationId;

    private Long senderId;

    private String senderUsername;

    private String type;

    private Long bizId;

    private String content;

    private Integer readStatus;

    /** 如 2026-05-12T18:30:00 */
    private String createdAt;
}
