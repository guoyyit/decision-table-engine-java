package com.github.guoyyit.decisiontableenginejava.engine.web;

import com.github.guoyyit.decisiontableenginejava.engine.auth.UserContext;
import com.github.guoyyit.decisiontableenginejava.engine.model.PublishRequest;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSet;
import com.github.guoyyit.decisiontableenginejava.engine.model.VersionView;
import com.github.guoyyit.decisiontableenginejava.engine.store.RuleSetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 规则集版本管理接口：发布、回滚、提升灰度、版本查询。
 */
@RestController
@RequestMapping("/api/rulesets")
public class RuleSetController {

    private final RuleSetService ruleSetService;

    public RuleSetController(RuleSetService ruleSetService) {
        this.ruleSetService = ruleSetService;
    }

    /** 发布新版本（需要 PUBLISHER 或 ADMIN 角色）。 */
    @PostMapping("/publish")
    public VersionView publish(@RequestBody PublishRequest request) {
        var compiled = ruleSetService.publish(request, UserContext.getOrAnonymous());
        RuleSet rs = compiled.ruleSet();
        return new VersionView(rs.version(), rs.name(), rs.status(), rs.createdBy(),
                rs.createdAt(), rs.comment(), rs.grayRatio(), rs.grayKey(),
                rs.tables().size(),
                rs.tables().stream().mapToInt(t -> t.rows().size()).sum());
    }

    /** 回滚到指定历史版本（需要 ADMIN 角色）。 */
    @PostMapping("/{name}/rollback")
    public VersionView rollback(@PathVariable String name, @RequestParam int version) {
        var compiled = ruleSetService.rollback(name, version, UserContext.getOrAnonymous());
        RuleSet rs = compiled.ruleSet();
        return new VersionView(rs.version(), rs.name(), rs.status(), rs.createdBy(),
                rs.createdAt(), rs.comment(), rs.grayRatio(), rs.grayKey(),
                rs.tables().size(),
                rs.tables().stream().mapToInt(t -> t.rows().size()).sum());
    }

    /** 将灰度版本提升为全量（需要 ADMIN 角色）。 */
    @PostMapping("/{name}/promote")
    public VersionView promote(@PathVariable String name) {
        var compiled = ruleSetService.promote(name, UserContext.getOrAnonymous());
        RuleSet rs = compiled.ruleSet();
        return new VersionView(rs.version(), rs.name(), rs.status(), rs.createdBy(),
                rs.createdAt(), rs.comment(), rs.grayRatio(), rs.grayKey(),
                rs.tables().size(),
                rs.tables().stream().mapToInt(t -> t.rows().size()).sum());
    }

    /** 列出所有规则集名称。 */
    @GetMapping
    public Set<String> names() {
        return ruleSetService.names();
    }

    /** 列出规则集的所有版本。 */
    @GetMapping("/{name}/versions")
    public List<VersionView> versions(@PathVariable String name) {
        return ruleSetService.listVersions(name);
    }

    /** 获取指定版本的规则集定义。 */
    @GetMapping("/{name}/versions/{version}")
    public RuleSet version(@PathVariable String name, @PathVariable int version) {
        return ruleSetService.getVersion(name, version);
    }
}
