# 基础功法本地交付

本页保留首次交付和冲量版本的历史证据。当前轻步已经改为轻身状态与连续跃步，
六块腿肌共同做功且不设力量门槛；现行行为、数据和验收以 [Catalog](Catalog.md)、[Integration](Integration.md)、
[Verification](Verification.md) 为准。

新建本地仓，一本基础功法书、调息/轻步/蓄力三个独立卷，各含一式 v1，无前置。
双端安装的正式 Fabric JAR 为纯数据：无 Java、入口或 Mixin。
书卷发现、学习、费用、施放、冷却及身体效果复用功法库与宿主公开协议，不复制规则代码。
未提交、未暂存、无远端、未推送；没有修改相邻依赖源码。

## 文件

- 根目录：`.gitattributes`、`.gitignore`、`AGENTS.md`、`README.md`、`LICENSE`、`NOTICE`、
  `THIRD_PARTY_NOTICES.md`、`build.gradle`、`settings.gradle`、`gradlew`、`gradlew.bat`、
  `gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties`。
- 工程模块：`shared/build.gradle`、`fabric/build.gradle`、`verification/build.gradle`、`gametest/build.gradle`。
- 唯一功法目录：`shared/src/main/resources/data/meridian_basic_techniques/technique_catalog.txtpb`。
- 正式模组元数据：`fabric/src/main/resources/fabric.mod.json`。
- 校验脚本：`scripts/check_data.py`、`scripts/check_docs.py`。
- 验证夹具：`verification/src/main/java/dev/kizuna/meridian/basictechniques/verification/BasicTechniquesCatalog.java`、
  `BasicBodies.java`；测试：`verification/src/test/java/dev/kizuna/meridian/basictechniques/verification/BasicCatalogTest.java`。
- GameTest：`gametest/src/main/java/dev/kizuna/meridian/human/fabric/BasicTestPlayer.java`、
  `BasicTechniquesGameTest.java`、`gametest/src/main/resources/fabric.mod.json`。
- 其他美术窗口交付的 12 张 PNG：`fabric/src/main/resources/assets/meridian_basic_techniques/textures/` 下
  `gui/technique/{breath,light_step,charge}.png` 及三个 `_64.png`、
  `item/{breath_volume,light_step_volume,charge_volume}_scroll.png`、
  `gui/scroll/{breath_volume,light_step_volume,charge_volume}.png`。
- Wiki：`docs/wiki/Home.md`、`Catalog.md`、`Integration.md`、`Verification.md`、本页；
  美术窗口另提供 `Art.md`，保留其原有生成与后处理记录。

## 设计解释与接口边界

数值、起手/收势、冷却、曲线与表现时机见 [目录与数值](Catalog.md)。
轻步的 800 W 解释为总功率，在四块力量至少 600 N 的主要腿肌间均分，每块 200 W × 0.5 s；
正式成人绑定已由单测覆盖。若每块腿肌都收 800 W，正式成人无法承担费用。
本仓依赖尚未提交的功法目录清单、书卷与公开宿主接口；不能在旧 89e37ec 二进制上运行。
表现 ID 已提供，PA 映射和四向轻步动画由宿主负责。
美术窗口提供的 12 张 PNG 已包含在正式包中，路径与来源见 [美术](Art.md)；本窗口没有生成图片。

## 2026-10-29 续作验证

`bash "gradlew" test build :gametest:runGametest --console=plain` 已通过，4 项 JUnit、7 项 GameTest 无失败或跳过。
实际日志：相邻 remote-session 的 `handoff/logs/resume-6b-basic-final.log`。
Human 已修复同刻水平与竖直冲量覆盖；测试还验证本人速度同步及基于该速度包的一帧客户端移动回报。
玩家劈门、耐久、伤害与卫道士 AI 在对应宿主仓完成验证。以下初始交付记录保留历史失败及当时产物哈希，
不代表本轮结果或重建后的产物。

## 初始命令与验证证据

工作目录为本仓根目录；实际命令：

```bash
bash "gradlew" test build :gametest:runGametest --console=plain
bash "gradlew" test build --console=plain
python3 "scripts/check_docs.py"
python3 "scripts/check_data.py"
git diff --check
```

日志在相邻 remote-session 的 `handoff/logs/`。
`basic-techniques-final-validation.log`：4 JUnit 通过，GameTest 3/4 通过，整条命令退出 1，
`BUILD FAILED in 49s`。随后单独执行 `test build` 通过，结果见下方最终产物记录。
失败为 `lightStepUsesInputAndZeroInputFacesForward` 的「轻步未沿输入方向」。
Human 对同次施放的每条冲量 `setVelocity`，UP 覆盖 INPUT；纯数据无合成三维冲量可表达，
须宿主修复正交分量组合及重试幂等。本仓保留方向断言，没有将失败改为跳过。
完整测试名与 XML 位置见 [验证](Verification.md)。最终产物与 SHA-256 见本页末尾。

## 初始交付时尚未验证

轻步完整水平/竖直冲量及零输入前向回退尚未通过；同一测试在首个方向断言失败，后续断言没有执行。
Windows 卷轴右键、创造栏、真实输入、图标与语言美术、动画和完整多仓装配须后续整合验收。
没有向 Windows 实例安装本仓 JAR，也没有修改取消的试玩目录。

代码形状自查：第 2 条，`BasicBodies.adult` 将经脉状态 builder 提为局部变量；
第 4 条，`BasicTechniquesGameTest.prepared/strength/exertArm` 复用同次身体与时间读取；
第 7 条，`BasicTestPlayer` 玩家名截取长度及 `BasicTechniquesGameTest` 时长/速度换算具名常量；
第 11 条，修正 `BasicTechniquesGameTest.prepared` 的缩进。

## 初始产物与收尾检查

最终单独构建日志 `basic-techniques-final-build.log`：退出 0，`BUILD SUCCESSFUL in 4s`。
JUnit XML 仍为 4 项全通过；GameTest XML 保留最近一次的 3/4 通过、1 项失败，没有跳过项。
实际 Gradle 命令包含以下日志重定向：

```bash
bash "gradlew" test build :gametest:runGametest --console=plain > "/home/kiz/Code/Kizuna-remote-session/handoff/logs/basic-techniques-final-validation.log" 2>&1
bash "gradlew" test build --console=plain > "/home/kiz/Code/Kizuna-remote-session/handoff/logs/basic-techniques-final-build.log" 2>&1
```

交付文件：`fabric/build/libs/kizuna-meridian-basic-techniques-addon-fabric-0.1.0-SNAPSHOT.jar`。
SHA-256：`0bed7b2eb12df401590b352c4371f45e0d7daf23ae209664fda285083e36a600`。
包内为 0 个 class、12 张 PNG，全部图片和唯一 txtpb 与当前源文件逐字节一致；
三张 32×32 图标、三张 64×64 图标、三张 32×32 卷轴、三张 1536×1024 背景均已核对路径与尺寸。
文档检查 7 页、数据检查通过。`git diff --check` 与逐文件未跟踪文本检查通过，
覆盖 32 个文本；5 个 Java 文件全部不超过 120 列。

两仓与旧 Windows 候选的只读收尾命令：

```bash
python3 "/home/kiz/Code/Kizuna-remote-session/handoff/logs/combat-basic-final-checks.py" > "/home/kiz/Code/Kizuna-remote-session/handoff/logs/combat-basic-final-checks.json"
```

该脚本核对测试 XML 的实际状态，明确保留上述轻步失败；退出 0 表示证据与预期记录一致，
不能解释为全部 GameTest 通过。两仓均为 0 暂存、0 提交、0 远端。
