package com.biteblog.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应表 notification（无 status 逻辑删除字段，勿使用 @TableLogic）
 */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long receiverId;

    private Long senderId;

    /** like / collect / comment / follow */
    private String type;

    /** 关联业务 ID，如笔记 ID */
    private Long bizId;

    private String content;

    /** 0 未读 1 已读 */
    private Integer readStatus;

    /** 0 正常 1 已撤回（取消点赞/收藏后软删除，列表不展示） */
    private Integer isRetracted;

    private LocalDateTime createdAt;
}
