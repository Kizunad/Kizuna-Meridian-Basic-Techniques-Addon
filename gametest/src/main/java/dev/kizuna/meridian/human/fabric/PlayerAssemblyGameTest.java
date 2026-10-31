package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.vanilla.DamagePreferences;
import dev.kizuna.meridian.vanilla.MuscleRules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.kizuna.meridian.human.fabric.BasicGameTestTicks.DEFAULT_TIMEOUT;

/** 完整装配的玩家入口：创造栏、卷轴学习、战斗载荷与真实身体效果。 */
public final class PlayerAssemblyGameTest {
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
    private static final int STEP_TICKS = 12;
    private static final int STEP_HEIGHT = 4;
    private static final int STEP_LANE_SPACING = 2;
    private static final double PEAK_STRENGTH = 1.3;
    private static final double DECLINE_STRENGTH = 1.15;
    private static final double COLLAPSE_STRENGTH = 0.85;
    private static final double EPSILON = 0.025;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void creativeTechniqueGroupAndScrollsLearnBasicAndUpperVolume(GameTestHelper context) {
        // 创造栏内容来自正式注册表；学习必须通过玩家物品交互写入同一个身体。
        var group = TechniqueScrollItems.group();
        var world = context.getLevel();
        context.assertTrue(BuiltInRegistries.CREATIVE_MODE_TAB.getValue(TechniqueScrollItems.GROUP_ID) == group,
                "功法栏未注册");
        context.assertTrue(group.getDisplayName().equals(
                Component.translatable("itemGroup.meridian_technique.techniques")), "功法栏标题键错误");
        group.buildContents(new CreativeModeTab.ItemDisplayParameters(world.enabledFeatures(),
                true /* hasPermissions */, world.registryAccess()));
        var displayed = group.getDisplayItems().stream()
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .collect(Collectors.toSet());
        var searchable = group.getSearchTabDisplayItems().stream()
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .collect(Collectors.toSet());
        for (String volume : VOLUMES) {
            String scrollId = volume + "_scroll";
            context.assertTrue(displayed.contains(scrollId) && searchable.contains(scrollId), "创造栏缺卷：" + volume);
        }
        try (var fixture = prepared(context)) {
            for (String volume : VOLUMES) {
                learnScroll(context, fixture, volume);
            }
            var actual = TechniqueFabric.service().known(fixture.player.getUUID()).stream()
                    .map(item -> item.technique().getId())
                    .collect(Collectors.toSet());
            context.assertTrue(actual.equals(LEARNED), "卷轴学习结果不符：" + actual);
        }
        context.succeed();
    }

    @GameTest(maxTicks = CHARGE_LIMIT)
    public void combatProtocolChargeFollowsHumanStrengthCurve(GameTestHelper context) {
        // 等待登录后的身体结算稳定，再从真实战斗接收器提交蓄力。
        var boosted = prepared(context);
        var control = prepared(context);
        var players = List.of(boosted, control);
        guarded(players, () -> learnScroll(context, boosted, BASIC + "charge_volume"));
        later(context, PREPARE_TICKS, players, () -> {
            press(boosted, "charge", Vec3.ZERO);
            later(context, PEAK_TICKS, players, () -> assertStrength(context, players, PEAK_STRENGTH));
            later(context, DECLINE_TICKS, players, () -> assertStrength(context, players, DECLINE_STRENGTH));
            later(context, COLLAPSE_TICKS, players, () -> assertStrength(context, players, COLLAPSE_STRENGTH));
            later(context, EXPIRE_TICKS, players, () -> {
                try (boosted; control) {
                    assertStrength(context, players, 1);
                    context.assertTrue(boosted.body().snapshot().modifiers().isEmpty(), "到期后修正未清理");
                    context.succeed();
                }
            });
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void combatProtocolLightStepGrantsStatusThenJumpChoosesFacing(GameTestHelper context) {
        // 战斗 UI 施放先给予状态，后续跳跃请求才使用当前视线求初速度。
        var right = prepared(context);
        var forward = prepared(context);
        var players = List.of(right, forward);
        positionForStep(context, right, 0);
        positionForStep(context, forward, STEP_LANE_SPACING);
        Vec3 rightStart = right.player.position();
        Vec3 forwardStart = forward.player.position();
        guarded(players, () -> {
            learnScroll(context, right, BASIC + "light_step_volume");
            learnScroll(context, forward, BASIC + "light_step_volume");
            press(right, "light_step", new Vec3(1, 0, 0));
            press(forward, "light_step", Vec3.ZERO);
        });
        later(context, STEP_TICKS, players, () -> {
            try (right; forward) {
                right.player.setYRot(-90);
                right.player.setOnGround(true /* onGround */);
                forward.player.setOnGround(true /* onGround */);
                var rightResult = TechniqueLeaps.jump(right.player);
                var forwardResult = TechniqueLeaps.jump(forward.player);
                context.assertTrue(rightResult == TechniqueLeaps.Jump.LEAP,
                        "轻步状态没有接通跳跃");
                context.assertTrue(forwardResult == rightResult, "无输入施放未得到相同能力");
                context.assertTrue(right.player.getDeltaMovement().horizontalDistanceSqr() > 0
                        && right.player.getDeltaMovement().y > 0, "跃步没有水平和向上的初速度");
                context.assertTrue(forward.player.getDeltaMovement().horizontalDistanceSqr() > 0,
                        "零输入施放没有跃步能力");
                context.succeed();
            }
        });
    }

    private static void positionForStep(GameTestHelper context, BasicTestPlayer fixture, int laneOffset) {
        // 独立空中通道避免随机登录位置碰到场地方块；正式传送包仍由移动夹具确认。
        var relative = new BlockPos(1 + laneOffset, STEP_HEIGHT, 1);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relative));
        fixture.player.connection.teleport(position.x, position.y, position.z, 0, 0);
    }

    static BasicTestPlayer prepared(GameTestHelper context) {
        // 管理操作只准备资源，后续学习、施放与身体结算使用正式路径。
        var fixture = new BasicTestPlayer(context);
        guarded(List.of(fixture), () -> {
            var body = fixture.body();
            long now = fixture.player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
            context.assertTrue(body.completeOpening(now, "all") == FailureCode.FAILURE_CODE_UNSPECIFIED, "开拓失败");
            var fill = DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS).build();
            context.assertTrue(body.debugQi(now, fill) == FailureCode.FAILURE_CODE_UNSPECIFIED, "补元失败");
        });
        return fixture;
    }

    static void learnScroll(GameTestHelper context, BasicTestPlayer fixture, String volume) {
        // 创造玩家使用正式物品且保持数量，学习结果由独立断言检查。
        // 26.2 的 ItemStack.use 把立即生效的成功结果重新包成带手持物的成功对象，所以按不挥手的服务端成功比较，
        // 不比对象身份。
        var item = TechniqueScrollItems.scrolls().get(Identifier.parse(volume + "_scroll"));
        context.assertTrue(item != null, "卷轴未登记：" + volume);
        var stack = new ItemStack(item, STACK_SIZE);
        fixture.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InteractionResult result = fixture.player.gameMode.useItem(
                fixture.player, fixture.player.level(), stack, InteractionHand.MAIN_HAND);
        context.assertTrue(result instanceof InteractionResult.Success success
                && success.swingSource() == InteractionResult.SwingSource.NONE, "卷轴交互失败：" + volume);
        context.assertTrue(stack.getCount() == STACK_SIZE, "创造模式错误消耗卷轴");
    }

    private static void press(BasicTestPlayer fixture, String path, Vec3 direction) {
        // 直接使用正式 Protobuf 和战斗通道，不复制网络协议或绕过接收器。
        var request = TechniquePress.newBuilder()
                .setTechniqueId(BASIC + path)
                .setMoveX((float) direction.x)
                .setMoveZ((float) direction.z)
                .build();
        fixture.press(request);
    }

    private static void assertStrength(GameTestHelper context, List<BasicTestPlayer> players, double expected) {
        // 对照身体抵消正常结算影响，倍率来自 Damage 公开肌肉规则。
        double ratio = strength(players.get(0)) / strength(players.get(1));
        context.assertTrue(Math.abs(ratio - expected) < EPSILON,
                "战斗载荷蓄力力量倍率不符：" + ratio + "，预期 " + expected);
    }

    private static double strength(BasicTestPlayer fixture) {
        // 在主世界权威时钟结算后读取相同手臂与握持方式。
        var body = fixture.body();
        body.settle(fixture.player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK);
        var muscle = DamagePreferences.parse("hp_per_vanilla_hp = 1").muscle();
        return muscle.plan(BodyProtobuf.toMessage(body.snapshot()),
                MuscleRules.Arm.RIGHT, MuscleRules.Grip.MAIN_HAND).coefficient();
    }

    private static void later(GameTestHelper context, int ticks, List<BasicTestPlayer> players, Runnable action) {
        // 任一延迟断言失败立即断线，避免下一条测试继承在线玩家。
        context.runAfterDelay(ticks, () -> guarded(players, action));
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
