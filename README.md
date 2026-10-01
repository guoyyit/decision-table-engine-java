# decision-table-engine-java

基于 Spring Boot 3 / Java 17 的决策表规则评估与执行服务：业务规则以表格配置、
统一加载执行，支持版本化、灰度发布、回滚、热更新与并发评估。

完整语义见 [docs/DECISION_TABLE_ENGINE.md](docs/DECISION_TABLE_ENGINE.md)。

## 构建与测试

```bash
mvn test          # 49 个自动化测试
mvn spring-boot:run
```

## 核心能力

- **版本化与回滚**：每次提交生成不可变完整版本；评估只来自某一个完整版本，
  杜绝字段级混合；回滚只是把生效指针指回历史不可变版本，行为与当时一致。
- **灰度发布**：稳定 SHA-256 哈希（百分比 + 白名单 + 强制灰度），同一路由永远落同一版本。
- **决策表语义**：条件列（EQ/NE/IN/NOT_IN/GT/GTE/LT/LTE/RANGE/PRESENT/通配）、
  动作列、优先级、命中策略（FIRST/UNIQUE/PRIORITY/COLLECT）与同优先级裁决
  （CONFLICT/LOWEST_ID/MERGE）；无命中、重复命中、条件冲突是可区分的输出状态。
- **幂等可复现**：评估器无状态、快照不可变、排序全部有确定主键，
  不受 HashMap 遍历顺序与 JDK 随机哈希影响。
- **热更新与并发**：评估读不可变快照（无锁），写路径 CAS 整体替换；
  更新/校验失败时上一个可用版本继续服务，在途评估读不到半更新状态。
- **性能**：EQ/IN 条件列预编译哈希索引，点查候选可缩减到常数行；
  上界 `O(n·c)`，索引命中 `O(c + k·c)`；结果暴露 `totalRows/evaluatedRows/elapsedNanos`。
- **异常可解释**：缺失/类型/范围/枚举/未知字段一律显式 REJECT 或 DEGRADE，
  评估异常可定位到表、行、条件列。
- **权限与审计**：写接口需 `ROLE_RULE_ADMIN`；越权尝试返回 403 且留痕。

## 快速示例

提交版本（需要管理员头）：

```bash
curl -s -X POST localhost:8080/api/admin/versions \
  -H 'X-User: alice' -H 'X-Roles: ROLE_RULE_ADMIN' -H 'Content-Type: application/json' \
  -d '{
    "message":"init",
    "tables":[{
      "tableId":"pricing","name":"pricing",
      "hitPolicy":"UNIQUE","tiePolicy":"CONFLICT",
      "inputFields":{"region":{"name":"region","type":"STRING","required":true,"allowed":["CN","US"]}},
      "actionColumns":[{"field":"offer","type":"STRING"}],
      "rows":[
        {"rowId":"r1","priority":0,
         "conditions":[{"field":"region","operator":"EQ","value":"CN","values":[],
                        "lower":null,"upper":null,"lowerIncl":false,"upperIncl":false,"wildcard":false}],
         "actions":{"offer":"CN_OFFER"}},
        {"rowId":"r2","priority":0,
         "conditions":[{"field":"region","operator":"EQ","value":"US","values":[],
                        "lower":null,"upper":null,"lowerIncl":false,"upperIncl":false,"wildcard":false}],
         "actions":{"offer":"US_OFFER"}}
      ]
    }]
  }'
```

评估：

```bash
curl -s -X POST localhost:8080/api/decision/tables/pricing/evaluate \
  -H 'Content-Type: application/json' -d '{"region":"CN"}'
# status=HIT, outputs.offer=CN_OFFER, version=1
```

灰度（100% 到版本 2，之后用 percent=0 关闭）：

```bash
curl -s -X POST localhost:8080/api/admin/gray \
  -H 'X-User: alice' -H 'X-Roles: ROLE_RULE_ADMIN' -H 'Content-Type: application/json' \
  -d '{"grayVersion":2,"percent":100,"allowList":[],"stableKeyFields":[]}'
```

回滚：

```bash
curl -s -X POST localhost:8080/api/admin/versions/1/rollback \
  -H 'X-User: alice' -H 'X-Roles: ROLE_RULE_ADMIN'
```

## 关键包结构

```
model/   规则与版本的不可变领域模型（record/enum）
eval/    条件判定、输入校验、命中策略裁决、结果与异常
engine/  预编译索引、不可变快照、版本仓库（CAS）、灰度路由、静态校验
service/ 决策服务门面（评估/提交/激活/回滚/灰度）、权限、审计、输入策略
web/     REST 接口、DTO、全局异常映射
```
