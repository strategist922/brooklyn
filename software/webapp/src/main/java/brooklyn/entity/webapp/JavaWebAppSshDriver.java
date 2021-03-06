package brooklyn.entity.webapp;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.util.List;

import brooklyn.entity.basic.Attributes;
import brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import brooklyn.location.basic.SshMachineLocation;

public abstract class JavaWebAppSshDriver extends JavaSoftwareProcessSshDriver implements JavaWebAppDriver {

    public JavaWebAppSshDriver(JavaWebAppSoftwareProcessImpl entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    public JavaWebAppSoftwareProcessImpl getEntity() {
        return (JavaWebAppSoftwareProcessImpl) super.getEntity();
    }

    protected boolean isProtocolEnabled(String protocol) {
        List<String> protocols = getEnabledProtocols();
        for (String contender : protocols) {
            if (protocol.equalsIgnoreCase(contender)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> getEnabledProtocols() {
        return entity.getAttribute(JavaWebAppSoftwareProcessImpl.ENABLED_PROTOCOLS);
    }
    
    @Override
    public Integer getHttpPort() {
        return entity.getAttribute(Attributes.HTTP_PORT);
    }

    @Override
    public Integer getHttpsPort() {
        return entity.getAttribute(Attributes.HTTPS_PORT);
    }

    @Override
    public HttpsSslConfig getHttpsSslConfig() {
        return entity.getAttribute(WebAppServiceConstants.HTTPS_SSL_CONFIG);
    }

    protected String getSslKeystoreUrl() {
        HttpsSslConfig ssl = getHttpsSslConfig();
        return (ssl == null) ? null : ssl.getKeystoreUrl();
    }
    
    protected String getSslKeystorePassword() {
        HttpsSslConfig ssl = getHttpsSslConfig();
        return (ssl == null) ? null : ssl.getKeystorePassword();
    }
    
    protected String getSslKeyAlias() {
        HttpsSslConfig ssl = getHttpsSslConfig();
        return (ssl == null) ? null : ssl.getKeyAlias();
    }

    protected String inferRootUrl() {
        if (isProtocolEnabled("https")) {
            int port = getHttpsPort();
            checkNotNull(port, "HTTPS_PORT sensors not set; is an acceptable port available?");
            return String.format("https://%s:%s/", getHostname(), port);
        } else if (isProtocolEnabled("http")) {
            int port = getHttpPort();
            checkNotNull(port, "HTTP_PORT sensors not set; is an acceptable port available?");
            return String.format("http://%s:%s/", getHostname(), port);
        } else {
            throw new IllegalStateException("HTTP and HTTPS protocols not enabled for "+entity+"; enabled protocols are "+getEnabledProtocols());
        }
    }
    
    @Override
    public void postLaunch() {
        String rootUrl = inferRootUrl();
        entity.setAttribute(WebAppService.ROOT_URL, rootUrl);
    }

    /** 
     * if files should be placed on the server for deployment,
     * override this to be the sub-directory of the runDir where they should be stored
     * (or override getDeployDir() if they should be copied somewhere else,
     * and set this null);
     * if files are not copied to the server, but injected (e.g. JMX or uploaded)
     * then override {@link #deploy(String, String)} as appropriate,
     * using getContextFromDeploymentTargetName(targetName)
     * and override this to return null
     */
    protected abstract String getDeploySubdir();
    
    protected String getDeployDir() {
        if (getDeploySubdir()==null)
            throw new IllegalStateException("no deployment directory available for "+this);
        return getRunDir() + "/" + getDeploySubdir();
    }

    @Override
    public void deploy(File file) {
        deploy(file, null);
    }

    @Override
    public void deploy(File f, String targetName) {
        if (targetName == null) {
            targetName = f.getName();
        }
        deploy(f.toURI().toASCIIString(), targetName);
    }

    /**
     * Deploys a URL as a webapp at the appserver.
     *
     * Returns a token which can be used as an argument to undeploy,
     * typically the web context with leading slash where the app can be reached (just "/" for ROOT)
     *
     * @see JavaWebAppSoftwareProcess#deploy(String, String) for details of how input filenames are handled
     */
    @Override
    public String deploy(String url, String targetName) {
        String canonicalTargetName = getFilenameContextMapper().convertDeploymentTargetNameToFilename(targetName);
        String dest = getDeployDir() + "/" + canonicalTargetName;
        log.info("{} deploying {} to {}:{}", new Object[]{entity, url, getHostname(), dest});
        // create a backup
        getMachine().run(String.format("mv -f %s %s.bak > /dev/null 2>&1", dest, dest)); //back up old file/directory
        int result = copyResource(url, dest);
        log.debug("{} deployed {} to {}:{}: result {}", new Object[]{entity, url, getHostname(), dest, result});
        if (result!=0) log.warn("Problem deploying {} to {}:{} for {}: result {}", new Object[]{url, getHostname(), dest, entity, result}); 
        return getFilenameContextMapper().convertDeploymentTargetNameToContext(canonicalTargetName);
    }
    
    @Override
    public void undeploy(String targetName) {
        String dest = getDeployDir() + "/" + getFilenameContextMapper().convertDeploymentTargetNameToFilename(targetName);
        log.info("{} undeploying {}:{}", new Object[]{entity, getHostname(), dest});
        int result = getMachine().run(String.format("rm -f %s", dest));
        log.debug("{} undeployed {}:{}: result {}", new Object[]{entity, getHostname(), dest, result});
    }
    
    @Override
    public FilenameToWebContextMapper getFilenameContextMapper() {
        return new FilenameToWebContextMapper();
    }
}
