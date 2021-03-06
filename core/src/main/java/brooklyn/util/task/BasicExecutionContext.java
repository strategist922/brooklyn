package brooklyn.util.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import brooklyn.management.ExecutionContext;
import brooklyn.management.ExecutionManager;
import brooklyn.management.Task;

import com.google.common.base.Function;
import com.google.common.collect.Maps;

/**
 * A means of executing tasks against an ExecutionManager with a given bucket/set of tags pre-defined
 * (so that it can look like an {@link Executor} and also supply {@link ExecutorService#submit(Callable)}
 */
public class BasicExecutionContext extends AbstractExecutionContext {
    static final ThreadLocal<BasicExecutionContext> perThreadExecutionContext = new ThreadLocal<BasicExecutionContext>();
    
    public static BasicExecutionContext getCurrentExecutionContext() { return perThreadExecutionContext.get(); }
 
    /** @deprecated in 0.4.0, use Tasks.current() */
    public Task<?> getCurrentTask() { return BasicExecutionManager.getCurrentTask(); }

    final ExecutionManager executionManager;
    final Set<Object> tags = new LinkedHashSet<Object>();

    public BasicExecutionContext(ExecutionManager executionManager) {
        this(Collections.emptyMap(), executionManager);
    }
    
    /**
     * Supported flags are {@code tag} and {@code tags}
     * 
     * @see ExecutionManager#submit(Map, Task)
     */
    public BasicExecutionContext(Map<?, ?> flags, ExecutionManager executionManager) {
        this.executionManager = executionManager;

        if (flags.get("tag") != null) tags.add(flags.remove("tag"));
        if (flags.containsKey("tags")) tags.addAll((Collection<?>)flags.remove("tags"));
    }

    public ExecutionManager getExecutionManager() {
        return executionManager;
    }
    
    /** returns tasks started by this context (or tasks which have all the tags on this object) */
    public Set<Task<?>> getTasks() { return executionManager.getTasksWithAllTags((Set)tags); }

    //these conform with ExecutorService but we do not want to expose shutdown etc here
     
    @SuppressWarnings({ "deprecation", "unchecked", "rawtypes" })
    @Override
    protected <T> Task<T> submitInternal(Map properties, Object task) {
        if (properties.get("tags")==null) properties.put("tags", new ArrayList()); 
        ((Collection)properties.get("tags")).addAll(tags);
        
        final Object startCallback = properties.get("newTaskStartCallback");
        properties.put("newTaskStartCallback", new Function<Object,Void>() {
            public Void apply(Object it) {
                registerPerThreadExecutionContext();
                if (startCallback!=null) ExecutionUtils.invoke(startCallback, it);
                return null;
            }});
        
        final Object endCallback = properties.get("newTaskEndCallback");
        properties.put("newTaskEndCallback", new Function<Object,Void>() {
            public Void apply(Object it) {
                try {
                    if (endCallback!=null) ExecutionUtils.invoke(endCallback, it);
                } finally {
                    clearPerThreadExecutionContext();
                }
                return null;
            }});
        
        return executionManager.submit(properties, task);
    }
    
    private void registerPerThreadExecutionContext() { perThreadExecutionContext.set(this); }

    private void clearPerThreadExecutionContext() { perThreadExecutionContext.remove(); }
}
