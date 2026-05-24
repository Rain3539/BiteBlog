package com.biteblog.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification_preference")
public class NotificationPreference {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设置者（通知接收方 userId） */
    private Long userId;

    /** mute_type / mute_sender / dnd_time */
    private String prefType;

    /** like|collect|comment / senderId / HH:mm-HH:mm */
    private String prefValue;

    private LocalDateTime createdAt;
}
