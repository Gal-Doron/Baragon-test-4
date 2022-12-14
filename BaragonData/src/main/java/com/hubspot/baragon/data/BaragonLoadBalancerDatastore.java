package com.hubspot.baragon.data;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hubspot.baragon.config.ZooKeeperConfiguration;
import com.hubspot.baragon.models.BaragonAgentMetadata;
import com.hubspot.baragon.models.BaragonGroup;
import com.hubspot.baragon.models.BaragonRequest;
import com.hubspot.baragon.models.BaragonService;
import com.hubspot.baragon.models.TrafficSource;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class BaragonLoadBalancerDatastore extends AbstractDataStore {
  private static final Logger LOG = LoggerFactory.getLogger(
    BaragonLoadBalancerDatastore.class
  );

  public static final String LOAD_BALANCER_GROUPS_FORMAT = "/load-balancer";
  public static final String LOAD_BALANCER_GROUP_FORMAT =
    LOAD_BALANCER_GROUPS_FORMAT + "/%s";
  public static final String LOAD_BALANCER_TARGET_COUNT_FORMAT =
    LOAD_BALANCER_GROUP_FORMAT + "/targetCount";
  public static final String LOAD_BALANCER_GROUP_LAST_REQUEST_FORMAT =
    LOAD_BALANCER_GROUP_FORMAT + "/lastRequest";
  public static final String LOAD_BALANCER_GROUP_HOSTS_FORMAT =
    LOAD_BALANCER_GROUP_FORMAT + "/hosts";
  public static final String LOAD_BALANCER_GROUP_HOST_FORMAT =
    LOAD_BALANCER_GROUP_HOSTS_FORMAT + "/%s";

  public static final String LOAD_BALANCER_BASE_PATHS_FORMAT =
    LOAD_BALANCER_GROUPS_FORMAT + "/%s/base-uris";
  public static final String LOAD_BALANCER_BASE_PATH_FORMAT =
    LOAD_BALANCER_BASE_PATHS_FORMAT + "/%s";

  @Inject
  public BaragonLoadBalancerDatastore(
    CuratorFramework curatorFramework,
    ObjectMapper objectMapper,
    ZooKeeperConfiguration zooKeeperConfiguration
  ) {
    super(curatorFramework, objectMapper, zooKeeperConfiguration);
  }

  public LeaderLatch createLeaderLatch(
    String clusterName,
    BaragonAgentMetadata agentMetadata
  ) {
    try {
      return new LeaderLatch(
        curatorFramework,
        String.format(LOAD_BALANCER_GROUP_HOSTS_FORMAT, clusterName),
        objectMapper.writeValueAsString(agentMetadata)
      );
    } catch (JsonProcessingException e) {
      throw Throwables.propagate(e);
    }
  }

  public Collection<BaragonGroup> getLoadBalancerGroups() {
    final Collection<String> nodes = getChildren(LOAD_BALANCER_GROUPS_FORMAT);

    if (nodes.isEmpty()) {
      return Collections.emptyList();
    }
    final Collection<BaragonGroup> groups = Lists.newArrayListWithCapacity(nodes.size());

    for (String node : nodes) {
      try {
        groups.addAll(
          readFromZk(String.format(LOAD_BALANCER_GROUP_FORMAT, node), BaragonGroup.class)
            .asSet()
        );
      } catch (Exception e) {
        LOG.error(
          String.format("Could not fetch info for group %s due to error %s", node, e)
        );
      }
    }

    return groups;
  }

  public Optional<BaragonGroup> getLoadBalancerGroup(String name) {
    try {
      return readFromZk(
        String.format(LOAD_BALANCER_GROUP_FORMAT, name),
        BaragonGroup.class
      );
    } catch (RuntimeException e) {
      if (e.getMessage().contains("No content")) {
        return Optional.absent();
      }
      throw Throwables.propagate(e);
    }
  }

  public BaragonGroup addSourceToGroup(String name, TrafficSource source) {
    Optional<BaragonGroup> maybeGroup = getLoadBalancerGroup(name);
    BaragonGroup group;
    if (maybeGroup.isPresent()) {
      group = maybeGroup.get();
      group.addTrafficSource(source);
    } else {
      group =
        new BaragonGroup(
          name,
          Optional.absent(),
          Sets.newHashSet(source),
          null,
          Optional.absent(),
          Collections.emptySet(),
          Collections.emptyMap(),
          1
        );
    }
    writeToZk(String.format(LOAD_BALANCER_GROUP_FORMAT, name), group);
    return group;
  }

  public Optional<BaragonGroup> removeSourceFromGroup(String name, TrafficSource source) {
    Optional<BaragonGroup> maybeGroup = getLoadBalancerGroup(name);
    if (maybeGroup.isPresent()) {
      maybeGroup.get().removeTrafficSource(source);
      writeToZk(String.format(LOAD_BALANCER_GROUP_FORMAT, name), maybeGroup.get());
      return maybeGroup;
    } else {
      return Optional.absent();
    }
  }

  public void updateGroupInfo(
    String name,
    Optional<String> defaultDomain,
    Set<String> domains,
    Map<String, Set<String>> domainAliases,
    Integer minHealthyAgents
  ) {
    Optional<BaragonGroup> maybeGroup = getLoadBalancerGroup(name);
    BaragonGroup group;
    if (maybeGroup.isPresent()) {
      group = maybeGroup.get();
      group.setDefaultDomain(defaultDomain);
      group.setDomains(domains);
      group.setDomainAliases(domainAliases);
      group.setMinHealthyAgents(minHealthyAgents);
    } else {
      group =
        new BaragonGroup(
          name,
          defaultDomain,
          Collections.<TrafficSource>emptySet(),
          null,
          defaultDomain,
          domains,
          domainAliases,
          1
        );
    }
    writeToZk(String.format(LOAD_BALANCER_GROUP_FORMAT, name), group);
  }

  public Set<String> getLoadBalancerGroupNames() {
    return ImmutableSet.copyOf(getChildren(LOAD_BALANCER_GROUPS_FORMAT));
  }

  public Optional<BaragonAgentMetadata> getAgent(String path) {
    return readFromZk(path, BaragonAgentMetadata.class);
  }

  public Optional<BaragonAgentMetadata> getAgent(String clusterName, String agentId) {
    Collection<BaragonAgentMetadata> agents = getAgentMetadata(clusterName);
    Optional<BaragonAgentMetadata> maybeAgent = Optional.absent();
    for (BaragonAgentMetadata agent : agents) {
      if (agent.getAgentId().equals(agentId)) {
        maybeAgent = Optional.of(agent);
        break;
      }
    }
    return maybeAgent;
  }

  public Collection<BaragonAgentMetadata> getAgentMetadata(String clusterName) {
    final Collection<String> nodes = getChildren(
      String.format(LOAD_BALANCER_GROUP_HOSTS_FORMAT, clusterName)
    );

    if (nodes.isEmpty()) {
      return Collections.emptyList();
    }

    final Collection<BaragonAgentMetadata> metadata = Lists.newArrayListWithCapacity(
      nodes.size()
    );

    for (String node : nodes) {
      try {
        final String value = new String(
          curatorFramework
            .getData()
            .forPath(String.format(LOAD_BALANCER_GROUP_HOST_FORMAT, clusterName, node)),
          Charsets.UTF_8
        );
        if (value.startsWith("http://")) {
          metadata.add(BaragonAgentMetadata.fromString(value));
        } else {
          metadata.add(objectMapper.readValue(value, BaragonAgentMetadata.class));
        }
      } catch (KeeperException.NoNodeException nne) {
        // uhh, didnt see that...
      } catch (JsonParseException | JsonMappingException je) {
        LOG.warn(
          String.format(
            "Exception deserializing %s",
            String.format(LOAD_BALANCER_GROUP_HOST_FORMAT, clusterName, node)
          ),
          je
        );
      } catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }

    return metadata;
  }

  public Collection<BaragonAgentMetadata> getAgentMetadata(
    Collection<String> clusterNames
  ) {
    final Set<BaragonAgentMetadata> metadata = Sets.newHashSet();

    for (String clusterName : clusterNames) {
      metadata.addAll(getAgentMetadata(clusterName));
    }

    return metadata;
  }

  public Optional<String> getBasePathServiceId(
    String loadBalancerGroup,
    String basePath
  ) {
    return readFromZk(
      String.format(
        LOAD_BALANCER_BASE_PATH_FORMAT,
        loadBalancerGroup,
        encodeUrl(basePath)
      ),
      String.class
    );
  }

  public void clearBasePath(String loadBalancerGroup, String basePath) {
    deleteNode(
      String.format(
        LOAD_BALANCER_BASE_PATH_FORMAT,
        loadBalancerGroup,
        encodeUrl(basePath)
      )
    );
  }

  public void setBasePathServiceId(
    String loadBalancerGroup,
    String basePath,
    String serviceId
  ) {
    writeToZk(
      String.format(
        LOAD_BALANCER_BASE_PATH_FORMAT,
        loadBalancerGroup,
        encodeUrl(basePath)
      ),
      serviceId
    );
  }

  public Collection<String> getBasePaths(String loadBalancerGroup) {
    final Collection<String> encodedPaths = getChildren(
      String.format(LOAD_BALANCER_BASE_PATHS_FORMAT, loadBalancerGroup)
    );
    final Collection<String> decodedPaths = Lists.newArrayListWithCapacity(
      encodedPaths.size()
    );

    for (String encodedPath : encodedPaths) {
      decodedPaths.add(decodeUrl(encodedPath));
    }

    return decodedPaths;
  }

  public Optional<String> getLastRequestForGroup(String loadBalancerGroup) {
    return readFromZk(
      String.format(LOAD_BALANCER_GROUP_LAST_REQUEST_FORMAT, loadBalancerGroup),
      String.class
    );
  }

  public void setLastRequestId(String loadBalancerGroup, String requestId) {
    writeToZk(
      String.format(LOAD_BALANCER_GROUP_LAST_REQUEST_FORMAT, loadBalancerGroup),
      requestId
    );
  }

  public int setTargetCount(String group, Integer count) {
    writeToZk(String.format(LOAD_BALANCER_TARGET_COUNT_FORMAT, group), count.toString());
    return count;
  }

  public Optional<Integer> getTargetCount(String group) {
    return readFromZk(
      String.format(LOAD_BALANCER_TARGET_COUNT_FORMAT, group),
      Integer.class
    );
  }

  public BaragonRequest updateForDefaultDomains(BaragonRequest request) {
    return request.withUpdatedDomains(
      getDomainsWithDefaults(request.getLoadBalancerService())
    );
  }

  public Set<String> getDomainsWithDefaults(BaragonService service) {
    Set<String> updatedDomains = new HashSet<>(service.getDomains());
    for (String group : service.getLoadBalancerGroups()) {
      Optional<BaragonGroup> maybeGroup = getLoadBalancerGroup(group);
      if (maybeGroup.isPresent()) {
        if (
          maybeGroup.get().getDefaultDomain().isPresent() &&
          maybeGroup.get().getDomains().stream().noneMatch(updatedDomains::contains)
        ) {
          updatedDomains.add(maybeGroup.get().getDefaultDomain().get());
        }
      }
    }
    return updatedDomains;
  }
}
