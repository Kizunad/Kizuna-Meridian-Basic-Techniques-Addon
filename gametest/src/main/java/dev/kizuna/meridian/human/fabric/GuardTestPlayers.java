package dev.kizuna.meridian.human.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** 未开脉、未学卷的新生玩家，施放与受伤均使用 Human 的正式世界 Store。 */
final class GuardTestPlayers {
    private static final int PLAYER_HEIGHT = 3;

    private GuardTestPlayers() {
        // 凡人夹具不注册测试宿主，也不替换正式服务。
    }

    static BasicTestPlayer mortal(GameTestHelper context) {
        // 沿用原生登录出生，不开脉、不补元、不学习，也不修改身体快照。
        var fixture = new BasicTestPlayer(context);
        context.runBeforeTestEnd(fixture::close);
        fixture.player.setGameMode(GameType.SURVIVAL);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(1, PLAYER_HEIGHT, 1)));
        fixture.player.snapTo(position.x, position.y, position.z, 0, 0);
        return fixture;
    }
}
