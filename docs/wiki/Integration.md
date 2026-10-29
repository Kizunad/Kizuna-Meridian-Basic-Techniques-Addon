# 安装与宿主接入

将 `fabric/build/libs/kizuna-meridian-basic-techniques-addon-fabric-0.1.0-SNAPSHOT.jar` 装在服务端和客户端。
硬依赖功法库 `meridian_technique` 与 Human `meridian_human_host`，Java 17、Minecraft 1.20.1。
本仓依赖功法库工作区中的清单发现、书卷与卷轴公开接口；该接口相对早期 89e37ec 已更新。
不修改依赖仓源码，不安装已取消的试玩目录。

`fabric.mod.json` 通过 `meridian_technique:catalogs` 声明目录；功法库负责真实清单发现、目录校验、
卷轴注册、创造栏、按卷学习与施放。无自定义入口，无第二份目录注册代码。
图标路径为 `assets/<namespace>/textures/gui/technique/<path>.png`。

卷轴显示名为「基础功法·调息卷」「基础功法·轻步卷」「基础功法·蓄力卷」。服务端确认学习成功后，
功法库打开只读阅读界面，显示书名·卷名、各式名称和 `presentation.description`、前置卷，ESC 关闭。
阅读背景来自本仓 `assets/meridian_basic_techniques/textures/gui/scroll/<卷路径>.png`，
正文在中央纸面内换行和滚动；专属图缺失时使用功法库通用背景，每个缺失资源只警告一次。

每式在起手开始发送持续表现，收势结束停止。表现 ID 为
`meridian_basic_techniques:technique_breath`、`:technique_charge`、`:technique_dash`。
Human 宿主负责映射 Players-Animation 的同名动作；`technique_dash` 须按实际冲量相对人物朝向，
选择 `technique_dash_forward/left/right/back` 四向。动画素材时长与功法效果时长分别由各自系统管理。
没有对应动画时宿主可忽略表现，数据效果仍由功法库处理；本仓不实现动画。

GameTest 单独装配正式 Human、Damage、功法服务及其依赖，验证代码不进入正式数据 JAR。
蓄力经 Framework 有效肌肉能力影响 Damage 的同剑实伤，不另写伤害倍率规则。
轻步要求四块腿肌的当前有效力量至少 510 N（原为 600 N），给基础 600 N 的股直肌留 15% 余量。
每块仍按 200 W 做功 0.5 秒，总功率 800 W；普通走路和连续施放保留正常消耗，重疲劳或重伤仍拒绝。

Human 宿主通过共享执行器分别更新水平与竖直速度，同刻 INPUT 与 UP 不再互相覆盖，效果重试由宿主去重。
`lightStepUsesInputAndZeroInputFacesForward` 已验证输入方向、零输入前向回退与竖直分量。
战斗协议装配测试还检查发给本人的速度包，并经原版客户端移动回报验证服务器接纳位移。
夹具只模拟这一帧客户端回报，真实客户端物理、动画与视觉表现仍需客户端验收。
