package brooklyn.entity.proxying;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.Application;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.Entities;
import brooklyn.management.ManagementContext;
import brooklyn.management.internal.AbstractManagementContext;
import brooklyn.test.entity.TestApplication;
import brooklyn.test.entity.TestApplicationImpl;
import brooklyn.test.entity.TestEntity;
import brooklyn.test.entity.TestEntityImpl;

public class InternalEntityFactoryTest {

    private ManagementContext managementContext;
    private InternalEntityFactory factory;

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        managementContext = Entities.newManagementContext();
        factory = new InternalEntityFactory(managementContext, managementContext.getEntityManager().getEntityTypeRegistry());
    }
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (managementContext instanceof AbstractManagementContext) ((AbstractManagementContext)managementContext).terminate();
    }
    
    @Test
    public void testCreatesEntity() throws Exception {
        EntitySpec<TestApplication> spec = BasicEntitySpec.newInstance(TestApplication.class);
        TestApplicationImpl app = (TestApplicationImpl) factory.createEntity(spec);
        
        Entity proxy = app.getProxy();
        assertTrue(proxy instanceof Application, "proxy="+app);
        assertFalse(proxy instanceof TestApplicationImpl, "proxy="+app);
        
        assertEquals(proxy.getParent(), null);
        assertSame(proxy.getApplication(), proxy);
    }
    
    @Test
    public void testCreatesProxy() throws Exception {
        TestApplicationImpl app = new TestApplicationImpl();
        EntitySpec<Application> spec = BasicEntitySpec.newInstance(Application.class).impl(TestApplicationImpl.class);
        Application proxy = factory.createEntityProxy(spec, app);
        
        assertFalse(proxy instanceof TestApplicationImpl, "proxy="+app);
        assertTrue(proxy instanceof EntityProxy, "proxy="+app);
    }
    
    @Test
    public void testSetsEntityIsLegacyConstruction() throws Exception {
        TestEntity legacy = new TestEntityImpl();
        assertTrue(legacy.isLegacyConstruction());
        
        TestEntity entity = factory.createEntity(BasicEntitySpec.newInstance(TestEntity.class));
        assertFalse(entity.isLegacyConstruction());
    }
    
    @Test
    public void testCreatesProxyImplementingAdditionalInterfaces() throws Exception {
        MyApplicationImpl app = new MyApplicationImpl();
        EntitySpec<Application> spec = BasicEntitySpec.newInstance(Application.class).impl(MyApplicationImpl.class).additionalInterfaces(MyInterface.class);
        Application proxy = factory.createEntityProxy(spec, app);
        
        assertFalse(proxy instanceof MyApplicationImpl, "proxy="+app);
        assertTrue(proxy instanceof MyInterface, "proxy="+app);
        assertTrue(proxy instanceof EntityProxy, "proxy="+app);
    }
    
    public interface MyInterface {
    }
    
    public static class MyApplicationImpl extends AbstractApplication implements MyInterface {
    }
}
