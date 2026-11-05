package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.basictechniques.verification.BasicTechniquesCatalog;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.server.TechniqueCasting;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** 用迁移前完整目录生成真实旧身体存档，再由新目录恢复并重施。 */
public final class StatusMigrationGameTest {
    private static final String PREFIX = "meridian_basic_techniques:";
    private static final int LIMIT = 500;
    private static final int AFTER_RECOVERY = 45;
    private static final int AFTER_WARMUP = 35;
    private static final int BREATH_COOLDOWN = 440;
    private static final int CHARGE_COOLDOWN = 240;
    private static final long MILLIS_PER_TICK = 50;
    private record History(String id, int cooldownTicks, BodySnapshot body) { }

    @GameTest(maxTicks = LIMIT)
    public void savedLegacyBreathFinishesBeforeNewStatusCanCast(GameTestHelper context) throws Exception {
        // 调息旧曲线经过真实保存和登录后仍完整运行，剩余冷却不得丢失。
        migrate(context, "breath", BREATH_COOLDOWN);
    }

    @GameTest(maxTicks = LIMIT)
    public void savedLegacyChargeFinishesBeforeNewStatusCanCast(GameTestHelper context) throws Exception {
        // 蓄力旧曲线不能被新状态生命周期提前清理或与重施叠乘。
        migrate(context, "charge", CHARGE_COOLDOWN);
    }

    private static void migrate(GameTestHelper context, String name, int cooldownTicks) throws Exception {
        // 历史夹具保留迁移前原始 txtpb；只复用纯 Java 施放服务生成已登记曲线。
        var fixture = PlayerAssemblyGameTest.prepared(context);
        context.runBeforeTestEnd(fixture::close);
        String id = PREFIX + name;
        TechniqueFabric.service().learn(fixture.player.getUUID(), id + "_volume");
        var host = TechniqueFabric.service().statusHost(fixture.player.getUUID()).orElseThrow();
        var casting = legacyCasting();
        context.assertTrue(casting.cast(host, request(fixture, id)).getPhase() == CastPhase.WARMUP,
                "历史目录未成功起手");
        context.assertTrue(!host.inspect().body().modifiers().isEmpty(), "历史起手没有登记完整曲线");
        context.runAfterDelay(AFTER_RECOVERY, () -> {
            casting.advance(host);
            var body = host.inspect().body();
            context.assertTrue(!body.modifiers().isEmpty(), "历史曲线被收势提前清除");
            fixture.close();
            reload(context, fixture.player.getUUID(), new History(id, cooldownTicks, body));
        });
    }

    private static void reload(GameTestHelper context, UUID playerId, History history) {
        // 登录后按身体时钟的剩余冷却等待，不把离线结算时钟误当作测试帧数。
        var restored = new BasicTestPlayer(context, playerId);
        context.runBeforeTestEnd(restored::close);
        var body = restored.body().snapshot();
        String id = history.id();
        long cooldownEnd = TechniqueState.read(history.body()).getCooldownsOrThrow(id);
        context.assertTrue(body.modifiers().equals(history.body().modifiers()), "更新读档改写旧曲线");
        var progress = TechniqueState.read(body);
        context.assertTrue(!progress.getCurveEndsMap().isEmpty(), "读档丢失旧曲线到期保护");
        context.assertTrue(progress.getCooldownsOrThrow(id) == cooldownEnd, "读档改变剩余冷却终点");
        var blocked = TechniqueFabric.service().cast(restored.player.getUUID(), request(restored, id));
        context.assertTrue(blocked.getFailure() == TechniqueFailure.COOLING_DOWN, "迁移丢失剩余冷却");
        long remainingTicks = (cooldownEnd - body.settledAt().elapsedMillis()) / MILLIS_PER_TICK;
        context.assertTrue(remainingTicks <= history.cooldownTicks(), "旧冷却超过原目录上限");
        context.runAfterDelay(remainingTicks + 1, () -> recast(context, restored, id));
    }

    private static void recast(GameTestHelper context, BasicTestPlayer fixture, String id) {
        // 冷却结束时旧曲线必须已自然结束，新施放起手也不能提前添加状态能力。
        var host = TechniqueFabric.service().statusHost(fixture.player.getUUID()).orElseThrow();
        context.assertTrue(host.inspect().body().modifiers().isEmpty(), "冷却结束仍有历史曲线");
        var outcome = TechniqueFabric.service().cast(fixture.player.getUUID(), request(fixture, id));
        context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "迁移后无法再次施放：" + outcome);
        context.assertTrue(host.inspect().body().modifiers().isEmpty(), "新状态起手提前登记曲线");
        context.runAfterDelay(AFTER_WARMUP, () -> {
            var registered = TechniqueStatusBenefits.get(id);
            context.assertTrue(fixture.player.hasEffect(registered.effect()), "迁移后的施放未生成状态");
            context.assertTrue(host.inspect().body().modifiers().stream()
                    .allMatch(modifier -> modifier.id().value().startsWith("meridian_technique:status/")),
                    "新状态仍混用历史施法曲线");
            fixture.close();
            context.succeed();
        });
    }

    private static TechniqueCasting legacyCasting() throws Exception {
        // 此快照只存在于测试资源，正式发布包仍只有新目录。
        try (var input = StatusMigrationGameTest.class.getResourceAsStream(
                "/status-fixtures/legacy-basic-techniques.txtpb")) {
            if (input == null) {
                throw new IllegalStateException("缺少历史目录夹具");
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var catalog = TechniqueCatalogManifest.parseText("legacy-basic", text);
            return new TechniqueCasting(BasicTechniquesCatalog.registry(catalog));
        }
    }

    private static CastRequest request(BasicTestPlayer fixture, String id) {
        // 新旧目录共用相同功法版本身份，检验持久进度的真实兼容性。
        var reference = TechniqueReference.newBuilder().setId(id).setVersion(1).build();
        return CastRequest.newBuilder()
                .setBodyId(fixture.player.getStringUUID())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
    }

}
