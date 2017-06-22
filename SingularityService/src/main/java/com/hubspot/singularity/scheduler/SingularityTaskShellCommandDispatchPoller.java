package com.hubspot.singularity.scheduler;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import org.apache.mesos.v1.Protos.AgentID;
import org.apache.mesos.v1.Protos.ExecutorID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskShellCommandRequest;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.data.transcoders.Transcoder;
import com.hubspot.singularity.mesos.SingularityMesosScheduler;

@Singleton
public class SingularityTaskShellCommandDispatchPoller extends SingularityLeaderOnlyPoller {

  private static final Logger LOG = LoggerFactory.getLogger(SingularityTaskShellCommandDispatchPoller.class);

  private final TaskManager taskManager;
  private final SingularityMesosScheduler scheduler;
  private final Transcoder<SingularityTaskShellCommandRequest> transcoder;

  @Inject
  SingularityTaskShellCommandDispatchPoller(TaskManager taskManager, SingularityConfiguration configuration, SingularityMesosScheduler scheduler, Transcoder<SingularityTaskShellCommandRequest> transcoder) {
    super(configuration.getCheckDeploysEverySeconds(), TimeUnit.SECONDS);

    this.taskManager = taskManager;
    this.scheduler = scheduler;
    this.transcoder = transcoder;
  }

  @Override
  public void runActionOnPoll() {
    final long start = System.currentTimeMillis();

    final List<SingularityTaskShellCommandRequest> shellRequests = taskManager.getAllQueuedTaskShellCommandRequests();

    if (!scheduler.isRunning()) {
      LOG.warn("Unable to process shell requests because scheduler driver isn't present ({} tasks waiting)", shellRequests.size());
      return;
    }

    if (shellRequests.isEmpty()) {
      LOG.trace("No shell requests to send.");
      return;
    }

    for (SingularityTaskShellCommandRequest shellRequest : shellRequests) {
      Optional<SingularityTask> task = taskManager.getTask(shellRequest.getTaskId());

      if (!task.isPresent() || !taskManager.isActiveTask(shellRequest.getTaskId().getId())) {
        LOG.info("Skipping shell request {} because {} didn't exist or isn't active", shellRequest, shellRequest.getTaskId());
        continue;
      }

      final ExecutorID executorId = task.get().getMesosTask().getExecutor().getExecutorId();
      final AgentID slaveId = task.get().getMesosTask().getAgentId();
      final byte[] bytes = transcoder.toBytes(shellRequest);

      scheduler.frameworkMessage(executorId, slaveId, bytes);

      LOG.info("Sent {} ({} bytes) to {} on {}", shellRequest, bytes.length, executorId, slaveId);

      taskManager.saveTaskShellCommandRequestToTask(shellRequest);
      taskManager.deleteTaskShellCommandRequestFromQueue(shellRequest);
    }

    LOG.info("Sent {} shell requests to executors in {}", shellRequests.size(), JavaUtils.duration(start));
  }

}
