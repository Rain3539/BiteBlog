package com.biteblog.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phone;

    private String username;

    private String passwordHash;

    private String avatar;

    private String bio;

    private Integer followerCount;

    private Integer followingCount;

    private Integer likeCount;

    private Boolean isBigV;

    /** 逻辑删除: 0=禁用, 1=正常 */
    @TableLogic
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
