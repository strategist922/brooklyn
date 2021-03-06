package brooklyn.location.jclouds;

import static brooklyn.util.GroovyJavaMethods.elvis;
import static brooklyn.util.GroovyJavaMethods.truth;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.jclouds.compute.options.RunScriptOptions.Builder.overrideLoginCredentials;
import static org.jclouds.scriptbuilder.domain.Statements.exec;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.NodeMetadataBuilder;
import org.jclouds.compute.domain.OperatingSystem;
import org.jclouds.compute.domain.OsFamily;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.domain.TemplateBuilderSpec;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.domain.Credentials;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.ec2.compute.options.EC2TemplateOptions;
import org.jclouds.openstack.nova.v2_0.compute.options.NovaTemplateOptions;
import org.jclouds.rest.AuthorizationException;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.domain.Statements;
import org.jclouds.scriptbuilder.statements.login.AdminAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.config.ConfigKey.HasConfigKey;
import brooklyn.config.ConfigUtils;
import brooklyn.entity.basic.Entities;
import brooklyn.location.NoMachinesAvailableException;
import brooklyn.location.basic.LocationConfigUtils;
import brooklyn.location.basic.LocationCreationUtils;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.cloud.AbstractCloudMachineProvisioningLocation;
import brooklyn.location.jclouds.templates.PortableTemplateBuilder;
import brooklyn.util.KeyValueParser;
import brooklyn.util.MutableMap;
import brooklyn.util.Time;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.flags.TypeCoercions;
import brooklyn.util.internal.Repeater;
import brooklyn.util.internal.ssh.SshTool;
import brooklyn.util.text.Identifiers;
import brooklyn.util.text.Strings;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.io.Closeables;

/**
 * For provisioning and managing VMs in a particular provider/region, using jclouds.
 * Configuration flags are defined in {@link JcloudsLocationConfig}.
 */
public class JcloudsLocation extends AbstractCloudMachineProvisioningLocation implements JcloudsLocationConfig {

    // TODO After converting from Groovy to Java, this is now very bad code! It relies entirely on putting 
    // things into and taking them out of maps; it's not type-safe, and it's thus very error-prone.
    // In Groovy, that's considered ok but not in Java. 

    // TODO test (and fix) ability to set config keys from flags

    // TODO retire CredentialsFromEnv
    // TODO check how/if fields used in JCloudsLocationFactory
    // TODO need a way to define imageId (and others?) with a specific location

    // TODO we say config is inherited, but it isn't the case for many "deep" / jclouds properties
    // e.g. when we pass getConfigBag() in and decorate it with additional flags
    // (inheritance only works when we call getConfig in this class)
    
    public static final Logger LOG = LoggerFactory.getLogger(JcloudsLocation.class);
        
    public static final String ROOT_USERNAME = "root";
    /** these userNames are known to be the preferred/required logins in some common/default images 
     *  where root@ is not allowed to log in */
    public static final List<String> ROOT_ALIASES = ImmutableList.of("ubuntu", "ec2-user");
    public static final List<String> NON_ADDABLE_USERS = ImmutableList.<String>builder().add(ROOT_USERNAME).addAll(ROOT_ALIASES).build();
    
    private final Map<String,Map<String, ? extends Object>> tagMapping = Maps.newLinkedHashMap();
    private final Map<JcloudsSshMachineLocation,String> vmInstanceIds = Maps.newLinkedHashMap();

    /** typically wants at least ACCESS_IDENTITY and ACCESS_CREDENTIAL */
    public JcloudsLocation(Map<?,?> conf) {
        super(conf);
    }

    /** @deprecated since 0.5.0 use map-based constructor */
    @Deprecated
    public JcloudsLocation(String identity, String credential, String providerLocationId) {
        this(MutableMap.of(ACCESS_IDENTITY, identity, ACCESS_CREDENTIAL, credential, 
                CLOUD_REGION_ID, providerLocationId));
    }
    
    protected void configure(Map properties) {
        super.configure(properties);
        
        if (getConfigBag().containsKey("providerLocationId")) {
            LOG.warn("Using deprecated 'providerLocationId' key in "+this);
            if (!getConfigBag().containsKey(CLOUD_REGION_ID))
                getConfigBag().put(CLOUD_REGION_ID, (String)getConfigBag().getStringKey("providerLocationId"));
        }
        
        if (!truth(name)) {
            name = elvis(getProvider(), "unknown") +
                   (truth(getRegion()) ? ":"+getRegion() : "") +
                   (truth(getEndpoint()) ? ":"+getEndpoint() : "");
        }
        
        setCreationString(getConfigBag());
    }
    
    public JcloudsLocation newSubLocation(Map<?,?> newFlags) {
        return LocationCreationUtils.newSubLocation(newFlags, this);
    }

    @Override
    public String toString() {
        Object identity = getIdentity();
        String configDescription = getConfigBag().getDescription();
        if (configDescription!=null && configDescription.startsWith(getClass().getSimpleName()))
            return configDescription;
        return getClass().getSimpleName()+"["+name+":"+(identity != null ? identity : null)+
                (configDescription!=null ? "/"+configDescription : "") + "]";
    }
        
    public String getProvider() {
        return getConfig(CLOUD_PROVIDER);
    }

    public String getIdentity() {
        return getConfig(ACCESS_IDENTITY);
    }
    
    public String getCredential() {
        return getConfig(ACCESS_CREDENTIAL);
    }
    
    /** returns the location ID used by the provider, if set, e.g. us-west-1 */
    public String getRegion() {
        return getConfig(CLOUD_REGION_ID);
    }

    /** @deprecated since 0.5.0 use getRegion */
    public String getJcloudsProviderLocationId() {
        return getConfig(CLOUD_REGION_ID);
    }

    public String getEndpoint() {
        return LocationConfigUtils.getConfigCheckingDeprecatedAlternatives(getConfigBag(), 
                CLOUD_ENDPOINT, JCLOUDS_KEY_ENDPOINT);
    }

    public String getUser(ConfigBag config) {
        return LocationConfigUtils.getConfigCheckingDeprecatedAlternatives(getConfigBag(), 
                USER, JCLOUDS_KEY_USERNAME);
    }
    
    protected Collection<JcloudsLocationCustomizer> getCustomizers(ConfigBag setup) {
        JcloudsLocationCustomizer customizer = setup.get(JCLOUDS_LOCATION_CUSTOMIZER);
        Collection<JcloudsLocationCustomizer> customizers = setup.get(JCLOUDS_LOCATION_CUSTOMIZERS);
        if (customizer==null && customizers==null) return Collections.emptyList();
        List<JcloudsLocationCustomizer> result = new ArrayList<JcloudsLocationCustomizer>();
        if (customizer!=null) result.add(customizer);
        if (customizers!=null) result.addAll(customizers);
        return result;
    }

    public void setDefaultImageId(String val) {
        setConfig(DEFAULT_IMAGE_ID, val);
    }

    // TODO remove tagMapping, or promote it
    // (i think i favour removing it, letting the config come in from the entity)
    
    public void setTagMapping(Map<String,Map<String, ? extends Object>> val) {
        tagMapping.clear();
        tagMapping.putAll(val);
    }
    
    // TODO Decide on semantics. If I give "TomcatServer" and "Ubuntu", then must I get back an image that matches both?
    // Currently, just takes first match that it finds...
    public Map<String,Object> getProvisioningFlags(Collection<String> tags) {
        Map<String,Object> result = Maps.newLinkedHashMap();
        Collection<String> unmatchedTags = Lists.newArrayList();
        for (String it : tags) {
            if (truth(tagMapping.get(it)) && !truth(result)) {
                result.putAll(tagMapping.get(it));
            } else {
                unmatchedTags.add(it);
            }
        }
        if (unmatchedTags.size() > 0) {
            LOG.debug("Location {}, failed to match provisioning tags {}", this, unmatchedTags);
        }
        return result;
    }
    
    public static final Set<ConfigKey<?>> getAllSupportedProperties() {
        Set<String> configsOnClass = Sets.newLinkedHashSet(
            Iterables.transform(ConfigUtils.getStaticKeysOnClass(JcloudsLocation.class),
                new Function<HasConfigKey<?>,String>() {
                    @Override @Nullable
                    public String apply(@Nullable HasConfigKey<?> input) {
                        return input.getConfigKey().getName();
                    }
                }));
        Set<ConfigKey<?>> configKeysInList = ImmutableSet.<ConfigKey<?>>builder()
                .addAll(SUPPORTED_TEMPLATE_BUILDER_PROPERTIES.keySet())
                .addAll(SUPPORTED_TEMPLATE_OPTIONS_PROPERTIES.keySet())
                .build();
        Set<String> configsInList = Sets.newLinkedHashSet(
            Iterables.transform(configKeysInList,
            new Function<ConfigKey<?>,String>() {
                @Override @Nullable
                public String apply(@Nullable ConfigKey<?> input) {
                    return input.getName();
                }
            }));
        
        SetView<String> extrasInList = Sets.difference(configsInList, configsOnClass);
        // notInList is normal
        if (!extrasInList.isEmpty())
            LOG.warn("JcloudsLocation supported properties differs from config defined on class: " + extrasInList);
        return Collections.unmodifiableSet(configKeysInList);
    }

    public ComputeService getComputeService() {
        return getComputeService(MutableMap.of());
    }
    public ComputeService getComputeService(Map<?,?> flags) {
        return JcloudsUtil.findComputeService((flags==null || flags.isEmpty()) ? getConfigBag() :
            ConfigBag.newInstanceExtending(getConfigBag(), flags));
    }
    
    public Set<? extends ComputeMetadata> listNodes() {
        return listNodes(MutableMap.of());
    }
    public Set<? extends ComputeMetadata> listNodes(Map<?,?> flags) {
        return getComputeService(flags).listNodes();
    }

    /** attaches a string describing where something is being created 
     * (provider, region/location and/or endpoint, callerContext) */
    protected void setCreationString(ConfigBag config) {
        config.setDescription(elvis(config.get(CLOUD_PROVIDER), "unknown")+
                (config.containsKey(CLOUD_REGION_ID) ? ":"+config.get(CLOUD_REGION_ID) : "")+
                (config.containsKey(CLOUD_ENDPOINT) ? ":"+config.get(CLOUD_ENDPOINT) : "")+
                (config.containsKey(CALLER_CONTEXT) ? "@"+config.get(CALLER_CONTEXT) : ""));
    }

    // ----------------- obtaining a new machine ------------------------
    
    public JcloudsSshMachineLocation obtain() throws NoMachinesAvailableException {
        return obtain(MutableMap.of());
    }
    public JcloudsSshMachineLocation obtain(TemplateBuilder tb) throws NoMachinesAvailableException {
        return obtain(MutableMap.of(), tb);
    }
    public JcloudsSshMachineLocation obtain(Map<?,?> flags, TemplateBuilder tb) throws NoMachinesAvailableException {
        return obtain(MutableMap.builder().putAll(flags).put(TEMPLATE_BUILDER, tb).build());
    }
    /** core method for obtaining a VM using jclouds;
     * Map should contain CLOUD_PROVIDER and CLOUD_ENDPOINT or CLOUD_REGION, depending on the cloud,
     * as well as ACCESS_IDENTITY and ACCESS_CREDENTIAL,
     * plus any further properties to specify e.g. images, hardware profiles, accessing user
     * (for initial login, and a user potentially to create for subsequent ie normal access) */
    public JcloudsSshMachineLocation obtain(Map<?,?> flags) throws NoMachinesAvailableException {
        ConfigBag setup = ConfigBag.newInstanceExtending(getConfigBag(), flags);
        setCreationString(setup);
        
        final ComputeService computeService = JcloudsUtil.findComputeService(setup);
        String groupId = elvis(setup.get(GROUP_ID), generateGroupId(setup.get(CLOUD_PROVIDER)));
        NodeMetadata node = null;
        try {
            LOG.info("Creating VM in "+setup.getDescription()+" for "+this);

            Template template = buildTemplate(computeService, setup);

            if (!setup.getUnusedConfig().isEmpty())
                LOG.debug("NOTE: unused flags passed to obtain VM in "+setup.getDescription()+": "+
                        setup.getUnusedConfig());
            
            Set<? extends NodeMetadata> nodes = computeService.createNodesInGroup(groupId, 1, template);
            node = Iterables.getOnlyElement(nodes, null);
            LOG.debug("jclouds created {} for {}", node, setup.getDescription());
            if (node == null)
                throw new IllegalStateException("No nodes returned by jclouds create-nodes in " + setup.getDescription());

            LoginCredentials initialCredentials = extractVmCredentials(setup, node);
            if (initialCredentials != null)
                node = NodeMetadataBuilder.fromNodeMetadata(node).credentials(initialCredentials).build();
            else
                // only happens if something broke above...
                initialCredentials = LoginCredentials.fromCredentials(node.getCredentials());
            
            // Wait for the VM to be reachable over SSH
            waitForReachable(computeService, node, initialCredentials, setup);
            
            String vmHostname = getPublicHostname(node, setup);
            JcloudsSshMachineLocation sshLocByHostname = registerJcloudsSshMachineLocation(node, vmHostname, setup);
            
            // Apply any optional app-specific customization.
            for (JcloudsLocationCustomizer customizer : getCustomizers(setup)) {
                customizer.customize(computeService, sshLocByHostname);
            }
            
            return sshLocByHostname;
        } catch (RunNodesException e) {
            if (e.getNodeErrors().size() > 0) {
                node = Iterables.get(e.getNodeErrors().keySet(), 0);
            }
            LOG.error("Failed to start VM for {}: {}", setup.getDescription(), e.getMessage());
            throw Throwables.propagate(e);
        } catch (Exception e) {
            LOG.error("Failed to start VM for {}: {}", setup.getDescription(), e.getMessage());
            LOG.debug(Throwables.getStackTraceAsString(e));
            throw Throwables.propagate(e);
        } finally {
            //leave it open for reuse
//            computeService.getContext().close();
        }

    }

    // ------------- constructing the template, etc ------------------------
    
    private static interface CustomizeTemplateBuilder {
        void apply(TemplateBuilder tb, ConfigBag props, Object v);
    }
    
    private static interface CustomizeTemplateOptions {
        void apply(TemplateOptions tb, ConfigBag props, Object v);
    }
    
    /** properties which cause customization of the TemplateBuilder */
    public static final Map<ConfigKey<?>,CustomizeTemplateBuilder> SUPPORTED_TEMPLATE_BUILDER_PROPERTIES = ImmutableMap.<ConfigKey<?>,CustomizeTemplateBuilder>builder()
            .put(MIN_RAM, new CustomizeTemplateBuilder() {
                    public void apply(TemplateBuilder tb, ConfigBag props, Object v) {
                        tb.minRam(TypeCoercions.coerce(v, Integer.class));
                    }})
            .put(MIN_CORES, new CustomizeTemplateBuilder() {
                    public void apply(TemplateBuilder tb, ConfigBag props, Object v) {
                        tb.minCores(TypeCoercions.coerce(v, Double.class));
                    }})
            .put(HARDWARE_ID, new CustomizeTemplateBuilder() {
                    public void apply(TemplateBuilder tb, ConfigBag props, Object v) {
                        tb.hardwareId(((CharSequence)v).toString());
                    }})
            .put(IMAGE_ID, new CustomizeTemplateBuilder() {
                    public void apply(TemplateBuilder tb, ConfigBag props, Object v) {
                        tb.imageId(((CharSequence)v).toString());
                    }})
            .put(IMAGE_DESCRIPTION_REGEX, new CustomizeTemplateBuilder() {
                    public void apply(TemplateBuilder tb, ConfigBag props, Object v) {
                        tb.imageDescriptionMatches(((CharSequence)v).toString());
                    }})
            .put(IMAGE_NAME_REGEX, new CustomizeTemplateBuilder() {
                    public void apply(TemplateBuilder tb, ConfigBag props, Object v) {
                        tb.imageNameMatches(((CharSequence)v).toString());
                    }})
            .put(TEMPLATE_SPEC, new CustomizeTemplateBuilder() {
                public void apply(TemplateBuilder tb, ConfigBag props, Object v) {
                        tb.from(TemplateBuilderSpec.parse(((CharSequence)v).toString()));
                    }})
            .put(DEFAULT_IMAGE_ID, new CustomizeTemplateBuilder() {
                    public void apply(TemplateBuilder tb, ConfigBag props, Object v) {
                        /* done in the code, but included here so that it is in the map */
                    }})
            .put(TEMPLATE_BUILDER, new CustomizeTemplateBuilder() {
                    public void apply(TemplateBuilder tb, ConfigBag props, Object v) {
                        /* done in the code, but included here so that it is in the map */
                    }})
            .build();
   
    /** properties which cause customization of the TemplateOptions */
    public static final Map<ConfigKey<?>,CustomizeTemplateOptions> SUPPORTED_TEMPLATE_OPTIONS_PROPERTIES = ImmutableMap.<ConfigKey<?>,CustomizeTemplateOptions>builder()
            .put(SECURITY_GROUPS, new CustomizeTemplateOptions() {
                    public void apply(TemplateOptions t, ConfigBag props, Object v) {
                        if (t instanceof EC2TemplateOptions) {
                            String[] securityGroups = toStringArray(v);
                            ((EC2TemplateOptions)t).securityGroups(securityGroups);
                        } else if (t instanceof NovaTemplateOptions) {
                            String[] securityGroups = toStringArray(v);
                            ((NovaTemplateOptions)t).securityGroupNames(securityGroups);
                        } else {
                            LOG.info("ignoring securityGroups({}) in VM creation because not supported for cloud/type ({})", v, t);
                        }
                    }})
            .put(USER_DATA_UUENCODED, new CustomizeTemplateOptions() {
                    public void apply(TemplateOptions t, ConfigBag props, Object v) {
                        if (t instanceof EC2TemplateOptions) {
                            byte[] bytes = toByteArray(v);
                            ((EC2TemplateOptions)t).userData(bytes);
                        } else {
                            LOG.info("ignoring userData({}) in VM creation because not supported for cloud/type ({})", v, t);
                        }
                    }})
            .put(INBOUND_PORTS, new CustomizeTemplateOptions() {
                    public void apply(TemplateOptions t, ConfigBag props, Object v) {
                        int[] inboundPorts = toIntArray(v);
                        if (LOG.isDebugEnabled()) LOG.debug("opening inbound ports {} for {}", Arrays.toString(inboundPorts), t);
                        t.inboundPorts(inboundPorts);
                    }})
            .put(USER_METADATA, new CustomizeTemplateOptions() {
                    public void apply(TemplateOptions t, ConfigBag props, Object v) {
                        t.userMetadata(toMapStringString(v));
                    }})
            .put(EXTRA_PUBLIC_KEY_DATA_TO_AUTH, new CustomizeTemplateOptions() {
                    public void apply(TemplateOptions t, ConfigBag props, Object v) {
                        t.authorizePublicKey(((CharSequence)v).toString());
                    }})
            .put(RUN_AS_ROOT, new CustomizeTemplateOptions() {
                    public void apply(TemplateOptions t, ConfigBag props, Object v) {
                        t.runAsRoot((Boolean)v);
                    }})
            .put(LOGIN_USER, new CustomizeTemplateOptions() {
                    public void apply(TemplateOptions t, ConfigBag props, Object v) {
                        t.overrideLoginUser(((CharSequence)v).toString());
                    }})
            .put(LOGIN_USER_PASSWORD, new CustomizeTemplateOptions() {
                    public void apply(TemplateOptions t, ConfigBag props, Object v) {
                        t.overrideLoginPassword(((CharSequence)v).toString());
                    }})
            .put(LOGIN_USER_PRIVATE_KEY_DATA, new CustomizeTemplateOptions() {
                    public void apply(TemplateOptions t, ConfigBag props, Object v) {
                        t.overrideLoginPrivateKey(((CharSequence)v).toString());
                    }})
            .build();

    private static boolean listedAvailableTemplatesOnNoSuchTemplate = false;

    /** returns the jclouds Template which describes the image to be built */
    protected Template buildTemplate(ComputeService computeService, ConfigBag config) {
        TemplateBuilder templateBuilder = (TemplateBuilder) config.get(TEMPLATE_BUILDER);
        if (templateBuilder==null)
            templateBuilder = new PortableTemplateBuilder();
        else
            LOG.debug("jclouds using templateBuilder {} as base for provisioning in {} for {}", new Object[] {
                    templateBuilder, this, config.getDescription()});

        if (!Strings.isEmpty(config.get(CLOUD_REGION_ID))) {
            templateBuilder.locationId(config.get(CLOUD_REGION_ID));
        }
        
        // Apply the template builder and options properties
        for (Map.Entry<ConfigKey<?>, CustomizeTemplateBuilder> entry : SUPPORTED_TEMPLATE_BUILDER_PROPERTIES.entrySet()) {
            ConfigKey<?> name = entry.getKey();
            CustomizeTemplateBuilder code = entry.getValue();
            if (config.containsKey(name))
                code.apply(templateBuilder, config, config.get(name));
        }

        if (templateBuilder instanceof PortableTemplateBuilder) {
            ((PortableTemplateBuilder<?>)templateBuilder).attachComputeService(computeService);
            // do the default last, and only if nothing else specified (guaranteed to be a PTB if nothing else specified)
            if (truth(config.get(DEFAULT_IMAGE_ID))) {
                if (((PortableTemplateBuilder<?>)templateBuilder).isBlank()) {
                    templateBuilder.imageId(config.get(DEFAULT_IMAGE_ID).toString());
                }
            }
        }

        // Then apply any optional app-specific customization.
        for (JcloudsLocationCustomizer customizer : getCustomizers(config)) {
            customizer.customize(computeService, templateBuilder);
        }
        
        // Finally try to build the template
        Template template;
        try {
            template = templateBuilder.build();
            if (template==null) throw new NullPointerException("No template found (templateBuilder.build returned null)");
            LOG.debug(""+this+" got template "+template+" (image "+template.getImage()+")");
            if (template.getImage()==null) throw new NullPointerException("Template does not contain an image (templateBuilder.build returned invalid template)");
            if (isBadTemplate(template.getImage())) {
                // release candidates might break things :(   TODO get the list and score them
                if (templateBuilder instanceof PortableTemplateBuilder) {
                    if (((PortableTemplateBuilder<?>)templateBuilder).getOsFamily()==null) {
                        templateBuilder.osFamily(OsFamily.UBUNTU).osVersionMatches("11.04").os64Bit(true);
                        Template template2 = templateBuilder.build();
                        if (template2!=null) {
                            LOG.debug(""+this+" preferring template {} over {}", template2, template);
                            template = template2;
                        }
                    }
                }
            }
        } catch (AuthorizationException e) {
            LOG.warn("Error resolving template: not authorized (rethrowing: "+e+")");
            throw new IllegalStateException("Not authorized to access cloud "+this+" to resolve "+templateBuilder, e);
        } catch (Exception e) {
            try {
                synchronized (this) {
                    // delay subsequent log.warns (put in synch block) so the "Loading..." message is obvious
                    LOG.warn("Unable to match required VM template constraints "+templateBuilder+" when trying to provision VM in "+this+" (rethrowing): "+e);
                    if (!listedAvailableTemplatesOnNoSuchTemplate) {
                        listedAvailableTemplatesOnNoSuchTemplate = true;
                        LOG.info("Loading available images at "+this+" for reference...");
                        ConfigBag m1 = ConfigBag.newInstanceCopying(config);
                        if (m1.containsKey(IMAGE_ID)) {
                            // if caller specified an image ID, remove that, but don't apply default filters
                            m1.remove(IMAGE_ID);
                            // TODO use key
                            m1.putStringKey("anyOwner", true);
                        }
                        ComputeService computeServiceLessRestrictive = JcloudsUtil.findComputeService(m1);
                        Set<? extends Image> imgs = computeServiceLessRestrictive.listImages();
                        LOG.info(""+imgs.size()+" available images at "+this);
                        for (Image img: imgs) {
                            LOG.info(" Image: "+img);
                        }
                    }
                }
            } catch (Exception e2) {
                LOG.warn("Error loading available images to report (following original error matching template which will be rethrown): "+e2, e2);
                throw new IllegalStateException("Unable to access cloud "+this+" to resolve "+templateBuilder, e);
            }
            throw new IllegalStateException("Unable to match required VM template constraints "+templateBuilder+" when trying to provision VM in "+this+". See list of images in log.", e);
        }
        TemplateOptions options = template.getOptions();
        
        for (Map.Entry<ConfigKey<?>, CustomizeTemplateOptions> entry : SUPPORTED_TEMPLATE_OPTIONS_PROPERTIES.entrySet()) {
            ConfigKey<?> key = entry.getKey();
            CustomizeTemplateOptions code = entry.getValue();
            if (config.containsKey(key))
                code.apply(options, config, config.get(key));
        }
                
        // Setup the user
        
        //NB: we ignore private key here because, by default we probably should not be installing it remotely;
        //also, it may not be valid for first login (it is created before login e.g. on amazon, so valid there;
        //but not elsewhere, e.g. on rackspace)
        String user = getUser(config);
        String loginUser = config.get(LOGIN_USER);
        Boolean dontCreateUser = config.get(DONT_CREATE_USER);
        String publicKeyData = LocationConfigUtils.getPublicKeyData(config);
        if (truth(user) && !NON_ADDABLE_USERS.contains(user) && 
                !user.equals(loginUser) && !truth(dontCreateUser)) {
            // create the user, if it's not the login user and not a known root-level user
            // by default we now give these users sudo privileges.
            // if you want something else, that can be specified manually, 
            // e.g. using jclouds UserAdd.Builder, with RunScriptOnNode, or template.options.runScript(xxx)
            // (if that is a common use case, we could expose a property here)
            // note AdminAccess requires _all_ fields set, due to http://code.google.com/p/jclouds/issues/detail?id=1095
            AdminAccess.Builder adminBuilder = AdminAccess.builder().
                    adminUsername(user).
                    grantSudoToAdminUser(true);
            adminBuilder.adminPassword(truth(config.get(PASSWORD)) ? config.get(PASSWORD) : Identifiers.makeRandomId(12));
            if (publicKeyData!=null)
                adminBuilder.authorizeAdminPublicKey(true).adminPublicKey(publicKeyData);
            else
                adminBuilder.authorizeAdminPublicKey(false).adminPublicKey("ignored").lockSsh(true);
            adminBuilder.installAdminPrivateKey(false).adminPrivateKey("ignored");
            adminBuilder.resetLoginPassword(true).loginPassword(Identifiers.makeRandomId(12));
            adminBuilder.lockSsh(true);
            options.runScript(adminBuilder.build());
        } else if (truth(publicKeyData)) {
            // don't create the user, but authorize the public key for the default user
            options.authorizePublicKey(publicKeyData);
        }
        
        // Finally, apply any optional app-specific customization.
        for (JcloudsLocationCustomizer customizer : getCustomizers(config)) {
            customizer.customize(computeService, options);
        }
        
        LOG.debug("jclouds using template {} / options {} to provision machine in {}", new Object[] {
                template, options, config.getDescription()});
        return template;
    }

    // TODO we really need a better way to decide which images are preferred
    // though to be fair this is similar to jclouds strategies
    // we fall back to the "bad" images (^^^ above) if we can't find a good one above
    // ---
    // but in practice in AWS images name "rc-" and from "alphas" break things badly
    // (apt repos don't work, etc)
    private boolean isBadTemplate(Image image) {
        String name = image.getName();
        if (name != null && name.contains(".rc-")) return true;
        OperatingSystem os = image.getOperatingSystem();
        if (os!=null) {
            String description = os.getDescription();
            if (description != null && description.contains("-alpha"))
                return true;
        }
        return false;
    }

    // ----------------- rebinding to existing machine ------------------------

    public JcloudsSshMachineLocation rebindMachine(NodeMetadata metadata) throws NoMachinesAvailableException {
        return rebindMachine(MutableMap.of(), metadata);
    }
    public JcloudsSshMachineLocation rebindMachine(Map flags, NodeMetadata metadata) throws NoMachinesAvailableException {
        ConfigBag setup = ConfigBag.newInstanceExtending(getConfigBag(), flags);
        if (!setup.containsKey("id")) setup.putStringKey("id", metadata.getId());
        setHostnameUpdatingCredentials(setup, metadata);
        return rebindMachine(setup);
    }
    
    /**
     * Brings an existing machine with the given details under management.
     * <p>
     * Required fields are:
     * <ul>
     *   <li>id: the jclouds VM id, e.g. "eu-west-1/i-5504f21d" (NB this is @see JcloudsSshMachineLocation#getJcloudsId() not #getId())
     *   <li>hostname: the public hostname or IP of the machine, e.g. "ec2-176-34-93-58.eu-west-1.compute.amazonaws.com"
     *   <li>userName: the username for ssh'ing into the machine
     * <ul>
     */
    public JcloudsSshMachineLocation rebindMachine(ConfigBag setup) throws NoMachinesAvailableException {
        try {
            if (setup.getDescription()==null) setCreationString(setup);
            
            String id = (String) checkNotNull(setup.getStringKey("id"), "id");
            String hostname = (String) checkNotNull(setup.getStringKey("hostname"), "hostname");
            String user = checkNotNull(getUser(setup), "user");
            
            LOG.info("Rebinding to VM {} ({}@{}), in jclouds location for provider {}", 
                    new Object[] {id, user, hostname, getProvider()});
            
            // can we allow re-use ?  previously didn't
            ComputeService computeService = JcloudsUtil.findComputeService(setup, false);
            NodeMetadata node = computeService.getNodeMetadata(id);
            if (node == null) {
                throw new IllegalArgumentException("Node not found with id "+id);
            }
    
            String pkd = LocationConfigUtils.getPrivateKeyData(setup);
            if (truth(pkd)) {
                LoginCredentials expectedCredentials = LoginCredentials.fromCredentials(new Credentials(user, pkd));
                //override credentials
                node = NodeMetadataBuilder.fromNodeMetadata(node).credentials(expectedCredentials).build();
            }
            // TODO confirm we can SSH ?

            return registerJcloudsSshMachineLocation(node, hostname, setup);
            
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }
    public JcloudsSshMachineLocation rebindMachine(Map flags) throws NoMachinesAvailableException {
        return rebindMachine(new ConfigBag().putAll(flags));
    }

    // -------------- create the SshMachineLocation instance, and connect to it etc ------------------------
    
    protected JcloudsSshMachineLocation registerJcloudsSshMachineLocation(NodeMetadata node, String vmHostname, ConfigBag setup) throws IOException {
        JcloudsSshMachineLocation machine = createJcloudsSshMachineLocation(node, vmHostname, setup);
        machine.setParentLocation(this);
        vmInstanceIds.put(machine, node.getId());
        return machine;
    }

    protected JcloudsSshMachineLocation createJcloudsSshMachineLocation(NodeMetadata node, String vmHostname, ConfigBag setup) throws IOException {
        Map<?,?> sshConfig = extractSshConfig(setup, node);
        if (LOG.isDebugEnabled())
            LOG.debug("creating JcloudsSshMachineLocation representation for {}@{} for {} with {}", 
                    new Object[] {
                            getUser(setup), 
                            vmHostname, 
                            setup.getDescription(), 
                            Entities.sanitize(sshConfig)
                    });
        return new JcloudsSshMachineLocation(
                MutableMap.builder()
                        .put("address", vmHostname) 
                        .put("displayName", vmHostname)
                        .put("user", getUser(setup))
                        // don't think "config" does anything
                        .putAll(sshConfig)
                        // FIXME remove "config" -- inserted directly, above
                        .put("config", sshConfig)
                        
                        .build(),
                this, 
                node);
    }

    // -------------- give back the machines------------------
    
    protected Map<String,Object> extractSshConfig(ConfigBag setup, NodeMetadata node) {
        ConfigBag nodeConfig = new ConfigBag();
        if (node!=null) {
            nodeConfig.putIfNotNull(PASSWORD, node.getCredentials().getPassword());
            nodeConfig.putIfNotNull(PRIVATE_KEY_DATA, node.getCredentials().getPrivateKey());
        }
        return extractSshConfig(setup, nodeConfig).getAllConfigRaw();
    }

    public void release(SshMachineLocation machine) {
        String instanceId = vmInstanceIds.remove(machine);
        if (!truth(instanceId)) {
            throw new IllegalArgumentException("Unknown machine "+machine);
        }
        
        LOG.info("Releasing machine {} in {}, instance id {}", new Object[] {machine, this, instanceId});
        
        removeChildLocation(machine);
        ComputeService computeService = null;
        try {
            computeService = JcloudsUtil.findComputeService(getConfigBag());
            computeService.destroyNode(instanceId);
        } catch (Exception e) {
            LOG.error("Problem releasing machine "+machine+" in "+this+", instance id "+instanceId+
                    "; discarding instance and continuing...", e);
            Throwables.propagate(e);
        } finally {
        /*
         //don't close
            if (computeService != null) {
                try {
                    computeService.getContext().close();
                } catch (Exception e) {
                    LOG.error "Problem closing compute-service's context; continuing...", e
                }
            }
         */
        }
    }

    // ------------ support methods --------------------

    public static String generateGroupId(String provider) {
        // In jclouds 1.5, there are strict rules for group id: it must be DNS compliant, and no more than 15 characters
        // TODO surely this can be overridden!  it's so silly being so short in common places ... or at least set better metadata?
        // TODO smarter length-aware system
        String user = System.getProperty("user.name");
        String rand = Identifiers.makeRandomId(6);
        String result = "brooklyn-" + ("brooklyn".equals(user) ? "" : user+"-") + rand;
        if ("vcloud".equals(provider)) {
            rand = Identifiers.makeRandomId(2);
            result = "br-" + Strings.maxlen(user, 4) + "-" + rand;
        }

        return result.toLowerCase();
    }

    protected LoginCredentials extractVmCredentials(ConfigBag setup, NodeMetadata node) {
        LoginCredentials expectedCredentials = setup.get(CUSTOM_CREDENTIALS);
        if (expectedCredentials!=null) {
            //set userName and other data, from these credentials
            Object oldUsername = setup.put(USER, expectedCredentials.getUser());
            LOG.debug("node {} username {} / {} (customCredentials)", new Object[] { node, expectedCredentials.getUser(), oldUsername });
            if (truth(expectedCredentials.getPassword())) setup.put(PASSWORD, expectedCredentials.getPassword());
            if (truth(expectedCredentials.getPrivateKey())) setup.put(PRIVATE_KEY_DATA, expectedCredentials.getPrivateKey());
        }
        if (expectedCredentials==null) {
            expectedCredentials = LoginCredentials.fromCredentials(node.getCredentials());
            String user = getUser(setup);
            LOG.debug("node {} username {} / {} (jclouds)", new Object[] { node, user, expectedCredentials.getUser() });
            if (truth(expectedCredentials.getUser())) {
                if (user==null) {
                    setup.put(USER, user = expectedCredentials.getUser());
                } else if ("root".equals(user) && ROOT_ALIASES.contains(expectedCredentials.getUser())) {
                    // deprecated, we used to default username to 'root'; now we leave null, then use autodetected credentials if no user specified
                    // 
                    LOG.warn("overriding username 'root' in favour of '"+expectedCredentials.getUser()+"' at {}; this behaviour may be removed in future", node);
                    setup.put(USER, user = expectedCredentials.getUser());
                }
            }
            //override credentials
            String pkd = elvis(LocationConfigUtils.getPrivateKeyData(setup), expectedCredentials.getPrivateKey());
            String pwd = elvis(setup.get(PASSWORD), expectedCredentials.getPassword());
            if (user==null || (pkd==null && pwd==null)) {
                String missing = (user==null ? "user" : "credential");
                LOG.warn("Not able to determine "+missing+" for "+this+" at "+node+"; will likely fail subsequently");
                expectedCredentials = null;
            } else {
                LoginCredentials.Builder expectedCredentialsBuilder = LoginCredentials.builder().
                        user(user);
                if (pkd!=null) expectedCredentialsBuilder.privateKey(pkd);
                if (pwd!=null && pkd==null) expectedCredentialsBuilder.password(pwd);
                expectedCredentials = expectedCredentialsBuilder.build();        
            }
        }
        return expectedCredentials;
    }

    protected void waitForReachable(final ComputeService computeService, NodeMetadata node, LoginCredentials expectedCredentials, ConfigBag setup) {
        String waitForSshable = setup.get(WAIT_FOR_SSHABLE);
        if (waitForSshable!=null && "false".equalsIgnoreCase(waitForSshable)) {
            LOG.debug("Skipping ssh check for {} ({}) due to config waitForSshable=false", node, setup.getDescription());
            return;
        }
        
        String vmIp = JcloudsUtil.getFirstReachableAddress(this.getComputeService().getContext(), node);
        if (vmIp==null) LOG.warn("Unable to extract IP for "+node+" ("+setup.getDescription()+"): subsequent connection attempt will likely fail");
        
        final NodeMetadata nodeRef = node;
        final LoginCredentials expectedCredentialsRef = expectedCredentials;
        long delayMs = -1;
        try {
            delayMs = Time.parseTimeString(""+waitForSshable);
        } catch (Exception e) { /* normal if 'true'; just fall back to default */ }
        if (delayMs<0) 
            delayMs = Time.parseTimeString(WAIT_FOR_SSHABLE.getDefaultValue());
        
        String user = expectedCredentialsRef.getUser();
        LOG.info("Started VM in {}; waiting {} for it to be sshable on {}@{}{}",
                new Object[] {
                        setup.getDescription(),Time.makeTimeString(delayMs),
                        user, vmIp, Objects.equal(user, getUser(setup)) ? "" : " (setup user is different: "+getUser(setup)+")"
                });
        
        boolean reachable = new Repeater()
            .repeat()
            .every(1,SECONDS)
            .until(new Callable<Boolean>() {
                public Boolean call() {
                    Statement statement = Statements.newStatementList(exec("hostname"));
                    // NB this assumes passwordless sudo !
                    ExecResponse response = computeService.runScriptOnNode(nodeRef.getId(), statement, 
                            overrideLoginCredentials(expectedCredentialsRef));
                    return response.getExitStatus() == 0;
                }})
            .limitTimeTo(delayMs, MILLISECONDS)
            .run();

        if (!reachable) {
            throw new IllegalStateException("SSH failed for "+
                    user+"@"+vmIp+" ("+setup.getDescription()+") after waiting "+
                    Time.makeTimeString(delayMs));
        }
    }
    
    // -------------------- hostnames ------------------------
    // hostnames are complicated, but irregardless, this code could be cleaned up!

    protected void setHostnameUpdatingCredentials(ConfigBag setup, NodeMetadata metadata) {
        List<String> usersTried = new ArrayList<String>();
        
        String originalUser = getUser(setup);
        if (truth(originalUser)) {
            if (setHostname(setup, metadata, false)) return;
            usersTried.add(originalUser);
        }
        
        LoginCredentials credentials = metadata.getCredentials();
        if (truth(credentials)) {
            if (truth(credentials.getUser())) setup.put(USER, credentials.getUser());
            if (truth(credentials.getPrivateKey())) setup.put(PRIVATE_KEY_DATA, credentials.getPrivateKey());
            if (setHostname(setup, metadata, false)) {
                if (originalUser!=null && !originalUser.equals(getUser(setup))) {
                    LOG.warn("Switching to cloud-specified user at "+metadata+" as "+getUser(setup)+" (failed to connect using: "+usersTried+")");
                }
                return;
            }
            usersTried.add(getUser(setup));
        }
        
        for (String u: NON_ADDABLE_USERS) {
            setup.put(USER, u);
            if (setHostname(setup, metadata, false)) {
                LOG.warn("Auto-detected user at "+metadata+" as "+getUser(setup)+" (failed to connect using: "+usersTried+")");
                return;
            }
            usersTried.add(getUser(setup));
        }
        // just repeat, so we throw exception
        LOG.warn("Failed to log in to "+metadata+", tried as users "+usersTried+" (throwing original exception)");
        setup.put(USER, originalUser);
        setHostname(setup, metadata, true);
    }
    
    protected boolean setHostname(ConfigBag setup, NodeMetadata metadata, boolean rethrow) {
        try {
            setup.put(SshTool.PROP_HOST, getPublicHostname(metadata, setup));
            return true;
        } catch (Exception e) {
            if (rethrow) {
                LOG.warn("couldn't connect to "+metadata+" when trying to discover hostname (rethrowing): "+e);
                throw Throwables.propagate(e);                
            }
            return false;
        }
    }

    String getPublicHostname(NodeMetadata node, ConfigBag setup) {
        if ("aws-ec2".equals(setup != null ? setup.get(CLOUD_PROVIDER) : null)) {
            String vmIp = null;
            try {
                vmIp = JcloudsUtil.getFirstReachableAddress(this.getComputeService().getContext(), node);
            } catch (Exception e) {
                LOG.warn("Error reaching aws-ec2 instance on port 22; falling back to jclouds metadata for address", e);
            }
            if (vmIp != null) {
                try {
                    return getPublicHostnameAws(vmIp, setup);
                } catch (Exception e) {
                    LOG.warn("Error querying aws-ec2 instance over ssh for its hostname; falling back to first reachable IP", e);
                    return vmIp;
                }
            }
        }
        
        return getPublicHostnameGeneric(node, setup);
    }
    
    private String getPublicHostnameGeneric(NodeMetadata node, @Nullable ConfigBag setup) {
        //prefer the public address to the hostname because hostname is sometimes wrong/abbreviated
        //(see that javadoc; also e.g. on rackspace, the hostname lacks the domain)
        //TODO would it be better to prefer hostname, but first check that it is resolvable? 
        if (truth(node.getPublicAddresses())) {
            return node.getPublicAddresses().iterator().next();
        } else if (truth(node.getHostname())) {
            return node.getHostname();
        } else if (truth(node.getPrivateAddresses())) {
            return node.getPrivateAddresses().iterator().next();
        } else {
            return null;
        }
    }
    
    private String getPublicHostnameAws(String ip, ConfigBag setup) {
        SshMachineLocation sshLocByIp = null;
        try {
            ConfigBag sshConfig = extractSshConfig(setup, new ConfigBag());
            
            // TODO messy way to get an SSH session 
            sshLocByIp = new SshMachineLocation(MutableMap.of("address", ip, "user", getUser(setup), 
                    "config", sshConfig.getAllConfig()));
            
            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            ByteArrayOutputStream errStream = new ByteArrayOutputStream();
            int exitcode = sshLocByIp.run(
                    MutableMap.of("out", outStream, "err", errStream), 
                    "echo `curl --silent --retry 20 http://169.254.169.254/latest/meta-data/public-hostname`; exit");
            String outString = new String(outStream.toByteArray());
            String[] outLines = outString.split("\n");
            for (String line : outLines) {
                if (line.startsWith("ec2-")) return line.trim();
            }
            throw new IllegalStateException("Could not obtain hostname for vm "+ip+"; exitcode="+exitcode+"; stdout="+outString+"; stderr="+new String(errStream.toByteArray()));
        } finally {
            Closeables.closeQuietly(sshLocByIp);
        }
    }
    
    // ------------ static converters (could go to a new file) ------------------
    
    public static File asFile(Object o) {
        if (o instanceof File) return (File)o;
        if (o == null) return null;
        return new File(o.toString());
    }

    public static String fileAsString(Object o) {
        if (o instanceof String) return (String)o;
        if (o instanceof File) return ((File)o).getAbsolutePath();
        if (o==null) return null;
        return o.toString();
    }


    protected static double toDouble(Object v) {
        if (v instanceof Number) {
            return ((Number)v).doubleValue();
        } else {
            throw new IllegalArgumentException("Invalid type for double: "+v+" of type "+v.getClass());
        }
    }

    protected static int[] toIntArray(Object v) {
        int[] result;
        if (v instanceof Iterable) {
            result = new int[Iterables.size((Iterable<?>)v)];
            int i = 0;
            for (Object o : (Iterable<?>)v) {
                result[i++] = (Integer) o;
            }
        } else if (v instanceof int[]) {
            result = (int[]) v;
        } else if (v instanceof Object[]) {
            result = new int[((Object[])v).length];
            for (int i = 0; i < result.length; i++) {
                result[i] = (Integer) ((Object[])v)[i];
            }
        } else if (v instanceof Integer) {
            result = new int[] {(Integer)v};
        } else {
            throw new IllegalArgumentException("Invalid type for int[]: "+v+" of type "+v.getClass());
        }
        return result;
    }

    protected static String[] toStringArray(Object v) {
        Collection<String> result = Lists.newArrayList();
        if (v instanceof Iterable) {
            for (Object o : (Iterable<?>)v) {
                result.add(o.toString());
            }
        } else if (v instanceof Object[]) {
            for (int i = 0; i < ((Object[])v).length; i++) {
                result.add(((Object[])v)[i].toString());
            }
        } else if (v instanceof String) {
            result.add((String) v);
        } else {
            throw new IllegalArgumentException("Invalid type for String[]: "+v+" of type "+v.getClass());
        }
        return result.toArray(new String[0]);
    }
    
    protected static byte[] toByteArray(Object v) {
        if (v instanceof byte[]) {
            return (byte[]) v;
        } else if (v instanceof CharSequence) {
            return v.toString().getBytes();
        } else {
            throw new IllegalArgumentException("Invalid type for byte[]: "+v+" of type "+v.getClass());
        }
    }
    
    // Handles GString
    protected static Map<String,String> toMapStringString(Object v) {
        if (v instanceof Map<?,?>) {
            Map<String,String> result = Maps.newLinkedHashMap();
            for (Map.Entry<?,?> entry : ((Map<?,?>)v).entrySet()) {
                String key = ((CharSequence)entry.getKey()).toString();
                String value = ((CharSequence)entry.getValue()).toString();
                result.put(key, value);
            }
            return result;
        } else if (v instanceof CharSequence) {
            return KeyValueParser.parseMap(v.toString());
        } else {
            throw new IllegalArgumentException("Invalid type for Map<String,String>: "+v+" of type "+v.getClass());
        }
    }
}
