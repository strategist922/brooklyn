package brooklyn.test

import static brooklyn.test.TestUtils.*
import static org.testng.Assert.*

import java.util.Map

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

import brooklyn.entity.Application
import brooklyn.entity.basic.AbstractApplication
import brooklyn.entity.group.DynamicCluster
import brooklyn.entity.proxy.nginx.NginxController
import brooklyn.entity.trait.Startable
import brooklyn.entity.webapp.tomcat.TomcatServer
import brooklyn.location.MachineLocation
import brooklyn.location.basic.aws.AwsLocation
import brooklyn.location.basic.aws.CredentialsFromEnv
import brooklyn.util.internal.EntityStartUtils

class AwsLocationTest {
    private static final Logger LOG = LoggerFactory.getLogger(AwsLocationTest.class)
    
    private static final String REGION_NAME = "us-east-1" // "eu-west-1"
    private static final String IMAGE_ID = REGION_NAME+"/"+"ami-0859bb61" // "ami-d7bb90a3"
    private static final String IMAGE_OWNER = "411009282317"
    
    private AwsLocation aws
    private File sshPrivateKey
    private File sshPublicKey

    private Application app
    private NginxController nginx
    private DynamicCluster cluster

    static class TestApplication extends AbstractApplication {
        public TestApplication(Map properties=[:]) {
            super(properties)
        }
    }

    @BeforeMethod(groups = "Live")
    public void setup() {
        app = new TestApplication();
    }

    @AfterMethod(groups = "Live")
    public void shutdown() {
        if (cluster != null && cluster.getAttribute(Startable.SERVICE_UP)) {
            EntityStartUtils.stopEntity(cluster)
        }
        if (nginx != null && nginx.getAttribute(Startable.SERVICE_UP)) {
            EntityStartUtils.stopEntity(nginx)
        }
    }
    
    @BeforeMethod(groups = "Live")
    public void setUp() {
        URL resource = getClass().getClassLoader().getResource("jclouds/id_rsa.private")
        assertNotNull resource
        sshPrivateKey = new File(resource.path)
        resource = getClass().getClassLoader().getResource("jclouds/id_rsa.pub")
        assertNotNull resource
        sshPublicKey = new File(resource.path)
        
        CredentialsFromEnv creds = new CredentialsFromEnv();
        aws = new AwsLocation(identity:creds.getAWSAccessKeyId(), credential:creds.getAWSSecretKey(), providerLocationId:REGION_NAME)
    }
    
    @Test(groups = ["Live", "WIP"] )
    public void testProvisionAwsCluster() {
        Map imageData = [
	            imageId:IMAGE_ID,
	            providerLocationId:REGION_NAME,
	            sshPublicKey:sshPublicKey,
	            sshPrivateKey:sshPrivateKey,
	            securityGroups:[ "everything" ]
            ]
        aws.setTagMapping([
            "brooklyn.entity.webapp.tomcat.TomcatServer":imageData,
            "brooklyn.entity.proxy.nginx.NginxController":imageData,
        ]) //, imageOwner:IMAGE_OWNER]])
 
        def template = { Map properties -> new TomcatServer(properties) }
        
        cluster = new DynamicCluster(owner:app, newEntity:template, initialSize:2, httpPort:8080 )
        URL war = getClass().getClassLoader().getResource("swf-booking-mvc.war")
        assertNotNull war, "Unable to locate resource $war"
        cluster.setConfig(TomcatServer.WAR, war.path)
        cluster.start([ aws ])

        nginx = new NginxController([
                "owner" : app,
                "cluster" : cluster,
                "domain" : "localhost",
                "port" : 8000,
                "portNumberSensor" : TomcatServer.HTTP_PORT,
            ])

        nginx.start([ aws ])
        
        executeUntilSucceeds([:], {
            // Nginx URL is available
            MachineLocation machine = nginx.locations.find { true }
            String url = "http://" + machine.address.hostAddress + ":" + nginx.getAttribute(NginxController.HTTP_PORT) + "/swf-booking-mvc"
            assertTrue urlRespondsWithStatusCode200(url)

            // Tomcat URL is available
            cluster.members.each {
                assertTrue urlRespondsWithStatusCode200(it.getAttribute(TomcatServer.ROOT_URL) + "hello-world")
            }
        })
    }
}