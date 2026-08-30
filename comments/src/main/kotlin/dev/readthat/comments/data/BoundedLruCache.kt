package dev.readthat.comments.data

/** Small synchronized LRU used by repositories; disk remains authoritative. */
internal class BoundedLruCache<K : Any, V : Any>(private val maxEntries: Int) {
    init { require(maxEntries > 0) }

    private val entries = LinkedHashMap<K, V>(maxEntries, 0.75f, true)

    operator fun get(key: K): V? = synchronized(entries) { entries[key] }

    fun put(key: K, value: V) = synchronized(entries) {
        entries[key] = value
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    fun remove(key: K): V? = synchronized(entries) { entries.remove(key) }

    fun snapshot(): Map<K, V> = synchronized(entries) { LinkedHashMap(entries) }
}
