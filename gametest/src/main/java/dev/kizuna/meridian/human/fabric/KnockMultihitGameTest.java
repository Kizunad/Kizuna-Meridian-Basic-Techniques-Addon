package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidHealthHost;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundAnchor;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueSoundCues;
import dev.kizuna.meridian.vanilla.DamagePreferences;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static dev.kizuna.meridian.human.fabric.BasicGameTestTicks.DEFAULT_TIMEOUT;

/** 用正式调度、伤害宿主、聊天与声音包观察验证逐击命中，不手动重置原版无敌帧。 */
public final class KnockMultihitGameTest {
    private static final String PREFIX = "meridian_door_breaker:";
    private static final String ARM = "left_upper_arm";
    private static final List<Long> HIT_TICKS = List.of(18L, 25L, 34L);
    private static final int OBSERVE_TICKS = 38;
    private static final int LEAVE_TICKS = 20;
    private static final int MAX_SEED = 100_000;
    private static final PermissionSet COMMAND_PERMISSION = LevelBasedPermissionSet.GAMEMASTER;
    private static final int HURT_DURATION = 10;
    private static final int TARGET_HEIGHT = 2;
    private static final int TARGET_Z = 3;
    private static final double TARGET_DISTANCE = 2;
    private static final double LEAVE_DISTANCE = 8;
    private static final double IRON_AXE_DAMAGE = 9;
    private static final double TOTAL_MULTIPLIER = 1.5;
    private static final double RAW_TOTAL = IRON_AXE_DAMAGE * TOTAL_MULTIPLIER;
    private static final double EPSILON = 0.0001;
    private static final long MILLIS_PER_TICK = 50;
    /** 声音与命中一样允许一 tick 调度差。 */
    private static final long TICK_TOLERANCE = 1;
    /** 声音包按 16 格范围下发，同批并行的测试相距只有几格，会互相听到；逐条数声音的测试各占一个批次。 */
    private static final String MAIN_HAND_BATCH = "meridian_basic_techniques_test:knock_sound_main_hand";
    private static final String OFF_HAND_BATCH = "meridian_basic_techniques_test:knock_sound_off_hand";
    private static final String LEAVE_BATCH = "meridian_basic_techniques_test:knock_sound_leave";
    private static final String SPILLOVER_BATCH = "meridian_basic_techniques_test:knock_sound_spillover";
    private static final String MOB_BATCH = "meridian_basic_techniques_test:knock_sound_mob";
    private static boolean observerInstalled;

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = MAIN_HAND_BATCH)
    public void playerMainHandHitsThreeTimesAndLogsEachHit(GameTestHelper context) {
        // 主手完整结算，并核对玩家实际收到的三条调试日志。
        run(context, Mode.MAIN_HAND);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = OFF_HAND_BATCH)
    public void playerOffhandHitsThreeTimes(GameTestHelper context) {
        // 副手走相同节奏与钝击冷却标签，不能漏掉中间一击。
        run(context, Mode.OFF_HAND);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = LEAVE_BATCH)
    public void leavingAfterFirstHitMakesLaterHitsMiss(GameTestHelper context) {
        // 目标在第一下后离开范围，后两击按当时世界位置重新选目标。
        run(context, Mode.LEAVE);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = SPILLOVER_BATCH)
    public void everyKnockSpillsFromAnAlreadyDestroyedArm(GameTestHelper context) {
        // 黑臂的三段伤害各走一次正式蔓延事务，日志保留三份蔓延明细。
        run(context, Mode.SPILLOVER);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = MOB_BATCH)
    public void vindicatorUsesTheSameThreeAuthoritativeHits(GameTestHelper context) {
        // 管理命令生成真炁卫道士，施放继续使用与玩家相同的正式目录。
        run(context, Mode.MOB);
    }

    private static void run(GameTestHelper context, Mode mode) {
        // 场地隔离且固定目标抗击退；观察不修改命中、时钟、冷却或伤害倍率。
        var fixture = PlayerAssemblyGameTest.prepared(context);
        var target = target(context);
        LivingEntity actor = fixture.player;
        if (mode == Mode.MOB) {
            actor = summon(context, fixture, target);
        }
        var cast = new Trial(fixture, actor, target, mode);
        try {
            prepare(context, cast);
            long started = context.getLevel().getGameTime();
            start(context, cast);
            if (mode == Mode.LEAVE) {
                context.runAfterDelay(LEAVE_TICKS, () -> target.setPos(target.position().add(LEAVE_DISTANCE, 0, 0)));
            }
            context.runAfterDelay(OBSERVE_TICKS, () -> {
                try (cast) {
                    verify(context, cast, started);
                    context.succeed();
                }
            });
        } catch (RuntimeException | Error failure) {
            cast.close();
            throw failure;
        }
    }

    private static ObservedVillager target(GameTestHelper context) {
        // 固定命中手臂只控制随机种子，身体创建与健康结算仍由类人宿主负责。
        installObserver();
        var target = new ObservedVillager(context.getLevel());
        Vec3 pos = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(1, TARGET_HEIGHT, TARGET_Z)));
        target.snapTo(pos.x, pos.y, pos.z, 0, 0);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        context.getLevel().addFreshEntity(target);
        target.armSeed = armSeed(inspect(target));
        return target;
    }

    private static LivingEntity summon(GameTestHelper context, BasicTestPlayer fixture, LivingEntity target) {
        // 指定档位仅用于准备，自动学卷和初始真元沿用公开管理入口。
        // 26.2 没有常驻的出生区块，登录点所在区块可能还没加载，生成在那里的实体暂时查不到，功法宿主认领不到；
        // 所以先把玩家挪到测试结构里出手者要站的位置，再在玩家脚下生成。
        Vec3 position = target.position().add(0, 0, -TARGET_DISTANCE);
        fixture.player.snapTo(position.x, position.y, position.z, 0, 0);
        var source = fixture.player.createCommandSourceStack().withPermission(COMMAND_PERMISSION);
        int result = BasicTestCommands.run(source, "meridian humanoid spawn vindicator true_qi");
        context.assertTrue(result > 0, "无法生成真炁卫道士");
        var mob = context.getLevel().getEntitiesOfClass(Vindicator.class,
                fixture.player.getBoundingBox().inflate(1), entity -> true)
                .stream().findFirst().orElseThrow();
        mob.setNoAi(true /* aiDisabled */);
        mob.setNoGravity(true /* noGravity */);
        return mob;
    }

    private static void prepare(GameTestHelper context, Trial trial) {
        // 玩家转为生存以验证末击磨损；受击目标和出手者始终在同一独立场地。
        var actor = trial.actor();
        var fixture = trial.fixture();
        Mode mode = trial.mode();
        if (mode == Mode.MOB) {
            fixture.player.setGameMode(GameType.SPECTATOR);
        } else {
            fixture.player.setGameMode(GameType.SURVIVAL);
        }
        Vec3 position = trial.target().position().add(0, 0, -TARGET_DISTANCE);
        actor.snapTo(position.x, position.y, position.z, 0, 0);
        if (mode == Mode.MOB) {
            // 旁观玩家与出手者同处，才能收到按距离下发的声音包。
            fixture.player.snapTo(position.x, position.y, position.z, 0, 0);
        }
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (mode == Mode.OFF_HAND) {
            hand = InteractionHand.OFF_HAND;
        }
        actor.setItemInHand(hand, new ItemStack(Items.IRON_AXE));
        TechniqueFabric.service().learn(actor.getUUID(), PREFIX + "upper");
        var viewer = fixture.player;
        int watched = BasicTestCommands.run(viewer.createCommandSourceStack().withPermission(COMMAND_PERMISSION),
                "meridian watch damage on");
        context.assertTrue(watched == 1, "伤害日志命令失败");
        if (mode == Mode.SPILLOVER) {
            destroyArm(context, trial.target());
        }
        fixture.connection.packets.clear();
        fixture.connection.sounds.clear();
    }

    private static void start(GameTestHelper context, Trial trial) {
        // 普通起手保留绝对时间零点，前两段也必须由功法调度器分发。
        var actor = trial.actor();
        var technique = TechniqueReference.newBuilder()
                .setId(PREFIX + "knock")
                .setVersion(1)
                .build();
        String bodyId = trial.fixture().body().snapshot().id().value();
        if (trial.mode() == Mode.MOB) {
            bodyId = inspect(actor).getId();
        }
        var request = CastRequest.newBuilder()
                .setBodyId(bodyId)
                .setTechnique(technique)
                .setCastId(UUID.randomUUID().toString())
                .setTargetId(trial.target().getStringUUID())
                .build();
        var result = TechniqueFabric.service().cast(actor.getUUID(), request);
        context.assertTrue(result.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "叩门起手失败：" + result);
    }

    private static void verify(GameTestHelper context, Trial trial, long started) {
        // 每次受伤既有已提交回执又重置闪红时间；末击后工具只磨损一次。
        var target = trial.target();
        Mode mode = trial.mode();
        int expected = HIT_TICKS.size();
        if (mode == Mode.LEAVE) {
            expected = 1;
        }
        context.assertTrue(target.events.size() == expected && target.hits.size() == expected,
                "逐击结算次数错误：events=" + target.events.size() + " hits=" + target.hits);
        for (int index = 0; index < expected; index++) {
            var hit = target.hits.get(index);
            long offset = hit.tick() - started;
            context.assertTrue(Math.abs(offset - HIT_TICKS.get(index)) <= 1, "命中时刻错误：" + offset);
            context.assertTrue(hit.hurtTime() == HURT_DURATION, "命中没有重新闪红：" + hit);
            var event = target.events.get(index);
            var result = event.result();
            context.assertTrue(result.body().getRevision() == result.before().getRevision() + 1,
                    "一击没有对应唯一身体事务");
            if (mode == Mode.SPILLOVER) {
                context.assertTrue(result.health().getDamageResolution().hasSpillover(), "本击未蔓延");
            }
        }
        if (mode != Mode.LEAVE) {
            context.assertTrue(Math.abs(target.hits.stream().mapToDouble(Hit::dose).sum() - RAW_TOTAL) < EPSILON,
                    "三击原始总剂量不是 1.5 倍");
        }
        verifyLogsAndWear(context, trial, expected);
        verifySounds(context, trial, started);
    }

    private static void verifyLogsAndWear(GameTestHelper context, Trial trial, int expected) {
        // 调试观察只显示本人参与的伤害；生物出手由回执证明，玩家另查实际聊天包。
        var actor = trial.actor();
        var packets = trial.fixture().connection.packets;
        Mode mode = trial.mode();
        if (mode == Mode.MOB) {
            context.assertTrue(actor.getMainHandItem().getDamageValue() == 0, "生物错误扣耐久");
            return;
        }
        var messages = packets.stream()
                .filter(ClientboundSystemChatPacket.class::isInstance).map(ClientboundSystemChatPacket.class::cast)
                .map(packet -> packet.content().getString()).filter(line -> line.startsWith("[伤害]")).toList();
        context.assertTrue(messages.size() == expected, "伤害聊天条数错误：" + messages);
        long feedback = packets.stream()
                .filter(ClientboundDamageEventPacket.class::isInstance).map(ClientboundDamageEventPacket.class::cast)
                .filter(packet -> packet.entityId() == trial.target().getId()).count();
        context.assertTrue(feedback == expected, "客户端受击通知条数错误：" + feedback);
        if (mode == Mode.SPILLOVER) {
            context.assertTrue(messages.stream().allMatch(line -> line.contains("溢出")), "日志缺少蔓延明细");
        }
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (mode == Mode.OFF_HAND) {
            hand = InteractionHand.OFF_HAND;
        }
        context.assertTrue(actor.getItemInHand(hand).getDamageValue() == 1, "末击未只扣一次铁斧耐久");
    }

    private static void verifySounds(GameTestHelper context, Trial trial, long started) {
        // 施放者声音每击照响；目标声音只随真正造成伤害的一击在目标位置响，离开范围后的挥空不响。
        var cues = knockCues();
        // 目录里没有声音时期望与实际都为空，会空过；先确认装配进来的是带声音的目录。
        context.assertTrue(!cues.isEmpty(), "叩门目录没有声音，装配的可能是旧 JAR");
        Set<String> catalogIds = cues.stream().map(timed -> timed.cue().getSoundId()).collect(Collectors.toSet());
        var heard = trial.fixture().connection.sounds.stream()
                .filter(sound -> sound.tick() >= started && catalogIds.contains(sound.soundId()))
                .toList();
        var report = heard.stream().map(sound -> (sound.tick() - started) + ":" + sound.soundId()).toList();
        var expected = expectedSounds(trial, cues, started);
        context.assertTrue(heard.size() == expected.size(), "功法声音条数错误：" + report);
        for (ExpectedSound sound : expected) {
            long matches = heard.stream().filter(sound::matches).count();
            context.assertTrue(matches == 1, "没有按目录听到声音：" + sound + " 实际：" + report);
        }
    }

    private static List<TimedCue> knockCues() {
        // 期望值取自正式注册表里的目录定义，测试不另抄一份音量与音调。
        var definition = TechniqueFabric.service().registry().findLatest(PREFIX + "knock").orElseThrow();
        var cues = new ArrayList<TimedCue>();
        for (TechniqueEffect effect : definition.getEffectsList()) {
            if (effect.hasSound()) {
                cues.add(new TimedCue(effect.getAtMillis() / MILLIS_PER_TICK, effect.getSound()));
            }
        }
        return cues;
    }

    private static List<ExpectedSound> expectedSounds(Trial trial, List<TimedCue> cues, long started) {
        // 施放者声音对准出手者；目标声音逐一对应实际命中记录，没有命中的一击就不该出声。
        var expected = new ArrayList<ExpectedSound>();
        Vec3 caster = trial.actor().position();
        for (TimedCue timed : cues) {
            SoundCue cue = timed.cue();
            long tick = started + timed.ticks();
            if (cue.getAnchor() == SoundAnchor.CASTER) {
                expected.add(ExpectedSound.of(tick, cue, caster));
            } else {
                for (Hit hit : trial.target().hits) {
                    if (Math.abs(hit.tick() - tick) <= TICK_TOLERANCE) {
                        expected.add(ExpectedSound.of(hit.tick(), cue, hit.position()));
                    }
                }
            }
        }
        return expected;
    }

    private static BodySnapshot inspect(LivingEntity entity) {
        // 读取正式类人健康宿主，不依赖其内部存储字段。
        var request = HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setSubjectId(entity.getStringUUID())
                .setInspect(true /* inspect */)
                .build();
        var response = HealthHostProtobuf.decodeResponse(new HumanoidHealthHost()
                .exchange(HealthHostProtobuf.encodeRequest(request)));
        if (!response.hasBody()) {
            throw new AssertionError("类人身体不可用：" + response);
        }
        return response.getBody();
    }

    private static long armSeed(BodySnapshot body) {
        // 使用当前伤害选点配置，随机种子只固定碰撞部位，不改变剂量与事务。
        var config = FabricLoader.getInstance().getConfigDir().resolve("meridian-vanilla-damage.toml");
        var preferences = DamagePreferences.load(config);
        for (long seed = 0; seed < MAX_SEED; seed++) {
            double roll = RandomSource.create(seed).nextDouble();
            if (preferences.select(body, "minecraft:unknown", roll).orElseThrow().getPartId().equals(ARM)) {
                return seed;
            }
        }
        throw new AssertionError("找不到手臂选点种子");
    }

    private static void destroyArm(GameTestHelper context, LivingEntity target) {
        // 准备已损毁部位，后续每次真实打击都必须由既有伤害策略蔓延。
        var body = inspect(target);
        double hp = body.getPartStatesList().stream().filter(part -> part.getId().equals(ARM))
                .findFirst().orElseThrow().getHp();
        var hit = HealthTarget.newBuilder().setPartId(ARM).build();
        var damage = DamageBody.newBuilder()
                .setSourceId("test:knock")
                .setDamageType("test:arm")
                .setKind(DamageKind.CUSTOM)
                .setHit(hit)
                .setDamageHp(hp)
                .build();
        var command = BodyCommand.newBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .setDamageBody(damage)
                .build();
        String id = UUID.randomUUID().toString();
        var envelope = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setBodyCommand(command)
                .build();
        var request = HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setSubjectId(target.getStringUUID())
                .setEffect(envelope)
                .build();
        var response = HealthHostProtobuf.decodeResponse(new HumanoidHealthHost()
                .exchange(HealthHostProtobuf.encodeRequest(request)));
        context.assertTrue(response.hasBody(), "黑臂准备失败：" + response);
    }

    private static void installObserver() {
        // 只记录测试实体的已提交事件，其他测试与正式宿主不积累额外状态。
        if (!observerInstalled) {
            VanillaDamageFabric.registerDamageObserver(event -> {
                if (event.target() instanceof ObservedVillager target) {
                    target.events.add(event);
                }
            });
            observerInstalled = true;
        }
    }

    private enum Mode { MAIN_HAND, OFF_HAND, LEAVE, SPILLOVER, MOB }
    private record Hit(long tick, float dose, int hurtTime, Vec3 position) { }
    private record TimedCue(long ticks, SoundCue cue) { }

    private record ExpectedSound(long tick, String soundId, Vec3 position, double volume, double pitch,
            double jitter) {
        static ExpectedSound of(long tick, SoundCue cue, Vec3 position) {
            // 省略的音量与音调按功法库同一套默认值解析。
            return new ExpectedSound(tick, cue.getSoundId(), position, TechniqueSoundCues.volume(cue),
                    TechniqueSoundCues.pitch(cue), TechniqueSoundCues.pitchJitter(cue));
        }

        boolean matches(BasicTestPlayer.HeardSound heard) {
            // 时刻容许一 tick 调度差，坐标按定点精度比较，音调落在目录写的抖动区间内。
            var packet = heard.packet();
            return heard.soundId().equals(soundId)
                    && Math.abs(heard.tick() - tick) <= TICK_TOLERANCE
                    && heard.position().distanceTo(position) <= BasicTestPlayer.HeardSound.positionTolerance(position)
                    && packet.getSource() == SoundSource.PLAYERS
                    && Math.abs(packet.getVolume() - volume) < EPSILON
                    && Math.abs(packet.getPitch() - pitch) <= jitter + EPSILON;
        }
    }

    private record Trial(BasicTestPlayer fixture, LivingEntity actor, ObservedVillager target, Mode mode)
            implements AutoCloseable {
        @Override
        public void close() {
            // 无论断言成功或失败，移除测试目标及生物出手者并断开在线玩家。
            target.discard();
            if (actor != fixture.player) {
                actor.discard();
            }
            fixture.close();
        }
    }

    private static final class ObservedVillager extends Villager {
        private final List<Hit> hits = new ArrayList<>();
        private final List<VanillaDamageFabric.DamageEvent> events = new ArrayList<>();
        private long armSeed;

        private ObservedVillager(ServerLevel world) {
            // 保留原版村民身份，让类人宿主自动绑定正式成人模板。
            super(EntityTypes.VILLAGER, world);
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            // 只固定命中手臂，记录原版 damage 返回后的闪红状态与位置，不清零受伤冷却；26.2 服务端受击入口是 hurtServer。
            getRandom().setSeed(armSeed);
            boolean accepted = super.hurtServer(level, source, amount);
            if (accepted) {
                hits.add(new Hit(level().getGameTime(), amount, hurtTime, position()));
            }
            return accepted;
        }
    }
}
