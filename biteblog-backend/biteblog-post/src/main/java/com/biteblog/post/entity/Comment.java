package com.biteblog.post.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("comment")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long noteId;

    private Long userId;

    /** null=顶级评论, 非null=回复 */
    private Long parentId;

    private String content;

    /** 逻辑删除: 0=删除, 1=正常 */
    @TableLogic
    private Integer status;

    private LocalDateTime createdAt;
}
