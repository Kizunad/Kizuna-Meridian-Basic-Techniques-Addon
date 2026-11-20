package dev.kizuna.meridian.human.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueBreathingPractice;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/** 身体宿主已经写下出身，但功法宿主尚未就绪时，用同一角色重登补学。 */
public final class RebornGrantGameTest {
    private static final String FORM = RebornBreathingMeasurements.FORM;
    private static final String VOLUME = FORM + "_volume";
    private static final int LOGIN_TICKS = 5;
    private static final double INTAKE_MULTIPLIER = 1.5;

    @GameTest(maxTicks = BasicGameTestTicks.DEFAULT_TIMEOUT)
    public void lostLearningHostRetriesOnRejoinAndDeathKeepsKnowledge(GameTestHelper context) {
        // 只撤该玩家的功法认领，身体和出身仍可处理选择与记账。
        var first = new BasicTestPlayer(context);
        var uuid = first.player.getUUID();
        context.runAfterDelay(LOGIN_TICKS, () -> {
            try (first) {
                var before = beginBreathing(first);
                context.assertTrue(before.getFailure().getCode() == FailureCode.ACCESS_DENIED,
                        "未选出身也能吐纳");
                HumanTechniqueHost.disconnect(uuid);
                var source = first.player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER);
                int chosen = BasicTestCommands.run(source, "meridian debug origin set @s meridian_humanoid:reborn");
                context.assertTrue(chosen > 0, "授卷失败时没有保存出身");
                context.assertFalse(knows(first), "功法宿主未就绪时写入了专卷");
            }
            var second = new BasicTestPlayer(context, uuid);
            context.runBeforeTestEnd(second::close);
            context.runAfterDelay(LOGIN_TICKS, () -> startAfterRetry(context, second));
        });
    }

    private static void startAfterRetry(GameTestHelper context, BasicTestPlayer fixture) {
        // 只接受重新登录触发的补发，后续再用该卷发动，死亡只结束运功。
        context.assertTrue(knows(fixture), "授卷失败后重登没有补学 " + VOLUME);
        var player = fixture.player;
        var body = fixture.body();
        var snapshot = body.snapshot();
        context.assertTrue(snapshot.meridianStates().stream()
                .allMatch(state -> state.opening().stage() == MeridianOpening.Stage.UNOPENED), "新身体已经开脉");
        long now = HumanTechniqueHost.nowMillis();
        context.assertFalse(body.canIntrospect(snapshot.id().value()), "基础吐纳放开了内观");
        context.assertTrue(body.startOpening(now, "ren") == FailureCode.ACCESS_DENIED, "基础吐纳放开了开脉");
        context.assertTrue(body.cultivate(now, CultivationInput.getDefaultInstance()) == FailureCode.ACCESS_DENIED,
                "基础吐纳放开了中焦");
        var outcome = TechniqueFabric.service().cast(player.getUUID(), GuardAssemblyGameTest.request(fixture, FORM));
        context.assertTrue(outcome.getReason().isEmpty(), "未开脉新身体不能发动专卷 " + outcome);
        context.runAfterDelay(LOGIN_TICKS, () -> {
            context.assertTrue(TechniqueBreathingPractice.active(body.snapshot()).isPresent(), "未进入维持");
            var receipt = beginBreathing(fixture);
            var view = receipt.getView();
            var breath = view.getBreathing();
            context.assertTrue(breath.getActive() && breath.getAutomatic(),
                    "未读新生的自动吐纳被拒 " + receipt);
            context.assertTrue(breath.getIntakeMultiplier() == INTAKE_MULTIPLIER,
                    "吐纳没有取得实际摄入倍率");
            context.assertFalse(view.hasEnvironment() || view.getChoicesCount() > 0,
                    "基础吐纳泄露环境或聚气配方");
            var source = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER);
            int assigned = BasicTestCommands.run(source, "meridian debug origin set @s meridian_humanoid:farmer");
            context.assertTrue(assigned > 0, "无法改换农户出身");
            context.assertFalse(body.breathingActive(), "撤掉天赋以后自动吐纳仍在运行");
            context.assertTrue(beginBreathing(fixture).getFailure().getCode() == FailureCode.ACCESS_DENIED,
                    "普通出身仍能吐纳");
            player.die(player.damageSources().genericKill());
            context.assertTrue(TechniqueBreathingPractice.active(body.snapshot()).isEmpty(), "死亡仍在维持");
            context.assertTrue(knows(fixture), "死亡清掉了学卷知识");
            fixture.close();
            context.succeed();
        });
    }

    private static GatheringResponse beginBreathing(BasicTestPlayer fixture) {
        // 客户端同款请求进入正式接收器，只读取本次请求对应的服务端回执。
        var control = BreathControl.newBuilder()
                .setAction(BreathAction.BEGIN_BREATHING)
                .setAutomatic(true /* automatic */)
                .setSequence(1)
                .build();
        var request = GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(java.util.UUID.randomUUID().toString())
                .setBodyId(fixture.player.getStringUUID())
                .setBreath(control)
                .build();
        fixture.player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
                MeridianChannels.GATHERING_REQUEST.payload(request.toByteArray())));
        for (var packet : fixture.connection.packets.reversed()) {
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof ProtobufChannel.Payload data
                    && data.type().equals(MeridianChannels.GATHERING_RESPONSE.type())) {
                try {
                    var receipt = GatheringResponse.parseFrom(data.bytes());
                    if (receipt.getRequestId().equals(request.getRequestId())) {
                        return receipt;
                    }
                } catch (InvalidProtocolBufferException invalid) {
                    throw new AssertionError("吐纳回执格式无效", invalid);
                }
            }
        }
        throw new AssertionError("未收到请求的吐纳回执 " + request.getRequestId());
    }

    private static boolean knows(BasicTestPlayer fixture) {
        // 直接读身体中的学习记录，功法宿主暂不可用时也能观察是否误发。
        var learned = TechniqueState.read(fixture.body().snapshot()).getKnownList();
        return learned.stream().anyMatch(entry -> entry.getTechnique().getId().equals(FORM));
    }
}
