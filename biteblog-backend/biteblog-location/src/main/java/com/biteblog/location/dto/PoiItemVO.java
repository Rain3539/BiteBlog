package com.biteblog.location.dto;

import lombok.Data;

@Data
public class PoiItemVO {
    private String id;
    private String name;
    private String address;
    private Double longitude;
    private Double latitude;
    private String type;
}
