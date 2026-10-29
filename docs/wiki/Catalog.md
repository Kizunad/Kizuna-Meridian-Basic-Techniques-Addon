# 目录与数值

唯一目录源为 `shared/src/main/resources/data/meridian_basic_techniques/technique_catalog.txtpb`。
schema 1，命名空间 `meridian_basic_techniques`，绑定模板 `human:adult` 版本 1。
书 ID 为 `meridian_basic_techniques:basic`，三卷分别为 `breath_volume`、`light_step_volume`、`charge_volume`；
每卷只含同名一式，无前置卷，均为版本 1、最低境界游炁。

| 式 path | 类别 | 来源与执行标签 | 总真元费用 | 起手/收势 | 冷却 |
|---|---|---|---|---|---|
| breath | 恢复 | layer_wandering_qi；breathing；arm_muscle、leg_muscle | 10 | 1.1/0.9 s | 20 s |
| light_step | 移动 | foot_meridian；leg_muscle | 15 | 0.28/0.2 s | 3 s |
| charge | 增益 | hand_meridian；arm_muscle | 15 | 1.6/0.4 s | 10 s |

标签均以 `human:` 开头。来源须已开拓，匹配模块最多 16 个；总真元费用在绑定来源间 EVEN 均分，
不是每条经脉重复扣费。轻步将总计 800 W × 0.5 s 分摊给四块主要腿肌，每块 200 W；
腿槽要求四块当前力量至少 600 N 的肌肉，在成人上对应双侧臀大肌和股直肌。
功法库的 work 按模块收费，若给所有腿肌各收 800 W，普通成人无法施放；这里将方案的 800 W 解释为总功率。
伤势或疲劳导致主要腿肌低于阈值时会绑定失败。轻步起手不可被伤害打断，另两式可打断。
冷却从收势结束算，效果从起手结束算。

调息给双臂、双腿恢复倍率峰值 ×4，曲线（秒:值）为 `0:0, 2:1, 8:1, 20:0`。
组织被动作占用时不自然恢复；停手后才体现恢复增益。
蓄力给双臂力量与功率峰值 ×1.3，曲线为 `0:0, 1.5:1, 2.5:1, 5:0, 6:-0.5, 9:-0.5, 10:0`；
虚脱平台倍率 ×0.85。倍率由 Framework 计算，功法不直接修改伤害。
轻步在起手结束时沿输入水平 6 m/s、向上 4 m/s，零输入使用人物前方。

三式图标 ID 分别为 `meridian_basic_techniques:breath`、`:light_step`、`:charge`。
美术资源由独立美术任务提供，缺图时战斗 UI 用文字占位。
