package brooklyn.event.feed.http;

import static brooklyn.test.TestUtils.executeUntilSucceeds;
import static org.testng.Assert.assertEquals;

import java.net.URL;
import java.util.concurrent.Callable;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.proxying.BasicEntitySpec;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.location.Location;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.test.entity.TestApplication;
import brooklyn.test.entity.TestEntity;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;

public class HttpFeedTest {

    final static BasicAttributeSensor<String> SENSOR_STRING = new BasicAttributeSensor<String>(String.class, "aString", "");
    final static BasicAttributeSensor<Integer> SENSOR_INT = new BasicAttributeSensor<Integer>(Integer.class, "aLong", "");

    private static final long TIMEOUT_MS = 10*1000;
    
    private MockWebServer server;
    private URL baseUrl;
    
    private Location loc;
    private TestApplication app;
    private EntityLocal entity;
    private HttpFeed feed;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        server = new MockWebServer();
        for (int i = 0; i < 100; i++) {
            server.enqueue(new MockResponse().setResponseCode(200).addHeader("content-type: application/json").setBody("{\"foo\":\"myfoo\"}"));
        }
        server.play();
        baseUrl = server.getUrl("/");

        loc = new LocalhostMachineProvisioningLocation();
        app = ApplicationBuilder.builder(TestApplication.class).manage();
        entity = app.createAndManageChild(BasicEntitySpec.newInstance(TestEntity.class));
        app.start(ImmutableList.of(loc));
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (feed != null) feed.stop();
        if (server != null) server.shutdown();
        if (app != null) Entities.destroyAll(app);
        feed = null;
    }
    
    @Test
    public void testPollsAndParsesHttpGetResponse() throws Exception {
        feed = HttpFeed.builder()
                .entity(entity)
                .baseUrl(baseUrl)
                .poll(new HttpPollConfig<Integer>(SENSOR_INT)
                        .period(100)
                        .onSuccess(HttpValueFunctions.responseCode()))
                .poll(new HttpPollConfig<String>(SENSOR_STRING)
                        .period(100)
                        .onSuccess(HttpValueFunctions.stringContentsFunction()))
                .build();
        
        assertSensorEventually(SENSOR_INT, (Integer)200, TIMEOUT_MS);
        assertSensorEventually(SENSOR_STRING, "{\"foo\":\"myfoo\"}", TIMEOUT_MS);
    }
    
    @Test
    public void testPollsAndParsesHttpPostResponse() throws Exception {
        feed = HttpFeed.builder()
                .entity(entity)
                .baseUrl(baseUrl)
                .poll(new HttpPollConfig<Integer>(SENSOR_INT)
                        .method("post")
                        .period(100)
                        .onSuccess(HttpValueFunctions.responseCode()))
                .poll(new HttpPollConfig<String>(SENSOR_STRING)
                        .method("post")
                        .period(100)
                        .onSuccess(HttpValueFunctions.stringContentsFunction()))
                .build();
        
        assertSensorEventually(SENSOR_INT, (Integer)200, TIMEOUT_MS);
        assertSensorEventually(SENSOR_STRING, "{\"foo\":\"myfoo\"}", TIMEOUT_MS);
    }
    
    @Test(groups="Integration")
    // marked as integration so it doesn't fail the plain build in environments
    // with dodgy DNS (ie where "thisdoesnotexistdefinitely" resolves as a host
    // which happily serves you adverts for your ISP, yielding "success" here)
    public void testPollsAndParsesHttpErrorResponseWild() throws Exception {
        feed = HttpFeed.builder()
                .entity(entity)
                .baseUri("http://thisdoesnotexistdefinitely")
                .poll(new HttpPollConfig<String>(SENSOR_STRING)
                        .onSuccess(Functions.constant("success"))
                        .onError(Functions.constant("error")))
                .build();
        
        assertSensorEventually(SENSOR_STRING, "error", TIMEOUT_MS);
    }
    
    @Test
    public void testPollsAndParsesHttpErrorResponseLocal() throws Exception {
        feed = HttpFeed.builder()
                .entity(entity)
                // combo of port 46069 and unknown path will hopefully give an error
                // (without the port, in jenkins it returns some bogus success page)
                .baseUri("http://localhost:46069/path/should/not/exist")
                .poll(new HttpPollConfig<String>(SENSOR_STRING)
                        .onSuccess(Functions.constant("success"))
                        .onError(Functions.constant("error")))
                .build();
        
        assertSensorEventually(SENSOR_STRING, "error", TIMEOUT_MS);
    }
    
    private <T> void assertSensorEventually(final AttributeSensor<T> sensor, final T expectedVal, long timeout) {
        executeUntilSucceeds(ImmutableMap.of("timeout", timeout), new Callable<Void>() {
            public Void call() {
                assertEquals(entity.getAttribute(sensor), expectedVal);
                return null;
            }});
    }
}
