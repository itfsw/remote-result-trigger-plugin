package io.jenkins.plugins.remote.result.trigger;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.Run;
import io.jenkins.plugins.remote.result.trigger.model.JobResultInfo;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author heweisc@dingtalk.com
 */
public class ReadRemoteResultStep extends Step {
    private String uid;

    @DataBoundConstructor
    public ReadRemoteResultStep() {
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new ReadRemoteResultStepExecution(this, context);
    }

    public String getUid() {
        return uid;
    }

    @DataBoundSetter
    public void setUid(String uid) {
        this.uid = uid;
    }

    @Extension
    public static class ReadRemoteResultStepDescriptor extends StepDescriptor {

        /**
         * Enumerates any kinds of context the {@link StepExecution} will treat as mandatory.
         * When {@link StepContext#get} is called, the return value may be null in general;
         * if your step cannot trivially handle a null value of a given kind, list that type here.
         * The Pipeline execution engine will then signal a user error before even starting your step if called in an inappropriate context.
         * For example, a step requesting a Launcher may only be run inside a {@code node {…}} block.
         */
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(Run.class);
        }

        /**
         * Return a short string that is a valid identifier for programming languages.
         * Follow the pattern {@code [a-z][A-Za-z0-9_]*}.
         * Step will be referenced by this name when used in a programming language.
         */
        @Override
        public String getFunctionName() {
            return "readRemoteResult";
        }
    }


    public static class ReadRemoteResultStepExecution extends SynchronousNonBlockingStepExecution<Map<?, ?>> {
        private static final long serialVersionUID = 4436899316471397907L;
        private final transient ReadRemoteResultStep step;

        public ReadRemoteResultStepExecution(@NonNull ReadRemoteResultStep step, @NonNull StepContext context) {
            super(context);
            this.step = step;
        }

        /**
         * Meat of the execution.
         * <p>
         * When this method returns, a step execution is over.
         */
        @Override
        protected Map<?, ?> run() throws Exception {
            RemoteBuildResultTriggerScheduledAction action = getTriggerAction();
            if (action != null && !action.getJobResultInfos().isEmpty()) {
                // 读取任务信息
                JobResultInfo jobInfo;
                if (StringUtils.isEmpty(step.getUid())) {
                    jobInfo = action.getJobResultInfos().get(0);
                } else {
                    jobInfo = action.getJobResultInfos().stream()
                            .filter(item -> step.getUid().equals(item.getUid()))
                            .findFirst().orElse(null);
                }
                if (jobInfo != null) {
                    return jobInfo.getResultJson();
                }
            }
            return new HashMap<>();
        }

        @Nullable
        @SuppressWarnings("rawtypes")
        private RemoteBuildResultTriggerScheduledAction getTriggerAction() throws IOException, InterruptedException {
            Run run = getContext().get(Run.class);
            if (run != null) {
                return run.getAction(RemoteBuildResultTriggerScheduledAction.class);
            }
            return null;
        }
    }
}
