package com.biteblog.recommend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendResponse {

    private List<RecommendItemVO> list = new ArrayList<>();
    private Long cursor;
    private Boolean hasMore = false;

    public static RecommendResponse empty() {
        return new RecommendResponse(new ArrayList<>(), null, false);
    }
}
