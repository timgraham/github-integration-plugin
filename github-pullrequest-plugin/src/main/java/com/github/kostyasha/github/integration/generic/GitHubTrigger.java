package com.github.kostyasha.github.integration.generic;

import antlr.ANTLRException;
import com.cloudbees.jenkins.GitHubRepositoryName;
import com.coravy.hudson.plugins.github.GithubProjectProperty;
import com.github.kostyasha.github.integration.generic.repoprovider.GitHubPluginRepoProvider;
import com.google.common.annotations.Beta;
import hudson.model.Action;
import hudson.model.Job;
import hudson.triggers.Trigger;
import org.jenkinsci.plugins.github.pullrequest.GitHubPRTriggerMode;
import org.kohsuke.github.GHRepository;
import org.kohsuke.stapler.DataBoundSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.codehaus.groovy.runtime.InvokerHelper.asList;
import static org.jenkinsci.plugins.github.pullrequest.GitHubPRTriggerMode.CRON;
import static org.jenkinsci.plugins.github.pullrequest.utils.ObjectsUtil.isNull;
import static org.jenkinsci.plugins.github.pullrequest.utils.ObjectsUtil.nonNull;

/**
 * @author Kanstantsin Shautsou
 */
public abstract class GitHubTrigger<T extends GitHubTrigger<T>> extends Trigger<Job<?, ?>> {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubTrigger.class);

    @CheckForNull
    private GitHubPRTriggerMode triggerMode = CRON;

    /**
     * Cancel queued runs for specific kind (i.e. PR by number, branch by name).
     */
    protected boolean cancelQueued = false;
    private boolean abortRunning = false;
    protected boolean skipFirstRun = false;

    @Beta
    private List<GitHubRepoProvider> repoProviders = asList(new GitHubPluginRepoProvider()); // default
    private transient GitHubRepoProvider repoProvider = null;

    // for performance
    private transient GitHubRepositoryName repoName;
    protected GitHubTrigger(String cronTabSpec) throws ANTLRException {
        super(cronTabSpec);
    }

    public GitHubTrigger(String spec, GitHubPRTriggerMode triggerMode) throws ANTLRException {
        super(spec);
        this.triggerMode = triggerMode;
    }

    public GitHubPRTriggerMode getTriggerMode() {
        return triggerMode;
    }

    public void setTriggerMode(GitHubPRTriggerMode triggerMode) {
        this.triggerMode = triggerMode;
    }

    public boolean isCancelQueued() {
        return cancelQueued;
    }

    @DataBoundSetter
    public void setCancelQueued(boolean cancelQueued) {
        this.cancelQueued = cancelQueued;
    }

    public boolean isAbortRunning() {
        return abortRunning;
    }

    @DataBoundSetter
    public void setAbortRunning(boolean abortRunning) {
        this.abortRunning = abortRunning;
    }

    public boolean isSkipFirstRun() {
        return skipFirstRun;
    }

    @DataBoundSetter
    public void setSkipFirstRun(boolean skipFirstRun) {
        this.skipFirstRun = skipFirstRun;
    }

    public GitHubRepositoryName getRepoName() {
        return repoName;
    }

    public void setRepoName(GitHubRepositoryName repoName) {
        this.repoName = repoName;
    }
    @Beta
    @Nonnull
    public List<GitHubRepoProvider> getRepoProviders() {
        if (isNull(repoProviders)) {
            repoProviders = asList(new GitHubPluginRepoProvider()); // old default behaviour
        }
        return repoProviders;
    }

    @Beta
    @DataBoundSetter
    public void setRepoProviders(List<GitHubRepoProvider> repoProviders) {
        this.repoProviders = repoProviders;
    }

    @Beta
    public void setRepoProvider(@Nonnull GitHubRepoProvider prov) {
        repoProviders = asList(prov);
    }

    @Beta
    public GitHubRepoProvider getRepoProvider() {
        if (isNull(repoProvider)) {
            boolean failed = false;
            for (GitHubRepoProvider prov : getRepoProviders()) {
                try {
                    prov.getGHRepository(this);
                    repoProvider = prov;
                } catch (Exception ignore) {
                    failed = true;
                }
            }
            if (failed) {
                LOG.error("Can't find repo provider for GitHubBranchTrigger job: {}", getJob().getFullName());
            }
        }
        checkState(nonNull(repoProvider), "Can't get repo provider for GH repo %s for %s", job.getName());
        return repoProvider;
    }

    @Nonnull
    public GHRepository getRemoteRepository() throws IOException {
        GHRepository remoteRepository = getRepoProvider().getGHRepository(this);
        checkState(nonNull(remoteRepository), "Can't get remote GH repo for %s", job.getName());
        return remoteRepository;
    }

    @Override
    public void stop() {
        //TODO clean hooks?
        if (nonNull(job)) {
            LOG.info("Stopping '{}' for project '{}'", getDescriptor().getDisplayName(), job.getFullName());
        }
        super.stop();
    }

    public abstract String getFinishMsg();

    public abstract GitHubPollingLogAction getPollingLogAction();

    @Nonnull
    @Override
    public Collection<? extends Action> getProjectActions() {
        if (isNull(getPollingLogAction())) {
            return Collections.emptyList();
        }
        return Collections.singleton(getPollingLogAction());
    }

    @CheckForNull
    public Job<?, ?> getJob() {
        return job;
    }

    public GitHubRepositoryName getRepoFullName() {
        return getRepoFullName(getJob());
    }

    public GitHubRepositoryName getRepoFullName(Job<?, ?> job) {
        if (isNull(repoName)) {
            checkNotNull(job, "job object is null, race condition?");
            GithubProjectProperty ghpp = job.getProperty(GithubProjectProperty.class);

            checkNotNull(ghpp, "GitHub project property is not defined. Can't setup GitHub trigger for job %s",
                    job.getName());
            checkNotNull(ghpp.getProjectUrl(), "A GitHub project url is required");

            GitHubRepositoryName repo = GitHubRepositoryName.create(ghpp.getProjectUrl().baseUrl());

            checkNotNull(repo, "Invalid GitHub project url: %s", ghpp.getProjectUrl().baseUrl());

            setRepoName(repo);
        }

        return repoName;
    }

    public void trySave() {
        try {
            job.save();
        } catch (IOException e) {
            LOG.error("Error while saving job to file", e);
        }
    }

    protected void saveIfSkipFirstRun() {
        if (skipFirstRun) {
            LOG.info("Skipping first run for {}", job.getFullName());
            skipFirstRun = false;
            trySave();
        }
    }
}
