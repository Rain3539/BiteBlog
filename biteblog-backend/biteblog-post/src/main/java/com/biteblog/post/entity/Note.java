package com.biteblog.post.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("note")
public class Note {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long authorId;

    private String title;

    private String content;

    private String shopName;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private Integer scoreColor;

    private Integer scoreSmell;

    private Integer scoreTaste;

    private Integer likeCount;

    private Integer collectCount;

    private Integer commentCount;

    /** 逻辑删除: 0=删除, 1=正常, 2=审核中 */
    @TableLogic
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
