package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.duozhi.outbound.OutboundApplication;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class ResourceLayoutTest {
    @Test
    void compiledApplicationUsesJava21Bytecode() throws Exception {
        try (var in =
                new DataInputStream(
                        OutboundApplication.class.getResourceAsStream(
                                "OutboundApplication.class"))) {
            assertThat(in.readInt()).isEqualTo(0xCAFEBABE);
            in.readUnsignedShort();
            assertThat(in.readUnsignedShort()).isEqualTo(65);
        }
    }

    @Test
    void javaSourcesFollowModuleAndLayerPackages() throws Exception {
        Path root = Path.of("src/main/java/com/duozhi/outbound");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("OutboundApplication.java")) continue;
                var relative = root.relativize(file);
                assertThat(relative.getNameCount()).as(file.toString()).isGreaterThanOrEqualTo(3);
                String expected =
                        "com.duozhi.outbound." + relative.getParent().toString().replace('/', '.');
                assertThat(Files.readString(file))
                        .as(file.toString())
                        .contains("package " + expected + ";");
            }
        }
    }

    @Test
    void everyMapperNamespaceMatchesItsModuleDaoFolderAndJavaInterface() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Path root = Path.of("src/main/resources/mapper");
        try (var files = Files.walk(root)) {
            var xml = files.filter(p -> p.toString().endsWith(".xml")).toList();
            assertThat(xml).hasSize(39);
            for (Path file : xml) {
                var mapper = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                String namespace = mapper.getAttribute("namespace");
                String expected =
                        "com.duozhi.outbound."
                                + root.relativize(file)
                                        .toString()
                                        .replace('/', '.')
                                        .replaceAll("\\.xml$", "");
                assertThat(namespace).isEqualTo(expected);
                assertThat(Class.forName(namespace).isInterface()).isTrue();
            }
        }
    }

    @Test
    void yamlConfigurationLoadsRecursiveMappersAndRootDefaults() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var config = loader.load("test", new ClassPathResource("application.yaml")).getFirst();
        assertThat(config.getProperty("mybatis.mapper-locations"))
                .isEqualTo("classpath*:mapper/**/*.xml");
        assertThat(config.getProperty("logging.config")).isEqualTo("classpath:logback-spring.xml");
        assertThat(new ClassPathResource("application.properties").exists()).isFalse();
        for (String name : new String[] {"setting-defaults.yaml", "scenario-defaults.yaml"}) {
            assertThat(loader.load(name, new ClassPathResource(name))).hasSize(1);
        }
    }
}
