package brooklyn.entity.basic

import java.lang.reflect.Field
import java.util.Collection
import java.util.Map
import java.util.concurrent.CopyOnWriteArrayList

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.Application
import brooklyn.entity.Effector
import brooklyn.entity.Entity
import brooklyn.entity.EntityClass
import brooklyn.entity.Group
import brooklyn.entity.ParameterType
import brooklyn.event.AttributeSensor
import brooklyn.event.EventListener
import brooklyn.event.Sensor
import brooklyn.event.adapter.PropertiesSensorAdapter
import brooklyn.event.basic.AttributeMap
import brooklyn.event.basic.ConfigKey
import brooklyn.location.Location
import brooklyn.management.ManagementContext
import brooklyn.management.SubscriptionContext
import brooklyn.management.Task
import brooklyn.management.internal.LocalManagementContext
import brooklyn.management.internal.LocalSubscriptionContext
import brooklyn.util.internal.LanguageUtils
import brooklyn.util.task.ExecutionContext

/**
 * Default {@link Entity} implementation
 * 
 * Provides several common fields ({@link #name}, {@link #id});
 * also provides a map {@link #config} which contains arbitrary fields.
 * <p>
 * Fields in config can be accessed (get and set) without referring to config,
 * (through use of propertyMissing). Note that config is typically inherited
 * by children, whereas the fields are not. (Attributes cannot be so accessed,
 * nor are they inherited.)
 *
 * @author alex, aled
 */
public abstract class AbstractEntity implements EntityLocal, GroovyInterceptable {
    private static final Logger log = LoggerFactory.getLogger(AbstractEntity.class);
 
    String id = LanguageUtils.newUid();
    Map<String,Object> presentationAttributes = [:]
    String displayName;
    final Collection<Group> groups = new CopyOnWriteArrayList<Group>()
    volatile Application application
    Collection<Location> locations = []
    Group owner
 
    protected transient volatile ExecutionContext execution
    protected transient volatile SubscriptionContext subscription
    protected transient volatile LocalManagementContext management = LocalManagementContext.getContext()
 
    /**
     * The sensor-attribute values of this entity. Updating this map should be done
     * via getAttribute/updateAttribute; it will automatically emit an attribute-change event.
     */
    protected final AttributeMap attributesInternal = new AttributeMap(this)
	
	//ENGR-1458  interesting to use property change. if it works great. 
	//if there are any issues with it consider instead just making attributesInternal private,
	//and forcing all changes to attributesInternal to go through update(AttributeSensor,...)
	//and do the publishing there...  (please leave this comment here for several months until we know... it's Jun 2011 right now)
    protected final PropertiesSensorAdapter propertiesAdapter = new PropertiesSensorAdapter(this, attributes)

    /*
     * TODO An alternative implementation approach would be to have:
     *   setOwner(Entity o, Map<ConfigKey,Object> inheritedConfig=[:])
     * The idea is that the owner could in theory decide explicitly what in its config
     * would be shared.
     * I (Aled) am undecided as to whether that would be better...
     */
    /**
     * Map of configuration information that is defined at start-up time for the entity. These
     * configuration parameters are shared and made accessible to the "owned children" of this
     * entity.
     */
    protected final Map<ConfigKey,Object> inheritableConfig = [:]
    
    public AbstractEntity(Map flags=[:]) {
        Entity suppliedOwner = flags.remove('owner')
        Map<ConfigKey,Object> suppliedInheritableConfig = flags.remove('inheritableConfig')

        if (suppliedInheritableConfig) inheritableConfig.putAll(suppliedInheritableConfig)
        
        //place named-arguments into corresponding fields if they exist, otherwise put into attributes map
        this.attributes << LanguageUtils.setFieldsFromMap(this, flags)

        // ENGR-1560 - why not init effectors here? or allow an "addEffector" like addAttribute?

        //set the owner if supplied; accept as argument or field
        if (suppliedOwner) suppliedOwner.addOwnedChild(this)
    }

    public void propertyMissing(String name, value) { attributes[name] = value }
 
    public Object propertyMissing(String name) {
        if (attributes.containsKey(name)) return attributes[name];
        else {
            //TODO could be more efficient ;)
            def v = owner?.attributes[name]
            if (v != null) return v;
            if (groups.find { group -> v = group.attributes[name] }) return v;
        }
        log.debug "no property or attribute $name on $this"
		if (name=="activity") log.warn "reference to removed field 'activity' on entity $this", new Throwable("location of failed reference to 'activity' on $this")
    }
	
    /**
     * Adds this as a member of the given group, registers with application if necessary
     */
    public void setOwner(Group e) {
        owner = e
        owner.inheritableConfig?.entrySet().each { Map.Entry<ConfigKey,Object> entry ->
            if (!inheritableConfig.containsKey(entry.getKey())) {
                inheritableConfig.put(entry.getKey(), entry.getValue())
            }
        }
        getApplication()
    }

    /**
     * Adds this as a member of the given group, registers with application if necessary
     */
    public void addGroup(Group e) {
        groups.add e
        getApplication()
    }
 
	public Collection<String> getGroupIds() {
        groups.collect { g -> g.id }
	}
	
	public Group getOwner() { owner }

	public Collection<Group> getGroups() { groups }

    /**
     * Returns the application, looking it up if not yet known (registering if necessary)
     */
    public Application getApplication() {
        if (application!=null) return application;
        def app = owner?.getApplication()
        if (app) {
            registerWithApplication(app)
            application
        }
        app
    }

	public String getApplicationId() {
		getApplication()?.id
	}

	public ManagementContext getManagementContext() {
		getApplication()?.getManagementContext()
	}
	
    protected synchronized void registerWithApplication(Application app) {
        if (application) return;
        this.application = app
        app.registerEntity(this)
    }

    public EntityClass getEntityClass() {
		//TODO registry? or a transient?
		new BasicEntityClass(getClass())
    }

    /**
     * Should be invoked at end-of-life to clean up the item.
     */
    public void destroy() {
		//FIXME this doesn't exist, but we need some way of deleting stale items
        removeApplicationRegistrant()
    }

    /**
     * Mutable attributes on this entity.
     *
     * This can include activity information and status information (e.g. AttributeSensors), as well as
     * arbitrary internal properties which can make life much easier/dynamic (though we lose something in type safety)
     * e.g. jmxHost / jmxPort are handled as attributes.
     * 
     * @deprecated this will not be exposed, final API TBD
     */
    @Deprecated
    public Map<String, Object> getAttributes() {
        return attributesInternal.asMap(); // .asImmutable(); // FIXME this does not make the children immutable
    }
    
	public <T> T getAttribute(AttributeSensor<T> attribute) { attributesInternal.getValue(attribute); }
 
    public <T> T updateAttribute(AttributeSensor<T> attribute, T val) {
        log.info "updating attribute {} as {}", attribute.name, val
        attributesInternal.update(attribute, val);
    }

    @Override
    public <T> T getConfig(ConfigKey<T> key) {
        return inheritableConfig.get(key);
    }

    @Override
    public <T> T setConfig(ConfigKey<T> key, T val) {
        return inheritableConfig.put(key, val);
    }

    /** @see Entity#subscribe(Entity, Sensor, EventListener) */
    public <T> long subscribe(Entity producer, Sensor<T> sensor, EventListener<T> listener) {
        subscriptionContext.getSubscriptionManager().subscribe this.id, producer.id, sensor.name, listener
    }

    protected synchronized SubscriptionContext getSubscriptionContext() {
		if (subscription) subscription;
        subscription = subscription ?: new LocalSubscriptionContext() // XXX doesn't work ?
    }

	protected synchronized ExecutionContext getExecutionContext() {
		if (execution) execution;
		synchronized (this) {
			if (execution) execution;
			execution = new ExecutionContext(tag: this, getManagementContext().getExecutionManager())
		}
	}
    
    public <T> Sensor<T> getSensor(String sensorName) {
        getEntityClass().getSensors() find { s -> s.name.equals(sensorName) }
    }

    /** default toString is simplified name of class, together with selected arguments */
    @Override
    public String toString() {
        StringBuffer result = []
        result << getClass().getSimpleName()
        if (!result) result << getClass().getName()
        //TODO groovy 1.8, use collectEntries
        result << toStringFieldsToInclude().collect({
            def v = this.hasProperty(it) ? this[it] : this.properties[it]
            v ? "$it=$v" : null
        }).findAll { it }
        result
    }
 
    /** override this, adding to the collection, to supply fields whose value, if not null, should be included in the toString */
    public Collection<String> toStringFieldsToInclude() { ['id', 'displayName'] }

    /** @see EntityLocal#emit(Sensor, Object) */
    public <T> void emit(Sensor<T> sensor, T val) {
        subscriptionContext.subscriptionManager.publish(sensor.newEvent(this, val))
    }
    
	// -------- EFFECTORS --------------
	
	private ThreadLocal<Boolean> invokeMethodPrep = new ThreadLocal() { protected Object initialValue() { Boolean.FALSE } }

	public Object invokeMethod(String name, Object args) {
		if (!this.@invokeMethodPrep.get()) {
			this.@invokeMethodPrep.set(true);
			
		    // ENGR-1560 - we do this in prepareArgsForEffector?
			//args should be an array, warn if we got here wrongly
			if (args==null) log.warn("$this.$name invoked with incorrect args signature (null)", new Throwable("source of incorrect invocation of $this.$name"))
			else if (!args.getClass().isArray()) log.warn("$this.$name invoked with incorrect args signature (non-array ${args.getClass()}): "+args, new Throwable("source of incorrect invocation of $this.$name"))
			
			try {
				Effector eff = getEffectors().get(name)
				if (eff) {
					args = prepareArgsForEffector(eff, args);
					Task currentTask = ExecutionContext.getCurrentTask();
					if (!currentTask || !currentTask.getTags().contains(this)) {
						//wrap in a task if we aren't already in a task that is tagged with this entity
						MetaClass mc = metaClass
						Task t = executionContext.submit( { mc.invokeMethod(this, name, args); },
							description: "call to method $name being treated as call to effector $eff" )
						return t.get();
					}
				}
			} finally { this.@invokeMethodPrep.set(false); }
		}
	    // ENGR-1560 - why is this here? Can we be called trwice from same thread witrh diff args/name or not possible?
		metaClass.invokeMethod(this, name, args);
		//following is recommended on web site, but above is how groovy actually implements it
//			def metaMethod = metaClass.getMetaMethod(name, newArgs)
//			if (metaMethod==null)
//				throw new IllegalArgumentException("Invalid arguments (no method found) for method $name: "+newArgs);
//			metaMethod.invoke(this, newArgs)
	}
 
	private transient volatile Map<String,Effector> effectors = null

    // ENGR-1560 don't like the idea of initialising (DCL, too ;) on first get
    // also assumes no dynamic effectors, or not added and removed at different lifeccle stages
    // prefer same syntax as attributes, maybe
	public Map<String,Effector> getEffectors() {
		if (effectors!=null) return effectors
		synchronized (this) {
			if (effectors!=null) return effectors
			Map<String,Effector> effectorsT = [:]
			getClass().getFields().each { Field f ->
				if (Effector.class.isAssignableFrom(f.getType())) {
					Effector eff = f.get(this)
					def overwritten = effectorsT.put(eff.name, eff)
					if (overwritten!=null) log.warn("multiple definitions for effector ${eff.name} on $this; preferring $eff to $overwritten")
				}
			}
			effectors = effectorsT
		}
	}
 
    // ENGR-1560 - shouldn't this be in AbstractEffector?
	/**
	 * Prepares arguments for passing to an {@link Effector}.
	 *
	 * Takes an array of arguments, which typically contain a map in the first position (and possibly nothing else),
	 * and returns an array of arguments suitable for use by Effector according to the ParameterTypes it exposes.
	 */
	public static Object prepareArgsForEffector(Effector eff, Object args) {
		//attempt to coerce unexpected types
		if (args==null) args = [:]
		if (!args.getClass().isArray()) {
			if (args instanceof Collection) args = args as Object[]
			else args = new Object[1] { args }
		}
		
		//if args starts with a map, assume it contains the named arguments
		//(but only use it when we have insufficient supplied arguments)
		List l = new ArrayList()
		l.addAll(args)
		Map m = (args[0] instanceof Map ? new LinkedHashMap(l.remove(0)) : null)
		def newArgs = []
		int newArgsNeeded = eff.getParameters().size()
		boolean mapUsed = false;
		eff.getParameters().eachWithIndex { ParameterType<?> it, int index ->
			if (l.size()>=newArgsNeeded)
				//all supplied (unnamed) arguments must be used; ignore map
				newArgs << l.remove(0)
			else if (m && it.name && m.containsKey(it.name))
				//some arguments were not supplied, and this one is in the map
				newArgs << m.remove(it.name)
			else if (index==0 && Map.class.isAssignableFrom(it.getParameterClass())) {
				//if first arg is a map it takes the supplied map
				newArgs << m
				mapUsed = true
			} else if (!l.isEmpty() && it.getParameterClass().isInstance(l[0]))
				//if there are parameters supplied, and type is correct, they get applied before default values
				//(this is akin to groovy)
				newArgs << l.remove(0)
			else if (it in BasicParameterType && it.hasDefaultValue())
				//finally, default values are used to make up for missing parameters
				newArgs << it.defaultValue
			else
				throw new IllegalArgumentException("Invalid arguments (count mismatch) for effector $eff: "+args);
				
			newArgsNeeded--
		}
		if (newArgsNeeded>0)
			throw new IllegalArgumentException("Invalid arguments (missing $newArgsNeeded) for effector $eff: "+args);
		if (!l.isEmpty())
			throw new IllegalArgumentException("Invalid arguments (${l.size()} extra) for effector $eff: "+args);
		if (m && !mapUsed)
			throw new IllegalArgumentException("Invalid arguments (${m.size()} extra named) for effector $eff: "+args);
		newArgs = newArgs as Object[]
	}
	
	public <T> Task<T> invoke(Map parameters=[:], Effector<T> eff) {
		invoke(eff, parameters);
	}
 
	//add'l form supplied for when map needs to be made explicit (above supports implicit named args)
	public <T> Task<T> invoke(Effector<T> eff, Map parameters) {
		executionContext.submit( { eff.call(this, parameters) }, description: "invocation of effector $eff" )
	}
}
