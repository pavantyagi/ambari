/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.actionmanager;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.inject.persist.Transactional;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.ExecuteActionRequest;
import org.apache.ambari.server.orm.dao.*;
import org.apache.ambari.server.orm.entities.*;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Singleton
public class ActionDBAccessorImpl implements ActionDBAccessor {
  private static final Logger LOG = LoggerFactory.getLogger(ActionDBAccessorImpl.class);
  private long requestId;
  @Inject
  ClusterDAO clusterDAO;
  @Inject
  HostDAO hostDAO;
  @Inject
  RequestDAO requestDAO;
  @Inject
  StageDAO stageDAO;
  @Inject
  HostRoleCommandDAO hostRoleCommandDAO;
  @Inject
  ExecutionCommandDAO executionCommandDAO;
  @Inject
  RoleSuccessCriteriaDAO roleSuccessCriteriaDAO;
  @Inject
  StageFactory stageFactory;
  @Inject
  RequestFactory requestFactory;
  @Inject
  HostRoleCommandFactory hostRoleCommandFactory;
  @Inject
  Clusters clusters;



  private Cache<Long, HostRoleCommand> hostRoleCommandCache;
  private long cacheLimit; //may be exceeded to store tasks from one request

  @Inject
  public ActionDBAccessorImpl(@Named("executionCommandCacheSize") long cacheLimit) {

    this.cacheLimit = cacheLimit;
    hostRoleCommandCache = CacheBuilder.newBuilder().
        expireAfterAccess(5, TimeUnit.MINUTES).
        build();

  }

  @Inject
  void init() {
    requestId = stageDAO.getLastRequestId();
  }

  /* (non-Javadoc)
   * @see org.apache.ambari.server.actionmanager.ActionDBAccessor#getStage(java.lang.String)
   */
  @Override
  public Stage getStage(String actionId) {
    StageEntity stageEntity = stageDAO.findByActionId(actionId);
    return stageFactory.createExisting(stageEntity);
  }

  /* (non-Javadoc)
   * @see org.apache.ambari.server.actionmanager.ActionDBAccessor#getAllStages(java.lang.String)
   */
  @Override
  public List<Stage> getAllStages(long requestId) {
    List<Stage> stages = new ArrayList<Stage>();
    for (StageEntity stageEntity : stageDAO.findByRequestId(requestId)) {
      stages.add(stageFactory.createExisting(stageEntity));
    }
    return stages;
  }

  /* (non-Javadoc)
   * @see org.apache.ambari.server.actionmanager.ActionDBAccessor#abortOperation(long)
   */
  @Override
  @Transactional
  public void abortOperation(long requestId) {
    long now = System.currentTimeMillis();

    //mark request as ended
    RequestEntity requestEntity = requestDAO.findByPK(requestId);
    if (requestEntity != null && requestEntity.getEndTime() == -1L) {
      requestEntity.setEndTime(now);
      requestDAO.merge(requestEntity);
    }

    List<HostRoleCommandEntity> commands =
        hostRoleCommandDAO.findByRequest(requestId);
    for (HostRoleCommandEntity command : commands) {
      if (command.getStatus() == HostRoleStatus.QUEUED ||
          command.getStatus() == HostRoleStatus.IN_PROGRESS ||
          command.getStatus() == HostRoleStatus.PENDING) {
        command.setStatus(HostRoleStatus.ABORTED);
        command.setEndTime(now);
        hostRoleCommandDAO.merge(command);
        LOG.info("Aborting command. Hostname " + command.getHostName()
            + " role " + command.getRole()
            + " requestId " + command.getRequestId()
            + " taskId " + command.getTaskId()
            + " stageId " + command.getStageId());
      }
    }
  }

  /* (non-Javadoc)
   * @see org.apache.ambari.server.actionmanager.ActionDBAccessor#timeoutHostRole(long, long, org.apache.ambari.server.Role)
   */
  @Override
  @Transactional
  public void timeoutHostRole(String host, long requestId, long stageId,
                              String role) {
    long now = System.currentTimeMillis();
    List<HostRoleCommandEntity> commands =
        hostRoleCommandDAO.findByHostRole(host, requestId, stageId, role);
    for (HostRoleCommandEntity command : commands) {
      command.setStatus(HostRoleStatus.TIMEDOUT);
      command.setEndTime(now);
      hostRoleCommandDAO.merge(command);
    }
  }

  /* (non-Javadoc)
   * @see org.apache.ambari.server.actionmanager.ActionDBAccessor#getPendingStages()
   */
  @Override
  public List<Stage> getStagesInProgress() {
    List<Stage> stages = new ArrayList<Stage>();
    List<HostRoleStatus> statuses =
        Arrays.asList(HostRoleStatus.QUEUED, HostRoleStatus.IN_PROGRESS,
            HostRoleStatus.PENDING);
    for (StageEntity stageEntity : stageDAO.findByCommandStatuses(statuses)) {
      stages.add(stageFactory.createExisting(stageEntity));
    }
    return stages;
  }

  @Override
  @Transactional
  public void persistActions(Request request) {

    RequestEntity requestEntity = request.constructNewPersistenceEntity();

    ClusterEntity clusterEntity = clusterDAO.findById(request.getClusterId());
    if (clusterEntity == null) {
      throw new RuntimeException(String.format("Cluster with id=%s not found", request.getClusterId()));
    }
    requestEntity.setCluster(clusterEntity);
    requestDAO.create(requestEntity);

    //TODO wire request to cluster
    List<StageEntity> stageEntities = new ArrayList<StageEntity>(request.getStages().size());

    for (Stage stage : request.getStages()) {
      StageEntity stageEntity = stage.constructNewPersistenceEntity();
      stageEntities.add(stageEntity);
      stageEntity.setCluster(clusterEntity);
      //TODO refactor to reduce merges
      stageEntity.setRequest(requestEntity);
      stageDAO.create(stageEntity);

      List<HostRoleCommand> orderedHostRoleCommands = stage.getOrderedHostRoleCommands();
      List<HostRoleCommandEntity> hostRoleCommandEntities = new ArrayList<HostRoleCommandEntity>();

      for (HostRoleCommand hostRoleCommand : orderedHostRoleCommands) {
        HostRoleCommandEntity hostRoleCommandEntity = hostRoleCommand.constructNewPersistenceEntity();
        hostRoleCommandEntities.add(hostRoleCommandEntity);
        hostRoleCommandEntity.setStage(stageEntity);

        HostEntity hostEntity = hostDAO.findByName(hostRoleCommandEntity.getHostName());
        if (hostEntity == null) {
          LOG.error("Host {} doesn't exists in database" + hostRoleCommandEntity.getHostName());
          throw new RuntimeException("Host '" + hostRoleCommandEntity.getHostName() + "' doesn't exists in database");
        }
        hostRoleCommandEntity.setHost(hostEntity);
        hostRoleCommandDAO.create(hostRoleCommandEntity);

        assert hostRoleCommandEntity.getTaskId() != null;

        hostRoleCommand.setTaskId(hostRoleCommandEntity.getTaskId());
        ExecutionCommandEntity executionCommandEntity = hostRoleCommand.constructExecutionCommandEntity();
        executionCommandEntity.setHostRoleCommand(hostRoleCommandEntity);

        executionCommandEntity.setTaskId(hostRoleCommandEntity.getTaskId());
        hostRoleCommandEntity.setExecutionCommand(executionCommandEntity);

        executionCommandDAO.create(hostRoleCommandEntity.getExecutionCommand());
        hostRoleCommandDAO.merge(hostRoleCommandEntity);
        hostDAO.merge(hostEntity);
      }

      for (RoleSuccessCriteriaEntity roleSuccessCriteriaEntity : stageEntity.getRoleSuccessCriterias()) {
        roleSuccessCriteriaDAO.create(roleSuccessCriteriaEntity);
      }

      stageDAO.create(stageEntity);
    }
    requestEntity.setStages(stageEntities);
    requestDAO.merge(requestEntity);
//    requestDAO.create(requestEntity);
  }

  @Override
  @Transactional
  public void startRequest(long requestId) {
    RequestEntity requestEntity = requestDAO.findByPK(requestId);
    if (requestEntity != null && requestEntity.getStartTime() == -1L) {
      requestEntity.setStartTime(System.currentTimeMillis());
    }
    requestDAO.merge(requestEntity);
  }

  @Override
  @Transactional
  public void endRequest(long requestId) {
    RequestEntity requestEntity = requestDAO.findByPK(requestId);
    if (requestEntity != null && requestEntity.getEndTime() == -1L) {
      requestEntity.setEndTime(System.currentTimeMillis());
    }
    requestDAO.merge(requestEntity);
  }

  @Override
  @Transactional
  public void updateHostRoleState(String hostname, long requestId,
                                  long stageId, String role, CommandReport report) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Update HostRoleState: "
          + "HostName " + hostname + " requestId " + requestId + " stageId "
          + stageId + " role " + role + " report " + report);
    }
    long now = System.currentTimeMillis();
    List<HostRoleCommandEntity> commands = hostRoleCommandDAO.findByHostRole(
        hostname, requestId, stageId, role);
    for (HostRoleCommandEntity command : commands) {
      command.setStatus(HostRoleStatus.valueOf(report.getStatus()));
      command.setStdOut(report.getStdOut().getBytes());
      command.setStdError(report.getStdErr().getBytes());
      command.setStructuredOut(report.getStructuredOut() == null ? null :
        report.getStructuredOut().getBytes());           // ===================================
      if (command.getStatus() == HostRoleStatus.COMPLETED ||
          command.getStatus() == HostRoleStatus.ABORTED ||
          command.getStatus() == HostRoleStatus.FAILED) {
        command.setEndTime(now);
      }
      command.setExitcode(report.getExitCode());
      hostRoleCommandDAO.merge(command);
    }
  }

  @Override
  public void abortHostRole(String host, long requestId, long stageId, String role) {
    CommandReport report = new CommandReport();
    report.setExitCode(999);
    report.setStdErr("Host Role in invalid state");
    report.setStdOut("");
    report.setStatus("ABORTED");
    updateHostRoleState(host, requestId, stageId, role, report);
  }

  @Override
  public long getLastPersistedRequestIdWhenInitialized() {
    return requestId;
  }

  @Override
  @Transactional
  public void hostRoleScheduled(Stage s, String hostname, String roleStr) {
    HostRoleCommand hostRoleCommand = s.getHostRoleCommand(hostname, roleStr);
    HostRoleCommandEntity entity = hostRoleCommandDAO.findByPK(hostRoleCommand.getTaskId());
    if (entity != null) {
      entity.setStartTime(hostRoleCommand.getStartTime());
      entity.setLastAttemptTime(hostRoleCommand.getLastAttemptTime());
      entity.setStatus(hostRoleCommand.getStatus());
      entity.setAttemptCount(hostRoleCommand.getAttemptCount());
      hostRoleCommandDAO.merge(entity);
    } else {
      throw new RuntimeException("HostRoleCommand is not persisted, cannot update:\n" + hostRoleCommand);
    }
  }

  @Override
  public List<HostRoleCommand> getRequestTasks(long requestId) {
    List<HostRoleCommand> tasks = new ArrayList<HostRoleCommand>();
    return getTasks(
        hostRoleCommandDAO.findTaskIdsByRequest(requestId)
    );
  }

  @Override
  public List<HostRoleCommand> getAllTasksByRequestIds(Collection<Long> requestIds) {
    if (requestIds.isEmpty()) {
      return Collections.emptyList();
    }

    return getTasks(
        hostRoleCommandDAO.findTaskIdsByRequestIds(requestIds)
    );
  }

  @Override
  public List<HostRoleCommand> getTasksByRequestAndTaskIds(Collection<Long> requestIds, Collection<Long> taskIds) {
    if (!requestIds.isEmpty() && !taskIds.isEmpty()) {
      return getTasks(hostRoleCommandDAO.findTaskIdsByRequestAndTaskIds(requestIds, taskIds));

    } else if (requestIds.isEmpty()) {
      return getTasks(taskIds);
    } else if (taskIds.isEmpty()) {
      return getAllTasksByRequestIds(requestIds);
    } else {
      return Collections.emptyList();
    }
  }

  @Override
  public List<HostRoleCommand> getTasks(Collection<Long> taskIds) {
    if (taskIds.isEmpty()) {
      return Collections.emptyList();
    }

    List<HostRoleCommand> commands = new ArrayList<HostRoleCommand>();

    Map<Long, HostRoleCommand> cached = hostRoleCommandCache.getAllPresent(taskIds);
    commands.addAll(cached.values());

    List<Long> absent = new ArrayList<Long>();
    absent.addAll(taskIds);
    absent.removeAll(cached.keySet());

    if (!absent.isEmpty()) {
      boolean allowStore = hostRoleCommandCache.size() <= cacheLimit;

      for (HostRoleCommandEntity commandEntity : hostRoleCommandDAO.findByPKs(absent)) {
        HostRoleCommand hostRoleCommand = hostRoleCommandFactory.createExisting(commandEntity);
        commands.add(hostRoleCommand);
        if (allowStore) {
          switch (hostRoleCommand.getStatus()) {
            case ABORTED:
            case COMPLETED:
            case TIMEDOUT:
            case FAILED:
              hostRoleCommandCache.put(hostRoleCommand.getTaskId(), hostRoleCommand);
              break;
          }
        }
      }
    }
    Collections.sort(commands, new Comparator<HostRoleCommand>() {
      @Override
      public int compare(HostRoleCommand o1, HostRoleCommand o2) {
        return (int) (o1.getTaskId()-o2.getTaskId());
      }
    });
    return commands;
  }

  @Override
  public List<Stage> getStagesByHostRoleStatus(Set<HostRoleStatus> statuses) {
    List<Stage> stages = new ArrayList<Stage>();
    for (StageEntity stageEntity : stageDAO.findByCommandStatuses(statuses)) {
      stages.add(stageFactory.createExisting(stageEntity));
    }
    return stages;
  }

  @Override
  public List<Long> getRequestIds() {
    return hostRoleCommandDAO.getRequests();
  }

  public HostRoleCommand getTask(long taskId) {
    HostRoleCommandEntity commandEntity = hostRoleCommandDAO.findByPK((int) taskId);
    if (commandEntity == null) {
      return null;
    }
    return hostRoleCommandFactory.createExisting(commandEntity);
  }

  @Override
  public List<Long> getRequestsByStatus(RequestStatus status) {
    boolean match = true;
    boolean checkAllTasks = false;
    Set<HostRoleStatus> statuses = new HashSet<HostRoleStatus>();
    if (status == RequestStatus.IN_PROGRESS) {
      statuses.addAll(Arrays.asList(HostRoleStatus.PENDING,
          HostRoleStatus.IN_PROGRESS, HostRoleStatus.QUEUED));
    } else if (status == RequestStatus.COMPLETED) {
      match = false;
      checkAllTasks = true;
      statuses.addAll(Arrays.asList(HostRoleStatus.PENDING,
          HostRoleStatus.IN_PROGRESS, HostRoleStatus.QUEUED,
          HostRoleStatus.ABORTED, HostRoleStatus.FAILED,
          HostRoleStatus.TIMEDOUT));
    } else if (status == RequestStatus.FAILED) {
      statuses.addAll(Arrays.asList(HostRoleStatus.ABORTED,
          HostRoleStatus.FAILED, HostRoleStatus.TIMEDOUT));
    }
    return hostRoleCommandDAO.getRequestsByTaskStatus(statuses, match, checkAllTasks);
  }

  @Override
  public Map<Long, String> getRequestContext(List<Long> requestIds) {
    return stageDAO.findRequestContext(requestIds);
  }

  @Override
  public String getRequestContext(long requestId) {
    return stageDAO.findRequestContext(requestId);
  }

  @Override
  @Transactional
  public List<Request> getRequests(Collection<Long> requestIds){
    List<RequestEntity> requestEntities = requestDAO.findByPks(requestIds);
    List<Request> requests = new ArrayList<Request>(requestEntities.size());
    for (RequestEntity requestEntity : requestEntities) {
      requests.add(requestFactory.createExisting(requestEntity));
    }
    return requests;
  }
}
