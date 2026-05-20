package com.biteblog.feed.service;

import com.biteblog.feed.dto.FeedItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    private static final String INBOX_PREFIX = "feed:inbox:";
    private static final String BIG_V_KEY = "feed:bigv";
    private static final String FOLLOW_PREFIX = "follow:";
    private static final String DELETED_KEY = "feed:deleted";

    // ==================== 关注 Feed 流（推拉结合） ====================

    /**
     * 推拉结合 Timeline：
     * 1. 从 Redis inbox:{userId} ZSet 拉取已推送的笔记（普通用户发布的）
     * 2. 查出当前用户关注的所有大V，实时拉取他们的最新笔记
     * 3. 合并去重，按时间倒序，游标分页
     * 4. inbox 为空时降级到数据库查询（冷启动兜底）
     */
    public Map<String, Object> getTimeline(Long userId, Long cursor, int size) {
        // cursor 为上一页最后一条笔记的时间戳(毫秒)，null 表示第一页

        // 1. 全量获取inbox中已推送的笔记
        Set<String> inboxNoteIds = redisTemplate.opsForZSet().reverseRange(
                INBOX_PREFIX + userId, 0, -1);

        // 2. 获取当前用户关注的大V列表，实时拉取
        Set<String> bigVs = redisTemplate.opsForSet().members(BIG_V_KEY);
        Set<String> following = redisTemplate.opsForSet().members(FOLLOW_PREFIX + userId);

        List<Long> bigVNoteIds = new ArrayList<>();
        if (bigVs != null && following != null) {
            Set<String> followedBigVs = new HashSet<>(following);
            followedBigVs.retainAll(bigVs);

            for (String bigVId : followedBigVs) {
                Set<String> bvNotes = redisTemplate.opsForZSet().reverseRange(
                        INBOX_PREFIX + bigVId, 0, -1);
                if (bvNotes != null) {
                    for (String n : bvNotes) {
                        bigVNoteIds.add(Long.parseLong(n));
                    }
                }
            }
        }

        // 3. 合并去重
        Set<Long> allNoteIds = new LinkedHashSet<>();
        if (inboxNoteIds != null) {
            for (String id : inboxNoteIds) {
                allNoteIds.add(Long.parseLong(id));
            }
        }
        allNoteIds.addAll(bigVNoteIds);

        // 4. inbox 为空 → 降级到数据库查询
        if (allNoteIds.isEmpty()) {
            return getTimelineFromDb(userId, size);
        }

        // 5. 过滤已删除笔记
        Set<String> deletedIds = redisTemplate.opsForSet().members(DELETED_KEY);
        if (deletedIds != null) {
            allNoteIds.removeIf(id -> deletedIds.contains(id.toString()));
        }

        // 6. 查询全部笔记详情，按时间倒序排列
        List<FeedItemVO> allItems = queryAndSortByTime(new ArrayList<>(allNoteIds));

        // 6.5 过滤掉自己的笔记
        allItems.removeIf(item -> userId.equals(item.getAuthorId()));

        // 6.6 过滤后为空 → 降级到数据库查询
        if (allItems.isEmpty()) {
            return getTimelineFromDb(userId, size);
        }

        // 7. 基于时间戳游标分页：过滤掉已读的（时间戳 >= cursor 的项）
        if (cursor != null && cursor > 0) {
            List<FeedItemVO> filtered = new ArrayList<>();
            for (FeedItemVO item : allItems) {
                if (toTimestamp(item.getCreatedAt()) < cursor) {
                    filtered.add(item);
                }
            }
            allItems = filtered;
        }

        // 8. 取前 size 条
        boolean hasMore = allItems.size() > size;
        List<FeedItemVO> items = hasMore ? allItems.subList(0, size) : allItems;
        Long nextCursor = (!items.isEmpty() && hasMore)
                ? toTimestamp(items.get(items.size() - 1).getCreatedAt())
                : null;

        // 8.5 inbox 为空时回填，确保后续请求可直接命中缓存
        if (inboxNoteIds == null || inboxNoteIds.isEmpty()) {
            warmUpInbox(userId);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("list", items);
        result.put("cursor", nextCursor);
        result.put("hasMore", hasMore);
        return result;
    }

    /**
     * 冷启动兜底：inbox 为空时，直接从数据库查关注用户的最新笔记
     */
    private Map<String, Object> getTimelineFromDb(Long userId, int size) {
        Set<String> following = redisTemplate.opsForSet().members(FOLLOW_PREFIX + userId);
        List<FeedItemVO> items;

        if (following == null || following.isEmpty()) {
            items = Collections.emptyList();
        } else {
            String inClause = following.stream().collect(Collectors.joining(","));
            items = jdbcTemplate.query(
                    "SELECT n.id, n.author_id, n.title, n.shop_name, n.like_count, " +
                            "n.collect_count, n.comment_count, n.created_at, " +
                            "(SELECT ni.image_url FROM note_image ni WHERE ni.note_id = n.id ORDER BY ni.sort_order LIMIT 1) AS cover_url " +
                            "FROM note n WHERE n.author_id IN (" + inClause + ") AND n.status = 1 " +
                            "ORDER BY n.created_at DESC LIMIT ?",
                    (rs, rowNum) -> {
                        FeedItemVO vo = new FeedItemVO();
                        vo.setPostId(rs.getLong("id"));
                        vo.setAuthorId(rs.getLong("author_id"));
                        vo.setTitle(rs.getString("title"));
                        vo.setShopName(rs.getString("shop_name"));
                        vo.setLikeCount(rs.getInt("like_count"));
                        vo.setCollectCount(rs.getInt("collect_count"));
                        vo.setCommentCount(rs.getInt("comment_count"));
                        vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                        vo.setCoverUrl(rs.getString("cover_url"));
                        return vo;
                    }, size);

            // 预热inbox
            if (!items.isEmpty()) {
                for (FeedItemVO item : items) {
                    redisTemplate.opsForZSet().add(INBOX_PREFIX + userId,
                            item.getPostId().toString(), toTimestamp(item.getCreatedAt()));
                }
            }
        }

        Long nextCursor = null;
        if (!items.isEmpty()) {
            nextCursor = toTimestamp(items.get(items.size() - 1).getCreatedAt());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("list", items);
        result.put("cursor", nextCursor);
        result.put("hasMore", false);
        return result;
    }

    // ==================== 辅助方法 ====================

    private List<FeedItemVO> queryAndSortByTime(List<Long> noteIds) {
        if (noteIds.isEmpty()) return Collections.emptyList();

        String inClause = noteIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        List<FeedItemVO> items = jdbcTemplate.query(
                "SELECT n.id, n.author_id, n.title, n.shop_name, n.like_count, " +
                        "n.collect_count, n.comment_count, n.created_at, " +
                        "(SELECT ni.image_url FROM note_image ni WHERE ni.note_id = n.id ORDER BY ni.sort_order LIMIT 1) AS cover_url " +
                        "FROM note n WHERE n.id IN (" + inClause + ") AND n.status = 1 " +
                        "ORDER BY n.created_at DESC",
                (rs, rowNum) -> {
                    FeedItemVO vo = new FeedItemVO();
                    vo.setPostId(rs.getLong("id"));
                    vo.setAuthorId(rs.getLong("author_id"));
                    vo.setTitle(rs.getString("title"));
                    vo.setShopName(rs.getString("shop_name"));
                    vo.setLikeCount(rs.getInt("like_count"));
                    vo.setCollectCount(rs.getInt("collect_count"));
                    vo.setCommentCount(rs.getInt("comment_count"));
                    vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                    vo.setCoverUrl(rs.getString("cover_url"));
                    return vo;
                });

        return items;
    }

    /**
     * inbox 被清空时回填：从 MySQL 查询关注用户的最新笔记并写入 Redis，
     * 避免每次请求都走大V拉取/DB降级的慢路径。
     */
    private void warmUpInbox(Long userId) {
        Set<String> following = redisTemplate.opsForSet().members(FOLLOW_PREFIX + userId);
        if (following == null || following.isEmpty()) {
            return;
        }
        String inClause = String.join(",", following);
        jdbcTemplate.query(
                "SELECT n.id, n.created_at FROM note n WHERE n.author_id IN (" + inClause + ") AND n.status = 1 ORDER BY n.created_at DESC LIMIT 100",
                (rs) -> {
                    long ts = rs.getTimestamp("created_at").toLocalDateTime()
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    redisTemplate.opsForZSet().add(INBOX_PREFIX + userId, rs.getString("id"), ts);
                });
    }

    private long toTimestamp(java.time.LocalDateTime dateTime) {
        return dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
