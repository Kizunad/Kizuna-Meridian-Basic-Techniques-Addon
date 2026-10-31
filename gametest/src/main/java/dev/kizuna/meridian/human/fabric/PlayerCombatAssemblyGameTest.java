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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
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

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void combatProtocolCleaveBreaksWoodenDoorAndWearsIronAxe(GameTestHelper context) {
        // 生存玩家读卷后发正式战斗载荷，门与耐久都等待真实施放时钟推进。
        var fixture = prepared(context);
        BlockPos lower = fixture.player.blockPosition().south();
        var world = context.getLevel();
        world.setBlockAndUpdate(lower.below(), Blocks.STONE.defaultBlockState());
        var door = Blocks.OAK_DOOR.defaultBlockState();
        world.setBlockAndUpdate(lower, door);
        world.setBlockAndUpdate(lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        press(fixture, "cleave");
        assertAcceptedLater(context, fixture, "cleave");
        context.runAfterDelay(CLEAVE_OBSERVE_TICKS, () -> {
            try (fixture) {
                context.assertTrue(world.getBlockState(lower).isAir()
                        && world.getBlockState(lower.above()).isAir(), "战斗协议劈门未移除木门上下半");
                context.assertTrue(fixture.player.getMainHandItem().getDamageValue() == CLEAVE_WEAR,
                        "战斗协议劈门的铁斧末击耐久错误");
                context.succeed();
            } finally {
                world.setBlockAndUpdate(lower.above(), Blocks.AIR.defaultBlockState());
                world.setBlockAndUpdate(lower, Blocks.AIR.defaultBlockState());
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void combatProtocolKnockDamagesMobThroughMuscleRules(GameTestHelper context) {
        // 原始钝击剂量与护甲前肌肉倍率分开核对，目标仍进入原版实际扣血链。
        var fixture = prepared(context);
        var target = new ObservedCow(fixture.player.serverLevel());
        double initialStrength = strength(fixture);
        Vec3 position = fixture.player.position().add(0, 0, TARGET_DISTANCE);
        target.moveTo(position.x, position.y, position.z, 0, 0);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.setHealth((float) TARGET_HEALTH);
        context.getLevel().addFreshEntity(target);
        press(fixture, "knock");
        assertAcceptedLater(context, fixture, "knock");
        context.runAfterDelay(KNOCK_OBSERVE_TICKS, () -> {
            try (fixture) {
                double rawDamage = IRON_AXE_DAMAGE * KNOCK_MULTIPLIER;
                context.assertTrue(target.hits == KNOCK_HIT_COUNT && Math.abs(target.dose - rawDamage) < EPSILON,
                        "叩门未按铁斧和三击总倍率提交剂量");
                context.assertTrue(target.damageType.equals(PREFIX + "blunt_main_hand"), "叩门没有使用主手钝击类型");
                double expected = rawDamage * initialStrength;
                double actual = TARGET_HEALTH - target.getHealth();
                context.assertTrue(actual >= expected * (1 - MULTIHIT_FATIGUE_RATIO) && actual <= expected + EPSILON,
                        "Damage 肌肉缩放实伤不符：实际 " + actual + "，预期 " + expected);
                context.succeed();
            } finally {
                target.discard();
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = SOUND_TEST_TICKS, batch = SOUND_BATCH)
    public void staggerInCleaveWarmupSilencesTheRemainingSounds(GameTestHelper context) {
        // 起手与踏步声照常响；起手中被僵直打断后，劈砍、命中与收势声都不再下发。
        var fixture = prepared(context);
        var cues = soundEffects("cleave");
        Set<String> catalogIds = cues.stream()
                .map(effect -> effect.getSound().getSoundId())
                .collect(Collectors.toSet());
        fixture.connection.sounds.clear();
        press(fixture, "cleave");
        assertAcceptedLater(context, fixture, "cleave");
        context.runAfterDelay(STAGGER_TICKS, () -> {
            long staggered = context.getLevel().getGameTime();
            try {
                interrupt(context, fixture, cues, catalogIds);
            } catch (RuntimeException | Error failure) {
                fixture.close();
                throw failure;
            }
            context.runAfterDelay(SILENCE_TICKS, () -> {
                try (fixture) {
                    var late = heard(fixture, catalogIds).stream()
                            .filter(sound -> sound.tick() > staggered)
                            .map(BasicTestPlayer.HeardSound::soundId)
                            .toList();
                    context.assertTrue(late.isEmpty(), "打断后仍有功法声音：" + late);
                    context.succeed();
                }
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void soundOutsideTheRegistryIsSentInlineWithItsId(GameTestHelper context) {
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
            context.assertTrue(sound.unwrapKey().isEmpty(), "未注册声音不应按注册表编号下发");
        }
        context.succeed();
    }

    private static void interrupt(GameTestHelper context, BasicTestPlayer fixture, List<TechniqueEffect> cues,
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
        var outcome = TechniqueFabric.service().stagger(fixture.player.getUUID(), stagger);
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

    private static BasicTestPlayer prepared(GameTestHelper context) {
        // 开脉补元只作为准备；知识来自卷轴，攻击时两手持物强制使用单手剂量。
        var fixture = PlayerAssemblyGameTest.prepared(context);
        PlayerAssemblyGameTest.learnScroll(context, fixture, PREFIX + "upper");
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(1, PLAYER_HEIGHT, 1)));
        fixture.player.moveTo(position.x, position.y, position.z, 0, 0);
        fixture.player.setGameMode(GameType.SURVIVAL);
        fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        fixture.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
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
        body.settle(fixture.player.getServer().overworld().getGameTime() * MILLIS_PER_TICK);
        var muscle = DamagePreferences.parse("hp_per_vanilla_hp = 1").muscle();
        return muscle.plan(BodyProtobuf.toMessage(body.snapshot()),
                MuscleRules.Arm.RIGHT, MuscleRules.Grip.MAIN_HAND).coefficient();
    }

    private static void assertAcceptedLater(GameTestHelper context, BasicTestPlayer fixture, String path) {
        // 网络接收器在主线程排队，下一轮读取唯一身体确认没有绕过施放服务。
        context.runAfterDelay(CAST_ACCEPT_TICKS, () -> {
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

    private static final class ObservedCow extends Cow {
        private int hits;
        private double dose;
        private String damageType = "";

        private ObservedCow(ServerLevel world) {
            // 观察入口剂量但保持牛的原版无护甲防御链。
            super(EntityType.COW, world);
        }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            // 只记录剂量与类型，保持原版扣血及 Damage 的肌肉缩放路径。
            hits++;
            dose += amount;
            damageType = source.typeHolder().unwrapKey().orElseThrow().location().toString();
            return super.hurt(source, amount);
        }
    }
}
