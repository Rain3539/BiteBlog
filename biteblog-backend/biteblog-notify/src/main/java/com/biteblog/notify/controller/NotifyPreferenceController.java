package com.biteblog.notify.controller;

import com.biteblog.common.result.Result;
import com.biteblog.notify.dto.DndTimeRequest;
import com.biteblog.notify.dto.MuteSenderRequest;
import com.biteblog.notify.dto.MuteTypeRequest;
import com.biteblog.notify.dto.NotificationPreferenceVO;
import com.biteblog.notify.service.NotifyPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notify/preference")
@RequiredArgsConstructor
public class NotifyPreferenceController {

    private final NotifyPreferenceService preferenceService;

    @GetMapping
    public Result<List<NotificationPreferenceVO>> list(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(preferenceService.listPreferences(userId));
    }

    @PostMapping("/mute/type")
    public Result<NotificationPreferenceVO> muteType(@RequestHeader("X-User-Id") Long userId,
                                                     @RequestBody MuteTypeRequest body) {
        return Result.success(preferenceService.muteType(userId, body.getType()));
    }

    @PostMapping("/mute/sender")
    public Result<NotificationPreferenceVO> muteSender(@RequestHeader("X-User-Id") Long userId,
                                                       @RequestBody MuteSenderRequest body) {
        return Result.success(preferenceService.muteSender(userId, body.getSenderId()));
    }

    @PostMapping("/dnd")
    public Result<NotificationPreferenceVO> setDnd(@RequestHeader("X-User-Id") Long userId,
                                                   @RequestBody DndTimeRequest body) {
        return Result.success(preferenceService.setDndTime(userId, body.getTimeRange()));
    }

    @DeleteMapping("/dnd")
    public Result<Void> clearDnd(@RequestHeader("X-User-Id") Long userId) {
        preferenceService.clearDndTime(userId);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader("X-User-Id") Long userId,
                               @PathVariable("id") Long id) {
        preferenceService.deletePreference(userId, id);
        return Result.success();
    }
}
