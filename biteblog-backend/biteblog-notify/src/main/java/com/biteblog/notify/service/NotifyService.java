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
import com.biteblog.notify.entity.NotificationArchive;
import com.biteblog.notify.mapper.NotificationArchiveMapper;
import com.biteblog.notify.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** Redis key 前缀：notify:unread:{userId}，缓存未读数，TTL 5 分钟 */
    private static final String UNREAD_KEY_PREFIX = "notify:unread:";
    private static final long UNREAD_TTL_MINUTES = 5;

    /**
     * 去重窗口：5 分钟内 receiver+sender+type+bizId 完全相同则视为重复投递，跳过写入。
     * 覆盖 RabbitMQ Unacked 消息重投场景（一般在秒级~分钟级重投）。
     */
    private static final long DEDUP_WINDOW_MINUTES = 5;

    /** 冷热分离：热表保留最近 N 天数据，更早的已读通知归档到冷表。 */
    private static final int RETENTION_DAYS = 30;

    /**
     * 并发查询发送者昵称的专用线程池。
     * 核心 5 线程，最大 10 线程，队列 50，拒绝策略为调用方直接执行（降级为同步）。
     * 使用守护线程，JVM 退出时不阻塞关闭。
     */
    private static final AtomicInteger FEIGN_THREAD_COUNTER = new AtomicInteger(0);
    private static final ThreadPoolExecutor FEIGN_EXECUTOR = new ThreadPoolExecutor(
            5, 10, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            r -> {
                Thread t = new Thread(r, "notify-feign-" + FEIGN_THREAD_COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    /** 每批最多迁移的记录数，防止单次大事务长时间锁表。 */
    private static final int ARCHIVE_BATCH_SIZE = 500;

    private final NotificationMapper notificationMapper;
    private final NotificationArchiveMapper notificationArchiveMapper;
    private final UserFeignClient userFeignClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    // ------------------------------------------------------------------ MQ 消费

    /**
     * 消费 Post 侧 interaction.* 消息。
     *
     * <p>注意：此方法作为外部入口经 Spring AOP 代理调用，@Transactional 在此处才真正生效。
     * 内部对 saveAndPush 的同 Bean 调用不经过代理，因此 saveAndPush 不再单独标注事务。
     */
    @Transactional
    public void handleInteractionEvent(Map<String, Object> event, String routingKey) {
        Long noteId = toLong(event.get("noteId"));
        Long userId = toLong(event.get("userId"));
        Long authorId = toLong(event.get("authorId"));
        String action = String.valueOf(event.getOrDefault("action", "add"));

        if (noteId == null || userId == null || authorId == null) {
            log.warn("notify skip: missing fields event={} routingKey={}", event, routingKey);
            return;
        }
        if (!"add".equalsIgnoreCase(action)) {
            log.info("notify skip: non-add interaction action={} routingKey={} noteId={}", action, routingKey, noteId);
            return;
        }
        if (authorId.equals(userId)) {
            // 自己给自己的内容互动，不产生他人通知
            return;
        }

        String type = mapInteractionType(routingKey);
        if (type == null) {
            // routing key 解析失败时从消息体 type 字段兜底
            type = normalizeInteractionTypeFromBody(String.valueOf(event.getOrDefault("type", "")));
        }
        if (type == null) {
            log.warn("notify skip: unknown routingKey={} event.type={}", routingKey, event.get("type"));
            return;
        }

        String content = switch (type) {
            case "like"    -> "赞了你的笔记";
            case "collect" -> "收藏了你的笔记";
            case "comment" -> "评论了你的笔记";
            default        -> "与你互动";
        };

        saveAndPush(authorId, userId, type, noteId, content);
    }

    /**
     * 写入通知并在事务提交后推送 WebSocket。
     *
     * <p>此方法由 handleInteractionEvent 在同一事务上下文内调用（同 Bean 调用，不过代理）。
     * Feign 查用户名和 WS 推送通过 registerSynchronization#afterCommit 延迟到事务真正提交后执行，
     * 确保数据库连接释放后才发起网络 I/O，避免连接池被长时间占用。
     */
    public void saveAndPush(Long receiverId, Long senderId, String type, Long bizId, String content) {
        // 幂等去重：防止 MQ 重投导致短时间内写入重复通知
        if (isDuplicate(receiverId, senderId, type, bizId)) {
            log.info("notify dedup skip receiverId={} senderId={} type={} bizId={}", receiverId, senderId, type, bizId);
            return;
        }

        Notification n = new Notification();
        n.setReceiverId(receiverId);
        n.setSenderId(senderId);
        n.setType(type);
        n.setBizId(bizId);
        n.setContent(content);
        n.setReadStatus(0);
        n.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(n);

        // Redis 未读数 +1（写库成功后立即更新缓存，事务内操作，允许 Redis 异常时降级）
        incrementUnreadCache(receiverId);

        // 捕获事务提交后需要用到的不可变值（Lambda 捕获变量须为 effectively final）
        final Long notificationId = n.getId();
        final String createdAtStr = n.getCreatedAt().format(ISO_DT);
        final Long finalSenderId = senderId;
        final String finalType = type;
        final Long finalBizId = bizId;
        final String finalContent = content;

        // 注册 afterCommit 回调：事务提交后才做 Feign 查昵称和 WS 推送
        // 若当前不在事务上下文（如单元测试直接调用），则立即执行
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doAfterCommitPush(receiverId, finalSenderId, notificationId,
                            finalType, finalBizId, finalContent, createdAtStr);
                }
            });
        } else {
            doAfterCommitPush(receiverId, finalSenderId, notificationId,
                    finalType, finalBizId, finalContent, createdAtStr);
        }
    }

    /** 事务提交后执行：Feign 查发送者昵称，组装 Payload，推送 WebSocket。 */
    private void doAfterCommitPush(Long receiverId, Long senderId, Long notificationId,
                                   String type, Long bizId, String content, String createdAtStr) {
        String senderUsername = resolveUsername(senderId);
        NotifyPushPayload payload = new NotifyPushPayload(
                notificationId, senderId, senderUsername, type, bizId, content, 0, createdAtStr);
        pushWs(receiverId, payload);
    }

    private void pushWs(Long receiverId, NotifyPushPayload payload) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(receiverId), "/queue/notify", payload);
        } catch (Exception e) {
            log.warn("notify ws push failed receiverId={}", receiverId, e);
        }
    }

    // ------------------------------------------------------------------ 去重

    /**
     * 检查在去重窗口内是否已存在相同的通知，防止 MQ 重投产生重复记录。
     * 使用业务层判断而非唯一索引，避免点赞→取消→再点赞的合理场景被拦截。
     */
    private boolean isDuplicate(Long receiverId, Long senderId, String type, Long bizId) {
        Long count = notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .eq(Notification::getSenderId, senderId)
                        .eq(Notification::getType, type)
                        .eq(Notification::getBizId, bizId)
                        .gt(Notification::getCreatedAt, LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES)));
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------ 定时清理

    /**
     * 冷热分离：每天凌晨 3 点将 30 天前的已读通知从热表（notification）
     * 迁移到冷表（notification_archive），热表保持精简以提升查询性能。
     *
     * <p>策略说明：
     * <ul>
     *   <li>只迁移 read_status=1 的已读记录；未读通知不动，保证用户能查到历史未读。</li>
     *   <li>每批最多处理 {@value #ARCHIVE_BATCH_SIZE} 条，防止单次大事务锁表。</li>
     *   <li>归档表使用 INSERT IGNORE，任务重复执行（如重启后补跑）不会报错，保证幂等。</li>
     *   <li>先写归档表、再删热表，若删除失败下次任务再次 INSERT IGNORE 仍幂等。</li>
     * </ul>
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void archiveOldReadNotifications() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        LocalDateTime archivedAt = LocalDateTime.now();
        int totalArchived = 0;

        List<Notification> batch;
        do {
            // 每批查 ARCHIVE_BATCH_SIZE 条，避免一次性加载过多数据到内存
            batch = notificationMapper.selectList(
                    new LambdaQueryWrapper<Notification>()
                            .lt(Notification::getCreatedAt, cutoff)
                            .eq(Notification::getReadStatus, 1)
                            .last("LIMIT " + ARCHIVE_BATCH_SIZE));

            if (batch.isEmpty()) {
                break;
            }

            // 逐条 INSERT IGNORE 写入归档表（幂等）
            for (Notification n : batch) {
                NotificationArchive archive = toArchive(n, archivedAt);
                notificationArchiveMapper.insertIgnore(archive);
            }

            // 归档成功后从热表删除
            List<Long> ids = batch.stream()
                    .map(Notification::getId)
                    .collect(Collectors.toList());
            notificationMapper.deleteBatchIds(ids);

            totalArchived += batch.size();
            log.info("notify archive batch: archived={} cutoff={}", batch.size(), cutoff);

        } while (batch.size() == ARCHIVE_BATCH_SIZE); // 不足一批说明已处理完

        log.info("notify archive done: totalArchived={} cutoff={}", totalArchived, cutoff);
    }

    private static NotificationArchive toArchive(Notification n, LocalDateTime archivedAt) {
        NotificationArchive a = new NotificationArchive();
        a.setId(n.getId());
        a.setReceiverId(n.getReceiverId());
        a.setSenderId(n.getSenderId());
        a.setType(n.getType());
        a.setBizId(n.getBizId());
        a.setContent(n.getContent());
        a.setReadStatus(n.getReadStatus());
        a.setCreatedAt(n.getCreatedAt());
        a.setArchivedAt(archivedAt);
        return a;
    }

    // ------------------------------------------------------------------ HTTP 查询

    /**
     * 分页查询通知列表，支持可选的类型与已读状态过滤。
     *
     * @param type       可选，like/collect/comment，为空则不过滤
     * @param readStatus 可选，0=未读 1=已读，为 null 则不过滤
     */
    public Map<String, Object> pageList(Long receiverId, int page, int size,
                                        String type, Integer readStatus) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        // 双列排序：created_at 相同时（如批量写入）再按 id DESC，保证分页结果稳定无重复
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getReceiverId, receiverId)
                .orderByDesc(Notification::getCreatedAt)
                .orderByDesc(Notification::getId);
        if (StringUtils.hasText(type)) {
            wrapper.eq(Notification::getType, type);
        }
        if (readStatus != null) {
            wrapper.eq(Notification::getReadStatus, readStatus);
        }

        Page<Notification> p = new Page<>(safePage, safeSize);
        notificationMapper.selectPage(p, wrapper);

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

    /**
     * 查询未读数：Cache-Aside 模式，优先读 Redis，miss 时查 DB 并回填（TTL 5 分钟）。
     * Redis 不可用时自动降级查库，接口不报错。
     */
    public long countUnread(Long receiverId) {
        String key = UNREAD_KEY_PREFIX + receiverId;
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(String.valueOf(cached));
            }
        } catch (Exception e) {
            log.warn("Redis get unread failed, fallback to DB. userId={}", receiverId, e);
        }
        Long c = notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .eq(Notification::getReadStatus, 0));
        long count = c == null ? 0 : c;
        try {
            redisTemplate.opsForValue().set(key, count, UNREAD_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis set unread failed. userId={}", receiverId, e);
        }
        return count;
    }

    @Transactional
    public void markAllRead(Long receiverId) {
        notificationMapper.update(null,
                new LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .eq(Notification::getReadStatus, 0)
                        .set(Notification::getReadStatus, 1));
        evictUnreadCache(receiverId);
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
        evictUnreadCache(receiverId);
    }

    // ------------------------------------------------------------------ Redis 辅助

    private void incrementUnreadCache(Long receiverId) {
        String key = UNREAD_KEY_PREFIX + receiverId;
        try {
            redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, UNREAD_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis increment unread failed. userId={}", receiverId, e);
        }
    }

    private void evictUnreadCache(Long receiverId) {
        try {
            redisTemplate.delete(UNREAD_KEY_PREFIX + receiverId);
        } catch (Exception e) {
            log.warn("Redis evict unread failed. userId={}", receiverId, e);
        }
    }

    // ------------------------------------------------------------------ 转换辅助

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

    /**
     * 并发查询多个发送者的昵称。
     *
     * <p>原来是串行 for 循环，N 个不同 senderId 需要 N × RTT 时间。
     * 改为 CompletableFuture 并发提交到 FEIGN_EXECUTOR，总耗时约等于最慢那次单次调用。
     * 对列表页通常只有少量不同发送者，并发效果显著。
     *
     * <p>整批设置 3 秒超时：若 user-service 响应慢，
     * 超时的那个 userId 昵称降级为 null（前端展示"用户{id}"），不影响其余结果。
     */
    private Map<Long, String> batchResolveUsernames(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 为每个 userId 并发提交一个异步任务，失败时返回 null entry 而非抛出
        List<CompletableFuture<Map.Entry<Long, String>>> futures = userIds.stream()
                .map(id -> CompletableFuture
                        .supplyAsync(() -> resolveUsername(id), FEIGN_EXECUTOR)
                        .handle((name, ex) -> {
                            if (ex != null) {
                                log.debug("resolve username async failed userId={}", id, ex);
                                return null;
                            }
                            return name != null ? Map.entry(id, name) : null;
                        }))
                .collect(Collectors.toList());

        Map<Long, String> result = new HashMap<>();
        for (CompletableFuture<Map.Entry<Long, String>> future : futures) {
            try {
                Map.Entry<Long, String> entry = future.get(3, TimeUnit.SECONDS);
                if (entry != null) {
                    result.put(entry.getKey(), entry.getValue());
                }
            } catch (TimeoutException e) {
                log.warn("notify resolve username timeout, degrading to null");
                future.cancel(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("notify resolve username interrupted");
            } catch (ExecutionException e) {
                log.debug("notify resolve username execution failed", e.getCause());
            }
        }
        return result;
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

    // ------------------------------------------------------------------ 类型解析

    private static String mapInteractionType(String routingKey) {
        if (routingKey == null) {
            return null;
        }
        return switch (routingKey) {
            case "interaction.like"    -> "like";
            case "interaction.collect" -> "collect";
            case "interaction.comment" -> "comment";
            default -> null;
        };
    }

    private static String normalizeInteractionTypeFromBody(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw)) {
            return null;
        }
        return switch (raw.trim()) {
            case "like", "collect", "comment" -> raw.trim();
            default -> null;
        };
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
