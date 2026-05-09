package com.biteblog.post.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("note_like")
public class NoteLike {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long noteId;

    private Long userId;

    private LocalDateTime createdAt;
}
