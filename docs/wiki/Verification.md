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
以及游炁/真炁在未开、手开、足开、全开身体上的绑定；三式费用须为任意属性、最低游离态匹配。
GameTest 位于 `gametest/src/main/java`：真实清单发现与按卷学习、正式 Human 身体的蓄力曲线及同剑实伤、
轻身状态、按键跃步与真实碰撞落地、调息对普通做功产生的手臂疲劳的恢复。
`allTechniquesStartAndPayWithOnlyTrueQi` 通过管理员库存入口让内腔只含真炁，
验证三式均能起手，分别扣除总额 10、15、15 元，剩余真炁的相态与实际密度保持不变。

`PlayerAssemblyGameTest` 使用 Framework 统一完整装配，额外覆盖：

- `creativeTechniqueGroupAndScrollsLearnBasicAndUpperVolume`：功法栏注册、展示与搜索内容，物品右键学习基础三卷和破门斩上卷，累计六式。
- `combatProtocolChargeFollowsHumanStrengthCurve`：正式战斗载荷接收入口，真实 Human 力量峰值、线性回落、虚脱与到期恢复。
- `combatProtocolLightStepGrantsStatusThenJumpChoosesFacing`：战斗协议施放给予状态，跳跃才按当前朝向求初速度。

`PlayerCombatAssemblyGameTest` 与 `VindicatorAssemblyGameTest` 补充整套接线回归：

- `combatProtocolCleaveBreaksWoodenDoorAndWearsIronAxe`：读上卷后发战斗协议，生存玩家劈掉木门上下半，铁斧扣 3 耐久。
- `combatProtocolKnockDamagesMobThroughMuscleRules`：读上卷后发战斗协议，铁斧单手叩门三击合计倍率 1.5，
  每击按当时肌肉系数对无护甲生物实际扣血，使用主手钝击类型。
- `trueQiVindicatorBrainCastsDoorBreakerAgainstPlayer`：真炁身体按只发破门卷的批次配置学卷，类人大脑起手劈门式，
  施放保留近战互斥与主动移动，通过 Human/Damage 扣除在线玩家生命。
- `trueQiVindicatorBrainCleaveBreaksDoorWithMobGriefing`：同样只发破门卷，大脑起手劈门式后才放置木门，
  `mobGriefing` 开启时上下半都被劈开，生物铁斧不磨损。
- 大脑按权重抽式，两个用例都在 2400 tick 内轮询到劈门式起手为止；卫道士用按用例名生成的固定 UUID，同一世界里抽样可重复。
  目标不受击退，留在长起手距离内；牛加高血量，玩家在劈门式起手前开能力无敌，免得先出的其他式经 Human 身体把它打死。

`HumanoidBrainPlayerGameTest` 覆盖类人大脑对真实玩家的反应：

- `wanderingVillagerHitByPlayerRetreats`：只会基础三卷的游炁村民被玩家打一拳后撤退拉开。
- `armedTrueQiVillagerHitByPlayerFightsBack`：学了破门上卷、持斧的真炁村民被打后还手，破门式扣玩家生命。
- `trueQiVindicatorLeapsToStandingPlayerAndStrikes`：持斧真炁卫道士对六格外静立的玩家先轻步，跃到玩家面前一到两格，再出破门打击式。

正式基础功法 JAR 与测试入口分开加载，清单来源断言为 `meridian_basic_techniques`，不复制一份测试目录。
`KnockMultihitGameTest` 覆盖玩家主副手、类人出手、18/25/34 tick 三次真实受伤与闪红、
实际伤害聊天包、逐击溢出蔓延、一次耐久以及首击后目标离开导致后两击落空。
五个叩门用例同时核对声音：`BasicTestPlayer` 记下发给玩家的每个声音包，按 Door-Breaker 目录逐条比对
ID、时刻（±1 tick）、位置、`PLAYERS` 类别、音量与音调抖动区间；施放者声音在出手者处，目标声音只对应实际命中记录，
目标离开后落空的两击没有命中声，每条恰好听到一次。
`staggerInCleaveWarmupSilencesTheRemainingSounds` 在破门起手中途僵直，此前的起手声照常响，之后的劈砍、命中与收势声都不再下发；
`LightBodyGameTest` 的三格台阶用例断言起跳 tick 在起跳点恰好一次 `entity.rabbit.jump`；
`soundOutsideTheRegistryIsSentInlineWithItsId` 验证注册表外的合法 ID 不按编号、随包带原 ID 下发。
声音包按 16 格范围下发，同批并行的用例相距只有几格会互相听到，所以逐条数声音的用例各占一个 `batch`。
用例先断言目录里确有声音，免得装配到无声音的旧 JAR 时期望与实际同为空而空过。
`gametest` 的 `modLocalRuntime files(...)` 在 Gradle 配置阶段重映射：目录改动后同一次调用里新打的 JAR 赶不上，
GameTest 跑的仍是上一版目录。改目录后先单独执行一次 `bash "gradlew" :fabric:build`；
叩门与破门的声音读 Door-Breaker 目录；Door-Breaker 在 settings 中以源码组合，GameTest 配置时按坐标构建并装入其当前目录，
不必再先到 Door-Breaker 仓单独打包。
`LegTechniqueGameTest` 通过 1200 tick 步行加 300 tick 疾跑的实际位置采样积累疲劳，
验证轻步与撞门之后各连续施放三次，每次轻步还支付一次跃步做功；期间不治疗或额外恢复体力。
八成疲劳或九成腿肌损伤仍能完成六肌绑定，但撞门做功预检返回 `INSUFFICIENT_CAPABILITY`，
轻身实际跃步因预算不足退回普通跳。
详细伤害、权限、八式时序和 AI 选择边界由 Human 与 Threatening 的单仓 GameTest 覆盖。
2026-10-29 最终整合：4 项 JUnit、11 项 GameTest 全部通过，日志为 `handoff/logs/integration-final-gametest.log`。

日志交付到相邻 remote-session 的 `handoff/logs/`。新仓未跟踪文件另用
`git diff --no-index --check /dev/null <文件>` 覆盖，普通 `git diff --check` 不包含未跟踪文件。
Windows 实机的卷轴操作、动画、美术和完整试玩装配由整合任务验收；本仓不替换正在使用的实例。

## 测试名与当前结果

功法音效版本的完整回归为 11 项 JUnit、37 项 GameTest，日志为 `handoff/logs/technique-sounds-basic-build4.log`；
比轻身直线预算版本的 32 项多出类人大脑三项与上述两项声音用例。
发布目录检查验证六肌绑定与功率总量，真实客户端验收由功法库的 `runLeapClientTest` 执行。

`BasicCatalogTest` 五项 JUnit 通过：

- `productionJarDeclaresCatalogAndContainsNoJava`
- `catalogValidatesIndependentVolumesAndPresentationLifetimes`
- `wanderingAndTrueQiBindOnlyOpenedMatchingSources`
- `lightStepDistributesWorkAcrossAllSixAdultLegMuscles`
- `allTechniquesAcceptAnyAttributeAtOrAboveFreePhase`

`BasicTechniquesGameTest` 使用正式 Human 玩家、Framework 和 Damage，四项全部通过：

- `discoversCatalogAndLearnsEachIndependentVolume`：清单发现，独立卷累计学习三式。
- `chargeCurveChangesHumanStrengthAndSameSwordDamage`：峰值、回落、虚脱和到期，有效力量与同型号铁剑实伤比率。
- `breathAcceleratesIdleArmFatigueRecovery`：普通做功产生的双臂疲劳恢复对照。
- `lightStepGrantsQuietStatusInsteadOfImmediateImpulse`：起手后给予轻身、无粒子、显示图标，施放本身不产生冲量。

`LeapTargetTest` 读取正式目录，检查直线预算 7 格、起跳速度 12 m/s、6–30 tick 与停用字段为空，
覆盖平地 3/5/6/7 格、上 2/3 格、下 4 格（超出直线预算的台面不被选中）、墙面吸附墙头、
液体/无净空/悬空目标的候选筛选、近处短跳、视线搜索和无支撑回退；逐 tick 轨迹误差不超过 0.3 格。
`LightBodyGameTest` 覆盖原版 `/effect give`、刷新不叠加、三格墙头实际落地误差不超过 0.5 格、
三格台阶飞行不超过 14 tick、连续三次做功且不扣真元、六 tick 落地间隔、间隔内网络请求挂起到时刻并从落地处起跳、
潜行与疲劳回退普通跳、碰墙、正常免摔与额外落差受伤、
真炁卫道士朝玩家自动跃步；真实世界的水、岩浆、低顶和悬空格均吸附到安全平台；
网络测试拒绝客户端伪造速度，并验证重复请求不重复扣费。
数值与说明见 [目录](Catalog.md) 和 [宿主接入](Integration.md)。

修复边界见 [安装与宿主接入](Integration.md)，历史验证与本轮续作结果见 [本地交付记录](Delivery.md)。
