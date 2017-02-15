/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.plugin.repository

import groovy.transform.NotYetImplemented
import org.gradle.integtests.fixtures.AbstractDependencyResolutionTest
import org.gradle.test.fixtures.file.LeaksFileHandles
import org.gradle.test.fixtures.maven.MavenFileRepository
import org.gradle.test.fixtures.plugin.PluginBuilder
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import spock.lang.Unroll

@LeaksFileHandles
class ResolvingFromSingleCustomPluginManagementSpec extends AbstractDependencyResolutionTest {
    private static final String MAVEN = 'maven'
    private static final String IVY = 'ivy'

    private enum PathType {
        ABSOLUTE, RELATIVE
    }

    private publishTestPlugin(String repoType) {
        def pluginBuilder = new PluginBuilder(testDirectory.file("plugin"))

        def message = "from plugin"
        def taskName = "pluginTask"

        pluginBuilder.addPluginWithPrintlnTask(taskName, message, "org.example.plugin")

        if (repoType == IVY) {
            pluginBuilder.publishAs("org.example.plugin:plugin:1.0", ivyRepo, executer)
        } else if (repoType == MAVEN) {
            pluginBuilder.publishAs("org.example.plugin:plugin:1.0", mavenRepo, executer)
        }
    }

    private String useCustomRepository(String repoType, PathType pathType, String resolutionStrategy = "") {
        def repoUrl = buildRepoPath(repoType, pathType)
        settingsFile << """
          pluginManagement {
            $resolutionStrategy
            repositories {
                ${repoType} {
                    url "${repoUrl}"
                }
            }
          }
        """
        return repoUrl
    }

    private String buildRepoPath(String repoType, PathType pathType) {
        def repoUrl = 'Nothing'
        if (repoType == MAVEN) {
            repoUrl = PathType.ABSOLUTE.equals(pathType) ? mavenRepo.uri : mavenRepo.getRootDir().name
        } else if (repoType == IVY) {
            repoUrl = PathType.ABSOLUTE.equals(pathType) ? ivyRepo.uri : ivyRepo.getRootDir().name
        }
        return repoUrl
    }

    @Unroll
    def "can resolve plugin from #pathType #repoType repo"() {
        given:
        publishTestPlugin(repoType)
        buildScript """
          plugins {
              id "org.example.plugin" version "1.0"
          }
        """

        and:
        useCustomRepository(repoType, pathType)

        when:
        succeeds("pluginTask")

        then:
        output.contains("from plugin")

        where:
        repoType | pathType
        IVY      | PathType.ABSOLUTE
        IVY      | PathType.RELATIVE
        MAVEN    | PathType.ABSOLUTE
        MAVEN    | PathType.RELATIVE
    }

    @Unroll
    def "can access classes from plugin from #repoType repo"() {
        given:
        publishTestPlugin(repoType)
        buildScript """
          plugins {
              id "org.example.plugin" version "1.0"
          }
          plugins.withType(org.gradle.test.TestPlugin) {
            println "I'm here"
          }
        """

        and:
        useCustomRepository(repoType, PathType.ABSOLUTE)

        when:
        succeeds("pluginTask")

        then:
        output.contains("I'm here")

        where:
        repoType << [IVY, MAVEN]
    }

    def 'setting changing version in resolutionStrategy will effect plugin choice'() {
        given:
        publishTestPlugin(MAVEN)
        buildScript """
          plugins {
              id "org.example.plugin" version '1000'
          }
          plugins.withType(org.gradle.test.TestPlugin) {
            println "I'm here"
          }
        """

        and:
        useCustomRepository(MAVEN, PathType.ABSOLUTE, """
            resolutionStrategy.eachPlugin { request ->
                if(request.requested.id.name == 'plugin') {
                    request.useTarget { target ->
                        target.version = '1.0'
                    }
                }
            }
        """)

        when:
        succeeds("pluginTask")

        then:
        output.contains("I'm here")
    }

    def 'when invalid version is specified, it will throw'() {
        given:
        publishTestPlugin(MAVEN)
        buildScript """
          plugins {
              id "org.example.plugin" version '1.2'
          }
          plugins.withType(org.gradle.test.TestPlugin) {
            println "I'm here"
          }
        """

        and:
        useCustomRepository(MAVEN, PathType.ABSOLUTE, """
            resolutionStrategy.eachPlugin { request ->
                if(request.requested.id.name == 'plugin') {
                    request.useTarget { target ->
                        target.version = null
                    }
                }
            }
        """)

        when:
        fails("pluginTask")

        then:
        errorOutput.contains("Plugin [id: 'org.example.plugin']")
    }

    def 'can specify an artifact to use'() {
        given:
        publishTestPlugin(MAVEN)
        buildScript """
          plugins {
              id "org.example.plugin"
          }
          plugins.withType(org.gradle.test.TestPlugin) {
            println "I'm here"
          }
        """

        and:
        useCustomRepository(MAVEN, PathType.ABSOLUTE, """
            resolutionStrategy.eachPlugin { request ->
                if(request.requested.id.name == 'plugin') {
                    request.useTarget { target ->
                        target.artifact = 'org.example.plugin:plugin:1.0'
                    }
                }
            }
        """)

        when:
        fails("pluginTask")

        then:
        errorOutput.contains("Plugin [id: 'org.example.plugin']")
    }

    @Unroll
    def "can apply plugin from #repoType repo to subprojects"() {
        given:
        publishTestPlugin(repoType)
        buildScript """
          plugins {
              id "org.example.plugin" version "1.0" apply false
          }

          subprojects {
            apply plugin: 'org.example.plugin'
          }
        """

        and:
        useCustomRepository(repoType, PathType.ABSOLUTE)
        settingsFile << """
            include 'sub'
        """

        expect:
        succeeds("sub:pluginTask")

        where:
        repoType << [IVY, MAVEN]
    }

    @Unroll
    def "custom #repoType repo is not mentioned in plugin resolution errors if none is defined"() {
        given:
        publishTestPlugin(repoType)
        buildScript """
          plugins {
              id "org.example.plugin"
          }
        """

        when:
        fails("pluginTask")

        then:
        !failure.output.contains(repoType)

        where:
        repoType << [IVY, MAVEN]
    }

    @Unroll
    @Requires(TestPrecondition.ONLINE)
    def "Fails gracefully if a plugin is not found in #repoType repo"() {
        given:
        publishTestPlugin(repoType)
        buildScript """
          plugins {
              id "org.example.foo" version "1.1"
          }
        """

        and:
        def repoUrl = useCustomRepository(repoType, PathType.ABSOLUTE)

        when:
        fails("pluginTask")

        then:
        failure.assertHasDescription("""Plugin [id: 'org.example.foo', version: '1.1'] was not found in any of the following sources:

- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- ${repoType}(${repoUrl}) (Could not resolve plugin artifact 'org.example.foo:org.example.foo.gradle.plugin:1.1')"""
        )

        where:
        repoType << [IVY, MAVEN]
    }

    @Unroll
    def "Works with subprojects and relative #repoType repo specification."() {
        given:
        publishTestPlugin(repoType)
        def subprojectScript = file("subproject/build.gradle")
        subprojectScript << """
          plugins {
              id "org.example.plugin" version "1.0"
          }
        """

        and:
        useCustomRepository(repoType, PathType.RELATIVE)

        and:
        settingsFile << """
          include 'subproject'
        """

        expect:
        succeeds("subproject:pluginTask")

        where:
        repoType << [IVY, MAVEN]
    }

    @NotYetImplemented
    def "Can specify repo in init script."() {
        given:
        publishTestPlugin(MAVEN)
        buildScript """
           plugins {
             id "org.example.plugin" version "1.0"
           }
        """

        and:
        def initScript = file('definePluginRepo.gradle')
        initScript << """
          pluginRepositories {
            maven {
              url "${mavenRepo.uri}"
            }
          }
        """
        args('-I', initScript.absolutePath)

        when:
        succeeds('pluginTask')

        then:
        output.contains('from plugin')
    }

    def "can resolve plugins even if buildscript block contains wrong repo with same name"() {
        given:
        publishTestPlugin(MAVEN)
        buildScript """
          buildscript {
            repositories {
                maven {
                    url '${new MavenFileRepository(file("other-repo")).uri}'
                }
            }
          }
          plugins {
              id "org.example.plugin" version "1.0"
          }
        """

        and:
        useCustomRepository(MAVEN, PathType.ABSOLUTE)

        when:
        succeeds("pluginTask")

        then:
        output.contains("from plugin")
    }

    def "Does not fall through to Plugin Portal if custom repo is defined"() {
        given:
        publishTestPlugin(MAVEN)
        buildScript """
            plugins {
                id "org.gradle.hello-world" version "0.2" //this exists in the plugin portal
            }
        """

        and:
        useCustomRepository(MAVEN, PathType.ABSOLUTE)

        expect:
        fails("helloWorld")
    }
}