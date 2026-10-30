package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 一个类人用例的延迟步骤：任何一步失败都先清理在线玩家、实体和全局规则，保留原始失败原因。 */
final class HumanoidTestStage {
    private final TestContext context;
    private final Runnable cleanup;

    HumanoidTestStage(TestContext context, Runnable cleanup) {
        // 清理动作由用例给出。
        this.context = context;
        this.cleanup = cleanup;
    }

    void later(int ticks, Runnable action) {
        // 固定延迟后执行。
        context.waitAndRun(ticks, () -> guarded(action));
    }

    void await(int ticks, BooleanSupplier condition, Supplier<String> failure, Runnable next) {
        // 每 tick 检查一次，期限内满足就继续，到期仍不满足即失败。
        if (condition.getAsBoolean()) {
            guarded(next);
            return;
        }
        if (ticks == 0) {
            guarded(() -> {
                throw new GameTestException(failure.get());
            });
            return;
        }
        context.waitAndRun(1, () -> await(ticks - 1, condition, failure, next));
    }

    void finish() {
        // 清理后报告通过。
        cleanup.run();
        context.complete();
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
