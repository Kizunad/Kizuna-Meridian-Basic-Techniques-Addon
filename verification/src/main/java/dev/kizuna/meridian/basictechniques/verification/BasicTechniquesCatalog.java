package dev.kizuna.meridian.basictechniques.verification;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonBundle;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonHostProfile;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonRegistration;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 测试目录解析与验证的公共装配入口。 */
public final class BasicTechniquesCatalog {
    public static final String RESOURCE =
            "/data/meridian_basic_techniques/technique_catalog.txtpb";

    private BasicTechniquesCatalog() {
        // 目录工具只提供静态验证入口。
    }

    public static TechniqueCatalog load() throws IOException {
        // 单元验证与正式发现共用功法库的有界清单解析器。
        var stream = BasicTechniquesCatalog.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IOException("找不到基础功法目录资源：" + RESOURCE);
        }
        try (stream) {
            byte[] bytes = stream.readNBytes(TechniqueCatalogManifest.MAX_BYTES + 1);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return TechniqueCatalogManifest.parseText("meridian_basic_techniques:" + RESOURCE.substring(1), text);
        }
    }

    public static TechniqueRegistry registry(TechniqueCatalog catalog) {
        // Human 模板标签通过真实附属注册表登记，再交给功法库校验目录。
        return TechniqueRegistry.load(addons(), List.of(catalog));
    }

    public static AddonRegistry addons() {
        // 测试宿主声明 Human 内容要求的完整 Framework 能力集合。
        AddonHostProfile host = AddonHostProfile.newBuilder()
                .setHostId("meridian:java_reference")
                .setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE)
                .addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY)
                .addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)
                .addCapabilities(AddonRegistry.OPENING_DEFINITIONS)
                .addCapabilities(AddonRegistry.OPENING_RUNTIME)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE)
                .build();
        AddonBundle human = HumanContent.bundle();
        AddonRegistration registration = AddonRegistration.newBuilder()
                .setProtocolVersion(1)
                .setHost(host)
                .addBundles(human)
                .build();
        return AddonRegistry.load(registration);
    }
}
