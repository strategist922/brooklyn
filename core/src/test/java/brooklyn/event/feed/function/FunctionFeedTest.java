package brooklyn.event.feed.function;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.proxying.BasicEntitySpec;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.location.Location;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.test.EntityTestUtils;
import brooklyn.test.TestUtils;
import brooklyn.test.entity.TestApplication;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.MutableMap;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Callables;

public class FunctionFeedTest {

    final static BasicAttributeSensor<String> SENSOR_STRING = new BasicAttributeSensor<String>(String.class, "aString", "");
    final static BasicAttributeSensor<Integer> SENSOR_INT = new BasicAttributeSensor<Integer>(Integer.class, "aLong", "");

    private Location loc;
    private TestApplication app;
    private EntityLocal entity;
    private FunctionFeed feed;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        loc = new LocalhostMachineProvisioningLocation();
        app = ApplicationBuilder.builder(TestApplication.class).manage();
        entity = app.createAndManageChild(BasicEntitySpec.newInstance(TestEntity.class));
        app.start(ImmutableList.of(loc));
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (feed != null) feed.stop();
        if (app != null) Entities.destroyAll(app);
    }
    
    @Test
    public void testPollsFunctionRepeatedlyToSetAttribute() throws Exception {
        feed = FunctionFeed.builder()
                .entity(entity)
                .poll(new FunctionPollConfig<Integer,Integer>(SENSOR_INT)
                        .period(1)
                        .callable(new IncrementingCallable())
                        //.onSuccess((Function<Object,Integer>)(Function)Functions.identity()))
                        )
                .build();
        
        TestUtils.executeUntilSucceeds(MutableMap.of(), new Runnable() {
            public void run() {
                Integer val = entity.getAttribute(SENSOR_INT);
                assertTrue(val != null && val > 2, "val="+val);
            }});
    }
    
    @Test
    public void testCallsOnSuccessWithResultOfCallable() throws Exception {
        feed = FunctionFeed.builder()
                .entity(entity)
                .poll(new FunctionPollConfig<Integer, Integer>(SENSOR_INT)
                        .period(1)
                        .callable(Callables.returning(123))
                        .onSuccess(new AddOneFunction()))
                .build();

        EntityTestUtils.assertAttributeEqualsEventually(entity, SENSOR_INT, 124);
    }
    
    @Test
    public void testCallsOnErrorWithExceptionFromCallable() throws Exception {
        final String errMsg = "my err msg";
        
        feed = FunctionFeed.builder()
                .entity(entity)
                .poll(new FunctionPollConfig<Object, String>(SENSOR_STRING)
                        .period(1)
                        .callable(new ExceptionCallable(errMsg))
                        .onError(new ToStringFunction()))
                .build();

        TestUtils.executeUntilSucceeds(MutableMap.of(), new Runnable() {
            public void run() {
                String val = entity.getAttribute(SENSOR_STRING);
                assertTrue(val != null && val.contains(errMsg), "val="+val);
            }});
    }
    
    @Test
    public void testSharesFunctionWhenMultiplePostProcessors() throws Exception {
        final IncrementingCallable incrementingCallable = new IncrementingCallable();
        final List<Integer> ints = new CopyOnWriteArrayList<Integer>();
        final List<String> strings = new CopyOnWriteArrayList<String>();
        
        entity.subscribe(entity, SENSOR_INT, new SensorEventListener<Integer>() {
                @Override public void onEvent(SensorEvent<Integer> event) {
                    ints.add(event.getValue());
                }});
        entity.subscribe(entity, SENSOR_STRING, new SensorEventListener<String>() {
                @Override public void onEvent(SensorEvent<String> event) {
                    strings.add(event.getValue());
                }});
        
        feed = FunctionFeed.builder()
                .entity(entity)
                .poll(new FunctionPollConfig<Integer, Integer>(SENSOR_INT)
                        .period(10)
                        .callable(incrementingCallable))
                .poll(new FunctionPollConfig<Integer, String>(SENSOR_STRING)
                        .period(10)
                        .callable(incrementingCallable)
                        .onSuccess(new ToStringFunction()))
                .build();

        TestUtils.executeUntilSucceeds(MutableMap.of(), new Runnable() {
            public void run() {
                assertEquals(ints.subList(0, 2), ImmutableList.of(0, 1));
                assertEquals(strings.subList(0, 2), ImmutableList.of("0", "1"));
            }});
    }
    
    private static class IncrementingCallable implements Callable<Integer> {
        private final AtomicInteger next = new AtomicInteger(0);
        
        @Override public Integer call() {
            return next.getAndIncrement();
        }
    }
    
    private static class AddOneFunction implements Function<Integer, Integer> {
        @Override public Integer apply(@Nullable Integer input) {
            return (input != null) ? (input + 1) : null;
        }
    }
    
    private static class ExceptionCallable implements Callable<Void> {
        private final String msg;
        ExceptionCallable(String msg) {
            this.msg = msg;
        }
        @Override public Void call() {
            throw new RuntimeException(msg);
        }
    }
    
    public static class ToStringFunction implements Function<Object, String> {
        @Override public String apply(@Nullable Object input) {
            return (input != null) ? (input.toString()) : null;
        }
    }
}
