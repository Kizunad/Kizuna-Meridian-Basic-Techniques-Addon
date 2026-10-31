package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Qi;
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
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.kizuna.meridian.human.fabric.BasicGameTestTicks.DEFAULT_TIMEOUT;

/** 正式 Human 玩家、功法服务和 Damage Mixin 的联合验证。 */
public final class BasicTechniquesGameTest {
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
    private static final double GAS_DENSITY = 0.2;
    private static final double GAS_FILL_RATIO = 0.5;
    private static final double QI_EPSILON = 0.000001;
    private static final double BREATH_QI_COST = 10;
    private static final double BUFF_QI_COST = 15;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void discoversCatalogAndLearnsEachIndependentVolume(GameTestHelper context) {
        // 清单发现使用 Fabric 加载器，学习提交使用正式在线玩家宿主。
        try (var fixture = prepared(context)) {
            var service = TechniqueFabric.service();
            context.assertTrue(service.registry().sources().stream().anyMatch(source -> source.source().equals(
                    "meridian_basic_techniques:data/meridian_basic_techniques/technique_catalog.txtpb")),
                    "没有通过真实清单发现目录");
            int learnedCount = 0;
            for (String path : new String[]{"charge", "breath", "light_step"}) {
                var learned = service.learn(fixture.player.getUUID(), PREFIX + path + "_volume");
                learnedCount++;
                context.assertTrue(learned.size() == learnedCount, "独立卷没有新增一式：" + path);
                context.assertTrue(learned.stream().anyMatch(item -> item.technique().getId().equals(PREFIX + path)),
                        "完整已学列表缺少本卷功法：" + path);
            }
            context.assertTrue(service.known(fixture.player.getUUID()).size() == TECHNIQUE_COUNT, "三式未写入身体");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void allTechniquesStartAndPayWithOnlyTrueQi(GameTestHelper context) {
        // 内腔只留真炁，三式均须经正式玩家宿主起手，并只扣声明的整式费用。
        var costs = Map.of("breath", BREATH_QI_COST, "light_step", BUFF_QI_COST, "charge", BUFF_QI_COST);
        for (var entry : costs.entrySet()) {
            String path = entry.getKey();
            double expectedCost = entry.getValue();
            try (var fixture = prepared(context)) {
                fillTrueQi(context, fixture);
                TechniqueFabric.service().learn(fixture.player.getUUID(), PREFIX + path + "_volume");
                // 全身总量包含扩散在途，内腔转运不能误计为功法费用。
                double before = fixture.body().snapshot().totalQi();
                context.assertTrue(before > expectedCost, "真炁夹具余额不足");
                start(context, fixture, path, Vec3.ZERO);
                var after = fixture.body().snapshot();
                double spent = before - after.totalQi();
                context.assertTrue(Math.abs(spent - expectedCost) < QI_EPSILON,
                        "整式真元扣费不符：" + path + "，实际 " + spent);
                context.assertTrue(after.meridianStates().stream()
                                .flatMap(state -> state.lumenQi().entries().stream())
                                .allMatch(qi -> qi.phase() == Qi.QiPhase.GAS
                                        && Math.abs(qi.density() - GAS_DENSITY) < QI_EPSILON),
                        "扣费改变了剩余真炁的相态或密度");
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = CHARGE_TEST_TICKS)
    public void chargeCurveChangesHumanStrengthAndSameSwordDamage(GameTestHelper context) {
        // 两个玩家同步持同一型号剑，消除相同做功疲劳对曲线倍率的干扰。
        var boosted = prepared(context);
        var control = prepared(context);
        boosted.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        control.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        var pair = new ChargePair(boosted, control);
        TechniqueFabric.service().learn(boosted.player.getUUID(), PREFIX + "charge_volume");
        later(context, PREPARE_TICKS, pair.players(), () -> {
            start(context, boosted, "charge", Vec3.ZERO);
            sampleCharge(context, pair);
        });
    }

    private static void sampleCharge(GameTestHelper context, ChargePair pair) {
        // 取峰值、线性回落与虚脱平台，最后检查曲线到期后的自然恢复。
        later(context, PEAK_TICKS, pair.players(), () -> assertCharge(context, pair, BOOST));
        later(context, DECLINE_TICKS, pair.players(), () -> assertCharge(context, pair, DECLINE));
        later(context, COLLAPSE_TICKS, pair.players(), () -> assertCharge(context, pair, COLLAPSE));
        later(context, EXPIRE_TICKS, pair.players(), () -> {
            try (var boosted = pair.boosted(); var control = pair.control()) {
                assertCharge(context, pair, 1);
                context.assertTrue(boosted.body().snapshot().modifiers().isEmpty(), "到期修正没有清理");
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void lightStepGrantsQuietStatusInsteadOfImmediateImpulse(GameTestHelper context) {
        // 施放输入不再直接推人；两种请求都在起手结束得到相同的轻身状态。
        var right = prepared(context);
        var forward = prepared(context);
        forward.player.setYRot(0);
        TechniqueFabric.service().learn(right.player.getUUID(), PREFIX + "light_step_volume");
        TechniqueFabric.service().learn(forward.player.getUUID(), PREFIX + "light_step_volume");
        start(context, right, "light_step", new Vec3(1, 0, 0));
        start(context, forward, "light_step", Vec3.ZERO);
        later(context, STEP_TICKS, List.of(right, forward), () -> {
            try (right; forward) {
                var effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(PREFIX + "light_body")).orElseThrow();
                for (var fixture : List.of(right, forward)) {
                    var status = fixture.player.getEffect(effect);
                    context.assertTrue(status != null && !status.isVisible() && status.isAmbient(),
                            "轻身缺失或显示了粒子");
                    context.assertTrue(status.showIcon(), "轻身图标被隐藏");
                    context.assertTrue(fixture.player.getDeltaMovement().lengthSqr() == 0, "施放仍产生冲量");
                }
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = BREATH_TEST_TICKS)
    public void breathAcceleratesIdleArmFatigueRecovery(GameTestHelper context) {
        // 两具正式身体施加相同做功，只有一具接受调息，比较权威组织疲劳差值。
        var boosted = prepared(context);
        var control = prepared(context);
        TechniqueFabric.service().learn(boosted.player.getUUID(), PREFIX + "breath_volume");
        start(context, boosted, "breath", Vec3.ZERO);
        later(context, BREATH_TICKS, List.of(boosted, control), () -> {
            exertArm(context, boosted);
            exertArm(context, control);
            later(context, RECOVERY_TICKS, List.of(boosted, control), () -> {
                try (boosted; control) {
                    boosted.body().settle(now(boosted));
                    control.body().settle(now(control));
                    context.assertTrue(armFatigue(boosted) < armFatigue(control), "调息未加速手臂疲劳恢复");
                    context.succeed();
                }
            });
        });
    }

    private static BasicTestPlayer prepared(GameTestHelper context) {
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

    private static void fillTrueQi(GameTestHelper context, BasicTestPlayer fixture) {
        // 管理员精确库存入口替换全部内腔，保证没有游离态余额可替三式支付。
        var body = fixture.body();
        double volume = body.snapshot().structure().meridians().stream()
                .mapToDouble(meridian -> meridian.lumenVolume()).sum();
        var fill = DebugQi.newBuilder()
                .setMode(DebugQiMode.SET_INVENTORY)
                .setAmount(volume * GAS_FILL_RATIO * GAS_DENSITY)
                .setDensity(GAS_DENSITY)
                .build();
        context.assertTrue(body.debugQi(now(fixture), fill) == FailureCode.FAILURE_CODE_UNSPECIFIED,
                "填充真炁失败");
    }

    private static void start(GameTestHelper context, BasicTestPlayer fixture, String path, Vec3 input) {
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
                .setBodyId(fixture.player.getStringUUID())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .setDirection(direction)
                .build();
        var outcome = TechniqueFabric.service().cast(fixture.player.getUUID(), request);
        context.assertTrue(outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "施放失败：" + outcome);
    }

    private static void assertCharge(GameTestHelper context, ChargePair pair, double expected) {
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

    private static double strike(GameTestHelper context, BasicTestPlayer fixture) {
        // 每次使用新的无护甲牛，避免无敌帧、旧伤和目标抗性影响实伤。
        Cow target = EntityTypes.COW.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (target == null) {
            throw new IllegalStateException("无法创建伤害目标");
        }
        target.setNoAi(true /* aiDisabled */);
        target.snapTo(fixture.player.getX(), fixture.player.getY(), fixture.player.getZ() + 1, 0, 0);
        context.getLevel().addFreshEntity(target);
        float before = target.getHealth();
        fixture.player.attack(target);
        double loss = before - target.getHealth();
        target.discard();
        context.assertTrue(loss > 0, "原版剑攻击未造成伤害");
        return loss;
    }

    private static void exertArm(GameTestHelper context, BasicTestPlayer fixture) {
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

    private static void later(GameTestHelper context, int ticks, List<BasicTestPlayer> players, Runnable action) {
        // 延迟断言失败时也释放真实在线玩家，避免跨测试保留宿主认领。
        context.runAfterDelay(ticks, () -> {
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
        return fixture.player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
    }
}
