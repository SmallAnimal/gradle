/*
 * Copyright 2013 the original author or authors.
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


package org.gradle.internal.component.external.model

import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.api.internal.ExperimentalFeatures
import org.gradle.api.internal.artifacts.dependencies.DefaultMutableVersionConstraint
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.internal.component.external.descriptor.Configuration
import org.gradle.internal.component.external.descriptor.MavenScope
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.ModuleSource
import org.gradle.util.TestUtil
import spock.lang.Unroll

import static org.gradle.internal.component.external.model.DefaultModuleComponentSelector.newSelector

class DefaultMavenModuleResolveMetadataTest extends AbstractModuleComponentResolveMetadataTest {
    private final ImmutableAttributesFactory attributesFactory = TestUtil.attributesFactory()
    private final experimentalFeatures = new ExperimentalFeatures()

    @Override
    AbstractModuleComponentResolveMetadata createMetadata(ModuleComponentIdentifier id, List<Configuration> configurations, List<DependencyMetadata> dependencies) {
        return new DefaultMavenModuleResolveMetadata(new DefaultMutableMavenModuleResolveMetadata(Mock(ModuleVersionIdentifier), id, dependencies), attributesFactory, experimentalFeatures)
    }

    def "builds and caches dependencies for a scope"() {
        given:
        configuration("compile")
        configuration("runtime", ["compile"])
        dependency("org", "module", "1.1", "Compile")
        dependency("org", "module", "1.2", "Runtime")
        dependency("org", "module", "1.3", "Test")
        dependency("org", "module", "1.4", "System")

        when:
        def md = metadata
        def runtime = md.getConfiguration("runtime")
        def compile = md.getConfiguration("compile")

        then:
        runtime.dependencies*.selector*.versionConstraint.preferredVersion == ["1.1", "1.2"]
        runtime.dependencies.is(runtime.dependencies)

        compile.dependencies*.selector*.versionConstraint.preferredVersion == ["1.1"]
        compile.dependencies.is(compile.dependencies)
    }

    def "builds and caches artifacts for a configuration"() {
        when:
        def runtime = metadata.getConfiguration("runtime")

        then:
        runtime.artifacts*.name.name == ["module"]
        runtime.artifacts*.name.extension == ["jar"]
        runtime.artifacts.is(runtime.artifacts)
    }

    @Unroll
    def "the #config configuration contains a single variant containing no attributes and the artifacts of the configuration"() {
        given:
        experimentalFeatures.enable()

        when:
        def configMetadata = metadata.getConfiguration(config)

        then:
        configMetadata.variants.size() == 1
        configMetadata.variants.first().attributes.empty
        configMetadata.variants.first().artifacts == configMetadata.artifacts

        where:
        config     | _
        "master"   | _
        "provided" | _
        "test"     | _
        "system"   | _
        "sources"  | _
        "javadoc"  | _
        "optional" | _
        "default"  | _
    }

    @Unroll
    def "the #config configuration contains a single variant containing Java library attributes and the artifacts of the configuration"() {
        given:
        experimentalFeatures.enable()

        when:
        def configMetadata = metadata.getConfiguration(config)

        then:
        configMetadata.variants.size() == 1
        configMetadata.variants.first().attributes.contains(Attribute.of(Usage.USAGE_ATTRIBUTE.name, String))
        configMetadata.variants.first().attributes.getAttribute(Attribute.of(Usage.USAGE_ATTRIBUTE.name, String)) == usage
        configMetadata.variants.first().artifacts == configMetadata.artifacts

        where:
        config     | usage
        "compile"  | Usage.JAVA_API
        "runtime"  | Usage.JAVA_RUNTIME
    }

    def "artifacts include union of those inherited from other configurations"() {
        when:
        def compileArtifacts = metadata.getConfiguration("compile").artifacts
        def runtimeArtifacts = metadata.getConfiguration("runtime").artifacts
        def defaultArtifacts = metadata.getConfiguration("default").artifacts

        then:
        runtimeArtifacts.size() == compileArtifacts.size()
        defaultArtifacts.size() == runtimeArtifacts.size()
    }

    def "copy with different source"() {
        given:
        def source = Stub(ModuleSource)
        def mutable = new DefaultMutableMavenModuleResolveMetadata(Mock(ModuleVersionIdentifier), id)
        mutable.packaging = "other"
        mutable.relocated = true
        mutable.snapshotTimestamp = "123"
        def metadata = mutable.asImmutable(attributesFactory, new ExperimentalFeatures())

        when:
        def copy = metadata.withSource(source)

        then:
        copy.source == source
        copy.packaging == "other"
        copy.relocated
        copy.snapshotTimestamp == "123"
    }

    def "recognises pom packaging"() {
        when:
        def metadata = new DefaultMutableMavenModuleResolveMetadata(Mock(ModuleVersionIdentifier), id)
        metadata.packaging = packaging

        then:
        metadata.packaging == packaging
        metadata.pomPackaging == isPom
        metadata.knownJarPackaging == isJar

        where:
        packaging      | isPom | isJar
        "pom"          | true  | false
        "jar"          | false | true
        "war"          | false | false
        "maven-plugin" | false | true
    }

    def dependency(String org, String module, String version, String scope) {
        def selector = newSelector(org, module, new DefaultMutableVersionConstraint(version))
        dependencies.add(new MavenDependencyDescriptor(MavenScope.valueOf(scope), false, selector, null, []))
    }

}
