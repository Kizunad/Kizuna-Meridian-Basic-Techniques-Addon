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
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/** 正式走路与施放做功产生疲劳，能力门槛保留余量但仍拒绝严重失能。 */
public final class LegTechniqueGameTest implements FabricGameTest {
    private static final String STEP = "meridian_basic_techniques:light_step";
    private static final String RAM = "meridian_door_breaker:ram";
    private static final String LEG_TAG = "human:leg_muscle";
    private static final int WALK_TICKS = 600;
    private static final int TEST_TIMEOUT = 1100;
    private static final int CAST_COUNT = 3;
    private static final int COOLDOWN_MARGIN = 2;
    private static final int PLAYER_HEIGHT = 2;
    private static final long MILLIS_PER_TICK = 50;
    private static final double WALK_DELTA = 0.1;
    private static final double WALK_MINIMUM_FATIGUE = 5;
    private static final double HEAVY_FATIGUE_RATIO = 0.8;
    private static final double INJURY_RATIO = 0.9;
    private static final double BURST_FRACTION = 0.25;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = TEST_TIMEOUT)
    public void lightStepAfterThirtySecondsWalkingCanCastThreeTimes(TestContext context) {
        // 以位置采样驱动真实步行，再按正常冷却连续施放，期间不治疗或补体力。
        walkThenCast(context, STEP);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = TEST_TIMEOUT)
    public void ramAfterThirtySecondsWalkingCanCastThreeTimes(TestContext context) {
        // 撞门复用相同腿肌门槛，连续三次付费包含冲步及收势的真实身体时钟。
        walkThenCast(context, RAM);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void exhaustedLegsRejectBothTechniques(TestContext context) {
        // 通过普通做功消耗八成耐力，不能因门槛留余量让严重疲劳继续施放。
        for (String technique : List.of(STEP, RAM)) {
            try (var fixture = prepared(context, technique)) {
                exhaust(context, fixture);
                context.assertTrue(cast(fixture, technique) == TechniqueFailure.INSUFFICIENT_CAPABILITY,
                        "重疲劳仍可施放：" + technique);
            }
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void seriouslyInjuredLegsRejectBothTechniques(TestContext context) {
        // 精确腿肌损伤经过健康事务，保留剩余 HP 以专门检验有效力量拒绝。
        for (String technique : List.of(STEP, RAM)) {
            try (var fixture = prepared(context, technique)) {
                injure(context, fixture);
                context.assertTrue(cast(fixture, technique) == TechniqueFailure.INSUFFICIENT_CAPABILITY,
                        "腿部重伤仍可施放：" + technique);
            }
        }
        context.complete();
    }

    private static void walkThenCast(TestContext context, String technique) {
        // 每 tick 小幅往返且保持落地，让 HumanMovementObserver 识别步行而非传送或冲刺。
        var fixture = prepared(context, technique);
        Vec3d origin = fixture.player.getPos();
        for (int tick = 1; tick <= WALK_TICKS; tick++) {
            int step = tick;
            later(context, tick, fixture, () -> {
                double offset = 0;
                if (step % 2 == 1) {
                    offset = WALK_DELTA;
                }
                fixture.player.setPosition(origin.add(offset, 0, 0));
                fixture.player.setOnGround(true /* onGround */);
            });
        }
        later(context, WALK_TICKS + COOLDOWN_MARGIN, fixture, () -> {
            var host = fixture.body();
            host.observe(now(fixture), HumanPlayerBody.Motion.IDLE);
            var body = host.snapshot();
            double fatigue = body.tissueStates().stream()
                    .filter(tissue -> tissue.id().value().equals("left_rectus_femoris"))
                    .findFirst().orElseThrow().fatigue();
            context.assertTrue(fatigue > WALK_MINIMUM_FATIGUE, "步行没有实际积累腿肌疲劳：" + fatigue);
            repeat(context, fixture, technique, 0);
        });
    }

    private static void repeat(TestContext context, BasicTestPlayer fixture, String technique, int completed) {
        // 冷却从收势结束计算；每次沿同一身体继续消费，不能换零疲劳夹具。
        TechniqueFailure failure = cast(fixture, technique);
        context.assertTrue(failure == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "连续第 " + (completed + 1) + " 次失败：" + technique + " " + failure);
        if (completed + 1 == CAST_COUNT) {
            fixture.close();
            context.complete();
            return;
        }
        var definition = TechniqueFabric.service().registry().findLatest(technique).orElseThrow();
        var phases = definition.getPhases();
        long duration = phases.getWarmupMillis() + phases.getActiveMillis() + phases.getRecoveryMillis();
        long delay = (duration + definition.getCooldownMillis()) / MILLIS_PER_TICK + COOLDOWN_MARGIN;
        later(context, delay, fixture, () -> repeat(context, fixture, technique, completed + 1));
    }

    private static BasicTestPlayer prepared(TestContext context, String technique) {
        // 正式玩家开脉补元并学卷；不会改变人体基础力量或组织恢复规则。
        var fixture = PlayerAssemblyGameTest.prepared(context);
        Vec3d position = Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(1, PLAYER_HEIGHT, 1)));
        fixture.player.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        fixture.player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        String volume = "meridian_door_breaker:upper";
        if (technique.equals(STEP)) {
            volume = "meridian_basic_techniques:light_step_volume";
        }
        TechniqueFabric.service().learn(fixture.player.getUuid(), volume);
        return fixture;
    }

    private static TechniqueFailure cast(BasicTestPlayer fixture, String technique) {
        // 请求只表达施放意图，绑定与做功均由正式服务决定。
        var reference = TechniqueReference.newBuilder()
                .setId(technique)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(fixture.player.getUuidAsString())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
        return TechniqueFabric.service().cast(fixture.player.getUuid(), request).getFailure();
    }

    private static List<Anatomy.TissueDefinition> legs(BasicTestPlayer fixture) {
        // 按内容标签选腿肌，避免测试复制正式肌肉名单。
        return fixture.body().snapshot().structure().tissues().stream()
                .filter(tissue -> tissue.tags().stream().anyMatch(tag -> tag.value().equals(LEG_TAG))).toList();
    }

    private static void exhaust(TestContext context, BasicTestPlayer fixture) {
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

    private static void injure(TestContext context, BasicTestPlayer fixture) {
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
        return fixture.player.getServer().getOverworld().getTime() * MILLIS_PER_TICK;
    }

    private static void later(TestContext context, long ticks, BasicTestPlayer fixture, Runnable action) {
        // 延迟断言失败时也断开玩家，避免污染后续 GameTest。
        context.waitAndRun(ticks, () -> {
            try {
                action.run();
            } catch (RuntimeException | Error failure) {
                fixture.close();
                throw failure;
            }
        });
    }
}
