package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.fabric.BasicTestPlayer;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollDrops;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItem;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 基础功法的生存来源：七种生人修成游炁后都会这三卷，玩家亲手杀死时每卷按目录的低概率各自掉。
 *
 * <p>生人走 Threatening 的正式生成命令，按档位照学卷表学卷；掉落走各自的原版掉落表。</p>
 */
public final class BasicScrollDropGameTest {
    private static final List<String> HUMANOIDS = List.of("minecraft:villager", "minecraft:wandering_trader",
            "minecraft:pillager", "minecraft:vindicator", "minecraft:evoker", "minecraft:witch",
            "minecraft:illusioner");
    private static final List<String> VOLUMES = List.of("meridian_basic_techniques:breath_volume",
            "meridian_basic_techniques:light_step_volume", "meridian_basic_techniques:charge_volume");
    private static final int TIMEOUT = 100;
    private static final int BIRTH_TICKS = 2;
    private static final int COLUMNS = 4;
    private static final int SPACING = 2;
    private static final int LOOT_ROLLS = 1000;
    private static final int LEAST_DROPS = 23;
    private static final int MOST_DROPS = 77;
    private static final int LAST_HURT_TICKS = 100;

    @GameTest(maxTicks = TIMEOUT)
    public void everyCultivatedHumanoidDropsTheBasicVolumesRarely(GameTestHelper context) {
        // 七种游炁生人三卷各掉约百分之五；凡人一卷不会、一卷不掉；不是玩家打死的也不掉。
        var fixture = new BasicTestPlayer(context);
        ServerLevel level = context.getLevel();
        List<Mob> cultivated = new ArrayList<>();
        for (int index = 0; index < HUMANOIDS.size(); index++) {
            cultivated.add(spawn(context, HUMANOIDS.get(index), "wandering_qi", slot(index)));
        }
        Mob mortal = spawn(context, HUMANOIDS.getFirst(), "mortal", slot(HUMANOIDS.size()));
        context.runAfterDelay(BIRTH_TICKS, () -> {
            try (fixture) {
                ServerPlayer player = fixture.player();
                DamageSource byPlayer = level.damageSources().playerAttack(player);
                for (Mob humanoid : cultivated) {
                    String name = humanoid.getType().toShortString();
                    context.assertTrue(VOLUMES.stream().allMatch(volume -> knows(humanoid, volume)), name + " 没学会基础三卷");
                    Map<String, Integer> drops = roll(level, humanoid, byPlayer, player);
                    System.out.println("[基础功法掉落] " + name + " 玩家击杀 " + LOOT_ROLLS + " 次：" + drops);
                    context.assertTrue(drops.values().stream().allMatch(BasicScrollDropGameTest::within),
                            name + " 掉卷次数偏离 5%：" + drops);
                }
                context.assertTrue(VOLUMES.stream().noneMatch(volume -> knows(mortal, volume)), "凡人也学会了基础三卷");
                context.assertTrue(total(roll(level, mortal, byPlayer, player)) == 0, "凡人也掉了基础功法");
                DamageSource byMob = level.damageSources().mobAttack(mortal);
                context.assertTrue(total(roll(level, cultivated.getFirst(), byMob, null)) == 0, "不是玩家打死的也掉卷");
                context.succeed();
            }
        });
    }

    private static BlockPos slot(int index) {
        // 一排四个、两格一个，互不挤占。
        return new BlockPos(1 + (index % COLUMNS) * SPACING, 2, 1 + (index / COLUMNS) * SPACING);
    }

    private static Mob spawn(GameTestHelper context, String entity, String tier, BlockPos relative) {
        // 走 Threatening 的正式生成命令，按档位生出有身体、照学卷表学过卷的生人；关掉 AI 免得它们动手或走开。
        ServerLevel level = context.getLevel();
        Vec3 position = context.absoluteVec(Vec3.atBottomCenterOf(relative));
        MinecraftServer server = level.getServer();
        CommandSourceStack source = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
        String command = String.format(Locale.ROOT, "meridian humanoid spawn %s %s %.2f %.2f %.2f",
                entity, tier, position.x, position.y, position.z);
        server.getCommands().performPrefixedCommand(source, command);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(entity));
        List<Mob> spawned = level.getEntitiesOfClass(Mob.class, new AABB(BlockPos.containing(position)),
                mob -> mob.getType() == type);
        if (spawned.size() != 1) {
            throw new IllegalStateException("生成命令没有在原地生出一只 " + entity + "：" + command + " → " + spawned);
        }
        Mob humanoid = spawned.getFirst();
        humanoid.setNoAi(true);
        return humanoid;
    }

    private static boolean knows(Mob humanoid, String volume) {
        // 和掉落条件同一判定：功法服务里学会了本卷全部式。
        return new TechniqueScrollDrops.KnowsVolume(volume).knows(humanoid);
    }

    private static Map<String, Integer> roll(ServerLevel level, Mob mob, DamageSource source, ServerPlayer killer) {
        // 用这种生人自己的原版掉落表抽 LOOT_ROLLS 次，按卷数掉出来的卷轴；killer 为空算非玩家击杀。
        boolean playerKilled = killer != null;
        if (playerKilled) {
            mob.setLastHurtByPlayer(killer, LAST_HURT_TICKS);
        }
        ResourceKey<LootTable> table = mob.getType().getDefaultLootTable().orElseThrow();
        List<ItemStack> drops = new ArrayList<>();
        for (int roll = 0; roll < LOOT_ROLLS; roll++) {
            mob.dropFromLootTable(level, source, playerKilled, table, drops::add);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String volume : VOLUMES) {
            TechniqueScrollItem scroll = scrollItem(volume);
            counts.put(volume, drops.stream().filter(stack -> stack.is(scroll)).mapToInt(ItemStack::getCount).sum());
        }
        return counts;
    }

    private static TechniqueScrollItem scrollItem(String volume) {
        // 功法库按卷生成的卷轴物品。
        TechniqueScrollItem item = TechniqueScrollItems.scrolls().get(Identifier.parse(volume + "_scroll"));
        if (item == null) {
            throw new IllegalStateException("没有卷轴物品：" + volume);
        }
        return item;
    }

    private static boolean within(int drops) {
        // 1000 次 5%：均值 50、标准差约 6.9，取正负四个标准差。
        return drops >= LEAST_DROPS && drops <= MOST_DROPS;
    }

    private static int total(Map<String, Integer> counts) {
        // 三卷掉落合计。
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
