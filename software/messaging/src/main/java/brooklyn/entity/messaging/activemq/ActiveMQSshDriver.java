package brooklyn.entity.messaging.activemq;

import static java.lang.String.format;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import brooklyn.entity.basic.lifecycle.CommonCommands;
import brooklyn.entity.drivers.downloads.DownloadResolver;
import brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.MutableMap;
import brooklyn.util.NetworkUtils;

import com.google.common.collect.ImmutableMap;

public class ActiveMQSshDriver extends JavaSoftwareProcessSshDriver implements ActiveMQDriver {

    private String expandedInstallDir;

    public ActiveMQSshDriver(ActiveMQBrokerImpl entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    protected String getLogFileLocation() { 
        return String.format("%s/data/activemq.log", getRunDir());
    }

    @Override
    public Integer getOpenWirePort() { 
        return entity.getAttribute(ActiveMQBroker.OPEN_WIRE_PORT);
    }

    public String getMirrorUrl() {
        return entity.getConfig(ActiveMQBroker.MIRROR_URL);
    }
    
    private String getExpandedInstallDir() {
        if (expandedInstallDir == null) throw new IllegalStateException("expandedInstallDir is null; most likely install was not called");
        return expandedInstallDir;
    }
    
    @Override
    public void install() {
        DownloadResolver resolver = entity.getManagementContext().getEntityDownloadsRegistry().resolve(this);
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();
        expandedInstallDir = getInstallDir()+"/"+resolver.getUnpackedDirectorName(format("apache-activemq-%s", getVersion()));

        List<String> commands = new LinkedList<String>();
        commands.addAll(CommonCommands.downloadUrlAs(urls, saveAs));
        commands.add(CommonCommands.INSTALL_TAR);
        commands.add("tar xzfv "+saveAs);

        newScript(INSTALLING).
                failOnNonZeroResultCode().
                body.append(commands).execute();
    }

    @Override
    public void customize() {
        NetworkUtils.checkPortsValid(ImmutableMap.of("jmxPort", getJmxPort(), "openWirePort", getOpenWirePort()));
        newScript(CUSTOMIZING).
                body.append(
                String.format("cp -R %s/{bin,conf,data,lib,webapps} .", getExpandedInstallDir()),
                "sed -i.bk 's/\\[-z \"$JAVA_HOME\"]/\\[ -z \"$JAVA_HOME\" ]/g' bin/activemq",
                "sed -i.bk 's/broker /broker useJmx=\"true\" /g' conf/activemq.xml",
                String.format("sed -i.bk 's/managementContext createConnector=\"false\"/managementContext connectorPort=\"%s\"/g' conf/activemq.xml", getJmxPort()),
                String.format("sed -i.bk 's/tcp:\\/\\/0.0.0.0:61616\"/tcp:\\/\\/0.0.0.0:%s\"/g' conf/activemq.xml", getOpenWirePort())
                //disable persistence (this should be a flag -- but it seems to have no effect, despite ):
//                "sed -i.bk 's/broker /broker persistent=\"false\" /g' conf/activemq.xml",
                ).execute();
    }

    @Override
    public void launch() {
        newScript(ImmutableMap.of("usePidFile", false), LAUNCHING).
                body.append(
                "nohup ./bin/activemq start > ./data/activemq-extra.log 2>&1 &"
                ).execute();
    }

    public String getPidFile() {
        return "data/activemq.pid";
    }
    
    @Override
    public boolean isRunning() {
        return newScript(ImmutableMap.of("usePidFile", getPidFile()), CHECK_RUNNING).execute() == 0;
    }

    @Override
    public void stop() {
        newScript(ImmutableMap.of("usePidFile", getPidFile()), STOPPING).execute();
    }

    @Override
    public void kill() {
        newScript(ImmutableMap.of("usePidFile", getPidFile()), KILLING).execute();
    }

    public Map<String, String> getShellEnvironment() {
        Map<String,String> orig = super.getShellEnvironment();
        String hostname = getMachine().getAddress().getHostName();
        return MutableMap.<String,String>builder()
                .putAll(orig)
                .put("ACTIVEMQ_HOME", getRunDir())
                .put("ACTIVEMQ_PIDFILE", getPidFile())
                .put("ACTIVEMQ_OPTS", orig.get("JAVA_OPTS") != null ? orig.get("JAVA_OPTS") : "")
                .put("ACTIVEMQ_SUNJMX_CONTROL", String.format("--jmxurl service:jmx:rmi://%s:%s/jndi/rmi://%s:%s/jmxrmi", hostname, getRmiServerPort(), hostname, getJmxPort()))
                .put("JAVA_OPTS", "")
                .build();
    }
}
