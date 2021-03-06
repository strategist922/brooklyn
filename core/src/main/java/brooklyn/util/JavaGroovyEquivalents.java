package brooklyn.util;

import groovy.lang.Closure;
import groovy.lang.GString;
import groovy.time.TimeDuration;

import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

public class JavaGroovyEquivalents {

    private static final Logger log = LoggerFactory.getLogger(JavaGroovyEquivalents.class);

    public static String join(Collection<?> collection, String separator) {
        StringBuffer result = new StringBuffer();
        Iterator<?> ci = collection.iterator();
        if (ci.hasNext()) result.append(asNonnullString(ci.next()));
        while (ci.hasNext()) {
            result.append(separator);
            result.append(asNonnullString(ci.next()));
        }
        return result.toString();
    }

    /** simple elvislike operators; uses groovy truth */
    @SuppressWarnings("unchecked")
    public static <T> Collection<T> elvis(Collection<T> preferred, Collection<?> fallback) {
        // TODO Would be nice to not cast, but this is groovy equivalent! Let's fix generics in stage 2
        return groovyTruth(preferred) ? preferred : (Collection<T>) fallback;
    }
    public static String elvis(String preferred, String fallback) {
        return groovyTruth(preferred) ? preferred : fallback;
    }
    public static String elvisString(Object preferred, Object fallback) {
        return elvis(asString(preferred), asString(fallback));
    }
    public static <T> T elvis(T preferred, T fallback) {
        return groovyTruth(preferred) ? preferred : fallback;
    }
    public static <T> T elvis(Iterable<?> preferences) {
        return elvis(Iterables.toArray(preferences, Object.class));
    }
    public static <T> T elvis(Object... preferences) {
        if (preferences.length == 0) throw new IllegalArgumentException("preferences must not be empty for elvis");
        for (Object contender : preferences) {
            if (groovyTruth(contender)) return (T) fix(contender);
        }
        return (T) fix(preferences[preferences.length-1]);
    }
    
    public static Object fix(Object o) {
        if (o instanceof GString) return (o.toString());
        return o;
    }

    public static String asString(Object o) {
        if (o==null) return null;
        return o.toString();
    }
    public static String asNonnullString(Object o) {
        if (o==null) return "null";
        return o.toString();
    }
    
    public static boolean groovyTruth(Collection<?> c) {
        return c != null && !c.isEmpty();
    }
    public static boolean groovyTruth(String s) {
        return s != null && !s.isEmpty();
    }
    public static boolean groovyTruth(Object o) {
        // TODO Doesn't handle matchers (see http://docs.codehaus.org/display/GROOVY/Groovy+Truth)
        if (o == null) {
            return false;
        } else if (o instanceof Boolean) {
            return (Boolean)o;
        } else if (o instanceof String) {
            return !((String)o).isEmpty();
        } else if (o instanceof Collection) {
            return !((Collection)o).isEmpty();
        } else if (o instanceof Map) {
            return !((Map)o).isEmpty();
        } else if (o instanceof Iterator) {
            return ((Iterator)o).hasNext();
        } else if (o instanceof Enumeration) {
            return ((Enumeration)o).hasMoreElements();
        } else {
            return true;
        }
    }
    
    public static <T> Predicate<T> groovyTruthPredicate() {
        return new Predicate<T>() {
            @Override public boolean apply(T val) {
                return groovyTruth(val);
            }
        };
    }

    public static <K,V> Map<K,V> mapOf(K key1, V val1) {
        Map<K,V> result = Maps.newLinkedHashMap();
        result.put(key1, val1);
        return result;
    }
    
    public static TimeDuration toTimeDuration(Object duration) {
        // TODO Lazy coding here for large number values; but refactoring away from groovy anyway...
        
        if (duration == null) {
            return null;
        } else if (duration instanceof TimeDuration) {
            return (TimeDuration) duration;
        } else if (duration instanceof Number) {
            long d = ((Number)duration).longValue();
            if (d <= Integer.MAX_VALUE && d >= Integer.MIN_VALUE) {
                return new TimeDuration(0,0,0,(int)d);
            } else {
                log.warn("Number "+d+" too large to convert to TimeDuration; using Integer.MAX_VALUE instead");
                return new TimeDuration(0,0,0,Integer.MAX_VALUE);
            }
        } else {
            throw new IllegalArgumentException("Cannot convert "+duration+" of type "+duration.getClass().getName()+" to a TimeDuration");
        }
    }

    public static <T> Predicate<T> toPredicate(final Closure<Boolean> c) {
        return new Predicate<T>() {
            @Override public boolean apply(T input) {
                return c.call(input);
            }
        };
    }
}
