package com.biteblog.post.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("note_image")
public class NoteImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long noteId;

    private String imageUrl;

    private Integer sortOrder;
}
