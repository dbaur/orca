/*
 * Copyright 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.front50.tasks;

import static java.lang.String.format;

import com.google.common.collect.ImmutableMap;
import com.netflix.spinnaker.fiat.model.UserPermission;
import com.netflix.spinnaker.fiat.model.resources.Role;
import com.netflix.spinnaker.fiat.model.resources.ServiceAccount;
import com.netflix.spinnaker.fiat.shared.FiatPermissionEvaluator;
import com.netflix.spinnaker.fiat.shared.FiatStatus;
import com.netflix.spinnaker.kork.exceptions.UserException;
import com.netflix.spinnaker.orca.api.pipeline.RetryableTask;
import com.netflix.spinnaker.orca.api.pipeline.TaskResult;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.front50.Front50Service;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import retrofit.client.Response;

/**
 * Save a pipeline-scoped Fiat Service Account. The roles from this service account are used for
 * authorization decisions when the pipeline is executed from an automated trigger.
 */
@Component
@Slf4j
public class SaveServiceAccountTask implements RetryableTask {

  private static final String SERVICE_ACCOUNT_SUFFIX = "@managed-service-account";

  @Autowired(required = false)
  private FiatStatus fiatStatus;

  @Autowired(required = false)
  private Front50Service front50Service;

  @Autowired(required = false)
  private FiatPermissionEvaluator fiatPermissionEvaluator;

  @Override
  public long getBackoffPeriod() {
    return TimeUnit.SECONDS.toMillis(1);
  }

  @Override
  public long getTimeout() {
    return TimeUnit.SECONDS.toMillis(60);
  }

  @Nonnull
  @SuppressWarnings("unchecked")
  @Override
  public TaskResult execute(@Nonnull StageExecution stage) {
    if (!fiatStatus.isEnabled()) {
      throw new UnsupportedOperationException("Fiat is not enabled, cannot save roles.");
    }

    if (front50Service == null) {
      throw new UnsupportedOperationException(
          "Front50 is not enabled, no way to save pipeline. Fix this by setting front50.enabled: true");
    }

    if (!stage.getContext().containsKey("pipeline")) {
      throw new IllegalArgumentException("pipeline context must be provided");
    }

    if (!(stage.getContext().get("pipeline") instanceof String)) {
      throw new IllegalArgumentException(
          "'pipeline' context key must be a base64-encoded string: Ensure you're on the most recent version of gate");
    }

    Map<String, Object> pipeline;
    try {
      pipeline = (Map<String, Object>) stage.decodeBase64("/pipeline", Map.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("pipeline must be encoded as base64", e);
    }

    if (!pipeline.containsKey("roles")) {
      log.debug("Skipping managed service accounts since roles field is not present.");
      return TaskResult.SUCCEEDED;
    }

    List<String> roles = (List<String>) pipeline.get("roles");
    String user = stage.getExecution().getTrigger().getUser();

    Map<String, Object> outputs = new HashMap<>();

    pipeline.computeIfAbsent(
        "id",
        k -> {
          String uuid = UUID.randomUUID().toString();
          outputs.put("pipeline.id", uuid);
          return uuid;
        });

    // Check if pipeline roles did not change, and skip updating a service account if so.
    String serviceAccountName = generateSvcAcctName(pipeline);
    if (!pipelineRolesChanged(serviceAccountName, roles)) {
      log.debug("Skipping managed service account creation/updatimg since roles have not changed.");
      return TaskResult.builder(ExecutionStatus.SUCCEEDED)
          .context(ImmutableMap.of("pipeline.serviceAccount", serviceAccountName))
          .build();
    }

    if (!isUserAuthorized(user, roles)) {
      log.warn("User {} is not authorized with all roles for pipeline", user);
      throw new UserException(
          format("User '%s' is not authorized with all roles for pipeline", user));
    }

    ServiceAccount svcAcct = new ServiceAccount();
    svcAcct.setName(serviceAccountName);
    svcAcct.setMemberOf(roles);

    // Creating a service account with an existing name will overwrite it
    // i.e. perform an update for our use case
    Response response = front50Service.saveServiceAccount(svcAcct);

    if (response.getStatus() != HttpStatus.OK.value()) {
      return TaskResult.ofStatus(ExecutionStatus.TERMINAL);
    }

    outputs.put("pipeline.serviceAccount", svcAcct.getName());

    return TaskResult.builder(ExecutionStatus.SUCCEEDED).context(outputs).build();
  }

  private String generateSvcAcctName(Map<String, Object> pipeline) {
    if (pipeline.containsKey("serviceAccount")) {
      return (String) pipeline.get("serviceAccount");
    }
    String pipelineName = (String) pipeline.get("id");
    return pipelineName.toLowerCase() + SERVICE_ACCOUNT_SUFFIX;
  }

  private boolean isUserAuthorized(String user, List<String> pipelineRoles) {
    if (user == null) {
      return false;
    }

    if (pipelineRoles == null || pipelineRoles.isEmpty()) { // No permissions == everyone can access
      return true;
    }

    UserPermission.View permission = fiatPermissionEvaluator.getPermission(user);
    if (permission == null) { // Should never happen?
      return false;
    }

    if (permission.isAdmin()) {
      return true;
    }

    // User has to have all the pipeline roles.
    Set<String> userRoles =
        permission.getRoles().stream().map(Role.View::getName).collect(Collectors.toSet());

    return userRoles.containsAll(pipelineRoles);
  }

  private boolean pipelineRolesChanged(String serviceAccountName, List<String> pipelineRoles) {
    UserPermission.View permission = fiatPermissionEvaluator.getPermission(serviceAccountName);
    if (permission == null || pipelineRoles == null) { // check if user has all permissions
      return true;
    }

    Set<String> currentRoles =
        permission.getRoles().stream().map(Role.View::getName).collect(Collectors.toSet());

    return !currentRoles.equals(new HashSet<>(pipelineRoles));
  }
}
