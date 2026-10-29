package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissues;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.vanilla.DamagePreferences;
import dev.kizuna.meridian.vanilla.MuscleRules;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/** 正式 Human 玩家、功法服务和 Damage Mixin 的联合验证。 */
public final class BasicTechniquesGameTest implements FabricGameTest {
    private static final String PREFIX = "meridian_basic_techniques:";
    private static final long MILLIS_PER_TICK = 50;
    private static final int TICKS_PER_SECOND = 20;
    private static final int CHARGE_TEST_TICKS = 300;
    private static final int BREATH_TEST_TICKS = 140;
    private static final int TECHNIQUE_COUNT = 3;
    private static final int PREPARE_TICKS = 25;
    private static final int PEAK_TICKS = 65;
    private static final int DECLINE_TICKS = 107;
    private static final int COLLAPSE_TICKS = 160;
    private static final int EXPIRE_TICKS = 237;
    private static final int STEP_TICKS = 7;
    private static final int BREATH_TICKS = 70;
    private static final int RECOVERY_TICKS = 20;
    private static final double BOOST = 1.3;
    private static final double DECLINE = 1.15;
    private static final double COLLAPSE = 0.85;
    private static final double EPSILON = 0.025;
    private static final double DAMAGE_EPSILON = 0.05;
    private static final double HORIZONTAL_METERS_PER_SECOND = 6;
    private static final double VERTICAL_METERS_PER_SECOND = 4;
    private static final double HORIZONTAL_SPEED = HORIZONTAL_METERS_PER_SECOND / TICKS_PER_SECOND;
    private static final double VERTICAL_SPEED = VERTICAL_METERS_PER_SECOND / TICKS_PER_SECOND;
    private static final double FATIGUE_POWER = 100;
    private static final double FATIGUE_SECONDS = 2;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void discoversCatalogAndLearnsEachIndependentVolume(TestContext context) {
        // 清单发现使用 Fabric 加载器，学习提交使用正式在线玩家宿主。
        try (var fixture = prepared(context)) {
            var service = TechniqueFabric.service();
            context.assertTrue(service.registry().sources().stream().anyMatch(source -> source.source().equals(
                    "meridian_basic_techniques:data/meridian_basic_techniques/technique_catalog.txtpb")),
                    "没有通过真实清单发现目录");
            int learnedCount = 0;
            for (String path : new String[]{"charge", "breath", "light_step"}) {
                var learned = service.learn(fixture.player.getUuid(), PREFIX + path + "_volume");
                learnedCount++;
                context.assertTrue(learned.size() == learnedCount, "独立卷没有新增一式：" + path);
                context.assertTrue(learned.stream().anyMatch(item -> item.technique().getId().equals(PREFIX + path)),
                        "完整已学列表缺少本卷功法：" + path);
            }
            context.assertTrue(service.known(fixture.player.getUuid()).size() == TECHNIQUE_COUNT, "三式未写入身体");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = CHARGE_TEST_TICKS)
    public void chargeCurveChangesHumanStrengthAndSameSwordDamage(TestContext context) {
        // 两个玩家同步持同一型号剑，消除相同做功疲劳对曲线倍率的干扰。
        var boosted = prepared(context);
        var control = prepared(context);
        boosted.player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        control.player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        var pair = new ChargePair(boosted, control);
        TechniqueFabric.service().learn(boosted.player.getUuid(), PREFIX + "charge_volume");
        later(context, PREPARE_TICKS, pair.players(), () -> {
            start(context, boosted, "charge", Vec3d.ZERO);
            sampleCharge(context, pair);
        });
    }

    private static void sampleCharge(TestContext context, ChargePair pair) {
        // 取峰值、线性回落与虚脱平台，最后检查曲线到期后的自然恢复。
        later(context, PEAK_TICKS, pair.players(), () -> assertCharge(context, pair, BOOST));
        later(context, DECLINE_TICKS, pair.players(), () -> assertCharge(context, pair, DECLINE));
        later(context, COLLAPSE_TICKS, pair.players(), () -> assertCharge(context, pair, COLLAPSE));
        later(context, EXPIRE_TICKS, pair.players(), () -> {
            try (var boosted = pair.boosted(); var control = pair.control()) {
                assertCharge(context, pair, 1);
                context.assertTrue(boosted.body().snapshot().modifiers().isEmpty(), "到期修正没有清理");
                context.complete();
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void lightStepUsesInputAndZeroInputFacesForward(TestContext context) {
        // 同一正式冲量宿主分别验证世界水平输入与零输入的实体朝向回退。
        var right = prepared(context);
        var forward = prepared(context);
        forward.player.setYaw(0);
        TechniqueFabric.service().learn(right.player.getUuid(), PREFIX + "light_step_volume");
        TechniqueFabric.service().learn(forward.player.getUuid(), PREFIX + "light_step_volume");
        start(context, right, "light_step", new Vec3d(1, 0, 0));
        start(context, forward, "light_step", Vec3d.ZERO);
        later(context, STEP_TICKS, List.of(right, forward), () -> {
            try (right; forward) {
                context.assertTrue(right.player.getVelocity().x > 0, "轻步未沿输入方向");
                context.assertTrue(forward.player.getVelocity().z > 0, "零输入未沿实体朝向");
                context.assertTrue(right.player.getVelocity().y > 0, "轻步缺少向上冲量");
                context.assertTrue(right.player.getVelocity().x <= HORIZONTAL_SPEED + EPSILON,
                        "水平冲量单位错误");
                context.assertTrue(right.player.getVelocity().y <= VERTICAL_SPEED + EPSILON,
                        "向上冲量单位错误");
                context.complete();
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = BREATH_TEST_TICKS)
    public void breathAcceleratesIdleArmFatigueRecovery(TestContext context) {
        // 两具正式身体施加相同做功，只有一具接受调息，比较权威组织疲劳差值。
        var boosted = prepared(context);
        var control = prepared(context);
        TechniqueFabric.service().learn(boosted.player.getUuid(), PREFIX + "breath_volume");
        start(context, boosted, "breath", Vec3d.ZERO);
        later(context, BREATH_TICKS, List.of(boosted, control), () -> {
            exertArm(context, boosted);
            exertArm(context, control);
            later(context, RECOVERY_TICKS, List.of(boosted, control), () -> {
                try (boosted; control) {
                    boosted.body().settle(now(boosted));
                    control.body().settle(now(control));
                    context.assertTrue(armFatigue(boosted) < armFatigue(control), "调息未加速手臂疲劳恢复");
                    context.complete();
                }
            });
        });
    }

    private static BasicTestPlayer prepared(TestContext context) {
        // 管理开拓和补元只初始化测试，学习、施放、效果和伤害均走正式生产入口。
        var fixture = new BasicTestPlayer(context);
        try {
            var body = fixture.body();
            long now = now(fixture);
            context.assertTrue(body.completeOpening(now, "all")
                    == FailureCode.FAILURE_CODE_UNSPECIFIED, "开拓失败");
            var fill = DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS).build();
            context.assertTrue(body.debugQi(now, fill)
                    == FailureCode.FAILURE_CODE_UNSPECIFIED, "补元失败");
            return fixture;
        } catch (RuntimeException | Error failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void start(TestContext context, BasicTestPlayer fixture, String path, Vec3d input) {
        // 请求只传精确功法引用和方向，不覆盖身体、费用或实体朝向。
        var reference = TechniqueReference.newBuilder()
                .setId(PREFIX + path)
                .setVersion(1)
                .build();
        var direction = HorizontalDirection.newBuilder()
                .setX(input.x)
                .setZ(input.z)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(fixture.player.getUuidAsString())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .setDirection(direction)
                .build();
        var outcome = TechniqueFabric.service().cast(fixture.player.getUuid(), request);
        context.assertTrue(outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "施放失败：" + outcome);
    }

    private static void assertCharge(TestContext context, ChargePair pair, double expected) {
        // 有效力量由 Framework 计算，实伤由同步挥剑的两个玩家直接测量。
        double ratio = strength(pair.boosted()) / strength(pair.control());
        context.assertTrue(Math.abs(ratio - expected) < EPSILON,
                "有效力量不符：" + ratio + "，预期 " + expected);
        double boostedDamage = strike(context, pair.boosted());
        double controlDamage = strike(context, pair.control());
        double damageRatio = boostedDamage / controlDamage;
        context.assertTrue(Math.abs(damageRatio - expected) < DAMAGE_EPSILON,
                "相同剑实伤倍率不符：" + damageRatio + "，预期 " + expected);
    }

    private static double strength(BasicTestPlayer fixture) {
        // 每次采样先结算同一个正式身体，保留正常攻击带来的疲劳。
        var body = fixture.body();
        body.settle(now(fixture));
        var muscle = DamagePreferences.parse("hp_per_vanilla_hp = 1").muscle();
        var plan = muscle.plan(BodyProtobuf.toMessage(body.snapshot()),
                MuscleRules.Arm.RIGHT, MuscleRules.Grip.MAIN_HAND);
        return plan.coefficient();
    }

    private static double strike(TestContext context, BasicTestPlayer fixture) {
        // 每次使用新的无护甲牛，避免无敌帧、旧伤和目标抗性影响实伤。
        CowEntity target = EntityType.COW.create(context.getWorld());
        if (target == null) {
            throw new IllegalStateException("无法创建伤害目标");
        }
        target.setAiDisabled(true /* aiDisabled */);
        target.refreshPositionAndAngles(fixture.player.getX(), fixture.player.getY(), fixture.player.getZ() + 1, 0, 0);
        context.getWorld().spawnEntity(target);
        float before = target.getHealth();
        fixture.player.attack(target);
        double loss = before - target.getHealth();
        target.discard();
        context.assertTrue(loss > 0, "原版剑攻击未造成伤害");
        return loss;
    }

    private static void exertArm(TestContext context, BasicTestPlayer fixture) {
        // 疲劳只能由 Framework 普通做功入口产生，不直接改写组织状态。
        var host = fixture.body();
        long now = now(fixture);
        host.settle(now);
        var body = host.snapshot();
        var arm = body.structure().tissues().stream()
                .filter(item -> item.tags().stream().anyMatch(tag -> tag.value().equals("human:arm_muscle")))
                .findFirst().orElseThrow();
        var load = ExertTissue.newBuilder()
                .setTissueId(arm.id().value())
                .setForceNewtons(0)
                .setPowerWatts(FATIGUE_POWER)
                .setDurationSeconds(FATIGUE_SECONDS)
                .build();
        var loads = ExertTissues.newBuilder().addLoads(load).build();
        var command = BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision())
                .setExertTissues(loads)
                .build();
        var request = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command)
                .build();
        context.assertTrue(host.applyExertion(now, request).hasCommandCommitted(), "做功失败");
    }

    private static double armFatigue(BasicTestPlayer fixture) {
        // 只比较双臂疲劳总量，腿部和呼吸肌不混入恢复判定。
        var body = BodyProtobuf.toMessage(fixture.body().snapshot());
        return body.getStructure().getTissuesList().stream()
                .filter(item -> item.getTagsList().contains("human:arm_muscle"))
                .mapToDouble(definition -> body.getTissueStatesList().stream()
                        .filter(state -> state.getId().equals(definition.getId()))
                        .findFirst().orElseThrow().getFatigue()).sum();
    }

    private static void later(TestContext context, int ticks, List<BasicTestPlayer> players, Runnable action) {
        // 延迟断言失败时也释放真实在线玩家，避免跨测试保留宿主认领。
        context.waitAndRun(ticks, () -> {
            try {
                action.run();
            } catch (RuntimeException | Error failure) {
                players.forEach(BasicTestPlayer::close);
                throw failure;
            }
        });
    }

    private record ChargePair(BasicTestPlayer boosted, BasicTestPlayer control) {
        List<BasicTestPlayer> players() {
            // 共用清理列表，保证任意采样断言失败时两端同时退出。
            return List.of(boosted, control);
        }
    }

    private static long now(BasicTestPlayer fixture) {
        // 功法与身体共用主世界累计 tick 时钟。
        return fixture.player.getServer().getOverworld().getTime() * MILLIS_PER_TICK;
    }
}
