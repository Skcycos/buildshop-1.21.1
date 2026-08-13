package com.tanrunn.buildshop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** 覆盖设计指南测试项：1（模板清理后不存在示例 Mod 残留）。 */
class TemplateCleanupTest {

    private static final List<String> FORBIDDEN = List.of(
            "examplemod", "example_mod", "ExampleMod", "example.com", "com.example"
    );

    private static Path projectRoot() {
        // FML-JUnit 把工作目录切到 build/minecraft-junit，向上寻找含 build.gradle 的项目根。
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.gradle")) && Files.exists(current.resolve("settings.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    @Test
    void noExampleModLeftoversInSources() throws IOException {
        Path root = projectRoot();
        Path src = root.resolve("src/main/java");
        Path gradle = root.resolve("build.gradle");

        try (Stream<Path> files = Files.walk(src)) {
            List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty(), "no java sources found");

            for (Path file : javaFiles) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                for (String forbidden : FORBIDDEN) {
                    assertFalse(content.toLowerCase().contains(forbidden.toLowerCase()),
                            file + " still contains '" + forbidden + "'");
                }
            }
        }
    }

    @Test
    void buildGradleHasNoExampleModReferences() throws IOException {
        Path gradle = projectRoot().resolve("build.gradle");
        String content = Files.readString(gradle, StandardCharsets.UTF_8);
        for (String forbidden : FORBIDDEN) {
            assertFalse(content.toLowerCase().contains(forbidden.toLowerCase()),
                    "build.gradle still contains '" + forbidden + "'");
        }
    }

    @Test
    void modIdIsBuildshopEverywhere() throws IOException {
        Path root = projectRoot();
        String main = Files.readString(root.resolve("src/main/java/com/tanrunn/buildshop/BuildShopMod.java"),
                StandardCharsets.UTF_8);
        assertFalse(main.contains("examplemod"), "BuildShopMod references examplemod");
    }
}
