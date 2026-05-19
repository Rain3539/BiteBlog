package com.biteblog.location.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("note_image")
public class NoteImage {
    @TableId
    private Long id;
    private Long noteId;
    private String imageUrl;
    private Integer sortOrder;
}
