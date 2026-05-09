package com.biteblog.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class PublishNoteRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长100字")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    private String shopName;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private Integer scoreColor;

    private Integer scoreSmell;

    private Integer scoreTaste;

    /** 前端已上传到 MinIO 的图片 URL 列表 */
    private List<String> imageUrls;
}
