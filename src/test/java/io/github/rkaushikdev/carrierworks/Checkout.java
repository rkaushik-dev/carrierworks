package io.github.rkaushikdev.carrierworks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** The checkout the tests run in, read as files: the build file, the sources and the resources. */
final class Checkout {

    /** The root of the checkout, which is where the build runs the tests. */
    static final Path ROOT = Path.of("").toAbsolutePath();

    /** The sources that ship. */
    static final Path MAIN = ROOT.resolve("src/main/java");

    /** The sources that never ship. */
    static final Path TEST = ROOT.resolve("src/test/java");

    private static final Pattern IMPORT =
            Pattern.compile("^import (?:static )?([\\w.]+?)(?:\\.\\*)?;", Pattern.MULTILINE);

    private Checkout() {}

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Every Java file under a directory, in a stable order. */
    static List<Path> javaFiles(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Every name a source file imports, static imports by their class. */
    static List<String> imports(String source) {
        List<String> names = new ArrayList<>();
        Matcher found = IMPORT.matcher(source);
        while (found.find()) {
            names.add(found.group(1));
        }
        return names;
    }

    /** The fully qualified name of the class a source file holds, from its path under a source root. */
    static String className(Path root, Path source) {
        String relative = root.relativize(source).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
    }

    /** The build file. */
    static Pom pom() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return new Pom(
                    factory.newDocumentBuilder().parse(ROOT.resolve("pom.xml").toFile()));
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IllegalStateException("pom.xml does not read as XML", failure);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * One dependency the build declares.
     *
     * @param version empty when the parent's dependency management supplies it
     * @param scope {@code compile} unless the build says otherwise
     */
    record Dependency(String groupId, String artifactId, Optional<String> version, String scope) {}

    /**
     * One build plugin.
     *
     * @param version empty when the parent's plugin management supplies it
     */
    record Plugin(String groupId, String artifactId, Optional<String> version, Element element) {}

    /** The parts of {@code pom.xml} the packaging tests read. */
    record Pom(Document document) {

        private Element project() {
            return document.getDocumentElement();
        }

        String version() {
            return child(project(), "version").orElseThrow();
        }

        String parentVersion() {
            return child(only(project(), "parent"), "version").orElseThrow();
        }

        /** A property the build defines, or empty. */
        Optional<String> property(String name) {
            return child(only(project(), "properties"), name);
        }

        /** A version as written, with a {@code ${property}} in it replaced by its value. */
        String resolve(String text) {
            Matcher reference = Pattern.compile("\\$\\{([^}]+)}").matcher(text);
            return reference.matches() ? property(reference.group(1)).orElse(text) : text;
        }

        List<Dependency> dependencies() {
            List<Dependency> found = new ArrayList<>();
            for (Element dependency : children(only(project(), "dependencies"), "dependency")) {
                found.add(new Dependency(
                        child(dependency, "groupId").orElseThrow(),
                        child(dependency, "artifactId").orElseThrow(),
                        child(dependency, "version"),
                        child(dependency, "scope").orElse("compile")));
            }
            return found;
        }

        List<Plugin> plugins() {
            List<Plugin> found = new ArrayList<>();
            for (Element plugin : children(only(only(project(), "build"), "plugins"), "plugin")) {
                found.add(new Plugin(
                        child(plugin, "groupId").orElse("org.apache.maven.plugins"),
                        child(plugin, "artifactId").orElseThrow(),
                        child(plugin, "version"),
                        plugin));
            }
            return found;
        }

        /** The plugin with this artifact id. */
        Plugin plugin(String artifactId) {
            return plugins().stream()
                    .filter(plugin -> plugin.artifactId().equals(artifactId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("pom.xml has no plugin " + artifactId));
        }

        /** Every element with this name anywhere under the build section, in document order. */
        List<String> texts(String name) {
            List<String> found = new ArrayList<>();
            NodeList nodes = only(project(), "build").getElementsByTagName(name);
            for (int index = 0; index < nodes.getLength(); index++) {
                found.add(nodes.item(index).getTextContent().strip());
            }
            return found;
        }

        private static Element only(Element parent, String name) {
            List<Element> found = children(parent, name);
            if (found.size() != 1) {
                throw new AssertionError("expected one <" + name + "> in <" + parent.getTagName() + ">");
            }
            return found.getFirst();
        }

        private static Optional<String> child(Element parent, String name) {
            return children(parent, name).stream().findFirst().map(element -> element.getTextContent()
                    .strip());
        }

        private static List<Element> children(Element parent, String name) {
            List<Element> found = new ArrayList<>();
            for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element && element.getTagName().equals(name)) {
                    found.add(element);
                }
            }
            return found;
        }
    }
}
