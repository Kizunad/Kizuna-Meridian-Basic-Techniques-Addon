package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.fabric.mixin.TechniqueJumpInvoker;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapNetworking;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapTargets;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapMotion;
import dev.kizuna.meridian.technique.server.TechniqueSoundCues;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.concurrent.atomic.AtomicInteger;

/** 真实身体、原版效果、碰撞与落地链的完整装配验收。 */
public final class LightBodyGameTest implements FabricGameTest {
    private static final String EFFECT = "meridian_basic_techniques:light_body";
    private static final int PERMISSION = 2;
    private static final int ARENA_Y = 100;
    private static final int ARENA_SPACING = 40;
    private static final int ARENA_RADIUS = 16;
    private static final int ARENA_Z = 300;
    private static final int WALL_HEIGHT = 3;
    private static final int STEP_DEPTH = 3;
    private static final int STEP_FLIGHT_LIMIT = 14;
    private static final int LEAP_DISTANCE = 6;
    private static final int FLIGHT_LIMIT = 50;
    private static final int TEST_LIMIT = 200;
    private static final int REPEATS = 3;
    private static final int EXTRA_DROP = 10;
    private static final int JOIN_PROTECTION_TICKS = 65;
    private static final int CHUNK_READY_TICKS = 10;
    private static final int HALF_TURN_DEGREES = 180;
    private static final long MILLIS_PER_TICK = 50;
    private static final double POSITION_TOLERANCE = 0.5;
    /** 声音包坐标是 1/8 格定点数，三轴截断误差合计不超过 0.25 格。 */
    private static final double SOUND_POSITION_TOLERANCE = 0.25;
    private static final double EPSILON = 0.000001;
    private static final AtomicInteger arenas = new AtomicInteger();

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void networkRejectsForgedVelocityAndDuplicateRequestDoesNotChargeAgain(GameTestHelper context) {
        // 经正式自定义载荷接收器，客户端上传位置或重复按键都不能改变权威扣费。
        try (var fixture = prepare(context)) {
            grant(fixture);
            var forged = LeapMotion.newBuilder()
                    .setAction(LeapMotion.Action.REQUEST)
                    .setVelocityX(LEAP_DISTANCE)
                    .build();
            request(fixture, forged);
            var access = (TechniqueLeapAccess) fixture.player;
            context.assertTrue(access.meridian$getLeap() == null, "伪造速度触发了跃步");
            var intent = LeapMotion.newBuilder().setAction(LeapMotion.Action.REQUEST).build();
            request(fixture, intent);
            context.assertTrue(access.meridian$getLeap() != null, "真实按键协议未启动跃步");
            double firstFatigue = fatigue(fixture);
            String cast = access.meridian$getLeap().castId;
            request(fixture, intent);
            context.assertTrue(Math.abs(fatigue(fixture) - firstFatigue) < EPSILON, "重复请求重复扣了体力");
            context.assertTrue(access.meridian$getLeap().castId.equals(cast), "重复请求重新起跳");
        }
        context.succeed();
    }

    private static void request(BasicTestPlayer fixture, LeapMotion message) {
        // 按真实频道的长度前缀 Protobuf 编码，不直接调用起跳方法。
        var buffer = PacketByteBufs.create().writeByteArray(message.toByteArray());
        try {
            fixture.player.connection.handleCustomPayload(
                    new ServerboundCustomPayloadPacket(TechniqueLeapNetworking.CHANNEL, buffer));
        } finally {
            buffer.release();
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void worldTargetsRejectLiquidsCeilingAndMissingSupport(GameTestHelper context) {
        // 真实碰撞与流体采样必须剔除危险顶面，不能只依赖纯求解器的候选夹具。
        try (var fixture = prepare(context)) {
            var world = context.getLevel();
            var target = fixture.player.blockPosition().offset(0, 0, LEAP_DISTANCE);
            world.setBlockAndUpdate(target, Blocks.WATER.defaultBlockState());
            assertSafeReplacement(context, fixture, target);
            world.setBlockAndUpdate(target, Blocks.LAVA.defaultBlockState());
            assertSafeReplacement(context, fixture, target);
            world.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
            world.setBlockAndUpdate(target.above(), Blocks.STONE.defaultBlockState());
            assertSafeReplacement(context, fixture, target);
            world.setBlockAndUpdate(target.above(), Blocks.AIR.defaultBlockState());
            world.setBlockAndUpdate(target.below(), Blocks.AIR.defaultBlockState());
            assertSafeReplacement(context, fixture, target);
        }
        context.succeed();
    }

    private static void assertSafeReplacement(GameTestHelper context, BasicTestPlayer fixture, BlockPos target) {
        // 旁边完整平台仍可站立，选点应吸附到那里而不是危险格或固定回退。
        var profile = registered().benefit().getLeap();
        Vec3 unsafe = Vec3.atBottomCenterOf(target);
        var landing = TechniqueLeapTargets.toward(fixture.player, profile, unsafe);
        context.assertTrue(landing.supported(), "危险目标附近有平台却未吸附");
        context.assertTrue(TechniqueLeapTargets.vector(landing.point()).distanceTo(unsafe) > EPSILON,
                "选点接受了流体、无净空或悬空格");
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void commandEffectIsQuietVisibleAndRefreshesInsteadOfStacking(GameTestHelper context) {
        // 原版命令使用真实注册表，重复施放覆盖等级并刷新同一个状态。
        try (var fixture = prepare(context)) {
            var source = fixture.player.createCommandSourceStack().withPermission(PERMISSION);
            int result = fixture.player.getServer().getCommands().performPrefixedCommand(source,
                    "effect give @s " + EFFECT + " 20 0");
            context.assertTrue(result == 1, "原版命令无法给予轻身");
            var registered = registered();
            var status = fixture.player.getEffect(registered.effect());
            context.assertTrue(status != null && !status.isVisible() && status.isAmbient()
                    && status.showIcon(), "原版命令没有保留安静图标效果");
            TechniqueStatusBenefits.apply(fixture.player, registered.benefit());
            context.assertTrue(fixture.player.getActiveEffects().stream()
                    .filter(effect -> effect.getEffect() == registered.effect()).count() == 1, "轻身叠加了");
            context.assertTrue(fixture.player.getEffect(registered.effect()).getAmplifier() == 0, "轻身升了等级");
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = TEST_LIMIT)
    public void raycastWallSnapsToThreeBlockTopAndLandsWithoutDamage(GameTestHelper context) {
        // 准星指向墙面，由生产求解器吸附墙头，不能靠测试直接传送落点。
        var fixture = prepare(context);
        Vec3 origin = fixture.player.position();
        wall(context, BlockPos.containing(origin).offset(0, 0, LEAP_DISTANCE), WALL_HEIGHT);
        grant(fixture);
        fixture.player.setXRot(0);
        var landing = TechniqueLeapTargets.aimed(fixture.player, registered().benefit().getLeap());
        context.assertTrue(Math.abs(landing.point().y() - origin.y - WALL_HEIGHT) < EPSILON,
                "墙面未吸附到三格高墙头：" + landing);
        double health = hp(fixture);
        context.runAfterDelay(JOIN_PROTECTION_TICKS, () -> {
            context.assertTrue(TechniqueLeaps.jump(fixture.player) == TechniqueLeaps.Jump.LEAP, "无法起跳");
            awaitLanding(context, fixture, 0, () -> {
                try (fixture) {
                    double error = fixture.player.position().distanceTo(TechniqueLeapTargets.vector(landing.point()));
                    context.assertTrue(error <= POSITION_TOLERANCE, "三格高落点误差：" + error);
                    context.assertTrue(Math.abs(hp(fixture) - health) < EPSILON, "正常跃步产生了摔伤");
                    context.succeed();
                }
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = TEST_LIMIT)
    public void threeBlockStepLandsWithinFourteenFlightTicks(GameTestHelper context) {
        // 准星指向三格高台阶正面，直线预算内吸附台面，按起跳速度求出的飞行不超过 14 tick。
        var fixture = prepare(context);
        Vec3 origin = fixture.player.position();
        var front = BlockPos.containing(origin).offset(0, 0, LEAP_DISTANCE);
        for (int depth = 0; depth < STEP_DEPTH; depth++) {
            wall(context, front.offset(0, 0, depth), WALL_HEIGHT);
        }
        grant(fixture);
        fixture.player.setXRot(0);
        var landing = TechniqueLeapTargets.aimed(fixture.player, registered().benefit().getLeap());
        context.assertTrue(landing.supported() && Math.abs(landing.point().y() - origin.y - WALL_HEIGHT) < EPSILON,
                "台阶未吸附到三格高台面：" + landing);
        context.runAfterDelay(JOIN_PROTECTION_TICKS, () -> {
            Vec3 takeoff = fixture.player.position();
            context.assertTrue(TechniqueLeaps.jump(fixture.player) == TechniqueLeaps.Jump.LEAP, "无法起跳");
            assertTakeoffSound(context, fixture, takeoff);
            var flight = ((TechniqueLeapAccess) fixture.player).meridian$getLeap();
            awaitLanding(context, fixture, 0, () -> {
                try (fixture) {
                    double error = fixture.player.position().distanceTo(TechniqueLeapTargets.vector(landing.point()));
                    context.assertTrue(error <= POSITION_TOLERANCE, "台阶落点误差：" + error);
                    context.assertTrue(flight.ticks <= STEP_FLIGHT_LIMIT, "上三格飞行 tick：" + flight.ticks);
                    context.succeed();
                }
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = TEST_LIMIT)
    public void threeLeapsPayWorkButNeverSpendQiAndRespectLandingDelay(GameTestHelper context) {
        // 连续起跳共享同一身体，每次先检查即时真元与真实疲劳，再等待自然落地。
        var fixture = prepare(context);
        grant(fixture);
        repeat(context, fixture, 0);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = TEST_LIMIT)
    public void earlyRequestIsHeldUntilReadyTickWithoutCorrection(GameTestHelper context) {
        // 落地间隔内到达的请求挂起而不回拉；挂起期间的预测位置包不被采纳，到时从落地处起跳。
        var fixture = prepare(context);
        grant(fixture);
        var intent = LeapMotion.newBuilder().setAction(LeapMotion.Action.REQUEST).build();
        var access = (TechniqueLeapAccess) fixture.player;
        context.runAfterDelay(JOIN_PROTECTION_TICKS, () -> {
            request(fixture, intent);
            context.assertTrue(access.meridian$getLeap() != null, "首跳未启动");
            awaitLanding(context, fixture, 0, () -> {
                Vec3 landed = fixture.player.position();
                request(fixture, intent);
                context.assertTrue(access.meridian$getLeap() == null && access.meridian$getLeapPending(),
                        "落地间隔内的请求没有挂起");
                var predicted = new ServerboundMovePlayerPacket.Pos(landed.x, landed.y + 1,
                        landed.z + LEAP_DISTANCE, false /* onGround */);
                fixture.player.connection.handleMovePlayer(predicted);
                context.assertTrue(fixture.player.position().equals(landed), "挂起期间采纳了预测位置包");
                awaitHeldLeap(context, fixture, landed, 0);
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void fatiguedLegsAndSneakingKeepVanillaJump(GameTestHelper context) {
        // 真实做功制造疲劳后拒绝跃步，原版跳跃入口仍可执行。
        try (var fixture = prepare(context)) {
            grant(fixture);
            fixture.player.setShiftKeyDown(true /* sneaking */);
            context.assertTrue(TechniqueLeaps.jump(fixture.player) == TechniqueLeaps.Jump.ORDINARY,
                    "潜行没有保留普通跳跃");
            fixture.player.setShiftKeyDown(false /* sneaking */);
            LegTechniqueGameTest.exhaust(context, fixture);
            context.assertTrue(TechniqueLeaps.jump(fixture.player) == TechniqueLeaps.Jump.ORDINARY,
                    "疲劳腿肌仍然能跃步");
            ((TechniqueJumpInvoker) fixture.player).meridian$ordinaryJump();
            context.assertTrue(fixture.player.getDeltaMovement().y > 0, "拒绝跃步后普通跳跃失效");
            context.assertTrue(((TechniqueLeapAccess) fixture.player).meridian$getLeap() == null,
                    "失败后残留了飞行免伤");
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = TEST_LIMIT)
    public void fallingBelowIntendedLandingStillDealsFallDamage(GameTestHelper context) {
        // 起跳时没有安全落点，只豁免原定同高跃步；下方十格平台必须结算额外摔伤。
        var fixture = prepare(context);
        Vec3 origin = fixture.player.position();
        clearAhead(context, BlockPos.containing(origin));
        platform(context, BlockPos.containing(origin).below(EXTRA_DROP + 1));
        grant(fixture);
        double health = hp(fixture);
        context.runAfterDelay(JOIN_PROTECTION_TICKS, () -> {
            context.assertTrue(TechniqueLeaps.jump(fixture.player) == TechniqueLeaps.Jump.LEAP, "回退跃步未起跳");
            awaitLanding(context, fixture, 0, () -> {
                try (fixture) {
                    context.assertTrue(fixture.player.getY() < origin.y - EXTRA_DROP + POSITION_TOLERANCE,
                            "没有落到额外低处");
                    context.assertTrue(hp(fixture) < health, "超出跃步落差仍然免疫摔伤");
                    context.succeed();
                }
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = TEST_LIMIT)
    public void solidWallStopsLeapWithoutPassingThrough(GameTestHelper context) {
        // 起跳后在路径上造不可越过的实墙，原版碰撞必须截住水平运动。
        var fixture = prepare(context);
        grant(fixture);
        Vec3 origin = fixture.player.position();
        context.assertTrue(TechniqueLeaps.jump(fixture.player) == TechniqueLeaps.Jump.LEAP, "无法起跳");
        wall(context, BlockPos.containing(origin).offset(0, 0, WALL_HEIGHT), EXTRA_DROP);
        awaitLanding(context, fixture, 0, () -> {
            try (fixture) {
                context.assertTrue(fixture.player.getZ() < origin.z + WALL_HEIGHT, "跃步穿过实墙");
                context.succeed();
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = TEST_LIMIT)
    public void vindicatorWithEffectLeapsTowardPlayer(GameTestHelper context) {
        // 真实真炁卫道士读原版目标，移动阶段自动用共享求解器追击玩家。
        var fixture = prepare(context);
        var structure = context.absolutePos(new BlockPos(1, 0, 1));
        var floor = new BlockPos(structure.getX(), ARENA_Y - 1, structure.getZ());
        platform(context, floor);
        fixture.player.setPos(Vec3.atBottomCenterOf(floor.above()));
        context.runAfterDelay(CHUNK_READY_TICKS, () -> summonAndObserve(context, fixture));
    }

    private static void summonAndObserve(GameTestHelper context, BasicTestPlayer fixture) {
        // 等强制加载区块进入实体管理器后再生成，功法宿主才能从 UUID 找到新生物。
        Vec3 origin = fixture.player.position();
        var spawnPos = context.absolutePos(new BlockPos(1, WALL_HEIGHT, 1));
        var source = fixture.player.createCommandSourceStack().withPermission(PERMISSION)
                .withPosition(Vec3.atBottomCenterOf(spawnPos));
        int result = fixture.player.getServer().getCommands().performPrefixedCommand(source,
                "meridian humanoid spawn vindicator true_qi");
        context.assertTrue(result > 0, "无法生成真炁卫道士：" + fixture.connection.packets.stream()
                .filter(ClientboundSystemChatPacket.class::isInstance)
                .map(ClientboundSystemChatPacket.class::cast)
                .map(packet -> packet.content().getString()).toList());
        var mob = context.getLevel().getEntitiesOfClass(Vindicator.class,
                new AABB(spawnPos).inflate(1), entity -> true).stream().findFirst().orElseThrow();
        mob.setPos(origin);
        mob.setNoAi(false /* aiDisabled */);
        mob.setOnGround(true /* onGround */);
        fixture.player.setPos(origin.add(0, 0, LEAP_DISTANCE));
        mob.setTarget(fixture.player);
        TechniqueStatusBenefits.apply(mob, registered().benefit());
        awaitMobLeap(context, fixture, mob, origin, 0);
    }

    private static void awaitMobLeap(GameTestHelper context, BasicTestPlayer fixture, Vindicator mob,
                                     Vec3 origin, int ticks) {
        // 逐 tick 观察：短飞行可能在固定等待前就已落地，必须在空中看到共享轨迹朝玩家推进。
        if (ticks >= FLIGHT_LIMIT) {
            fixture.close();
            context.fail("卫道士没有朝玩家跃步：" + mob.position() + " target=" + mob.getTarget()
                    + " age=" + mob.tickCount + " ground=" + mob.onGround());
        }
        context.runAfterDelay(1, () -> {
            if (((TechniqueLeapAccess) mob).meridian$getLeap() != null && mob.getY() > origin.y
                    && mob.getZ() > origin.z) {
                try (fixture) {
                    mob.discard();
                    context.succeed();
                }
            } else {
                awaitMobLeap(context, fixture, mob, origin, ticks + 1);
            }
        });
    }

    private static void repeat(GameTestHelper context, BasicTestPlayer fixture, int completed) {
        // 在同一平台往返，落地六 tick 后立即再跳，避免把连跳变成重新施放。
        var actor = fixture.player;
        long now = actor.getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        fixture.body().settle(now);
        double qi = qi(fixture);
        double fatigue = fatigue(fixture);
        context.assertTrue(TechniqueLeaps.jump(actor) == TechniqueLeaps.Jump.LEAP, "连续跃步失败：" + completed);
        context.assertTrue(Math.abs(qi(fixture) - qi) < EPSILON, "跃步重复扣了真元");
        context.assertTrue(fatigue(fixture) > fatigue, "跃步没有做功");
        awaitLanding(context, fixture, 0, () -> {
            if (completed + 1 == REPEATS) {
                fixture.close();
                context.succeed();
                return;
            }
            context.assertTrue(TechniqueLeaps.jump(actor) == TechniqueLeaps.Jump.WAIT, "落地硬直失效");
            actor.setYRot(actor.getYRot() + HALF_TURN_DEGREES);
            int delay = registered().benefit().getLeap().getLandingDelayTicks();
            context.runAfterDelay(delay, () -> repeat(context, fixture, completed + 1));
        });
    }

    private static void awaitHeldLeap(GameTestHelper context, BasicTestPlayer fixture, Vec3 landed, int ticks) {
        // 挂起的请求在可起跳时刻由服务端 tick 恢复，起点必须是挂起时的落地位置。
        var access = (TechniqueLeapAccess) fixture.player;
        var flight = access.meridian$getLeap();
        if (flight != null) {
            try (fixture) {
                context.assertTrue(!access.meridian$getLeapPending(), "起跳后仍处于挂起");
                context.assertTrue(flight.origin.equals(landed), "挂起请求没有从落地处起跳：" + flight.origin);
                context.succeed();
            }
            return;
        }
        if (ticks >= FLIGHT_LIMIT) {
            fixture.close();
            context.fail("挂起请求到时没有起跳");
        }
        context.runAfterDelay(1, () -> awaitHeldLeap(context, fixture, landed, ticks + 1));
    }

    private static void awaitLanding(GameTestHelper context, BasicTestPlayer fixture, int ticks, Runnable done) {
        // 使用真实服务器 tick 推进，失败时断开测试连接，防止污染随后用例。
        if (ticks >= FLIGHT_LIMIT) {
            var flight = ((TechniqueLeapAccess) fixture.player).meridian$getLeap();
            String details = fixture.player.position() + " velocity=" + fixture.player.getDeltaMovement()
                    + " ground=" + fixture.player.onGround();
            if (flight != null) {
                details += " ticks=" + flight.ticks + " landingY=" + flight.landingY;
            }
            fixture.close();
            context.fail("跃步未在预期时间内落地：" + details);
        }
        context.runAfterDelay(1, () -> {
            if (((TechniqueLeapAccess) fixture.player).meridian$getLeap() == null && fixture.player.onGround()) {
                done.run();
            } else {
                awaitLanding(context, fixture, ticks + 1, done);
            }
        });
    }

    private static BasicTestPlayer prepare(GameTestHelper context) {
        // 场地位于独立高空平台，不碰其他测试结构或服务器地面。
        var fixture = PlayerAssemblyGameTest.prepared(context);
        int x = arenas.incrementAndGet() * ARENA_SPACING;
        var floor = new BlockPos(x, ARENA_Y - 1, ARENA_Z);
        forceArena(context, floor);
        clearArena(context, floor);
        platform(context, floor);
        var position = Vec3.atBottomCenterOf(floor.above());
        fixture.player.moveTo(position.x, position.y, position.z, 0, 0);
        fixture.player.setGameMode(GameType.SURVIVAL);
        fixture.player.setNoGravity(false /* noGravity */);
        fixture.player.setOnGround(true /* onGround */);
        fixture.player.setDeltaMovement(Vec3.ZERO);
        return fixture;
    }

    private static void forceArena(GameTestHelper context, BlockPos center) {
        // 远离测试结构的场地也必须进入实体 tick 范围，才能验证真实碰撞与 AI。
        var minimum = new ChunkPos(center.offset(-ARENA_RADIUS, 0, -ARENA_RADIUS));
        var maximum = new ChunkPos(center.offset(ARENA_RADIUS, 0, ARENA_RADIUS));
        for (int x = minimum.x; x <= maximum.x; x++) {
            for (int z = minimum.z; z <= maximum.z; z++) {
                context.getLevel().setChunkForced(x, z, true /* forced */);
            }
        }
    }

    private static void clearArena(GameTestHelper context, BlockPos center) {
        // GameTest 世界会复用存档，清掉上次用例留下的墙体和平台，避免测试依赖执行历史。
        for (BlockPos position : BlockPos.betweenClosed(center.offset(-ARENA_RADIUS, -EXTRA_DROP - 1, -ARENA_RADIUS),
                center.offset(ARENA_RADIUS, EXTRA_DROP + 1, ARENA_RADIUS))) {
            context.getLevel().setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
    }

    private static void platform(GameTestHelper context, BlockPos center) {
        // 完整支撑面允许真实 Entity.move 触发落地判定。
        for (int x = -ARENA_RADIUS; x <= ARENA_RADIUS; x++) {
            for (int z = -ARENA_RADIUS; z <= ARENA_RADIUS; z++) {
                context.getLevel().setBlockAndUpdate(center.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static void wall(GameTestHelper context, BlockPos base, int height) {
        // 三格宽墙体同时验证实体宽度和顶面净空。
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y < height; y++) {
                context.getLevel().setBlockAndUpdate(base.offset(x, y, 0), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static void clearAhead(GameTestHelper context, BlockPos origin) {
        // 保留起跳脚下，清空其余平台让无落点的回退轨迹落到更低处。
        for (int x = -ARENA_RADIUS; x <= ARENA_RADIUS; x++) {
            for (int z = -ARENA_RADIUS; z <= ARENA_RADIUS; z++) {
                if (x != 0 || z != 0) {
                    context.getLevel().setBlockAndUpdate(origin.offset(x, -1, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void assertTakeoffSound(GameTestHelper context, BasicTestPlayer fixture, Vec3 takeoff) {
        // 服务端确认起跳的同一 tick 在起跳点播放一次目录写的起跳声，起跳者本人也收到声音包。
        var cue = registered().benefit().getLeap().getTakeoffSound();
        long now = fixture.player.level().getGameTime();
        var heard = fixture.connection.sounds.stream()
                .filter(sound -> sound.tick() == now && sound.soundId().equals(cue.getSoundId()))
                .toList();
        context.assertTrue(heard.size() == 1, "起跳声条数错误：" + heard.size());
        var sound = heard.get(0);
        double offset = sound.position().distanceTo(takeoff);
        context.assertTrue(offset <= SOUND_POSITION_TOLERANCE, "起跳声偏离起跳点：" + offset);
        double loudness = Math.abs(sound.packet().getVolume() - TechniqueSoundCues.volume(cue));
        context.assertTrue(loudness < EPSILON, "起跳声音量与目录不符：" + sound.packet().getVolume());
        double detune = Math.abs(sound.packet().getPitch() - TechniqueSoundCues.pitch(cue));
        context.assertTrue(detune <= TechniqueSoundCues.pitchJitter(cue) + EPSILON, "起跳声音调越界：" + detune);
    }

    private static TechniqueStatusBenefits.Registered registered() {
        // 正式注册内容是测试的唯一数值源。
        return TechniqueStatusBenefits.get(EFFECT);
    }

    private static void grant(BasicTestPlayer fixture) {
        // 直接效果入口与施放后的宿主入口相同，不替换身体。
        TechniqueStatusBenefits.apply(fixture.player, registered().benefit());
    }

    private static double hp(BasicTestPlayer fixture) {
        // 检查 Framework 实际部位 HP，不使用可能延迟的原版心数投影。
        return fixture.body().snapshot().partStates().stream().mapToDouble(part -> part.hp()).sum();
    }

    private static double qi(BasicTestPlayer fixture) {
        // 只比较相邻一次事务前后的真元库存。
        return fixture.body().snapshot().meridianStates().stream()
                .mapToDouble(state -> state.lumenQi().totalQi()).sum();
    }

    private static double fatigue(BasicTestPlayer fixture) {
        // 腿肌疲劳必须由 Framework 做功增加，不能只播放动画。
        return fixture.body().snapshot().tissueStates().stream().mapToDouble(tissue -> tissue.fatigue()).sum();
    }
}
