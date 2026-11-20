package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** 用同一次提交前后的逐肌疲劳量计价，排除本击伤势本身造成的耐力变化。 */
public final class GuardHitCostGameTest {
    private static final int WARMUP = 8;
    private static final int NEAR_BREAK = 180;
    private static final int TIMEOUT = 220;
    private static final float DOSE = 10;
    private static final float HEAVY = 20;
    private static final double COST = 9000;
    private static final double EPSILON = 1e-6;
    private static final double GUARDED_SHARE = 0.7;
    private static final int DISTANCE = 2;
    private static final int RESISTANCE = 4;
    private static final int ABSORPTION_HP_PER_LEVEL = 4;
    private static final int LIGHT_WEAR = 7;
    private static final int HEAVY_WEAR = 13;
    private enum Path { NORMAL, DIRECT }
    private enum Defense { ARMED, ARMORED, ABSORBED, REAR }

    @GameTest(maxTicks = BasicGameTestTicks.DEFAULT_TIMEOUT)
    public void fixedDosesChargeOnlyGuardReduction(GameTestHelper context) {
        // 两条入口各覆盖完好剑、局部甲、完全吸收与绕背。
        var checks = new ArrayList<Runnable>();
        for (Path path : Path.values()) {
            for (Defense defense : Defense.values()) {
                var fixture = GuardTestPlayers.mortal(context);
                GuardAssemblyGameTest.start(context, fixture);
                checks.add(() -> verifyHit(context, fixture, path, defense));
            }
        }
        context.runAfterDelay(WARMUP, () -> {
            checks.forEach(Runnable::run);
            context.succeed();
        });
    }

    private static void verifyHit(GameTestHelper context, BasicTestPlayer fixture, Path path, Defense defense) {
        // 由真实观察器给出已提交的身体和确定的承力部位。
        var player = fixture.player;
        var sword = new ItemStack(Items.IRON_SWORD);
        if (defense == Defense.ARMED) {
            player.setItemSlot(EquipmentSlot.MAINHAND, sword);
        } else if (defense == Defense.ARMORED) {
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        } else if (defense == Defense.ABSORBED && path == Path.NORMAL) {
            player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, TIMEOUT, RESISTANCE));
        } else if (defense == Defense.ABSORBED) {
            // 黄心均分全身，确保承力前臂的那一份足以吸收二十点来击。
            int parts = fixture.body().snapshot().partStates().size();
            int levels = (int) Math.ceil(HEAVY * parts / ABSORPTION_HP_PER_LEVEL);
            player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, TIMEOUT, levels - 1));
        }
        // 绕背取非致死剂量，头部仍有 HP，死亡清理不会另行结束守式。
        float dose = path == Path.DIRECT && defense != Defense.REAR ? HEAVY : DOSE;
        var event = strike(context, fixture, path, defense, dose);
        var result = event.result();
        var before = result.before();
        var after = result.body();
        double paid = paid(before, after);
        double frontCost = COST * dose / DOSE;
        double expected = defense == Defense.REAR ? 0 : frontCost;
        context.assertTrue(Math.abs(paid - expected) < EPSILON,
                path + "/" + defense + " 应付 " + expected + " 实付 " + paid);
        if (defense == Defense.ARMED) {
            int wear = path == Path.NORMAL ? LIGHT_WEAR : HEAVY_WEAR;
            context.assertTrue(sword.getDamageValue() == wear, "一次兵器磨损 " + sword.getDamageValue());
        }
        if (defense == Defense.ARMORED) {
            double injury = event.input().damageAfterEffects();
            context.assertTrue(injury > 0 && injury < dose * GUARDED_SHARE, "前臂胸甲应实际减伤 " + injury);
        }
        if (defense == Defense.ABSORBED) {
            context.assertTrue(!result.health().hasDamageResolution(), "完全吸收仍提交伤害");
        }
        long beforeRevision = before.getRevision();
        long afterRevision = after.getRevision();
        context.assertTrue(afterRevision == beforeRevision + 1,
                path + "/" + defense + " 命中版本 " + beforeRevision + " → " + afterRevision);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void breakingHitStillGuardsAndNextHitInSameTickDoesNot(GameTestHelper context) {
        // 守到可付余额低于一次命中费，在同一服务器回调里打两下。
        var fixture = GuardTestPlayers.mortal(context);
        var worn = new ItemStack(Items.IRON_SWORD);
        worn.setDamageValue(worn.getMaxDamage() - 1);
        fixture.player.setItemSlot(EquipmentSlot.MAINHAND, worn);
        GuardAssemblyGameTest.start(context, fixture);
        context.runAfterDelay(WARMUP + NEAR_BREAK, () -> {
            var first = strike(context, fixture, Path.DIRECT, Defense.ARMED, DOSE);
            var input = first.input();
            context.assertTrue(input.guarded().isPresent() && !input.weaponGuarded(), "碎刃应由前臂挡住");
            context.assertTrue(Math.abs(input.damageAfterEffects() - DOSE * GUARDED_SHARE) < EPSILON,
                    "余量不足的本击仍须享受完整减伤");
            context.assertTrue(worn.isEmpty(), "碎刃没有在本击消耗");
            var after = BodyProtobuf.fromMessage(first.result().body());
            context.assertTrue(HumanStamina.from(after).availableJoules() == 0, "破防本击未付尽余量");
            var broken = TechniqueState.read(after).getActiveCast().getSustained();
            context.assertTrue(broken.getExhausted() && broken.hasEndRequestedMillis(),
                    "命中回执没有当场撤防");
            var entity = fixture.player.getUUID();
            context.assertTrue(TechniqueFabric.service().activeGuard(entity).isEmpty(), "耗尽仍有守窗");
            var next = strike(context, fixture, Path.DIRECT, Defense.ARMED, DOSE);
            context.assertTrue(next.input().guarded().isEmpty(), "同 tick 第二击仍能挡");
            context.assertTrue(next.input().damageAfterEffects() == DOSE, "同 tick 第二击未按原剂量受伤");
            context.succeed();
        });
    }

    private static VanillaDamageFabric.DamageEvent strike(GameTestHelper context, BasicTestPlayer fixture,
                                                          Path path, Defense defense, float dose) {
        // 测试伤害类型只固定头部瞄准，使用正式的普通或直接伤害入口。
        var player = fixture.player;
        int distance = defense == Defense.REAR ? -DISTANCE : DISTANCE;
        var attacker = GuardAssemblyGameTest.attacker(context, fixture, distance);
        var typeId = Identifier.parse("meridian_basic_techniques_test:guard_cost");
        var key = ResourceKey.create(Registries.DAMAGE_TYPE, typeId);
        var type = context.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
        var source = new DamageSource(type, attacker);
        List<VanillaDamageFabric.DamageEvent> events = new ArrayList<>();
        VanillaDamageFabric.registerDamageObserver(event -> {
            if (event.target() == player && event.source() == source) {
                events.add(event);
            }
        });
        if (path == Path.NORMAL) {
            player.hurtServer(context.getLevel(), source, dose);
        } else {
            VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(player, source, "head", dose));
        }
        context.assertTrue(events.size() == 1, "每击应只有一次成功通知 " + path + "/" + defense + " " + events.size());
        return events.getFirst();
    }

    private static double paid(BodySnapshot before, BodySnapshot after) {
        // 将已提交的逐肌疲劳放回命中前构型，独立于伤残、投影血量和耐久读出费用。
        var fatigueOnly = before.toBuilder();
        for (int i = 0; i < after.getTissueStatesCount(); i++) {
            fatigueOnly.getTissueStatesBuilder(i).setFatigue(after.getTissueStates(i).getFatigue());
        }
        var original = HumanStamina.from(BodyProtobuf.fromMessage(before));
        var charged = HumanStamina.from(BodyProtobuf.fromMessage(fatigueOnly.build()));
        return original.availableJoules() - charged.availableJoules();
    }
}
