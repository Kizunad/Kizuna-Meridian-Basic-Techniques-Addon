package dev.kizuna.meridian.basictechniques.verification;

import com.google.gson.JsonParser;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveDuration;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiRole;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Category;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationDuration;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 正式格挡数据的独立约束，不把功法库的目录拒绝当作参数断言。 */
public final class GuardCatalogTest {
    private static final String GUARD = "meridian_basic_techniques:guard";
    private static final String ARROWS = "meridian_basic_techniques:guard_arrows";
    private static final long WARMUP_MILLIS = 400;
    private static final long RECOVERY_MILLIS = 300;
    private static final double ANGLE_DEGREES = 120;
    private static final double DAMAGE_MULTIPLIER = 0.7;
    private static final double MOVEMENT_MULTIPLIER = 0.4;
    private static final double JOULES_PER_SECOND = 10000;
    private static final double JOULES_PER_DAMAGE = 3000;
    private static final long BREAK_MILLIS = 1750;
    private static final double RESTART_RATIO = 0.2;

    @Test
    public void guardIsInnateAndAbsentFromEveryVolume() throws Exception {
        // 直接检查原始目录，删掉天生标记时明确失败在该规则上。
        var catalog = BasicTechniquesCatalog.load();
        var guard = guard();
        assertTrue("格挡必须天生会", guard.getInnate());
        assertEquals(1, guard.getVersion());
        assertEquals(Category.DEFENSE, guard.getCategory());
        assertEquals(AiRole.AI_ROLE_GUARD, guard.getAiHints().getSupport().getRole());
        for (var volume : catalog.getVolumesList()) {
            assertFalse(volume.getId(), volume.getTechniquesList().stream()
                    .anyMatch(reference -> reference.getId().equals(GUARD)));
        }
    }

    @Test
    public void guardWaitsForReleaseBeforeFullRecovery() throws Exception {
        // 持续模式必须显式声明，不能用固定时长或极大的毫秒数代替按住。
        var guard = guard();
        var phases = guard.getPhases();
        assertEquals("格挡必须持续到松手", ActiveDuration.ACTIVE_DURATION_UNTIL_RELEASE,
                phases.getActiveDuration());
        assertFalse(phases.hasActiveMillis());
        assertEquals(WARMUP_MILLIS, phases.getWarmupMillis());
        assertEquals(RECOVERY_MILLIS, phases.getRecoveryMillis());
        assertTrue(phases.getInterruptibleByDamage());
        assertTrue(guard.hasCooldownMillis());
        assertEquals(0, guard.getCooldownMillis());
        assertFalse(guard.hasFollowUp());
    }

    @Test
    public void guardAcceptsMeleeAndOnlyArrowDamageTags() throws Exception {
        // 白名单复用近战标签并精确限定箭，避免把所有弹射物都纳入格挡。
        var effects = guard().getEffectsList().stream().filter(TechniqueEffect::hasGuard).toList();
        assertEquals(1, effects.size());
        var effect = effects.getFirst();
        var guard = effect.getGuard();
        var angle = guard.getAngle();
        var multiplier = guard.getDamageMultiplier();
        var stamina = guard.getStamina();
        assertEquals(JOULES_PER_SECOND, stamina.getJoulesPerSecond(), 0);
        assertEquals(JOULES_PER_DAMAGE, stamina.getJoulesPerDamagePrevented(), 0);
        assertEquals(BREAK_MILLIS, stamina.getBreakMillis());
        assertEquals(RESTART_RATIO, stamina.getRestartRatio(), 0);
        assertEquals(WARMUP_MILLIS, effect.getAtMillis());
        assertEquals(ANGLE_DEGREES, angle.getValue(), 0);
        assertEquals(Unit.DEGREE, angle.getUnit());
        assertEquals(DAMAGE_MULTIPLIER, multiplier.getValue(), 0);
        assertEquals(Unit.RATIO, multiplier.getUnit());
        assertEquals("格挡须同时包含近战和箭", List.of("meridian:melee", ARROWS), guard.getDamageTagsList());
        try (var jar = new JarFile(System.getProperty("basicTechniques.fabricJar"))) {
            var entry = jar.getJarEntry("data/meridian_basic_techniques/tags/damage_type/guard_arrows.json");
            assertNotNull(entry);
            try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                var tag = JsonParser.parseReader(reader).getAsJsonObject();
                assertFalse(tag.get("replace").getAsBoolean());
                var values = tag.getAsJsonArray("values");
                assertEquals(1, values.size());
                assertEquals("minecraft:arrow", values.get(0).getAsString());
            }
        }
    }

    @Test
    public void mortalGuardHasNoCostSlotsOrHeldItemRequirements() throws Exception {
        // 未开脉的凡人能直接绑定空槽守式；物品和组织能力都不能成为隐藏门槛。
        var guard = guard();
        assertEquals(Realm.WANDERING_QI, guard.getMinimumRealm());
        assertEquals(0, guard.getSlotsCount());
        assertTrue(guard.hasCost());
        var cost = guard.getCost();
        assertEquals(0, cost.getQiCount());
        assertEquals(0, cost.getWorkCount());
        assertFalse(guard.hasProjectileWeapon());
        assertFalse(guard.hasRequireAmmunition());
        assertFalse(guard.hasBrewItem());
        assertEquals(MOVEMENT_MULTIPLIER, guard.getMovementSpeedMultiplier(), 0);
        assertFalse(guard.getAllowUseWhileCasting());
        assertFalse(guard.getAllowOffHandUseWhileCasting());
        var body = BasicBodies.adult(Realm.WANDERING_QI, BasicBodies.Opening.CLOSED);
        var binding = TechniqueBinder.bind(body, guard);
        assertTrue(binding.getReason(), binding.hasBinding());
        assertEquals(0, binding.getBinding().getSlotsCount());
    }

    @Test
    public void guardBorrowsOnePoseFromWarmupUntilActiveEnd() throws Exception {
        // 动作资源只声明一处；起手即抱架，松手进入收势时停止保持。
        var guard = guard();
        var effects = guard.getEffectsList().stream().filter(TechniqueEffect::hasPresentation).toList();
        assertEquals(1, effects.size());
        var effect = effects.getFirst();
        var presentation = effect.getPresentation();
        assertEquals(0, effect.getAtMillis());
        assertEquals("meridian_basic_techniques:guard_arms", presentation.getId());
        assertEquals(PresentationDuration.UNTIL_ACTIVE_END, presentation.getDuration());
        assertEquals(0, presentation.getStartMillis());
        try (var jar = new JarFile(System.getProperty("basicTechniques.fabricJar"))) {
            String iconPath = guard.getPresentation().getIconId().replace(":", "/textures/gui/technique/");
            assertNotNull(jar.getJarEntry("assets/" + iconPath + ".png"));
        }
    }

    private static TechniqueDefinition guard() throws Exception {
        // 参数单测先读原始定义，完整注册表验证由 BasicCatalogTest 负责。
        return BasicTechniquesCatalog.load().getDefinitionsList().stream()
                .filter(definition -> definition.getId().equals(GUARD))
                .findFirst().orElseThrow(() -> new AssertionError("基础功法目录缺格挡"));
    }
}
