package com.biteblog.location.dto;

import lombok.Data;

import java.util.List;

@Data
public class NearbyMarkerVO {
    private Long noteId;
    private Long authorId;
    private String title;
    private String shopName;
    private Double longitude;
    private Double latitude;
    private Double distance;
    private List<String> images;
}
