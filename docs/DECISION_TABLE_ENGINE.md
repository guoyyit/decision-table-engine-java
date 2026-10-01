# 决策表规则引擎 — 评估语义、裁决规则与配置说明

本服务以表格形式配置业务规则，统一加载、编译与执行。本文档说明版本模型、
评估语义、命中/裁决规则、并发与热更新语义、性能上界、异常处理策略以及接口配置项。

## 1. 总体模型

```
RuleSetVersion (不可变完整版本)
 └── DecisionTable (tableId 唯一)
      ├── inputFields  : Map<字段名, FieldSchema>   输入模式（类型/必填/范围/枚举）
      ├── actionColumns: List<ActionColumn>        动作列（输出字段+类型）
      └── rows         : List<RuleRow>             规则行（rowId 唯一、priority、conditions、actions）
```

- **字段类型 DataType**：`STRING` / `NUMBER` / `BOOLEAN`。
- **条件操作符 Operator**：`EQ`、`NE`、`IN`、`NOT_IN`、`GT`、`GTE`、`LT`、`LTE`、`RANGE`、`PRESENT`；
  条件为 `null` 或 `wildcard=true`（通配符 `-`）表示该列不限制（任意值含缺失都满足）。
- **命中策略 HitPolicy**：`FIRST` / `UNIQUE` / `PRIORITY` / `COLLECT`。
- **同优先级裁决 TiePolicy**：`CONFLICT` / `LOWEST_ID` / `MERGE`。

## 2. 版本化、灰度发布与回滚语义

1. 每次 `commit` 产生一个**不可变**的 `RuleSetVersion`（版本号单调递增）。
   提交前执行整体静态校验，**任何**问题都会拒绝整次提交，不会产生半成品版本。
2. 评估结果必定来自**某个已提交的完整版本**。评估线程在评估开始时获取一个
   不可变 `EngineSnapshot` 引用，评估全程使用该快照；不存在跨版本的字段级混合。
3. 首次提交自动成为 `ACTIVE`；后续提交为已提交状态，需 `activate` 全量发布，
   或通过 `configureGray` 指向灰度版本。
4. **灰度路由是稳定哈希**：
   - `X-Force-Gray=true`：强制灰度（灰度版本必须存在）；
   - `X-Stable-Key` 命中白名单 `allowList`：走灰度；
   - 否则取稳定键 SHA-256 前 8 字节对 100 取模，`bucket < grayPercent` 走灰度；
   - 稳定键：配置了 `stableKeyFields` 时按该顺序拼接，否则输入按字段名
     **字典序**拼接。**不使用** `Object.hashCode()`（JVM 加盐随机化）、随机数或时间戳，
     因此同一份输入在任何时刻路由结果都一致。
5. **回滚**只移动 `ACTIVE` 指针，指向历史的不可变版本对象，并清除灰度配置。
   历史版本的内容与编译结果从未被修改，所以回滚后的行为与该版本当时完全一致
   （回滚不会“重放”之后提交的任何变更）。

## 3. 评估语义与裁决规则（确定性）

评估顺序：输入校验 → 索引筛选候选行 → 逐列完整判定 → 按命中策略裁决。

### 3.1 条件三值语义

- 通配：任意取值（含缺失）满足。
- `PRESENT`：值非 null 才满足。
- 字段缺失（null）且非通配/PRESENT：**一律不满足**。特别地，`NE`/`NOT_IN`
  对缺失值也不满足——“缺失”不等于“不等于”，避免静默放行。
- 数值比较不区分 `Integer/Long/Double/BigDecimal`（反序列化差异），统一按 double 比较。
- 字符串与数值等不可比类型相遇：抛出可定位到 `tableId/rowId/columnIndex` 的
  `EvaluationException`，不做隐式转换。
- `RANGE [lower, upper]`：边界可分别配置是否包含；`lower/upper` 可缺省表示开区间。

### 3.2 命中策略裁决

| 策略 | 匹配数与情形 | 状态 | 输出 |
|------|--------------|------|------|
| FIRST | 多条匹配 | HIT | 定义顺序的第一条；其余忽略，不产生冲突 |
| UNIQUE | 0 条 | NO_HIT | 空 |
| UNIQUE | 1 条 | HIT | 该行动作 |
| UNIQUE | ≥2 条 | CONFLICT | 空输出，matchedRowIds 列出全部 |
| PRIORITY | 最高优先级唯一 | HIT | 该行动作 |
| PRIORITY | 并列且动作完全一致 | DUPLICATE_HIT | 该动作（重复命中） |
| PRIORITY | 并列且动作不一致，tie=CONFLICT | CONFLICT | 空输出 |
| PRIORITY | 并列且动作不一致，tie=LOWEST_ID | HIT | rowId 字典序最小者，reason 注明胜出者 |
| PRIORITY | 并列且动作不一致，tie=MERGE | DUPLICATE_HIT | 逐列合并，不一致的动作列置 null |
| COLLECT | 0 条 | NO_HIT | 空 |
| COLLECT | 1 条 | HIT | 该行动作 |
| COLLECT | 多条且动作一致 | DUPLICATE_HIT | 该动作 |
| COLLECT | 多条且动作不一致 | HIT | 每个动作字段输出按 rowId 排序的 `List` |

- 优先级 `priority`：数值越小优先级越高；并列时以 `rowId` 字典序作为最终决胜键，
  保证“条件重叠且优先级相同”时裁决结果**确定且可复现**。
- 六种输出状态 `EvalStatus` 可明确区分：
  `HIT`、`NO_HIT`（无命中）、`DUPLICATE_HIT`（重复命中）、`CONFLICT`（条件冲突）、
  `REJECTED`（输入被拒绝）、`DEGRADED`（输入降级）。
- `matchedRowIds` 与 `trace` 均按确定顺序给出；trace 中未命中行记录第一个
  不满足的条件列序号 `failedColumn`，可直接定位。

### 3.3 可复现性保证

- 规则版本、编译产物、快照全部不可变；
- 评估器无状态；不使用随机数、当前时间参与判定、不加锁改变顺序；
- 所有需要排序的地方（错误列表、同优先级行、COLLECT 输出、Map 输出）都使用
  TreeMap/显式排序（按字段名或 rowId），**不受 HashMap 遍历顺序影响**；
- 相同输入 + 相同版本在任何并发度、任何执行顺序下输出一致
  （有并发测试覆盖，版本字段级混合计数必须为 0）。

## 4. 并发与热更新

- 评估路径**无锁**：只读当前 `EngineSnapshot`（`AtomicReference`）及其不可变内容。
- 写路径（commit/activate/rollback/gray）在服务内串行化，并通过 CAS 提交新快照；
  CAS 失败则读取最新快照重试，绝不覆盖并发提交。
- 更新期间正在执行的评估持有旧快照引用，因此**读不到半更新状态**；
  热更新失败、校验不通过、权限不足时，当前快照不发生任何变化，继续提供上一个可用版本。
- 编译缓存按 `(version, tableId)` 保存；回滚到旧版本无需重新编译。

## 5. 性能：索引与耗时上界

- 提交/激活时按版本编译决策表：对每个条件列，若**所有行**在该列都是
  `EQ`/`IN`（无通配、无其它操作符、无越位行），则建立
  `字面值 -> 候选行下标集合` 的哈希索引。
- 单次评估：
  1. 输入校验 `O(f)`（f 为声明字段数）；
  2. 在输入有值的索引列上取候选集，选择候选最少的一列；某索引列查无此值则立即空结果；
  3. 对候选行逐列完整判定。
- **复杂度上界**：
  - 索引命中：`O(c + k·c)`，`k` 为最小候选集大小、`c` 为条件列数；
    点查场景 `k=1`，与规则总数 `n` 无关。
  - 最坏退化（全部列不可索引，如存在通配）：`O(n·c)` 全表顺序扫描。
  - 一次性编译成本：`O(n·c)`，仅在版本首次被使用时发生。
- 结果中暴露 `totalRows` 与 `evaluatedRows`：当 `evaluatedRows < totalRows`
  即说明索引生效；`elapsedNanos` 给出本次实测耗时。
- 工程参考：测试环境下 10,000 行、单列 EQ 索引的点查稳定在 **2ms 以内**
  （`CompiledTableIndexTest`，含完整校验、判定与 trace）。具体上界随 CPU、
  条件列数与动作列数变化，建议按 `O(n·c)` 做容量规划，并依赖索引把热点列做成 EQ/IN。

## 6. 输入异常处理策略

评估前按 `FieldSchema` 严格校验，任何问题都不静默放行，错误按字段名排序返回：

| 情形 | reason | 默认策略结果 |
|------|--------|--------------|
| 必填字段缺失 | MISSING | REJECTED |
| 类型不匹配（STRING/NUMBER/BOOLEAN 严格） | TYPE_MISMATCH | REJECTED |
| NUMBER 超出 [min,max] | OUT_OF_RANGE | REJECTED |
| STRING 不在 allowed | ENUM_VIOLATION | REJECTED |
| 出现未声明字段 | UNKNOWN_FIELD | REJECTED |
| 评估期条件类型不可比/范围比较遇非数值 | EvaluationException | HTTP 500，body 含 tableId/rowId/columnIndex |

- `POST /api/decision/tables/{tableId}/evaluate?policy=REJECT|DEGRADE`
  - `REJECT`（默认）：返回 `status=REJECTED`（HTTP 200，业务状态可区分），
    `errors` 含字段级原因，不执行任何规则。
  - `DEGRADE`：返回 `status=DEGRADED` 与默认输出（当前为空 Map，可在调用侧扩展为表级默认值）。
- 规则静态问题（重复 ID、未知字段、缺参数、字面量类型不符、动作集合不一致等）
  在提交时一次性返回（HTTP 422，details 为全部问题）。

## 7. 权限与审计

- 所有写接口需要请求头：
  - `X-User`：操作人；
  - `X-Roles`：逗号分隔角色，必须包含 `ROLE_RULE_ADMIN`；否则 HTTP 403。
- 评估与查询不要求管理员角色（`ROLE_RULE_VIEWER` 即可，由网关/后续安全层细化）。
- **所有变更尝试都写审计**，包括被拒绝的越权尝试（`allowed=false`）：
  字段含时间、操作人、角色、动作（COMMIT/ACTIVATE/ROLLBACK/CONFIGURE_GRAY）、
  详情、是否允许、关联版本。审计随快照不可变追加（内存保留最近 1000 条）。

## 8. HTTP 接口与配置项

| 方法 路径 | 说明 |
|-----------|------|
| `POST /api/decision/tables/{tableId}/evaluate?policy=REJECT` | 评估；可选头 `X-Stable-Key`、`X-Force-Gray` |
| `POST /api/admin/versions` | 提交版本（body: `{message, tables[]}`） |
| `POST /api/admin/versions/{v}/activate` | 全量激活 |
| `POST /api/admin/versions/{v}/rollback` | 回滚（移动 active 指针并清灰度） |
| `POST /api/admin/gray` | 灰度配置 `{grayVersion, percent 0-100, allowList[], stableKeyFields[]}`；percent=0 或 grayVersion=null 关闭 |
| `GET /api/admin/versions` | 当前 active、灰度配置、版本清单 |
| `GET /api/admin/audit` | 审计事件列表 |

配置项（`application.properties`）：当前仅 `server.port=0`（随机端口，测试友好）。
规则存储为**内存实现**：版本/快照不可变，进程重启后规则不保留；
生产持久化可在 `RuleVersionStore` 后接数据库（提交写库 + 启动加载不可变快照），
评估与热更新语义不变。

## 9. 测试覆盖

共 49 个自动化测试（`mvn test`）：
- `ConditionMatcherTest`：操作符、缺失语义、数值跨类型、异常行列定位；
- `InputValidatorTest`：缺失/类型/范围/枚举/未知字段与确定性错误顺序；
- `RuleValidatorTest`：重复 ID、缺参数、类型不符、RANGE、动作集合不一致；
- `TableEvaluatorTest`：四种命中策略、NO_HIT/DUPLICATE_HIT/CONFLICT、
  拒绝与降级、trace 定位、同输入反复可复现；
- `CompiledTableIndexTest`：索引缩减候选、通配退化为全表扫描、耗时上界、空候选；
- `GrayRouterTest`：稳定哈希可复现、白名单、强制灰度、0%/100%、键字段隔离；
- `DecisionServiceTest`：版本切换、回滚行为一致、提交失败保持旧版本、
  越权拒绝+留痕、并发评估与发布无字段混合、在途评估一致性；
- `DecisionControllerIntegrationTest`：端到端 HTTP，覆盖上述全部链路与状态码。
