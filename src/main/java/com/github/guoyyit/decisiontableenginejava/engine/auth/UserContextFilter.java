package com.github.guoyyit.decisiontableenginejava.engine.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 X-User 请求头解析当前用户并注入 {@link UserContext}，请求结束后清理。
 */
@Component
@Order(1)
public class UserContextFilter extends OncePerRequestFilter {

    public static final String USER_HEADER = "X-User";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String user = request.getHeader(USER_HEADER);
            UserContext.set(user == null || user.isBlank() ? "anonymous" : user);
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
