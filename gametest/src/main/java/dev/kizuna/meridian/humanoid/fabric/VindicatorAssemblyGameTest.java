package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.fabric.BasicTestPlayer;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.AfterBatch;
import net.minecraft.test.BeforeBatch;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/** 全部宿主同服时，只学破门卷的真炁卫道士由类人大脑起手劈门式并影响玩家和木门。 */
public final class VindicatorAssemblyGameTest implements FabricGameTest {
    private static final String PLAYER_BATCH = "assembly-player";
    private static final String DOOR_BATCH = "assembly-mob-griefing";
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

    @BeforeBatch(batchId = PLAYER_BATCH)
    public void beforePlayerBatch(ServerWorld world) {
        // 玩家用例只发破门卷，大脑只在破门式里选。
        DoorBreakerOnlyVolumes.install();
    }

    @AfterBatch(batchId = PLAYER_BATCH)
    public void afterPlayerBatch(ServerWorld world) {
        // 后续批次用启动配置。
        DoorBreakerOnlyVolumes.restore();
    }

    @BeforeBatch(batchId = DOOR_BATCH)
    public void beforeDoorBatch(ServerWorld world) {
        // 劈门用例同样只发破门卷。
        DoorBreakerOnlyVolumes.install();
    }

    @AfterBatch(batchId = DOOR_BATCH)
    public void afterDoorBatch(ServerWorld world) {
        // 后续批次用启动配置。
        DoorBreakerOnlyVolumes.restore();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = TEST_TIMEOUT, batchId = PLAYER_BATCH)
    public void trueQiVindicatorBrainCastsDoorBreakerAgainstPlayer(TestContext context) {
        // 真实玩家脱离登录保护后成为目标，大脑起手劈门式，观察 Human 健康宿主实际扣血。
        // 大脑按权重抽式，劈门式之前的打击两击就能把玩家打死，所以劈门式起手前玩家无敌。
        var fixture = new BasicTestPlayer(context);
        VindicatorEntity mob = generated(context, "player");
        var player = fixture.player();
        Vec3d position = mob.getPos().add(0, 0, TARGET_DISTANCE);
        player.refreshPositionAndAngles(position.x, position.y, position.z, PLAYER_YAW, 0);
        player.changeGameMode(GameMode.SURVIVAL);
        durable(player);
        player.getAbilities().invulnerable = true;
        var stage = new HumanoidTestStage(context, () -> {
            // 断线并移除卫道士，防止其他整合用例继承目标。
            mob.discard();
            fixture.close();
        });
        stage.later(JOIN_READY_TICKS, () -> {
            mob.setTarget(player);
            mob.setAiDisabled(false /* aiDisabled */);
            stage.await(CAST_TICKS, () -> activeTechnique(mob).filter(CLEAVE::equals).isPresent(),
                    () -> "大脑没有起手劈门式：" + progress(mob), () -> {
                        context.assertTrue(HumanoidTechniqueHost.isCasting(mob)
                                        && mob.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) > 0,
                                "施放未保留近战互斥与主动移动");
                        player.getAbilities().invulnerable = false;
                        float health = player.getHealth();
                        stage.await(HIT_OBSERVE_TICKS, () -> player.getHealth() < health,
                                () -> "大脑劈门式没有经过 Human 与 Damage 扣除玩家生命", () -> {
                                    assertAxeIntact(context, mob);
                                    stage.finish();
                                });
                    });
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = TEST_TIMEOUT, batchId = DOOR_BATCH)
    public void trueQiVindicatorBrainCleaveBreaksDoorWithMobGriefing(TestContext context) {
        // 大脑起手劈门式后才在它面前放门，避免关闭的门先遮挡视线。
        VindicatorEntity mob = generated(context, "door");
        CowEntity target = target(context, mob);
        var world = context.getWorld();
        var server = world.getServer();
        var griefing = world.getGameRules().get(GameRules.DO_MOB_GRIEFING);
        boolean original = griefing.get();
        griefing.set(true /* value */, server);
        var stage = new HumanoidTestStage(context, () -> {
            // 恢复规则并移除实体，防止其他整合用例继承全局设置。
            griefing.set(original, server);
            mob.discard();
            target.discard();
        });
        mob.setTarget(target);
        mob.setAiDisabled(false /* aiDisabled */);
        stage.await(CAST_TICKS, () -> activeTechnique(mob).filter(CLEAVE::equals).isPresent(),
                () -> "大脑没有起手劈门式：" + progress(mob), () -> {
                    mob.setAiDisabled(true /* aiDisabled */);
                    BlockPos door = mob.getBlockPos().south();
                    placeDoor(context, door);
                    stage.later(HIT_OBSERVE_TICKS, () -> {
                        context.assertTrue(world.getBlockState(door).isAir()
                                && world.getBlockState(door.up()).isAir(), "大脑劈门没有破坏上下木门");
                        assertAxeIntact(context, mob);
                        stage.finish();
                    });
                });
    }

    private static VindicatorEntity generated(TestContext context, String name) {
        // 生成器固定真炁档，自动学卷与大脑仍走正式宿主；不手动发施放请求。
        for (int x = 0; x < FLOOR_SIZE; x++) {
            for (int z = 0; z < FLOOR_SIZE; z++) {
                context.setBlockState(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        var world = context.getWorld();
        UUID id = UUID.nameUUIDFromBytes(("vindicator-assembly/" + name).getBytes(StandardCharsets.UTF_8));
        Entity stale = world.getEntity(id);
        if (stale != null) {
            stale.discard();
        }
        VindicatorEntity mob = EntityType.VINDICATOR.create(world);
        mob.setUuid(id);
        Vec3d position = Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(ORIGIN, ORIGIN, ORIGIN)));
        mob.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        mob.setAiDisabled(true /* aiDisabled */);
        mob.setOnGround(true /* onGround */);
        mob.setPersistent();
        mob.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        world.spawnEntity(mob);
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

    private static CowEntity target(TestContext context, VindicatorEntity mob) {
        // 卫道士正南两格不动的牛，承受打击而不反击。
        CowEntity cow = EntityType.COW.create(context.getWorld());
        Vec3d position = mob.getPos().add(0, 0, TARGET_DISTANCE);
        cow.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        cow.setAiDisabled(true /* aiDisabled */);
        cow.setSilent(true /* silent */);
        durable(cow);
        context.getWorld().spawnEntity(cow);
        return cow;
    }

    private static void durable(LivingEntity target) {
        // 目标血量足够撑到劈门式，且不被击退出劈门式的长起手距离。
        target.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.setHealth((float) TARGET_HEALTH);
        target.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE)
                .setBaseValue(FULL_KNOCKBACK_RESISTANCE);
    }

    private static String progress(VindicatorEntity mob) {
        // 失败信息里列出权威快照的功法进度。
        return TechniqueState.read(BodyProtobuf.fromMessage(
                ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot())).toString();
    }

    private static Optional<String> activeTechnique(VindicatorEntity mob) {
        // 权威快照中正在施放的式。
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(
                ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot()));
        if (!progress.hasActiveCast()) {
            return Optional.empty();
        }
        return Optional.of(progress.getActiveCast().getRequest().getTechnique().getId());
    }

    private static void placeDoor(TestContext context, BlockPos lower) {
        // 使用世界绝对位置放置完整木门，使施放时朝向与方块范围保持一致。
        var world = context.getWorld();
        var state = Blocks.OAK_DOOR.getDefaultState();
        world.setBlockState(lower.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(lower, state);
        world.setBlockState(lower.up(), state.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void assertAxeIntact(TestContext context, VindicatorEntity mob) {
        // 生物出招不磨损手持铁斧。
        context.assertTrue(mob.getMainHandStack().getDamage() == 0, "生物出招错误磨损铁斧");
    }
}
