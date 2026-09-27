package com.github.samblake.bob.processor;

import com.github.samblake.bob.builder.Bob;
import com.github.samblake.bob.builder.Missing;
import com.github.samblake.bob.builder.Present;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.Character.toUpperCase;
import static java.util.stream.Collectors.toList;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PROTECTED;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.lang.model.util.ElementFilter.constructorsIn;
import static javax.tools.Diagnostic.Kind.ERROR;

/**
 * Generates two classes alongside each class annotated with {@link Bob}, using its only non-private constructor.
 *
 * <p>{@code <Name>Builder} has one type parameter per constructor argument, each starting as {@link Missing}.
 * Every {@code with<Argument>} call returns a new builder with that argument's type parameter set to
 * {@link Present}, so the builder's type records which arguments have been supplied.
 *
 * <p>{@code <Name>Builders} is the superclass the annotated class must extend. Its static {@code from} only
 * accepts a builder whose type parameters are all {@link Present}, so {@code <Name>.from(builder)} fails to
 * compile until every argument has been set.
 *
 * <pre>{@code
 * OrderView view = OrderView.from(OrderViewBuilder.builder()
 *         .withProducts(products)
 *         .withAddress(address)
 *         .withTotal(total));
 * }</pre>
 */
@SupportedAnnotationTypes("com.github.samblake.bob.builder.Bob")
public class BobProcessor extends AbstractProcessor {

    private static final ClassName MISSING = ClassName.get(Missing.class);
    private static final ClassName PRESENT = ClassName.get(Present.class);

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Bob.class)) {
            if (isValidTarget(element)) {
                generate((TypeElement) element);
            }
        }
        return true;
    }

    private boolean isValidTarget(Element element) {
        if (element.getKind() != ElementKind.CLASS) {
            return error(element, "@Bob can only be used on a class");
        }
        TypeElement type = (TypeElement) element;
        if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
            return error(type, "@Bob can only be used on a top level class");
        }
        if (type.getModifiers().contains(ABSTRACT)) {
            return error(type, "@Bob cannot be used on an abstract class");
        }
        if (!type.getTypeParameters().isEmpty()) {
            return error(type, "@Bob does not support generic classes");
        }
        List<ExecutableElement> constructors = constructorsOf(type);
        if (constructors.size() != 1) {
            return error(type, "@Bob needs exactly one non private constructor");
        }
        if (constructors.get(0).getParameters().isEmpty()) {
            return error(type, "@Bob needs a constructor with at least one parameter");
        }
        if (!constructors.get(0).getTypeParameters().isEmpty()) {
            return error(type, "@Bob does not support generic constructors");
        }
        String baseName = type.getSimpleName() + "Builders";
        String superclass = type.getSuperclass().toString();
        if (!superclass.equals(baseName) && !superclass.equals(packageOf(type) + "." + baseName)) {
            return error(type, type.getSimpleName() + " must extend the generated " + baseName);
        }
        return true;
    }

    private void generate(TypeElement type) {
        ExecutableElement constructor = constructorsOf(type).get(0);
        ClassName builder = ClassName.get(type).peerClass(type.getSimpleName() + "Builder");
        TypeName completeType = builderOf(builder, Collections.nCopies(constructor.getParameters().size(), PRESENT));
        ParameterSpec complete = ParameterSpec.builder(completeType, "builder").build();

        write(type, builderType(type, constructor, builder, complete));
        write(type, baseType(type, constructor, builder, complete));
    }

    private static TypeSpec builderType(
            TypeElement type, ExecutableElement constructor, ClassName builder, ParameterSpec complete) {
        List<? extends VariableElement> parameters = constructor.getParameters();
        List<TypeVariableName> states = map(parameters, parameter -> TypeVariableName.get(stateOf(parameter)));
        TypeSpec.Builder builderType = TypeSpec.classBuilder(builder)
                .addOriginatingElement(type)
                .addModifiers(visibilityOf(type))
                .addModifiers(FINAL)
                .addTypeVariables(states);

        MethodSpec.Builder builderConstructor = MethodSpec.constructorBuilder().addModifiers(PRIVATE);
        for (VariableElement parameter : parameters) {
            TypeName parameterType = TypeName.get(parameter.asType());
            builderType.addField(parameterType, nameOf(parameter), PRIVATE, FINAL);
            builderConstructor
                    .addParameter(parameterType, nameOf(parameter))
                    .addStatement("this.$N = $N", nameOf(parameter), nameOf(parameter));
        }
        builderType.addMethod(builderConstructor.build());

        builderType.addMethod(MethodSpec.methodBuilder("builder")
                .addModifiers(PUBLIC, STATIC)
                .returns(builderOf(builder, Collections.nCopies(parameters.size(), MISSING)))
                .addStatement("return new $T<>($L)", builder,
                        CodeBlock.join(map(parameters, BobProcessor::defaultOf), ", "))
                .build());

        CodeBlock arguments = CodeBlock.join(map(parameters, parameter -> CodeBlock.of("$N", nameOf(parameter))), ", ");
        for (int i = 0; i < parameters.size(); i++) {
            VariableElement parameter = parameters.get(i);
            List<TypeName> state = new ArrayList<>(states);
            state.set(i, PRESENT);
            builderType.addMethod(MethodSpec.methodBuilder("with" + capitalise(nameOf(parameter)))
                    .addModifiers(PUBLIC)
                    .returns(builderOf(builder, state))
                    .addParameter(TypeName.get(parameter.asType()), nameOf(parameter))
                    .addStatement("return new $T<>($L)", builder, arguments)
                    .build());
        }

        builderType.addMethod(MethodSpec.methodBuilder("build")
                .addModifiers(STATIC)
                .returns(ClassName.get(type))
                .addParameter(complete)
                .addExceptions(thrownBy(constructor))
                .addStatement("return new $T($L)", ClassName.get(type), CodeBlock.join(
                        map(parameters, parameter -> CodeBlock.of("$N.$N", complete, nameOf(parameter))), ", "))
                .build());

        return builderType.build();
    }

    private static TypeSpec baseType(TypeElement type,
            ExecutableElement constructor, ClassName builder, ParameterSpec complete) {

        return TypeSpec.classBuilder(builder.peerClass(type.getSimpleName() + "Builders"))
                .addOriginatingElement(type)
                .addModifiers(visibilityOf(type))
                .addModifiers(ABSTRACT)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(PROTECTED).build())
                .addMethod(MethodSpec.methodBuilder("from")
                        .addModifiers(PUBLIC, STATIC)
                        .returns(ClassName.get(type))
                        .addParameter(complete)
                        .addExceptions(thrownBy(constructor))
                        .addStatement("return $T.build($N)", builder, complete)
                        .build())
                .build();
    }

    private void write(TypeElement origin, TypeSpec generated) {
        try {
            JavaFile.builder(packageOf(origin), generated)
                    .skipJavaLangImports(true)
                    .indent("    ")
                    .build()
                    .writeTo(processingEnv.getFiler());
        }
        catch (IOException e) {
            error(origin, "Could not write " + generated.name + ": " + e.getMessage());
        }
    }

    private boolean error(Element element, String message) {
        processingEnv.getMessager().printMessage(ERROR, message, element);
        return false;
    }

    private String packageOf(TypeElement type) {
        return processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
    }

    private static Modifier[] visibilityOf(TypeElement type) {
        return type.getModifiers().contains(PUBLIC)
                ? new Modifier[] {PUBLIC}
                : new Modifier[0];
    }

    private static List<TypeName> thrownBy(ExecutableElement constructor) {
        return map(constructor.getThrownTypes(), TypeName::get);
    }

    private static TypeName builderOf(ClassName builder, List<? extends TypeName> states) {
        return ParameterizedTypeName.get(builder, states.toArray(new TypeName[0]));
    }

    private static List<ExecutableElement> constructorsOf(TypeElement type) {
        return constructorsIn(type.getEnclosedElements()).stream()
                .filter(constructor -> !constructor.getModifiers().contains(PRIVATE))
                .collect(toList());
    }

    private static String nameOf(VariableElement parameter) {
        return parameter.getSimpleName().toString();
    }

    private static String stateOf(VariableElement parameter) {
        return capitalise(nameOf(parameter)) + "State";
    }

    private static CodeBlock defaultOf(VariableElement parameter) {
        TypeMirror type = parameter.asType();
        if (type.getKind() == TypeKind.BOOLEAN) {
            return CodeBlock.of("false");
        }
        if (type.getKind() == TypeKind.CHAR) {
            return CodeBlock.of("'\\0'");
        }
        return type.getKind().isPrimitive()
                ? CodeBlock.of("($T) 0", TypeName.get(type))
                : CodeBlock.of("null");
    }

    private static String capitalise(String name) {
        return toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static <T, R> List<R> map(List<? extends T> items, Function<T, R> mapper) {
        return items.stream()
                .map(mapper)
                .collect(toList());
    }

}