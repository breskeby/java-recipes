package com.breskeby.rewrite.java.backport;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.util.stream.Collectors.joining;
import static java.util.stream.IntStream.rangeClosed;

public class MapOfBackportRecipe extends Recipe {

    private static final MethodMatcher MAP_OF_MATCHER = new MethodMatcher("java.util.Map of(..)");

    @Override
    public String getDisplayName() {
        return "MapOfBackportRecipe";
    }

    @Override
    public String getDescription() {
        return "Backport java.util.Map.of() to Java 8 compliant plain java API";
    }

    @Override
    protected JavaVisitor<ExecutionContext> getVisitor() {
        return new BackportMapsOfVisitor();
    }

    public class BackportMapsOfVisitor extends JavaIsoVisitor<ExecutionContext> {
        private JavaType.FullyQualified classType;
        private Map<UUID, Set<Integer>> usedMethodParamSizes = new HashMap<>();
        private UUID currentClassId;

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext executionContext) {
            method = super.visitMethodInvocation(method, executionContext);
            JavaType.Method type = method.getType();
            if (type != null && MAP_OF_MATCHER.matches(method)) {
                int paramCount = method.getArguments().size();
                usedMethodParamSizes.compute(currentClassId, (uuid, integers) -> {
                    if (integers == null) {
                        integers = new HashSet<>();
                    }
                    integers.add(paramCount);
                    return integers;
                });
                String code = "mapOf(" +
                        rangeClosed(1, paramCount).mapToObj(i -> "#{any()}").collect(joining(", ")) + ")";
                JavaTemplate mapOfUsage = JavaTemplate.builder(this::getCursor, code).build();
                JavaType.Parameterized parameterized = (JavaType.Parameterized) method.getType().getDeclaringType();
                JavaType.Parameterized build = JavaType.Parameterized.build(classType, parameterized.getTypeParameters());
                JavaType.Method mapOfType = method.getType().withName("mapOf").withDeclaringType(build);
                method = method.withTemplate(mapOfUsage, method.getCoordinates().replace(), method.getArguments().toArray());
                return method.withType(mapOfType);
            }
            return method;
        }


        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExecutionContext executionContext) {
            classType = cd.getType();
            currentClassId = cd.getId();
            cd = super.visitClassDeclaration(cd, executionContext);
            for (Integer usedMethodParamSize : usedMethodParamSizes.getOrDefault(cd.getId(), Collections.emptySet())) {
                cd = maybeMapOfImplementation(cd, usedMethodParamSize);
            }
            return cd;
        }

        private J.ClassDeclaration maybeMapOfImplementation(J.ClassDeclaration cd, Integer usedMethodParamSize) {
            // Check if the class already has a method named "mapOf" so we don't incorrectly add a second "mapOf" method
            boolean helloMethodAlreadyExists = cd.getBody().getStatements().stream()
                    .filter(statement -> statement instanceof J.MethodDeclaration)
                    .map(J.MethodDeclaration.class::cast)
                    .anyMatch(md -> md.getName().getSimpleName().equals("mapOf") && md.getParameters().size() == usedMethodParamSize);

            if (helloMethodAlreadyExists == false) {
                cd = cd.withBody(
                        cd.getBody().withTemplate(
                                mapOfTemplate(usedMethodParamSize),
                                cd.getBody().getCoordinates().lastStatement()
                        ));
                maybeAddImport("java.util.HashMap");
                maybeAddImport("java.util.Collections");
            }
            return cd;
        }

        private JavaTemplate mapOfTemplate(Integer paramsCount) {
            String parameterString = rangeClosed(1, (paramsCount / 2)).mapToObj(i -> ("K k" + i + ", V v" + i))
                    .collect(joining(", "));
            String mapPutOperationString = rangeClosed(1, (paramsCount / 2)).mapToObj(i -> ("map.put(k" + i + ", v" + i + ");"))
                    .collect(joining("\n"));
            return JavaTemplate.builder(this::getCursor,
                    "private static <K, V> Map<K, V> mapOf(" + parameterString + ") {" +
                            "Map<K, V> map = new HashMap<K,V>();" +
                            mapPutOperationString +
                            "return Collections.unmodifiableMap(map)" +
                            "}")
                    .imports("java.util.Collections")
                    .imports("java.util.HashMap").build();
        }
    }
}