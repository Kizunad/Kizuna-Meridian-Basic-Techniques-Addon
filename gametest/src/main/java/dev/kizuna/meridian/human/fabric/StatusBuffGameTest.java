package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeciesBirths;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.fabric.world.TechniqueTissueEffects;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ProblemReporter;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import static dev.kizuna.meridian.human.fabric.BasicGameTestTicks.DEFAULT_TIMEOUT;

/** 原版状态与真实 Human 身体的生命周期回归。 */
public final class StatusBuffGameTest {
    private static final String PREFIX = "meridian_basic_techniques:";
    private static final String BREATH = PREFIX + "breath";
    private static final String CHARGE = PREFIX + "charge";
    private static final String LIGHT = PREFIX + "light_body";
    private static final long MILLIS_PER_TICK = 50;
    private static final int PEAK_TICKS = 40;
    private static final int WEAK_TICKS = 140;
    private static final int AFTER_WARMUP = 40;
    private static final int CUSTOM_PEAK_TICKS = 16;
    private static final int CUSTOM_DURATION = 80;
    private static final int LONG_DURATION = 200;
    private static final int TEST_LIMIT = 220;
    private static final double PEAK = 1.3;
    private static final double WEAK = 0.85;
    private static final double EPSILON = 0.02;
    private static final double EXACT_EPSILON = 0.000001;
    private static final double CHARGE_COST = 15;
    private static final long STAGGER_MILLIS = 200;
    private static final double STAGGER_SPEED = 0.5;
    private static final long CAPACITY_DURATION_MILLIS = 20000;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void allOrdersCoexistAndOnlyLeapAbilityCanJump(GameTestHelper context) {
        // 六种添加顺序都查询同一轻身，只有组织状态时不得触发跃步。
        for (List<String> order : List.of(List.of(BREATH, CHARGE, LIGHT), List.of(BREATH, LIGHT, CHARGE),
                List.of(CHARGE, BREATH, LIGHT), List.of(CHARGE, LIGHT, BREATH),
                List.of(LIGHT, BREATH, CHARGE), List.of(LIGHT, CHARGE, BREATH))) {
            try (var fixture = ticking(context, new BasicTestPlayer(context))) {
                for (String id : order) {
                    grant(fixture, id);
                }
                context.assertTrue(fixture.player.getActiveEffects().size() == order.size(), "三种状态未共存");
                context.assertTrue(TechniqueStatusBenefits.activeLeap(fixture.player).orElseThrow()
                        .benefit().getId().equals(LIGHT), "轻身查询被组织状态替代");
                fixture.player.removeEffect(registered(LIGHT).effect());
                context.assertTrue(TechniqueLeaps.jump(fixture.player) == TechniqueLeaps.Jump.ORDINARY,
                        "只有组织状态仍触发跃步");
                int count = fixture.body().snapshot().modifiers().size();
                grant(fixture, CHARGE);
                context.assertTrue(fixture.body().snapshot().modifiers().size() == count, "刷新叠加了修正");
                fixture.player.removeAllEffects();
                context.assertTrue(fixture.body().snapshot().modifiers().isEmpty(), "清除后遗留修正");
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void interruptedWarmupKeepsCostWithoutStatusOrCurve(GameTestHelper context) {
        // 在起手第一刻施加正式僵直，费用只扣一次且未来不会补发状态。
        var fixture = ticking(context, PlayerAssemblyGameTest.prepared(context));
        var service = TechniqueFabric.service();
        service.learn(fixture.player.getUUID(), PREFIX + "charge_volume");
        double before = fixture.body().snapshot().totalQi();
        CastOutcome outcome = service.cast(fixture.player.getUUID(), cast(fixture, CHARGE));
        context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "没有进入起手");
        double paid = fixture.body().snapshot().totalQi();
        context.assertTrue(Math.abs(before - paid - CHARGE_COST) < EXACT_EPSILON, "费用没有一次付清");
        service.stagger(fixture.player.getUUID(), new TechniqueService.Stagger(STAGGER_MILLIS, STAGGER_SPEED));
        context.assertTrue(Math.abs(fixture.body().snapshot().totalQi() - paid) < EXACT_EPSILON,
                "打断立即退费或重复扣费");
        later(context, AFTER_WARMUP, fixture, () -> {
            context.assertTrue(!fixture.player.hasEffect(registered(CHARGE).effect()), "打断后仍给状态");
            context.assertTrue(fixture.body().snapshot().modifiers().isEmpty(), "打断后仍登记曲线");
            context.assertTrue(fixture.body().snapshot().totalQi() <= paid + EXACT_EPSILON,
                    "打断后退费；正常扩散损耗允许降低真元");
            fixture.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = TEST_LIMIT)
    public void peakMilkAndWeakCommandClearRemoveActualAbilities(GameTestHelper context) {
        // 分别在峰值和乏力期通过真实牛奶消费与原版命令撤销。
        var milk = ticking(context, new BasicTestPlayer(context));
        var command = ticking(context, new BasicTestPlayer(context));
        grant(milk, CHARGE);
        grant(command, CHARGE);
        later(context, PEAK_TICKS, milk, () -> {
            assertFactor(context, milk, PEAK);
            new ItemStack(Items.MILK_BUCKET).finishUsingItem(milk.player.level(), milk.player);
            assertCleared(context, milk);
            milk.close();
        });
        later(context, WEAK_TICKS, command, () -> {
            assertFactor(context, command, WEAK);
            command(context, command, "effect clear @s " + CHARGE);
            assertCleared(context, command);
            command.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void commandDurationAmplifierAndRejectedShortRefreshFollowVanilla(GameTestHelper context) {
        // 四秒高等级命令缩放整条曲线，拒绝一秒覆盖时保持当前相位与归属。
        var fixture = ticking(context, new BasicTestPlayer(context));
        command(context, fixture, "effect give @s " + CHARGE + " 4 10 true");
        context.assertTrue(effect(fixture, CHARGE).getDuration() == CUSTOM_DURATION, "命令时长未采用");
        var original = fixture.body().snapshot().modifiers();
        var source = fixture.player.createCommandSourceStack()
                .withPermission(LevelBasedPermissionSet.GAMEMASTER);
        int accepted = BasicTestCommands.run(source, "effect give @s " + CHARGE + " 1 10 true");
        context.assertTrue(accepted == 0, "原版拒绝的短时覆盖被当成刷新");
        context.assertTrue(original.equals(fixture.body().snapshot().modifiers()), "拒绝覆盖仍重建曲线");
        later(context, CUSTOM_PEAK_TICKS, fixture, () -> {
            assertFactor(context, fixture, PEAK);
            fixture.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void infiniteCommandNormalizesOnceAndBodylessEntityIsSafe(GameTestHelper context) {
        // 无限效果只规范一次；无身体牛不会被状态接线制造身体。
        try (var fixture = ticking(context, new BasicTestPlayer(context))) {
            command(context, fixture, "effect give @s " + CHARGE + " infinite 7 true");
            context.assertTrue(effect(fixture, CHARGE).getDuration() == LONG_DURATION, "无限状态未规范为有限时长");
            var cow = EntityTypes.COW.create(context.getLevel(), EntitySpawnReason.COMMAND);
            context.assertTrue(cow != null, "未创建无身体实体");
            context.getLevel().addFreshEntity(cow);
            TechniqueStatusBenefits.apply(cow, registered(CHARGE).benefit());
            context.assertTrue(cow.hasEffect(registered(CHARGE).effect()), "无身体命令状态未被原版接受");
            context.assertTrue(TechniqueFabric.service().statusHost(cow.getUUID()).isEmpty(), "状态伪造了身体");
            cow.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = TEST_LIMIT)
    public void relogPreservesWeakPhaseAndRemainingDuration(GameTestHelper context) {
        // 真实断线保存原版效果和归属，再以同一 UUID 登录；离线不重启曲线。
        var fixture = ticking(context, new BasicTestPlayer(context));
        UUID id = fixture.player.getUUID();
        grant(fixture, CHARGE);
        later(context, WEAK_TICKS, fixture, () -> {
            int remaining = effect(fixture, CHARGE).getDuration();
            fixture.close();
            context.runAfterDelay(1, () -> verifyRelog(context, id, remaining));
        });
    }

    private static void verifyRelog(GameTestHelper context, UUID id, int remaining) {
        // 登录和实体首 tick 都经过正式接线，不能用手工 grant 代替存档恢复。
        var restored = new BasicTestPlayer(context, id);
        context.runBeforeTestEnd(restored::close);
        context.assertTrue(effect(restored, CHARGE).getDuration() <= remaining, "重登刷新了持续时间");
        context.assertTrue(TechniqueTissueEffects.of(restored.player).leases().getFirst().getTotalTicks()
                == LONG_DURATION, "重登没有保留曲线总时长");
        later(context, 1, restored, () -> {
            restored.player.doTick();
            assertFactor(context, restored, WEAK);
            restored.player.removeAllEffects();
            assertCleared(context, restored);
            restored.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void dimensionTravelPreservesRemainingStatusAndDeathClearsOwnership(GameTestHelper context) {
        // 正常跨维度保留剩余效果，死亡时只撤销旧身体的自有组织修正。
        var fixture = ticking(context, new BasicTestPlayer(context));
        grant(fixture, CHARGE);
        later(context, PEAK_TICKS, fixture, () -> {
            int remaining = effect(fixture, CHARGE).getDuration();
            var destination = fixture.player.level().getServer().getLevel(Level.NETHER);
            fixture.player.teleportTo(destination, fixture.player.getX(), fixture.player.getY(),
                    fixture.player.getZ(), Set.of(), 0, 0, true /* resetCamera */);
            context.assertTrue(effect(fixture, CHARGE).getDuration() == remaining, "换维度改变剩余时长");
            TechniqueTissueEffects.of(fixture.player).tick();
            assertFactor(context, fixture, PEAK);
            fixture.player.die(fixture.player.damageSources().genericKill());
            context.assertTrue(fixture.body().snapshot().modifiers().isEmpty(), "死亡遗留状态修正");
            context.assertTrue(TechniqueTissueEffects.of(fixture.player).leases().isEmpty(), "死亡遗留旧组织归属");
            var respawned = fixture.player.level().getServer().getPlayerList()
                    .respawn(fixture.player, false /* keepEverything */, Entity.RemovalReason.KILLED);
            context.assertTrue(respawned.getActiveEffects().isEmpty(), "复活继承了旧状态");
            context.assertTrue(TechniqueTissueEffects.of(respawned).leases().isEmpty(), "复活继承了旧归属");
            var host = TechniqueFabric.service().statusHost(respawned.getUUID()).orElseThrow();
            context.assertTrue(host.inspect().body().modifiers().isEmpty(), "复活新身体继承了旧曲线");
            fixture.close();
            context.succeed();
        });
    }

    private static CastRequest cast(BasicTestPlayer fixture, String id) {
        // 施放请求只携带精确功法身份，全部费用与组织由服务端绑定。
        TechniqueReference reference = TechniqueReference.newBuilder().setId(id).setVersion(1).build();
        return CastRequest.newBuilder()
                .setBodyId(fixture.player.getStringUUID())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
    }

    @GameTest(maxTicks = TEST_LIMIT)
    public void naturalExpiryRemovesBothStatusAndModifiers(GameTestHelper context) {
        // 原版倒计时与 Framework 曲线共同到期，不能只让能力到期而保留图标。
        var fixture = ticking(context, new BasicTestPlayer(context));
        grant(fixture, CHARGE);
        later(context, LONG_DURATION + 1, fixture, () -> {
            assertCleared(context, fixture);
            context.assertTrue(TechniqueTissueEffects.of(fixture.player).leases().isEmpty(), "自然到期遗留归属");
            fixture.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void capacityRejectionDoesNotPublishIconOrTouchLegacyCurves(GameTestHelper context) {
        // 真正填满 Framework 的修正容量后给予状态，原版容器也必须保持失败前的状态。
        try (var fixture = ticking(context, new BasicTestPlayer(context))) {
            TechniqueHost host = TechniqueFabric.service().statusHost(fixture.player.getUUID()).orElseThrow();
            fillCapacity(host);
            var original = host.inspect().body().modifiers();
            var source = fixture.player.createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.GAMEMASTER);
            int accepted = BasicTestCommands.run(source, "effect give @s " + CHARGE);
            context.assertTrue(accepted == 0, "容量不足仍回报成功");
            context.assertTrue(effect(fixture, CHARGE) == null, "容量不足留下了装饰状态");
            context.assertTrue(TechniqueTissueEffects.of(fixture.player).leases().isEmpty(), "失败遗留归属");
            context.assertTrue(host.inspect().body().modifiers().equals(original), "失败改动了旧完整曲线");
        }
        context.succeed();
    }

    private static void fillCapacity(TechniqueHost host) {
        // 中性历史曲线只用于占满真实容量，不影响玩家的其他身体能力。
        var body = host.inspect().body();
        String tissue = body.structure().tissues().getFirst().id().value();
        var start = BodyMessages.CurvePoint.newBuilder().setOffsetMillis(0).setValue(0).build();
        var end = BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(CAPACITY_DURATION_MILLIS)
                .setValue(0)
                .build();
        var modifiers = new ArrayList<BodyMessages.CapabilityModifier>();
        for (int index = 0; index < CapabilityModifier.MAX_PER_BODY; index++) {
            var modifier = BodyMessages.CapabilityModifier.newBuilder()
                    .setId("meridian_technique:cast/capacity/" + index)
                    .setSource(TechniqueHost.CALLER_ID)
                    .setTissueId(tissue)
                    .setStartMillis(body.settledAt().elapsedMillis())
                    .addCurve(start)
                    .addCurve(end)
                    .setStrengthPeak(1)
                    .setBurstPeak(1)
                    .setEndurancePeak(1)
                    .setRecoveryPeak(1)
                    .build();
            modifiers.add(modifier);
        }
        BodyActivity activity = BodyActivity.newBuilder().addAllAddModifiers(modifiers).build();
        host.submit(new TechniqueHost.Submission("capacity_" + UUID.randomUUID(), body.revision(), activity));
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void humanoidUsesSharedStatusCapabilityEntryAcrossUnload(GameTestHelper context) {
        // 类人实体通过同一通用入口绑定肌肉，施放决策无需新增内容身份分支。
        var mob = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.COMMAND);
        context.assertTrue(mob != null, "类人实体未创建");
        BlockPos position = context.absolutePos(BlockPos.ZERO);
        mob.snapTo(position.getX(), position.getY(), position.getZ(), 0, 0);
        mob.setNoAi(true /* aiDisabled */);
        mob.setPersistenceRequired();
        HumanoidSpeciesBirths.birth(mob, HumanoidTier.TRUE_QI);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "类人实体未加入世界");
        TechniqueStatusBenefits.apply(mob, registered(CHARGE).benefit());
        var host = TechniqueFabric.service().statusHost(mob.getUUID()).orElseThrow();
        context.assertTrue(!host.inspect().body().modifiers().isEmpty(), "类人状态只有图标没有能力");
        context.runAfterDelay(PEAK_TICKS, () -> {
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, mob.registryAccess());
            mob.saveWithoutId(output);
            int remaining = mob.getEffect(registered(CHARGE).effect()).getDuration();
            mob.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            var restored = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.LOAD);
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING,
                    restored.registryAccess(), output.buildResult()));
            context.assertTrue(context.getLevel().addFreshEntity(restored), "卸载后实体未重新加入世界");
            TechniqueTissueEffects.of(restored).tick();
            context.assertTrue(restored.getEffect(registered(CHARGE).effect()).getDuration() == remaining,
                    "实体加载重置状态时长");
            var restoredHost = TechniqueFabric.service().statusHost(restored.getUUID()).orElseThrow();
            context.assertTrue(!restoredHost.inspect().body().modifiers().isEmpty(), "实体加载丢失曲线");
            restored.removeAllEffects();
            context.assertTrue(restoredHost.inspect().body().modifiers().isEmpty(), "类人状态清除遗留能力");
            restored.discard();
            context.succeed();
        });
    }

    private static BasicTestPlayer ticking(GameTestHelper context, BasicTestPlayer fixture) {
        // 内存连接不在原版网络 tick 列表，显式执行其玩家 tick 才能推进原版药水时长。
        context.onEachTick(() -> {
            if (fixture.connection.isConnected() && !fixture.player.isRemoved()) {
                fixture.player.doTick();
            }
        });
        context.runBeforeTestEnd(fixture::close);
        return fixture;
    }

    private static void command(GameTestHelper context, BasicTestPlayer fixture, String command) {
        // 与玩家聊天栏相同的命令解析器，测试来源仅增加管理员权限。
        var source = fixture.player.createCommandSourceStack()
                .withPermission(LevelBasedPermissionSet.GAMEMASTER);
        context.assertTrue(BasicTestCommands.run(source, command) > 0, "命令未成功：" + command);
    }

    private static void grant(BasicTestPlayer fixture, String id) {
        // 与玩家和类人宿主分发使用同一入口，命令测试另走原版解析器。
        TechniqueStatusBenefits.apply(fixture.player, registered(id).benefit());
    }

    private static TechniqueStatusBenefits.Registered registered(String id) {
        // 身份与数值来自正式目录注册结果。
        return TechniqueStatusBenefits.get(id);
    }

    private static MobEffectInstance effect(BasicTestPlayer fixture, String id) {
        // 剩余时间只读取原版容器。
        return fixture.player.getEffect(registered(id).effect());
    }

    private static void assertCleared(GameTestHelper context, BasicTestPlayer fixture) {
        // 同时检查原版状态、自有曲线与真实能力，避免装饰图标假阳性。
        context.assertTrue(effect(fixture, CHARGE) == null, "清除后仍有图标状态");
        context.assertTrue(fixture.body().snapshot().modifiers().isEmpty(), "清除后仍有能力修正");
        assertFactor(context, fixture, 1);
    }

    private static void assertFactor(GameTestHelper context, BasicTestPlayer fixture, double expected) {
        // 同时读取双臂力量与功率，命令 amplifier 不改变身体倍率。
        fixture.body().settle(fixture.player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK);
        var body = fixture.body().snapshot();
        for (var tissue : body.structure().tissues()) {
            if (tissue.tags().stream().noneMatch(tag -> tag.value().equals("human:arm_muscle"))) {
                continue;
            }
            var available = TissueCapabilities.available(body, tissue.id());
            double strength = available.strengthNewtons() / tissue.capacity().strengthNewtons();
            double burst = available.burstWatts() / tissue.capacity().burstWatts();
            context.assertTrue(Math.abs(strength - expected) < EPSILON, "力量倍率错误：" + strength
                    + " duration=" + effect(fixture, CHARGE)
                    + " leases=" + TechniqueTissueEffects.of(fixture.player).leases()
                    + " modifiers=" + body.modifiers());
            context.assertTrue(Math.abs(burst - expected) < EPSILON, "功率倍率错误：" + burst);
        }
    }

    private static void later(GameTestHelper context, int ticks, BasicTestPlayer fixture, Runnable assertion) {
        // 延迟断言失败也断开玩家，防止污染后续测试。
        context.runAfterDelay(ticks, () -> {
            try {
                assertion.run();
            } catch (RuntimeException | Error failure) {
                fixture.close();
                throw failure;
            }
        });
    }
}
