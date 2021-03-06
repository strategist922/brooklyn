package brooklyn.event.feed.jmx;

import static brooklyn.util.GroovyJavaMethods.truth;
import static com.google.common.base.Preconditions.checkNotNull;
import groovy.time.TimeDuration;

import java.io.IOException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.InvalidAttributeValueException;
import javax.management.JMX;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.java.UsesJmx;
import brooklyn.util.GroovyJavaMethods;
import brooklyn.util.MutableMap;
import brooklyn.util.Time;
import brooklyn.util.crypto.SecureKeys;
import brooklyn.util.crypto.SslTrustUtils;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.exceptions.RuntimeInterruptedException;
import brooklyn.util.internal.LanguageUtils;
import brooklyn.util.internal.TimeExtras;
import brooklyn.util.jmx.jmxmp.JmxmpAgent;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

public class JmxHelper {

    protected static final Logger LOG = LoggerFactory.getLogger(JmxHelper.class);

    static { TimeExtras.init(); }

    public static final String JMX_URL_FORMAT = "service:jmx:rmi:///jndi/rmi://%s:%d/%s";
    // first host:port may be ignored, so above is sufficient, but not sure
    public static final String RMI_JMX_URL_FORMAT = "service:jmx:rmi://%s:%d/jndi/rmi://%s:%d/%s";
    // jmxmp
    public static final String JMXMP_URL_FORMAT = "service:jmx:jmxmp://%s:%d";
    
    // Tracks the MBeans we have failed to find, with a set keyed off the url
    private static final Map<String, Set<ObjectName>> notFoundMBeansByUrl = Collections.synchronizedMap(new WeakHashMap<String, Set<ObjectName>>());

    public static final Map<String, String> CLASSES = ImmutableMap.<String,String>builder()
            .put("Integer", Integer.TYPE.getName())
            .put("Long", Long.TYPE.getName())
            .put("Boolean", Boolean.TYPE.getName())
            .put("Byte", Byte.TYPE.getName())
            .put("Character", Character.TYPE.getName())
            .put("Double", Double.TYPE.getName())
            .put("Float", Float.TYPE.getName())
            .put("GStringImpl", String.class.getName())
            .put("LinkedHashMap", Map.class.getName())
            .put("TreeMap", Map.class.getName())
            .put("HashMap", Map.class.getName())
            .put("ConcurrentHashMap", Map.class.getName())
            .put("TabularDataSupport", TabularData.class.getName())
            .put("CompositeDataSupport", CompositeData.class.getName())
            .build();

    public static String toConnectorUrl(String host, Integer jmxRmiRegistryPort, Integer rmiServerPort, String context) {
        if (rmiServerPort != null && rmiServerPort > 0) {
            return String.format(RMI_JMX_URL_FORMAT, host, rmiServerPort, host, jmxRmiRegistryPort, context);
        } else {
            return String.format(JMX_URL_FORMAT, host, jmxRmiRegistryPort, context);
        }
    }

    public static String toConnectorUrl(EntityLocal entity) {
        String url = entity.getAttribute(Attributes.JMX_SERVICE_URL);
        if (url != null) {
            return url;
        } else {
            String host = checkNotNull(entity.getAttribute(Attributes.HOSTNAME));
            Integer jmxEntryPort = entity.getAttribute(Attributes.JMX_PORT);
            if (GroovyJavaMethods.truth(entity.getConfig(UsesJmx.JMX_SSL_ENABLED))) {
                url = String.format(JMXMP_URL_FORMAT, host, jmxEntryPort);
            } else {
                Integer rmiServerPort = entity.getAttribute(Attributes.RMI_SERVER_PORT);
                String context = entity.getAttribute(Attributes.JMX_CONTEXT);
                url = toConnectorUrl(host, jmxEntryPort, rmiServerPort, context);
            }
            if (entity.getEntityType().getSensors().contains(Attributes.JMX_SERVICE_URL))
                entity.setAttribute(Attributes.JMX_SERVICE_URL, url);
            return url;
        }
    }

    final EntityLocal entity;
    final String url;
    final String user;
    final String password;

    private volatile JMXConnector connector;
    private volatile MBeanServerConnection connection;
    private boolean triedConnecting;
    private boolean failedReconnecting;
    private long failedReconnectingTime;
    private int minTimeBetweenReconnectAttempts = 1000;

    // Tracks the MBeans we have failed to find for this JmsHelper's connection URL (so can log just once for each)
    private final Set<ObjectName> notFoundMBeans;

    public JmxHelper(EntityLocal entity) {
        this(toConnectorUrl(entity), entity, entity.getAttribute(Attributes.JMX_USER), entity.getAttribute(Attributes.JMX_PASSWORD));
    }
    
    // TODO split this in to two classes, one for entities, and one entity-neutral
    // (simplifying set of constructors below)
    
    public JmxHelper(String url) {
        this(url, null, null);
    }

    public JmxHelper(String url, String user, String password) {
        this(url, null, user, password);
    }
    
    public JmxHelper(String url, EntityLocal entity, String user, String password) {
        this.url = url;
        this.entity = entity;
        this.user = user;
        this.password = password;

        synchronized (notFoundMBeansByUrl) {
            Set<ObjectName> set = notFoundMBeansByUrl.get(url);
            if (set == null) {
                set = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<ObjectName, Boolean>()));
                notFoundMBeansByUrl.put(url, set);
            }
            notFoundMBeans = set;
        }
    }

    public void setMinTimeBetweenReconnectAttempts(int val) {
        minTimeBetweenReconnectAttempts = val;
    }
    
    public String getUrl(){
        return url;
    }

    // ============== connection related calls =======================

    //for tesing purposes
    protected MBeanServerConnection getConnection() {
        return connection;
    }

    /**
     * Checks if the JmxHelper is connected. Returned value could be stale as soon
     * as it is received.
     *
     * This method is thread safe.
     *
     * @return true if connected, false otherwise.
     */
    public boolean isConnected() {
        return connection!=null;
    }

    /**
     * Reconnects. If it already is connected, it disconnects first.
     *
     * @throws IOException
     */
    public synchronized void reconnectWithRetryDampened() throws IOException {
        // If we've already tried reconnecting very recently, don't try again immediately
        if (failedReconnecting) {
            long timeSince = (System.currentTimeMillis() - failedReconnectingTime);
            if (timeSince < minTimeBetweenReconnectAttempts) {
                String msg = "Not reconnecting to JMX at "+url+" because attempt failed "+Time.makeTimeString(timeSince)+" ago";
                throw new IllegalStateException(msg);
            }
        }
        
        reconnect();
    }
    
    public synchronized void reconnect() throws IOException {
        disconnect();

        try {
            connect();
            failedReconnecting = false;
        } catch (Exception e) {
            if (failedReconnecting) {
                if (LOG.isDebugEnabled()) LOG.debug("unable to re-connect to JMX url (repeated failure): {}: {}", url, e);
            } else {
                LOG.warn("unable to re-connect to JMX url: {}: {}", url, e);
                failedReconnecting = true;
            }
            failedReconnectingTime = System.currentTimeMillis();
            throw Throwables.propagate(e);
        }
    }

    /** attempts to connect immediately */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public synchronized void connect() throws IOException {
        if (connection != null) return;

        triedConnecting = true;
        if (connector != null) connector.close();
        JMXServiceURL serviceUrl = new JMXServiceURL(url);
        Map env = getConnectionEnvVars();
        try {
            connector = JMXConnectorFactory.connect(serviceUrl, env);
        } catch (NullPointerException npe) {
            //some software -- eg WSO2 -- will throw an NPE exception if the JMX connection can't be created, instead of an IOException.
            //this is a break of contract with the JMXConnectorFactory.connect method, so this code verifies if the NPE is
            //thrown by a known offender (wso2) and if so replaces the bad exception by a new IOException.
            //ideally WSO2 will fix this bug and we can remove this code.
            boolean thrownByWso2 = npe.getStackTrace()[0].toString().contains("org.wso2.carbon.core.security.CarbonJMXAuthenticator.authenticate");
            if (thrownByWso2) {
                throw new IOException("Failed to connect to url "+url+". NullPointerException is thrown, but replaced by an IOException to fix a WSO2 JMX problem", npe);
            } else {
                throw npe;
            }
        }
        connection = connector.getMBeanServerConnection();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Map getConnectionEnvVars() {
        Map env = new LinkedHashMap();
        
        if (truth(user) && truth(password)) {
            String[] creds = new String[] {user, password};
            env.put(JMXConnector.CREDENTIALS, creds);
        }
        
        if (entity!=null && GroovyJavaMethods.truth(entity.getConfig(UsesJmx.JMX_SSL_ENABLED))) {
            env.put("jmx.remote.profiles", JmxmpAgent.TLS_JMX_REMOTE_PROFILES);

            PrivateKey key = entity.getConfig(UsesJmx.JMX_SSL_ACCESS_KEY);
            Certificate cert = entity.getConfig(UsesJmx.JMX_SSL_ACCESS_CERT);
            KeyStore ks = SecureKeys.newKeyStore();
            try {
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                if (key!=null) {
                    ks.setKeyEntry("brooklyn-jmx-access", key, "".toCharArray(), new Certificate[] { cert });
                }
                kmf.init(ks, "".toCharArray());

                TrustManager tms = 
                        // TODO use root cert for trusting server
                        //trustStore!=null ? SecureKeys.getTrustManager(trustStore) : 
                        SslTrustUtils.TRUST_ALL;

                SSLContext ctx = SSLContext.getInstance("TLSv1");
                ctx.init(kmf.getKeyManagers(), new TrustManager[] { tms }, null);
                SSLSocketFactory ssf = ctx.getSocketFactory(); 
                env.put(JmxmpAgent.TLS_SOCKET_FACTORY_PROPERTY, ssf); 
                
            } catch (Exception e) {
                LOG.warn("Error setting key "+key+" for "+entity+": "+e, e);
            }
        }
        
        return env;
    }

    /**
     * Continuously attempts to connect for for at least the indicated amount of time; or indefinitely if -1. This method
     * is useful when you are not sure if the system you are trying to connect to already is up and running.
     *
     * This method doesn't throw an Exception, but returns true on success, false otherwise.
     *
     * TODO: What happens if already connected?
     *
     * @param timeoutMs
     * @return
     */
    public boolean connect(long timeoutMs) {
        if (LOG.isDebugEnabled()) LOG.debug("Connecting to JMX URL: {} ({})", url, ((timeoutMs == -1) ? "indefinitely" : timeoutMs+"ms timeout"));
        long startMs = System.currentTimeMillis();
        long endMs = (timeoutMs == -1) ? Long.MAX_VALUE : (startMs + timeoutMs);
        long currentTime = startMs;
        Throwable lastError = null;
        int attempt = 0;
        while (currentTime <= endMs) {
            currentTime = System.currentTimeMillis();
            if (attempt != 0) sleep(100); //sleep 100 to prevent thrashing and facilitate interruption
            if (LOG.isTraceEnabled()) LOG.trace("trying connection to {} at time {}", url, currentTime);

            try {
                connect();
                return true;
            } catch (Exception e) {
                if (shouldRetryOn(e)) {
                    if (LOG.isDebugEnabled()) LOG.debug("Attempt {} failed connecting to {} ({})", new Object[] {attempt + 1, url, e.getMessage()});
                    lastError = e;
                } else {
                    throw Throwables.propagate(e);
                }
            }
            attempt++;
        }
        LOG.warn("unable to connect to JMX url: "+url, lastError);
        return false;
    }

    private boolean shouldRetryOn(Exception e) {
        // Expect SecurityException, IOException, etc.
        // But can also see things like javax.naming.ServiceUnavailableException with WSO2 app-servers.
        // So let's not try to second guess strange behaviours that future entities will exhibit.
        //
        // However, if it was our request that was invalid then not worth retrying.
        
        if (e instanceof AttributeNotFoundException) return false;
        if (e instanceof InstanceAlreadyExistsException) return false;
        if (e instanceof InstanceNotFoundException) return false;
        if (e instanceof InvalidAttributeValueException) return false;
        if (e instanceof ListenerNotFoundException) return false;
        if (e instanceof MalformedObjectNameException) return false;
        if (e instanceof NotCompliantMBeanException) return false;

        return true;
    }

    /**
     * Disconnects. Method doesn't throw an exception.
     *
     * Can safely be called if already disconnected.
     *
     * This method is threadsafe.
     */
    public synchronized void disconnect() {
        triedConnecting = false;
        if (connector != null) {
            if (LOG.isDebugEnabled()) LOG.debug("Disconnecting from JMX URL {}", url);
            try {
                connector.close();
            } catch (Exception e) {
                LOG.warn("Caught exception disconnecting from JMX at {} ({})", url, e.getMessage());
            } finally {
                connector = null;
                connection = null;
            }
        }
    }

    /**
     * Gets a usable MBeanServerConnection.
     *
     * Method is threadsafe.
     *
     * @returns the MBeanServerConnection
     * @throws IllegalStateException if not connected.
     */
    private synchronized MBeanServerConnection getConnectionOrFail() {
        if (isConnected())
            return getConnection();

        if (triedConnecting) {
            throw new IllegalStateException("Failed to connect to JMX at "+url);
        } else {
            String msg = "Not connected (and not attempted to connect) to JMX at "+url+
                    (failedReconnecting ? (" (last reconnect failure at "+ Time.makeDateString(failedReconnectingTime) + ")") : "");
            throw new IllegalStateException(msg);
        }
    }

    private <T> T invokeWithReconnect(Callable<T> task) {
        try {
            return task.call();
        } catch (Exception e) {
            if (shouldRetryOn(e)) {
                try {
                    reconnectWithRetryDampened();
                    return task.call();
                } catch (Exception e2) {
                    throw Throwables.propagate(e2);
                }
            } else {
                throw Throwables.propagate(e);
            }
        }
    }

    // ====================== query related calls =======================================

    /**
     * Converts from an object name pattern to a real object name, by querying with findMBean; 
     * if no matching MBean can be found (or if more than one match found) then returns null.
     * If the supplied object name is not a pattern then just returns that. If the 
     */
    public ObjectName toLiteralObjectName(ObjectName objectName) {
        if (checkNotNull(objectName, "objectName").isPattern()) {
            ObjectInstance bean = findMBean(objectName);    
            return (bean != null) ? bean.getObjectName() : null;
        } else {
            return objectName;
        }
    }
    
    public Set<ObjectInstance> findMBeans(final ObjectName objectName) {
        return invokeWithReconnect(new Callable<Set<ObjectInstance>>() {
                public Set<ObjectInstance> call() throws Exception {
                    return getConnectionOrFail().queryMBeans(objectName, null);
                }});
    }

    public ObjectInstance findMBean(ObjectName objectName) {
        Set<ObjectInstance> beans = findMBeans(objectName);
        if (beans.size() == 1) {
            notFoundMBeans.remove(objectName);
            return Iterables.getOnlyElement(beans);
        } else {
            boolean changed = notFoundMBeans.add(objectName);

            if (beans.size() > 1) {
                if (changed) {
                    LOG.warn("JMX object name query returned {} values for {} at {}; ignoring all",
                            new Object[] {beans.size(), objectName.getCanonicalName(), url});
                } else {
                    if (LOG.isDebugEnabled()) LOG.debug("JMX object name query returned {} values for {} at {} (repeating); ignoring all", 
                            new Object[] {beans.size(), objectName.getCanonicalName(), url});
                }
            } else {
                if (changed) {
                    LOG.warn("JMX object {} not found at {}", objectName.getCanonicalName(), url);
                } else {
                    if (LOG.isDebugEnabled()) LOG.debug("JMX object {} not found at {} (repeating)", objectName.getCanonicalName(), url);
                }
            }
            return null;
        }
    }

    public Set<ObjectInstance> doesMBeanExistsEventually(final ObjectName objectName, TimeDuration timeout) {
        return doesMBeanExistsEventually(objectName, timeout.toMilliseconds(), TimeUnit.MILLISECONDS);
    }
    
    public Set<ObjectInstance> doesMBeanExistsEventually(final ObjectName objectName, long timeoutMillis) {
        return doesMBeanExistsEventually(objectName, timeoutMillis, TimeUnit.MILLISECONDS);
    }
    
    public Set<ObjectInstance> doesMBeanExistsEventually(String objectName, TimeDuration timeout) {
        return doesMBeanExistsEventually(createObjectName(objectName), timeout);
    }
    
    public Set<ObjectInstance> doesMBeanExistsEventually(String objectName, long timeout, TimeUnit timeUnit) {
        return doesMBeanExistsEventually(createObjectName(objectName), timeout, timeUnit);
    }

    /** returns set of beans found, with retry, empty set if none after timeout */
    public Set<ObjectInstance> doesMBeanExistsEventually(final ObjectName objectName, long timeout, TimeUnit timeUnit) {
        final long timeoutMillis = timeUnit.toMillis(timeout);
        final AtomicReference<Set<ObjectInstance>> beans = new AtomicReference<Set<ObjectInstance>>(Collections.<ObjectInstance>emptySet());
        try {
            LanguageUtils.repeatUntilSuccess(
                    MutableMap.of("timeout", timeoutMillis), 
                    "Wait for "+objectName,
            		new Callable<Boolean>() {
                        public Boolean call() {
                            connect(timeoutMillis);
                            beans.set(findMBeans(objectName));
                            return !beans.get().isEmpty();
                        }
                    });
            return beans.get();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    public void assertMBeanExistsEventually(ObjectName objectName, TimeDuration timeout) {
        assertMBeanExistsEventually(objectName, timeout.toMilliseconds(), TimeUnit.MILLISECONDS);
    }
    
    public void assertMBeanExistsEventually(ObjectName objectName, long timeoutMillis) {
        assertMBeanExistsEventually(objectName, timeoutMillis, TimeUnit.MILLISECONDS);
    }
    
    public void assertMBeanExistsEventually(ObjectName objectName, long timeout, TimeUnit timeUnit) {
        Set<ObjectInstance> beans = doesMBeanExistsEventually(objectName, timeout, timeUnit);
        if (beans.size() != 1) {
            throw new IllegalStateException("MBean "+objectName+" not found within "+timeout+
                    (beans.size() > 1 ? "; found multiple matches: "+beans : ""));
        }
    }

    /**
     * Returns a specific attribute for a JMX {@link ObjectName}.
     */
    public Object getAttribute(ObjectName objectName, final String attribute) {
        final ObjectName realObjectName = toLiteralObjectName(objectName);
        
        if (realObjectName != null) {
            Object result = invokeWithReconnect(new Callable<Object>() {
                    public Object call() throws Exception {
                        return getConnectionOrFail().getAttribute(realObjectName, attribute);
                    }});

            if (LOG.isTraceEnabled()) LOG.trace("From {}, for jmx attribute {}.{}, got value {}", new Object[] {url, objectName.getCanonicalName(), attribute, result});
            return result;
        } else {
            return null;
        }
    }

    public void setAttribute(String objectName, String attribute, Object val) {
        setAttribute(createObjectName(objectName), attribute, val);
    }

    public void setAttribute(ObjectName objectName, final String attribute, final Object val) {
        final ObjectName realObjectName = toLiteralObjectName(objectName);
        
        if (realObjectName != null) {
            invokeWithReconnect(new Callable<Void>() {
                    public Void call() throws Exception {
                        getConnectionOrFail().setAttribute(realObjectName, new javax.management.Attribute(attribute, val));
                        return null;
                    }});
            if (LOG.isTraceEnabled()) LOG.trace("From {}, for jmx attribute {}.{}, set value {}", new Object[] {url, objectName.getCanonicalName(), attribute, val});
        } else {
            if (LOG.isDebugEnabled()) LOG.debug("From {}, cannot set attribute {}.{}, because mbean not found", new Object[] {url, objectName.getCanonicalName(), attribute});
        }
    }

    /** @see #operation(ObjectName, String, Object ...) */
    public Object operation(String objectName, String method, Object... arguments) {
        return operation(createObjectName(objectName), method, arguments);
    }

    /**
     * Executes an operation on a JMX {@link ObjectName}.
     */
    public Object operation(ObjectName objectName, final String method, final Object... arguments) {
        final ObjectName realObjectName = toLiteralObjectName(objectName);
        final String[] signature = new String[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            Class<?> clazz = arguments[i].getClass();
            signature[i] = (CLASSES.containsKey(clazz.getSimpleName()) ? CLASSES.get(clazz.getSimpleName()) : clazz.getName());
        }
        
        Object result = invokeWithReconnect(new Callable<Object>() {
                public Object call() throws Exception {
                    return getConnectionOrFail().invoke(realObjectName, method, arguments, signature);
                }});

        if (LOG.isTraceEnabled()) LOG.trace("From {}, for jmx operation {}.{}({}), got value {}", new Object[] {url, realObjectName.getCanonicalName(), method, Arrays.asList(arguments), 
                result});
        return result;
    }

    public void addNotificationListener(String objectName, NotificationListener listener) {
        addNotificationListener(createObjectName(objectName), listener, null);
    }
    
    public void addNotificationListener(String objectName, NotificationListener listener, NotificationFilter filter) {
        addNotificationListener(createObjectName(objectName), listener, filter);
    }

    public void addNotificationListener(ObjectName objectName, NotificationListener listener) {
        addNotificationListener(objectName, listener, null);
    }
    
    public void addNotificationListener(final ObjectName objectName, final NotificationListener listener, final NotificationFilter filter) {
        invokeWithReconnect(new Callable<Void>() {
                public Void call() throws Exception {
                    getConnectionOrFail().addNotificationListener(objectName, listener, filter, null);
                    return null;
                }});
    }

    public void removeNotificationListener(String objectName, NotificationListener listener) {
        removeNotificationListener(createObjectName(objectName), listener);
    }

    public void removeNotificationListener(final ObjectName objectName, final NotificationListener listener) {
        removeNotificationListener(objectName, listener, null);
    }
    
    public void removeNotificationListener(final ObjectName objectName, final NotificationListener listener, final NotificationFilter filter) {
        if (isConnected()) invokeWithReconnect(new Callable<Void>() {
                public Void call() throws Exception {
                    getConnectionOrFail().removeNotificationListener(objectName, listener, filter, null);
                    return null;
                }});
    }

    public <M> M getProxyObject(String objectName, Class<M> mbeanInterface) {
        return getProxyObject(createObjectName(objectName), mbeanInterface);
    }

    public <M> M getProxyObject(ObjectName objectName, Class<M> mbeanInterface) {
        MBeanServerConnection connection = getConnectionOrFail();
        return JMX.newMBeanProxy(connection, objectName, mbeanInterface, false);
    }

    public static ObjectName createObjectName(String name) {
        try {
            return new ObjectName(name);
        } catch (MalformedObjectNameException e) {
            throw Throwables.propagate(e);
        }
    }
    
    private static void sleep(long sleepTimeMillis) {
        try {
            Thread.sleep(sleepTimeMillis);
        } catch (InterruptedException e) {
            throw new RuntimeInterruptedException(e);
        }
    }
}
