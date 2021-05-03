/*
 * Copyright ${year} the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;

import javax.annotation.Nullable;
import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * Provides access to Rewrite classes resolved and loaded from the supplied dependency configuration.
 * This keeps them isolated from the rest of Gradle's runtime classpath.
 * So there shouldn't be problems with conflicting transitive dependency versions or anything like that.
 */
@SuppressWarnings({"unchecked", "UnusedReturnValue", "InnerClassMayBeStatic"})
public class RewriteReflectiveFacade {

    private final Configuration configuration;
    private final RewriteExtension extension;
    private final AbstractRewriteTask task;
    private URLClassLoader classLoader;

    public RewriteReflectiveFacade(Configuration configuration, RewriteExtension extension, AbstractRewriteTask task) {
        this.configuration = configuration;
        this.extension = extension;
        this.task = task;
    }

    private URLClassLoader getClassLoader() {
        // Lazily populate classLoader so that the configuration and extension have a chance to be altered by the rest of the build.gradle
        if (classLoader == null) {
            // Once resolved no new dependencies may be added to a configuration
            // If there are multiple rewrite tasks in the same project, as there usually are, the first will resolve the configuration
            Configuration confWithRewrite = task.getProject().getConfigurations().maybeCreate("rewrite" + task.getName());
            confWithRewrite.extendsFrom(configuration);

            DependencyHandler dependencies = task.getProject().getDependencies();
            String configurationName = confWithRewrite.getName();
            String rewriteVersion = extension.getRewriteVersion();
            dependencies.add(configurationName, "org.openrewrite:rewrite-java-11:" + rewriteVersion);
            dependencies.add(configurationName, "org.openrewrite:rewrite-java-8:" + rewriteVersion);
            dependencies.add(configurationName, "org.openrewrite:rewrite-xml:" + rewriteVersion);
            dependencies.add(configurationName, "org.openrewrite:rewrite-yaml:" + rewriteVersion);
            dependencies.add(configurationName, "org.openrewrite:rewrite-properties:" + rewriteVersion);
            dependencies.add(configurationName, "org.openrewrite:rewrite-maven:" + rewriteVersion);
            // Some rewrite classes use slf4j loggers (even though they probably shouldn't)
            // Ideally this would be the same implementation used by Gradle at runtime
            // But there are reflection and classpath shenanigans that make that one hard to get at
            dependencies.add(configurationName, "org.slf4j:slf4j-simple:1.7.30");

            URL[] jars = confWithRewrite.getFiles().stream()
                    .map(File::toURI)
                    .map(uri -> {
                        try {
                            return uri.toURL();
                        } catch (MalformedURLException e) {
                            throw new RuntimeException(e);
                        }
                    }).toArray(URL[]::new);
            classLoader = new URLClassLoader(jars);
        }
        return classLoader;
    }

    private List<SourceFile> parseBase(Object real, Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
        try {
            Class<?> executionContextClass = getClassLoader().loadClass("org.openrewrite.ExecutionContext");
            List<Object> results = (List<Object>) real.getClass()
                    .getMethod("parse", Iterable.class, Path.class, executionContextClass)
                    .invoke(real, sourcePaths, baseDir, ctx.real);
            return results.stream()
                    .map(SourceFile::new)
                    .collect(toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class EnvironmentBuilder {
        private final Object real;
        private EnvironmentBuilder(Object real) {
            this.real = real;
        }

        public EnvironmentBuilder scanRuntimeClasspath(String... acceptPackages) {
            try {
                real.getClass().getMethod("scanRuntimeClasspath", String[].class).invoke(real, new Object[]{ acceptPackages});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder scanClasspath(Iterable<Path> compileClasspath, String ... acceptPackages) {
            try {
                real.getClass().getMethod("scanClasspath", Iterable.class, String[].class)
                        .invoke(real, compileClasspath, acceptPackages);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder scanUserHome() {
            try {
                real.getClass().getMethod("scanUserHome").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder load(YamlResourceLoader yamlResourceLoader) {
            try {
                Class<?> resourceLoaderClass = getClassLoader().loadClass("org.openrewrite.config.ResourceLoader");
                real.getClass().getMethod("load", resourceLoaderClass).invoke(real, yamlResourceLoader.real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public Environment build() {
            try {
                return new Environment(real.getClass().getMethod("build").invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class SourceFile {
        private final Object real;

        private SourceFile(Object real) {
            this.real = real;
        }

        public Path getSourcePath() {
            try {
                return (Path) real.getClass().getMethod("getSourcePath").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String print() {
            try {
                return (String) real.getClass().getMethod("print").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class Result {
        private final Object real;

        private Result(Object real) {
            this.real = real;
        }

        @Nullable public SourceFile getBefore() {
            try {
                return new SourceFile(real.getClass().getMethod("getBefore").invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Nullable public SourceFile getAfter() {
            try {
                return new SourceFile(real.getClass().getMethod("getAfter").invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public List<Recipe> getRecipesThatMadeChanges() {
            try {
                Set<Object> result = (Set<Object>) real.getClass().getMethod("getRecipesThatMadeChanges").invoke(real);
                return result.stream()
                        .map(Recipe::new)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String diff(){
            try {
                return (String) real.getClass().getMethod("diff").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class Recipe {
        private final Object real;

        private Recipe(Object real) {
            this.real = real;
        }

        public List<Result> run(List<SourceFile> sources) {
            try {
                List<Object> unwrappedSources = sources.stream().map(it -> it.real).collect(toList());
                List<Object> result = (List<Object>) real.getClass().getMethod("run", List.class)
                        .invoke(real, unwrappedSources);
                return result.stream()
                        .map(Result::new)
                        .collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String getName() {
            try {
                return (String) real.getClass().getMethod("getName").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Collection<Validated> validateAll() {
            try {
                List<Object> results = (List<Object>) real.getClass().getMethod("validateAll").invoke(real);
                return results.stream().map(r -> {
                    String canonicalName = r.getClass().getCanonicalName();
                    if (canonicalName.equals("org.openrewrite.Validated.Invalid")) {
                        return new Validated.Invalid(r);
                    } else if (canonicalName.equals("org.openrewrite.Validated.Both")) {
                        return new Validated.Both(r);
                    } else {
                        return null;
                    }
                }).filter(Objects::nonNull).collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

    public interface Validated {

        Object getReal();

        default List<Invalid> failures() {
            try {
                Object real = getReal();
                List<Object> results = (List<Object>) real.getClass().getMethod("failures").invoke(real);
                return results.stream().map(Invalid::new).collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        class Invalid implements Validated {

            private final Object real;

            public Invalid(Object real) {
                this.real = real;
            }

            @Override
            public Object getReal() {
                return real;
            }

            public String getProperty() {
                try {
                    return (String) real.getClass().getMethod("getProperty").invoke(real);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            public String getMessage() {
                try {
                    return (String) real.getClass().getMethod("getMessage").invoke(real);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            public Throwable getException() {
                try {
                    return (Throwable) real.getClass().getMethod("getException").invoke(real);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        class Both implements Validated {

            private final Object real;

            public Both(Object real) {
                this.real = real;
            }

            @Override
            public Object getReal() {
                return real;
            }
        }
    }

    public class NamedStyles {
        private final Object real;

        private NamedStyles(Object real) {
            this.real = real;
        }

        public String getName() {
            try {
                return (String) real.getClass().getMethod("getName").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class Environment {
        private final Object real;

        private Environment(Object real) {
            this.real = real;
        }

        public List<NamedStyles> activateStyles(Iterable<String> activeStyles) {
            try {
                //noinspection unchecked
                List<Object> raw = (List<Object>) real.getClass()
                        .getMethod("activateStyles", Iterable.class)
                        .invoke(real, activeStyles);
                return raw.stream()
                        .map(NamedStyles::new)
                        .collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Recipe activateRecipes(Iterable<String> activeRecipes) {
            try {
                return new Recipe(real.getClass()
                        .getMethod("activateRecipes", Iterable.class)
                        .invoke(real, activeRecipes));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Collection<RecipeDescriptor> listRecipeDescriptors() {
            try {
                Collection<Object> result = (Collection<Object>) real.getClass().getMethod("listRecipeDescriptors").invoke(real);
                return result.stream()
                        .map(RecipeDescriptor::new)
                        .collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Collection<NamedStyles> listStyles() {
            try {
                List<Object> raw = (List<Object>) real.getClass().getMethod("listStyles").invoke(real);
                return raw.stream()
                        .map(NamedStyles::new)
                        .collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

    public class RecipeDescriptor {
        private final Object real;

        private RecipeDescriptor(Object real) {
            this.real = real;
        }

        public String getName() {
            try {
                return (String) real.getClass().getMethod("getName").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public EnvironmentBuilder environmentBuilder(Properties properties) {
        try {
            return new EnvironmentBuilder(getClassLoader()
                    .loadClass("org.openrewrite.config.Environment")
                    .getMethod("builder", Properties.class)
                    .invoke(null, properties)
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class YamlResourceLoader {
        private final Object real;

        private YamlResourceLoader(Object real) {
            this.real = real;
        }
    }

    public YamlResourceLoader yamlResourceLoader(InputStream yamlInput, URI source, Properties properties) {
        try {
            return new YamlResourceLoader(getClassLoader()
                    .loadClass("org.openrewrite.config.YamlResourceLoader")
                    .getConstructor(InputStream.class, URI.class, Properties.class)
                    .newInstance(yamlInput, source, properties)
            );
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class InMemoryExecutionContext {
        private final Object real;

        private InMemoryExecutionContext(Object real) {
            this.real = real;
        }
    }

    public InMemoryExecutionContext inMemoryExecutionContext(Consumer<Throwable> onError) {
        try {
            return new InMemoryExecutionContext(getClassLoader()
                    .loadClass("org.openrewrite.InMemoryExecutionContext")
                    .getConstructor(Consumer.class)
                    .newInstance(onError));
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class JavaParserBuilder {
        private final Object real;

        private JavaParserBuilder(Object real) {
            this.real = real;
        }

        public JavaParserBuilder styles(List<NamedStyles> styles) {
            try {
                List<Object> unwrappedStyles = styles.stream()
                        .map(it -> it.real)
                        .collect(toList());
                real.getClass().getMethod("styles", Iterable.class).invoke(real, unwrappedStyles);
                return this;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public JavaParserBuilder classpath(Collection<Path> classpath) {
            try {
                real.getClass().getMethod("classpath", Collection.class).invoke(real, classpath);
                return this;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public JavaParserBuilder logCompilationWarningsAndErrors(boolean logCompilationWarningsAndErrors) {
            try {
                real.getClass().getMethod("logCompilationWarningsAndErrors", boolean.class).invoke(real, logCompilationWarningsAndErrors);
                return this;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public JavaParser build() {
            try {
                return new JavaParser(real.getClass().getMethod("build").invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class JavaParser {
        private final Object real;

        private JavaParser(Object real) {
            this.real = real;
        }

        public List<SourceFile> parse(Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
            return parseBase(real, sourcePaths, baseDir, ctx);
        }
    }

    public JavaParserBuilder javaParserFromJavaVersion() {
        try {
            if (System.getProperty("java.version").startsWith("1.8")) {
                return new JavaParserBuilder( getClassLoader()
                        .loadClass("org.openrewrite.java.Java8Parser")
                        .getMethod("builder")
                        .invoke(null));
            }
            return new JavaParserBuilder(getClassLoader()
                    .loadClass("org.openrewrite.java.Java11Parser")
                    .getMethod("builder")
                    .invoke(null));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class YamlParser {
        private final Object real;

        private YamlParser(Object real) {
            this.real = real;
        }

        public List<SourceFile> parse(Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
            return parseBase(real, sourcePaths, baseDir, ctx);
        }
    }

    public YamlParser yamlParser() {
        try {
            return new YamlParser(getClassLoader().loadClass("org.openrewrite.yaml.YamlParser")
                    .getDeclaredConstructor()
                    .newInstance());
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class PropertiesParser {
        private final Object real;

        private PropertiesParser(Object real) {
            this.real = real;
        }

        public List<SourceFile> parse(Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
            return parseBase(real, sourcePaths, baseDir, ctx);
        }
    }

    public PropertiesParser propertiesParser() {
        try {
            return new PropertiesParser(getClassLoader().loadClass("org.openrewrite.properties.PropertiesParser")
                    .getDeclaredConstructor()
                    .newInstance());
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class XmlParser {
        private final Object real;

        private XmlParser(Object real) {
            this.real = real;
        }

        public List<SourceFile> parse(Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
            return parseBase(real, sourcePaths, baseDir, ctx);
        }
    }

    public XmlParser xmlParser() {
        try {
            return new XmlParser(getClassLoader().loadClass("org.openrewrite.xml.XmlParser")
                    .getDeclaredConstructor()
                    .newInstance());
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}
