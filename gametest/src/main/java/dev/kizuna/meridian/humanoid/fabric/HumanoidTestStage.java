package dev.kizuna.meridian.humanoid.fabric;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;

/** 一个类人用例的延迟步骤：任何一步失败都先清理在线玩家、实体和全局规则，保留原始失败原因。 */
final class HumanoidTestStage {
    private final GameTestHelper context;
    private final Runnable cleanup;

    HumanoidTestStage(GameTestHelper context, Runnable cleanup) {
        // 清理动作由用例给出。
        this.context = context;
        this.cleanup = cleanup;
    }

    void later(int ticks, Runnable action) {
        // 固定延迟后执行。
        context.runAfterDelay(ticks, () -> guarded(action));
    }

    void beforeDecision(Mob mob, Runnable action) {
        // 类人大脑按实体 ID 错相决策；等到它下次决策的前一 tick 再执行，下一 tick 先由大脑看到这一步的结果。
        int interval = HumanoidBrain.config().schedule().decisionIntervalTicks();
        if (Math.floorMod(mob.tickCount + 1L + mob.getId(), interval) == 0) {
            guarded(action);
            return;
        }
        context.runAfterDelay(1, () -> beforeDecision(mob, action));
    }

    void await(int ticks, BooleanSupplier condition, Supplier<String> failure, Runnable next) {
        // 每 tick 检查一次，期限内满足就继续，到期仍不满足即失败；26.2 的断言异常带组件消息与失败 tick。
        if (condition.getAsBoolean()) {
            guarded(next);
            return;
        }
        if (ticks == 0) {
            guarded(() -> {
                throw context.assertionException(Component.literal(failure.get()));
            });
            return;
        }
        context.runAfterDelay(1, () -> await(ticks - 1, condition, failure, next));
    }

    void finish() {
        // 清理后报告通过。
        cleanup.run();
        context.succeed();
    }

    private void guarded(Runnable step) {
        // 断言失败也先清理再抛出。
        try {
            step.run();
        } catch (RuntimeException | Error failure) {
            cleanup.run();
            throw failure;
        }
    }
}
