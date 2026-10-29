package dev.kizuna.meridian.basictechniques.verification;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;

import java.io.IOException;
import java.util.UUID;

/** 基于正式 Human 出生数据的绑定夹具，不参与游戏运行。 */
public final class BasicBodies {
    public enum Opening { CLOSED, HAND, FOOT, ALL }

    private BasicBodies() {
        // 夹具仅提供具名构造入口。
    }

    public static BodySnapshot adult(BodyMessages.Realm realm, Opening opening) throws IOException {
        // 仅改变测试境界与开拓标记；结构、组织能力和初始真元均来自正式出生规则。
        var template = BasicTechniquesCatalog.addons()
                .findTemplate(HumanContent.TEMPLATE_ID, HumanContent.CONTENT_VERSION).orElseThrow();
        var time = BodyMessages.TimeCursor.newBuilder()
                .setDomain("meridian_basic_techniques:test")
                .setEpoch("binding")
                .setElapsedMillis(0)
                .build();
        byte[] bytes = HumanBirth.candidate(template, UUID.randomUUID().toString(), time);
        var born = BodyMessages.BodySnapshot.parseFrom(bytes);
        var candidate = born.toBuilder().setRealm(realm);
        var opened = BodyMessages.MeridianOpening.newBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.OPENED)
                .setProgress(1)
                .build();
        for (int index = 0; index < born.getMeridianStatesCount(); index++) {
            var state = born.getMeridianStates(index);
            var definition = born.getStructure().getMeridiansList().stream()
                    .filter(item -> item.getId().equals(state.getId())).findFirst().orElseThrow();
            if (matches(definition, opening)) {
                var openedState = state.toBuilder().setOpening(opened).build();
                candidate.setMeridianStates(index, openedState);
            }
        }
        return BodyProtobuf.fromMessage(candidate.build());
    }

    private static boolean matches(BodyMessages.MeridianDefinition definition, Opening opening) {
        // 手足开拓按标签选择，避免把命名细节复制到测试。
        return switch (opening) {
            case CLOSED -> false;
            case ALL -> true;
            case HAND -> definition.getTagsList().contains("human:hand_meridian");
            case FOOT -> definition.getTagsList().contains("human:foot_meridian");
        };
    }
}
