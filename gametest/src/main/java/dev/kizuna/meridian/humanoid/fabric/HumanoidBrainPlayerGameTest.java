package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.fabric.BasicTestPlayer;
import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 类人大脑对真实玩家：被打后撤退或还手，卫道士轻身跃到静立玩家面前再出招。 */
public final class HumanoidBrainPlayerGameTest {
    private static final String DOOR_BREAKER_PREFIX = "meridian_door_breaker:";
    private static final String LIGHT_STEP = "meridian_basic_techniques:light_step";
    private static final int TEST_TIMEOUT = 600;
    private static final int JOIN_READY_TICKS = 65;
    private static final int REACTION_TICKS = 400;
    private static final BlockPos SUBJECT_POS = new BlockPos(2, 0, 2);
    private static final double HIT_DISTANCE = 2;
    private static final double ASSAULT_DISTANCE = 6;
    private static final double RETREAT_GAIN = 2;
    private static final double LANDING_MIN = 1;
    private static final double LANDING_MAX = 2;
    private static final float PLAYER_YAW = 180;
    private static final Set<Decision.Action> RETREATS = EnumSet.of(Decision.Action.RETREAT, Decision.Action.KITE);

    @GameTest(maxTicks = TEST_TIMEOUT,
            environment = "meridian_basic_techniques_test:humanoid_brain_player_retreat")
    public void wanderingVillagerHitByPlayerRetreats(GameTestHelper context) {
        // 只会基础三卷的游炁村民被玩家打一拳后由大脑撤退拉开。
        Villager villager = spawn(context, EntityTypes.VILLAGER, HumanoidTier.WANDERING_QI, "player-retreat");
        var fixture = new BasicTestPlayer(context);
        ServerPlayer player = standing(fixture, villager, HIT_DISTANCE);
        var stage = new HumanoidTestStage(context, () -> {
            // 断线并移除村民。
            villager.discard();
            fixture.close();
        });
        var track = new Track(villager, player);
        stage.later(JOIN_READY_TICKS, () -> {
            double start = villager.distanceTo(player);
            player.attack(villager);
            stage.await(REACTION_TICKS,
                    () -> track.observe().sawAny(RETREATS) && villager.distanceTo(player) > start + RETREAT_GAIN,
                    () -> "被打的村民没有撤退拉开：" + track, stage::finish);
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT,
            environment = "meridian_basic_techniques_test:humanoid_brain_player_fight_back")
    public void armedTrueQiVillagerHitByPlayerFightsBack(GameTestHelper context) {
        // 学了破门上卷、持斧的真炁村民被玩家打一拳后还手，破门式经 Human 扣玩家生命。
        Villager villager = spawn(context, EntityTypes.VILLAGER, HumanoidTier.TRUE_QI, "player-fight-back");
        villager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        HumanoidTechniqueHost.prepare(villager);
        TechniqueFabric.service().learn(villager.getUUID(), DOOR_BREAKER_PREFIX + "upper");
        var fixture = new BasicTestPlayer(context);
        ServerPlayer player = standing(fixture, villager, HIT_DISTANCE);
        var stage = new HumanoidTestStage(context, () -> {
            // 断线并移除村民。
            villager.discard();
            fixture.close();
        });
        var track = new Track(villager, player);
        stage.later(JOIN_READY_TICKS, () -> {
            float health = player.getHealth();
            player.attack(villager);
            stage.await(REACTION_TICKS,
                    () -> track.observe().castAny(DOOR_BREAKER_PREFIX) && player.getHealth() < health,
                    () -> "被打的真炁村民没有还手伤到玩家：" + track, stage::finish);
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT,
            environment = "meridian_basic_techniques_test:humanoid_brain_player_assault")
    public void trueQiVindicatorLeapsToStandingPlayerAndStrikes(GameTestHelper context) {
        // 持斧真炁卫道士对六格外静立的玩家先轻身，跃到玩家面前一到两格，再出破门打击式。
        Vindicator vindicator = spawn(context, EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, "player-assault");
        vindicator.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        vindicator.setNoAi(true /* aiDisabled */);
        var fixture = new BasicTestPlayer(context);
        ServerPlayer player = standing(fixture, vindicator, ASSAULT_DISTANCE);
        var stage = new HumanoidTestStage(context, () -> {
            // 断线并移除卫道士。
            vindicator.discard();
            fixture.close();
        });
        var track = new Track(vindicator, player);
        stage.later(JOIN_READY_TICKS, () -> {
            vindicator.setTarget(player);
            vindicator.setNoAi(false /* aiDisabled */);
            stage.await(REACTION_TICKS, () -> track.observe().struckAfterLanding(DOOR_BREAKER_PREFIX),
                    () -> "卫道士没有轻身跃到玩家面前出招：" + track, () -> {
                        context.assertTrue(track.cast(LIGHT_STEP), "跃步前没有轻步：" + track);
                        double landing = track.landingDistance();
                        context.assertTrue(landing >= LANDING_MIN && landing <= LANDING_MAX,
                                "跃步落点不在玩家面前一到两格：" + track);
                        stage.finish();
                    });
        });
    }

    private static <T extends Mob> T spawn(GameTestHelper context, EntityType<T> type, HumanoidTier tier,
                                                 String name) {
        // 固定 UUID 让选式种子可重复；GameTest 世界跨运行保留，上次超时留下的同 UUID 实体先移除。
        UUID id = UUID.nameUUIDFromBytes(("humanoid-brain-player/" + name).getBytes(StandardCharsets.UTF_8));
        Entity stale = context.getLevel().getEntity(id);
        if (stale != null) {
            stale.discard();
        }
        T mob = type.create(context.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(SUBJECT_POS));
        mob.setUUID(id);
        mob.snapTo(position.x, position.y, position.z, 0, 0);
        mob.setPersistenceRequired();
        HumanoidSpeciesBirths.birth(mob, tier);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "实体未加入世界：" + name);
        return mob;
    }

    private static ServerPlayer standing(BasicTestPlayer fixture, Mob mob, double distance) {
        // 生存模式玩家站在实体正南若干格、面朝实体，不移动。
        ServerPlayer player = fixture.player();
        Vec3 position = mob.position().add(0, 0, distance);
        player.snapTo(position.x, position.y, position.z, PLAYER_YAW, 0);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static List<String> castTechniques(Mob mob) {
        // 权威快照里上一次与正在施放的式，先旧后新。
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(
                ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot()));
        var result = new ArrayList<String>();
        if (progress.hasLastCast()) {
            result.add(progress.getLastCast().getTechnique().getId());
        }
        if (progress.hasActiveCast()) {
            result.add(progress.getActiveCast().getRequest().getTechnique().getId());
        }
        return result;
    }

    /** 逐 tick 记下一只实体出现过的式、决策动作与跃步落点。 */
    private static final class Track {
        private final Mob mob;
        private final LivingEntity target;
        private final Set<String> techniques = new LinkedHashSet<>();
        private final Set<String> afterLanding = new LinkedHashSet<>();
        private final Set<Decision.Action> actions = EnumSet.noneOf(Decision.Action.class);
        private long lastDecisionTick = Long.MIN_VALUE;
        private boolean leaping;
        private double landingDistance = Double.NaN;

        private Track(Mob mob, LivingEntity target) {
            // 落点按到该目标的距离记录。
            this.mob = mob;
            this.target = target;
        }

        Track observe() {
            // 记当前施放、新增决策与跃步落地；返回自己便于在条件里链式读取。
            List<String> casts = castTechniques(mob);
            techniques.addAll(casts);
            if (landed()) {
                afterLanding.addAll(casts);
            }
            boolean inFlight = ((TechniqueLeapAccess) mob).meridian$getLeap() != null;
            if (leaping && !inFlight && !landed()) {
                landingDistance = mob.distanceTo(target);
            }
            leaping = inFlight;
            for (var logged : HumanoidBrain.decisions(mob.getUUID())) {
                if (logged.tick() > lastDecisionTick) {
                    lastDecisionTick = logged.tick();
                    actions.add(logged.decision().action());
                }
            }
            return this;
        }

        private boolean landed() {
            // 是否已有一次跃步落地。
            return !Double.isNaN(landingDistance);
        }

        boolean cast(String technique) {
            // 是否施放过这一式。
            return techniques.contains(technique);
        }

        boolean castAny(String prefix) {
            // 是否施放过某个目录的式。
            return techniques.stream().anyMatch(technique -> technique.startsWith(prefix));
        }

        boolean struckAfterLanding(String prefix) {
            // 跃步落地后是否施放过某个目录的式。
            return afterLanding.stream().anyMatch(technique -> technique.startsWith(prefix));
        }

        boolean sawAny(Set<Decision.Action> wanted) {
            // 是否做过其中任一动作的决策。
            return wanted.stream().anyMatch(actions::contains);
        }

        double landingDistance() {
            // 第一次跃步落地时到目标的距离；尚未落地为 NaN。
            return landingDistance;
        }

        @Override
        public String toString() {
            // 失败信息用的摘要。
            return mob.getStringUUID() + " techniques=" + techniques + " afterLanding=" + afterLanding
                    + " actions=" + actions + " landing=" + landingDistance
                    + " decisions=" + HumanoidBrain.decisions(mob.getUUID()).stream()
                    .map(logged -> logged.tick() + ":" + logged.decision().action() + "(" + logged.decision().reason()
                            + ")")
                    .toList();
        }
    }
}
