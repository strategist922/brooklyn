package brooklyn.entity.group;

import static org.testng.Assert.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.BasicGroup;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.proxying.BasicEntitySpec;
import brooklyn.entity.trait.Startable;
import brooklyn.location.basic.SimulatedLocation;
import brooklyn.management.EntityManager;
import brooklyn.test.TestUtils;
import brooklyn.test.entity.TestApplication;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.MutableMap;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class MembershipTrackingPolicyTest {

    private static final long TIMEOUT_MS = 10*1000;
    
    SimulatedLocation loc;
    EntityManager entityManager;
    TestApplication app;
    private BasicGroup group;
    private RecordingMembershipTrackingPolicy policy;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() {
        loc = new SimulatedLocation();
        app = ApplicationBuilder.builder(TestApplication.class).manage();
        entityManager = app.getManagementContext().getEntityManager();
        
        group = app.createAndManageChild(BasicEntitySpec.newInstance(BasicGroup.class)
                .configure("childrenAsMembers", true));
        policy = new RecordingMembershipTrackingPolicy(MutableMap.of("group", group));
        group.addPolicy(policy);
        policy.setGroup(group);
        
        app.start(ImmutableList.of(loc));
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() {
        if (app != null) Entities.destroy(app);
    }
    
    private TestEntity createAndManageChildOf(Entity parent) {
        EntityManager entityManager = app.getManagementContext().getEntityManager();
        TestEntity result = entityManager.createEntity(BasicEntitySpec.newInstance(TestEntity.class).parent(parent));
        Entities.manage(result);
        return result;
    }
    
    @Test
    public void testNotifiedOfMemberAddedAndRemoved() throws Exception {
        TestEntity e1 = createAndManageChildOf(group);
        
        assertRecordsEventually(Record.newAdded(e1));
        
        e1.clearParent();
        assertRecordsEventually(Record.newAdded(e1), Record.newRemoved(e1));
    }

    @Test
    public void testNotifiedOfMemberChanged() throws Exception {
        TestEntity e1 = createAndManageChildOf(group);
        
        e1.setAttribute(Startable.SERVICE_UP, true);
        
        assertRecordsEventually(Record.newAdded(e1), Record.newChanged(e1));
    }

    @Test
    public void testNotNotifiedWhenPolicySuspended() throws Exception {
        policy.suspend();
        
        TestEntity e1 = createAndManageChildOf(group);
        
        assertRecordsContinually(new Record[0]);
    }

    @Test
    public void testNotifiedOfEverythingWhenPolicyResumed() throws Exception {
        TestEntity e1 = createAndManageChildOf(group);
        
        assertRecordsEventually(Record.newAdded(e1));
        
        policy.suspend();
        
        TestEntity e2 = createAndManageChildOf(group);
        assertRecordsContinually(Record.newAdded(e1));
        
        policy.resume();
        assertRecordsEventually(Record.newAdded(e1), Record.newAdded(e1), Record.newAdded(e2));
    }

    @Test
    public void testNotifiedOfSubsequentChangesWhenPolicyResumed() throws Exception {
        policy.suspend();
        policy.resume();
        
        TestEntity e1 = createAndManageChildOf(group);
        assertRecordsEventually(Record.newAdded(e1));
    }

    private void assertRecordsEventually(final Record... expected) {
        TestUtils.assertEventually(MutableMap.of("timeout", TIMEOUT_MS), new Runnable() {
            public void run() {
                assertEquals(policy.records, ImmutableList.copyOf(expected), "actual="+policy.records);
            }});
    }
    
    private void assertRecordsContinually(final Record... expected) {
        TestUtils.assertSucceedsContinually(ImmutableMap.of("timeout", 100), new Runnable() {
            public void run() {
                assertEquals(policy.records, ImmutableList.copyOf(expected), "actual="+policy.records);
            }});
    }
    
    static class RecordingMembershipTrackingPolicy extends AbstractMembershipTrackingPolicy {
        final List<Record> records = new CopyOnWriteArrayList<Record>();
        
        public RecordingMembershipTrackingPolicy(MutableMap<String, BasicGroup> flags) {
            super(flags);
        }

        @Override protected void onEntityChange(Entity member) {
            records.add(Record.newChanged(member));
        }

        @Override protected void onEntityAdded(Entity member) {
            records.add(Record.newAdded(member));
        }

        @Override protected void onEntityRemoved(Entity member) {
            records.add(Record.newRemoved(member));
        }
    }
    
    static class Record {
        final String action;
        final Entity member;
        
        static Record newChanged(Entity member) {
            return new Record("change", member);
        }
        static Record newAdded(Entity member) {
            return new Record("added", member);
        }
        static Record newRemoved(Entity member) {
            return new Record("removed", member);
        }
        static Record newChangeRecord(Entity member) {
            return new Record("change", member);
        }
        private Record(String action, Entity member) {
            this.action = action;
            this.member = member;
        }
        @Override
        public String toString() {
            return action+"("+member+")";
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(action, member);
        }
        @Override
        public boolean equals(Object other) {
            return other instanceof Record && Objects.equal(action, ((Record)other).action) &&
                    Objects.equal(member, ((Record)other).member);
        }
    }
}
