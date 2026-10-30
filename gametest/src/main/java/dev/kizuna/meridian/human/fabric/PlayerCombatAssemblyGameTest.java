package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueSounds;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundAnchor;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.DamagePreferences;
import dev.kizuna.meridian.vanilla.MuscleRules;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 正式卷轴经战斗协议施放，验证整套装配中的世界效果和 Damage 接线。 */
public final class PlayerCombatAssemblyGameTest implements FabricGameTest {
    private static final String PREFIX = "meridian_door_breaker:";
    private static final int PLAYER_HEIGHT = 2;
    private static final int CAST_ACCEPT_TICKS = 2;
    private static final int CLEAVE_OBSERVE_TICKS = 75;
    private static final int KNOCK_OBSERVE_TICKS = 40;
    private static final int CLEAVE_WEAR = 3;
    private static final double TARGET_DISTANCE = 2;
    private static final double TARGET_HEALTH = 100;
    private static final double IRON_AXE_DAMAGE = 9;
    private static final double KNOCK_MULTIPLIER = 1.5;
    private static final int KNOCK_HIT_COUNT = 3;
    private static final double MULTIHIT_FATIGUE_RATIO = 0.05;
    private static final double EPSILON = 0.001;
    private static final long MILLIS_PER_TICK = 50;
    /** 破门起手 3200 毫秒，第 30 tick 时 850 毫秒的踏步声已响、劈砍声未到。 */
    private static final int STAGGER_TICKS = 30;
    /** 打断后再等 70 tick，越过原定 4500 毫秒的收势声。 */
    private static final int SILENCE_TICKS = 70;
    private static final int SOUND_TEST_TICKS = 120;
    /** 同批并行的测试相距只有几格，会听到彼此的功法声音；数声音的测试单独一个批次。 */
    private static final String SOUND_BATCH = "cleave-stagger-sound";
    private static final long STAGGER_MILLIS = 500;
    private static final double FULL_SPEED = 1;
    /** 不在服务端注册表里的声音事件，模拟纯数据功法书在自己客户端资源里声明的声音。 */
    private static final String INLINE_SOUND = "meridian_basic_techniques:test.inline_hum";

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void combatProtocolCleaveBreaksWoodenDoorAndWearsIronAxe(TestContext context) {
        // 生存玩家读卷后发正式战斗载荷，门与耐久都等待真实施放时钟推进。
        var fixture = prepared(context);
        BlockPos lower = fixture.player.getBlockPos().south();
        var world = context.getWorld();
        world.setBlockState(lower.down(), Blocks.STONE.getDefaultState());
        var door = Blocks.OAK_DOOR.getDefaultState();
        world.setBlockState(lower, door);
        world.setBlockState(lower.up(), door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        press(fixture, "cleave");
        assertAcceptedLater(context, fixture, "cleave");
        context.waitAndRun(CLEAVE_OBSERVE_TICKS, () -> {
            try (fixture) {
                context.assertTrue(world.getBlockState(lower).isAir()
                        && world.getBlockState(lower.up()).isAir(), "战斗协议劈门未移除木门上下半");
                context.assertTrue(fixture.player.getMainHandStack().getDamage() == CLEAVE_WEAR,
                        "战斗协议劈门的铁斧末击耐久错误");
                context.complete();
            } finally {
                world.setBlockState(lower.up(), Blocks.AIR.getDefaultState());
                world.setBlockState(lower, Blocks.AIR.getDefaultState());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void combatProtocolKnockDamagesMobThroughMuscleRules(TestContext context) {
        // 原始钝击剂量与护甲前肌肉倍率分开核对，目标仍进入原版实际扣血链。
        var fixture = prepared(context);
        var target = new ObservedCow(fixture.player.getServerWorld());
        double initialStrength = strength(fixture);
        Vec3d position = fixture.player.getPos().add(0, 0, TARGET_DISTANCE);
        target.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        target.setAiDisabled(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.setHealth((float) TARGET_HEALTH);
        context.getWorld().spawnEntity(target);
        press(fixture, "knock");
        assertAcceptedLater(context, fixture, "knock");
        context.waitAndRun(KNOCK_OBSERVE_TICKS, () -> {
            try (fixture) {
                double rawDamage = IRON_AXE_DAMAGE * KNOCK_MULTIPLIER;
                context.assertTrue(target.hits == KNOCK_HIT_COUNT && Math.abs(target.dose - rawDamage) < EPSILON,
                        "叩门未按铁斧和三击总倍率提交剂量");
                context.assertTrue(target.damageType.equals(PREFIX + "blunt_main_hand"), "叩门没有使用主手钝击类型");
                double expected = rawDamage * initialStrength;
                double actual = TARGET_HEALTH - target.getHealth();
                context.assertTrue(actual >= expected * (1 - MULTIHIT_FATIGUE_RATIO) && actual <= expected + EPSILON,
                        "Damage 肌肉缩放实伤不符：实际 " + actual + "，预期 " + expected);
                context.complete();
            } finally {
                target.discard();
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = SOUND_TEST_TICKS, batchId = SOUND_BATCH)
    public void staggerInCleaveWarmupSilencesTheRemainingSounds(TestContext context) {
        // 起手与踏步声照常响；起手中被僵直打断后，劈砍、命中与收势声都不再下发。
        var fixture = prepared(context);
        var cues = soundEffects("cleave");
        Set<String> catalogIds = cues.stream()
                .map(effect -> effect.getSound().getSoundId())
                .collect(Collectors.toSet());
        fixture.connection.sounds.clear();
        press(fixture, "cleave");
        assertAcceptedLater(context, fixture, "cleave");
        context.waitAndRun(STAGGER_TICKS, () -> {
            long staggered = context.getWorld().getTime();
            try {
                interrupt(context, fixture, cues, catalogIds);
            } catch (RuntimeException | Error failure) {
                fixture.close();
                throw failure;
            }
            context.waitAndRun(SILENCE_TICKS, () -> {
                try (fixture) {
                    var late = heard(fixture, catalogIds).stream()
                            .filter(sound -> sound.tick() > staggered)
                            .map(BasicTestPlayer.HeardSound::soundId)
                            .toList();
                    context.assertTrue(late.isEmpty(), "打断后仍有功法声音：" + late);
                    context.complete();
                }
            });
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void soundOutsideTheRegistryIsSentInlineWithItsId(TestContext context) {
        // 未注册的声音照常下发：不按注册表编号，而是随包带上原 ID，客户端有该资源就能播放。
        try (var fixture = new BasicTestPlayer(context)) {
            var cue = SoundCue.newBuilder()
                    .setSoundId(INLINE_SOUND)
                    .setAnchor(SoundAnchor.CASTER)
                    .build();
            fixture.connection.sounds.clear();
            TechniqueSounds.play(fixture.player, cue);
            var heard = fixture.connection.sounds.stream().map(BasicTestPlayer.HeardSound::soundId).toList();
            context.assertTrue(heard.equals(List.of(INLINE_SOUND)), "未注册声音没有按原 ID 下发：" + heard);
            var sound = fixture.connection.sounds.get(0).packet().getSound();
            context.assertTrue(sound.getKey().isEmpty(), "未注册声音不应按注册表编号下发");
        }
        context.complete();
    }

    private static void interrupt(TestContext context, BasicTestPlayer fixture, List<TechniqueEffect> cues,
            Set<String> catalogIds) {
        // 打断前应已听到起手阶段的全部声音；伤害附属用的僵直入口随后打断可打断的起手。
        long elapsedMillis = (STAGGER_TICKS - CAST_ACCEPT_TICKS) * MILLIS_PER_TICK;
        long due = cues.stream().filter(effect -> effect.getAtMillis() < elapsedMillis).count();
        // 打断前后都要有目录声音，否则「打断后静音」会空过。
        context.assertTrue(due > 0 && due < cues.size(),
                "破门声音没有跨过打断时刻：起手 " + due + "/" + cues.size());
        var early = heard(fixture, catalogIds).stream().map(BasicTestPlayer.HeardSound::soundId).toList();
        context.assertTrue(early.size() == due, "起手阶段声音条数错误：" + early);
        var stagger = new TechniqueService.Stagger(STAGGER_MILLIS, FULL_SPEED);
        var outcome = TechniqueFabric.service().stagger(fixture.player.getUuid(), stagger);
        context.assertTrue(outcome.isPresent() && outcome.get().getPhase() == CastPhase.INTERRUPTED,
                "僵直没有打断破门起手：" + outcome);
    }

    private static List<TechniqueEffect> soundEffects(String path) {
        // 期望声音取自正式注册表里的目录定义，测试不另抄一份。
        var definition = TechniqueFabric.service().registry().findLatest(PREFIX + path).orElseThrow();
        return definition.getEffectsList().stream().filter(TechniqueEffect::hasSound).toList();
    }

    private static List<BasicTestPlayer.HeardSound> heard(BasicTestPlayer fixture, Set<String> catalogIds) {
        // 只看目录里出现过的声音 ID，原版受击、脚步等声音不计入。
        return fixture.connection.sounds.stream()
                .filter(sound -> catalogIds.contains(sound.soundId()))
                .toList();
    }

    private static BasicTestPlayer prepared(TestContext context) {
        // 开脉补元只作为准备；知识来自卷轴，攻击时两手持物强制使用单手剂量。
        var fixture = PlayerAssemblyGameTest.prepared(context);
        PlayerAssemblyGameTest.learnScroll(context, fixture, PREFIX + "upper");
        Vec3d position = Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(1, PLAYER_HEIGHT, 1)));
        fixture.player.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        fixture.player.changeGameMode(GameMode.SURVIVAL);
        fixture.player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        fixture.player.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.SHIELD));
        return fixture;
    }

    private static void press(BasicTestPlayer fixture, String path) {
        // 省略目标让宿主按玩家当前朝向选中世界实体，沿用 UI 默认选手。
        var request = TechniquePress.newBuilder().setTechniqueId(PREFIX + path).build();
        fixture.press(request);
    }

    private static double strength(BasicTestPlayer fixture) {
        // 等待起手期间没有其他做功；使用扣账前倍率，目标 damage 入口已在出手上下文内部。
        var body = fixture.body();
        body.settle(fixture.player.getServer().getOverworld().getTime() * MILLIS_PER_TICK);
        var muscle = DamagePreferences.parse("hp_per_vanilla_hp = 1").muscle();
        return muscle.plan(BodyProtobuf.toMessage(body.snapshot()),
                MuscleRules.Arm.RIGHT, MuscleRules.Grip.MAIN_HAND).coefficient();
    }

    private static void assertAcceptedLater(TestContext context, BasicTestPlayer fixture, String path) {
        // 网络接收器在主线程排队，下一轮读取唯一身体确认没有绕过施放服务。
        context.waitAndRun(CAST_ACCEPT_TICKS, () -> {
            try {
                var progress = TechniqueState.read(fixture.body().snapshot());
                context.assertTrue(progress.hasActiveCast()
                        && progress.getActiveCast().getRequest().getTechnique().getId().equals(PREFIX + path),
                        "战斗协议没有启动预期功法：" + path);
                context.assertTrue(TechniqueFabric.service().registry().findLatest(PREFIX + path).isPresent(),
                        "正式目录缺式");
            } catch (RuntimeException | Error failure) {
                fixture.close();
                throw failure;
            }
        });
    }

    private static final class ObservedCow extends CowEntity {
        private int hits;
        private double dose;
        private String damageType = "";

        private ObservedCow(ServerWorld world) {
            // 观察入口剂量但保持牛的原版无护甲防御链。
            super(EntityType.COW, world);
        }

        @Override
        public boolean damage(DamageSource source, float amount) {
            // 只记录剂量与类型，保持原版扣血及 Damage 的肌肉缩放路径。
            hits++;
            dose += amount;
            damageType = source.getTypeRegistryEntry().getKey().orElseThrow().getValue().toString();
            return super.damage(source, amount);
        }
    }
}
