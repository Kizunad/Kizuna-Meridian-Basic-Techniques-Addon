package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.VanillaDamageBridge;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.human.fabric.BasicGameTestTicks.DEFAULT_TIMEOUT;

/** 正式目录、在线 Human 玩家和 Damage 结算的格挡闭环；不依赖尚未接线的按键协议。 */
public final class GuardAssemblyGameTest {
    private static final String GUARD = "meridian_basic_techniques:guard";
    private static final String CHARGE = "meridian_basic_techniques:charge";
    private static final int WARMUP_TICKS = 4;
    private static final int RECOVERY_TICKS = 6;
    private static final int HOLD_TICKS = 60;
    private static final long RECOVERY_MILLIS = 300;
    private static final long STAGGER_MILLIS = 500;
    private static final double DOSE_HP = 10;
    private static final double GUARDED_HP = 7;
    private static final double MOVEMENT_MULTIPLIER = 0.4;
    private static final double ATTACK_DISTANCE = 2;
    private static final double EPSILON = 0.000001;
    private static final List<String> FOREARMS = List.of("left_forearm", "right_forearm");

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void freshMortalStartsInnateGuardWithoutLearningOrHeldItemGate(GameTestHelper context) {
        // 未读任何卷的凡人空手、持剑和持食物都能起式，且不会因此生成学习记录。
        for (var item : List.of(Items.AIR, Items.IRON_SWORD, Items.APPLE)) {
            try (var fixture = GuardTestPlayers.mortal(context)) {
                fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
                assertFreshMortal(context, fixture);
                double qiBefore = fixture.body().snapshot().totalQi();
                start(context, fixture);
                close(context, fixture.body().snapshot().totalQi(), qiBefore, "起式不能消耗真元");
                context.assertTrue(TechniqueState.read(fixture.body().snapshot()).getKnownCount() == 0,
                        "天生格挡不能写入学习记录");
                context.assertTrue(TechniqueFabric.service().activeGuard(fixture.player.getUUID()).isEmpty(),
                        "起手尚未结束就提前防御");
            }
        }
        context.assertTrue(!TechniqueScrollItems.scrolls().containsKey(
                Identifier.parse("meridian_basic_techniques:guard_volume_scroll")), "天生格挡不应注册卷轴");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void guardHoldsUntilReleaseAndCleansMovementAndItemLocks(GameTestHelper context) {
        // 守满三秒仍没有固定结束时刻；松手撤防，完整收势后才释放移速与物品锁。
        var fixture = GuardTestPlayers.mortal(context);
        double speed = fixture.player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        start(context, fixture);
        assertLocks(context, fixture, speed * MOVEMENT_MULTIPLIER);
        context.runAfterDelay(HOLD_TICKS, () -> {
            var active = active(fixture);
            context.assertTrue(active.hasSustained() && !active.getSustained().hasEndRequestedMillis(),
                    "按住时错误结束持续施放");
            context.assertTrue(active.getActiveEndMillis() == 0 && active.getRecoveryEndMillis() == 0,
                    "按住时不能生成固定结束边界");
            assertGuarding(context, fixture);
            release(context, fixture);
            assertLocks(context, fixture, speed * MOVEMENT_MULTIPLIER);
            context.runAfterDelay(RECOVERY_TICKS + 1, () -> {
                close(context, fixture.player.getAttributeValue(Attributes.MOVEMENT_SPEED), speed, "收势后恢复步速");
                context.assertTrue(!HumanTechniqueHost.blocksUse(fixture.player, InteractionHand.MAIN_HAND,
                        BlockPos.ZERO), "收势后物品锁未释放");
                fixture.close();
                context.succeed();
            });
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void guardRecoveryRejectsOtherTechniqueForThreeHundredMillis(GameTestHelper context) {
        // 已学蓄力且资源足够的玩家，也必须等守式完整收势结束后才能出另一式。
        var fixture = PlayerAssemblyGameTest.prepared(context);
        context.runBeforeTestEnd(fixture::close);
        var service = TechniqueFabric.service();
        UUID playerId = fixture.player.getUUID();
        service.learn(playerId, "meridian_basic_techniques:charge_volume");
        start(context, fixture);
        context.runAfterDelay(WARMUP_TICKS, () -> {
            assertGuarding(context, fixture);
            rejectCharge(context, fixture);
            release(context, fixture);
            var recovering = active(fixture);
            context.assertTrue(recovering.getRecoveryEndMillis() - recovering.getActiveEndMillis() == RECOVERY_MILLIS,
                    "格挡的收势长度不是三百毫秒");
            rejectCharge(context, fixture);
            context.runAfterDelay(RECOVERY_TICKS - 1, () -> rejectCharge(context, fixture));
            context.runAfterDelay(RECOVERY_TICKS, () -> {
                var outcome = service.cast(playerId, request(fixture, CHARGE));
                context.assertTrue(outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                        "完整收势结束仍不能起蓄力：" + outcome);
                fixture.close();
                context.succeed();
            });
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void frontMeleeRedirectsToForearmAtSevenTenths(GameTestHelper context) {
        // 固定近战打头以排除随机选点，仍经过正式 Damage 改点和 Human 健康事务。
        var fixture = GuardTestPlayers.mortal(context);
        var attacker = attacker(context, fixture, ATTACK_DISTANCE);
        start(context, fixture);
        context.runAfterDelay(WARMUP_TICKS, () -> {
            assertGuarding(context, fixture);
            var hit = strike(fixture, fixture.player.damageSources().mobAttack(attacker));
            assertForearm(context, hit);
            assertGuarding(context, fixture);
            context.assertTrue(TechniqueFabric.service().staggerRemainingMillis(fixture.player.getUUID()) == 0,
                    "挡住的近战不应产生 HIT");
            fixture.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void rearMeleeKeepsOriginalHeadAndFullDose(GameTestHelper context) {
        // 同一种近战从背后命中时不得转到前臂或减轻剂量。
        var fixture = GuardTestPlayers.mortal(context);
        var attacker = attacker(context, fixture, -ATTACK_DISTANCE);
        start(context, fixture);
        context.runAfterDelay(WARMUP_TICKS, () -> {
            assertGuarding(context, fixture);
            var hit = strike(fixture, fixture.player.damageSources().mobAttack(attacker));
            assertHead(context, hit);
            fixture.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void frontArrowUsesPackagedArrowTag(GameTestHelper context) {
        // 原版箭类型读取正式包中的窄标签，来向由箭的反向速度给出。
        var fixture = GuardTestPlayers.mortal(context);
        var arrow = EntityTypes.ARROW.create(context.getLevel(), EntitySpawnReason.COMMAND);
        context.assertTrue(arrow != null, "无法创建箭");
        arrow.setPos(fixture.player.position().add(0, 0, ATTACK_DISTANCE));
        arrow.setDeltaMovement(0, 0, -1);
        start(context, fixture);
        context.runAfterDelay(WARMUP_TICKS, () -> {
            assertGuarding(context, fixture);
            assertForearm(context, strike(fixture, source(context, DamageTypes.ARROW, arrow)));
            fixture.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void tridentIsNotIncludedWithArrows(GameTestHelper context) {
        // 三叉戟同为原版弹射物，但不属于本式的箭标签。
        var fixture = GuardTestPlayers.mortal(context);
        var trident = EntityTypes.TRIDENT.create(context.getLevel(), EntitySpawnReason.COMMAND);
        context.assertTrue(trident != null, "无法创建三叉戟");
        trident.setPos(fixture.player.position().add(0, 0, ATTACK_DISTANCE));
        trident.setDeltaMovement(0, 0, -1);
        start(context, fixture);
        context.runAfterDelay(WARMUP_TICKS, () -> {
            assertGuarding(context, fixture);
            assertHead(context, strike(fixture, source(context, DamageTypes.TRIDENT, trident)));
            fixture.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void hitInterruptsGuardWarmup(GameTestHelper context) {
        // 只保护生效段；抬手期间仍服从目录的受击可打断规则。
        try (var fixture = GuardTestPlayers.mortal(context)) {
            start(context, fixture);
            var stagger = new TechniqueService.Stagger(STAGGER_MILLIS, 1);
            var service = TechniqueFabric.service();
            UUID playerId = fixture.player.getUUID();
            var outcome = service.stagger(playerId, stagger).orElseThrow();
            context.assertTrue(outcome.getPhase() == CastPhase.INTERRUPTED, "HIT 未打断格挡起手");
            context.assertTrue(service.activeGuard(playerId).isEmpty(),
                    "起手被打断后仍有防御");
        }
        context.succeed();
    }

    private static void assertFreshMortal(GameTestHelper context, BasicTestPlayer fixture) {
        // 境界和已学记录直接读正式身体，不从功法的可用列表反推凡人身份。
        var body = fixture.body().snapshot();
        var service = TechniqueFabric.service();
        UUID playerId = fixture.player.getUUID();
        context.assertTrue(body.realm() == Realm.WANDERING_QI, "新生玩家不在最低境界");
        context.assertTrue(body.meridianStates().stream()
                .allMatch(state -> state.opening().stage() == MeridianOpening.Stage.UNOPENED),
                "新生凡人已经开脉");
        context.assertTrue(TechniqueState.read(body).getKnownCount() == 0, "新玩家已经学过功法");
        context.assertTrue(service.known(playerId).stream()
                .anyMatch(entry -> entry.technique().getId().equals(GUARD)), "新玩家不认识天生格挡");
        var ordinary = service.cast(playerId, request(fixture, CHARGE));
        context.assertTrue(ordinary.getFailure() == TechniqueFailure.NOT_KNOWN, "未学普通式也被放行");
    }

    private static void start(GameTestHelper context, BasicTestPlayer fixture) {
        // 施放正式内容引用，不在测试里注入另一个守式定义。
        var outcome = TechniqueFabric.service().cast(fixture.player.getUUID(), request(fixture, GUARD));
        context.assertTrue(outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "格挡起式失败：" + outcome);
        context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "格挡未从起手开始");
    }

    private static CastRequest request(BasicTestPlayer fixture, String id) {
        // 可信请求只携带真实身体和施放身份，不提供时长、费用或伤害倍率。
        var reference = TechniqueReference.newBuilder()
                .setId(id)
                .setVersion(1)
                .build();
        return CastRequest.newBuilder()
                .setBodyId(fixture.body().snapshot().id().value())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
    }

    private static ActiveCast active(BasicTestPlayer fixture) {
        // 读取原身体的权威施放进度，不另存按住或收势标志。
        return TechniqueState.read(fixture.body().snapshot()).getActiveCast();
    }

    private static void release(GameTestHelper context, BasicTestPlayer fixture) {
        // 松手请求绑定当前这次施放；接受后即不能再挡后续伤害。
        var active = active(fixture);
        var request = EndActiveRequest.newBuilder()
                .setBodyId(fixture.body().snapshot().id().value())
                .setCastId(active.getRequest().getCastId())
                .build();
        var service = TechniqueFabric.service();
        UUID playerId = fixture.player.getUUID();
        var outcome = service.endActive(playerId, request);
        context.assertTrue(outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "松手失败：" + outcome);
        context.assertTrue(service.activeGuard(playerId).isEmpty(), "松手后仍在防御");
    }

    private static void rejectCharge(GameTestHelper context, BasicTestPlayer fixture) {
        // 必须是施放占用拒绝，不能由未学、缺元或境界不足凑出失败。
        var outcome = TechniqueFabric.service().cast(fixture.player.getUUID(), request(fixture, CHARGE));
        context.assertTrue(outcome.getFailure() == TechniqueFailure.ALREADY_CASTING,
                "格挡保持或收势时应以施放占用拒绝蓄力：" + outcome);
    }

    private static void assertGuarding(GameTestHelper context, BasicTestPlayer fixture) {
        // 每次伤害前确认守已生效，防止方向或标签测试因为根本没起式而空过。
        context.assertTrue(TechniqueFabric.service().activeGuard(fixture.player.getUUID()).isPresent(),
                "起手结束后没有有效格挡");
    }

    private static void assertLocks(GameTestHelper context, BasicTestPlayer fixture, double expectedSpeed) {
        // 读取玩家实际属性和宿主交互判定，覆盖数据到 Human 效果锁的接线。
        close(context, fixture.player.getAttributeValue(Attributes.MOVEMENT_SPEED), expectedSpeed, "守时移速");
        for (var hand : InteractionHand.values()) {
            context.assertTrue(HumanTechniqueHost.blocksUse(fixture.player, hand, BlockPos.ZERO),
                    "守时没有拦截用物品：" + hand);
        }
    }

    private static LivingEntity attacker(GameTestHelper context, BasicTestPlayer fixture, double forwardDistance) {
        // 牛没有身体肌肉倍率且不加入世界，固定来向而不引入自主攻击和寻路。
        var attacker = EntityTypes.COW.create(context.getLevel(), EntitySpawnReason.COMMAND);
        context.assertTrue(attacker != null, "无法创建近战来源");
        attacker.setPos(fixture.player.position().add(0, 0, forwardDistance));
        return attacker;
    }

    private static DamageSource source(GameTestHelper context, ResourceKey<DamageType> key, Entity direct) {
        // 使用真实伤害类型注册表和实体弹道；测试不修改生产白名单。
        var type = context.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
        return new DamageSource(type, direct);
    }

    private static VanillaDamageBridge.CommitResult strike(BasicTestPlayer fixture, DamageSource source) {
        // 可信直接伤害入口只固定原始头部落点；改点、减伤、组织扣血仍全部由正式实现决定。
        var damage = new VanillaDamageFabric.DirectDamage(fixture.player, source, "head", DOSE_HP);
        return VanillaDamageFabric.damageDirect(damage).orElseThrow();
    }

    private static void assertForearm(GameTestHelper context, VanillaDamageBridge.CommitResult hit) {
        // 部位损失必须只发生于一条前臂，并核对最终提交的剂量，不能只看原版红闪。
        var losses = hit.health().getDamageResolution().getLossesList().stream()
                .filter(loss -> loss.getTarget().hasPartId()).toList();
        context.assertTrue(losses.size() == 1, "正面命中不应分到多个部位：" + losses);
        var loss = losses.getFirst();
        context.assertTrue(FOREARMS.contains(loss.getTarget().getPartId()), "正面命中没有只转到前臂：" + losses);
        close(context, loss.getHp(), GUARDED_HP, "前臂承受七成剂量");
        close(context, partHp(hit.body(), "head"), partHp(hit.before(), "head"), "格挡后原头部不掉血");
    }

    private static void assertHead(GameTestHelper context, VanillaDamageBridge.CommitResult hit) {
        // 未挡住的命中仍落在原头部，双前臂不承担这一击。
        var before = hit.before();
        var after = hit.body();
        close(context, partHp(before, "head") - partHp(after, "head"), DOSE_HP, "原头部承受完整剂量");
        for (String arm : FOREARMS) {
            close(context, partHp(after, arm), partHp(before, arm), "未挡住时前臂不掉血");
        }
    }

    private static double partHp(BodySnapshot body, String id) {
        // 身体来自已提交伤害回执，不重查可能推进到另一时刻的宿主。
        return body.getPartStatesList().stream().filter(part -> part.getId().equals(id))
                .findFirst().orElseThrow().getHp();
    }

    private static void close(GameTestHelper context, double actual, double expected, String message) {
        // 浮点容差只覆盖计算误差，失败时保留实际和预期值便于定位接线。
        context.assertTrue(Math.abs(actual - expected) < EPSILON,
                message + "：实际 " + actual + "，预期 " + expected);
    }
}
