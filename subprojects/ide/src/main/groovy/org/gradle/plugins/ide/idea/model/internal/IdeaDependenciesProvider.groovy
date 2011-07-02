/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.plugins.ide.idea.model.internal

import org.gradle.api.Project
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency
import org.gradle.api.specs.Spec
import org.gradle.plugins.ide.idea.model.IdeaModule
import org.gradle.plugins.ide.idea.model.ModuleLibrary
import org.gradle.plugins.ide.idea.model.Path
import org.gradle.plugins.ide.idea.model.PathFactory
import org.gradle.plugins.ide.internal.IdeDependenciesExtractor
import org.gradle.api.artifacts.*

/**
 * All code was refactored from the GenerateIdeaModule class.
 * TODO: This class should be refactored so that we can reuse it in Eclipse plugin also.
 *
 * @author Szczepan Faber, created at: 4/1/11
 */
class IdeaDependenciesProvider {

    Project project
    Map<String, Map<String, Collection<Configuration>>> scopes
    boolean downloadSources
    boolean downloadJavadoc
    PathFactory pathFactory

    private final IdeDependenciesExtractor dependenciesExtractor = new IdeDependenciesExtractor()

    Set<org.gradle.plugins.ide.idea.model.Dependency> provide(IdeaModule ideaModule, PathFactory pathFactory) {
        this.project = ideaModule.project
        this.scopes = ideaModule.scopes
        this.downloadSources = ideaModule.downloadSources
        this.downloadJavadoc = ideaModule.downloadJavadoc
        this.pathFactory = pathFactory

        Set result = new LinkedHashSet()
        ideaModule.singleEntryLibraries.each { scope, files ->
            files.each {
                if (it && it.isDirectory()) {
                    result << new ModuleLibrary([getPath(it)] as Set, [] as Set, [] as Set, [] as Set, scope)
                }
            }
        }

        scopes.keySet().each { scope ->
            result.addAll(getModuleLibraries(scope))
            result.addAll(getModules(scope))
            result
        }

        return result
    }

    protected Set getModules(String scope) {
        def s = scopes[scope]
        if (s) {
            return dependenciesExtractor.extractProjectDependencies(s.plus, s.minus).collect {
                new ModuleDependencyBuilder().create(it.dependency, scope)
            }
        }
        return []
    }

    protected Set getModuleLibraries(String scope) {
        if (!scopes[scope]) { return [] }

        def repoFileDependencies = dependenciesExtractor.extractRepoFileDependencies(
                project.configurations, scopes[scope].plus, scopes[scope].minus, downloadSources, downloadJavadoc)

        List moduleLibraries = repoFileDependencies.collect {
            new ModuleLibrary([getPath(it.dependency)] as Set, it.javadoc ? [getPath(it.javadoc)] as Set : [] as Set, it.source ? [getPath(it.source)] as Set : [] as Set, [] as Set, scope)
        }

        moduleLibraries.addAll(getSelfResolvingFiles(getScopeDependencies(scopes[scope],
                { it instanceof SelfResolvingDependency && !(it instanceof ProjectDependency)}), scope))
        return moduleLibraries as LinkedHashSet
    }

    private def getSelfResolvingFiles(Collection dependencies, String scope) {
        dependencies.inject([] as LinkedHashSet) { result, SelfResolvingDependency selfResolvingDependency ->
            result.addAll(selfResolvingDependency.resolve().collect { File file ->
                new ModuleLibrary([getPath(file)] as Set, [] as Set, [] as Set, [] as Set, scope)
            })
            result
        }
    }

    private Set getScopeDependencies(Map<String, Collection<Configuration>> configurations, Closure filter) {
        Set firstLevelDependencies = new LinkedHashSet()
        configurations.plus.each { Configuration configuration ->
            firstLevelDependencies.addAll(configuration.getAllDependencies().findAll(filter))
        }
        configurations.minus.each { Configuration configuration ->
            configuration.getAllDependencies().findAll(filter).each { minusDep ->
                // This deals with dependencies that are defined in different scopes with different
                // artifacts. Right now we accept the fact, that in such a situation some artifacts
                // might be duplicated in Idea (they live in different scopes then).
                if (minusDep instanceof ExternalDependency) {
                    ExternalDependency removeCandidate = firstLevelDependencies.find { it == minusDep }
                    if (removeCandidate && removeCandidate.artifacts == minusDep.artifacts) {
                        firstLevelDependencies.remove(removeCandidate)
                    }
                } else {
                    firstLevelDependencies.remove(minusDep)
                }
            }
        }
        return firstLevelDependencies
    }

    private Set<ResolvedDependency> resolveDependencies(Collection<Configuration> plusConfigurations, Collection<Configuration> minusConfigurations) {
        def result = new LinkedHashSet()
        for (plusConfiguration in plusConfigurations) {
            result.addAll(getAllDeps(plusConfiguration.resolvedConfiguration.getFirstLevelModuleDependencies({ it instanceof ExternalDependency } as Spec)))
        }
        for (minusConfiguration in minusConfigurations) {
            result.removeAll(getAllDeps(minusConfiguration.resolvedConfiguration.getFirstLevelModuleDependencies({ it instanceof ExternalDependency } as Spec)))
        }
        result
    }

    private Set<File> resolveFiles(Collection<Configuration> plusConfigurations, Collection<Configuration> minusConfigurations) {
        def result = new LinkedHashSet()
        for (plusConfiguration in plusConfigurations) {
            result.addAll(plusConfiguration.files { it instanceof ExternalDependency })
        }
        for (minusConfiguration in minusConfigurations) {
            result.removeAll(minusConfiguration.files { it instanceof ExternalDependency })
        }
        result
    }

    private getFiles(Set dependencies, String classifier) {
        return project.configurations.detachedConfiguration((dependencies as Dependency[])).files.inject([:]) { result, sourceFile ->
            String key = sourceFile.name.replace("-${classifier}.jar", '.jar')
            result[key] = sourceFile
            result
        }
    }

    private List getResolvableDependenciesForAllResolvedDependencies(Set allResolvedDependencies, Closure configureClosure) {
        return allResolvedDependencies.collect { ResolvedDependency resolvedDependency ->
            def dependency = new DefaultExternalModuleDependency(resolvedDependency.moduleGroup, resolvedDependency.moduleName, resolvedDependency.moduleVersion,
                    resolvedDependency.configuration)
            dependency.transitive = false
            configureClosure.call(dependency)
            dependency
        }
    }

    protected Set getAllDeps(Collection deps, Set allDeps = []) {
        deps.each { ResolvedDependency resolvedDependency ->
            def notSeenBefore = allDeps.add(resolvedDependency)
            if (notSeenBefore) { // defend against circular dependencies
                getAllDeps(resolvedDependency.children, allDeps)
            }
        }
        allDeps
    }

    protected addSourceArtifact(DefaultExternalModuleDependency dependency) {
        dependency.artifact { artifact ->
            artifact.name = dependency.name
            artifact.type = 'source'
            artifact.extension = 'jar'
            artifact.classifier = 'sources'
        }
    }

    protected addJavadocArtifact(DefaultExternalModuleDependency dependency) {
        dependency.artifact { artifact ->
            artifact.name = dependency.name
            artifact.type = 'javadoc'
            artifact.extension = 'jar'
            artifact.classifier = 'javadoc'
        }
    }

    protected Path getPath(File file) {
        return pathFactory.path(file)
    }
}
