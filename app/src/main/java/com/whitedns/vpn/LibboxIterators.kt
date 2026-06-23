package com.whitedns.vpn

import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

class StringArray(private val values: List<String>) : StringIterator {
    constructor(iterator: Iterator<String>) : this(iterator.asSequence().toList())

    private val iterator = values.iterator()

    override fun len(): Int = values.size
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): String = iterator.next()
}

class LibboxNetworkInterfaceArray(
    private val iterator: Iterator<LibboxNetworkInterface>,
) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): LibboxNetworkInterface = iterator.next()
}

fun StringIterator?.toKotlinList(): List<String> {
    if (this == null) return emptyList()
    val result = mutableListOf<String>()
    while (hasNext()) result += next()
    return result
}

fun RoutePrefix.addressString(): String = address()

fun RoutePrefix.prefixLength(): Int = prefix()
