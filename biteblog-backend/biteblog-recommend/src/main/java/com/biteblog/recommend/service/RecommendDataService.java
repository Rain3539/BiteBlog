package com.biteblog.recommend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biteblog.recommend.entity.Note;
import com.biteblog.recommend.entity.NoteImage;
import com.biteblog.recommend.entity.UserBehavior;
import com.biteblog.recommend.mapper.NoteImageMapper;
import com.biteblog.recommend.mapper.NoteMapper;
import com.biteblog.recommend.mapper.UserBehaviorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecommendDataService {

    private static final int NORMAL_STATUS = 1;
    private static final int MAX_HOT_NOTES_SIZE = 100;
    private static final int MAX_PRECOMPUTE_SIZE = 5000;

    private final NoteMapper noteMapper;
    private final NoteImageMapper noteImageMapper;
    private final UserBehaviorMapper userBehaviorMapper;

    public long countUserBehaviors(Long userId) {
        if (userId == null) {
            return 0;
        }
        return userBehaviorMapper.selectCount(new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId));
    }

    public List<UserBehavior> listRecentUserBehaviors(Long userId, int limit) {
        if (userId == null || limit <= 0) {
            return List.of();
        }
        return userBehaviorMapper.selectList(new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .orderByDesc(UserBehavior::getCreatedAt)
                .last("LIMIT " + limit));
    }

    public List<Note> listHotNotes(int size) {
        return listHotNotes(0, size);
    }

    public List<Note> listHotNotes(int offset, int size) {
        return listHotNotes(offset, size, List.of());
    }

    public List<Note> listHotNotes(int offset, int size, Collection<Long> excludedNoteIds) {
        int safeOffset = Math.max(0, offset);
        int safeSize = Math.max(1, Math.min(size, MAX_HOT_NOTES_SIZE));
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getStatus, NORMAL_STATUS);
        List<Long> excluded = distinctIds(excludedNoteIds);
        if (!excluded.isEmpty()) {
            wrapper.notIn(Note::getId, excluded);
        }
        return noteMapper.selectList(wrapper
                .orderByDesc(Note::getLikeCount)
                .orderByDesc(Note::getCollectCount)
                .orderByDesc(Note::getCommentCount)
                .orderByDesc(Note::getCreatedAt)
                .last("LIMIT " + safeOffset + ", " + safeSize));
    }

    public List<Note> searchNormalNotes(String keyword, String city, int size, Collection<Long> excludedNoteIds) {
        int safeSize = Math.max(1, Math.min(size, MAX_HOT_NOTES_SIZE));
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getStatus, NORMAL_STATUS);
        if (keyword != null && !keyword.isBlank()) {
            String text = keyword.trim();
            wrapper.and(item -> item.like(Note::getTitle, text)
                    .or()
                    .like(Note::getContent, text)
                    .or()
                    .like(Note::getShopName, text));
        }
        if (city != null && !city.isBlank()) {
            wrapper.like(Note::getAddress, city.trim());
        }
        List<Long> excluded = distinctIds(excludedNoteIds);
        if (!excluded.isEmpty()) {
            wrapper.notIn(Note::getId, excluded);
        }
        return noteMapper.selectList(wrapper
                .orderByDesc(Note::getLikeCount)
                .orderByDesc(Note::getCollectCount)
                .orderByDesc(Note::getCreatedAt)
                .last("LIMIT " + safeSize));
    }

    public List<UserBehavior> listBehaviorsByNoteIds(Collection<Long> noteIds, int limit) {
        List<Long> ids = distinctIds(noteIds);
        if (ids.isEmpty() || limit <= 0) {
            return List.of();
        }
        return userBehaviorMapper.selectList(new LambdaQueryWrapper<UserBehavior>()
                .in(UserBehavior::getNoteId, ids)
                .orderByDesc(UserBehavior::getCreatedAt)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public List<UserBehavior> listBehaviorsByUserIds(Collection<Long> userIds, int limit) {
        List<Long> ids = distinctIds(userIds);
        if (ids.isEmpty() || limit <= 0) {
            return List.of();
        }
        return userBehaviorMapper.selectList(new LambdaQueryWrapper<UserBehavior>()
                .in(UserBehavior::getUserId, ids)
                .orderByDesc(UserBehavior::getCreatedAt)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public List<UserBehavior> listRecentBehaviorsForPrecompute(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_PRECOMPUTE_SIZE));
        return userBehaviorMapper.selectList(new LambdaQueryWrapper<UserBehavior>()
                .orderByDesc(UserBehavior::getCreatedAt)
                .last("LIMIT " + safeLimit));
    }

    public List<Note> listNormalNotesForPrecompute(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_PRECOMPUTE_SIZE));
        return noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getStatus, NORMAL_STATUS)
                .orderByDesc(Note::getLikeCount)
                .orderByDesc(Note::getCollectCount)
                .orderByDesc(Note::getCommentCount)
                .orderByDesc(Note::getCreatedAt)
                .last("LIMIT " + safeLimit));
    }

    public Map<Long, Note> getNormalNotesByIds(Collection<Long> noteIds) {
        List<Long> ids = distinctIds(noteIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return noteMapper.selectList(new LambdaQueryWrapper<Note>()
                        .in(Note::getId, ids)
                        .eq(Note::getStatus, NORMAL_STATUS))
                .stream()
                .collect(Collectors.toMap(Note::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    public Map<Long, String> getCoverUrls(Collection<Long> noteIds) {
        List<Long> ids = distinctIds(noteIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<NoteImage> images = noteImageMapper.selectList(new LambdaQueryWrapper<NoteImage>()
                .in(NoteImage::getNoteId, ids)
                .orderByAsc(NoteImage::getSortOrder)
                .orderByAsc(NoteImage::getId));

        Map<Long, List<NoteImage>> grouped = images.stream()
                .collect(Collectors.groupingBy(NoteImage::getNoteId));

        Map<Long, String> covers = new LinkedHashMap<>();
        for (Long id : ids) {
            List<NoteImage> noteImages = grouped.getOrDefault(id, Collections.emptyList());
            noteImages.stream()
                    .min(Comparator.comparing(NoteImage::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(NoteImage::getId, Comparator.nullsLast(Long::compareTo)))
                    .map(NoteImage::getImageUrl)
                    .ifPresent(url -> covers.put(id, url));
        }
        return covers;
    }

    private List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
