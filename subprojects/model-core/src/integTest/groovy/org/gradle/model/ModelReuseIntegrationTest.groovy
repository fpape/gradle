/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.model

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.EnableModelDsl
import org.gradle.integtests.fixtures.executer.DaemonGradleExecuter
import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.model.persist.ReusingModelRegistryStore
import spock.lang.IgnoreIf

@IgnoreIf({ GradleContextualExecuter.isDaemon() })
class ModelReuseIntegrationTest extends AbstractIntegrationSpec {

    def setup() {
        executer = new DaemonGradleExecuter(distribution, testDirectoryProvider)
        executer.beforeExecute {
            requireIsolatedDaemons()
            withArgument("-D$ReusingModelRegistryStore.TOGGLE=true")
            withDaemonIdleTimeoutSecs(5)
        }
        EnableModelDsl.enable(executer)
    }

    def cleanup() {
        executer.withArgument("--stop").run()
    }

    String hashFor(String prefix) {
        (output =~ /$prefix: (\d+)/)[0][1]
    }

    def "model elements are reused when toggle is enabled and when using daemon"() {
        when:
        buildScript """
            class Rules extends $RuleSource.name {
                @$Model.name
                List<String> vals() {
                  []
                }
            }

            pluginManager.apply Rules

            model {
                tasks {
                    create("show") {
                        doLast {
                            println "vals: " + System.identityHashCode(\$("vals"))
                            println "task: " + System.identityHashCode(it)
                        }
                    }
                }
            }
        """


        then:
        succeeds "show"
        ":show" in executedTasks
        output.contains ReusingModelRegistryStore.BANNER

        and:
        def valHash = hashFor("vals")
        def taskHash = hashFor("task")

        when:
        succeeds "show"

        then:
        valHash == hashFor("vals")
        taskHash != hashFor("task")
    }

    def "can enable reuse with the component model"() {
        when:
        buildScript """
            plugins {
              id "org.gradle.jvm-component"
              id "org.gradle.java-lang"
            }

            model {
                components {
                    println "creating component"
                    create("main", JvmLibrarySpec)
                }
            }
        """

        then:
        succeeds "build"
        succeeds "build"
    }

}
