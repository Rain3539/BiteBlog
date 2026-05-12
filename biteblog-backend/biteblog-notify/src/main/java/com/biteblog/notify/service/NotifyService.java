package com.biteblog.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.biteblog.common.exception.BusinessException;
import com.biteblog.common.result.ErrorCode;
import com.biteblog.common.result.Result;
import com.biteblog.notify.client.UserFeignClient;
import com.biteblog.notify.dto.NotificationVO;
import com.biteblog.notify.dto.NotifyPushPayload;
import com.biteblog.notify.entity.Notification;
import com.biteblog.notify.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final NotificationMapper notificationMapper;
    private final UserFeignClient userFeignClient;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 消费 Post 侧 interaction.* 消息（与 RankEventListener 解析方式一致）
     */
    public void handleInteractionEvent(Map<String, Object> event, String routingKey) {
        Long noteId = toLong(event.get("noteId"));
        Long userId = toLong(event.get("userId"));
        Long authorId = toLong(event.get("authorId"));
        if (noteId == null || userId == null || authorId == null) {
            log.warn("notify skip interaction, missing fields: event={}, routingKey={}", event, routingKey);
            return;
        }
        if (authorId.equals(userId)) {
            return;
        }
        String type = mapInteractionType(routingKey);
        // Post 消息体里带有 type=like/collect/comment；部分环境下 RECEIVED_ROUTING_KEY 与本地不一致时从 body 兜底
        if (type == null) {
            type = normalizeInteractionTypeFromBody(String.valueOf(event.getOrDefault("type", "")));
        }
        if (type == null) {
            log.warn("notify skip unknown routingKey={}, event.type={}", routingKey, event.get("type"));
            return;
        }
        String content = switch (type) {
            case "like" -> "赞了你的笔记";
            case "collect" -> "收藏了你的笔记";
            case "comment" -> "评论了你的笔记";
            default -> "与你互动";
        };
        saveAndPush(authorId, userId, type, noteId, content);
    }

    private static String mapInteractionType(String routingKey) {
        if (routingKey == null) {
            return null;
        }
        return switch (routingKey) {
            case "interaction.like" -> "like";
            case "interaction.collect" -> "collect";
            case "interaction.comment" -> "comment";
            default -> null;
        };
    }

    /** 与 Post 侧 Map 中 type 字段一致：like / collect / comment */
    private static String normalizeInteractionTypeFromBody(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw)) {
            return null;
        }
        return switch (raw.trim()) {
            case "like", "collect", "comment" -> raw.trim();
            default -> null;
        };
    }

    @Transactional
    public void saveAndPush(Long receiverId, Long senderId, String type, Long bizId, String content) {
        Notification n = new Notification();
        n.setReceiverId(receiverId);
        n.setSenderId(senderId);
        n.setType(type);
        n.setBizId(bizId);
        n.setContent(content);
        n.setReadStatus(0);
        n.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(n);

        String senderUsername = resolveUsername(senderId);
        String createdAtStr = n.getCreatedAt() != null ? n.getCreatedAt().format(ISO_DT) : null;
        NotifyPushPayload payload = new NotifyPushPayload(
                n.getId(),
                senderId,
                senderUsername,
                type,
                bizId,
                content,
                0,
                createdAtStr
        );

        pushWs(receiverId, payload);
    }

    private void pushWs(Long receiverId, NotifyPushPayload payload) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(receiverId), "/queue/notify", payload);
        } catch (Exception e) {
            log.warn("notify WebSocket push failed receiverId={}", receiverId, e);
        }
    }

    public Map<String, Object> pageList(Long receiverId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<Notification> p = new Page<>(safePage, safeSize);
        notificationMapper.selectPage(p,
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .orderByDesc(Notification::getCreatedAt));

        Set<Long> senderIds = p.getRecords().stream()
                .map(Notification::getSenderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = batchResolveUsernames(senderIds);

        List<NotificationVO> list = p.getRecords().stream()
                .map(n -> toVO(n, names))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", p.getTotal());
        return result;
    }

    public long countUnread(Long receiverId) {
        Long c = notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .eq(Notification::getReadStatus, 0));
        return c == null ? 0 : c;
    }

    @Transactional
    public void markAllRead(Long receiverId) {
        notificationMapper.update(null,
                new LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .eq(Notification::getReadStatus, 0)
                        .set(Notification::getReadStatus, 1));
    }

    @Transactional
    public void markRead(Long receiverId, Long notificationId) {
        Notification n = notificationMapper.selectById(notificationId);
        if (n == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!receiverId.equals(n.getReceiverId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (Objects.equals(n.getReadStatus(), 1)) {
            return;
        }
        n.setReadStatus(1);
        notificationMapper.updateById(n);
    }

    private NotificationVO toVO(Notification n, Map<Long, String> senderNames) {
        NotificationVO vo = new NotificationVO();
        vo.setNotificationId(n.getId());
        vo.setSenderId(n.getSenderId());
        vo.setSenderUsername(senderNames.getOrDefault(n.getSenderId(), null));
        vo.setType(n.getType());
        vo.setBizId(n.getBizId());
        vo.setContent(n.getContent());
        vo.setReadStatus(n.getReadStatus());
        vo.setCreatedAt(n.getCreatedAt());
        return vo;
    }

    private Map<Long, String> batchResolveUsernames(Set<Long> userIds) {
        Map<Long, String> map = new HashMap<>();
        for (Long id : userIds) {
            String name = resolveUsername(id);
            if (name != null) {
                map.put(id, name);
            }
        }
        return map;
    }

    private String resolveUsername(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            Result<Map<String, Object>> res = userFeignClient.getUser(userId);
            if (res != null && res.getData() != null) {
                Object username = res.getData().get("username");
                return username != null ? String.valueOf(username) : null;
            }
        } catch (Exception e) {
            log.debug("notify resolve username failed userId={}", userId, e);
        }
        return null;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }
}
