/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.front50.pipeline

import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import static com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType.PIPELINE

class PipelineStageSpec extends Specification {
    def executionRepository = Mock(ExecutionRepository)

    @Subject
    def pipelineStage = new PipelineStage(executionRepository)

    @Unroll
    def "should cancel child pipeline (if started and not already canceled)"() {
        given:
        def childPipeline = PipelineExecutionImpl.newPipeline("childPipeline")
        childPipeline.canceled = childPipelineIsCanceled

        def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "pipeline", stageContext)

        and:
        executionRepository.retrieve(PIPELINE, stageContext.executionId) >> childPipeline

        when:
        pipelineStage.cancel(stage)

        then:
        (shouldCancel ? 1 : 0) * executionRepository.cancel(PIPELINE, stageContext.executionId, "parent pipeline", null)

        where:
        stageContext                     || childPipelineIsCanceled || shouldCancel
        [:]                              || false                   || false            // child pipeline has not started
        [executionId: "sub-pipeline-id"] || false                   || true            // child pipeline has started and should cancel
        [executionId: "sub-pipeline-id"] || true                    || false            // child pipeline has already been canceled
    }

    @Unroll
    def "should skip pipeline if manually skipped"() {
        given:
        def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("testapp"), "pipeline", stageContext)

        when:
        def canSkip = pipelineStage.canManuallySkip(stage)

        then:
        assert canSkip == expectCanSkip

        where:
        stageContext       || expectCanSkip
        [:]                || false
        [skippable: true]  || true
        [skippable: false] || false
    }
}
