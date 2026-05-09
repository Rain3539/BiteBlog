package com.biteblog.post.service;

import com.biteblog.common.exception.BusinessException;
import com.biteblog.common.result.ErrorCode;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    public String uploadImage(MultipartFile file) {
        String objectName = "post/" + UUID.randomUUID() + "_" + file.getOriginalFilename();
        try {
            boolean found = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
            return String.format("http://localhost:9000/%s/%s", bucket, objectName);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.POST_IMAGE_UPLOAD_FAIL);
        }
    }
}
