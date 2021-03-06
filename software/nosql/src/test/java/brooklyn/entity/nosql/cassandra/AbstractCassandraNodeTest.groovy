/*
 * Copyright 2012-2013 by Cloudsoft Corp.
 */
package brooklyn.entity.nosql.cassandra

import static brooklyn.test.TestUtils.*
import static java.util.concurrent.TimeUnit.*
import static org.testng.Assert.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod

import brooklyn.entity.basic.ApplicationBuilder
import brooklyn.entity.basic.Attributes
import brooklyn.entity.basic.Entities
import brooklyn.entity.trait.Startable
import brooklyn.location.Location
import brooklyn.location.basic.LocalhostMachineProvisioningLocation
import brooklyn.test.entity.TestApplication
import brooklyn.util.internal.TimeExtras

import com.google.common.base.Throwables
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.netflix.astyanax.AstyanaxContext
import com.netflix.astyanax.Keyspace
import com.netflix.astyanax.MutationBatch
import com.netflix.astyanax.connectionpool.NodeDiscoveryType
import com.netflix.astyanax.connectionpool.OperationResult
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolConfigurationImpl
import com.netflix.astyanax.connectionpool.impl.CountingConnectionPoolMonitor
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl
import com.netflix.astyanax.model.Column
import com.netflix.astyanax.model.ColumnFamily
import com.netflix.astyanax.model.ColumnList
import com.netflix.astyanax.serializers.StringSerializer
import com.netflix.astyanax.thrift.ThriftFamilyFactory

/**
 * Cassandra test framework for integration and live tests, using Astyanax API.
 */
public class AbstractCassandraNodeTest {
    private static final Logger log = LoggerFactory.getLogger(AbstractCassandraNodeTest.class)

    static {
        TimeExtras.init()
    }

    protected TestApplication app
    protected Location testLocation
    protected CassandraNode cassandra

    @BeforeMethod(alwaysRun = true)
    public void setup() throws Exception {
        app = ApplicationBuilder.builder(TestApplication.class).manage();
        testLocation = new LocalhostMachineProvisioningLocation()
    }

    @AfterMethod(alwaysRun = true)
    public void shutdown() {
        Entities.destroyAll(app)
    }

    /**
     * Exercise the {@link CassandraNode} using the Astyanax API.
     */
    protected void astyanaxTest() throws Exception {
        // Create context
        AstyanaxContext<Keyspace> context = getAstyanaxContext(cassandra)
        try {
            // (Re) Create keyspace
            Keyspace keyspace = context.getEntity()
            try {
                keyspace.dropKeyspace()
            } catch (Exception e) { /* Ignore */ }
            keyspace.createKeyspace(ImmutableMap.<String, Object>builder()
                .put("strategy_options", ImmutableMap.<String, Object>of("replication_factor", "1"))
                .put("strategy_class", "SimpleStrategy")
                .build());
            assertNull(keyspace.describeKeyspace().getColumnFamily("Rabbits"))
            assertNull(keyspace.describeKeyspace().getColumnFamily("People"))

            // Create column family
            ColumnFamily<String, String> cf = new ColumnFamily<String, String>(
                    "People", // Column Family Name
                    StringSerializer.get(), // Key Serializer
                    StringSerializer.get()) // Column Serializer
            keyspace.createColumnFamily(cf, null);

            // Insert rows
            MutationBatch m = keyspace.prepareMutationBatch()
            m.withRow(cf, "one")
                    .putColumn("name", "Alice", null)
                    .putColumn("company", "Cloudsoft Corp", null)
            m.withRow(cf, "two")
                    .putColumn("name", "Bob", null)
                    .putColumn("company", "Cloudsoft Corp", null)
                    .putColumn("pet", "Cat", null)

            OperationResult<Void> insert = m.execute()
            assertEquals(insert.host.hostName, cassandra.getAttribute(Attributes.HOSTNAME))
            assertTrue(insert.latency > 0L)

            // Query data
            OperationResult<ColumnList<String>> query = keyspace.prepareQuery(cf)
                    .getKey("one")
                    .execute()
            assertEquals(query.host.hostName, cassandra.getAttribute(Attributes.HOSTNAME))
            assertTrue(query.latency > 0L)

            ColumnList<String> columns = query.getResult()
            assertEquals(columns.size(), 2)

            // Lookup columns in response by name
            String name = columns.getColumnByName("name").getStringValue()
            assertEquals(name, "Alice")

            // Iterate through the columns
            for (Column<String> c : columns) {
                assertTrue(ImmutableList.of("name", "company").contains(c.getName()))
            }
        } catch (ConnectionException ce) {
            // Error connecting to Cassandra
            Throwables.propagate(ce)
        } finally {
            context.shutdown()
        }
    }

    protected AstyanaxContext<Keyspace> getAstyanaxContext(CassandraNode server) {
        AstyanaxContext<Keyspace> context = new AstyanaxContext.Builder()
                .forCluster(server.getClusterName())
                .forKeyspace("BrooklynIntegrationTest")
                .withAstyanaxConfiguration(new AstyanaxConfigurationImpl()
                        .setDiscoveryType(NodeDiscoveryType.NONE))
                .withConnectionPoolConfiguration(new ConnectionPoolConfigurationImpl("BrooklynPool")
                        .setPort(server.getThriftPort())
                        .setMaxConnsPerHost(1)
                        .setConnectTimeout(5000) // 10s
                        .setSeeds(String.format("%s:%d", server.getAttribute(Attributes.HOSTNAME), server.getThriftPort())))
                .withConnectionPoolMonitor(new CountingConnectionPoolMonitor())
                .buildKeyspace(ThriftFamilyFactory.getInstance())

        context.start()
        return context
    }
}
