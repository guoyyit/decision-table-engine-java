# 决策表规则引擎（decision-table-engine-java）

以决策表（条件列 + 动作列 + 优先级）形式配置业务规则，由服务统一加载、预编译与执行。
支持版本化管理、灰度发布、秒级回滚、并发安全评估、热更新不中断、输入非法可解释拒绝、
越权变更拦截与审计追溯。相同输入在同一版本下的执行结果始终可复现。

---

## 1. 核心概念

| 概念 | 说明 |
| --- | --- |
| 规则集（RuleSet） | 一个命名的决策表集合，每次发布产生一个单调递增、不可变的版本。 |
| 决策表（DecisionTable） | 由若干**条件列**、**动作列**与**规则行**组成。 |
| 条件列（ConditionColumn） | 定义输入字段名、类型、比较算子、是否必填、数值范围/枚举允许集。 |
| 动作列（ActionColumn） | 定义命中后输出的动作字段（如 `discountRate`），可设默认值。 |
| 规则行（RuleRow） | 一行规则：`priority`（越大越优先）+ 条件单元格 + 动作单元格。单元格为 `null` 表示该列对本行不生效（通配）。 |
| 版本状态 | `ACTIVE`（全量）、`GRAY`（灰度中）、`INACTIVE`（历史，仅可回滚激活）。 |

评估结果必然来自某个**已提交的完整版本**：服务内存中同时只持有"当前全量版本"与"当前灰度版本"
两个原子引用，评估在一次调用内快照引用，规则集内部不存在字段级别的新旧混合。

## 2. 评估语义

对每张决策表按声明顺序执行：

1. **输入校验**：逐条件列检查输入字段
   - 必填字段缺失 → `MISSING_FIELD`
   - 类型不符（如数字列收到字符串）→ `TYPE_MISMATCH`
   - 数值超出 `[min, max]` → `OUT_OF_RANGE`
   - 枚举值不在允许集 → `ENUM_VIOLATION`

   校验错误可定位到**表 + 列 + 字段**（`ValidationError` 携带 `tableName/columnName/field`）。
2. **非法输入策略**（表级配置 `invalidInputPolicy`）：
   - `REJECT`：整表拒绝，最终状态 `REJECT`，不输出动作；
   - `DEGRADE`：输出表配置的默认动作（`degradedActions`），状态 `DEGRADED`。
3. **候选选取**：通过等值索引（见第 6 节）选出候选行，候选按
   **(priority 降序, 行号升序)** 排列。
4. **逐行匹配**：行内所有非空条件单元格全部成立则命中。支持的算子：

   | 算子 | 语义 |
   | --- | --- |
   | `EQ` / `NE` | 等于 / 不等于 |
   | `GT` / `GE` / `LT` / `LE` | 大于 / 大于等于 / 小于 / 小于等于（数值按 double 比较，其余按 Comparable） |
   | `IN` / `NOT_IN` | 属于 / 不属于单元格集合 |
   | `RANGE` | 单元格为 `[lo, hi]`，闭区间 |
   | `CONTAINS` | 字符串包含子串，或集合包含元素 |
   | `IS_NULL` / `NOT_NULL` | 输入字段为 null / 非 null（非必填列可用） |

5. **命中策略裁决**（见第 3 节）。
6. **多表汇总**：状态优先级
   `REJECT > CONFLICT > DUPLICATE_HIT > DEGRADE > HIT > NOT_HIT`。

## 3. 裁决规则（确定性说明）

| 状态 | 触发条件 | 动作输出 |
| --- | --- | --- |
| `HIT` | `FIRST_MATCH` 下唯一最高优先级命中；或 `ALL_MATCH` 下全部命中行对各动作列取值一致 | 命中行动作（缺失回退动作列默认值） |
| `DUPLICATE_HIT` | `FIRST_MATCH` 下**同一最高优先级**存在多个命中行（条件重叠） | 取行号更小的行（`TiePolicy.LOWER_ROW_INDEX_WINS`），`matchedRows` 列出全部同优先级命中行 |
| `CONFLICT` | `ALL_MATCH` 下多个命中行对**同一动作列**给出不同值 | 不输出动作，`conflicts` 列出表、动作列、参与行 id 与各自的值 |
| `NOT_HIT` | 无任何规则行命中 | 无动作 |
| `REJECT` | 输入非法且策略为 `REJECT` | 无动作，`errors` 给出可定位错误链 |
| `DEGRADE` | 输入非法且策略为 `DEGRADE` | 输出表配置的默认动作 |

**为什么是确定的**：候选排序键为 `(priority DESC, 行号 ASC)`，行号即规则行在表中的声明顺序；
所有结果列表均按该确定顺序输出，全程不遍历 HashMap/HashSet，不使用 `Object.hashCode()`，
不依赖任何运行时随机化。

## 4. 版本化、灰度与回滚

- **发布**：`POST /api/rulesets/publish`，版本号由服务端单调分配（调用方不可指定）。
  发布前执行完整结构校验与预编译；**校验/编译失败不切换任何引用**，上一个可用版本继续服务，
  审计记录 `FAILURE`。
- **原子切换**：发布流程为"先编译、后切换 `AtomicReference`"。评估在调用开始时快照引用，
  因此更新期间正在执行的评估不会读到半更新状态，且每次评估的结果自洽于同一个完整版本。
- **灰度发布**：发布时指定 `grayRatio`（1..99）与 `grayKey`（输入字段名，如 `requestId`）。
  分流使用 **FNV-1a 32 位稳定哈希**：`hash(key) % 100 < grayRatio` 走灰度版本。
  同一 key 在任何时刻、任何 JVM 下都路由到同一版本；key 缺失时走全量版本。
- **提升全量**：`POST /api/rulesets/{name}/promote`（ADMIN），灰度内容复制为新版本全量激活。
- **回滚**：`POST /api/rulesets/{name}/rollback?version=v`（ADMIN）。回滚 = 把历史版本的
  表内容**逐字段复制**为一个新版本并全量激活，因此回滚后行为与历史版本完全一致
  （版本号不同，但 `tables` 逐字段相等）；历史版本保持不可变、可追溯。

## 5. 幂等性与可复现性

- 评估是纯函数：`结果 = f(规则集版本, 输入)`，无副作用、无可变状态。
- 已通过测试验证：相同输入在 16 线程 × 50 次评估下结果完全一致；
  输入 Map 以不同插入顺序构造，结果完全一致；
  并发发布期间的每次评估，其动作与版本严格对应（无字段级混合）。

## 6. 性能：索引与耗时上界

- 发布时预编译：为所有 `EQ` 条件列建立倒排索引
  `列名 -> 单元格值 -> 命中行号列表（升序）`；无 `EQ` 条件的行进入 `unindexed` 列表始终扫描。
- 评估时选择**候选最少**的索引列定位候选行，再补充无索引行，最后在候选集合内做条件匹配。
- **单次评估耗时上界**：设规则行数为 R、每行条件列数为 C、决策表数为 T。
  - 最好/典型：等值索引命中，候选数 k ≪ R，耗时上界 `O(T·C + k·C)`；
  - 最坏：所有行在索引列上取值相同、或表中没有 `EQ` 条件列（退化为全表扫描），上界 `O(T·R·C)`。
- 实测（10,000 行、2 条件列）：索引路径平均约 **0.5ms**；全表扫描路径约 **13ms**（见测试输出）。
  评估耗时纳秒数记录在 `EvaluationResult.elapsedNanos` 中。

## 7. 权限与审计

- 角色：`VIEWER`（评估/查询）、`PUBLISHER`（发布）、`ADMIN`（回滚/提升/管理）。
- 所有变更接口通过请求头 `X-User` 识别用户（默认 `anonymous`）；角色不足时返回 `403`，
  审计记录 `DENIED`。
- 审计记录（`GET /api/audit`）包含：时间、用户、动作（`PUBLISH/ROLLBACK/PROMOTE`）、
  对象（`规则集#v版本`）、结果（`SUCCESS/DENIED/FAILURE`）、详情。

## 8. 配置项（application.properties）

```properties
# 服务端口（0 表示随机端口）
server.port=0

# 管理员 / 发布者用户名单（逗号分隔），其余用户默认为 VIEWER
engine.auth.admins=admin
engine.auth.publishers=publisher,system
```

## 9. HTTP 接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/rulesets/publish` | PUBLISHER | 发布新版本（body 为 PublishRequest） |
| POST | `/api/rulesets/{name}/rollback?version=n` | ADMIN | 回滚到历史版本 |
| POST | `/api/rulesets/{name}/promote` | ADMIN | 灰度版本提升为全量 |
| GET | `/api/rulesets` | 任意 | 规则集名称列表 |
| GET | `/api/rulesets/{name}/versions` | 任意 | 版本列表 |
| GET | `/api/rulesets/{name}/versions/{version}` | 任意 | 指定版本规则集定义 |
| POST | `/api/evaluate/{name}?routingKey=` | 任意 | 评估（body 为输入字段 Map） |
| GET | `/api/audit` | 任意 | 审计记录 |

统一错误响应：`403`（越权）、`400`（校验失败/规则集不存在），body 含 `timestamp/status/error/message`。

## 10. 测试覆盖

`mvn test` 运行全部 25 个用例，覆盖：

- 版本切换（v1 → v2 评估结果随之切换，历史版本内容不变）
- 回滚（回滚后动作与历史版本逐字段一致）
- 并发评估（多线程结果一致；并发发布期间无半更新/字段级混合）
- 热更新失败（非法规则集被拒，旧版本继续服务，审计 FAILURE）
- 条件冲突（ALL_MATCH 动作不一致 → CONFLICT，含行 id 与值）
- 异常输入（缺失/类型不符/越界 → REJECT 可定位；DEGRADE 策略降级输出）
- 重复命中（同优先级重叠 → DUPLICATE_HIT 且行号小者胜，结果稳定）
- 无命中（NOT_HIT 可区分）
- 灰度（同 key 恒路由同版本；分流同时观察到两版本；promote 后全量新版本）
- 权限（VIEWER 发布/回滚 → 403 + DENIED 审计）
- 审计（PUBLISH/ROLLBACK 均有 SUCCESS 记录）
- 性能（万行索引路径平均 < 5ms，全表扫描 < 200ms）
- 确定性（输入 Map 构造顺序不影响结果）
- Web 层（MockMvc：403/400/200 及错误体）
