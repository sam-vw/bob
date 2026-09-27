package com.github.samblake.bob.processor;

import com.github.samblake.bob.builder.Missing;
import com.github.samblake.bob.builder.Present;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class BobProcessorTest {

    private static final String PERSON =
            "package test;\n"
            + "@com.github.samblake.bob.builder.Bob\n"
            + "public class Person extends PersonBuilders {\n"
            + "    public Person(String name, java.util.List<String> nicknames, int age) {}\n"
            + "}\n";

    @Rule
    public TemporaryFolder output = new TemporaryFolder();

    @Test
    public void compilesWhenEveryArgumentIsSet() {
        Result result = compile(PERSON, usage(".withAge(3).withName(\"a\").withNicknames(null)"));

        assertThat(result.errors, result.success, is(true));
    }

    @Test
    public void failsWhenAnArgumentIsMissing() {
        Result result = compile(PERSON, usage(".withName(\"a\").withAge(3)"));

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("PersonBuilder<" + Present.class.getName() + ","
                + Missing.class.getName() + "," + Present.class.getName() + "> cannot be converted"));
    }

    @Test
    public void failsWhenTheGeneratedBaseIsNotExtended() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.bob.builder.Bob\n"
                + "public class Person {\n"
                + "    public Person(String name) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("Person must extend the generated PersonBuilders"));
    }

    @Test
    public void failsWithMoreThanOneConstructor() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.bob.builder.Bob\n"
                + "public class Person extends PersonBuilders {\n"
                + "    public Person(String name) {}\n"
                + "    public Person(int age) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("needs exactly one non private constructor"));
    }

    @Test
    public void failsWithAGenericConstructor() {
        Result result = compile(
                "package test;\n"
                + "@com.github.samblake.bob.builder.Bob\n"
                + "public class Person extends PersonBuilders {\n"
                + "    public <T extends Number> Person(T value, String name) {}\n"
                + "}\n");

        assertThat(result.success, is(false));
        assertThat(result.errors, containsString("does not support generic constructors"));
    }

    private static String usage(String calls) {
        return "package test;\n"
                + "class Usage {\n"
                + "    Person person = Person.from(PersonBuilder.builder()" + calls + ");\n"
                + "}\n";
    }

    private Result compile(String... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> files = Arrays.stream(sources).map(Source::new).collect(Collectors.toList());

        JavaCompiler.CompilationTask task = compiler.getTask(null, null, diagnostics,
                Arrays.asList("-d", output.getRoot().getPath(), "-s", output.getRoot().getPath(),
                        "-classpath", System.getProperty("java.class.path")),
                null, files);
        task.setProcessors(singletonList(new BobProcessor()));
        boolean success = task.call();

        String errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(null))
                .collect(Collectors.joining("\n"));

        return new Result(success, errors);
    }

    private static final class Result {
        private final boolean success;
        private final String errors;

        private Result(boolean success, String errors) {
            this.success = success;
            this.errors = errors;
        }
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String code;

        private Source(String code) {
            super(URI.create("string:///" + className(code).replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        private static String className(String code) {
            String pkg = code.substring("package ".length(), code.indexOf(';'));
            int start = code.indexOf("class ") + "class ".length();
            int end = start;
            while (Character.isJavaIdentifierPart(code.charAt(end))) {
                end++;
            }
            return pkg + "." + code.substring(start, end);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
