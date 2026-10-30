package dev.kizuna.meridian.basictechniques.verification;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.server.TechniqueLeap;
import dev.kizuna.meridian.technique.server.TechniqueLeap.Point;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 用正式目录检验直线预算、落点优先级、无路回退和离散轨迹精度。 */
public final class LeapTargetTest {
    private static final double BLOCK_CENTER = 0.5;
    private static final double STANDARD_DISTANCE = 6;
    private static final double SHORT_DISTANCE = 2;
    private static final double WALL_HEIGHT = 3;
    private static final Point ORIGIN = new Point(BLOCK_CENTER, 0, BLOCK_CENTER);
    private static final Point FORWARD_TARGET = new Point(ORIGIN.x(), 0, ORIGIN.z() + STANDARD_DISTANCE);
    private static final Point FORWARD = new Point(0, 0, 1);
    private static final double EPSILON = 0.000001;
    private static final double MAXIMUM_ERROR = 0.3;
    private static final double MAXIMUM_PATH = 7;
    private static final double LAUNCH_SPEED = 12;
    private static final int MINIMUM_FLIGHT_TICKS = 6;
    private static final int MAXIMUM_FLIGHT_TICKS = 30;
    private static final List<Integer> DISTANCES = List.of(3, 5, 6, 7);
    private static final List<Integer> HEIGHTS = List.of(-4, 0, 2, 3);

    @Test
    public void catalogDeclaresPathBudgetAndLaunchSpeed() throws Exception {
        // 发行目录只使用直线预算和起跳速度，停用的距离与固定飞行字段保持为空。
        LeapProfile profile = profile();
        assertEquals(MAXIMUM_PATH, profile.getMaximumPath(), EPSILON);
        assertEquals(LAUNCH_SPEED, profile.getLaunchSpeed(), EPSILON);
        assertEquals(MINIMUM_FLIGHT_TICKS, profile.getMinimumFlightTicks());
        assertEquals(MAXIMUM_FLIGHT_TICKS, profile.getMaximumFlightTicks());
        assertEquals(0, profile.getFlightTicks());
        assertEquals(0, profile.getMaximumDistance(), EPSILON);
    }

    @Test
    public void targetsInsidePathAreReachedAndOutsideAreRejected() throws Exception {
        // 平地四档距离、两三格高台与四格下坡：直线预算内逐 tick 落到目标，超出预算的台面不被选中。
        LeapProfile profile = profile();
        for (int distance : DISTANCES) {
            for (int height : HEIGHTS) {
                Point target = new Point(ORIGIN.x(), height, ORIGIN.z() + distance);
                var landing = select(profile, target, List.of(target));
                if (TechniqueLeap.path(ORIGIN, target) <= profile.getMaximumPath()) {
                    assertTrue(landing.supported());
                    assertEquals(target, landing.point());
                    assertTrajectory(profile, target);
                } else {
                    assertFalse(landing.supported());
                    assertEquals(profile.getMaximumPath(), TechniqueLeap.path(ORIGIN, landing.point()), EPSILON);
                }
            }
        }
    }

    @Test
    public void wallFaceSnapsToWallTopAndClosePointStaysClose() throws Exception {
        // 墙面射线高度不必等于墙头高度，近处准星保留短跳。
        LeapProfile profile = profile();
        Point top = new Point(ORIGIN.x(), WALL_HEIGHT, FORWARD_TARGET.z());
        Point wall = new Point(ORIGIN.x(), 1, FORWARD_TARGET.z());
        assertEquals(top, select(profile, wall, List.of(top)).point());
        Point nearby = new Point(ORIGIN.x(), 0, ORIGIN.z() + SHORT_DISTANCE);
        assertEquals(nearby, select(profile, nearby, List.of(nearby, top)).point());
    }

    @Test
    public void unsuitableLiquidBlockedAndUnsupportedPointsChooseNearestStandingSurface() throws Exception {
        // Terrain 不返回流体、无净空和悬空点；三种无效目标都吸附到旁边的真实支撑。
        LeapProfile profile = profile();
        Point safe = new Point(ORIGIN.x() + 1, 0, FORWARD_TARGET.z());
        for (Point unsafe : List.of(FORWARD_TARGET, new Point(ORIGIN.x(), 1, FORWARD_TARGET.z()),
                new Point(ORIGIN.x(), -1, FORWARD_TARGET.z()))) {
            assertEquals(safe, select(profile, unsafe, List.of(safe)).point());
        }
    }

    @Test
    public void noNearbySurfaceUsesSightLineThenProjectedAim() throws Exception {
        // 远离准星的侧方平台不能抢走视线通道；全部无支撑时明确标记回退并落向准星。
        LeapProfile profile = profile();
        Point farSide = new Point(ORIGIN.x() + STANDARD_DISTANCE, 0, ORIGIN.z());
        assertEquals(FORWARD_TARGET, select(profile, farSide, List.of(FORWARD_TARGET)).point());
        var fallback = select(profile, FORWARD_TARGET, List.of());
        assertFalse(fallback.supported());
        assertEquals(FORWARD_TARGET, fallback.point());
        assertTrajectory(profile, fallback.point());
    }

    private static TechniqueLeap.Landing select(LeapProfile profile, Point hit, List<Point> supported) {
        // 小型地形夹具只提供可站立点，生产宿主另验证碰撞和液体适配。
        TechniqueLeap.Terrain terrain = (x, y, z) -> supported.stream()
                .filter(point -> (int) Math.floor(point.x()) == x && point.y() == y
                        && (int) Math.floor(point.z()) == z).findFirst();
        return TechniqueLeap.select(profile, new TechniqueLeap.Aim(ORIGIN, FORWARD, Optional.of(hit)), terrain);
    }

    private static void assertTrajectory(LeapProfile profile, Point target) {
        // 按直线长度求出飞行 tick 并固定初速度后不吸附位置，轨迹误差必须小于设计上限。
        int ticks = TechniqueLeap.flightTicks(profile, ORIGIN, target);
        Point velocity = TechniqueLeap.velocity(ORIGIN, target, ticks);
        double x = ORIGIN.x();
        double y = ORIGIN.y();
        double z = ORIGIN.z();
        double vertical = velocity.y();
        for (int tick = 0; tick < ticks; tick++) {
            x += velocity.x();
            y += vertical;
            z += velocity.z();
            vertical = TechniqueLeap.nextVertical(vertical);
        }
        assertTrue(Math.sqrt(new Point(x, y, z).distanceSquared(target)) <= MAXIMUM_ERROR);
    }

    private static LeapProfile profile() throws Exception {
        // 测试直接读取发行目录，数值变化会同时改变轨迹边界。
        return BasicTechniquesCatalog.registry(BasicTechniquesCatalog.load())
                .findLatest("meridian_basic_techniques:light_step").orElseThrow()
                .getEffects(0).getStatusBenefit().getLeap();
    }
}
