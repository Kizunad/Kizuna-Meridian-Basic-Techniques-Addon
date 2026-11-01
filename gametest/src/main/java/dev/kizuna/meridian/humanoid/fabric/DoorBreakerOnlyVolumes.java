package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.VolumeTable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 卫道士旧用例的批次配置：学卷表每档只留破门卷，批次结束换回启动时装配的配置。 */
final class DoorBreakerOnlyVolumes {
    private static final String DOOR_BREAKER_PREFIX = "meridian_door_breaker:";
    private static HumanoidBrainConfig previous;

    private DoorBreakerOnlyVolumes() {
        // 只在批次前后切换全局配置。
    }

    static void install() {
        // 记下批次前的配置；其余各节照旧，基础三卷不发。
        HumanoidBrainConfig current = HumanoidBrain.config();
        previous = current;
        HumanoidBrain.configure(new HumanoidBrainConfig(current.schedule(), current.threat(), current.assessment(),
                current.response(), doorBreakerVolumes(current.volumes())));
    }

    static void restore() {
        // 换回批次前的配置；批次前未切换时不动。
        if (previous == null) {
            return;
        }
        HumanoidBrain.configure(previous);
        previous = null;
    }

    private static VolumeTable doorBreakerVolumes(VolumeTable table) {
        // 物种与档位不变，每档只留破门目录的卷。
        var species = new HashMap<String, Map<HumanoidTier, List<VolumeTable.Grant>>>();
        table.species().forEach((entityId, tiers) -> {
            var kept = new EnumMap<HumanoidTier, List<VolumeTable.Grant>>(HumanoidTier.class);
            tiers.forEach((tier, grants) -> kept.put(tier, doorBreakerGrants(grants)));
            species.put(entityId, kept);
        });
        return new VolumeTable(species);
    }

    private static List<VolumeTable.Grant> doorBreakerGrants(List<VolumeTable.Grant> grants) {
        // 只留破门目录的卷。
        return grants.stream()
                .filter(grant -> grant.volume().startsWith(DOOR_BREAKER_PREFIX))
                .toList();
    }
}
