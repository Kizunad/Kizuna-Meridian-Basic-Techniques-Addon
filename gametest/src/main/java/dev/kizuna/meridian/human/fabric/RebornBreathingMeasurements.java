package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.basictechniques.verification.BasicTechniquesCatalog;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpace;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpaceKind;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.CaptureQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyTimeSource;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueBreathingPractice;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntityTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 相同出生快照、时间和环境库存分别用于一次固定接触及完整自动吐纳。 */
public final class RebornBreathingMeasurements {
    static final String FORM = "meridian_basic_techniques:later_breathing";
    private static final Logger LOG = LoggerFactory.getLogger("reborn_measure");
    private static final String SOURCE = "meridian_basic_techniques_test:breathing";
    private static final String FIRE = "meridian:fire";
    private static final double FREE_DENSITY = 0.01;
    private static final double STOCK = 100;
    private static final double BLOCKS = 100;
    private static final double SAMPLE = 0.5;
    private static final double YIELD = 0.2;
    private static final double SECOND_FEE = 0.005;
    private static final double INTAKE = 1.5;
    private static final double EPSILON = 1e-7;
    private static final long SECOND = 1000;
    private static final long MINUTE = 60 * SECOND;
    private static final long STEP = 250;
    private static final int TICKS_PER_MINUTE = 1200;
    private static final int CAST_TICKS = 4;
    private static final int COMBAT_MARGIN = 40;
    private static final int STRIKES = 3;
    private static final BlockPos TARGET = new BlockPos(1, 3, 1);

    private enum Mode { OFF, ON }

    @GameTest(maxTicks = TICKS_PER_MINUTE + COMBAT_MARGIN)
    public void fightingWithoutBreathingPaysForOneMinute(GameTestHelper context) {
        // 普攻三次，以连续身体时间和实际收支核对整分钟。
        var fixture = GuardTestPlayers.mortal(context);
        var player = fixture.player;
        var service = TechniqueFabric.service();
        service.learn(player.getUUID(), FORM + "_volume");
        var outcome = service.cast(player.getUUID(), GuardAssemblyGameTest.request(fixture, FORM));
        context.assertTrue(outcome.getReason().isEmpty(), "正式起式失败 " + outcome);
        context.runAfterDelay(CAST_TICKS, () -> {
            var body = fixture.body();
            body.settle(HumanTechniqueHost.nowMillis());
            var before = body.snapshot();
            context.assertTrue(TechniqueBreathingPractice.active(before).isPresent(), "战斗前没有运功");
            for (int index = 0; index < STRIKES; index++) {
                context.runAfterDelay(1 + index * TICKS_PER_MINUTE / STRIKES, () -> {
                    var target = context.spawn(EntityTypes.COW, TARGET);
                    float health = target.getHealth();
                    player.attack(target);
                    context.assertTrue(target.getHealth() < health, "普攻没有造成伤害");
                });
            }
            context.runAfterDelay(TICKS_PER_MINUTE, () -> {
                body.settle(HumanTechniqueHost.nowMillis());
                var after = body.snapshot();
                long elapsed = after.settledAt().elapsedMillis() - before.settledAt().elapsedMillis();
                context.assertTrue(elapsed == MINUTE, "战斗时长 " + elapsed);
                context.assertTrue(TechniqueBreathingPractice.active(after).isPresent(), "空转提前收法");
                double net = after.totalQi() - before.totalQi();
                near(context, net, -SECOND_FEE * MINUTE / SECOND, "战斗净额");
                LOG.info("REBORN combat strikes={} elapsed={} before={} after={} net={}",
                        STRIKES, elapsed, before.totalQi(), after.totalQi(), net);
                context.succeed();
            });
        });
    }

    @GameTest
    public void wanderingBreathingAccounts(GameTestHelper context) {
        // 从未开脉的新身体快照分出四次测量。
        try (var fixture = new BasicTestPlayer(context)) {
            measure(context, fixture, HumanContent.breathRecipeId(FIRE));
        }
    }

    @GameTest
    public void trueQiBreathingAccounts(GameTestHelper context) {
        // 管理指令只设置境界，四个分支仍从同一身体出发。
        try (var fixture = new BasicTestPlayer(context)) {
            var source = fixture.player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER);
            context.assertTrue(BasicTestCommands.run(source, "meridian body realm @s true_qi") > 0, "升真炁失败");
            measure(context, fixture, HumanContent.trueQiBreathRecipeId(FIRE));
        }
    }

    private static void measure(GameTestHelper context, BasicTestPlayer fixture, String recipe) {
        // 开关对照单独准备吐纳权限；选出身取得权限由装配验收走真实选择入口。
        var command = fixture.player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER);
        int unlocked = BasicTestCommands.run(command, "meridian progression unlock @s");
        context.assertTrue(unlocked > 0, "未授权测量用吐纳");
        var initial = fixture.body().snapshot();
        for (var mode : Mode.values()) {
            fixed(context, initial, recipe, mode);
            automatic(context, initial, mode);
        }
        context.succeed();
    }

    private static Trial trial(GameTestHelper context, BodySnapshot initial, Mode mode) {
        // 独立复制区域库存、浓度和有效期，再通过人体维持入口发动目录效果。
        var at = BodyProtobuf.toTime(initial.settledAt());
        var until = at.toBuilder().setElapsedMillis(at.getElapsedMillis() + MINUTE + SECOND).build();
        var space = EnvironmentSpace.newBuilder()
                .setProtocolVersion(1)
                .setSourceId(SOURCE)
                .setKind(EnvironmentSpaceKind.REGION_BACKGROUND)
                .setVolumeBlocks(BLOCKS)
                .build();
        var inventory = new QiInventory(List.of(new Qi(Qi.QiAttribute.FIRE, STOCK, FREE_DENSITY)));
        var source = EnvironmentAbsorption.initialize(space, inventory, at, until,
                new EnvironmentQiRules(EnvironmentConfigLoader.defaults()));
        var store = new InMemoryBodyStore(List.of(initial), List.of(), QiInventory.empty(), List.of(source));
        var body = new HumanPlayerBody(initial, store, Set.of());
        long start = at.getElapsedMillis();
        body.selectEnvironment(SOURCE, start);
        body.connectGathering(start, response -> { });
        if (mode == Mode.ON) {
            var definition = TechniqueFabric.service().registry().findLatest(FORM).orElseThrow();
            context.assertTrue(body.beginPractice(start, "measured-practice", definition), "正式运功未启动");
        }
        return new Trial(body, store, start);
    }

    private static void fixed(GameTestHelper context, BodySnapshot initial, String recipe, Mode mode) {
        // 一秒后对同一肺送半格接触，根据回执和余额核对原料、成品和付款。
        var trial = trial(context, initial, mode);
        var body = trial.body();
        body.settle(trial.start() + SECOND);
        var snapshot = body.snapshot();
        var at = BodyProtobuf.toTime(snapshot.settledAt());
        var plan = TimeAdvancePlan.newBuilder()
                .setTarget(at)
                .setBiologicalSecondsPerSimulationSecond(1)
                .setMode(TimeAdvanceMode.ACTIVE)
                .build();
        BodyTimeSource<String> clock = (caller, state) -> plan;
        var handler = new BodyCommandHandler<String>((caller, command) -> Optional.of(caller),
                (caller, command, state) -> true, clock, trial.store(), (caller, receipt, before, after) -> { },
                BasicTechniquesCatalog.addons().recipes(), (caller, state, time) -> Optional.of(SOURCE));
        var request = contact(snapshot, recipe);
        var response = MeridianProtocol.decodeResponse(handler.handleCapturedQi("owner", request.toByteArray()));
        context.assertTrue(response.hasCommandCommitted(), "固定接触失败 " + response);
        var receipt = trial.store().receipts(snapshot.id()).stream()
                .filter(r -> r.getRequestId().equals(request.getRequestId())).findFirst().orElseThrow();
        var exchange = receipt.getQiCapture().getEnvironment().getExchange();
        double raw = BodyProtobuf.fromInventory(exchange.getAbsorbedQi()).totalQi();
        double refined = raw - BodyProtobuf.fromInventory(exchange.getReleasedQi()).totalQi();
        double factor = mode == Mode.ON ? INTAKE : 1;
        double expected = SAMPLE * factor;
        near(context, raw, expected, "固定接触原料");
        near(context, refined, expected * YIELD, "固定接触炼成");
        double fee = trial.store().environmentQi().totalQi();
        near(context, fee, mode == Mode.ON ? SECOND_FEE : 0, "固定接触扣费");
        double net = body.snapshot().totalQi() - initial.totalQi();
        near(context, net, refined - fee, "固定接触净额");
        LOG.info("REBORN fixed {} {} raw={} refined={} paid={} net={}", initial.realm(), mode, raw, refined, fee, net);
    }

    private static RequestEnvelope contact(BodySnapshot body, String recipe) {
        // 客户端没有此入口，测试只控制接触时刻、肺和取样体积。
        var capture = CaptureQi.newBuilder()
                .setOrganId("left_lung")
                .setRecipeId(recipe)
                .setRecipeVersion(1)
                .setSampleVolumeBlocks(SAMPLE)
                .build();
        var command = BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision())
                .setCaptureQi(capture)
                .build();
        return RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("fixed-contact")
                .setBodyCommand(command)
                .build();
    }

    private static void automatic(GameTestHelper context, BodySnapshot initial, Mode mode) {
        // 正式自动会话推进一分钟，以实际库存核对接触与输送。
        var trial = trial(context, initial, mode);
        var body = trial.body();
        var started = control(body, BreathAction.BEGIN_BREATHING, 1);
        var response = body.gather(trial.start(), started);
        context.assertTrue(response.getView().getBreathing().getActive(), "自动吐纳未开始");
        long end = trial.start() + MINUTE;
        for (long now = trial.start() + STEP; now <= end; now += STEP) {
            body.settle(now);
            body.advanceGathering(now);
        }
        var stopped = body.gather(end, control(body, BreathAction.END_BREATHING, 2));
        context.assertTrue(stopped.hasView(), "停止吐纳失败");
        var totals = stopped.getView().getBreathing();
        var after = body.snapshot();
        double refined = STOCK - BodyProtobuf.fromInventory(
                trial.store().environmentSource(SOURCE).orElseThrow().getInventory()).totalQi();
        double paid = trial.store().environmentQi().totalQi();
        double net = after.totalQi() - initial.totalQi();
        near(context, net, refined - paid, "自动吐纳总账");
        near(context, refined, totals.getAbsorbedQi() * YIELD, "自动吐纳原料");
        double minuteFee = SECOND_FEE * MINUTE / SECOND;
        near(context, paid, mode == Mode.ON ? minuteFee : 0, "自动吐纳扣费");
        context.assertTrue(refined > 0, "整分钟没有接触");
        LOG.info("REBORN auto {} {} raw={} refined={} stored={} paid={} net={} lumenDelta={} organ={} transit={}",
                initial.realm(), mode, totals.getAbsorbedQi(), refined, totals.getStoredQi(), paid, net,
                after.lumenQiTotal() - initial.lumenQiTotal(), after.organQiTotal(), after.transitQiTotal());
    }

    private static GatheringRequest control(HumanPlayerBody body, BreathAction action, int sequence) {
        // 自动吐纳的开始和停止都经过宿主鉴权与会话序号。
        var control = BreathControl.newBuilder()
                .setSequence(sequence)
                .setAction(action)
                .setAutomatic(true /* automatic */)
                .build();
        return GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setBodyId(body.snapshot().id().value())
                .setRequestId("measure-" + sequence)
                .setBreath(control)
                .build();
    }

    private static void near(GameTestHelper context, double actual, double expected, String label) {
        // 跨环境和身体的守恒只允许双精度尾差。
        context.assertTrue(Math.abs(actual - expected) < EPSILON, label + " " + actual + " / " + expected);
    }

    private record Trial(HumanPlayerBody body, InMemoryBodyStore store, long start) { }
}
