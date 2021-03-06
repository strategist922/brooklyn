/*
 * Copyright 2012-2013 by Cloudsoft Corp.
 */
package brooklyn.entity.nosql.cassandra;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.SoftwareProcessImpl;
import brooklyn.event.feed.jmx.JmxAttributePollConfig;
import brooklyn.event.feed.jmx.JmxFeed;
import brooklyn.event.feed.jmx.JmxHelper;
import brooklyn.util.MutableMap;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

/**
 * Implementation of {@link CassandraNode}.
 */
public class CassandraNodeImpl extends SoftwareProcessImpl implements CassandraNode {
    /** serialVersionUID */
    private static final long serialVersionUID = -5430475649331861964L;

    private static final Logger log = LoggerFactory.getLogger(CassandraNodeImpl.class);

    public CassandraNodeImpl() {
        this(MutableMap.of(), null);
    }
    public CassandraNodeImpl(Map<?, ?> properties) {
        this(properties, null);
    }
    public CassandraNodeImpl(Entity parent) {
        this(MutableMap.of(), parent);
    }
    public CassandraNodeImpl(Map<?, ?> properties, Entity parent) {
        super(properties, parent);
    }

    public Integer getGossipPort() { return getAttribute(CassandraNode.GOSSIP_PORT); }
    public Integer getSslGossipPort() { return getAttribute(CassandraNode.SSL_GOSSIP_PORT); }
    public Integer getThriftPort() { return getAttribute(CassandraNode.THRIFT_PORT); }
    public String getClusterName() { return getAttribute(CassandraNode.CLUSTER_NAME); }
    public Long getToken() { return getAttribute(CassandraNode.TOKEN); }
    public String getSeeds() { return getConfig(CassandraNode.SEEDS); }

    @Override
    public Class<CassandraNodeDriver> getDriverInterface() {
        return CassandraNodeDriver.class;
    }

    private volatile JmxFeed jmxFeed;
    private JmxHelper jmxHelper;
    private ObjectName storageServiceMBean = JmxHelper.createObjectName("org.apache.cassandra.db:type=StorageService");

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    protected void connectSensors() {
        super.connectSensors();

        jmxHelper = new JmxHelper(this);
        jmxFeed = JmxFeed.builder()
                .entity(this)
                .period(500, TimeUnit.MILLISECONDS)
                .helper(jmxHelper)
                .pollAttribute(new JmxAttributePollConfig<Boolean>(SERVICE_UP)
                        .objectName(storageServiceMBean)
                        .attributeName("Initialized")
                        .onSuccess(Functions.forPredicate(Predicates.notNull()))
                        .onError(Functions.constant(false)))
                .pollAttribute(new JmxAttributePollConfig<Long>(TOKEN)
                        .objectName(storageServiceMBean)
                        .attributeName("TokenToEndpointMap")
                        .onSuccess((Function) new Function<Map, Long>() {
                            @Override
                            public Long apply(@Nullable Map input) {
                                if (input == null || input.isEmpty()) return 0L;
                                // FIXME does not work on aws-ec2, uses RFC1918 address
                                Predicate<String> self = Predicates.in(ImmutableList.of(getAttribute(HOSTNAME), getAttribute(ADDRESS)));
                                Set tokens = Maps.filterValues(input, self).keySet();
                                return Long.parseLong(Iterables.getFirst(tokens, "-1"));
                            }
                        })
                        .onError(Functions.constant(-1L)))
                .pollAttribute(new JmxAttributePollConfig<Integer>(PEERS)
                        .objectName(storageServiceMBean)
                        .attributeName("TokenToEndpointMap")
                        .onSuccess((Function) new Function<Map, Integer>() {
                            @Override
                            public Integer apply(@Nullable Map input) {
                                if (input == null || input.isEmpty()) return 0;
                                return input.size();
                            }
                        })
                        .onError(Functions.constant(-1)))
                .build();
    }

    @Override
    public void disconnectSensors() {
        super.disconnectSensors();

        if (jmxFeed != null) jmxFeed.stop();
        if (jmxHelper.isConnected()) jmxHelper.disconnect();
    }

    @Override
    public void setToken(String token) {
        try {
            if (!jmxHelper.isConnected()) jmxHelper.connect();;
            jmxHelper.operation(storageServiceMBean, "move", token);
            log.info("Moved server {} to token {}", getId(), token);
        } catch (IOException ioe) {
            Throwables.propagate(ioe);
        }
    }
}
