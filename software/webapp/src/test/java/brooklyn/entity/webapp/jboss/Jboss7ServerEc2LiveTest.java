package brooklyn.entity.webapp.jboss;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertNotNull;

import java.net.URL;

import org.testng.annotations.Test;

import brooklyn.entity.AbstractEc2LiveTest;
import brooklyn.entity.proxying.BasicEntitySpec;
import brooklyn.location.Location;
import brooklyn.test.HttpTestUtils;
import brooklyn.test.TestUtils;

import com.google.common.collect.ImmutableList;

/**
 * A simple test of installing+running on AWS-EC2, using various OS distros and versions. 
 */
public class Jboss7ServerEc2LiveTest extends AbstractEc2LiveTest {
    
    private URL warUrl = checkNotNull(getClass().getClassLoader().getResource("hello-world.war"));
    
    @Override
    protected void doTest(Location loc) throws Exception {
        final JBoss7Server server = app.createAndManageChild(BasicEntitySpec.newInstance(JBoss7Server.class)
                .configure("war", warUrl.toString()));
        
        app.start(ImmutableList.of(loc));
        
        String url = server.getAttribute(JBoss7Server.ROOT_URL);
        
        HttpTestUtils.assertHttpStatusCodeEventuallyEquals(url, 200);
        HttpTestUtils.assertContentContainsText(url, "Hello");
        
        TestUtils.executeUntilSucceeds(new Runnable() {
            public void run() {
                assertNotNull(server.getAttribute(JBoss7Server.REQUEST_COUNT));
                assertNotNull(server.getAttribute(JBoss7Server.ERROR_COUNT));
                assertNotNull(server.getAttribute(JBoss7Server.TOTAL_PROCESSING_TIME));
                assertNotNull(server.getAttribute(JBoss7Server.MAX_PROCESSING_TIME));
                assertNotNull(server.getAttribute(JBoss7Server.BYTES_RECEIVED));
                assertNotNull(server.getAttribute(JBoss7Server.BYTES_SENT));
            }});
    }
    
    @Test(enabled=false)
    public void testDummy() {} // Convince testng IDE integration that this really does have test methods  
}
