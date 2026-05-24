package com.biteblog.notify.dto;

import lombok.Data;

@Data
public class MuteTypeRequest {
    /** like / collect / comment */
    private String type;
}
