package com.biteblog.user.controller;

import com.biteblog.common.result.Result;
import com.biteblog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 用户注册 POST /user/register */
    @PostMapping("/register")
    public Result<?> register(@RequestBody Map<String, String> params) {
        String phone = params.get("phone");
        String password = params.get("password");
        String username = params.get("username");
        return Result.success(userService.register(phone, password, username));
    }

    /** 用户登录 POST /user/login */
    @PostMapping("/login")
    public Result<?> login(@RequestBody Map<String, String> params) {
        String phone = params.get("phone");
        String password = params.get("password");
        return Result.success(userService.login(phone, password));
    }

    /** 获取用户主页 GET /user/{id} */
    @GetMapping("/{id}")
    public Result<?> getProfile(@PathVariable Long id) {
        return Result.success(userService.getUserInfo(id));
    }

    /** 关注/取关 POST /user/follow/{id} */
    @PostMapping("/follow/{id}")
    public Result<?> follow(@PathVariable Long id,
                            @RequestHeader("X-User-Id") Long userId) {
        boolean followed = userService.follow(userId, id);
        return Result.success(Map.of("followed", followed));
    }

    /** 获取关注列表 GET /user/{id}/following */
    @GetMapping("/{id}/following")
    public Result<?> getFollowing(@PathVariable Long id,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return Result.success(userService.getFollowingList(id, page, size));
    }

    /** 获取粉丝列表 GET /user/{id}/followers */
    @GetMapping("/{id}/followers")
    public Result<?> getFollowers(@PathVariable Long id,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return Result.success(userService.getFollowersList(id, page, size));
    }
}
