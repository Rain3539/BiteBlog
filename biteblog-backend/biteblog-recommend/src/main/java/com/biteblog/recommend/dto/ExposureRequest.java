package com.biteblog.recommend.dto;

import lombok.Data;

import java.util.List;

@Data
public class ExposureRequest {

    private List<Long> postIds;
}
