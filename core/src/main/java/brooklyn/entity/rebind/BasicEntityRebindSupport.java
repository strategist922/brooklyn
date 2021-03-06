package brooklyn.entity.rebind;

import static brooklyn.entity.basic.Entities.sanitize;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.Group;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.rebind.dto.MementosGenerators;
import brooklyn.event.AttributeSensor;
import brooklyn.location.Location;
import brooklyn.mementos.EntityMemento;
import brooklyn.policy.basic.AbstractPolicy;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

public class BasicEntityRebindSupport implements RebindSupport<EntityMemento> {

    protected static final Logger LOG = LoggerFactory.getLogger(BasicEntityRebindSupport.class);
    
    private final EntityLocal entity;
    
    public BasicEntityRebindSupport(EntityLocal entity) {
        this.entity = checkNotNull(entity, "entity");
    }
    
    @Override
    public EntityMemento getMemento() {
        return getMementoWithProperties(Collections.<String,Object>emptyMap());
    }

    protected EntityMemento getMementoWithProperties(Map<String,?> props) {
        EntityMemento memento = MementosGenerators.newEntityMementoBuilder(entity).customFields(props).build();
    	if (LOG.isTraceEnabled()) LOG.trace("Creating memento for entity {}({}): parent={}; children={}; locations={}; "+
    	        "policies={}; members={}; config={}; attributes={}; entityReferenceConfigs={}; " +
    	        "entityReferenceAttributes={}; customProperties={}", 
    			new Object[] {memento.getType(), memento.getId(), memento.getParent(), memento.getChildren(), 
                memento.getLocations(), memento.getPolicies(), memento.getMembers(), sanitize(memento.getConfig()), 
                sanitize(memento.getAttributes()), memento.getEntityReferenceConfigs(), 
                memento.getEntityReferenceAttributes(), sanitize(memento.getCustomFields())});
    	return memento;
    }

    @Override
    public void reconstruct(RebindContext rebindContext, EntityMemento memento) {
    	if (LOG.isTraceEnabled()) LOG.trace("Reconstructing entity {}({}): parent={}; children={}; locations={}; " +
    	        "policies={}; members={}; config={}; attributes={}; entityReferenceConfigs={}; " +
    	        "entityReferenceAttributes={}; customProperties={}", 
    	        new Object[] {memento.getType(), memento.getId(), memento.getParent(), memento.getChildren(), 
    	        memento.getLocations(), memento.getPolicies(), memento.getMembers(), sanitize(memento.getConfig()), 
    	        sanitize(memento.getAttributes()), memento.getEntityReferenceConfigs(), 
    	        memento.getEntityReferenceAttributes(), sanitize(memento.getCustomFields())});

        // Note that the id should have been set in the constructor; it is immutable
        entity.setDisplayName(memento.getDisplayName());

        for (Map.Entry<ConfigKey, Object> entry : memento.getConfig().entrySet()) {
            try {
                ConfigKey key = entry.getKey();
                Object value = entry.getValue();
                Class<?> type = (key.getType() != null) ? key.getType() : rebindContext.loadClass(key.getTypeName());
                if (memento.getEntityReferenceConfigs().contains(entry.getKey())) {
                    value = MementoTransformer.transformIdsToEntities(rebindContext, value, type, true);
                } else if (memento.getLocationReferenceConfigs().contains(entry.getKey())) {
                    value = MementoTransformer.transformIdsToLocations(rebindContext, value, type, true);
                }
                entity.setConfig(key, value);
            } catch (ClassNotFoundException e) {
                throw Throwables.propagate(e);
            }
        }
        for (Map.Entry<AttributeSensor, Object> entry : memento.getAttributes().entrySet()) {
            try {
                AttributeSensor key = entry.getKey();
                Object value = entry.getValue();
                Class<?> type = (key.getType() != null) ? key.getType() : rebindContext.loadClass(key.getTypeName());
                if (memento.getEntityReferenceAttributes().contains(entry.getKey())) {
                    value = MementoTransformer.transformIdsToEntities(rebindContext, value, type, true);
                } else if (memento.getLocationReferenceAttributes().contains(entry.getKey())) {
                    value = MementoTransformer.transformIdsToLocations(rebindContext, value, type, true);
                }
                ((EntityInternal)entity).setAttributeWithoutPublishing(key, value);
            } catch (ClassNotFoundException e) {
                throw Throwables.propagate(e);
            }
        }
        
        setParent(rebindContext, memento);
        addChildren(rebindContext, memento);
        addPolicies(rebindContext, memento);
        addMembers(rebindContext, memento);
        addLocations(rebindContext, memento);

        doReconstruct(rebindContext, memento);
    }
    
    /**
     * For overriding, to reconstruct other fields.
     */
    protected void doReconstruct(RebindContext rebindContext, EntityMemento memento) {
        // default is no-op
    }
    
    protected void addMembers(RebindContext rebindContext, EntityMemento memento) {
        if (memento.getMembers().size() > 0) {
            if (entity instanceof Group) {
                for (String memberId : memento.getMembers()) {
                    Entity member = rebindContext.getEntity(memberId);
                    if (member != null) {
                        ((Group)entity).addMember(member);
                    } else {
                        LOG.warn("Entity not found; discarding member {} of group {}({})",
                                new Object[] {memberId, memento.getType(), memento.getId()});
                    }
                }
            } else {
                throw new UnsupportedOperationException("Entity with members should be a group: entity="+entity+"; type="+entity.getClass()+"; members="+memento.getMembers());
            }
        }
    }
    
    protected void addChildren(RebindContext rebindContext, EntityMemento memento) {
        for (String childId : memento.getChildren()) {
            Entity child = rebindContext.getEntity(childId);
            if (child != null) {
                entity.addChild(child);
            } else {
                LOG.warn("Entity not found; discarding child {} of entity {}({})",
                        new Object[] {childId, memento.getType(), memento.getId()});
            }
        }
    }

    protected void setParent(RebindContext rebindContext, EntityMemento memento) {
        Entity parent = (memento.getParent() != null) ? rebindContext.getEntity(memento.getParent()) : null;
        if (parent != null) {
            entity.setParent(parent);
        } else if (memento.getParent() != null){
            LOG.warn("Entity not found; discarding parent {} of entity {}({}), so entity will be orphaned and unmanaged",
                    new Object[] {memento.getParent(), memento.getType(), memento.getId()});
        }
    }
    
    protected void addLocations(RebindContext rebindContext, EntityMemento memento) {
        for (String id : memento.getLocations()) {
            Location loc = rebindContext.getLocation(id);
            if (loc != null) {
                ((EntityInternal)entity).addLocations(ImmutableList.of(loc));
            } else {
                LOG.warn("Location not found; discarding location {} of entity {}({})",
                        new Object[] {id, memento.getType(), memento.getId()});
            }
        }
    }
    
    protected void addPolicies(RebindContext rebindContext, EntityMemento memento) {
        for (String policyId : memento.getPolicies()) {
            AbstractPolicy policy = (AbstractPolicy) rebindContext.getPolicy(policyId);
            if (policy != null) {
                entity.addPolicy(policy);
            } else {
                LOG.warn("Policy not found; discarding policy {} of entity {}({})",
                        new Object[] {policyId, memento.getType(), memento.getId()});
            }
        }
    }
}
