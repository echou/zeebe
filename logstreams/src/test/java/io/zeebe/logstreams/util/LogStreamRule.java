/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.logstreams.util;

import static io.zeebe.logstreams.impl.LogBlockIndexWriter.LOG;
import static io.zeebe.logstreams.impl.service.LogStreamServiceNames.distributedLogPartitionServiceName;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.zeebe.distributedlog.DistributedLogstreamService;
import io.zeebe.distributedlog.impl.DefaultDistributedLogstreamService;
import io.zeebe.distributedlog.impl.DistributedLogstreamPartition;
import io.zeebe.distributedlog.impl.DistributedLogstreamServiceConfig;
import io.zeebe.logstreams.LogStreams;
import io.zeebe.logstreams.impl.LogStreamBuilder;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.state.StateStorage;
import io.zeebe.servicecontainer.ServiceContainer;
import io.zeebe.servicecontainer.impl.ServiceContainerImpl;
import io.zeebe.util.sched.ActorScheduler;
import io.zeebe.util.sched.clock.ControlledActorClock;
import io.zeebe.util.sched.testing.ActorSchedulerRule;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.stubbing.Answer;

public class LogStreamRule extends ExternalResource {
  public static final String DEFAULT_NAME = "test-logstream";

  private final String name;
  private final TemporaryFolder temporaryFolder;
  private File blockIndexDirectory;
  private File snapshotDirectory;

  private ActorScheduler actorScheduler;
  private ServiceContainer serviceContainer;
  private LogStream logStream;
  private DistributedLogstreamService distributedLogImpl;

  private final ControlledActorClock clock = new ControlledActorClock();

  private final Consumer<LogStreamBuilder> streamBuilder;

  private LogStreamBuilder builder;
  private StateStorage stateStorage;

  public LogStreamRule(final TemporaryFolder temporaryFolder) {
    this(DEFAULT_NAME, temporaryFolder);
  }

  public LogStreamRule(
      final TemporaryFolder temporaryFolder, final Consumer<LogStreamBuilder> streamBuilder) {
    this(DEFAULT_NAME, temporaryFolder, streamBuilder);
  }

  public LogStreamRule(final String name, final TemporaryFolder temporaryFolder) {
    this(name, temporaryFolder, b -> {});
  }

  public LogStreamRule(
      final String name,
      final TemporaryFolder temporaryFolder,
      final Consumer<LogStreamBuilder> streamBuilder) {
    this.name = name;
    this.temporaryFolder = temporaryFolder;
    this.streamBuilder = streamBuilder;
  }

  @Override
  protected void before() {
    actorScheduler = new ActorSchedulerRule(clock).get();
    actorScheduler.start();

    serviceContainer = new ServiceContainerImpl(actorScheduler);
    serviceContainer.start();

    try {
      this.blockIndexDirectory = temporaryFolder.newFolder("index", "runtime");
      this.snapshotDirectory = temporaryFolder.newFolder("index", "snapshots");
    } catch (IOException e) {
      LOG.error("Couldn't create blockIndex/snapshots directory", e);
    }

    stateStorage = new StateStorage(blockIndexDirectory, snapshotDirectory);
    builder =
        LogStreams.createFsLogStream(0)
            .logDirectory(temporaryFolder.getRoot().getAbsolutePath())
            .serviceContainer(serviceContainer)
            .indexStateStorage(stateStorage);

    // apply additional configs
    streamBuilder.accept(builder);

    openLogStream();
  }

  @Override
  protected void after() {
    if (logStream != null) {
      logStream.close();
    }

    try {
      serviceContainer.close(5, TimeUnit.SECONDS);
    } catch (final TimeoutException | ExecutionException | InterruptedException e) {
      e.printStackTrace();
    }

    actorScheduler.stop();
  }

  private void openDistributedLog() {
    final DistributedLogstreamPartition mockDistLog = mock(DistributedLogstreamPartition.class);
    distributedLogImpl =
        new DefaultDistributedLogstreamService(new DistributedLogstreamServiceConfig());

    final String nodeId = "0";
    try {
      FieldSetter.setField(
          distributedLogImpl,
          DefaultDistributedLogstreamService.class.getDeclaredField("logStream"),
          logStream);

      FieldSetter.setField(
          distributedLogImpl,
          DefaultDistributedLogstreamService.class.getDeclaredField("logStorage"),
          logStream.getLogStorage());

      FieldSetter.setField(
          distributedLogImpl,
          DefaultDistributedLogstreamService.class.getDeclaredField("currentLeader"),
          nodeId);

    } catch (NoSuchFieldException e) {
      e.printStackTrace();
    }

    doAnswer(
            (Answer<Long>)
                invocation -> {
                  final Object[] arguments = invocation.getArguments();
                  if (arguments != null
                      && arguments.length > 1
                      && arguments[0] != null
                      && arguments[1] != null) {
                    final byte[] bytes = (byte[]) arguments[0];
                    final long pos = (long) arguments[1];
                    return distributedLogImpl.append(nodeId, pos, bytes);
                  }
                  return -1L;
                })
        .when(mockDistLog)
        .append(any(), anyLong());

    serviceContainer
        .createService(distributedLogPartitionServiceName(builder.getLogName()), () -> mockDistLog)
        .install()
        .join();
  }

  private void closeDistributedLog() {
    serviceContainer.removeService(distributedLogPartitionServiceName(builder.getLogName()));
  }

  public void closeLogStream() {
    logStream.close();
    logStream = null;
    closeDistributedLog();
    distributedLogImpl = null;
  }

  public void openLogStream() {
    logStream = builder.build().join();
    openDistributedLog();
    logStream.openAppender().join();
  }

  public LogStream getLogStream() {
    return logStream;
  }

  public void setCommitPosition(final long position) {
    logStream.setCommitPosition(position);
  }

  public long getCommitPosition() {
    return logStream.getCommitPosition();
  }

  public ControlledActorClock getClock() {
    return clock;
  }

  public ActorScheduler getActorScheduler() {
    return actorScheduler;
  }

  public ServiceContainer getServiceContainer() {
    return serviceContainer;
  }

  public File getSnapshotDirectory() {
    return snapshotDirectory;
  }

  public StateStorage getStateStorage() {
    return stateStorage;
  }
}
