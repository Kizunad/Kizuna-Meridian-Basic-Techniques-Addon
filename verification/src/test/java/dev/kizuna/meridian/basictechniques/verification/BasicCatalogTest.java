package dev.kizuna.meridian.basictechniques.verification;

import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationDuration;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCostMatching;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BasicCatalogTest {
    private static final int TECHNIQUE_COUNT = 3;
    private static final int VOLUME_COUNT = 3;
    private static final int STEP_MUSCLES = 6;
    private static final double STEP_TOTAL_POWER = 800;
    private static final double EPSILON = 0.000001;

    @Test
    public void productionJarDeclaresCatalogAndContainsNoJava() throws Exception {
        // 实际安装包必须只包含数据，清单路径经正式解析器读取。
        try (var jar = new JarFile(System.getProperty("basicTechniques.fabricJar"))) {
            String path = BasicTechniquesCatalog.RESOURCE.substring(1);
            var metadata = jar.getJarEntry("fabric.mod.json");
            String json = new String(jar.getInputStream(metadata).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"meridian_technique:catalogs\": \"" + path + "\""));
            assertFalse(json.contains("entrypoints"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().endsWith(".class")));
            String text = new String(jar.getInputStream(jar.getJarEntry(path)).readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(BasicTechniquesCatalog.load(), TechniqueCatalogManifest.parseText("basic:" + path, text));
        }
    }

    @Test
    public void catalogValidatesIndependentVolumesAndPresentationLifetimes() throws Exception {
        // 三卷互不依赖，轻步施放仅给予状态，其余两式播放施放表现。
        var registry = BasicTechniquesCatalog.registry(BasicTechniquesCatalog.load());
        assertEquals(TECHNIQUE_COUNT, registry.definitions().size());
        assertEquals(VOLUME_COUNT, registry.volumes().size());
        assertEquals(1, registry.books().size());
        for (var volume : registry.volumes()) {
            assertEquals(0, volume.getPrerequisiteVolumeIdsCount());
            assertEquals(1, volume.getTechniquesCount());
        }
        for (var definition : registry.definitions()) {
            var effect = definition.getEffects(0);
            if (effect.hasStatusBenefit()) {
                assertEquals(definition.getPhases().getWarmupMillis(), effect.getAtMillis());
                assertEquals("meridian_basic_techniques:light_body", effect.getStatusBenefit().getId());
                continue;
            }
            assertEquals(0, effect.getAtMillis());
            assertEquals(PresentationDuration.UNTIL_RECOVERY_END, effect.getPresentation().getDuration());
            assertEquals(0, effect.getPresentation().getStartMillis());
            assertTrue(definition.getPresentation().getIconId().startsWith("meridian_basic_techniques:"));
        }
    }

    @Test
    public void allTechniquesAcceptAnyAttributeAtOrAboveFreePhase() throws Exception {
        // 三式只限制最低相态，不把属性或游离态精确匹配带回正式目录。
        var registry = BasicTechniquesCatalog.registry(BasicTechniquesCatalog.load());
        assertEquals(TECHNIQUE_COUNT, registry.definitions().size());
        for (var definition : registry.definitions()) {
            var techniqueCost = definition.getCost();
            assertEquals(1, techniqueCost.getQiCount());
            var cost = techniqueCost.getQi(0);
            assertEquals(QiCostMatching.ANY_ATTRIBUTE_AT_LEAST_PHASE, cost.getMatching());
            assertTrue(cost.getAttribute().isEmpty());
            assertEquals(QiPhase.FREE, cost.getPhase());
        }
    }

    @Test
    public void wanderingAndTrueQiBindOnlyOpenedMatchingSources() throws Exception {
        // 相同成人模板在两个境界上都按当前开拓事实判定，不因境界较高自动开脉。
        var registry = BasicTechniquesCatalog.registry(BasicTechniquesCatalog.load());
        for (Realm realm : new Realm[]{Realm.WANDERING_QI, Realm.TRUE_QI}) {
            var closed = BasicBodies.adult(realm, BasicBodies.Opening.CLOSED);
            var all = BasicBodies.adult(realm, BasicBodies.Opening.ALL);
            for (var definition : registry.definitions()) {
                assertEquals(TechniqueFailure.MERIDIAN_NOT_OPENED,
                        TechniqueBinder.bind(closed, definition).getFailure());
                assertTrue(TechniqueBinder.bind(all, definition).hasBinding());
            }
            var hand = BasicBodies.adult(realm, BasicBodies.Opening.HAND);
            var foot = BasicBodies.adult(realm, BasicBodies.Opening.FOOT);
            var charge = registry.findLatest("meridian_basic_techniques:charge").orElseThrow();
            var step = registry.findLatest("meridian_basic_techniques:light_step").orElseThrow();
            assertTrue(TechniqueBinder.bind(hand, charge).hasBinding());
            assertTrue(TechniqueBinder.bind(foot, step).hasBinding());
            assertEquals(TechniqueFailure.MERIDIAN_NOT_OPENED, TechniqueBinder.bind(hand, step).getFailure());
            assertEquals(TechniqueFailure.MERIDIAN_NOT_OPENED, TechniqueBinder.bind(foot, charge).getFailure());
        }
    }

    @Test
    public void lightStepDistributesWorkAcrossAllSixAdultLegMuscles() throws Exception {
        // 六块腿肌共同支付跃步做功，不再以绝对力量排除腓肠肌或疲劳肌肉。
        var registry = BasicTechniquesCatalog.registry(BasicTechniquesCatalog.load());
        var step = registry.findLatest("meridian_basic_techniques:light_step").orElseThrow();
        var body = BasicBodies.adult(Realm.WANDERING_QI, BasicBodies.Opening.FOOT);
        var requirement = step.getSlotsList().stream()
                .filter(candidate -> candidate.getId().equals("legs")).findFirst().orElseThrow();
        assertFalse(requirement.hasMinimumStrength());
        assertEquals(STEP_MUSCLES, requirement.getMinimumCount());
        assertEquals(STEP_MUSCLES, requirement.getMaximumCount());
        var binding = TechniqueBinder.bind(body, step).getBinding();
        var legs = binding.getSlotsList().stream()
                .filter(slot -> slot.getSlotId().equals("legs")).findFirst().orElseThrow();
        assertEquals(STEP_MUSCLES, legs.getModuleIdsCount());
        assertEquals(0, step.getCost().getWorkCount());
        var work = step.getEffects(0).getStatusBenefit().getLeap().getWork();
        double perMuscle = work.getPower().getValue();
        assertEquals(STEP_TOTAL_POWER, legs.getModuleIdsCount() * perMuscle, EPSILON);
        for (String module : legs.getModuleIdsList()) {
            var muscle = body.structure().tissues().stream()
                    .filter(tissue -> tissue.id().value().equals(module)).findFirst().orElseThrow();
            assertTrue(muscle.capacity().burstWatts() > perMuscle);
        }
        assertTrue(legs.getModuleIdsList().contains("left_gastrocnemius"));
        assertTrue(legs.getModuleIdsList().contains("right_gastrocnemius"));
    }
}
