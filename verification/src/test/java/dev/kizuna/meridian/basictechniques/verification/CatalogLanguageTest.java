package dev.kizuna.meridian.basictechniques.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kizuna.meridian.technique.server.TechniqueScrollText;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class CatalogLanguageTest {
    private static final String NAMESPACE = "meridian_basic_techniques";

    @Test
    public void packagedLanguagesNameEveryBookVolumeAndTechnique() throws Exception {
        // 从最终安装包遍历完整目录，缺一个式名或卷名都不能通过。
        var catalog = BasicTechniquesCatalog.load();
        try (var jar = new JarFile(System.getProperty("basicTechniques.fabricJar"))) {
            for (String locale : List.of("zh_cn", "en_us")) {
                var language = language(jar, locale);
                for (var book : catalog.getBooksList()) {
                    assertName(language, TechniqueScrollText.key("book", book.getId()));
                }
                for (var volume : catalog.getVolumesList()) {
                    assertName(language, TechniqueScrollText.key("volume", volume.getId()));
                    assertName(language, TechniqueScrollText.key("item", volume.getId() + "_scroll"));
                }
                for (var definition : catalog.getDefinitionsList()) {
                    assertName(language, TechniqueScrollText.key("technique", definition.getId()));
                    var presentation = definition.getPresentation();
                    assertFalse(presentation.getDisplayName().isBlank());
                    assertFalse(presentation.getDisplayName().contains(":"));
                    assertTrue(presentation.getDescription().length() > presentation.getDisplayName().length());
                }
            }
        }
    }

    private static JsonObject language(JarFile jar, String locale) throws Exception {
        // 读取真实打包资源，防止测试只验证了未打入包的源文件。
        var entry = jar.getJarEntry("assets/" + NAMESPACE + "/lang/" + locale + ".json");
        assertNotNull(entry);
        try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void assertName(JsonObject language, String key) {
        // 语言值必须可读，不能把资源 ID 再当作翻译内容。
        assertTrue(key, language.has(key));
        String name = language.get(key).getAsString();
        assertFalse(key, name.isBlank());
        assertFalse(key, name.contains(":"));
    }
}
