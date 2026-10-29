package dev.kizuna.meridian.basictechniques.verification;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationDuration;
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
    private static final int STEP_MUSCLES = 4;
    private static final double STEP_TOTAL_POWER = 800;
    private static final double MINIMUM_LEG_STRENGTH = 510;
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
        // 三卷互不依赖，每式的表现由起手持续至收势结束。
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
            assertEquals(0, effect.getAtMillis());
            assertEquals(PresentationDuration.UNTIL_RECOVERY_END, effect.getPresentation().getDuration());
            assertEquals(0, effect.getPresentation().getStartMillis());
            assertTrue(definition.getPresentation().getIconId().startsWith("meridian_basic_techniques:"));
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
    public void lightStepDistributesWorkAcrossFourCapableAdultLegMuscles() throws Exception {
        // 真实成人绑定应选四块主要腿肌，按模块收费后仍保持总计 800 W。
        var registry = BasicTechniquesCatalog.registry(BasicTechniquesCatalog.load());
        var step = registry.findLatest("meridian_basic_techniques:light_step").orElseThrow();
        var body = BasicBodies.adult(Realm.WANDERING_QI, BasicBodies.Opening.FOOT);
        var requirement = step.getSlotsList().stream()
                .filter(candidate -> candidate.getId().equals("legs")).findFirst().orElseThrow();
        assertEquals(MINIMUM_LEG_STRENGTH, requirement.getMinimumStrength().getValue(), 0);
        var binding = TechniqueBinder.bind(body, step).getBinding();
        var legs = binding.getSlotsList().stream()
                .filter(slot -> slot.getSlotId().equals("legs")).findFirst().orElseThrow();
        assertEquals(STEP_MUSCLES, legs.getModuleIdsCount());
        var work = step.getCost().getWork(0);
        assertEquals(STEP_TOTAL_POWER, legs.getModuleIdsCount() * work.getPower().getValue(), EPSILON);
    }
}
