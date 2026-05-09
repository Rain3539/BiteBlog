-- ================================================================
-- BiteBlog 数据库初始化脚本
-- ================================================================

CREATE DATABASE IF NOT EXISTS nacos_config DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE biteblog;

-- ==================== 用户表 ====================
CREATE TABLE IF NOT EXISTS `user` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `phone`           VARCHAR(20)  NOT NULL COMMENT '手机号',
    `username`        VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password_hash`   VARCHAR(128) NOT NULL COMMENT '密码哈希(bcrypt)',
    `avatar`          VARCHAR(512) DEFAULT NULL COMMENT '头像URL',
    `bio`             VARCHAR(200) DEFAULT NULL COMMENT '个人简介',
    `follower_count`  INT          NOT NULL DEFAULT 0 COMMENT '粉丝数',
    `following_count` INT          NOT NULL DEFAULT 0 COMMENT '关注数',
    `like_count`      INT          NOT NULL DEFAULT 0 COMMENT '获赞总数',
    `is_big_v`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否大V(0否1是)',
    `status`          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '状态(0禁用1正常)',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ==================== 关注关系表 ====================
CREATE TABLE IF NOT EXISTS `follow_relation` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT   NOT NULL COMMENT '关注者ID',
    `target_user_id`  BIGINT   NOT NULL COMMENT '被关注者ID',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follow` (`user_id`, `target_user_id`),
    INDEX `idx_target` (`target_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关注关系表';

-- ==================== 探店笔记表 ====================
CREATE TABLE IF NOT EXISTS `note` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `author_id`       BIGINT        NOT NULL COMMENT '作者ID',
    `title`           VARCHAR(100)  NOT NULL COMMENT '标题',
    `content`         TEXT          NOT NULL COMMENT '正文内容',
    `shop_name`       VARCHAR(100)  DEFAULT NULL COMMENT '店铺名称',
    `address`         VARCHAR(200)  DEFAULT NULL COMMENT '店铺地址',
    `longitude`       DECIMAL(10,7) DEFAULT NULL COMMENT '经度',
    `latitude`        DECIMAL(10,7) DEFAULT NULL COMMENT '纬度',
    `score_color`     TINYINT       DEFAULT 0 COMMENT '环境评分(1-5)',
    `score_smell`     TINYINT       DEFAULT 0 COMMENT '卫生评分(1-5)',
    `score_taste`     TINYINT       DEFAULT 0 COMMENT '口味评分(1-5)',
    `like_count`      INT           NOT NULL DEFAULT 0 COMMENT '点赞数',
    `collect_count`   INT           NOT NULL DEFAULT 0 COMMENT '收藏数',
    `comment_count`   INT           NOT NULL DEFAULT 0 COMMENT '评论数',
    `status`          TINYINT       NOT NULL DEFAULT 1 COMMENT '状态(0删除1正常2审核中)',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_author` (`author_id`),
    INDEX `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='探店笔记表';

-- ==================== 笔记图片表 ====================
CREATE TABLE IF NOT EXISTS `note_image` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `note_id`     BIGINT       NOT NULL COMMENT '笔记ID',
    `image_url`   VARCHAR(512) NOT NULL COMMENT '图片URL(MinIO)',
    `sort_order`  TINYINT      NOT NULL DEFAULT 0 COMMENT '排序序号',
    PRIMARY KEY (`id`),
    INDEX `idx_note` (`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记图片表';

-- ==================== 点赞表 ====================
CREATE TABLE IF NOT EXISTS `note_like` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `note_id`     BIGINT   NOT NULL COMMENT '笔记ID',
    `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_like` (`note_id`, `user_id`),
    INDEX `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞表';

-- ==================== 收藏表 ====================
CREATE TABLE IF NOT EXISTS `note_favorite` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `note_id`     BIGINT   NOT NULL COMMENT '笔记ID',
    `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_favorite` (`note_id`, `user_id`),
    INDEX `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表';

-- ==================== 评论表 ====================
CREATE TABLE IF NOT EXISTS `comment` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `note_id`     BIGINT       NOT NULL COMMENT '笔记ID',
    `user_id`     BIGINT       NOT NULL COMMENT '评论者ID',
    `parent_id`   BIGINT       DEFAULT NULL COMMENT '父评论ID(NULL=顶级评论)',
    `content`     VARCHAR(500) NOT NULL COMMENT '评论内容',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态(0删除1正常)',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_note` (`note_id`),
    INDEX `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论表';

-- ==================== 用户行为表 ====================
CREATE TABLE IF NOT EXISTS `user_behavior` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT      NOT NULL COMMENT '用户ID',
    `note_id`        BIGINT      NOT NULL COMMENT '笔记ID',
    `behavior_type`  VARCHAR(20) NOT NULL COMMENT '行为类型(view/dwell/like/collect/comment)',
    `weight`         INT         NOT NULL DEFAULT 1 COMMENT '行为权重',
    `dwell_time`     INT         DEFAULT NULL COMMENT '停留时长(秒)',
    `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_user_type` (`user_id`, `behavior_type`),
    INDEX `idx_note` (`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户行为表';

-- ==================== 通知表 ====================
CREATE TABLE IF NOT EXISTS `notification` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `receiver_id`  BIGINT       NOT NULL COMMENT '接收者ID',
    `sender_id`    BIGINT       NOT NULL COMMENT '发送者ID',
    `type`         VARCHAR(20)  NOT NULL COMMENT '通知类型(like/collect/comment/follow)',
    `biz_id`       BIGINT       DEFAULT NULL COMMENT '关联业务ID(笔记ID等)',
    `content`      VARCHAR(200) DEFAULT NULL COMMENT '通知摘要',
    `read_status`  TINYINT      NOT NULL DEFAULT 0 COMMENT '已读状态(0未读1已读)',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_receiver` (`receiver_id`, `read_status`),
    INDEX `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知表';

-- ==================== 管理员审核日志表 ====================
CREATE TABLE IF NOT EXISTS `admin_audit_log` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `admin_id`     BIGINT       NOT NULL COMMENT '管理员ID',
    `action`       VARCHAR(20)  NOT NULL COMMENT '操作(delete/approve/reject)',
    `target_type`  VARCHAR(20)  NOT NULL COMMENT '目标类型(note/comment/user)',
    `target_id`    BIGINT       NOT NULL COMMENT '目标ID',
    `reason`       VARCHAR(200) DEFAULT NULL COMMENT '操作原因',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_admin` (`admin_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理员审核日志表';


