package com.biteblog.post.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.biteblog.common.exception.BusinessException;
import com.biteblog.common.result.ErrorCode;
import com.biteblog.post.dto.EsPostDocument;
import com.biteblog.post.dto.PostDetailVO;
import com.biteblog.post.dto.PublishNoteRequest;
import com.biteblog.post.entity.*;
import com.biteblog.post.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostService extends ServiceImpl<NoteMapper, Note> {

    private final NoteMapper noteMapper;
    private final NoteImageMapper imageMapper;
    private final NoteLikeMapper likeMapper;
    private final NoteFavoriteMapper favoriteMapper;
    private final RedisTemplate<String, Object> objectRedisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final EsSyncService esSyncService;
    private final PostEventPublisher eventPublisher;

    private static final String CACHE_KEY = "post:cache:";
    private static final long CACHE_TTL_MINUTES = 30;

    // ==================== 发布笔记 ====================

    @Transactional
    public Long publishNote(PublishNoteRequest req, Long authorId) {
        Note note = new Note();
        note.setAuthorId(authorId);
        note.setTitle(req.getTitle());
        note.setContent(req.getContent());
        note.setShopName(req.getShopName());
        note.setAddress(req.getAddress());
        note.setLongitude(req.getLongitude());
        note.setLatitude(req.getLatitude());
        note.setScoreColor(req.getScoreColor());
        note.setScoreSmell(req.getScoreSmell());
        note.setScoreTaste(req.getScoreTaste());
        note.setLikeCount(0);
        note.setCollectCount(0);
        note.setCommentCount(0);
        note.setStatus(1);
        noteMapper.insert(note);

        if (req.getImageUrls() != null && !req.getImageUrls().isEmpty()) {
            for (int i = 0; i < req.getImageUrls().size(); i++) {
                NoteImage img = new NoteImage();
                img.setNoteId(note.getId());
                img.setImageUrl(req.getImageUrls().get(i));
                img.setSortOrder(i);
                imageMapper.insert(img);
            }
        }

        // 异步写 ES
        try {
            EsPostDocument doc = buildEsDoc(note, req.getImageUrls());
            esSyncService.savePostToEs(doc);
        } catch (Exception ignored) {
            // ES 不可用不影响发布
        }

        // 发 MQ 事件
        eventPublisher.publishNoteCreated(note.getId(), authorId);

        // 同步写入作者自己的inbox（绕过fanout延迟，保证发布者立即可见）
        try {
            stringRedisTemplate.opsForZSet().add("feed:inbox:" + authorId, note.getId().toString(), System.currentTimeMillis());
        } catch (Exception ignored) {}

        return note.getId();
    }

    // ==================== 笔记详情 ====================

    @SuppressWarnings("unchecked")
    public PostDetailVO getDetail(Long postId, Long currentUserId) {
        String cacheKey = CACHE_KEY + postId;

        // 先查 Redis
        Object cached = objectRedisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof Map) {
            return mapToVO((Map<String, Object>) cached);
        }

        Note note = noteMapper.selectById(postId);
        if (note == null || note.getStatus() == 0) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        List<NoteImage> images = imageMapper.selectList(
                new LambdaQueryWrapper<NoteImage>()
                        .eq(NoteImage::getNoteId, postId)
                        .orderByAsc(NoteImage::getSortOrder)
        );
        List<String> imageUrls = images.stream()
                .map(NoteImage::getImageUrl)
                .collect(Collectors.toList());

        PostDetailVO vo = new PostDetailVO();
        vo.setPostId(note.getId());
        vo.setAuthorId(note.getAuthorId());
        vo.setTitle(note.getTitle());
        vo.setContent(note.getContent());
        vo.setShopName(note.getShopName());
        vo.setAddress(note.getAddress());
        vo.setLongitude(note.getLongitude());
        vo.setLatitude(note.getLatitude());
        vo.setImageUrls(imageUrls);
        vo.setScoreColor(note.getScoreColor());
        vo.setScoreSmell(note.getScoreSmell());
        vo.setScoreTaste(note.getScoreTaste());
        vo.setLikeCount(note.getLikeCount());
        vo.setCollectCount(note.getCollectCount());
        vo.setCommentCount(note.getCommentCount());
        vo.setCreatedAt(note.getCreatedAt());

        // 互动状态
        if (currentUserId != null) {
            boolean liked = likeMapper.exists(
                    new LambdaQueryWrapper<NoteLike>()
                            .eq(NoteLike::getNoteId, postId)
                            .eq(NoteLike::getUserId, currentUserId)
            );
            boolean favorited = favoriteMapper.exists(
                    new LambdaQueryWrapper<NoteFavorite>()
                            .eq(NoteFavorite::getNoteId, postId)
                            .eq(NoteFavorite::getUserId, currentUserId)
            );
            vo.setLiked(liked);
            vo.setFavorited(favorited);
        } else {
            vo.setLiked(false);
            vo.setFavorited(false);
        }

        // 写回缓存
        objectRedisTemplate.opsForValue().set(cacheKey, vo, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        return vo;
    }

    // ==================== 删除笔记 ====================

    @Transactional
    public void deleteNote(Long postId, Long userId) {
        Note note = noteMapper.selectById(postId);
        if (note == null || note.getStatus() == 0) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        if (!note.getAuthorId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        note.setStatus(0);
        noteMapper.updateById(note);

        objectRedisTemplate.delete(CACHE_KEY + postId);

        // 异步清理 ES
        try {
            esSyncService.deletePostFromEs(postId);
        } catch (Exception ignored) {
        }

        eventPublisher.publishNoteDeleted(postId);
    }

    // ==================== 用户笔记列表 ====================

    public Map<String, Object> getUserPosts(Long userId, int page, int size) {
        Page<Note> notePage = noteMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Note>()
                        .eq(Note::getAuthorId, userId)
                        .eq(Note::getStatus, 1)
                        .orderByDesc(Note::getCreatedAt));

        List<Map<String, Object>> list = notePage.getRecords().stream().map(note -> {
            Map<String, Object> map = new HashMap<>();
            map.put("postId", note.getId());
            map.put("title", note.getTitle());
            map.put("shopName", note.getShopName());
            map.put("likeCount", note.getLikeCount());
            map.put("collectCount", note.getCollectCount());
            map.put("commentCount", note.getCommentCount());
            map.put("createdAt", note.getCreatedAt());
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", notePage.getTotal());
        return result;
    }

    // ==================== ES 全文搜索 ====================

    public Map<String, Object> search(String keyword, int page, int size) {
        try {
            List<EsPostDocument> list = esSyncService.search(keyword, page, size);
            long total = esSyncService.countSearchResults(keyword);

            List<Map<String, Object>> items = list.stream().map(doc -> {
                Map<String, Object> map = new HashMap<>();
                map.put("postId", doc.getPostId());
                map.put("title", doc.getTitle());
                map.put("shopName", doc.getShopName());
                map.put("likeCount", doc.getLikeCount());
                if (doc.getImageUrls() != null && !doc.getImageUrls().isEmpty()) {
                    map.put("cover", doc.getImageUrls().get(0));
                }
                return map;
            }).collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("list", items);
            result.put("total", total);
            return result;
        } catch (Exception e) {
            // ES 不可用降级——返回空列表
            Map<String, Object> result = new HashMap<>();
            result.put("list", List.of());
            result.put("total", 0L);
            return result;
        }
    }

    // ==================== 私有方法 ====================

    private EsPostDocument buildEsDoc(Note note, List<String> imageUrls) {
        EsPostDocument doc = new EsPostDocument();
        doc.setPostId(note.getId().toString());
        doc.setUserId(note.getAuthorId().toString());
        doc.setTitle(note.getTitle());
        doc.setContent(note.getContent());
        doc.setShopName(note.getShopName());
        doc.setImageUrls(imageUrls);
        doc.setScoreColor(note.getScoreColor());
        doc.setScoreSmell(note.getScoreSmell());
        doc.setScoreTaste(note.getScoreTaste());
        doc.setLikeCount(note.getLikeCount().longValue());
        doc.setCollectCount(note.getCollectCount().longValue());
        doc.setCommentCount(note.getCommentCount().longValue());
        doc.setStatus(note.getStatus());
        doc.setCreatedAt(note.getCreatedAt());
        return doc;
    }

    @SuppressWarnings("unchecked")
    private PostDetailVO mapToVO(Map<String, Object> map) {
        PostDetailVO vo = new PostDetailVO();
        vo.setPostId(toLong(map.get("postId")));
        vo.setAuthorId(toLong(map.get("authorId")));
        vo.setAuthorName((String) map.get("authorName"));
        vo.setAuthorAvatar((String) map.get("authorAvatar"));
        vo.setTitle((String) map.get("title"));
        vo.setContent((String) map.get("content"));
        vo.setShopName((String) map.get("shopName"));
        vo.setAddress((String) map.get("address"));
        vo.setImageUrls((List<String>) map.get("imageUrls"));
        vo.setScoreColor((Integer) map.get("scoreColor"));
        vo.setScoreSmell((Integer) map.get("scoreSmell"));
        vo.setScoreTaste((Integer) map.get("scoreTaste"));
        vo.setLikeCount((Integer) map.get("likeCount"));
        vo.setCollectCount((Integer) map.get("collectCount"));
        vo.setCommentCount((Integer) map.get("commentCount"));
        vo.setLiked((Boolean) map.get("liked"));
        vo.setFavorited((Boolean) map.get("favorited"));
        return vo;
    }

    private Long toLong(Object obj) {
        if (obj instanceof Integer) {
            return ((Integer) obj).longValue();
        }
        return (Long) obj;
    }
}
