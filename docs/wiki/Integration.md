# 安装与宿主接入

将 `fabric/build/libs/kizuna-meridian-basic-techniques-addon-fabric-0.1.0-SNAPSHOT.jar` 装在服务端和客户端。
硬依赖功法库 `meridian_technique` 与 Human `meridian_human_host`，Java 17、Minecraft 1.20.1。
本仓依赖功法库工作区中的清单发现、书卷与卷轴公开接口；该接口相对早期 89e37ec 已更新。
不修改依赖仓源码，不安装已取消的试玩目录。

`fabric.mod.json` 通过 `meridian_technique:catalogs` 声明目录；功法库负责真实清单发现、目录校验、
卷轴注册、创造栏、按卷学习与施放。无自定义入口，无第二份目录注册代码。
图标路径为 `assets/<namespace>/textures/gui/technique/<path>.png`。
三式真元费用接受任意属性及游离态以上相态；相变后的真炁或带主属性真元也能支付，
无需另备无属性游离态真元。调息、轻步、蓄力仍分别收取整式总额 10、15、15 元，按绑定来源均分。

卷轴显示名为「基础功法·调息卷」「基础功法·轻步卷」「基础功法·蓄力卷」。服务端确认学习成功后，
功法库打开只读阅读界面，显示书名·卷名、各式名称和 `presentation.description`、前置卷，ESC 关闭。
阅读背景来自本仓 `assets/meridian_basic_techniques/textures/gui/scroll/<卷路径>.png`，
正文在中央纸面内换行和滚动；专属图缺失时使用功法库通用背景，每个缺失资源只警告一次。

调息、蓄力在起手开始发送持续表现，收势结束停止。表现 ID 为
`meridian_basic_techniques:technique_breath`、`:technique_charge`、`:technique_dash`。
Human 宿主负责映射 Players-Animation 的同名动作；`technique_dash` 须按实际冲量相对人物朝向，
选择 `technique_dash_forward/left/right/back` 四向。动画素材时长与功法效果时长分别由各自系统管理。
轻步施放只给予轻身，实际起跳才借用 `technique_dash`，落地停止；专属跃步动画尚未制作。
本仓不实现动画或运行时 Java；必需宿主与动画模块按整套装配安装。

三式都用功法库的 `sound` 效果（`SoundCue`）配原版音效，不带音频文件。服务端在 `at_millis` 时刻、施放者位置
（`CASTER`）按 `SoundCategory.PLAYERS` 播放，类人与玩家同一路径。调息、蓄力起手被打断后，起效声不再播放。
轻步的起跳声写在跃步档 `leap.takeoff_sound`：每次实际跃起时在起跳者位置响，落地仍是原版方块脚步声。
带 ± 的音调每次播放在该范围内随机。

| 式 | 时刻 | 声音（`minecraft:`） | 音量 | 音调 | 锚点 | 用途 |
|---|---|---|---:|---|---|---|
| 调息 | 0 ms | `entity.player.breath` | 0.6 | 0.8 | CASTER | 吸气 |
| 调息 | 1100 ms | `block.amethyst_block.chime` | 0.5 | 0.7 | CASTER | 起效 |
| 轻步 | 280 ms | `block.amethyst_block.chime` | 0.4 | 0.9 | CASTER | 轻身起效 |
| 轻步 | 每次跃起 | `entity.rabbit.jump` | 0.6 | 0.7 ±0.05 | CASTER | 起跳 |
| 蓄力 | 0 ms | `entity.player.breath` | 0.5 | 0.6 | CASTER | 沉气 |
| 蓄力 | 1600 ms | `entity.experience_orb.pickup` | 0.3 | 0.5 | CASTER | 起效 |

GameTest 单独装配正式 Human、Damage、功法服务及其依赖，验证代码不进入正式数据 JAR。
蓄力经 Framework 有效肌肉能力影响 Damage 的同剑实伤，不另写伤害倍率规则。
轻步绑定六块腿肌（双侧臀大肌、股直肌、腓肠肌），不设绝对力量门槛。
每次跃步每块约 133.33 W 做功 0.5 秒，总功率 800 W；普通走路保留正常消耗，
重疲劳或重伤使当前功率或耐力不足时，实际跃步退回普通跳，施放增益本身仍只收真元。
三类肌肉的基础爆发功率分别为 650、500、400 W，均高于分摊功率。

功法库从初始化目录注册 `light_body` 原版状态。Human 和类人宿主执行同一 `status_benefit` 意图，
每次跃步通过功法宿主的 Framework 活动入口重新绑定腿肌并提交做功，命令给予状态也无需重复学卷或开脉。
服务端从眼睛射线求落点，按直线长度和起跳速度求飞行 tick 后计算初速度；客户端只预测，
Protobuf 请求不能提供位置、速度或身体数值。预测与服务端起点、初速度一致时不改本地位置，
落地不发传送包，偏差超过 0.25 格才纠正；落地间隔按服务端回执的可起跳时刻判断，客户端提前到达的请求由服务端挂起到该时刻。
飞行中原版客户端位置不能覆盖权威轨迹；碰撞、流体、落地与额外跌落伤害仍经过原版宿主。
客户端显示中性半透明落点描边，不生成粒子。状态图标位于
`assets/meridian_basic_techniques/textures/mob_effect/light_body.png`，18×18 RGBA，由 CPA 生成主体并像素缩小。
