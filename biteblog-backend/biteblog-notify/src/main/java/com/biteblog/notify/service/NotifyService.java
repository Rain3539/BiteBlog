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
import org.springframework.data.redis.core.StringRedisTemplate;
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

    /**
     * Redis key 前缀：notify:unread:{userId}。
     * 存储格式为纯整数字符串（由 StringRedisTemplate 写入），支持原生 INCR / DECR 命令。
     * 不再使用 Jackson RedisTemplate 写入此 key，避免序列化格式（["java.lang.Long",5]）
     * 与 INCR/DECR 期望的纯数字字符串之间的冲突。
     */
    private static final String UNREAD_KEY_PREFIX = "notify:unread:";
    private static final long UNREAD_TTL_MINUTES = 5;

    /** 发送者昵称缓存：notify:sender:{userId} → username 字符串，TTL 30 分钟 */
    private static final String SENDER_KEY_PREFIX = "notify:sender:";
    private static final long SENDER_TTL_MINUTES = 30;

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

    /** 粉丝数达到此阈值视为大 V，不向粉丝 fanout follow_post 通知，避免写入风暴。 */
    private static final int FOLLOW_POST_FANOUT_THRESHOLD = 500;
    private static final String REDIS_FANS_KEY_PREFIX = "fans:";
    /** Feed 服务维护的大 V 作者集合（粉丝 ≥50 时写入） */
    private static final String FEED_BIGV_KEY = "feed:bigv";
    /** 与 Feed 一致：粉丝数 ≥50 视为大 V，不 fanout follow_post */
    private static final int FEED_BIG_V_THRESHOLD = 50;
    private static final String CACHE_USER_KEY_PREFIX = "cache:user:";

    private final NotificationMapper notificationMapper;
    private final NotificationArchiveMapper notificationArchiveMapper;
    private final UserFeignClient userFeignClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotifyPreferenceService preferenceService;
    /** 纯字符串 Redis 操作：未读计数 notify:unread:*、昵称缓存 notify:sender:* */
    private final StringRedisTemplate stringRedisTemplate;

    // ------------------------------------------------------------------ MQ 消费

    /**
     * 消费 Post 侧 interaction.* 消息。
     *
     * <p>注意：此方法作为外部入口经 Spring AOP 代理调用，@Transactional 在此处才真正生效。
     * 内部对 saveAndPush 的同 Bean 调用不经过代理，因此 saveAndPush 不再单独标注事务。
     */
    @Transactional
    public void handleInteractionEvent(Map<String, Object> event, String routingKey) {
        Long noteId   = toLong(event.get("noteId"));
        Long userId   = toLong(event.get("userId"));
        Long authorId = toLong(event.get("authorId"));
        String action = String.valueOf(event.getOrDefault("action", "add"));

        if (noteId == null || userId == null || authorId == null) {
            log.warn("notify skip: missing fields event={} routingKey={}", event, routingKey);
            return;
        }

        String type = mapInteractionType(routingKey);
        if (type == null) {
            type = normalizeInteractionTypeFromBody(String.valueOf(event.getOrDefault("type", "")));
        }

        if ("remove".equalsIgnoreCase(action)) {
            if ("like".equals(type) || "collect".equals(type)) {
                if (!authorId.equals(userId)) {
                    handleRetract(authorId, userId, type, noteId);
                }
            } else {
                log.info("notify skip: remove action for type={} routingKey={} noteId={}", type, routingKey, noteId);
            }
            return;
        }

        if (!"add".equalsIgnoreCase(action)) {
            log.info("notify skip: unknown interaction action={} routingKey={} noteId={}", action, routingKey, noteId);
            return;
        }

        if ("comment".equals(type)) {
            handleCommentAdd(event, noteId, userId, authorId);
            return;
        }

        if (authorId.equals(userId)) {
            // 自己给自己的内容互动，不产生他人通知
            return;
        }

        if (type == null) {
            log.warn("notify skip: unknown routingKey={} event.type={}", routingKey, event.get("type"));
            return;
        }

        String content = switch (type) {
            case "like"    -> "赞了你的笔记";
            case "collect" -> "收藏了你的笔记";
            default        -> "与你互动";
        };

        saveAndPush(authorId, userId, type, noteId, content);
    }

    /**
     * 评论互动：顶级评论通知笔记作者；回复评论额外通知父评论作者（comment_reply）。
     */
    private void handleCommentAdd(Map<String, Object> event, Long noteId, Long userId, Long authorId) {
        if (!authorId.equals(userId)) {
            saveAndPush(authorId, userId, "comment", noteId, "评论了你的笔记");
        }

        Long parentId = toLong(event.get("parentId"));
        Long commentId = toLong(event.get("commentId"));
        Long parentCommentUserId = toLong(event.get("parentCommentUserId"));
        if (parentId == null || commentId == null || parentCommentUserId == null) {
            return;
        }
        if (parentCommentUserId.equals(userId) || parentCommentUserId.equals(authorId)) {
            return;
        }

        String snippet = extractCommentSnippet(event.get("commentContent"));
        String replyContent = snippet.isEmpty()
                ? "回复了你的评论"
                : "回复了你的评论：" + snippet;
        saveAndPush(parentCommentUserId, userId, "comment_reply", noteId, replyContent);
    }

    private static String extractCommentSnippet(Object raw) {
        if (raw == null) {
            return "";
        }
        String text = String.valueOf(raw).trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }

    /**
     * 消费 Post 侧 note.published：向作者粉丝推送 follow_post 通知（小 V 限定）。
     */
    @Transactional
    public void handleNotePublished(Long noteId, Long authorId) {
        if (noteId == null || authorId == null) {
            log.warn("notify skip note.published: missing noteId={} authorId={}", noteId, authorId);
            return;
        }

        if (isFollowPostBigV(authorId)) {
            log.info("notify skip note.published: big-V authorId={}", authorId);
            return;
        }

        String fansKey = REDIS_FANS_KEY_PREFIX + authorId;
        Long fanCount;
        Set<String> fanSet;
        try {
            fanCount = stringRedisTemplate.opsForSet().size(fansKey);
            if (fanCount == null || fanCount == 0) {
                log.info("notify skip note.published: no fans authorId={}", authorId);
                return;
            }
            if (fanCount >= FOLLOW_POST_FANOUT_THRESHOLD) {
                log.info("notify skip note.published: fan threshold authorId={} fans={}", authorId, fanCount);
                return;
            }
            fanSet = stringRedisTemplate.opsForSet().members(fansKey);
        } catch (Exception e) {
            log.warn("notify skip note.published: read fans failed authorId={}", authorId, e);
            return;
        }
        if (fanSet == null || fanSet.isEmpty()) {
            return;
        }

        int sent = 0;
        for (String fanIdStr : fanSet) {
            Long fanId = parseFanId(fanIdStr);
            if (fanId == null || authorId.equals(fanId)) {
                continue;
            }
            if (preferenceService.check(fanId, authorId, "follow_post")
                    == NotifyPreferenceService.CheckResult.MUTED) {
                continue;
            }
            saveAndPush(fanId, authorId, "follow_post", noteId, "发布了新笔记");
            sent++;
        }
        log.info("notify follow_post fanout: noteId={} authorId={} sent={}/{}", noteId, authorId, sent, fanCount);
    }

    /** 大 V 判定：与 Feed 对齐（feed:bigv / 粉丝 ≥50），或粉丝 ≥500 防风暴。 */
    private boolean isFollowPostBigV(Long authorId) {
        try {
            Boolean inBigV = stringRedisTemplate.opsForSet().isMember(FEED_BIGV_KEY, String.valueOf(authorId));
            if (Boolean.TRUE.equals(inBigV)) {
                return true;
            }
            Object cachedCount = stringRedisTemplate.opsForHash()
                    .get(CACHE_USER_KEY_PREFIX + authorId, "followerCount");
            if (cachedCount != null) {
                int followerCount = Integer.parseInt(cachedCount.toString());
                if (followerCount >= FEED_BIG_V_THRESHOLD) {
                    return true;
                }
            }
            Long fanCount = stringRedisTemplate.opsForSet().size(REDIS_FANS_KEY_PREFIX + authorId);
            if (fanCount != null && fanCount >= FEED_BIG_V_THRESHOLD) {
                return true;
            }
            return fanCount != null && fanCount >= FOLLOW_POST_FANOUT_THRESHOLD;
        } catch (Exception e) {
            log.warn("notify big-V check failed authorId={}", authorId, e);
            return false;
        }
    }

    private static Long parseFanId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 处理取消互动（like/collect action=remove）：软撤回最近一条匹配通知。
     * 评论目前无 remove 事件，不在此处理。
     */
    public void handleRetract(Long receiverId, Long senderId, String type, Long bizId) {
        Notification target = notificationMapper.selectOne(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .eq(Notification::getSenderId, senderId)
                        .eq(Notification::getType, type)
                        .eq(Notification::getBizId, bizId)
                        .eq(Notification::getIsRetracted, 0)
                        .orderByDesc(Notification::getCreatedAt)
                        .last("LIMIT 1"));
        if (target == null) {
            log.info("notify retract: no active notification receiver={} sender={} type={} biz={}",
                    receiverId, senderId, type, bizId);
            return;
        }
        notificationMapper.update(null,
                new LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getId, target.getId())
                        .set(Notification::getIsRetracted, 1));
        if (Integer.valueOf(0).equals(target.getReadStatus())) {
            decrementUnreadCache(receiverId);
        }
        log.info("notify retracted: id={} type={} receiver={}", target.getId(), type, receiverId);
    }

    /**
     * 写入通知并在事务提交后推送 WebSocket。
     *
     * <p>此方法由 handleInteractionEvent 在同一事务上下文内调用（同 Bean 调用，不过代理）。
     * Feign 查用户名和 WS 推送通过 registerSynchronization#afterCommit 延迟到事务真正提交后执行，
     * 确保数据库连接释放后才发起网络 I/O，避免连接池被长时间占用。
     */
    public void saveAndPush(Long receiverId, Long senderId, String type, Long bizId, String content) {
        NotifyPreferenceService.CheckResult pref =
                preferenceService.check(receiverId, senderId, type);
        if (pref == NotifyPreferenceService.CheckResult.MUTED) {
            log.info("notify muted: receiver={} type={} sender={}", receiverId, type, senderId);
            return;
        }

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
        n.setIsRetracted(0);
        n.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(n);

        // 勿扰时段写库但不更新未读 Redis（次日打开列表仍可见历史未读）
        if (pref != NotifyPreferenceService.CheckResult.DND) {
            incrementUnreadCache(receiverId);
        }

        // 捕获事务提交后需要用到的不可变值（Lambda 捕获变量须为 effectively final）
        final Long   notificationId = n.getId();
        final String createdAtStr   = n.getCreatedAt().format(ISO_DT);
        final Long   finalSenderId  = senderId;
        final String finalType      = type;
        final Long   finalBizId     = bizId;
        final String finalContent   = content;
        final boolean pushWs        = pref != NotifyPreferenceService.CheckResult.DND;

        // 注册 afterCommit 回调：事务提交后才做 Feign 查昵称和 WS 推送
        // 若当前不在事务上下文（如单元测试直接调用），则立即执行
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (pushWs) {
                        doAfterCommitPush(receiverId, finalSenderId, notificationId,
                                finalType, finalBizId, finalContent, createdAtStr);
                    }
                }
            });
        } else if (pushWs) {
            doAfterCommitPush(receiverId, finalSenderId, notificationId,
                    finalType, finalBizId, finalContent, createdAtStr);
        }
    }

    /** 事务提交后执行：Feign 查发送者昵称，组装 Payload，推送 WebSocket。 */
    private void doAfterCommitPush(Long receiverId, Long senderId, Long notificationId,
                                   String type, Long bizId, String content, String createdAtStr) {
        String senderUsername = resolveUsernameWithCache(senderId);
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
                        .eq(Notification::getSenderId,   senderId)
                        .eq(Notification::getType,       type)
                        .eq(Notification::getBizId,      bizId)
                        .eq(Notification::getIsRetracted, 0)
                        .gt(Notification::getCreatedAt,  LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES)));
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------ 定时任务

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
        LocalDateTime cutoff    = LocalDateTime.now().minusDays(RETENTION_DAYS);
        LocalDateTime archivedAt = LocalDateTime.now();
        int totalArchived = 0;

        List<Notification> batch;
        do {
            batch = notificationMapper.selectList(
                    new LambdaQueryWrapper<Notification>()
                            .lt(Notification::getCreatedAt, cutoff)
                            .eq(Notification::getReadStatus, 1)
                            .last("LIMIT " + ARCHIVE_BATCH_SIZE));
            if (batch.isEmpty()) break;

            for (Notification n : batch) {
                notificationArchiveMapper.insertIgnore(toArchive(n, archivedAt));
            }
            List<Long> ids = batch.stream().map(Notification::getId).collect(Collectors.toList());
            notificationMapper.deleteBatchIds(ids);

            totalArchived += batch.size();
            log.info("notify archive batch: archived={} cutoff={}", batch.size(), cutoff);
        } while (batch.size() == ARCHIVE_BATCH_SIZE);

        log.info("notify archive done: totalArchived={} cutoff={}", totalArchived, cutoff);
    }

    /**
     * 未读数 Redis 缓存对账任务，每 10 分钟执行一次。
     *
     * <p>长时间运行后，并发的 INCR / DECR 以及网络抖动可能导致 Redis 中的未读数与 DB 实际值
     * 产生轻微漂移。本任务只扫描 Redis 中已存在的 {@code notify:unread:*} key（活跃用户），
     * 与 DB {@code SELECT COUNT(*)} 对比，发现差异则用 DB 真实值覆盖 Redis，
     * 不对冷用户触发全量 COUNT，避免无谓的 DB 压力。
     *
     * <p>由于使用 Redis KEYS 命令，生产环境用户量极大时可改为 SCAN 迭代；
     * 当前课程场景用户量有限，KEYS 可接受。
     */
    @Scheduled(fixedDelay = 600_000)
    public void reconcileUnreadCache() {
        Set<String> keys = stringRedisTemplate.keys(UNREAD_KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        int fixed = 0;
        for (String key : keys) {
            try {
                String cached = stringRedisTemplate.opsForValue().get(key);
                if (cached == null) continue;

                long redisVal = Long.parseLong(cached);
                Long userId   = Long.valueOf(key.substring(UNREAD_KEY_PREFIX.length()));

                Long dbCount = notificationMapper.selectCount(
                        new LambdaQueryWrapper<Notification>()
                                .eq(Notification::getReceiverId, userId)
                                .eq(Notification::getReadStatus, 0)
                                .eq(Notification::getIsRetracted, 0));
                long actual = dbCount == null ? 0L : dbCount;

                if (redisVal != actual) {
                    stringRedisTemplate.opsForValue().set(
                            key, String.valueOf(actual), UNREAD_TTL_MINUTES, TimeUnit.MINUTES);
                    log.info("notify unread reconcile fixed: userId={} redis={} -> db={}", userId, redisVal, actual);
                    fixed++;
                }
            } catch (Exception e) {
                log.warn("notify unread reconcile error: key={}", key, e);
            }
        }
        if (fixed > 0) {
            log.info("notify unread reconcile done: fixed={} / scanned={}", fixed, keys.size());
        }
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
                .eq(Notification::getIsRetracted, 0)
                .orderByDesc(Notification::getCreatedAt)
                .orderByDesc(Notification::getId);
        if (StringUtils.hasText(type)) {
            if ("comment".equals(type)) {
                wrapper.in(Notification::getType, "comment", "comment_reply");
            } else {
                wrapper.eq(Notification::getType, type);
            }
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
     * 查询未读数：Cache-Aside 模式，优先读 Redis（StringRedisTemplate，纯整数字符串），
     * miss 时查 DB 并回填；Redis 不可用时自动降级查库，接口不报错。
     */
    public long countUnread(Long receiverId) {
        String key = UNREAD_KEY_PREFIX + receiverId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(cached);
            }
        } catch (Exception e) {
            log.warn("Redis get unread failed, fallback to DB. userId={}", receiverId, e);
        }

        Long c = notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .eq(Notification::getReadStatus, 0)
                        .eq(Notification::getIsRetracted, 0));
        long count = c == null ? 0L : c;

        try {
            stringRedisTemplate.opsForValue().set(
                    key, String.valueOf(count), UNREAD_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis set unread failed. userId={}", receiverId, e);
        }
        return count;
    }

    /**
     * 全部已读：DB 批量更新后，将 Redis 未读数置为字符串 "0"（不删除 key）。
     *
     * <p>使用 SET "0" 而非 DEL key 的原因：
     * 下次调用 unread-count 时可直接返回 0，避免触发 DB COUNT(*)，
     * 减少「全部已读」高频操作下的 DB 回扫压力。
     */
    @Transactional
    public void markAllRead(Long receiverId) {
        notificationMapper.update(null,
                new LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .eq(Notification::getReadStatus, 0)
                        .eq(Notification::getIsRetracted, 0)
                        .set(Notification::getReadStatus, 1));
        setUnreadCacheZero(receiverId);
    }

    /**
     * 单条已读：DB 更新后，对 Redis 未读数执行 DECR（而非 DEL）。
     *
     * <p>DECR 相比 DEL 的优势：未读数从 N 变为 N-1，不会触发后续查询对 DB 的 COUNT 回扫，
     * 在用户逐条阅读（如从通知列表点击）的高频场景下大幅降低 DB 压力。
     * 若 DECR 后值 < 0（漂移情况），删除 key 让下次回填修正。
     */
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
            // 已经是已读状态，幂等返回
            return;
        }
        n.setReadStatus(1);
        notificationMapper.updateById(n);
        decrementUnreadCache(receiverId);
    }

    // ------------------------------------------------------------------ Redis 辅助

    /**
     * 未读数 +1：使用 StringRedisTemplate 执行原生 INCR，确保 value 是纯整数字符串。
     * key 不存在时 INCR 自动初始化为 1，并刷新 TTL。
     */
    private void incrementUnreadCache(Long receiverId) {
        String key = UNREAD_KEY_PREFIX + receiverId;
        try {
            stringRedisTemplate.opsForValue().increment(key);
            stringRedisTemplate.expire(key, UNREAD_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis increment unread failed, degrading. userId={}", receiverId, e);
        }
    }

    /**
     * 未读数 -1：执行原生 DECR；若结果 < 0 说明数据已漂移，删除 key 让下次回填修正。
     * 正常路径（单条已读）调用此方法，避免每次已读都触发 DB COUNT。
     */
    private void decrementUnreadCache(Long receiverId) {
        String key = UNREAD_KEY_PREFIX + receiverId;
        try {
            Long cur = stringRedisTemplate.opsForValue().decrement(key);
            if (cur != null && cur < 0) {
                // 值已漂移为负，删除 key，下次 countUnread 时从 DB 回填正确值
                stringRedisTemplate.delete(key);
                log.warn("Redis unread count went negative, evicted for reconcile. userId={}", receiverId);
            } else if (cur != null) {
                // 刷新 TTL，防止长时间未过期的 key 造成内存积压
                stringRedisTemplate.expire(key, UNREAD_TTL_MINUTES, TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            // Redis 异常时降级：删除 key，下次从 DB 回填
            log.warn("Redis decrement unread failed, evicting. userId={}", receiverId, e);
            try {
                stringRedisTemplate.delete(key);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 将 Redis 未读数置为 "0"（全部已读后调用）。
     * 置 0 而非删除：铃铛角标立即归零，且下次 unread-count 请求命中缓存直接返回 0，
     * 无需触发 DB COUNT(*) 回扫。
     */
    private void setUnreadCacheZero(Long receiverId) {
        String key = UNREAD_KEY_PREFIX + receiverId;
        try {
            stringRedisTemplate.opsForValue().set(
                    key, "0", UNREAD_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis set-zero unread failed, evicting. userId={}", receiverId, e);
            try {
                stringRedisTemplate.delete(key);
            } catch (Exception ignored) {}
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
     * 批量解析发送者昵称：优先读 Redis 缓存（notify:sender:{userId}），未命中才并发 Feign。
     *
     * <p>Phase 1：逐个查 Redis，命中则直接放入结果集。
     * Phase 2：对 miss 的 userId 提交到 FEIGN_EXECUTOR 并发请求 user-service，
     * 成功后回填 Redis（TTL {@value #SENDER_TTL_MINUTES} 分钟），整批 3 秒超时。
     *
     * <p>空字符串表示「已查过但无昵称」，用于防止缓存穿透；前端仍展示「用户{id}」。
     */
    private Map<Long, String> batchResolveUsernames(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, String> result = new HashMap<>();
        Set<Long> missIds = new LinkedHashSet<>();

        for (Long id : userIds) {
            try {
                String cached = stringRedisTemplate.opsForValue().get(senderCacheKey(id));
                if (cached != null) {
                    result.put(id, cached.isEmpty() ? null : cached);
                } else {
                    missIds.add(id);
                }
            } catch (Exception e) {
                log.debug("sender cache get failed userId={}", id, e);
                missIds.add(id);
            }
        }

        if (missIds.isEmpty()) {
            return result;
        }

        List<CompletableFuture<Map.Entry<Long, String>>> futures = missIds.stream()
                .map(id -> CompletableFuture
                        .supplyAsync(() -> {
                            String name = resolveUsernameFromFeign(id);
                            cacheSenderUsername(id, name);
                            return name != null ? Map.entry(id, name) : null;
                        }, FEIGN_EXECUTOR)
                        .handle((entry, ex) -> {
                            if (ex != null) {
                                log.debug("resolve username async failed userId={}", id, ex);
                                return null;
                            }
                            return entry;
                        }))
                .collect(Collectors.toList());

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

    /**
     * 单用户昵称解析（WebSocket 推送等路径）：Cache-Aside，miss 时 Feign 并回填。
     */
    private String resolveUsernameWithCache(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            String cached = stringRedisTemplate.opsForValue().get(senderCacheKey(userId));
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }
        } catch (Exception e) {
            log.debug("sender cache get failed userId={}", userId, e);
        }
        String name = resolveUsernameFromFeign(userId);
        cacheSenderUsername(userId, name);
        return name;
    }

    private static String senderCacheKey(Long userId) {
        return SENDER_KEY_PREFIX + userId;
    }

    /** 回填昵称缓存；username 为 null 时存空串，避免重复 Feign 穿透。 */
    private void cacheSenderUsername(Long userId, String username) {
        try {
            stringRedisTemplate.opsForValue().set(
                    senderCacheKey(userId),
                    username != null ? username : "",
                    SENDER_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("sender cache set failed userId={}", userId, e);
        }
    }

    /** 远程调用 user-service 获取昵称（无缓存）。 */
    private String resolveUsernameFromFeign(Long userId) {
        if (userId == null) return null;
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
        if (routingKey == null) return null;
        return switch (routingKey) {
            case "interaction.like"    -> "like";
            case "interaction.collect" -> "collect";
            case "interaction.comment" -> "comment";
            default -> null;
        };
    }

    private static String normalizeInteractionTypeFromBody(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw)) return null;
        return switch (raw.trim()) {
            case "like", "collect", "comment" -> raw.trim();
            default -> null;
        };
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.valueOf(String.valueOf(value));
    }
}
