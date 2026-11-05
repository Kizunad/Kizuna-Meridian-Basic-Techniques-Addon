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
轻身状态、按键跃步与真实碰撞落地、调息对普通做功产生的双臂与双腿疲劳的恢复。
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
  `mob_griefing` 开启时上下半都被劈开，生物铁斧不磨损。
- 大脑按权重抽式，两个用例都在 2400 tick 内轮询到劈门式起手为止；卫道士用按用例名生成的固定 UUID，同一世界里抽样可重复。
  目标不受击退，留在长起手距离内；牛加高血量，玩家在劈门式起手前由 Fabric 伤害许可事件挡掉伤害，
  免得先出的其他式经 Human 身体把它打死。

`HumanoidBrainPlayerGameTest` 覆盖类人大脑对真实玩家的反应：

- `wanderingVillagerHitByPlayerRetreats`：只会基础三卷的游炁村民被玩家打一拳后撤退拉开。
- `armedTrueQiVillagerHitByPlayerFightsBack`：学了破门上卷、持斧的真炁村民被打后还手，破门式扣玩家生命。
- `trueQiVindicatorLeapsToStandingPlayerAndStrikes`：持斧真炁卫道士对六格外静立的玩家先轻步，跃到玩家面前一到两格，再出破门打击式。

正式基础功法 JAR 与测试入口分开加载，清单来源断言为 `meridian_basic_techniques`，不复制一份测试目录。
`KnockMultihitGameTest` 覆盖玩家主副手、类人出手、9/16/25 tick 三次真实受伤与闪红、
实际伤害聊天包、逐击溢出蔓延、一次耐久以及首击后目标离开导致后两击落空。
五个叩门用例同时核对声音：`BasicTestPlayer` 记下发给玩家的每个声音包，按 Door-Breaker 目录逐条比对
ID、时刻（±1 tick）、位置、类别（玩家出手 `PLAYERS`、卫道士出手 `HOSTILE`）、音量与音调抖动区间；施放者声音在出手者处，目标声音只对应实际命中记录，
目标离开后落空的两击没有命中声，每条恰好听到一次。
`staggerInCleaveWarmupSilencesTheRemainingSounds` 在破门起手中途僵直，此前的起手声照常响，之后的劈砍、命中与收势声都不再下发；
`LightBodyGameTest` 的三格台阶用例断言起跳 tick 在起跳点恰好一次 `entity.rabbit.jump`；
`soundOutsideTheRegistryIsSentInlineWithItsId` 验证注册表外的合法 ID 不按编号、随包带原 ID 下发。
声音包按 16 格范围下发，同批并行的用例相距只有几格会互相听到，所以逐条数声音的用例各占一个批次：
26.2 按测试环境分批，这些用例各引用一个空环境（`gametest/src/main/resources/data/meridian_basic_techniques_test/test_environment/`）。
用例先断言目录里确有声音，免得装配到无声音的旧 JAR 时期望与实际同为空而空过。
`gametest` 以 `localRuntime files(...)` 引用 `fabric` 的 `jar` 任务；26.x 不重映射，`runGametest` 前会先重打 JAR，
改目录后不必再单独构建一次；
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
- `breathAcceleratesIdleArmAndLegFatigueRecovery`：普通做功产生的双臂、双腿疲劳恢复对照，并检查生命、组织 HP 和真元。

`StatusBuffGameTest` 覆盖三状态的六种添加顺序、轻身能力筛选、同种刷新、起手打断不退费、
峰值牛奶清除、乏力段命令清除、自定义时长、高 amplifier、拒绝短覆盖、无限时长规范、无身体实体、
重登相位、维度切换、死亡复活、实体卸载加载、自然到期及容量不足时原版图标回滚。
内存网络连接显式执行原版玩家 tick，确保药水剩余时长真实递减。
`StatusMigrationGameTest` 用迁移前目录实际施放并保存 Human 身体，再由新目录重登，
验证旧曲线、剩余冷却和到期后的再次施放。历史 txtpb 只存在于 GameTest 资源中。
`-PgametestRunDir=build/status-buffs-gametest` 可指定独立运行目录，避免复用其他测试的旧配置。

功法库 `TechniqueStatusTissuesTest` 使用真实 Framework `handleActivity` 验证容量拒绝、revision 冲突、
原子刷新、重复清除、长时曲线分段、旧曲线不可提前删除与目录能力校验。
客户端验收入口为功法库 `:fabric:runStatusClientTest -PscrollFullAssembly`，读取独立测试世界
`fabric/build/statusclienttest/saves/status-buffs-test`，输出昼夜、GUI scale 2/4 的原版 HUD/状态列表截图及牛奶清除截图。
测试资源和 Java 均不进入 Basic 正式包。
`runStatusRestartClientTest` 分别使用 `-PstatusRestart=save`、`restore` 启动两个独立 JVM，
验证正常停服后的原版剩余时长、总时长元数据、乏力相位和清除。

2026-11-05 Linux 验收使用与 BS3D 匹配的临时 Weights、Vanilla HEAD 产物，未修改依赖仓库源码。
HUD 三图正常；整合装配的 BS3D 背包布局将第三项原版状态裁到屏幕外，完整状态列表验收尚有此限制。
详细截图、命令和版本记录见本次交接报告。
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

## 2026-11-01：S3 迁到 Minecraft 26.2

Minecraft 26.2、Java 25、Gradle 9.8.0 wrapper、Loom 1.19.3（26.x 不混淆，不重映射），版本取自 Framework 的版本目录。
正式 Fabric 包仍是纯数据，`fabric.mod.json` 只把依赖改为 Java ≥25、Minecraft 26.2、Loader ≥0.20.1，目录与资源不变。

GameTest 只做 26.2 改名与接口替换，断言不变：

- 改用 Fabric `@GameTest`；未单独写时限的用例显式保留 1.20.1 默认的 100 tick（`BasicGameTestTicks`），
  26.2 默认只有 20 tick。
- `batch` 改为测试环境：叩门五例、破门僵直声音一例、类人大脑三例各引用一个空环境；
  卫道士两例的批次前后钩子改为自定义环境类型 `meridian_basic_techniques_test:door_breaker_only`
  （`BasicTestEnvironments`），批次前只发破门卷、批次后换回启动配置，两例仍各占一个批次。
  测试环境注册表按对象身份拒收重复值，所以该类型是记录，每份环境数据解出一个新实例，不用枚举单例。
- `BasicTestPlayer` 经功法库 testFixtures 的 `TestPlayers.logIn` 登录；功法库的公共记录连接不能扩展，
  本仓保留自己的记录连接，以便按世界 tick 记下声音包。战斗按键与跃步请求按通道构造载荷，
  所以 `gametest` 编译期加 Framework 的 Fabric 模组（`compileOnly`，运行时由完整装配提供）。
  身体世界状态改经 `HumanTechniqueHost.state()` 读取；速度包直接取 `movement()`（26.2 到编码时才量化）。
- 命令执行入口不再返回结果，改由来源的结果回调取回（`BasicTestCommands`）；权限 2 改 `GAMEMASTER`。
- 受击观察改覆盖 `hurtServer`，伤害类型键改 `identifier()`；实体类型常量改 `EntityTypes`，生成带 `COMMAND` 原因；
  `moveTo` 改 `snapTo`，`mobGriefing` 改 `GameRules.MOB_GRIEFING`，区块坐标改 `ChunkPos.containing`，
  移动包补 `horizontalCollision`，延迟失败改用 `context.assertionException`。

首次用完整装配运行时又按 26.2 行为改了八处测试侧写法，断言不变：

- 26.2 的 `ItemStack.use` 把立即生效的成功结果重新包成带手持物的成功对象，学卷交互改为判断不挥手的服务端成功，
  不再比对 `InteractionResult.CONSUME` 的对象身份。
- 26.2 分批发区块，客户端回执前不发下一批，未发到区块里的实体不向玩家同步，叩门用例收不到受击通知；
  记录连接收到批次结束包时像真实客户端那样回执。区块发到后，其中的实体要等玩家跨区段或发来移动包才重算配对，
  测试玩家不发移动包，所以回执后按移动包的服务端处理重算一次；1.20.1 发区块时就同步其中的实体。
- 26.2 的生物不把能力无敌的玩家设为目标，卫道士对玩家一例改由 Fabric 伤害许可事件在劈门式起手前挡掉玩家受伤。
- 原版村民向身边空手的玩家展示交易时会清空主手，持斧村民还手一例在玩家出拳前不开 AI，与卫道士突进一例同一写法；
  开 AI 到大脑首次接手之间原版日常仍会跑，大脑按实体 ID 错相决策，所以出拳放在大脑下次决策的前一 tick。
- 26.2 的 GameTest 场地随机放在百万格外，声音包坐标先转单精度再除，那里的误差可到半格以上；
  叩门声音比对的位置容差每轴加上该处半个单精度间隔。
- 26.2 没有常驻的出生区块，登录点所在区块可能未加载，生成在那里的实体查不到、功法宿主认领不到；
  叩门真炁卫道士一例先把玩家挪到出手者的位置，再在玩家脚下生成。
- 26.2 的空结构外围是屏障墙：游炁村民撤退一例改在结构南侧生成，北面留出撤退的地方；
  并像还手一例那样出拳前不开 AI，免得原版闲逛事先走远、撤退拉不开要求的距离。
- 完整装配里类人大脑也管卫道士，大脑先看到目标就由它指挥跃步，自动追击按设计让位；两边按实体 ID 错相决策，
  谁先看到目标随整批用例的生成次序变化。轻身卫道士追击一例关着 AI 等到自动追击决策相位的前一 tick，
  再开 AI 并给目标。

命令：`./gradlew test build :gametest:runGametest`（每次用新运行目录）；`python3 scripts/check_docs.py`；
`python3 scripts/check_data.py`；`git diff --check`。

结果：JUnit 11/11（`BasicCatalogTest` 5、`LeapTargetTest` 5、`CatalogLanguageTest` 1）；
完整装配 GameTest 38/38（本仓 37 例加原版 `minecraft:always_pass`），连续三次新运行目录都全过；
文档检查通过（7 个页面），数据检查通过。
