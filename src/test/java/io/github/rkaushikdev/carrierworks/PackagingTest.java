package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.Checkout.Dependency;
import io.github.rkaushikdev.carrierworks.Checkout.Plugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a test suite structurally cannot catch, checked by reading the build file and the sources.
 *
 * <p>A test-only dependency used by a shipped class works perfectly in every test run, because a
 * test run is the one class path where the test dependencies are always present. It fails the
 * moment somebody runs the jar on its own, and it fails as a class that cannot be found at start up
 * rather than anywhere useful.
 *
 * <p>So this reads every shipped source file, collects what it imports from outside the JDK and the
 * bench itself, and checks each against the dependencies the build declares for run time. It also
 * checks that every version is pinned, because a bench that resolves a different Hibernate on the
 * day somebody rebuilds the container is a bench with a new set of bugs.
 */
class PackagingTest {

    private static final String OWN = "io.github.rkaushikdev.carrierworks";

    /** Which declared dependency supplies each package the shipped code imports from. */
    private static final Map<String, String> SUPPLIERS = Map.ofEntries(
            Map.entry("com.fasterxml.jackson", "spring-boot-starter-web"),
            Map.entry("com.zaxxer.hikari", "spring-boot-starter-data-jpa"),
            Map.entry("io.swagger.v3", "springdoc-openapi-starter-webmvc-ui"),
            Map.entry("jakarta.persistence", "spring-boot-starter-data-jpa"),
            Map.entry("jakarta.servlet", "spring-boot-starter-web"),
            Map.entry("jakarta.validation", "spring-boot-starter-validation"),
            Map.entry("org.flywaydb", "flyway-core"),
            Map.entry("org.hibernate", "spring-boot-starter-data-jpa"),
            Map.entry("org.springframework.orm", "spring-boot-starter-data-jpa"),
            Map.entry("org.springframework", "spring-boot-starter-web"),
            Map.entry("picocli", "picocli"));

    /** What the test-scoped dependencies bring, which a shipped class must never import. */
    private static final List<String> TEST_PACKAGES = List.of(
            "org.junit",
            "org.assertj",
            "org.mockito",
            "org.hamcrest",
            "org.springframework.test",
            "org.springframework.boot.test",
            "com.jayway.jsonpath",
            "org.skyscreamer",
            "org.awaitility",
            "org.xmlunit");

    private static final Pattern EXACT = Pattern.compile("\\d+(\\.\\d+)+");

    /** Imports from outside the JDK and the bench itself. */
    private static List<String> thirdParty(List<String> imports) {
        return imports.stream()
                .filter(name -> !name.startsWith(OWN + ".") && !inTheJdk(name))
                .toList();
    }

    /** Whether a name lives in a module of the running JDK, read off its package. */
    private static boolean inTheJdk(String name) {
        String packageName = packageOf(name);
        return ModuleLayer.boot().modules().stream()
                .anyMatch(module -> module.getPackages().contains(packageName));
    }

    /** The package part of an imported name: everything before the first capitalised step. */
    private static String packageOf(String name) {
        List<String> steps = new ArrayList<>();
        for (String step : name.split("\\.")) {
            if (Character.isUpperCase(step.charAt(0))) {
                break;
            }
            steps.add(step);
        }
        return String.join(".", steps);
    }

    /** The declared dependency that supplies a name, by the longest package prefix that matches. */
    private static Optional<String> supplier(String name) {
        return SUPPLIERS.entrySet().stream()
                .filter(entry -> name.startsWith(entry.getKey() + "."))
                .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Map.Entry::getValue);
    }

    private static Set<String> artifacts(String... scopes) {
        Set<String> wanted = Set.of(scopes);
        return Checkout.pom().dependencies().stream()
                .filter(dependency -> wanted.contains(dependency.scope()))
                .map(Dependency::artifactId)
                .collect(Collectors.toSet());
    }

    private static Set<String> runtime() {
        return artifacts("compile", "runtime");
    }

    @Test
    void everyShippedClassOnlyImportsDeclaredDependencies() {
        Set<String> declared = runtime();
        int read = 0;
        for (Path source : Checkout.javaFiles(Checkout.MAIN)) {
            for (String name : thirdParty(Checkout.imports(Checkout.read(source)))) {
                read++;
                Optional<String> supplier = supplier(name);
                assertThat(supplier)
                        .as(source.getFileName() + " imports " + name)
                        .isPresent();
                assertThat(declared)
                        .as(source.getFileName() + " imports " + name)
                        .contains(supplier.get());
            }
        }
        assertThat(read).as("third party imports read").isGreaterThan(100);
    }

    @Test
    void anImportNobodyDeclaredIsCaught() {
        List<String> imports = Checkout.imports(
                "package x;\n\nimport java.util.List;\nimport io.github.rkaushikdev.carrierworks.Ids;\n"
                        + "import com.example.widgets.Widget;\nimport static picocli.CommandLine.Option;\n");
        assertThat(thirdParty(imports)).containsExactly("com.example.widgets.Widget", "picocli.CommandLine.Option");
        assertThat(supplier("com.example.widgets.Widget")).isEmpty();
        assertThat(supplier("picocli.CommandLine.Option")).contains("picocli");
        assertThat(supplier("org.springframework.orm.jpa.JpaVendorAdapter")).contains("spring-boot-starter-data-jpa");
    }

    @Test
    void noShippedClassImportsATestOnlyDependency() {
        assertThat(artifacts("test")).contains("spring-boot-starter-test");
        Set<String> testClasses = Checkout.javaFiles(Checkout.TEST).stream()
                .map(source -> Checkout.className(Checkout.TEST, source))
                .collect(Collectors.toSet());
        assertThat(testClasses).contains(OWN + ".TestClock", OWN + ".inventory.BoardSetup");
        for (Path source : Checkout.javaFiles(Checkout.MAIN)) {
            for (String name : Checkout.imports(Checkout.read(source))) {
                assertThat(TEST_PACKAGES)
                        .as(source.getFileName() + " imports " + name)
                        .noneMatch(prefix -> name.startsWith(prefix + "."));
                assertThat(testClasses)
                        .as(source.getFileName() + " imports " + name)
                        .doesNotContain(name);
            }
        }
    }

    @Test
    void everyDependencyIsPinned() {
        Checkout.Pom pom = Checkout.pom();
        assertThat(pom.parentVersion()).matches(EXACT);
        for (Dependency dependency : pom.dependencies()) {
            dependency.version().map(pom::resolve).ifPresent(version -> assertThat(version)
                    .as(dependency.artifactId())
                    .matches(EXACT));
        }
        for (Plugin plugin : pom.plugins()) {
            plugin.version().map(pom::resolve).ifPresent(version -> assertThat(version)
                    .as(plugin.artifactId())
                    .matches(EXACT));
        }
        String wrapper = Checkout.read(Checkout.ROOT.resolve(".mvn/wrapper/maven-wrapper.properties"));
        assertThat(wrapper).containsPattern("distributionUrl=\\S+/apache-maven/\\d+\\.\\d+\\.\\d+/");
    }

    @Test
    void theBuildDeclaresTheJavaItNeeds() {
        assertThat(Checkout.pom().property("java.version")).contains("21");
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }

    @Test
    void theTestsAreNotShipped() {
        Checkout.Pom pom = Checkout.pom();
        assertThat(pom.texts("sourceDirectory")).isEmpty();
        assertThat(pom.texts("goal")).contains("add-test-source").doesNotContain("add-source");
        assertThat(Checkout.javaFiles(Checkout.MAIN))
                .isNotEmpty()
                .noneMatch(source -> source.getFileName().toString().endsWith("Test.java"));
        assertThat(Files.exists(Checkout.MAIN.resolve(OWN.replace('.', '/')).resolve("testroutes")))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"spotless-maven-plugin", "jacoco-maven-plugin", "spring-boot-starter-test"})
    void theToolingIsDeclaredForTheBuildOnly(String name) {
        Set<String> plugins =
                Checkout.pom().plugins().stream().map(Plugin::artifactId).collect(Collectors.toSet());
        assertThat(plugins.contains(name) || artifacts("test").contains(name))
                .as(name)
                .isTrue();
        assertThat(runtime()).doesNotContain(name);
    }

    /** Everything above persistence works on plain objects, and this proves it. */
    @Test
    void jpaIsOnlyImportedFromThePersistencePackage() {
        Path persistence = Checkout.MAIN.resolve(OWN.replace('.', '/')).resolve("persistence");
        int outside = 0;
        int inside = 0;
        for (Path source : Checkout.javaFiles(Checkout.MAIN)) {
            List<String> mapping = Checkout.imports(Checkout.read(source)).stream()
                    .filter(name -> name.startsWith("jakarta.persistence.")
                            || name.startsWith("org.hibernate.")
                            || name.startsWith("org.springframework.orm."))
                    .toList();
            if (source.startsWith(persistence)) {
                inside += mapping.size();
            } else {
                outside++;
                assertThat(mapping).as(source.getFileName().toString()).isEmpty();
            }
        }
        assertThat(outside).as("classes outside persistence").isGreaterThan(100);
        assertThat(inside).as("mapping imports inside persistence").isGreaterThan(10);
    }
}
