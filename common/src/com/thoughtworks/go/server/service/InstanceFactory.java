/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
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
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.server.service;

import java.util.List;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.EnvironmentVariablesConfig;
import com.thoughtworks.go.config.JobConfig;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.StageConfig;
import com.thoughtworks.go.config.StageNotFoundException;
import com.thoughtworks.go.domain.DefaultJobPlan;
import com.thoughtworks.go.domain.JobIdentifier;
import com.thoughtworks.go.domain.JobInstance;
import com.thoughtworks.go.domain.JobInstances;
import com.thoughtworks.go.domain.JobPlan;
import com.thoughtworks.go.domain.JobType;
import com.thoughtworks.go.domain.Pipeline;
import com.thoughtworks.go.domain.RunOnAllAgents;
import com.thoughtworks.go.domain.SchedulingContext;
import com.thoughtworks.go.domain.SingleJobInstance;
import com.thoughtworks.go.domain.Stage;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.util.Clock;
import org.springframework.stereotype.Component;

@Component
public class InstanceFactory {
    public Pipeline createPipelineInstance(PipelineConfig pipelineConfig, BuildCause buildCause, SchedulingContext context, String md5, Clock clock) {
        buildCause.assertMaterialsMatch(pipelineConfig.materialConfigs());
        return new Pipeline(CaseInsensitiveString.str(pipelineConfig.name()), pipelineConfig.getLabelTemplate(), buildCause, createStageInstance(pipelineConfig.first(), context, md5, clock));
    }

    public Stage createStageInstance(PipelineConfig pipelineConfig, CaseInsensitiveString stageName, SchedulingContext context, String md5, Clock clock) {
        StageConfig stageConfig = pipelineConfig.findBy(stageName);
        if (stageConfig == null) {
            throw new StageNotFoundException(pipelineConfig.name(), stageName);
        }
        return createStageInstance(stageConfig, context, md5, clock);
    }

    public Stage createStageInstance(StageConfig stageConfig, SchedulingContext context, String md5, Clock clock) {
        return new Stage(CaseInsensitiveString.str(stageConfig.name()), createJobInstances(stageConfig, context, clock),
                context.getApprovedBy(), stageConfig.approvalType(), stageConfig.isFetchMaterials(), stageConfig.isCleanWorkingDir(), md5, clock);
    }

    public Stage createStageForRerunOfJobs(Stage stage, List<String> jobNames, SchedulingContext context, StageConfig stageConfig, Clock clock, String latestMd5) {
        Stage newStage = stage.createClone();
        newStage.prepareForRerunOf(context, latestMd5);
        createRerunJobs(newStage, jobNames, context, stageConfig, clock);
        return newStage;
    }

    public JobInstances createJobInstance(CaseInsensitiveString stageName, JobConfig jobConfig, SchedulingContext context, Clock clock, JobType.JobNameGenerator jobNameGenerator) {
        JobInstances instances = new JobInstances();
        createJobType(jobConfig.isRunOnAllAgents()).createJobInstances(instances, context, jobConfig, CaseInsensitiveString.str(stageName), jobNameGenerator, clock, this);
        return instances;
    }

    private JobInstances createJobInstances(StageConfig stageConfig, SchedulingContext context, Clock clock) {
        JobInstances instances = new JobInstances();
        for (JobConfig jobConfig : stageConfig.getJobs()) {
            RunOnAllAgents.CounterBasedJobNameGenerator nameGenerator = new RunOnAllAgents.CounterBasedJobNameGenerator(CaseInsensitiveString.str(jobConfig.name()));
            JobInstances configInstances = createJobInstance(stageConfig.name(), jobConfig, context, clock, nameGenerator);
            instances.addAll(configInstances);
        }
        return instances;
    }

    private void createRerunJobs(Stage newStage, List<String> jobNames, SchedulingContext context, StageConfig stageConfig, Clock clock) {
        for (String jobName : jobNames) {
            JobInstances jobInstances = newStage.getJobInstances();
            JobInstance oldJob = jobInstances.getByName(jobName);
            jobInstances.remove(oldJob);
            createJobType(oldJob.isRunOnAllAgents()).createRerunInstances(oldJob, jobInstances, context, stageConfig, clock, this);
        }
    }

    private JobType createJobType(boolean runOnAllAgents) {
        return runOnAllAgents ? new RunOnAllAgents() : new  SingleJobInstance();
    }

    public void reallyCreateJobInstance(JobConfig config, JobInstances jobs, String uuid, String jobName, boolean runOnAllAgents, SchedulingContext context, final Clock clock) {
        JobInstance instance = new JobInstance(jobName, clock);
        instance.setPlan(createJobPlan(config, context));
        instance.setAgentUuid(uuid);
        instance.setRunOnAllAgents(runOnAllAgents);
        jobs.add(instance);
    }

    public JobPlan createJobPlan(JobConfig config, SchedulingContext context) {
        JobIdentifier identifier = new JobIdentifier();
        return new DefaultJobPlan(config.resources(), config.artifactPlans(), config.getProperties(), -1, identifier, null, context.overrideEnvironmentVariables(config.getVariables()).getEnvironmentVariablesConfig(), new EnvironmentVariablesConfig());
    }
}