package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodyActions;
import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.HealthMessages.FatigueRelief;
import dev.kizuna.meridian.protocol.pb.HealthMessages.RestoreFatigue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.SetAddonProgress;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.List;
import java.util.UUID;

/** 正式格挡、真实玩家与按毫秒推进的玩家存档副本。 */
public final class GuardStaminaGameTest {
    private static final String GUARD = "meridian_basic_techniques:guard";
    private static final long WARMUP = 400;
    private static final int WARMUP_TICKS = 8;
    private static final int BREAK_TICK = 183;
    private static final long SECOND = 1000;
    private static final long BREAK_DELAY = 1750;
    private static final int BREAK_DELAY_TICKS = 35;
    private static final double FULL = 91400;
    private static final double HOLD_COST = 10000;
    private static final int MUSCLES = 18;
    private static final int TICKS_PER_SECOND = 20;
    private static final double EPSILON = 1e-6;
    private static final double FULL_FATIGUE = 100;
    private static final double EXACT_RESTART_FATIGUE = 80;
    private static final String CHEST = "right_pectoralis_major";

    @GameTest(maxTicks = BasicGameTestTicks.DEFAULT_TIMEOUT)
    public void lateSettlementSplitsAtWarmupAndNeverPaysTwice(GameTestHelper context) {
        // 从真实起手快照分别走逐毫秒边界和一次迟到推进，比较权威费用。
        var fixture = GuardTestPlayers.mortal(context);
        GuardAssemblyGameTest.start(context, fixture);
        var body = copy(fixture.body());
        var late = copy(fixture.body());
        long start = body.snapshot().settledAt().elapsedMillis();
        long open = start + WARMUP;
        body.settle(open - 1);
        amount(context, body, FULL);
        context.assertTrue(BodyActions.usedTissues(body.snapshot()).isEmpty(), "起手占用了肌肉");
        body.settle(open);
        amount(context, body, FULL);
        context.assertTrue(BodyActions.usedTissues(body.snapshot()).size() == MUSCLES, "成架未占全十八块");
        long now = open + SECOND;
        body.settle(now);
        amount(context, body, FULL - HOLD_COST);
        var first = body.snapshot();
        body.settle(now);
        context.assertTrue(first.revision() == body.snapshot().revision(), "同一时刻重复提交");
        late.settle(now);
        amount(context, late, FULL - HOLD_COST);
        var restored = copy(late);
        restored.settle(now);
        amount(context, restored, FULL - HOLD_COST);
        context.succeed();
    }

    @GameTest(maxTicks = WARMUP_TICKS + BREAK_TICK + BasicGameTestTicks.DEFAULT_TIMEOUT)
    public void fullGuardBreaksOnTick183AndKeepsDeadline(GameTestHelper context) {
        // 最后一拍前还能守，下一拍查窗时疲劳、退出和截止已同时可见。
        var fixture = GuardTestPlayers.mortal(context);
        GuardAssemblyGameTest.start(context, fixture);
        var service = TechniqueFabric.service();
        var entity = fixture.player.getUUID();
        context.runAfterDelay(WARMUP_TICKS + BREAK_TICK - 1, () -> {
            context.assertTrue(service.activeGuard(entity).isPresent(), "守窗提前破防");
            amount(context, fixture.body(), FULL - (BREAK_TICK - 1) * HOLD_COST / TICKS_PER_SECOND);
        });
        context.runAfterDelay(WARMUP_TICKS + BREAK_TICK, () -> {
            context.assertTrue(service.activeGuard(entity).isEmpty(), "第 183 拍仍在守");
            var body = fixture.body();
            var progress = TechniqueState.read(body.snapshot());
            var cast = progress.getActiveCast();
            context.assertTrue(cast.getSustained().getExhausted(), "缺少破防原因");
            long deadline = progress.getGuardBreakEndsOrThrow(GUARD);
            context.assertTrue(deadline == cast.getActiveEndMillis() + BREAK_DELAY, "禁守截止错误");
            context.assertTrue(BodyActions.usedTissues(body.snapshot()).isEmpty(), "破防残留肌肉占用");
            var outcome = service.cast(entity, GuardAssemblyGameTest.request(fixture, GUARD));
            context.assertTrue(outcome.getReason().equals(HumanGuardStamina.BROKEN), "禁守期内可重新起式");
            checkRestart(context, body, deadline);
        });
        context.runAfterDelay(WARMUP_TICKS + BREAK_TICK + BREAK_DELAY_TICKS, () -> {
            var denied = service.cast(entity, GuardAssemblyGameTest.request(fixture, GUARD));
            context.assertTrue(denied.getReason().equals(HumanGuardStamina.WEAK), "禁守到期但体力不足时起守");
            var body = fixture.body();
            var muscles = HumanStamina.from(body.snapshot()).workingMuscles();
            restore(context, body, muscles);
            var next = service.cast(entity, GuardAssemblyGameTest.request(fixture, GUARD));
            context.assertTrue(next.getPhase() == CastPhase.WARMUP, "余力恢复后没有重新起式");
            context.assertTrue(!TechniqueState.read(body.snapshot()).containsGuardBreakEnds(GUARD), "再起仍残留破防记录");
            context.succeed();
        });
    }

    private static void checkRestart(GameTestHelper context, HumanPlayerBody source, long deadline) {
        // 序列化重建后跨越离线时间，两成和截止分别卡在两侧，并验等号。
        var body = copy(source);
        long offline = SECOND * TICKS_PER_SECOND;
        body.resume(body.snapshot().settledAt().elapsedMillis() + offline);
        long shifted = deadline + offline;
        var progress = TechniqueState.read(body.snapshot());
        var rules = progress.getActiveCast().getSustained().getGuard().getStamina();
        context.assertTrue(progress.getGuardBreakEndsOrThrow(GUARD) == shifted, "离线吞掉禁守期限");
        body.settle(shifted);
        var weak = HumanGuardStamina.rejection(body.snapshot(), GUARD, rules);
        context.assertTrue(weak.orElseThrow().equals(HumanGuardStamina.WEAK), "时间到、余力不足仍可起守");
        var message = BodyProtobuf.toMessage(body.snapshot()).toBuilder();
        var working = HumanStamina.from(body.snapshot()).workingMuscles();
        for (var muscle : message.getTissueStatesBuilderList()) {
            if (working.contains(muscle.getId())) {
                muscle.setFatigue(EXACT_RESTART_FATIGUE);
            }
        }
        var exact = BodyProtobuf.fromMessage(message.build());
        context.assertTrue(HumanGuardStamina.rejection(exact, GUARD, rules).isEmpty(), "恰好两成未放行");
        var under = message.clone();
        under.getTissueStatesBuilderList().stream().filter(tissue -> tissue.getId().equals(CHEST))
                .findFirst().orElseThrow().setFatigue(EXACT_RESTART_FATIGUE + EPSILON);
        var below = BodyProtobuf.fromMessage(under.build());
        context.assertTrue(HumanGuardStamina.rejection(below, GUARD, rules).orElseThrow()
                .equals(HumanGuardStamina.WEAK), "少于两成误放行");
        message.getSettledAtBuilder().setElapsedMillis(shifted - 1);
        var early = BodyProtobuf.fromMessage(message.build());
        context.assertTrue(HumanGuardStamina.rejection(early, GUARD, rules).orElseThrow()
                .equals(HumanGuardStamina.BROKEN), "余力够但时间未到就放行");
    }

    @GameTest(maxTicks = BasicGameTestTicks.DEFAULT_TIMEOUT)
    public void medicineWorksWhileHeldAndWalkingSurvivesRelease(GameTestHelper context) {
        // 守中主动消疲仍能恢复胸肌；松开只撤本次守式占用，腿继续行走。
        var fixture = GuardTestPlayers.mortal(context);
        GuardAssemblyGameTest.start(context, fixture);
        context.runAfterDelay(WARMUP_TICKS + TICKS_PER_SECOND, () -> {
            var body = fixture.body();
            long now = HumanTechniqueHost.nowMillis();
            body.settle(now);
            amount(context, body, FULL - HOLD_COST);
            restore(context, body, List.of(CHEST));
            double relieved = HumanStamina.from(body.snapshot()).availableJoules();
            context.assertTrue(relieved > FULL - HOLD_COST, "药物没有恢复参守肌肉");
            var held = copy(body);
            held.settle(now + SECOND);
            amount(context, held, relieved - HOLD_COST);
            body.observe(now, HumanPlayerBody.Motion.WALK);
            GuardAssemblyGameTest.release(context, fixture);
            var released = body.snapshot();
            var used = BodyActions.usedTissues(released);
            context.assertTrue(used.stream().noneMatch(id -> id.value().equals(CHEST)), "松手仍占胸肌");
            context.assertTrue(used.stream().anyMatch(id -> id.value().equals("left_rectus_femoris")), "误停行走");
            context.assertTrue(!TechniqueState.read(released).containsGuardBreakEnds(GUARD), "普通松手记作破防");
            verifyWalkingRecovery(context, body, now);
            context.succeed();
        });
    }

    private static void verifyWalkingRecovery(GameTestHelper context, HumanPlayerBody released, long now) {
        // 宿主继续观察为行走时，未用的胸肌自然恢复，下肢仍按自己的行走动作付费。
        var walking = copy(released);
        walking.observe(now, HumanPlayerBody.Motion.WALK);
        String chest = "left_pectoralis_major";
        String thigh = "left_rectus_femoris";
        double previousChest = fatigue(walking, chest);
        double previousThigh = fatigue(walking, thigh);
        walking.settle(now + SECOND);
        context.assertTrue(fatigue(walking, chest) < previousChest, "收势后空闲胸肌没有恢复");
        context.assertTrue(fatigue(walking, thigh) > previousThigh, "走路腿肌没有继续付费");
    }

    private static double fatigue(HumanPlayerBody body, String tissue) {
        // 按组织身份取身体上已提交的疲劳点。
        return body.snapshot().tissueStates().stream().filter(state -> state.id().value().equals(tissue))
                .findFirst().orElseThrow().fatigue();
    }

    @GameTest(maxTicks = BasicGameTestTicks.DEFAULT_TIMEOUT)
    public void interruptingPaidGuardsClearsOccupancyAndPreservesFatigue(GameTestHelper context) {
        // 在同一拍中断两个已经付费的守式，核对身体占用、疲劳与截止。
        var staggered = GuardTestPlayers.mortal(context);
        var unloading = GuardTestPlayers.mortal(context);
        GuardAssemblyGameTest.start(context, staggered);
        GuardAssemblyGameTest.start(context, unloading);
        context.runAfterDelay(WARMUP_TICKS + TICKS_PER_SECOND, () -> {
            var service = TechniqueFabric.service();
            var stagger = new TechniqueService.Stagger(SECOND, 0, TechniqueService.StaggerKind.HOLD);
            service.stagger(staggered.player.getUUID(), stagger);
            HumanTechniqueHost.beforeUnload(unloading.player);
            for (var fixture : List.of(staggered, unloading)) {
                var body = fixture.body();
                amount(context, body, FULL - HOLD_COST);
                var snapshot = body.snapshot();
                var progress = TechniqueState.read(snapshot);
                context.assertTrue(BodyActions.usedTissues(snapshot).isEmpty(), "中断后仍占用参守肌肉");
                context.assertTrue(!progress.hasActiveCast(), "中断后仍有施放");
                context.assertTrue(!progress.containsGuardBreakEnds(GUARD), "中断被记为破防");
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = BasicGameTestTicks.DEFAULT_TIMEOUT)
    public void oldFrozenGuardClearsWithoutBackCharging(GameTestHelper context) {
        // 将未声明体力配置的旧施放走真实恢复入口，随后在正式目录重新起式。
        var fixture = GuardTestPlayers.mortal(context);
        GuardAssemblyGameTest.start(context, fixture);
        var body = fixture.body();
        var saved = body.snapshot();
        var progress = TechniqueState.read(saved).toBuilder();
        progress.getActiveCastBuilder().getSustainedBuilder().getGuardBuilder().clearStamina();
        var update = SetAddonProgress.newBuilder()
                .setId(TechniqueState.KEY)
                .setProgress(TechniqueState.pack(progress.build()))
                .build();
        var command = BodyCommand.newBuilder()
                .setBodyId(saved.id().value())
                .setExpectedRevision(saved.revision())
                .setSetAddonProgress(update)
                .build();
        body.submitRecovery("load_legacy_guard", command);
        HumanTechniqueHost.reconnect(fixture.player);
        amount(context, body, FULL);
        context.assertTrue(body.snapshot().actions().isEmpty(), "旧守式占用未清");
        context.assertTrue(!TechniqueState.read(body.snapshot()).hasActiveCast(), "旧冻结施放在恢复后仍有效");
        GuardAssemblyGameTest.start(context, fixture);
        var next = copy(body);
        next.settle(saved.settledAt().elapsedMillis() + WARMUP + SECOND);
        amount(context, next, FULL - HOLD_COST);
        context.succeed();
    }

    private static void restore(GameTestHelper context, HumanPlayerBody body, List<String> tissues) {
        // 与药物相同的可信消疲入口，同时校验该次身体版本和实际回执。
        var medicine = RestoreFatigue.newBuilder().setSourceId("sample:medicine");
        for (String tissue : tissues) {
            var dose = FatigueRelief.newBuilder().setTissueId(tissue).setAmount(FULL_FATIGUE).build();
            medicine.addDoses(dose);
        }
        var current = body.snapshot();
        var command = BodyCommand.newBuilder()
                .setBodyId(current.id().value())
                .setExpectedRevision(current.revision())
                .setRestoreFatigue(medicine)
                .build();
        var request = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command)
                .build();
        context.assertTrue(body.applyHealth(current.settledAt().elapsedMillis(), request).hasCommandCommitted(),
                "药物消疲失败");
    }

    private static HumanPlayerBody copy(HumanPlayerBody body) {
        // 使用存档消息与真实活动回执恢复另一具内存身体，毫秒推进不会移动在线世界的时钟。
        var saved = BodyProtobuf.fromMessage(BodyProtobuf.toMessage(body.snapshot()));
        return new HumanPlayerBody(saved, body.receipts(), body.releasedQi());
    }

    private static void amount(GameTestHelper context, HumanPlayerBody body, double expected) {
        // 焦耳来自逐肌查询，不从施放游标反推。
        double actual = HumanStamina.from(body.snapshot()).availableJoules();
        context.assertTrue(Math.abs(actual - expected) < EPSILON, "全身余力应为 " + expected + "，实际 " + actual);
    }
}
