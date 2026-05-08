package com.biteblog.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误码枚举
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 通用错误
    SYSTEM_ERROR(500, "系统内部错误"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或Token已过期"),
    FORBIDDEN(403, "无权限操作"),
    NOT_FOUND(404, "资源不存在"),
    TOO_MANY_REQUESTS(429, "请求频率超限"),

    // 用户模块 1xxx
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_ALREADY_EXISTS(1002, "手机号已注册"),
    PASSWORD_ERROR(1003, "密码错误"),
    USER_DISABLED(1004, "账号已被禁用"),

    // 笔记模块 2xxx
    POST_NOT_FOUND(2001, "笔记不存在或已删除"),
    POST_IMAGE_UPLOAD_FAIL(2002, "图片上传失败"),
    POST_CONTENT_INVALID(2003, "笔记内容不合法"),

    // Feed 模块 3xxx
    FEED_EMPTY(3001, "暂无新内容"),

    // 推荐模块 4xxx
    RECOMMEND_UNAVAILABLE(4001, "推荐服务暂不可用"),

    // 位置模块 5xxx
    LOCATION_ERROR(5001, "地理位置获取失败"),

    // 通知模块 6xxx
    NOTIFY_SEND_FAIL(6001, "通知发送失败");

    private final int code;
    private final String msg;
}
