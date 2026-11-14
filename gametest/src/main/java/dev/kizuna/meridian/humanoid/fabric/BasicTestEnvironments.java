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
 * GameTest 批次环境类型：批次开始前学卷表只留一个功法目录的卷，批次结束后换回启动配置。
 *
 * <p>26.2 按环境分批并在批次前后调用环境的 setup/teardown，代替 1.20.1 的批次前后钩子。
 */
public final class BasicTestEnvironments implements ModInitializer {
    private static final String NAMESPACE = "meridian_basic_techniques_test";
    /** 卫道士整合用例只发破门卷，大脑只在破门式里选。 */
    private static final OnlyVolumesType DOOR_BREAKER_ONLY = new OnlyVolumesType("meridian_door_breaker:");
    /** 「只会基础三卷」的用例：别的功法包登记给生人的卷（如村民的袖手拳）都不发。 */
    private static final OnlyVolumesType BASIC_ONLY = new OnlyVolumesType("meridian_basic_techniques:");

    @Override
    public void onInitialize() {
        // 环境类型须在注册表冻结前登记；test_environment 数据按类型 ID 引用。
        Registry.register(BuiltInRegistries.TEST_ENVIRONMENT_DEFINITION_TYPE,
                Identifier.fromNamespaceAndPath(NAMESPACE, "door_breaker_only"), DOOR_BREAKER_ONLY.codec);
        Registry.register(BuiltInRegistries.TEST_ENVIRONMENT_DEFINITION_TYPE,
                Identifier.fromNamespaceAndPath(NAMESPACE, "basic_only"), BASIC_ONLY.codec);
    }

    /** 一种「只发某个目录的卷」的环境类型：卷 ID 前缀和它的编解码器。 */
    private static final class OnlyVolumesType {
        private final String prefix;
        private final MapCodec<OnlyVolumesEnvironment> codec;

        private OnlyVolumesType(String prefix) {
            // 注册表按对象身份拒收重复值，所以每份数据解出一个新实例，不能共用单例。
            this.prefix = prefix;
            this.codec = MapCodec.unit(() -> new OnlyVolumesEnvironment(this));
        }
    }

    /** 一份环境数据；引用同一类型的几份数据各自是一个批次。 */
    private record OnlyVolumesEnvironment(OnlyVolumesType type) implements TestEnvironmentDefinition<Unit> {
        @Override
        public Unit setup(ServerLevel level) {
            // 批次里出生的生人只学这个目录的卷。
            OnlyVolumes.install(type.prefix);
            return Unit.INSTANCE;
        }

        @Override
        public void teardown(ServerLevel level, Unit saved) {
            // 后续批次用启动配置。
            OnlyVolumes.restore();
        }

        @Override
        public MapCodec<OnlyVolumesEnvironment> codec() {
            // 环境数据不带参数，类型本身就是配置。
            return type.codec;
        }
    }
}
