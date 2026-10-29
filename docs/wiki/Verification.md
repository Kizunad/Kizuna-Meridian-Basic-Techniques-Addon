# 验证

仓库根目录执行（wrapper 无执行位时用 Bash）：

```bash
bash "gradlew" test build --console=plain
bash "gradlew" :gametest:runGametest --console=plain
python3 "scripts/check_docs.py"
python3 "scripts/check_data.py"
git diff --check
```

JUnit 位于 `verification/src/test/java`：正式纯数据包、目录校验、独立书卷、表现生命周期，
以及游炁/真炁在未开、手开、足开、全开身体上的绑定。
GameTest 位于 `gametest/src/main/java`：真实清单发现与按卷学习、正式 Human 身体的蓄力曲线及同剑实伤、
轻步输入与前向回退、调息对普通做功产生的手臂疲劳的恢复。

`PlayerAssemblyGameTest` 使用 Framework 统一完整装配，额外覆盖：

- `creativeTechniqueGroupAndScrollsLearnBasicAndUpperVolume`：功法栏注册、展示与搜索内容，物品右键学习基础三卷和破门斩上卷，累计六式。
- `combatProtocolChargeFollowsHumanStrengthCurve`：正式战斗载荷接收入口，真实 Human 力量峰值、线性回落、虚脱与到期恢复。
- `combatProtocolLightStepMovesAlongInputAndEntityFacing`：同一战斗入口的输入方向、零输入朝向、本人速度包和服务端接纳的位移；夹具先经正式传送放到独立空中通道，避免随机登录点的方块遮挡，再根据真实速度包回报一帧客户端位置，保留严格方向与位移断言。

`PlayerCombatAssemblyGameTest` 与 `VindicatorAssemblyGameTest` 补充整套接线回归：

- `combatProtocolCleaveBreaksWoodenDoorAndWearsIronAxe`：读上卷后发战斗协议，生存玩家劈掉木门上下半，铁斧扣 3 耐久。
- `combatProtocolKnockDamagesMobThroughMuscleRules`：读上卷后发战斗协议，铁斧单手叩门三击合计倍率 1.5，
  每击按当时肌肉系数对无护甲生物实际扣血，使用主手钝击类型。
- `trueQiVindicatorAiCastsDoorBreakerAgainstPlayer`：真炁身体自动学卷，原版 AI tick 自选破门斩，通过 Human/Damage 扣除在线玩家生命。
- `trueQiVindicatorAiCleaveBreaksDoorWithMobGriefing`：真实 AI 起手后放置木门，`mobGriefing` 开启时上下半都被劈开，生物铁斧不磨损。

正式基础功法 JAR 与测试入口分开加载，清单来源断言为 `meridian_basic_techniques`，不复制一份测试目录。
`KnockMultihitGameTest` 覆盖玩家主副手、类人出手、18/25/34 tick 三次真实受伤与闪红、
实际伤害聊天包、逐击溢出蔓延、一次耐久以及首击后目标离开导致后两击落空。
`LegTechniqueGameTest` 通过 600 tick 的实际位置采样积累步行疲劳，验证轻步与撞门之后各连续施放三次，
期间不恢复体力；另以正式做功与健康事务证明八成疲劳或九成腿肌损伤仍被拒绝。
详细伤害、权限、八式时序和 AI 选择边界由 Human 与 Threatening 的单仓 GameTest 覆盖。
2026-10-29 最终整合：4 项 JUnit、11 项 GameTest 全部通过，日志为 `handoff/logs/integration-final-gametest.log`。

日志交付到相邻 remote-session 的 `handoff/logs/`。新仓未跟踪文件另用
`git diff --no-index --check /dev/null <文件>` 覆盖，普通 `git diff --check` 不包含未跟踪文件。
Windows 实机的卷轴操作、动画、美术和完整试玩装配由整合任务验收；本仓不替换正在使用的实例。

## 测试名与当前结果

`BasicCatalogTest` 四项 JUnit 通过：

- `productionJarDeclaresCatalogAndContainsNoJava`
- `catalogValidatesIndependentVolumesAndPresentationLifetimes`
- `wanderingAndTrueQiBindOnlyOpenedMatchingSources`
- `lightStepDistributesWorkAcrossFourCapableAdultLegMuscles`

`BasicTechniquesGameTest` 使用正式 Human 玩家、Framework 和 Damage，四项全部通过：

- `discoversCatalogAndLearnsEachIndependentVolume`：清单发现，独立卷累计学习三式。
- `chargeCurveChangesHumanStrengthAndSameSwordDamage`：峰值、回落、虚脱和到期，有效力量与同型号铁剑实伤比率。
- `breathAcceleratesIdleArmFatigueRecovery`：普通做功产生的双臂疲劳恢复对照。
- `lightStepUsesInputAndZeroInputFacesForward`：输入水平分量、向上冲量与零输入前向回退。

修复边界见 [安装与宿主接入](Integration.md)，历史验证与本轮续作结果见 [本地交付记录](Delivery.md)。
