package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhases;
import dev.kizuna.meridian.technique.server.TechniqueCastEndReason;
import dev.kizuna.meridian.technique.server.TechniqueCastEnded;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 玩家按键放带「朝目标推进」的式，面前没有实体：整套装配里要能放完。
 * 闯门和万门一斩都有朝目标的冲量；对着门、对着空地放时没有实体目标，冲量按面朝的方向给，不能让整式半途被身体拒绝。
 */
public final class PlayerFollowUpAssemblyGameTest {
    private static final String PREFIX = "meridian_door_breaker:";
    private static final List<String> VOLUMES = List.of("upper", "middle", "lower");
    private static final PermissionSet COMMAND_PERMISSION = LevelBasedPermissionSet.GAMEMASTER;
    private static final int PLAYER_HEIGHT = 2;
    private static final int CAST_ACCEPT_TICKS = 2;
    private static final long MILLIS_PER_TICK = 50;
    /** 承接窗口从前式收势过半开；多等两 tick 再按，稳在窗口里。 */
    private static final double WINDOW_RECOVERY_RATIO = 0.5;
    private static final int WINDOW_MARGIN_TICKS = 2;
    /** 一式放完以后再等一会儿，结束事件一定已经发出。 */
    private static final int END_MARGIN_TICKS = 20;
    /** 闯门 4.25 秒，留余量。 */
    private static final int RAM_TEST_TICKS = 140;
    /** 劈门 4.4 秒里的前 3.1 秒，加万门一斩 7.05 秒，再留余量。 */
    private static final int FINALE_TEST_TICKS = 300;

    @GameTest(maxTicks = RAM_TEST_TICKS)
    public void keyPressRamWithNothingInFrontRunsToItsEnd(GameTestHelper context) {
        // 真炁玩家持铁斧对着空地按闯门：三段朝目标的冲量都没有目标可朝，闯门仍要正常放完。
        var watch = new Watch(prepared(context));
        press(watch.fixture, "ram");
        context.runAfterDelay(CAST_ACCEPT_TICKS, () -> awaitCompleted(context, watch, "ram"));
    }

    @GameTest(maxTicks = FINALE_TEST_TICKS)
    public void keyPressFinaleAfterCleaveRunsToItsEnd(GameTestHelper context) {
        // 按劈门，收势过半时按万门一斩（只能承接）。面前没有实体，万门一斩起跳时朝目标的冲量没有目标可朝，仍要正常放完。
        var watch = new Watch(prepared(context));
        CastPhases cleave = phases("cleave");
        int untilWindow = ticks(cleave.getWarmupMillis() + cleave.getActiveMillis()
                + (long) (cleave.getRecoveryMillis() * WINDOW_RECOVERY_RATIO)) + WINDOW_MARGIN_TICKS;
        press(watch.fixture, "cleave");
        context.runAfterDelay(CAST_ACCEPT_TICKS + untilWindow, () -> {
            watch.guarded(() -> press(watch.fixture, "finale"));
            context.runAfterDelay(CAST_ACCEPT_TICKS, () -> awaitCompleted(context, watch, "finale"));
        });
    }

    private static void awaitCompleted(GameTestHelper context, Watch watch, String path) {
        // 按键受理以后：身上正在放的必须是这一式；记下它的施放编号，等它的时长过完，结束原因必须是放完。
        String castId = watch.guarded(() -> {
            var active = TechniqueState.read(watch.fixture.body().snapshot()).getActiveCast().getRequest();
            context.assertTrue(active.getTechnique().getId().equals(PREFIX + path),
                    "按键没有放出 " + path + "，现在是：" + active.getTechnique().getId() + "，按键回执：" + watch.replies);
            return active.getCastId();
        });
        CastPhases phases = phases(path);
        int castTicks = ticks(phases.getWarmupMillis() + phases.getActiveMillis() + phases.getRecoveryMillis());
        context.runAfterDelay(castTicks + END_MARGIN_TICKS, () -> {
            try (watch) {
                var end = watch.ended.stream().filter(event -> event.castId().equals(castId)).findFirst();
                context.assertTrue(end.isPresent(), path + " 到时间还没有结束：" + watch.ended);
                context.assertTrue(end.get().reason() == TechniqueCastEndReason.COMPLETED,
                        path + " 没有放完：" + end.get());
                context.succeed();
            }
        });
    }

    /** 一个测试玩家和它这一局的施放回执、结束事件；关掉时撤监听器、让玩家下线。 */
    private static final class Watch implements AutoCloseable {
        private final BasicTestPlayer fixture;
        private final List<TechniqueCastEnded> ended = new ArrayList<>();
        private final List<String> replies = new ArrayList<>();
        private final Consumer<TechniqueCastEnded> endListener;
        private final Consumer<TechniqueService.CastObserved> castListener;

        private Watch(BasicTestPlayer fixture) {
            // 同一批里别的测试也在施放，只记这个玩家的。
            this.fixture = fixture;
            this.endListener = event -> {
                if (event.entityUuid().equals(fixture.player.getUUID())) {
                    ended.add(event);
                }
            };
            this.castListener = event -> {
                if (event.entityUuid().equals(fixture.player.getUUID())) {
                    var outcome = event.outcome();
                    replies.add(outcome.getPhase() + "/" + outcome.getFailure() + "/" + outcome.getReason());
                }
            };
            TechniqueFabric.service().addCastEndListener(endListener);
            TechniqueFabric.service().addCastListener(castListener);
        }

        private void guarded(Runnable action) {
            guarded(() -> {
                action.run();
                return null;
            });
        }

        private <T> T guarded(Supplier<T> action) {
            // 中途失败也要撤监听器、让玩家下线，原异常交给 GameTest 报告。
            try {
                return action.get();
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        @Override
        public void close() {
            TechniqueFabric.service().removeCastEndListener(endListener);
            TechniqueFabric.service().removeCastListener(castListener);
            fixture.close();
        }
    }

    private static BasicTestPlayer prepared(GameTestHelper context) {
        // 开脉、升真炁、补满真元只作准备；三卷走正式卷轴学，主手铁斧、副手空着。
        var fixture = PlayerAssemblyGameTest.prepared(context);
        try {
            var source = fixture.player.createCommandSourceStack().withPermission(COMMAND_PERMISSION);
            context.assertTrue(BasicTestCommands.run(source, "meridian body realm @s true_qi") > 0, "升真炁失败");
            // 万门一斩要手经里气态以上的真元；夹具默认补的是游离态，这里按密度 1 把全身内腔覆盖成够用的量。
            context.assertTrue(BasicTestCommands.run(source, "meridian qi set @s 300 all lumen 1") > 0, "补元失败");
            VOLUMES.forEach(volume -> PlayerAssemblyGameTest.learnScroll(context, fixture, PREFIX + volume));
            Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(1, PLAYER_HEIGHT, 1)));
            fixture.player.snapTo(position.x, position.y, position.z, 0, 0);
            fixture.player.setGameMode(GameType.SURVIVAL);
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            return fixture;
        } catch (RuntimeException | Error failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void press(BasicTestPlayer fixture, String path) {
        // 和玩家按键一样只带式的编号：是不是承接由服务端按窗口判断。
        fixture.press(TechniquePress.newBuilder().setTechniqueId(PREFIX + path).build());
    }

    private static CastPhases phases(String path) {
        return TechniqueFabric.service().registry().findLatest(PREFIX + path).orElseThrow().getPhases();
    }

    private static int ticks(long millis) {
        return Math.toIntExact(millis / MILLIS_PER_TICK);
    }
}
