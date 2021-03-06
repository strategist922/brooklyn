package brooklyn.entity.webapp

import static brooklyn.test.TestUtils.*
import static java.util.concurrent.TimeUnit.*
import static org.testng.AssertJUnit.*

import org.testng.annotations.Test

import brooklyn.entity.Entity
import brooklyn.entity.webapp.tomcat.*
import brooklyn.location.PortRange
import brooklyn.location.basic.LocalhostMachineProvisioningLocation
import brooklyn.location.basic.PortRanges
import brooklyn.policy.autoscaling.AutoScalerPolicy
import brooklyn.test.entity.TestApplication
import brooklyn.test.entity.TestApplicationImpl

import com.google.common.collect.Iterables

public class TomcatAutoScalerPolicyTest {
    
    // TODO Test is time-sensitive: we send two web-requests in rapid succession, and expect the average workrate to
    // be 2 msgs/sec; we then expect resizing to kick-in.
    // P speculate that... if for some reason things are running slow (e.g. GC during that one second), then brooklyn 
    // may not report the 2 msgs/sec.
    
    @Test(groups=["Integration"])
    public void testWithTomcatServers() {
        /*
         * One DynamicWebAppClster with auto-scaler policy
         * AutoScaler listening to DynamicWebAppCluster.TOTAL_REQS
         * AutoScaler minSize 1
         * AutoScaler upper metric 1
         * AutoScaler lower metric 0
         * .. send one request
         * wait til auto-scaling complete
         * assert cluster size 2
         */
        
        PortRange httpPort = PortRanges.fromString("7880+");
        PortRange jmxP = PortRanges.fromString("32199+");
        PortRange shutdownP = PortRanges.fromString("31880+");
        TestApplication app = new TestApplicationImpl()
        try {
            DynamicWebAppCluster cluster = new DynamicWebAppClusterImpl(
                factory: { Map properties, Entity parent ->
                    def tc = new TomcatServerImpl(properties, parent)
                    tc.setConfig(TomcatServer.HTTP_PORT.configKey, httpPort)
                    tc.setConfig(TomcatServer.JMX_PORT.configKey, jmxP)
                    tc.setConfig(TomcatServer.SHUTDOWN_PORT, shutdownP)
                    tc
                },
                initialSize: 1,
                parent: app
            )
            
            AutoScalerPolicy policy = AutoScalerPolicy.builder()
                    .metric(DynamicWebAppCluster.AVERAGE_REQUEST_COUNT)
                    .metricRange(0, 1)
                    .minPoolSize(1)
                    .build();
            cluster.addPolicy(policy)
            
            app.start([new LocalhostMachineProvisioningLocation(name:'london')])
            
            assertEquals 1, cluster.currentSize
            
            TomcatServerImpl tc = Iterables.getOnlyElement(cluster.getMembers())
            2.times { connectToURL(tc.getAttribute(TomcatServerImpl.ROOT_URL)) }
            
            executeUntilSucceeds(timeout: 3*SECONDS) {
                assertEquals 2.0d/cluster.currentSize, cluster.getAttribute(DynamicWebAppCluster.AVERAGE_REQUEST_COUNT)
            }
    
            executeUntilSucceedsWithShutdown(cluster, timeout: 5*MINUTES) {
                assertTrue policy.isRunning()
                assertEquals 2, cluster.currentSize
                assertEquals 1.0d, cluster.getAttribute(DynamicWebAppCluster.AVERAGE_REQUEST_COUNT)
            }
        } finally {
            app.stop()
        }
    }
}
