package com.biteblog.post.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user")
public class UserInfo {
    @TableId
    private Long id;
    private String username;
    private String avatar;
}
