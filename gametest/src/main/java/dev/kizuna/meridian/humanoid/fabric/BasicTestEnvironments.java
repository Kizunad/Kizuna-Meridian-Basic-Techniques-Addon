package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.serialization.MapCodec;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;

/**
 * GameTest 批次环境类型：批次开始前学卷表只留破门卷，批次结束后换回启动配置。
 *
 * <p>26.2 按环境分批并在批次前后调用环境的 setup/teardown，代替 1.20.1 的批次前后钩子。
 */
public final class BasicTestEnvironments implements ModInitializer {
    private static final Identifier DOOR_BREAKER_ONLY =
            Identifier.fromNamespaceAndPath("meridian_basic_techniques_test", "door_breaker_only");

    @Override
    public void onInitialize() {
        // 环境类型须在注册表冻结前登记；test_environment 数据按类型 ID 引用。
        Registry.register(BuiltInRegistries.TEST_ENVIRONMENT_DEFINITION_TYPE, DOOR_BREAKER_ONLY,
                DoorBreakerOnly.CODEC);
    }

    private enum DoorBreakerOnly implements TestEnvironmentDefinition<Unit> {
        INSTANCE;

        private static final MapCodec<DoorBreakerOnly> CODEC = MapCodec.unit(INSTANCE);

        @Override
        public Unit setup(ServerLevel level) {
            // 卫道士整合用例只发破门卷，大脑只在破门式里选。
            DoorBreakerOnlyVolumes.install();
            return Unit.INSTANCE;
        }

        @Override
        public void teardown(ServerLevel level, Unit saved) {
            // 后续批次用启动配置。
            DoorBreakerOnlyVolumes.restore();
        }

        @Override
        public MapCodec<DoorBreakerOnly> codec() {
            // 环境数据不带参数，类型本身就是配置。
            return CODEC;
        }
    }
}
