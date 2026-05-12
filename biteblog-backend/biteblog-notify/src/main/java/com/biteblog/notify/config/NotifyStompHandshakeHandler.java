package com.biteblog.notify.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * 将 Principal#getName 设为 userId 字符串，供 convertAndSendToUser 路由
 */
@Component
public class NotifyStompHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Object uid = attributes.get("userId");
        if (uid == null) {
            return null;
        }
        String name = String.valueOf(uid);
        return () -> name;
    }
}
