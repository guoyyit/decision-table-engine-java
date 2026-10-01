package com.github.guoyyit.decisiontableenginejava.engine.auth;

import com.github.guoyyit.decisiontableenginejava.engine.model.ForbiddenException;
import com.github.guoyyit.decisiontableenginejava.engine.model.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限校验：基于用户角色判断是否允许执行某操作。
 * <p>角色映射通过配置项 engine.auth.admins / engine.auth.publishers 配置，
 * 未配置的用户默认为 VIEWER。</p>
 */
@Component
public class PermissionService {

    private final Set<String> admins;
    private final Set<String> publishers;

    public PermissionService(
            @Value("${engine.auth.admins:admin}") String adminUsers,
            @Value("${engine.auth.publishers:publisher}") String publisherUsers) {
        this.admins = split(adminUsers);
        this.publishers = split(publisherUsers);
    }

    private static Set<String> split(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 解析用户角色。 */
    public Role resolve(String user) {
        if (user == null) {
            return Role.VIEWER;
        }
        if (admins.contains(user)) {
            return Role.ADMIN;
        }
        if (publishers.contains(user)) {
            return Role.PUBLISHER;
        }
        return Role.VIEWER;
    }

    /** 要求当前用户至少拥有指定角色，否则抛出 ForbiddenException。 */
    public void require(String user, Role required) {
        Role actual = resolve(user);
        if (actual.ordinal() < required.ordinal()) {
            throw new ForbiddenException(
                    "用户 " + user + " 角色为 " + actual + "，无权执行需要 " + required + " 的操作");
        }
    }
}
