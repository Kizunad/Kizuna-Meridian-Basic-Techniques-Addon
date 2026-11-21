package dev.kizuna.meridian.basictechniques.verification;

import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationDuration;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCostMatching;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ScrollDrop;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BasicCatalogTest {
    private static final int TECHNIQUE_COUNT = 5;
    private static final int VOLUME_COUNT = 4;
    private static final int BOOK_COUNT = 2;
    private static final String BASIC_BOOK = "meridian_basic_techniques:basic";
    private static final String REBORN = "meridian_basic_techniques:later_breathing";
    private static final String REBORN_BOOK = REBORN + "_book";
    private static final String REBORN_VOLUME = REBORN + "_volume";
    private static final int STEP_MUSCLES = 6;
    private static final double STEP_TOTAL_POWER = 800;
    private static final double EPSILON = 0.000001;
    private static final double SCROLL_DROP_CHANCE = 0.05;
    private static final List<String> HUMANOIDS = List.of("minecraft:villager", "minecraft:wandering_trader",
            "minecraft:pillager", "minecraft:vindicator", "minecraft:evoker", "minecraft:witch",
            "minecraft:illusioner");

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
        // 三卷互不依赖；天生守式的表现只保持到生效段结束。
        var registry = BasicTechniquesCatalog.registry(BasicTechniquesCatalog.load());
        var definitions = registry.definitions();
        var volumes = registry.volumes();
        assertEquals(TECHNIQUE_COUNT, definitions.size());
        assertEquals(VOLUME_COUNT, volumes.size());
        assertEquals(BOOK_COUNT, registry.books().size());
        for (var volume : volumes) {
            assertEquals(0, volume.getPrerequisiteVolumeIdsCount());
            assertEquals(1, volume.getTechniquesCount());
        }
        for (var definition : definitions) {
            if (definition.getId().equals(REBORN)) {
                assertTrue(definition.hasMaintainedBreathing());
                continue;
            }
            var effect = definition.getEffects(0);
            if (effect.hasStatusBenefit()) {
                assertEquals(definition.getPhases().getWarmupMillis(), effect.getAtMillis());
                assertEquals("meridian_basic_techniques:light_body", effect.getStatusBenefit().getId());
                continue;
            }
            assertEquals(0, effect.getAtMillis());
            var expectedDuration = PresentationDuration.UNTIL_RECOVERY_END;
            if (definition.getInnate()) {
                expectedDuration = PresentationDuration.UNTIL_ACTIVE_END;
            }
            var presentation = effect.getPresentation();
            assertEquals(expectedDuration, presentation.getDuration());
            assertEquals(0, presentation.getStartMillis());
            assertTrue(definition.getPresentation().getIconId().startsWith("meridian_basic_techniques:"));
        }
    }

    @Test
    public void learnedTechniquesAcceptAnyAttributeAtOrAboveFreePhase() throws Exception {
        // 三式只限制最低相态，不把属性或游离态精确匹配带回正式目录。
        var registry = BasicTechniquesCatalog.registry(BasicTechniquesCatalog.load());
        var definitions = registry.definitions();
        assertEquals(TECHNIQUE_COUNT, definitions.size());
        for (var definition : definitions) {
            if (definition.getInnate() || definition.getId().equals(REBORN)) {
                continue;
            }
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
                if (definition.getInnate()) {
                    continue;
                }
                if (definition.getId().equals(REBORN)) {
                    assertTrue(TechniqueBinder.bind(closed, definition).hasBinding());
                    continue;
                }
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

    @Test
    public void originalBookDropsFromAllSevenHumanoidsAtALowerChance() throws Exception {
        // 七种生人修成后都会这三卷，所以每卷向七种生人各声明一项；人人都会，概率占位比单种生人的功法低。
        var registry = BasicTechniquesCatalog.registry(BasicTechniquesCatalog.load());
        for (var volume : registry.volumes()) {
            if (!volume.getBookId().equals(BASIC_BOOK)) {
                continue;
            }
            List<String> entities = volume.getScrollDropsList().stream()
                    .map(ScrollDrop::getEntityType)
                    .toList();
            assertEquals(volume.getId(), HUMANOIDS, entities);
            for (ScrollDrop drop : volume.getScrollDropsList()) {
                assertEquals(volume.getId(), SCROLL_DROP_CHANCE, drop.getChance(), 0);
            }
        }
    }

    @Test
    public void rebornVolumeHasScrollItemButNoSurvivalDrop() throws Exception {
        // 出身卷可以由创造栏或命令取得，但不声明任何生存掉落来源。
        var registry = BasicTechniquesCatalog.registry(BasicTechniquesCatalog.load());
        var volume = registry.findVolume(REBORN_VOLUME).orElseThrow();
        assertTrue("出身卷没有生成实体卷轴", TechniqueScrollCatalog.hasScrollItem(volume));
        assertEquals(0, volume.getScrollDropsCount());
        assertEquals(REBORN_BOOK, volume.getBookId());
        assertEquals(REBORN, volume.getTechniques(0).getId());
        assertEquals(1, registry.volumes().stream().filter(v -> v.getBookId().equals(REBORN_BOOK)).count());
        assertEquals(1, registry.volumes().stream().flatMap(v -> v.getTechniquesList().stream())
                .filter(ref -> ref.getId().equals(REBORN)).count());
        assertFalse("未学也能使用出身功法", registry.findLatest(REBORN).orElseThrow().getInnate());
    }
}
