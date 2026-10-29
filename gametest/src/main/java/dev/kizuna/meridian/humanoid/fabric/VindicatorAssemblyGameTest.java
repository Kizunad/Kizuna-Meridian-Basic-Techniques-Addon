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
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

import java.util.UUID;

/** 全部宿主同服时，真炁卫道士从原版 AI 决策施放并影响玩家和木门。 */
public final class VindicatorAssemblyGameTest implements FabricGameTest {
    private static final int TEST_TIMEOUT = 200;
    private static final int JOIN_READY_TICKS = 65;
    private static final int DECISION_TICKS = VindicatorTechniqueAi.DECISION_INTERVAL_TICKS + 2;
    private static final int HIT_OBSERVE_TICKS = 80;
    private static final int ORIGIN = 2;
    private static final int FLOOR_SIZE = 5;
    private static final double TARGET_DISTANCE = 2;
    private static final float PLAYER_YAW = 180;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = TEST_TIMEOUT)
    public void trueQiVindicatorAiCastsDoorBreakerAgainstPlayer(TestContext context) {
        // 真实玩家脱离登录保护后成为原版目标，观察 Human 健康宿主实际扣血。
        attack(context, Outcome.PLAYER_DAMAGE);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = TEST_TIMEOUT,
            batchId = "assembly-mob-griefing")
    public void trueQiVindicatorAiCleaveBreaksDoorWithMobGriefing(TestContext context) {
        // 玩家在卫道士起手后放门，避免关闭门遮挡视线导致 AI 无法决策。
        attack(context, Outcome.DOOR_BREAK);
    }

    private static void attack(TestContext context, Outcome outcome) {
        // 两种世界效果共用真实 AI 起手；测试只控制场地、目标和世界规则。
        var fixture = new BasicTestPlayer(context);
        VindicatorEntity mob = generated(context);
        var player = fixture.player();
        Vec3d position = mob.getPos().add(0, 0, TARGET_DISTANCE);
        player.refreshPositionAndAngles(position.x, position.y, position.z, PLAYER_YAW, 0);
        player.changeGameMode(GameMode.SURVIVAL);
        var world = context.getWorld();
        var server = world.getServer();
        var griefing = world.getGameRules().get(GameRules.DO_MOB_GRIEFING);
        boolean original = griefing.get();
        Runnable cleanup = () -> {
            // 恢复规则并断线，防止其他整合用例继承目标和全局设置。
            griefing.set(original, server);
            mob.discard();
            fixture.close();
        };
        later(context, JOIN_READY_TICKS, cleanup, () -> {
            float health = player.getHealth();
            mob.setTarget(player);
            mob.setAiDisabled(false /* aiDisabled */);
            later(context, DECISION_TICKS, cleanup, () -> {
                assertAiCast(context, mob);
                mob.setAiDisabled(true /* aiDisabled */);
                BlockPos door = mob.getBlockPos().south();
                if (outcome == Outcome.DOOR_BREAK) {
                    griefing.set(true /* value */, server);
                    placeDoor(context, door);
                }
                later(context, HIT_OBSERVE_TICKS, cleanup, () -> {
                    assertOutcome(context, new Observation(outcome, mob, fixture, health, door));
                    cleanup.run();
                    context.complete();
                });
            });
        });
    }

    private static VindicatorEntity generated(TestContext context) {
        // 生成器固定真炁档，自动学卷与 AI 仍走正式宿主；不手动发施放请求。
        for (int x = 0; x < FLOOR_SIZE; x++) {
            for (int z = 0; z < FLOOR_SIZE; z++) {
                context.setBlockState(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        var world = context.getWorld();
        VindicatorEntity mob = EntityType.VINDICATOR.create(world);
        UUID id = UUID.randomUUID();
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
        return mob;
    }

    private static void assertAiCast(TestContext context, VindicatorEntity mob) {
        // 确认真实决策选中了破门斩，且施放锁阻断原版近战造成的假阳性。
        var body = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
        context.assertTrue(body.getRealm() == Realm.TRUE_QI, "卫道士不是实测真炁档");
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(body));
        String techniqueId = progress.getActiveCast().getRequest().getTechnique().getId();
        context.assertTrue(progress.hasActiveCast() && techniqueId.equals("meridian_door_breaker:cleave"),
                "原版 AI 没有选择劈门式：" + progress);
        context.assertTrue(HumanoidTechniqueHost.isCasting(mob)
                        && mob.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) > 0,
                "施放未保留近战互斥与主动移动");
    }

    private static void placeDoor(TestContext context, BlockPos lower) {
        // 使用世界绝对位置放置完整木门，使施放时朝向与方块范围保持一致。
        var world = context.getWorld();
        var state = Blocks.OAK_DOOR.getDefaultState();
        world.setBlockState(lower.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(lower, state);
        world.setBlockState(lower.up(), state.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void assertOutcome(TestContext context, Observation observed) {
        // 只验证跨模组端到端副作用；详细伤害、权限和耐久边界由宿主测试负责。
        if (observed.outcome() == Outcome.PLAYER_DAMAGE) {
            context.assertTrue(observed.fixture().player().getHealth() < observed.health(),
                    "AI 破门斩没有经过 Human 与 Damage 扣除玩家生命");
        } else {
            var world = context.getWorld();
            BlockPos door = observed.door();
            context.assertTrue(world.getBlockState(door).isAir()
                    && world.getBlockState(door.up()).isAir(), "AI 劈门没有破坏上下木门");
        }
        context.assertTrue(observed.mob().getMainHandStack().getDamage() == 0, "生物劈门错误磨损铁斧");
    }

    private static void later(TestContext context, int ticks, Runnable cleanup, Runnable action) {
        // 延迟断言失败也清理在线玩家和全局规则，保留原始失败原因。
        context.waitAndRun(ticks, () -> {
            try {
                action.run();
            } catch (RuntimeException | Error failure) {
                cleanup.run();
                throw failure;
            }
        });
    }

    private enum Outcome { PLAYER_DAMAGE, DOOR_BREAK }
    private record Observation(Outcome outcome, VindicatorEntity mob, BasicTestPlayer fixture,
                               float health, BlockPos door) { }
}
