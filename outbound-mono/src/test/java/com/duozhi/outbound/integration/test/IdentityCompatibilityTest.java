package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityCompatibilityTest {
    @TempDir Path output;

    @Test
    void actualOriginalNullUserBranchPassesWhileNewExplicitPermissionRejects() throws Exception {
        Path original =
                Path.of("..", "backend-java", "src", "main", "java", "com", "duozhi", "outbound");
        String servlet =
                Path.of(
                                jakarta.servlet.http.HttpServletRequest.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toString();
        assertThat(
                        ToolProvider.getSystemJavaCompiler()
                                .run(
                                        null,
                                        null,
                                        null,
                                        "-classpath",
                                        servlet,
                                        "-d",
                                        output.toString(),
                                        original.resolve("Identity.java").toString(),
                                        original.resolve("ApiError.java").toString()))
                .isZero();
        try (var loader =
                new URLClassLoader(
                        new URL[] {output.toUri().toURL()}, getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (name.equals("com.duozhi.outbound.Identity")
                                || name.equals("com.duozhi.outbound.ApiError")) {
                            Class<?> found = findLoadedClass(name);
                            if (found == null) found = findClass(name);
                            if (resolve) resolveClass(found);
                            return found;
                        }
                        return super.loadClass(name, resolve);
                    }
                }) {
            Class<?> type = loader.loadClass("com.duozhi.outbound.Identity");
            Object legacy = type.getConstructor(long.class, Map.class).newInstance(1L, null);
            assertThatCode(() -> type.getMethod("admin").invoke(legacy)).doesNotThrowAnyException();
        }
        var denied = new Identity(1, null, Set::of);
        assertThatThrownBy(() -> denied.admin("admin:users"))
                .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatCode(
                        () ->
                                new Identity(1, null, () -> Set.of("admin:users"))
                                        .admin("admin:users"))
                .doesNotThrowAnyException();
        assertThatCode(() -> new Identity(1, Map.of("role", "admin"), Set::of).admin("admin:users"))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                new Identity(1, Map.of("role", "agent"), Set::of)
                                        .admin("admin:users"))
                .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.status).isEqualTo(403));
        System.out.println(
                "Original null-user admin(): allowed; translated without explicit scope: 403;"
                        + " authorized key/admin: allowed; agent: 403");
    }
}
