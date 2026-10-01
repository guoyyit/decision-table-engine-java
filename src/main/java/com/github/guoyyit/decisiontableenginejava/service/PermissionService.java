package com.github.guoyyit.decisiontableenginejava.service;

import org.springframework.stereotype.Component;

/**
 * 权限校验。角色约定：
 *   RULE_ADMIN  可提交/激活/回滚规则
 *   RULE_VIEWER 仅可查看与评估
 * 越权访问抛出 AccessDeniedException。
 */
@Component
public class PermissionService {

    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    public boolean isAdmin(String roles) {
        return roles != null && java.util.Arrays.stream(roles.split(","))
                .map(String::trim).anyMatch("ROLE_RULE_ADMIN"::equals);
    }

    public void requireAdmin(String actor, String roles, String action) {
        if (!isAdmin(roles)) {
            throw new AccessDeniedException(
                    "用户 " + actor + " 无权执行 " + action + "，需要 ROLE_RULE_ADMIN");
        }
    }
}
