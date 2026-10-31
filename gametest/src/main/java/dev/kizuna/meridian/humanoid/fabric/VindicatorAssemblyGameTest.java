package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.fabric.BasicTestPlayer;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 全部宿主同服时，只学破门卷的真炁卫道士由类人大脑起手劈门式并影响玩家和木门。 */
public final class VindicatorAssemblyGameTest {
    /** 两个批次环境都在批次前只发破门卷、批次后换回启动配置，见 {@link BasicTestEnvironments}。 */
    private static final String PLAYER_BATCH = "meridian_basic_techniques_test:assembly_player";
    private static final String DOOR_BATCH = "meridian_basic_techniques_test:assembly_mob_griefing";
    private static final String CLEAVE = "meridian_door_breaker:cleave";
    private static final int TEST_TIMEOUT = 2600;
    private static final int JOIN_READY_TICKS = 65;
    private static final int CAST_TICKS = 2400;
    private static final int HIT_OBSERVE_TICKS = 80;
    private static final int ORIGIN = 2;
    private static final int FLOOR_SIZE = 5;
    private static final double TARGET_DISTANCE = 2;
    private static final double TARGET_HEALTH = 1000;
    private static final double FULL_KNOCKBACK_RESISTANCE = 1;
    private static final float PLAYER_YAW = 180;
    /** 劈门式起手前不受伤害的玩家。 */
    private static final Set<UUID> SHIELDED = new HashSet<>();
    private static boolean shieldInstalled;

    @GameTest(maxTicks = TEST_TIMEOUT, environment = PLAYER_BATCH)
    public void trueQiVindicatorBrainCastsDoorBreakerAgainstPlayer(GameTestHelper context) {
        // 真实玩家脱离登录保护后成为目标，大脑起手劈门式，观察 Human 健康宿主实际扣血。
        // 大脑按权重抽式，劈门式之前的打击两击就能把玩家打死，所以劈门式起手前挡掉玩家受到的伤害。
        // 26.2 的生物不把能力无敌的玩家设为目标，不能再用能力无敌保护。
        var fixture = new BasicTestPlayer(context);
        Vindicator mob = generated(context, "player");
        var player = fixture.player();
        Vec3 position = mob.position().add(0, 0, TARGET_DISTANCE);
        player.snapTo(position.x, position.y, position.z, PLAYER_YAW, 0);
        player.setGameMode(GameType.SURVIVAL);
        durable(player);
        shield(player);
        var stage = new HumanoidTestStage(context, () -> {
            // 断线并移除卫道士，撤掉伤害保护，防止其他整合用例继承目标。
            mob.discard();
            SHIELDED.remove(player.getUUID());
            fixture.close();
        });
        stage.later(JOIN_READY_TICKS, () -> {
            mob.setTarget(player);
            mob.setNoAi(false /* aiDisabled */);
            stage.await(CAST_TICKS, () -> activeTechnique(mob).filter(CLEAVE::equals).isPresent(),
                    () -> "大脑没有起手劈门式：" + progress(mob), () -> {
                        context.assertTrue(HumanoidTechniqueHost.isCasting(mob)
                                        && mob.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0,
                                "施放未保留近战互斥与主动移动");
                        SHIELDED.remove(player.getUUID());
                        float health = player.getHealth();
                        stage.await(HIT_OBSERVE_TICKS, () -> player.getHealth() < health,
                                () -> "大脑劈门式没有经过 Human 与 Damage 扣除玩家生命", () -> {
                                    assertAxeIntact(context, mob);
                                    stage.finish();
                                });
                    });
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT, environment = DOOR_BATCH)
    public void trueQiVindicatorBrainCleaveBreaksDoorWithMobGriefing(GameTestHelper context) {
        // 大脑起手劈门式后才在它面前放门，避免关闭的门先遮挡视线。
        Vindicator mob = generated(context, "door");
        Cow target = target(context, mob);
        var world = context.getLevel();
        var server = world.getServer();
        var rules = world.getGameRules();
        boolean original = rules.get(GameRules.MOB_GRIEFING);
        rules.set(GameRules.MOB_GRIEFING, true /* value */, server);
        var stage = new HumanoidTestStage(context, () -> {
            // 恢复规则并移除实体，防止其他整合用例继承全局设置。
            rules.set(GameRules.MOB_GRIEFING, original, server);
            mob.discard();
            target.discard();
        });
        mob.setTarget(target);
        mob.setNoAi(false /* aiDisabled */);
        stage.await(CAST_TICKS, () -> activeTechnique(mob).filter(CLEAVE::equals).isPresent(),
                () -> "大脑没有起手劈门式：" + progress(mob), () -> {
                    mob.setNoAi(true /* aiDisabled */);
                    BlockPos door = mob.blockPosition().south();
                    placeDoor(context, door);
                    stage.later(HIT_OBSERVE_TICKS, () -> {
                        context.assertTrue(world.getBlockState(door).isAir()
                                && world.getBlockState(door.above()).isAir(), "大脑劈门没有破坏上下木门");
                        assertAxeIntact(context, mob);
                        stage.finish();
                    });
                });
    }

    private static Vindicator generated(GameTestHelper context, String name) {
        // 生成器固定真炁档，自动学卷与大脑仍走正式宿主；不手动发施放请求。
        for (int x = 0; x < FLOOR_SIZE; x++) {
            for (int z = 0; z < FLOOR_SIZE; z++) {
                context.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        var world = context.getLevel();
        UUID id = UUID.nameUUIDFromBytes(("vindicator-assembly/" + name).getBytes(StandardCharsets.UTF_8));
        Entity stale = world.getEntity(id);
        if (stale != null) {
            stale.discard();
        }
        Vindicator mob = EntityTypes.VINDICATOR.create(world, EntitySpawnReason.COMMAND);
        mob.setUUID(id);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(ORIGIN, ORIGIN, ORIGIN)));
        mob.snapTo(position.x, position.y, position.z, 0, 0);
        mob.setNoAi(true /* aiDisabled */);
        mob.setOnGround(true /* onGround */);
        mob.setPersistenceRequired();
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        world.addFreshEntity(mob);
        var region = HumanoidFabric.region(mob);
        var now = HumanoidFabric.now();
        var request = new HumanoidBodyGenerator.GenerationRequest(
                HumanoidSpecies.VINDICATOR, HumanoidVariant.TRUE_QI, id, region.inventory(), now);
        var snapshot = HumanoidFabric.generator().generate(request, world.getSeed());
        var saved = new HumanoidBodyState.SavedBody(id, snapshot.toByteArray(), new byte[0][], region.sourceId());
        var body = HumanoidBodyState.restore(id, saved, now, HumanoidSpecies.VINDICATOR);
        ((HumanoidEntityAccess) mob).meridian$humanoidBody(body);
        HumanoidTechniqueHost.prepare(mob);
        context.assertTrue(body.snapshot().getRealm() == Realm.TRUE_QI, "卫道士不是实测真炁档");
        return mob;
    }

    private static Cow target(GameTestHelper context, Vindicator mob) {
        // 卫道士正南两格不动的牛，承受打击而不反击。
        Cow cow = EntityTypes.COW.create(context.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 position = mob.position().add(0, 0, TARGET_DISTANCE);
        cow.snapTo(position.x, position.y, position.z, 0, 0);
        cow.setNoAi(true /* aiDisabled */);
        cow.setSilent(true /* silent */);
        durable(cow);
        context.getLevel().addFreshEntity(cow);
        return cow;
    }

    private static void shield(ServerPlayer player) {
        // 伤害许可只装一次，只挡登记过的玩家，其他用例和正式宿主照常受伤。
        if (!shieldInstalled) {
            ServerLivingEntityEvents.ALLOW_DAMAGE.register(
                    (entity, source, amount) -> !SHIELDED.contains(entity.getUUID()));
            shieldInstalled = true;
        }
        SHIELDED.add(player.getUUID());
    }

    private static void durable(LivingEntity target) {
        // 目标血量足够撑到劈门式，且不被击退出劈门式的长起手距离。
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.setHealth((float) TARGET_HEALTH);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE)
                .setBaseValue(FULL_KNOCKBACK_RESISTANCE);
    }

    private static String progress(Vindicator mob) {
        // 失败信息里列出权威快照的功法进度。
        return TechniqueState.read(BodyProtobuf.fromMessage(
                ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot())).toString();
    }

    private static Optional<String> activeTechnique(Vindicator mob) {
        // 权威快照中正在施放的式。
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(
                ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot()));
        if (!progress.hasActiveCast()) {
            return Optional.empty();
        }
        return Optional.of(progress.getActiveCast().getRequest().getTechnique().getId());
    }

    private static void placeDoor(GameTestHelper context, BlockPos lower) {
        // 使用世界绝对位置放置完整木门，使施放时朝向与方块范围保持一致。
        var world = context.getLevel();
        var state = Blocks.OAK_DOOR.defaultBlockState();
        world.setBlockAndUpdate(lower.below(), Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(lower, state);
        world.setBlockAndUpdate(lower.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void assertAxeIntact(GameTestHelper context, Vindicator mob) {
        // 生物出招不磨损手持铁斧。
        context.assertTrue(mob.getMainHandItem().getDamageValue() == 0, "生物出招错误磨损铁斧");
    }
}
