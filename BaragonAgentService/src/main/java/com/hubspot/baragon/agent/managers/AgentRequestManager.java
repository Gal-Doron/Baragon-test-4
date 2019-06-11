package com.hubspot.baragon.agent.managers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.hubspot.baragon.agent.BaragonAgentServiceModule;
import com.hubspot.baragon.agent.config.LoadBalancerConfiguration;
import com.hubspot.baragon.agent.config.TestingConfiguration;
import com.hubspot.baragon.agent.lbs.FilesystemConfigHelper;
import com.hubspot.baragon.data.BaragonRequestDatastore;
import com.hubspot.baragon.data.BaragonStateDatastore;
import com.hubspot.baragon.exceptions.LockTimeoutException;
import com.hubspot.baragon.exceptions.MissingTemplateException;
import com.hubspot.baragon.models.AgentBatchResponseItem;
import com.hubspot.baragon.models.BaragonAgentState;
import com.hubspot.baragon.models.BaragonRequest;
import com.hubspot.baragon.models.BaragonRequestBatchItem;
import com.hubspot.baragon.models.BaragonService;
import com.hubspot.baragon.models.RequestAction;
import com.hubspot.baragon.models.ServiceContext;
import com.hubspot.baragon.models.UpstreamInfo;

@Singleton
public class AgentRequestManager {
  private static final Logger LOG = LoggerFactory.getLogger(AgentRequestManager.class);
  private final FilesystemConfigHelper configHelper;
  private final BaragonStateDatastore stateDatastore;
  private final BaragonRequestDatastore requestDatastore;
  private final AtomicReference<String> mostRecentRequestId;
  private final Optional<TestingConfiguration> maybeTestingConfiguration;
  private final Random random;
  private final AtomicReference<BaragonAgentState> agentState;
  private final LoadBalancerConfiguration loadBalancerConfiguration;
  private final long agentLockTimeoutMs;

  @Inject
  public AgentRequestManager(BaragonStateDatastore stateDatastore,
                        BaragonRequestDatastore requestDatastore,
                        FilesystemConfigHelper configHelper,
                        Optional<TestingConfiguration> maybeTestingConfiguration,
                        LoadBalancerConfiguration loadBalancerConfiguration,
                        Random random,
                        AtomicReference<BaragonAgentState> agentState,
                        @Named(BaragonAgentServiceModule.AGENT_MOST_RECENT_REQUEST_ID) AtomicReference<String> mostRecentRequestId,
                        @Named(BaragonAgentServiceModule.AGENT_LOCK_TIMEOUT_MS) long agentLockTimeoutMs) {
    this.stateDatastore = stateDatastore;
    this.configHelper = configHelper;
    this.maybeTestingConfiguration = maybeTestingConfiguration;
    this.requestDatastore = requestDatastore;
    this.mostRecentRequestId = mostRecentRequestId;
    this.random = random;
    this.agentState = agentState;
    this.loadBalancerConfiguration = loadBalancerConfiguration;
    this.agentLockTimeoutMs = agentLockTimeoutMs;
  }

  public List<AgentBatchResponseItem> processRequests(List<BaragonRequestBatchItem> batch) throws InterruptedException {
    Map<String, Optional<BaragonRequest>> requests = new HashMap<>();

    // Grab the existing upstreams at the start of this batch, and have apply() and revert() calls modify the list in-memory as we work through batch items
    Map<String, Collection<UpstreamInfo>> existingUpstreamsForThisBatch = batch.stream()
        .map(requestItem -> {
          final Optional<BaragonRequest> maybeRequest = requestDatastore.getRequest(requestItem.getRequestId());

          requests.put(requestItem.getRequestId(), maybeRequest);

          Optional<BaragonService> oldService;

          if (maybeRequest.isPresent()) {
            oldService = getOldService(maybeRequest.get());
          } else {
            oldService = Optional.absent();
          }

          return oldService;
        })
        .filter(Optional::isPresent)
        .map(Optional::get)
        .distinct()
        .collect(Collectors.toMap(BaragonService::getServiceId, service -> {
          try {
            return stateDatastore.getUpstreams(service.getServiceId());
          } catch (Exception e) {
            LOG.warn("Unable to get upstream information for service {}", service.getServiceId(), e);
            return new ArrayList<>();
          }
        }));

    List<AgentBatchResponseItem> responses = new ArrayList<>(batch.size());
    int i = 0;
    for (BaragonRequestBatchItem item : batch) {
      boolean isLast = i == batch.size() - 1;
      responses.add(getResponseItem(processRequest(item.getRequestId(), requests.get(item.getRequestId()), existingUpstreamsForThisBatch, actionForBatchItem(item), !isLast, Optional.of(i)), item));
      i++;
    }
    return responses;
  }

  private AgentBatchResponseItem getResponseItem(Response httpResponse, BaragonRequestBatchItem item) {
    Optional<String> maybeMessage = httpResponse.getEntity() != null ? Optional.of(httpResponse.getEntity().toString()) : Optional.<String>absent();
    return new AgentBatchResponseItem(item.getRequestId(), httpResponse.getStatus(), maybeMessage, item.getRequestType());
  }

  private Optional<RequestAction> actionForBatchItem(BaragonRequestBatchItem item) {
    switch (item.getRequestType()) {
      case REVERT:
      case CANCEL:
        return Optional.of(RequestAction.REVERT);
      case APPLY:
      default:
        if (item.getRequestAction().isPresent()) {
          return item.getRequestAction();
        } else {
          return Optional.of(RequestAction.UPDATE);
        }
    }
  }

  public Response processRequest(String requestId, Optional<BaragonRequest> maybeRequest, Map<String, Collection<UpstreamInfo>> existingUpstreams, Optional<RequestAction> maybeAction, boolean delayReload, Optional<Integer> batchItemNumber) throws InterruptedException {
    if (!maybeRequest.isPresent()) {
      return Response.status(Response.Status.NOT_FOUND).entity(String.format("Request %s does not exist", requestId)).build();
    }
    final BaragonRequest request = maybeRequest.get();
    RequestAction action = maybeAction.or(request.getAction().or(RequestAction.UPDATE));
    Optional<BaragonService> maybeOldService = getOldService(request);

    return processRequest(requestId, action, request, maybeOldService, existingUpstreams, delayReload, batchItemNumber);
  }

  public Response processRequest(String requestId, RequestAction action, BaragonRequest request, Optional<BaragonService> maybeOldService, Map<String, Collection<UpstreamInfo>> existingUpstreams, boolean delayReload, Optional<Integer> batchItemNumber) {
    long start = System.currentTimeMillis();
    try {
      agentState.set(BaragonAgentState.APPLYING);
      LOG.info("Received request to {} with id {}", action, requestId);
      Collection<UpstreamInfo> existingUpstreamsForThisService;
      String serviceId;
      switch (action) {
        case DELETE:
          return delete(request, maybeOldService, delayReload);
        case RELOAD:
          return reload(request, delayReload);
        case REVERT:
          serviceId = maybeOldService.or(request.getLoadBalancerService()).getServiceId();
          existingUpstreamsForThisService = existingUpstreams.get(serviceId);
          if (existingUpstreamsForThisService == null || existingUpstreamsForThisService.isEmpty()) {
            existingUpstreamsForThisService = new ArrayList<>(stateDatastore.getUpstreams(serviceId));
            existingUpstreams.put(serviceId, existingUpstreamsForThisService);
          }

          return revert(request, maybeOldService, existingUpstreamsForThisService, delayReload, batchItemNumber);
        default:
          serviceId = request.getLoadBalancerService().getServiceId();
          existingUpstreamsForThisService = existingUpstreams.get(serviceId);
          if (existingUpstreamsForThisService == null || existingUpstreamsForThisService.isEmpty()) {
            existingUpstreamsForThisService = new ArrayList<>(stateDatastore.getUpstreams(serviceId));
            existingUpstreams.put(serviceId, existingUpstreamsForThisService);
          }

          return apply(request, maybeOldService, existingUpstreamsForThisService, delayReload, batchItemNumber);
      }
    } catch (LockTimeoutException e) {
      LOG.error("Couldn't acquire agent lock for {} in {} ms", requestId, agentLockTimeoutMs, e);
      return Response.status(Response.Status.CONFLICT).entity(String.format("Couldn't acquire agent lock for %s in %s ms. Lock Info: %s", requestId, agentLockTimeoutMs, e.getLockInfo())).build();
    } catch (Exception e) {
      LOG.error("Caught exception while {}ING for request {}", action, requestId, e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(String.format("Caught exception while %sING for request %s: %s", action, requestId, e.getMessage())).build();
    } finally {
      LOG.info("Done processing {} request {} after {}ms)", action, requestId, System.currentTimeMillis() - start);
      agentState.set(BaragonAgentState.ACCEPTING);
    }
  }

  private Response reload(BaragonRequest request, boolean delayReload) throws Exception {
    if (!delayReload) {
      configHelper.checkAndReload();
    }
    mostRecentRequestId.set(request.getLoadBalancerRequestId());
    return Response.ok().build();
  }

  private Response delete(BaragonRequest request, Optional<BaragonService> maybeOldService, boolean delayReload) throws Exception {
    configHelper.delete(request.getLoadBalancerService(), maybeOldService, request.isNoReload(), request.isNoValidate(), delayReload);
    mostRecentRequestId.set(request.getLoadBalancerRequestId());
    return Response.ok().build();
  }

  private Response apply(BaragonRequest request, Optional<BaragonService> maybeOldService, Collection<UpstreamInfo> existingUpstreams, boolean delayReload, Optional<Integer> batchItemNumber) throws Exception {
    final ServiceContext update = getApplyContext(request, existingUpstreams);
    triggerTesting();
    configHelper.apply(update, maybeOldService, true, request.isNoReload(), request.isNoValidate(), delayReload, batchItemNumber);
    mostRecentRequestId.set(request.getLoadBalancerRequestId());
    return Response.ok().build();
  }

  private Response revert(BaragonRequest request, Optional<BaragonService> maybeOldService, Collection<UpstreamInfo> existingUpstreams, boolean delayReload, Optional<Integer> batchItemNumber) throws Exception {
    final ServiceContext update;
    if (movedOffLoadBalancer(maybeOldService)) {
      update = new ServiceContext(request.getLoadBalancerService(), Collections.<UpstreamInfo>emptyList(), System.currentTimeMillis(), false);
    } else {
      update = new ServiceContext(maybeOldService.get(), existingUpstreams, System.currentTimeMillis(), true);
    }

    triggerTesting();

    LOG.info("Reverting to {}", update);
    try {
      configHelper.apply(update, Optional.<BaragonService>absent(), false, request.isNoReload(), request.isNoValidate(), delayReload, batchItemNumber);
    } catch (MissingTemplateException e) {
      if (serviceDidNotPreviouslyExist(maybeOldService)) {
        return Response.ok().build();
      } else {
        throw e;
      }
    }

    return Response.ok().build();
  }

  private ServiceContext getApplyContext(BaragonRequest request, Collection<UpstreamInfo> existingUpstreams) throws Exception {
    if (movedOffLoadBalancer(request)) {
      return new ServiceContext(request.getLoadBalancerService(), Collections.<UpstreamInfo>emptyList(), System.currentTimeMillis(), false);
    } else if (!request.getReplaceUpstreams().isEmpty()) {
      return new ServiceContext(request.getLoadBalancerService(), request.getReplaceUpstreams(), System.currentTimeMillis(), true);
    } else {
      List<UpstreamInfo> upstreams = new ArrayList<>();
      upstreams.addAll(request.getAddUpstreams());
      for (UpstreamInfo existingUpstream : existingUpstreams) {
        boolean present = false;
        boolean toRemove = false;
        for (UpstreamInfo currentUpstream : upstreams) {
          if (UpstreamInfo.upstreamAndGroupMatches(currentUpstream, existingUpstream)) {
            present = true;
            break;
          }
        }
        for (UpstreamInfo upstreamToRemove : request.getRemoveUpstreams()) {
          if (UpstreamInfo.upstreamAndGroupMatches(upstreamToRemove, existingUpstream)) {
            toRemove = true;
            break;
          }
        }
        if (!present && !toRemove) {
          upstreams.add(existingUpstream);
        }
      }

      existingUpstreams.clear();
      existingUpstreams.addAll(upstreams);

      return new ServiceContext(request.getLoadBalancerService(), upstreams, System.currentTimeMillis(), true);
    }
  }

  private boolean movedOffLoadBalancer(Optional<BaragonService> maybeOldService) {
    return (!maybeOldService.isPresent() || !maybeOldService.get().getLoadBalancerGroups().contains(loadBalancerConfiguration.getName()));
  }

  private boolean movedOffLoadBalancer(BaragonRequest request) {
    return (!request.getLoadBalancerService().getLoadBalancerGroups().contains(loadBalancerConfiguration.getName()));
  }

  private boolean serviceDidNotPreviouslyExist(Optional<BaragonService> maybeOldService) {
    return (!maybeOldService.isPresent() || !maybeOldService.get().getLoadBalancerGroups().contains(loadBalancerConfiguration.getName()));
  }

  private Optional<BaragonService> getOldService(BaragonRequest request) {
    Optional<BaragonService> service = Optional.absent();
    if (request.getReplaceServiceId().isPresent()) {
      service = stateDatastore.getService(request.getReplaceServiceId().get());
    }
    if (service.isPresent()) {
      return service;
    } else {
      return stateDatastore.getService(request.getLoadBalancerService().getServiceId());
    }
  }

  private void triggerTesting() throws Exception {
    if (maybeTestingConfiguration.isPresent() && maybeTestingConfiguration.get().isEnabled() && maybeTestingConfiguration.get().getApplyDelayMs() > 0) {
      Thread.sleep(maybeTestingConfiguration.get().getApplyDelayMs());
    }

    if (maybeTestingConfiguration.isPresent() && maybeTestingConfiguration.get().isEnabled()) {
      if (random.nextFloat() <= maybeTestingConfiguration.get().getApplyFailRate()) {
        throw new Exception("Random testing failure");
      }
    }
  }
}
