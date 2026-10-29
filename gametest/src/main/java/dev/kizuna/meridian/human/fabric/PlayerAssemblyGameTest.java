package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.vanilla.DamagePreferences;
import dev.kizuna.meridian.vanilla.MuscleRules;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 完整装配的玩家入口：创造栏、卷轴学习、战斗载荷与真实身体效果。 */
public final class PlayerAssemblyGameTest implements FabricGameTest {
    private static final String BASIC = "meridian_basic_techniques:";
    private static final List<String> VOLUMES = List.of(BASIC + "breath_volume", BASIC + "charge_volume",
            BASIC + "light_step_volume", "meridian_door_breaker:upper");
    private static final Set<String> LEARNED = Set.of(BASIC + "breath", BASIC + "charge", BASIC + "light_step",
            "meridian_door_breaker:knock", "meridian_door_breaker:cleave", "meridian_door_breaker:ram");
    private static final int STACK_SIZE = 2;
    private static final long MILLIS_PER_TICK = 50;
    private static final int PREPARE_TICKS = 25;
    private static final int CHARGE_LIMIT = 300;
    private static final int PEAK_TICKS = 65;
    private static final int DECLINE_TICKS = 107;
    private static final int COLLAPSE_TICKS = 160;
    private static final int EXPIRE_TICKS = 237;
    private static final int STEP_TICKS = 7;
    private static final int STEP_HEIGHT = 4;
    private static final int STEP_LANE_SPACING = 2;
    private static final double PEAK_STRENGTH = 1.3;
    private static final double DECLINE_STRENGTH = 1.15;
    private static final double COLLAPSE_STRENGTH = 0.85;
    private static final double EPSILON = 0.025;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void creativeTechniqueGroupAndScrollsLearnBasicAndUpperVolume(TestContext context) {
        // 创造栏内容来自正式注册表；学习必须通过玩家物品交互写入同一个身体。
        var group = TechniqueScrollItems.group();
        var world = context.getWorld();
        context.assertTrue(Registries.ITEM_GROUP.get(TechniqueScrollItems.GROUP_ID) == group, "功法栏未注册");
        context.assertTrue(group.getDisplayName().equals(
                Text.translatable("itemGroup.meridian_technique.techniques")), "功法栏标题键错误");
        group.updateEntries(new ItemGroup.DisplayContext(world.getEnabledFeatures(),
                true /* hasPermissions */, world.getRegistryManager()));
        var displayed = group.getDisplayStacks().stream()
                .map(stack -> Registries.ITEM.getId(stack.getItem()).toString())
                .collect(Collectors.toSet());
        var searchable = group.getSearchTabStacks().stream()
                .map(stack -> Registries.ITEM.getId(stack.getItem()).toString())
                .collect(Collectors.toSet());
        for (String volume : VOLUMES) {
            String scrollId = volume + "_scroll";
            context.assertTrue(displayed.contains(scrollId) && searchable.contains(scrollId), "创造栏缺卷：" + volume);
        }
        try (var fixture = prepared(context)) {
            for (String volume : VOLUMES) {
                learnScroll(context, fixture, volume);
            }
            var actual = TechniqueFabric.service().known(fixture.player.getUuid()).stream()
                    .map(item -> item.technique().getId())
                    .collect(Collectors.toSet());
            context.assertTrue(actual.equals(LEARNED), "卷轴学习结果不符：" + actual);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = CHARGE_LIMIT)
    public void combatProtocolChargeFollowsHumanStrengthCurve(TestContext context) {
        // 等待登录后的身体结算稳定，再从真实战斗接收器提交蓄力。
        var boosted = prepared(context);
        var control = prepared(context);
        var players = List.of(boosted, control);
        guarded(players, () -> learnScroll(context, boosted, BASIC + "charge_volume"));
        later(context, PREPARE_TICKS, players, () -> {
            press(boosted, "charge", Vec3d.ZERO);
            later(context, PEAK_TICKS, players, () -> assertStrength(context, players, PEAK_STRENGTH));
            later(context, DECLINE_TICKS, players, () -> assertStrength(context, players, DECLINE_STRENGTH));
            later(context, COLLAPSE_TICKS, players, () -> assertStrength(context, players, COLLAPSE_STRENGTH));
            later(context, EXPIRE_TICKS, players, () -> {
                try (boosted; control) {
                    assertStrength(context, players, 1);
                    context.assertTrue(boosted.body().snapshot().modifiers().isEmpty(), "到期后修正未清理");
                    context.complete();
                }
            });
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void combatProtocolLightStepMovesAlongInputAndEntityFacing(TestContext context) {
        // 检查方向与发给本人的速度，再用客户端移动回报验证服务器接受该位移。
        var right = prepared(context);
        var forward = prepared(context);
        var players = List.of(right, forward);
        positionForStep(context, right, 0);
        positionForStep(context, forward, STEP_LANE_SPACING);
        Vec3d rightStart = right.player.getPos();
        Vec3d forwardStart = forward.player.getPos();
        guarded(players, () -> {
            learnScroll(context, right, BASIC + "light_step_volume");
            learnScroll(context, forward, BASIC + "light_step_volume");
            press(right, "light_step", new Vec3d(1, 0, 0));
            press(forward, "light_step", Vec3d.ZERO);
        });
        later(context, STEP_TICKS, players, () -> {
            try (right; forward) {
                context.assertTrue(right.player.getVelocity().x > 0, "轻步未沿输入方向");
                context.assertTrue(forward.player.getVelocity().z > 0, "零输入未沿实体朝向");
                Vec3d rightVelocity = right.receiveVelocityAndMove();
                Vec3d forwardVelocity = forward.receiveVelocityAndMove();
                context.assertTrue(rightVelocity.x > 0 && rightVelocity.y > 0, "本人速度包丢失水平或竖直分量");
                context.assertTrue(forwardVelocity.z > 0, "本人速度包未保留朝向回退");
                context.assertTrue(right.player.getX() > rightStart.x, "输入方向未产生实际位移");
                context.assertTrue(forward.player.getZ() > forwardStart.z, "实体朝向未产生实际位移");
                context.assertTrue(right.player.getY() > rightStart.y, "轻步缺少向上位移");
                context.complete();
            }
        });
    }

    private static void positionForStep(TestContext context, BasicTestPlayer fixture, int laneOffset) {
        // 独立空中通道避免随机登录位置碰到场地方块；正式传送包仍由移动夹具确认。
        var relative = new BlockPos(1 + laneOffset, STEP_HEIGHT, 1);
        Vec3d position = Vec3d.ofBottomCenter(context.getAbsolutePos(relative));
        fixture.player.networkHandler.requestTeleport(position.x, position.y, position.z, 0, 0);
    }

    static BasicTestPlayer prepared(TestContext context) {
        // 管理操作只准备资源，后续学习、施放与身体结算使用正式路径。
        var fixture = new BasicTestPlayer(context);
        guarded(List.of(fixture), () -> {
            var body = fixture.body();
            long now = fixture.player.getServer().getOverworld().getTime() * MILLIS_PER_TICK;
            context.assertTrue(body.completeOpening(now, "all") == FailureCode.FAILURE_CODE_UNSPECIFIED, "开拓失败");
            var fill = DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS).build();
            context.assertTrue(body.debugQi(now, fill) == FailureCode.FAILURE_CODE_UNSPECIFIED, "补元失败");
        });
        return fixture;
    }

    static void learnScroll(TestContext context, BasicTestPlayer fixture, String volume) {
        // 创造玩家使用正式物品且保持数量，学习结果由独立断言检查。
        var item = TechniqueScrollItems.scrolls().get(new Identifier(volume + "_scroll"));
        context.assertTrue(item != null, "卷轴未登记：" + volume);
        var stack = new ItemStack(item, STACK_SIZE);
        fixture.player.setStackInHand(Hand.MAIN_HAND, stack);
        ActionResult result = fixture.player.interactionManager.interactItem(
                fixture.player, fixture.player.getWorld(), stack, Hand.MAIN_HAND);
        context.assertTrue(result == ActionResult.CONSUME, "卷轴交互失败：" + volume);
        context.assertTrue(stack.getCount() == STACK_SIZE, "创造模式错误消耗卷轴");
    }

    private static void press(BasicTestPlayer fixture, String path, Vec3d direction) {
        // 直接使用正式 Protobuf 和战斗通道，不复制网络协议或绕过接收器。
        var request = TechniquePress.newBuilder()
                .setTechniqueId(BASIC + path)
                .setMoveX((float) direction.x)
                .setMoveZ((float) direction.z)
                .build();
        fixture.press(request);
    }

    private static void assertStrength(TestContext context, List<BasicTestPlayer> players, double expected) {
        // 对照身体抵消正常结算影响，倍率来自 Damage 公开肌肉规则。
        double ratio = strength(players.get(0)) / strength(players.get(1));
        context.assertTrue(Math.abs(ratio - expected) < EPSILON,
                "战斗载荷蓄力力量倍率不符：" + ratio + "，预期 " + expected);
    }

    private static double strength(BasicTestPlayer fixture) {
        // 在主世界权威时钟结算后读取相同手臂与握持方式。
        var body = fixture.body();
        body.settle(fixture.player.getServer().getOverworld().getTime() * MILLIS_PER_TICK);
        var muscle = DamagePreferences.parse("hp_per_vanilla_hp = 1").muscle();
        return muscle.plan(BodyProtobuf.toMessage(body.snapshot()),
                MuscleRules.Arm.RIGHT, MuscleRules.Grip.MAIN_HAND).coefficient();
    }

    private static void later(TestContext context, int ticks, List<BasicTestPlayer> players, Runnable action) {
        // 任一延迟断言失败立即断线，避免下一条测试继承在线玩家。
        context.waitAndRun(ticks, () -> guarded(players, action));
    }

    private static void guarded(List<BasicTestPlayer> players, Runnable action) {
        // 初始化与延迟执行共用清理边界，保留原始异常供 GameTest 报告。
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            players.forEach(BasicTestPlayer::close);
            throw failure;
        }
    }
}
