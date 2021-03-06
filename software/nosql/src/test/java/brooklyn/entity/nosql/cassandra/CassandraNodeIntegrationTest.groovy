/*
 * Copyright 2012-2013 by Cloudsoft Corp.
 */
package brooklyn.entity.nosql.cassandra

import static brooklyn.test.TestUtils.*
import static java.util.concurrent.TimeUnit.*
import static org.testng.Assert.*

import java.util.concurrent.TimeUnit

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.Test

import brooklyn.entity.proxying.BasicEntitySpec
import brooklyn.entity.trait.Startable

import com.google.common.collect.ImmutableList

/**
 * Cassandra integration tests.
 *
 * Test the operation of the {@link CassandraNode} class.
 */
public class CassandraNodeIntegrationTest extends AbstractCassandraNodeTest {
    private static final Logger log = LoggerFactory.getLogger(CassandraNodeIntegrationTest.class)

    /**
     * Test that a node starts and sets SERVICE_UP correctly.
     */
    @Test(groups = "Integration")
    public void canStartupAndShutdown() {
        cassandra = app.createAndManageChild(BasicEntitySpec.newInstance(CassandraNode.class));
        app.start(ImmutableList.of(testLocation))
        executeUntilSucceedsWithShutdown(cassandra, timeout:2*TimeUnit.MINUTES) {
            assertTrue cassandra.getAttribute(Startable.SERVICE_UP)
        }
        assertFalse cassandra.getAttribute(Startable.SERVICE_UP)
    }

    /**
     * Test that a node starts and sets SERVICE_UP correctly when a jmx port is supplied.
     */
    @Test(groups = "Integration")
    public void canStartupAndShutdownWithCustomJmx() {
        cassandra = app.createAndManageChild(BasicEntitySpec.newInstance(CassandraNode.class)
                .configure("jmxPort", "11099+")
                .configure("rmiServerPort", "19001+"));
        app.start(ImmutableList.of(testLocation))
        executeUntilSucceedsWithShutdown(cassandra, timeout:2*TimeUnit.MINUTES) {
            assertTrue cassandra.getAttribute(Startable.SERVICE_UP)
        }
        assertFalse cassandra.getAttribute(Startable.SERVICE_UP)
    }

    /**
     * Test that a keyspace and column family can be created and used with Astyanax client.
     */
    @Test(groups = "Integration")
    public void testConnection() throws Exception {
        cassandra = app.createAndManageChild(BasicEntitySpec.newInstance(CassandraNode.class)
                .configure("thriftPort", "9876+"));
        app.start(ImmutableList.of(testLocation))
        executeUntilSucceeds(timeout:2*TimeUnit.MINUTES) {
            assertTrue cassandra.getAttribute(Startable.SERVICE_UP)
        }

        astyanaxTest()
    }
}
