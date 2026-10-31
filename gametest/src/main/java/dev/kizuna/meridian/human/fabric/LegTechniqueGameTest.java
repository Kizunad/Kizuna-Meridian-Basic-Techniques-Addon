package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissues;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.human.fabric.BasicGameTestTicks.DEFAULT_TIMEOUT;

/** 正式步行、疾跑与施放产生疲劳，六块腿肌共同做功并拒绝超出预算的活动。 */
public final class LegTechniqueGameTest {
    private static final String STEP = "meridian_basic_techniques:light_step";
    private static final String RAM = "meridian_door_breaker:ram";
    private static final String LEG_TAG = "human:leg_muscle";
    private static final int WALK_TICKS = 1200;
    private static final int SPRINT_TICKS = 300;
    private static final int MOTION_TICKS = WALK_TICKS + SPRINT_TICKS;
    private static final int TEST_TIMEOUT = 2400;
    private static final int CAST_COUNT = 3;
    private static final int WORKING_LEG_COUNT = 6;
    private static final int COOLDOWN_MARGIN = 2;
    private static final int PLAYER_HEIGHT = 2;
    private static final long MILLIS_PER_TICK = 50;
    private static final double WALK_DELTA = 0.1;
    private static final double MOTION_MINIMUM_FATIGUE = 35;
    private static final double HEAVY_FATIGUE_RATIO = 0.8;
    private static final double INJURY_RATIO = 0.9;
    private static final double BURST_FRACTION = 0.25;

    @GameTest(maxTicks = TEST_TIMEOUT)
    public void lightStepAfterWalkingAndSprintingCanCastAndExertThreeTimes(GameTestHelper context) {
        // 真实走六十秒再疾跑十五秒，每次施放结束后还要支付一次跃步做功。
        walkThenCast(context, STEP);
    }

    @GameTest(maxTicks = TEST_TIMEOUT)
    public void ramAfterWalkingAndSprintingCanCastThreeTimes(GameTestHelper context) {
        // 相同运动负载后连续三次撞门，期间不治疗、不补体力或更换身体。
        walkThenCast(context, RAM);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void exhaustedLegsRejectBothTechniques(GameTestHelper context) {
        // 通过普通做功消耗八成耐力；绑定仍成功，但实际功率预算不足必须拒绝。
        for (String technique : List.of(STEP, RAM)) {
            try (var fixture = prepared(context, technique)) {
                exhaust(context, fixture);
                assertWorkRejected(context, fixture, technique);
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void seriouslyInjuredLegsRejectBothTechniques(GameTestHelper context) {
        // 保留一成组织 HP，绑定仍成功，伤势压低的做功预算负责拒绝。
        for (String technique : List.of(STEP, RAM)) {
            try (var fixture = prepared(context, technique)) {
                injure(context, fixture);
                assertWorkRejected(context, fixture, technique);
            }
        }
        context.succeed();
    }

    private static void walkThenCast(GameTestHelper context, String technique) {
        // 位置采样持续往返，后十五秒同时设置疾跑标记，让宿主分别记入两种运动负载。
        var fixture = prepared(context, technique);
        Vec3 origin = fixture.player.position();
        for (int tick = 1; tick <= MOTION_TICKS; tick++) {
            int step = tick;
            later(context, tick, fixture, () -> {
                double offset = 0;
                if (step % 2 == 1) {
                    offset = WALK_DELTA;
                }
                fixture.player.setPos(origin.add(offset, 0, 0));
                fixture.player.setSprinting(step > WALK_TICKS);
                fixture.player.setOnGround(true /* onGround */);
            });
        }
        later(context, MOTION_TICKS + COOLDOWN_MARGIN, fixture, () -> {
            fixture.player.setSprinting(false /* sprinting */);
            var host = fixture.body();
            host.observe(now(fixture), HumanPlayerBody.Motion.IDLE);
            var body = host.snapshot();
            double fatigue = body.tissueStates().stream()
                    .filter(tissue -> tissue.id().value().equals("left_rectus_femoris"))
                    .findFirst().orElseThrow().fatigue();
            context.assertTrue(fatigue > MOTION_MINIMUM_FATIGUE, "步行和疾跑没有实际积累腿肌疲劳：" + fatigue);
            repeat(context, fixture, technique, 0);
        });
    }

    private static void repeat(GameTestHelper context, BasicTestPlayer fixture, String technique, int completed) {
        // 冷却从收势结束计算；每次沿同一身体继续消费，不能换零疲劳夹具。
        TechniqueFailure failure = cast(fixture, technique);
        context.assertTrue(failure == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "连续第 " + (completed + 1) + " 次失败：" + technique + " " + failure);
        var definition = TechniqueFabric.service().registry().findLatest(technique).orElseThrow();
        var phases = definition.getPhases();
        long duration = phases.getWarmupMillis() + phases.getActiveMillis() + phases.getRecoveryMillis();
        long finishDelay = duration / MILLIS_PER_TICK + COOLDOWN_MARGIN;
        later(context, finishDelay, fixture, () -> {
            if (technique.equals(STEP)) {
                var profile = definition.getEffects(0).getStatusBenefit().getLeap();
                boolean paid = TechniqueFabric.service().exertLeap(fixture.player.getUUID(), definition,
                        profile, UUID.randomUUID().toString());
                context.assertTrue(paid, "运动后轻身跃步无法支付做功");
            }
            if (completed + 1 == CAST_COUNT) {
                fixture.close();
                context.succeed();
            } else {
                long delay = definition.getCooldownMillis() / MILLIS_PER_TICK + COOLDOWN_MARGIN;
                later(context, delay, fixture, () -> repeat(context, fixture, technique, completed + 1));
            }
        });
    }

    private static void assertWorkRejected(GameTestHelper context, BasicTestPlayer fixture, String technique) {
        // 移除力量门槛后先证明绑定成功，再检查各式真正支付做功的入口。
        var definition = TechniqueFabric.service().registry().findLatest(technique).orElseThrow();
        var binding = TechniqueBinder.bind(fixture.body().snapshot(), definition);
        context.assertTrue(binding.hasBinding(), "槽位仍拦截 " + technique + "：" + binding.getFailure());
        if (technique.equals(STEP)) {
            TechniqueStatusBenefits.apply(fixture.player, definition.getEffects(0).getStatusBenefit());
            fixture.player.setOnGround(true /* onGround */);
            context.assertTrue(TechniqueLeaps.jump(fixture.player) == TechniqueLeaps.Jump.ORDINARY,
                    "轻身未在做功预算不足时退回普通跳");
        } else {
            TechniqueFailure failure = cast(fixture, technique);
            context.assertTrue(failure == TechniqueFailure.INSUFFICIENT_CAPABILITY,
                    "撞门未因做功不足拒绝：" + failure);
        }
    }

    private static BasicTestPlayer prepared(GameTestHelper context, String technique) {
        // 正式玩家开脉补元并学卷；不会改变人体基础力量或组织恢复规则。
        var definition = TechniqueFabric.service().registry().findLatest(technique).orElseThrow();
        var slot = definition.getSlotsList().stream()
                .filter(candidate -> candidate.getTagsList().contains(LEG_TAG)).findFirst().orElseThrow();
        context.assertTrue(slot.getMinimumCount() == WORKING_LEG_COUNT && !slot.hasMinimumStrength(),
                "完整装配载入了旧腿槽目录：" + technique);
        var fixture = PlayerAssemblyGameTest.prepared(context);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(1, PLAYER_HEIGHT, 1)));
        fixture.player.snapTo(position.x, position.y, position.z, 0, 0);
        fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        String volume = "meridian_door_breaker:upper";
        if (technique.equals(STEP)) {
            volume = "meridian_basic_techniques:light_step_volume";
        }
        TechniqueFabric.service().learn(fixture.player.getUUID(), volume);
        return fixture;
    }

    private static TechniqueFailure cast(BasicTestPlayer fixture, String technique) {
        // 请求只表达施放意图，绑定与做功均由正式服务决定。
        var reference = TechniqueReference.newBuilder()
                .setId(technique)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(fixture.player.getStringUUID())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
        return TechniqueFabric.service().cast(fixture.player.getUUID(), request).getFailure();
    }

    private static List<Anatomy.TissueDefinition> legs(BasicTestPlayer fixture) {
        // 按内容标签选腿肌，避免测试复制正式肌肉名单。
        return fixture.body().snapshot().structure().tissues().stream()
                .filter(tissue -> tissue.tags().stream().anyMatch(tag -> tag.value().equals(LEG_TAG))).toList();
    }

    static void exhaust(GameTestHelper context, BasicTestPlayer fixture) {
        // 用低于爆发功率的真实做功制造重度疲劳，不直接改写快照字段。
        var host = fixture.body();
        long millis = now(fixture);
        host.settle(millis);
        var loads = ExertTissues.newBuilder();
        for (var tissue : legs(fixture)) {
            var capacity = tissue.capacity();
            double watts = capacity.burstWatts() * BURST_FRACTION;
            double seconds = capacity.enduranceJoules() * HEAVY_FATIGUE_RATIO / watts;
            var load = ExertTissue.newBuilder()
                    .setTissueId(tissue.id().value())
                    .setPowerWatts(watts)
                    .setDurationSeconds(seconds)
                    .build();
            loads.addLoads(load);
        }
        var exertion = loads.build();
        var command = BodyCommand.newBuilder().setExertTissues(exertion).build();
        var response = host.applyExertion(millis, request(fixture, command));
        context.assertTrue(response.hasCommandCommitted(), "重疲劳准备失败：" + response);
    }

    private static void injure(GameTestHelper context, BasicTestPlayer fixture) {
        // 每块腿肌保留一成 HP，伤势造成的能力下降不能被基础模板力量掩盖。
        var host = fixture.body();
        long millis = now(fixture);
        for (var tissue : legs(fixture)) {
            host.settle(millis);
            var target = HealthTarget.newBuilder().setTissueId(tissue.id().value()).build();
            var damage = DamageBody.newBuilder()
                    .setSourceId("test:leg")
                    .setDamageType("test:muscle")
                    .setKind(DamageKind.CUSTOM)
                    .setHit(target)
                    .setDamageHp(tissue.maxHp() * INJURY_RATIO)
                    .build();
            var command = BodyCommand.newBuilder().setDamageBody(damage).build();
            var response = host.applyHealth(millis, request(fixture, command));
            context.assertTrue(response.hasCommandCommitted(), "腿肌损伤准备失败：" + response);
        }
    }

    private static RequestEnvelope request(BasicTestPlayer fixture, BodyCommand operation) {
        // 权威版本由当前身体读取，保留普通做功和健康入口各自的权限边界。
        var body = fixture.body().snapshot();
        var command = operation.toBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision())
                .build();
        return RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command)
                .build();
    }

    private static long now(BasicTestPlayer fixture) {
        // 与宿主一致使用主世界 tick 作为身体时钟。
        return fixture.player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
    }

    private static void later(GameTestHelper context, long ticks, BasicTestPlayer fixture, Runnable action) {
        // 延迟断言失败时也断开玩家，避免污染后续 GameTest。
        context.runAfterDelay(ticks, () -> {
            try {
                action.run();
            } catch (RuntimeException | Error failure) {
                fixture.close();
                throw failure;
            }
        });
    }
}
