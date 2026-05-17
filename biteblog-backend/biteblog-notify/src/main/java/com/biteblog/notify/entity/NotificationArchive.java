package com.biteblog.notify.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知归档实体，对应 notification_archive 表（冷数据）。
 * id 来自热表，不自增；archived_at 为归档时间戳。
 */
@Data
@TableName("notification_archive")
public class NotificationArchive {

    /** 来自热表的原始 ID，INSERT 时手动赋值 */
    @TableId
    private Long id;

    private Long receiverId;
    private Long senderId;
    private String type;
    private Long bizId;
    private String content;
    private Integer readStatus;
    private LocalDateTime createdAt;
    private LocalDateTime archivedAt;
}
