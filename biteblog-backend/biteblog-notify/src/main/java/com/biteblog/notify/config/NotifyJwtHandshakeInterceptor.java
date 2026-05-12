package com.biteblog.notify.config;

import com.biteblog.common.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * STOMP 握手：Query 参数 token=JWT（直连 notify-service:8087，与网关 HTTP 鉴权分离）
 */
@Slf4j
@Component
public class NotifyJwtHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        String token = servletRequest.getServletRequest().getParameter("token");
        if (!StringUtils.hasText(token)) {
            log.warn("notify ws handshake rejected: missing token");
            return false;
        }
        if (!JwtUtil.validateToken(token)) {
            log.warn("notify ws handshake rejected: invalid token");
            return false;
        }
        Long userId = JwtUtil.getUserId(token);
        attributes.put("userId", userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
