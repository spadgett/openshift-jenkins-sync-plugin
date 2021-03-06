/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import hudson.model.Job;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.SCM;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.api.model.BuildStrategy;
import io.fabric8.openshift.api.model.GitBuildSource;
import io.fabric8.openshift.api.model.JenkinsPipelineBuildStrategy;
import jenkins.model.Jenkins;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class BuildConfigToJobMapper {
  public static final String JENKINS_PIPELINE_BUILD_STRATEGY = "JenkinsPipeline";
  public static final String DEFAULT_JENKINS_FILEPATH = "Jenkinsfile";
  private static final Logger LOGGER = Logger.getLogger(BuildConfigToJobMapper.class.getName());

  public static Job<WorkflowJob, WorkflowRun> mapBuildConfigToJob(BuildConfig bc, String defaultNamespace) {
    if (!OpenShiftUtils.isJenkinsBuildConfig(bc)) {
      return null;
    }

    WorkflowJob job = new WorkflowJob(Jenkins.getInstance(), OpenShiftUtils.jenkinsJobName(bc, defaultNamespace));

    BuildConfigSpec spec = bc.getSpec();
    BuildSource source = null;
    String jenkinsfile = null;
    String jenkinsfilePath = null;
    if (spec != null) {
      source = spec.getSource();
      BuildStrategy strategy = spec.getStrategy();
      if (strategy != null) {
        JenkinsPipelineBuildStrategy jenkinsPipelineStrategy = strategy.getJenkinsPipelineStrategy();
        if (jenkinsPipelineStrategy != null) {
          jenkinsfile = jenkinsPipelineStrategy.getJenkinsfile();
          jenkinsfilePath = jenkinsPipelineStrategy.getJenkinsfilePath();
        }
      }
    }
    if (jenkinsfile == null) {
      // Is this a Jenkinsfile from Git SCM?
      if (source != null &&
        source.getGit() != null &&
        source.getGit().getUri() != null) {
        if (jenkinsfilePath == null) {
          jenkinsfilePath = DEFAULT_JENKINS_FILEPATH;
        }
        if (!isEmpty(source.getContextDir())) {
          jenkinsfilePath = new File(source.getContextDir(), jenkinsfilePath).getPath();
        }
        GitBuildSource gitSource = source.getGit();
        String branchRef = gitSource.getRef();
        List<BranchSpec> branchSpecs = Collections.emptyList();
        if (isNotBlank(branchRef)) {
          branchSpecs = Collections.singletonList(new BranchSpec(branchRef));
        }
        GitSCM scm = new GitSCM(
          Collections.singletonList(new UserRemoteConfig(gitSource.getUri(), null, null, null)),
          branchSpecs,
          false,
          Collections.<SubmoduleConfig>emptyList(),
          null,
          null,
          null
          );
        job.setDefinition(new CpsScmFlowDefinition(scm, jenkinsfilePath));
      } else {
        LOGGER.warning("BuildConfig does not contain source repository information - cannot map BuildConfig to Jenkins job");
        return null;
      }
    } else {
      job.setDefinition(new CpsFlowDefinition(jenkinsfile));
    }

    job.addTrigger(new BuildTrigger());

    return job;
  }

  /**
   * Updates the {@link BuildConfig} if the Jenkins {@link WorkflowJob} changes
   *
   * @param job the job thats been updated via Jenkins
   * @param buildConfig the OpenShift BuildConfig to update
   * @return true if the BuildConfig was changed
   */
  public static boolean updateBuildConfigFromJob(WorkflowJob job, BuildConfig buildConfig) {
    NamespaceName namespaceName = NamespaceName.create(buildConfig);
    JenkinsPipelineBuildStrategy jenkinsPipelineStrategy = null;
    BuildConfigSpec spec = buildConfig.getSpec();
    if (spec != null) {
      BuildStrategy strategy = spec.getStrategy();
      if (strategy != null) {
        jenkinsPipelineStrategy = strategy.getJenkinsPipelineStrategy();
      }
    }

    if (jenkinsPipelineStrategy == null) {
      LOGGER.warning("No jenkinsPipelineStrategy available in the BuildConfig " + namespaceName);
      return false;
    }

    FlowDefinition definition = job.getDefinition();
    if (definition instanceof CpsScmFlowDefinition) {
      CpsScmFlowDefinition cpsScmFlowDefinition = (CpsScmFlowDefinition) definition;
      String scriptPath = cpsScmFlowDefinition.getScriptPath();
      if (scriptPath != null && scriptPath.trim().length() > 0) {
        jenkinsPipelineStrategy.setJenkinsfilePath(scriptPath);

        SCM scm = cpsScmFlowDefinition.getScm();
        if (scm instanceof GitSCM) {
          GitSCM gitSCM = (GitSCM) scm;
          List<RemoteConfig> repositories = gitSCM.getRepositories();
          if (repositories != null && repositories.size() > 0) {
            RemoteConfig remoteConfig = repositories.get(0);
            List<URIish> urIs = remoteConfig.getURIs();
            if (urIs != null && urIs.size() > 0) {
              URIish urIish = urIs.get(0);
              String gitUrl = urIish.toString();
              if (gitUrl != null && gitUrl.length() > 0) {
                List<BranchSpec> branches = gitSCM.getBranches();
                if (branches != null && branches.size() > 0) {
                  BranchSpec branchSpec = branches.get(0);
                  String branch = branchSpec.getName();
                  while (branch.startsWith("*") || branch.startsWith("/")) {
                    branch = branch.substring(1);
                  }
                }
                String ref = null;
                OpenShiftUtils.updateGitSourceUrl(buildConfig, gitUrl, ref);
              }
            }
          }
        }
        return true;
      }
      return false;
    }

    if (definition instanceof CpsFlowDefinition) {
      CpsFlowDefinition cpsFlowDefinition = (CpsFlowDefinition) definition;
      String jenkinsfile = cpsFlowDefinition.getScript();
      if (jenkinsfile != null && jenkinsfile.trim().length() > 0) {
        jenkinsPipelineStrategy.setJenkinsfile(jenkinsfile);
        return true;
      }
      return false;
    }

    LOGGER.warning("Cannot update BuildConfig " + namespaceName + " as the definition is of class " + (definition == null ? "null" : definition.getClass().getName()));
    return false;
  }
}
