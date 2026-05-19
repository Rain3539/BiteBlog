package com.biteblog.recommend.client.dto;

import lombok.Data;

@Data
public class UserProfileDTO {

    private Long userId;
    private String username;
    private String avatar;
    private Boolean isBigV;
}
