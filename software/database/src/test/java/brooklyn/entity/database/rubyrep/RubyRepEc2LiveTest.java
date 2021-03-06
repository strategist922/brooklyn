package brooklyn.entity.database.rubyrep;

import brooklyn.entity.AbstractEc2LiveTest;
import brooklyn.entity.database.postgresql.PostgreSqlIntegrationTest;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.proxying.BasicEntitySpec;
import brooklyn.location.Location;
import org.testng.annotations.Test;

public class RubyRepEc2LiveTest extends AbstractEc2LiveTest {

    @Override
    protected void doTest(Location loc) throws Exception {
        PostgreSqlNode db1 = app.createAndManageChild(BasicEntitySpec.newInstance(PostgreSqlNode.class)
                .configure("creationScriptContents", PostgreSqlIntegrationTest.CREATION_SCRIPT)
                .configure("port", 9111));

        PostgreSqlNode db2 = app.createAndManageChild(BasicEntitySpec.newInstance(PostgreSqlNode.class)
                .configure("creationScriptContents", PostgreSqlIntegrationTest.CREATION_SCRIPT)
                .configure("port", 9111));

        RubyRepIntegrationTest.startInLocation(app, db1, db2, loc);
        RubyRepIntegrationTest.testReplication(db1, db2);
    }
    
    @Test(enabled=false)
    public void testDummy() {} // Convince testng IDE integration that this really does have test methods  
}

