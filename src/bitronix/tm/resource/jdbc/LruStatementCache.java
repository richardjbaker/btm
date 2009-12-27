package bitronix.tm.resource.jdbc;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bitronix.tm.utils.LruEvictionListener;

/**
 * Last Recently Used PreparedStatement cache with eviction listeners 
 * support implementation.
 *
 * <p>&copy; <a href="http://www.bitronix.be">Bitronix Software</a></p>
 *
 * @author lorban
 */
public class LruStatementCache {

    private final static Logger log = LoggerFactory.getLogger(LruStatementCache.class);

    /**
     * The _target_ maxSize of the cache.  The cache may drift slightly
     * higher in size in the case that every statement in the cache is 
     * in use and therefore nothing can be evicted.  But eventually 
     * (probably quickly) the cache will return to maxSize.
     */
    private int maxSize;
    
    /**
     * We use a LinkedHashMap with _access order_ specified in the
     * constructor.  According to the LinkedHashMap documentation:
     *   A special constructor is provided to create a linked hash map
     *   whose order of iteration is the order in which its entries 
     *   were last accessed, from least-recently accessed to most-recently
     *   (access-order). This kind of map is well-suited to building LRU 
     *   caches. Invoking the put or get method results in an access to 
     *   the corresponding entry (assuming it exists after the invocation 
     *   completes).
     */
    private LinkedHashMap cache;

    /**
     * A list of listeners concerned with prepared statement cache
     * evictions.
     */
    private List evictionListners;

    /**
     * See the LinkedHashMap documentation.  We maintain our own size
     * here, rather than calling size(), because size() on a LinkedHashMap
     * is proportional in time (O(n)) with the size of the collection -- i.e.
     * calling size() must traverse the entire list and count the elements.
     * Tracking size ourselves provides O(1) access.
     */
    private int size;

    public LruStatementCache(int maxSize) {
        this.maxSize = maxSize;
        cache = new LinkedHashMap(maxSize, 0.75f, true /* access order */);
        evictionListners = new ArrayList();
    }

    /**
     * The provided key is just a 'shell' JdbcPreparedStatementHandle, it comes
     * in with no actual 'delegate' PreparedStatement.  However, it contains all
     * other pertinent information such as SQL statement, autogeneratedkeys
     * flag, cursor holdability, etc.  See the equals() method in the
     * JdbcPreparedStatementHandle class.  It is a complete key for a cached
     * statement.
     * 
     * If there is a matching cached PreparedStatement, it will be set as the
     * delegate in the provided JdbcPreparedStatementHandle.
     *
     * @param key the cache key
     * @return the cached JdbcPreparedStatementHandle statement, or null
     */
    public PreparedStatement get(JdbcPreparedStatementHandle key) {
        // See LinkedHashMap documentation.  Getting an entry means it is 
        // updated as the 'youngest' (Most Recently Used) entry.  Iteration
        // order is Least Recently Used (LRU) to Most Recently Used (MRU).
        StatementTracker cached = (StatementTracker) cache.get(key);
        if (cached != null) {
            cached.usageCount++;
            key.setDelegate(cached.statement);
            if (log.isDebugEnabled()) log.debug("delivered from cache with usage count " + cached.usageCount + " statement <" + key + "> in " + key.getPooledConnection());
            return key;
        }

        return null;
    }

    /**
     * A statement is put into the cache when it is "closed".
     *
     * @param stmt
     * @return
     */
    public PreparedStatement put(JdbcPreparedStatementHandle key) {
        if (maxSize < 1) {
            return null;
        }

        // See LinkedHashMap documentation.  Getting an entry means it is 
        // updated as the 'youngest' (Most Recently Used) entry.  Iteration
        // order is Least Recently Used (LRU) to Most Recently Used (MRU).
        StatementTracker cached = (StatementTracker) cache.get(key);
        if (cached == null) {
            if (log.isDebugEnabled()) log.debug("adding to cache statement <" + key + "> in " + key.getPooledConnection());
            cache.put(key, new StatementTracker(key.getDelegateUnchecked()));
            size++;
        } else {
            cached.usageCount--;
            if (log.isDebugEnabled()) log.debug("returning to cache statement <" + key + "> with usage count " + cached.usageCount + " in " + key.getPooledConnection());
        }

        // If the size is exceeded, we will _try_ to evict one (or more) 
        // statements until the max level is again reached.  However, if
        // every statement in the cache is 'in use', the size of the cache
        // is not reduced.  Eventually the cache will be reduced, no worries.
        if (size > maxSize) {
            tryEviction();
        }

        return key;
    }

    /**
     * Evict all statements from the cache.  This likely happens on
     * connection close.
     */
    protected void clear() {
        Iterator it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Entry) it.next();
            StatementTracker tracker = (StatementTracker) entry.getValue();
            it.remove();
            fireEvictionEvent(tracker.statement);
        }
        cache.clear();
        size = 0;
    }

    /**
     * Try to evict statements from the cache.  Only statements with a
     * current usage count of zero will be evicted.  Statements are
     * evicted until the cache is reduced to maxSize.
     */
    private void tryEviction() {
        // Iteration order of the LinkedHashMap is from LRU to MRU
        Iterator it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Entry) it.next();
            StatementTracker tracker = (StatementTracker) entry.getValue();
            if (tracker.usageCount == 0) {
                it.remove();
                size--;
                JdbcPreparedStatementHandle key = (JdbcPreparedStatementHandle) entry.getKey();
                if (log.isDebugEnabled()) log.debug("evicting from cache statement <" + key + "> " + key.getDelegateUnchecked() + " in " + key.getPooledConnection());
                fireEvictionEvent(tracker.statement);
                // We can stop evicting if we're at maxSize...
                if (size <= maxSize) {
                    break;
                }
            }
        }
    }

    private void fireEvictionEvent(Object value) {
        for (int i = 0; i < evictionListners.size(); i++) {
            LruEvictionListener listener = (LruEvictionListener) evictionListners.get(i);
            listener.onEviction(value);
        }
    }

    public void addEvictionListener(LruEvictionListener listener) {
        evictionListners.add(listener);
    }

    public void removeEvictionListener(LruEvictionListener listener) {
        evictionListners.remove(listener);
    }

    private class StatementTracker
    {
        private PreparedStatement statement;
        private int usageCount;

        private StatementTracker(PreparedStatement stmt) {
            this.statement = stmt;
            this.usageCount = 1;
        }
    }
}
