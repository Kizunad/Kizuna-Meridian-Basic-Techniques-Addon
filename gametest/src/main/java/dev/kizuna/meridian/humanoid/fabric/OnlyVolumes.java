package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.VolumeTable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 批次配置：学卷表每档只留一个功法目录的卷，批次结束换回启动时装配的配置。 */
final class OnlyVolumes {
    private static HumanoidBrainConfig previous;

    private OnlyVolumes() {
        // 只在批次前后切换全局配置。
    }

    static void install(String prefix) {
        // 记下批次前的配置；其余各节照旧，只发卷 ID 以 prefix 开头的卷。
        HumanoidBrainConfig current = HumanoidBrain.config();
        previous = current;
        HumanoidBrain.configure(new HumanoidBrainConfig(current.schedule(), current.threat(), current.assessment(),
                current.response(), kept(current.volumes(), prefix)));
    }

    static void restore() {
        // 换回批次前的配置；批次前未切换时不动。
        if (previous == null) {
            return;
        }
        HumanoidBrain.configure(previous);
        previous = null;
    }

    private static VolumeTable kept(VolumeTable table, String prefix) {
        // 物种与档位不变，每档只留这个目录的卷。
        var species = new HashMap<String, Map<HumanoidTier, List<VolumeTable.Grant>>>();
        table.species().forEach((entityId, tiers) -> {
            var kept = new EnumMap<HumanoidTier, List<VolumeTable.Grant>>(HumanoidTier.class);
            tiers.forEach((tier, grants) -> kept.put(tier, grants.stream()
                    .filter(grant -> grant.volume().startsWith(prefix))
                    .toList()));
            species.put(entityId, kept);
        });
        return new VolumeTable(species);
    }
}
