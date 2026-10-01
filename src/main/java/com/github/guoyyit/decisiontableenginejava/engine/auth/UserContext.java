package com.github.guoyyit.decisiontableenginejava.engine.auth;

/**
 * 当前请求用户上下文（基于 ThreadLocal，由 {@link UserContextFilter} 从请求头注入）。
 */
public final class UserContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(String user) {
        CURRENT.set(user);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static String getOrAnonymous() {
        String u = CURRENT.get();
        return u == null || u.isBlank() ? "anonymous" : u;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
