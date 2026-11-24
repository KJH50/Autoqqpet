# 代码审核报告（2026-11-24）

> 范围：仓库 `main` @ `9abd397` 之上的本地改动 + 决策/调度相关模块的通读。
> 方法：`git diff` 逐行核对 → 读 `optimizer/`、`pet/`（记账/照顾/冒险节奏）、`ui/data` 关键路径 →
> `testDebugUnitTest`（409 用例）+ `lintDebug`（0 error）双跑验证。
> 结论：**未发现会导致资金/进度算错的新问题**；修掉 4 处（1 处功能缺陷 + 3 处 lint 错误），
> 另有 7 条观察项记录在案。P2 清单核对见第 3 节。

---

## 1. 本次修掉的问题

| # | 严重度 | 位置 | 问题 | 处理 |
| --- | --- | --- | --- | --- |
| R1 | **中（功能缺陷）** | [AutoRunner.kt](../app/src/main/java/io/github/juwanhwang/autoqqpet/optimizer/AutoRunner.kt) `solverDecision` / `solverInput` | **最优调度（默认模式）完全忽略「每天最多冒险几次」设置**。金币优先的「冒险先行」分支只判金币到顶（`!snap.advGoldCapped`）；求解器输入也不看 `adventureLeft`，求解器本身只认金币池。结果：设置 3 次也能一直派冒险，直到金币到顶或次数在别处被拦。 | 冒险先行分支加 `snap.adventureLeft > 0`；`solverInput` 在 `adventureLeft <= 0` 时不再给冒险候选（执行层每轮只走一步、派出即递减，所以清零即硬停）。 |
| R2 | 低（lint error） | [LiveState.kt](../app/src/main/java/io/github/juwanhwang/autoqqpet/ui/data/LiveState.kt) `start()` | 运行期广播注册按 SDK 版本手写两条路径，Android 14（targetSdk 34）要求非系统广播显式声明导出状态；lint 报 `UnspecifiedRegisterReceiverFlag`。写法本身在 33+ 是对的，但 33 以下走另一条路、容易漏改。 | 统一改 `ContextCompat.registerReceiver(...)`：状态广播 `RECEIVER_EXPORTED`（发送方是 QQ 进程）、内部刷新 `RECEIVER_NOT_EXPORTED`，行为等价、各版本一条路径。 |
| R3 | 低（lint error） | [App.kt](../app/src/main/java/io/github/juwanhwang/autoqqpet/ui/App.kt) `SystemBarsEffect` | `LocalContext.current as? ComponentActivity`：Context 不保证是 Activity（预览、多窗口、嵌入场景会拿到非 Activity），lint 判 error。 | 改用 `LocalActivity.current` 再判型；拿不到就直接 return（与原逻辑同）。 |
| R4 | 低（lint error） | App.kt `AevumApp` | 源码公开版把开屏协议页注释掉后，`AnimatedContent` 的 `targetState`（`agreed`）不再被 lambda 使用，lint 报 `UnusedContentLambdaTargetStateParameter`。 | 加 `@SuppressLint(...)` 并在注释里写明「原拦截逻辑以注释保留，恢复时取消注释即可」，避免后人当成死代码删掉。 |

验证：`testDebugUnitTest` 66 个测试类 / 409 用例 / 0 失败；`lintDebug` 从 **4 error** 降为 **0 error**（余 163 warning、1 hint，见 §2.7）。

## 2. 观察项（未改，供决策）

1. **雇佣价乐观估计（中）**：`solverInput.hirePriced` 在「自动雇人开着且次数没到上限」时按「目录本金 + 加成上限」给工定价，
   但运行时好友可能不可雇（`hirePlan` 会退化为自做）。→ 求解器可能高估打工、把学习挤掉。缓解：`docs/issue-algo-review.md`
   P1 的「雇佣加成上限学习」把实际结算回落进 `daily.bonusCap`；更彻底的做法是求解后若雇不到，按自做价重解一次。
2. **学习优先不降级选课（低）**：`DayPlanner.decide` 的学习池只收目标属性（及随机属性）的课；目标属性全被降效/学费不够时
   不会改上别的属性课，直接转打工或冒险。是否符合「学习优先」的产品预期，需要确认（若确认要降级，进池规则改一行）。
3. **旧逻辑跨线折算单档（低）**：`DayPlanner.crossFactor` 过 480 分钟后统一按 0.25 折算，没有 720 分钟后的 0.10 档；
   600 分钟以内与官方三档等价，更长任务会略微高估（`docs/issue-algo-review.md` P1 已记录）。
4. **学习均值淘汰策略（低）**：`DailyStore.learnedText` 用 `takeLast(64)` 按**插入序**截断；更新不改变位置，
   长尾旧键会被新键挤掉。对「最近常做的档位」够用，但历史样本会丢；若要做长期均值需要换带时间衰减的存储。
5. **到顶回滚边界（低）**：`rollbackAdventureZeros` 当 `zeros ≥ 样本数` 且总额不为 0 时会保留 `count=1 + 原总额`，
   均值被抬高。触发需要连段（30）大于样本数，实际概率很低；建议加一条断言/夹取。
6. **编排层不可单测（工程债）**：`AutoRunner` 的决策编排（`solverInput`、GOLD 先行、照顾门槛）都吃 Android 依赖，
   单测覆盖不到；R1 这类"设置被绕过"的问题只能靠读代码发现。建议把 `solverInput` 的纯数据组装提成 `object`/顶层函数放进
   `optimizer/`，用快照 + 假 `DailyStore` 直接断言。
7. **Lint 现状（163 warning）**：`UseKtx` 125 条是风格（`SharedPreferences.edit()`、`Bundle` 等），
   `PrivateApi` 4 条（`ActivityThread` 反射）与 `MissingPermission` 2 条（精确闹钟）是本项目的模块机制固有，
   `ExportedReceiver` 3 条是 QQ 侧广播的既定形态——都不建议机械修，改了反而增加升级 QQ 后的适配面。

## 3. P2 清单核对（「有没有项目」）

`docs/issue-algo-review.md` 的 P2 目前 **3 项**，逐条核对代码确认**都还没实现**，且都缺前置数据/协议：

| P2 项 | 核对证据 | 还缺什么 |
| --- | --- | --- |
| 群友互动（踩踩/PK，#20） | `net/Commands.kt` 里没有 `0x9b47`/`0x9b48`，全仓搜不到群列表/群成员相关命令 | 两个命令的请求体/回包结构，以及"踩群友"实际走哪条写命令 |
| 主动踩陌生人（#29） | `pet/SparkPolicy.kt` 只有「回踩今天踩过我的人」（`visitors`，默认关），没有"主动发现陌生人"的入口 | 「附近/推荐的人」列表命令（当前只有好友名册 `0x985d` 与访客） |
| 风控自适应节奏（#28/#30/#31） | `optimizer/PauseGate.kt` 只有固定夜间窗口 + 息屏暂停，`Store` 里是静态开关，没有任何基于失败/踢线的自适应退避 | 可识别的风控信号（踢下线/验证/`Ban state`）作为事件源 |

**结论：P2 没有可以直接开工的项**（三项都卡在抓包/协议验证上，本机没有装 QQ 的环境），维持文档现状即可。
若后续拿到真机抓包，按 P2 → 先做「群列表 + 群成员分页」只读解析，再做写操作（踩/PK），且默认关 + 每日限额。

## 4. 复现与验证命令

```bat
:: 全量单测（409 用例）
gradlew.bat --no-daemon testDebugUnitTest

:: 静态检查（要求 0 error 才通过）
gradlew.bat --no-daemon lintDebug

:: 打包
gradlew.bat --no-daemon assembleDebug
```

报告产物：`app/build/reports/tests/testDebugUnitTest/index.html`、`app/build/reports/lint-results-debug.html`。
