package com.biteblog.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.biteblog.common.exception.BusinessException;
import com.biteblog.common.result.ErrorCode;
import com.biteblog.common.util.JwtUtil;
import com.biteblog.user.entity.FollowRelation;
import com.biteblog.user.entity.User;
import com.biteblog.user.mapper.FollowRelationMapper;
import com.biteblog.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService extends ServiceImpl<UserMapper, User> {

    private final UserMapper userMapper;
    private final FollowRelationMapper followRelationMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> objectRedisTemplate;

    private static final String CACHE_USER_KEY = "cache:user:";
    private static final String REDIS_FOLLOW_KEY = "follow:";
    private static final String REDIS_FANS_KEY = "fans:";
    private static final long CACHE_TTL_HOURS = 2;

    // ==================== 注册 ====================

    public Map<String, Object> register(String phone, String password, String username) {
        // 手机号唯一性检查
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (count > 0) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }

        // 用户名唯一性检查
        Long nameCount = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (nameCount > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "用户名已被使用");
        }

        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFollowerCount(0);
        user.setFollowingCount(0);
        user.setLikeCount(0);
        user.setIsBigV(false);
        user.setStatus(1);
        userMapper.insert(user);

        String token = JwtUtil.generateToken(user.getId(), user.getUsername());

        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("token", token);
        return result;
    }

    // ==================== 登录 ====================

    public Map<String, Object> login(String phone, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }

        String token = JwtUtil.generateToken(user.getId(), user.getUsername());

        // 缓存 session 到 Redis
        redisTemplate.opsForValue().set(
                "session:" + user.getId(), token, 24, TimeUnit.HOURS);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        return result;
    }

    // ==================== 获取用户信息 ====================

    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserInfo(Long userId) {
        String cacheKey = CACHE_USER_KEY + userId;

        // 先查 Redis 缓存
        Object cached = objectRedisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof Map) {
            return (Map<String, Object>) cached;
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("avatar", user.getAvatar());
        result.put("bio", user.getBio());
        result.put("followerCount", user.getFollowerCount());
        result.put("followingCount", user.getFollowingCount());
        result.put("likeCount", user.getLikeCount());
        result.put("isBigV", user.getIsBigV());
        result.put("createdAt", user.getCreatedAt());

        // 写入缓存
        objectRedisTemplate.opsForValue().set(cacheKey, result, CACHE_TTL_HOURS, TimeUnit.HOURS);

        return result;
    }

    // ==================== 关注 / 取关 ====================

    @Transactional
    public boolean follow(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "不能关注自己");
        }

        // 检查目标用户是否存在
        if (userMapper.selectById(targetUserId) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // 查是否已关注
        FollowRelation existing = followRelationMapper.selectOne(
                new LambdaQueryWrapper<FollowRelation>()
                        .eq(FollowRelation::getUserId, userId)
                        .eq(FollowRelation::getTargetUserId, targetUserId));

        if (existing != null) {
            // 已关注 → 取关
            followRelationMapper.deleteById(existing.getId());

            // 更新计数
            userMapper.update(null,
                    new LambdaUpdateWrapper<User>()
                            .eq(User::getId, userId)
                            .setSql("following_count = following_count - 1"));
            userMapper.update(null,
                    new LambdaUpdateWrapper<User>()
                            .eq(User::getId, targetUserId)
                            .setSql("follower_count = follower_count - 1"));

            // 更新 Redis
            redisTemplate.opsForSet().remove(REDIS_FOLLOW_KEY + userId, targetUserId.toString());
            redisTemplate.opsForSet().remove(REDIS_FANS_KEY + targetUserId, userId.toString());
            evictUserCache(userId, targetUserId);

            return false; // 取关
        } else {
            // 未关注 → 关注
            FollowRelation relation = new FollowRelation();
            relation.setUserId(userId);
            relation.setTargetUserId(targetUserId);
            followRelationMapper.insert(relation);

            // 更新计数
            userMapper.update(null,
                    new LambdaUpdateWrapper<User>()
                            .eq(User::getId, userId)
                            .setSql("following_count = following_count + 1"));
            userMapper.update(null,
                    new LambdaUpdateWrapper<User>()
                            .eq(User::getId, targetUserId)
                            .setSql("follower_count = follower_count + 1"));

            // 更新 Redis
            redisTemplate.opsForSet().add(REDIS_FOLLOW_KEY + userId, targetUserId.toString());
            redisTemplate.opsForSet().add(REDIS_FANS_KEY + targetUserId, userId.toString());
            evictUserCache(userId, targetUserId);

            return true; // 关注
        }
    }

    // ==================== 关注列表 ====================

    public Map<String, Object> getFollowingList(Long userId, int page, int size) {
        Page<FollowRelation> pageParam = new Page<>(page, size);
        Page<FollowRelation> followPage = followRelationMapper.selectPage(pageParam,
                new LambdaQueryWrapper<FollowRelation>()
                        .eq(FollowRelation::getUserId, userId)
                        .orderByDesc(FollowRelation::getCreatedAt));

        List<FollowRelation> records = followPage.getRecords();
        if (records.isEmpty()) {
            return Map.of("list", List.of(), "total", followPage.getTotal());
        }

        // 批量查询用户信息
        List<Long> targetIds = records.stream()
                .map(FollowRelation::getTargetUserId)
                .collect(Collectors.toList());
        List<User> users = userMapper.selectBatchIds(targetIds);
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> list = records.stream().map(r -> {
            User u = userMap.get(r.getTargetUserId());
            Map<String, Object> item = new HashMap<>();
            item.put("userId", u.getId());
            item.put("username", u.getUsername());
            item.put("avatar", u.getAvatar());
            item.put("bio", u.getBio());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", followPage.getTotal());
        return result;
    }

    // ==================== 粉丝列表 ====================

    public Map<String, Object> getFollowersList(Long userId, int page, int size) {
        Page<FollowRelation> pageParam = new Page<>(page, size);
        Page<FollowRelation> followPage = followRelationMapper.selectPage(pageParam,
                new LambdaQueryWrapper<FollowRelation>()
                        .eq(FollowRelation::getTargetUserId, userId)
                        .orderByDesc(FollowRelation::getCreatedAt));

        List<FollowRelation> records = followPage.getRecords();
        if (records.isEmpty()) {
            return Map.of("list", List.of(), "total", followPage.getTotal());
        }

        List<Long> followerIds = records.stream()
                .map(FollowRelation::getUserId)
                .collect(Collectors.toList());
        List<User> users = userMapper.selectBatchIds(followerIds);
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> list = records.stream().map(r -> {
            User u = userMap.get(r.getUserId());
            Map<String, Object> item = new HashMap<>();
            item.put("userId", u.getId());
            item.put("username", u.getUsername());
            item.put("avatar", u.getAvatar());
            item.put("bio", u.getBio());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", followPage.getTotal());
        return result;
    }

    // ==================== 私有方法 ====================

    private void evictUserCache(Long... userIds) {
        for (Long id : userIds) {
            redisTemplate.delete(CACHE_USER_KEY + id);
        }
    }
}
