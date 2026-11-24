# Issue 里的算法意见：现状核对、本轮修复与新功能整理

> 来源：公开仓 <https://github.com/Juwan-Hwang/Autoqqpet/issues>（#1–#33，2026-10-12 起）。
> 核对基线：仓库 `main` @ `9abd397`（2.2.1 源码公开版）。
> 目的：把**用户提出的、与决策/调度算法有关的意见**逐条对到代码上，说明哪些已经实现、
> 哪些是本轮能修的算法错误、哪些属于**新功能**（需要抓包或真机验证，不在本轮改）。

---

## 1. 已有意见的现状核对

| Issue | 用户提出的算法意见 | 现状 | 代码位置（2.2.1） |
| --- | --- | --- | --- |
| [#3](https://github.com/Juwan-Hwang/Autoqqpet/issues/3) [#4](https://github.com/Juwan-Hwang/Autoqqpet/issues/4) | 选了「学习优先」却整天冒险：45 秒短冒险被基线折成 29.3/h，压过真实的 11.4/h 课程 | **已修**：学习/打工优先挡下冒险不参与 rate 比价，学工被降效剔空才去冒险 | `optimizer/DayPlanner.kt` `workStudy` 过滤（第 262 行起） |
| [#22](https://github.com/Juwan-Hwang/Autoqqpet/issues/22) [#12](https://github.com/Juwan-Hwang/Autoqqpet/issues/12) | 冒险出现「天色不对」必零金币，应当场召回重开，别白跑 | **已修**：出发后 6.5s 读进度，命中即召回；读失败按 8s 重试，最多 4 次 | `optimizer/WeatherCheckPlan.kt`、`AutoRunner.weatherRecallClose()` |
| [#16](https://github.com/Juwan-Hwang/Autoqqpet/issues/16) 后续 | 阴天/晴天也没召回 | **部分口径不同**：只认进度条目里的「天色不对」标记；「晴天/阴天」是结算结局名，不代表坏天气，不能据此召回 | `pet/Models.kt` `WEATHER_MARKERS` |
| [#18](https://github.com/Juwan-Hwang/Autoqqpet/issues/18) | 开了隐藏职业应有 8 个打工地点，界面只检测到 4 个 | **已修**：全量刷新时把决策没读的已解锁职业一并读进缓存 | `optimizer/CatalogReader.readJobs()` 目录补全段 |
| [#15](https://github.com/Juwan-Hwang/Autoqqpet/issues/15) | 喂食被服务端拒绝后整天不再照顾，体力耗穿 | **已修**：`Ban state forbit write` 只进冷却（15 分钟起，翻倍封顶 6h），到期自动重探；语义性连拒才当天跳过 | `pet/CareManager.kt`（`CareBan`） |
| [#11](https://github.com/Juwan-Hwang/Autoqqpet/issues/11) [#28](https://github.com/Juwan-Hwang/Autoqqpet/issues/28) [#30](https://github.com/Juwan-Hwang/Autoqqpet/issues/30) | 夜间/息屏少发包，降低风控 | **已实现**：暂停闸（夜间时段、息屏）任一命中就全停 | `optimizer/PauseGate.kt`、`store/Store.kt` |
| [#1](https://github.com/Juwan-Hwang/Autoqqpet/issues/1) [#25](https://github.com/Juwan-Hwang/Autoqqpet/issues/25) | PK 黑名单；PK 输了也可不拉黑 | **已实现**：互动名单黑/白模式 + 「PK 输了拉黑对方」开关 | `pet/FriendPolicy.kt`、`store/SettingKeys.kt` `PK_BLACKLIST_ON_LOSS` |
| [#17](https://github.com/Juwan-Hwang/Autoqqpet/issues/17) | 属性增加「均衡」 | **已实现**：均衡学习按轮转指针选课 | `optimizer/StudyRotation.kt` |
| [#23](https://github.com/Juwan-Hwang/Autoqqpet/issues/23) | 喂食改成「不足时马上喂」 | **已实现**：每次决策先判体力/清洁地板，低于阈值立刻喂/洗 | `DayPlanner.decide()` 第 1 步 |
| [#27](https://github.com/Juwan-Hwang/Autoqqpet/issues/27) | 点通知打开模块而不是 QQ | **已实现**：设置项 `notify_open = app/qq` | `keepalive/KeepAliveService.tapLaunchIntent()` |

结论：**已关闭的 issue 里，与决策算法直接相关的部分在 2.2.1 都已经落到代码上**；本轮不再重做，
只修了对不上的两处（见第 2 节）。仍然开着的 issue（#20 群友、#29 踩陌生人、#28 风控）属于
新功能/平台策略，见第 3 节。

---

## 2. 本轮修掉的算法错误

### 2.1 rate 的单位不一致（长短任务被系统性地算歪）

`DayPlanner` 把学习/打工/冒险都折算成「目标属性等效/小时」再横比，但补给成本是**每趟**的绝对量
（体力×0.1 + 清洁×0.04）。旧式直接把「每趟成本」从「每小时收益」里减掉，量纲对不上：

```kotlin
// 旧：rate(每小时) -= cost(每趟)
c.attrGain * factor * 60.0 / c.durationMin - costOf(c.staminaCost, c.cleanCost)
```

后果：600 分钟的长课被罚了 10 倍，30 分钟的短课只罚一半，**同样的时间收益密度会因为时长不同算出不同
rate**，系统性地把短任务（短工、45 秒冒险）排到长课前面——这正是 #4 里「一天 463 次冒险」的另一半成因。

修法：先算**每趟净收益**，再折算到小时：

```kotlin
(c.attrGain * factor - costOf(c.staminaCost, c.cleanCost)) * 60.0 / c.durationMin
```

`courseRate` / `jobRate` / `adventureRate` 三处统一。单测 `RateUnitsTest`：
- 30 分钟 +6、600 分钟 +120（成本等比）→ 两者 rate 必须相等（9.68/h）；
- 10 分钟 10 点与 60 分钟 60 点 → 相等（5.04/h）；
- 45 秒冒险扣整套补给后必须为负，不能被当成 40/h 的高价值。

### 2.2 求解结果回查成本时认错条目（冒险 sub 恒为 0）

`solverDecision()` 判「这一步要不要先喂/先洗」时按 `subEventType` 找条目：

```kotlin
snap.adventures.firstOrNull { it.subEventType == first.sub }
```

真机冒险的 `subEventType` **恒为 0**，遇到两条以上冒险时永远命中第一条，拿别人的体力/清洁消耗
去判「该不该先照顾」。改为 **sub 优先、名字兜底**（与 `bySubOrName` 同口径）：

```kotlin
(if (sub > 0) list.firstOrNull { subOf(it) == sub } else null) ?: list.firstOrNull { nameOf(it) == name }
```

学习/打工同样受益（同名不同档的真实数据里，名字兜底比错配 sub 可靠）。

> 两处改动均通过 `testDebugUnitTest` 全量单测（基线 30 个测试类 + 新增 `RateUnitsTest`）。

---

## 3. 新增算法功能意见（未实现，按优先级）

这些是从 issue 与 `docs/study-work-optimizer.md`「现实修正」里整理出来、**2.2.1 还没有**的算法向建议。
按「用户价值 / 实现风险」排序；协议类改动必须先抓包验证。

### P0：能直接改善收益与稳定性的

| 建议 | 来源 | 算法要点 | 风险 |
| --- | --- | --- | --- |
| **职业升级预留金币** | 设计文档 §5.5、issue #22 的「后期升级耗金大」 | 求解器 `floorGold` 加 `careerReserve`：从职业升级目录读下一级价，低于它就不排长工/不交昂贵学费 | 目录里升级价要新读一条；读不到时退回 `goldReserve` |
| **天气窗口降权** | [#22](https://github.com/Juwan-Hwang/Autoqqpet/issues/22) 的延伸 | 不只「出发后召回」：把当天已出现「天色不对」的时段做成滑动窗口，窗口内降低冒险 EV，优先学工 | 需要按小时聚合天气命中样本，冷启动无数据 |
| **PK 择敌 margin 自适应** | [#16](https://github.com/Juwan-Hwang/Autoqqpet/issues/16)、[#1](https://github.com/Juwan-Hwang/Autoqqpet/issues/1) | 现在固定 83% 战力阈值；按最近 N 场胜负率回调（连胜提高阈值、连败降低），减少白送金币 | 胜负要靠结算称号判，样本少时抖动，需要下限保护 |
| **冒险金币池按时段建模** | [#11](https://github.com/Juwan-Hwang/Autoqqpet/issues/11)/[#22](https://github.com/Juwan-Hwang/Autoqqpet/issues/22) 的全天日志 | 现在池深是单值（到顶观测/先验 5375）；改成按小时衰减的剩余池，早段高、晚段低，避免下午空刷 | 需要按小时记账，历史样本迁移 |

### P1：口径补全（不改协议）

| 建议 | 来源 | 算法要点 | 风险 |
| --- | --- | --- | --- |
| **护理勋章「只读不代练」** | 设计文档 §5.6 | 新增设置：只读取体力/清洁，不主动喂/洗（避免「一键照顾」影响勋章判定） | 纯 UI + 决策开关，低风险 |
| **均衡学习的缺口加权** | [#17](https://github.com/Juwan-Hwang/Autoqqpet/issues/17) 的延伸 | 现在按属性轮转；改成按「三维相对短板」动态加权，优先补最弱项 | 需要读取三维当前值，已有数据 |
| **雇佣加成上限学习** | 求解器 `bonusCap` | 目录 `f12` 只给区间末尾；把实际结算的加成回落进 `daily.bonusCap`，定价更准 | 已有 `hc/h` 键，补一条均值即可 |
| **同价档位「完成更多课程」次级目标** | 设计文档 §1 目标② | 求解器排名在目标值之后加「单位时间完成数」作次级排序，等价时偏好短课以多拿课程数奖励 | 影响面小，需确认课程数是否影响奖励 |
| **旧逻辑跨线折算对齐三档** | 设计文档 §1 的疲劳曲线 | `DayPlanner.crossFactor` 跨 480 分钟后统一按 0.25 折算，没有 720 分钟后的 0.10 档；600 分钟以内与官方三档等价，更长的档会略微高估。要不要改取决于是否愿意让旧逻辑与 `OptimalPlanner.tierFactor` 完全同口径 | 低：只影响 12 小时以上的长课/长工，改动会让旧逻辑更保守 |

### P2：需要抓包/真机验证的新功能（不在本轮改）

| 建议 | 来源 | 需要什么 | 风险 |
| --- | --- | --- | --- |
| **群友互动（踩踩/PK）** | [#20](https://github.com/Juwan-Hwang/Autoqqpet/issues/20)：`0x9b47_0` 群列表、`0x9b48_0` 群成员分页 | 先抓包确认这两个命令的请求体与回包结构、以及「踩群友」走哪条写命令 | 高：群成员属陌生人，风控敏感，必须限量 + 默认关 |
| **主动踩陌生人** | [#29](https://github.com/Juwan-Hwang/Autoqqpet/issues/29) | 需要「附近/推荐的人」列表命令（当前只有好友名册与访客） | 高：同上，且无稳定的目标来源 |
| **风控自适应节奏** | [#28](https://github.com/Juwan-Hwang/Autoqqpet/issues/28) [#30](https://github.com/Juwan-Hwang/Autoqqpet/issues/30) [#31](https://github.com/Juwan-Hwang/Autoqqpet/issues/31) | 需要能识别的风控信号（踢下线、验证提示、`Ban state`）做事件源 | 中：信号不统一，误判会整体停摆；建议先只做「拉长间隔」 |

### 已否决/不做的

- **按「学工累计 8h」整体停学工**：疲劳是服务端按档位降效的，本地时长门槛会双重惩罚（代码注释已记录）。
- **用「晴天/阴天」结算名判坏天气召回**：这是结局名不是天气预告，按它召回会误杀正常趟。

---

## 4. 怎么验证

1. **纯逻辑**：`gradlew.bat testDebugUnitTest`（Linux/macOS 用 `./gradlew`；Windows 启动器已随仓库提供，
   Gradle 发行包由 wrapper 自动下载）；新增用例见 `app/src/test/.../RateUnitsTest.kt`。
2. **决策可解释性**：每轮日志的「依据」行会打印 `学习=… 打工=… 冒险=…` 与择一原因，改完 rate 后
   直接对比同一份快照的决策是否变化。
3. **真机**：至少跑一个完整上午，确认 ①长课不再被短工挤掉、②冒险召回仍按 6.5s 节奏、
   ③「先喂再开工」不再因为认错冒险条目而误判。
