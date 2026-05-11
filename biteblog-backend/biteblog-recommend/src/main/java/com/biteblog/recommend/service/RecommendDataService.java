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
        int safeSize = Math.max(1, Math.min(size, MAX_HOT_NOTES_SIZE));
        return noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getStatus, NORMAL_STATUS)
                .orderByDesc(Note::getLikeCount)
                .orderByDesc(Note::getCollectCount)
                .orderByDesc(Note::getCommentCount)
                .orderByDesc(Note::getCreatedAt)
                .last("LIMIT " + safeSize));
    }

    public Map<Long, Note> getNormalNotesByIds(Collection<Long> noteIds) {
        if (noteIds == null || noteIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = noteIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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
        if (noteIds == null || noteIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = noteIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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
}
