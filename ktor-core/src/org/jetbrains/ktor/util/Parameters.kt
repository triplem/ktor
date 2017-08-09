package org.jetbrains.ktor.util

import java.util.*

interface Parameters {
    companion object {
        val Empty: Parameters = ParametersImpl()

        inline fun build(caseInsensitiveKey: Boolean = false, body: ParametersBuilder.() -> Unit): Parameters = ParametersBuilder(caseInsensitiveKey).apply(body).build()
    }

    val caseInsensitiveKey: Boolean

    operator fun get(name: String): String? = getAll(name)?.firstOrNull()
    fun getAll(name: String): List<String>?


    fun names(): Set<String>
    fun entries(): Set<Map.Entry<String, List<String>>>

    operator fun contains(name: String): Boolean = getAll(name) != null
    fun contains(name: String, value: String): Boolean = getAll(name)?.contains(value) ?: false
    fun forEach(body: (String, List<String>) -> Unit) = entries().forEach { (k, v) -> body(k, v) }
    fun isEmpty(): Boolean
}

private class ParametersSingleImpl(override val caseInsensitiveKey: Boolean, val name: String, val values: List<String>) : Parameters {
    override fun getAll(name: String): List<String>? = if (this.name.equals(name, caseInsensitiveKey)) values else null
    override fun entries(): Set<Map.Entry<String, List<String>>> = setOf(object : Map.Entry<String, List<String>> {
        override val key: String = name
        override val value: List<String> = values
        override fun toString() = "$key=$value"
    })

    override fun isEmpty(): Boolean = false
    override fun names(): Set<String> = setOf(name)
    override fun toString() = "Parameters(case=${!caseInsensitiveKey}) ${entries()}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Parameters) return false
        if (caseInsensitiveKey != other.caseInsensitiveKey) return false
        return entriesEquals(entries(), other.entries())
    }

    override fun forEach(body: (String, List<String>) -> Unit) = body(name, values)
    override fun get(name: String): String? = if (name.equals(this.name, caseInsensitiveKey)) values.firstOrNull() else null
    override fun contains(name: String): Boolean = name.equals(this.name, caseInsensitiveKey)
    override fun contains(name: String, value: String): Boolean = name.equals(this.name, caseInsensitiveKey) && values.contains(value)
    override fun hashCode() = entriesHashCode(entries(), 31 * caseInsensitiveKey.hashCode())
}

private class ParametersImpl(override val caseInsensitiveKey: Boolean = false, private val values: Map<String, List<String>> = emptyMap()) : Parameters {
    override operator fun get(name: String) = listForKey(name)?.firstOrNull()
    override fun getAll(name: String): List<String>? = listForKey(name)

    override operator fun contains(name: String) = listForKey(name) != null
    override fun contains(name: String, value: String) = listForKey(name)?.contains(value) ?: false

    override fun names(): Set<String> = Collections.unmodifiableSet(values.keys)
    override fun isEmpty() = values.isEmpty()
    override fun entries(): Set<Map.Entry<String, List<String>>> = Collections.unmodifiableSet(values.entries)
    override fun forEach(body: (String, List<String>) -> Unit) = values.forEach(body)

    private fun listForKey(key: String): List<String>? = values[key]
    override fun toString() = "Parameters(case=${!caseInsensitiveKey}) ${entries()}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Parameters) return false
        if (caseInsensitiveKey != other.caseInsensitiveKey) return false
        return entriesEquals(entries(), other.entries())
    }

    override fun hashCode() = entriesHashCode(entries(), 31 * caseInsensitiveKey.hashCode())
}

class ParametersBuilder(val caseInsensitiveKey: Boolean = false, size: Int = 8) {
    private val values: MutableMap<String, MutableList<String>> = if (caseInsensitiveKey) CaseInsensitiveMap(size) else LinkedHashMap(size)
    private var built = false

    fun getAll(name: String): List<String>? = listForKey(name)
    fun contains(name: String, value: String) = listForKey(name)?.contains(value) ?: false

    fun names() = values.keys
    fun isEmpty() = values.isEmpty()
    fun entries(): Set<Map.Entry<String, List<String>>> = Collections.unmodifiableSet(values.entries)

    operator fun set(name: String, value: String) {
        val list = ensureListForKey(name, 1)
        list.clear()
        list.add(value)
    }

    operator fun get(name: String): String? = getAll(name)?.firstOrNull()

    fun append(name: String, value: String) {
        ensureListForKey(name, 1).add(value)
    }

    fun appendAll(parameters: Parameters) {
        parameters.forEach { name, values ->
            appendAll(name, values)
        }
    }

    fun appendMissing(parameters: Parameters) {
        parameters.forEach { name, values ->
            appendMissing(name, values)
        }
    }

    fun appendAll(key: String, values: Iterable<String>) {
        ensureListForKey(key, (values as? Collection)?.size ?: 2).addAll(values)
    }

    fun appendMissing(key: String, values: Iterable<String>) {
        val existing = listForKey(key)?.toSet() ?: emptySet()

        appendAll(key, values.filter { it !in existing })
    }

    fun remove(name: String) {
        values.remove(name)
    }

    fun removeKeysWithNoEntries() {
        for ((k, _) in values.filter { it.value.isEmpty() }) {
            remove(k)
        }
    }

    fun remove(name: String, value: String) = listForKey(name)?.remove(value) ?: false

    fun clear() {
        values.clear()
    }

    fun build(): Parameters {
        require(!built) { "ValueMapBuilder can only build single ValueMap" }
        built = true
        return ParametersImpl(caseInsensitiveKey, values)
    }

    private fun ensureListForKey(key: String, size: Int): MutableList<String> {
        val existing = listForKey(key)
        if (existing != null) {
            return existing
        }

        appendNewKey(key, size)
        return ensureListForKey(key, size)
    }

    private fun appendNewKey(key: String, size: Int) {
        values[key] = ArrayList(size)
    }

    private fun listForKey(key: String): MutableList<String>? = values[key]
}

fun parametersOf(vararg pairs: Pair<String, List<String>>, caseInsensitiveKey: Boolean = false): Parameters {
    return ParametersImpl(caseInsensitiveKey, pairs.asList().toMap())
}

fun parametersOf(pair: Pair<String, List<String>>, caseInsensitiveKey: Boolean = false): Parameters {
    return ParametersSingleImpl(caseInsensitiveKey, pair.first, pair.second)
}

fun parametersOf(name: String, value: List<String>, caseInsensitiveKey: Boolean = false): Parameters {
    return ParametersSingleImpl(caseInsensitiveKey, name, value)
}

fun parametersOf(): Parameters {
    return Parameters.Empty
}

fun parametersOf(map: Map<String, Iterable<String>>, caseInsensitiveKey: Boolean = false): Parameters {
    val size = map.size
    if (size == 1) {
        val entry = map.entries.single()
        return ParametersSingleImpl(caseInsensitiveKey, entry.key, entry.value.toList())
    }
    val values: MutableMap<String, List<String>> = if (caseInsensitiveKey) CaseInsensitiveMap(size) else LinkedHashMap(size)
    map.entries.forEach { values.put(it.key, it.value.toList()) }
    return ParametersImpl(caseInsensitiveKey, values)
}

operator fun Parameters.plus(other: Parameters) = when {
    caseInsensitiveKey == other.caseInsensitiveKey -> when {
        this.isEmpty() -> other
        other.isEmpty() -> this
        else -> Parameters.build(caseInsensitiveKey) { appendAll(this@plus); appendAll(other) }
    }
    else -> throw IllegalArgumentException("It is forbidden to concatenate case sensitive and case insensitive maps")
}

fun Parameters.toMap(): Map<String, List<String>> =
        entries().associateByTo(LinkedHashMap<String, List<String>>(), { it.key }, { it.value.toList() })

fun Parameters.flattenEntries(): List<Pair<String, String>> = entries().flatMap { e -> e.value.map { e.key to it } }

fun Parameters.filter(keepEmpty: Boolean = false, predicate: (String, String) -> Boolean): Parameters {
    val entries = entries()
    val values: MutableMap<String, MutableList<String>> = if (caseInsensitiveKey) CaseInsensitiveMap(entries.size) else LinkedHashMap(entries.size)
    entries.forEach { entry ->
        val list = entry.value.filterTo(ArrayList(entry.value.size)) { predicate(entry.key, it) }
        if (keepEmpty || list.isNotEmpty())
            values.put(entry.key, list)
    }

    return ParametersImpl(caseInsensitiveKey, values)
}

fun ParametersBuilder.appendFiltered(source: Parameters, keepEmpty: Boolean = false, predicate: (String, String) -> Boolean) {
    source.forEach { name, value ->
        val list = value.filterTo(ArrayList(value.size)) { predicate(name, it) }
        if (keepEmpty || list.isNotEmpty())
            appendAll(name, list)
    }
}

private fun entriesEquals(a: Set<Map.Entry<String, List<String>>>, b: Set<Map.Entry<String, List<String>>>): Boolean {
    return a == b
}

private fun entriesHashCode(entries: Set<Map.Entry<String, List<String>>>, seed: Int): Int {
    return seed * 31 + entries.hashCode()
}

@Deprecated("Use Parameters", replaceWith = ReplaceWith("Parameters"))
typealias ValuesMap = Parameters
@Deprecated("Use parametersOf instead", replaceWith = ReplaceWith("parametersOf(*pairs, caseInsensitiveKey = caseInsensitiveKey)"))
fun valuesOf(vararg pairs: Pair<String, List<String>>, caseInsensitiveKey: Boolean = false) = parametersOf(*pairs, caseInsensitiveKey = caseInsensitiveKey)
@Deprecated("Use parametersOf instead", replaceWith = ReplaceWith("parametersOf(pair, caseInsensitiveKey = caseInsensitiveKey)"))
fun valuesOf(pair: Pair<String, List<String>>, caseInsensitiveKey: Boolean = false) = parametersOf(pair, caseInsensitiveKey = caseInsensitiveKey)
@Deprecated("Use parametersOf instead", replaceWith = ReplaceWith("parametersOf(name, value, caseInsensitiveKey = caseInsensitiveKey)"))
fun valuesOf(name: String, value: List<String>, caseInsensitiveKey: Boolean = false) = parametersOf(name, value, caseInsensitiveKey)
@Deprecated("Use parametersOf instead", replaceWith = ReplaceWith("parametersOf()"))
fun valuesOf() = parametersOf()
@Deprecated("Use parametersOf instead", replaceWith = ReplaceWith("parametersOf(map, caseInsensitiveKey = caseInsensitiveKey)"))
fun valuesOf(map: Map<String, Iterable<String>>, caseInsensitiveKey: Boolean = false) = parametersOf(map, caseInsensitiveKey)
