package com.whitedns.vpn

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

internal data class EngineTrafficRate(val uploadBytesPerSecond: Long, val downloadBytesPerSecond: Long) {
    companion object {
        fun parse(json: String): EngineTrafficRate {
            val values = JSONObject(json)
            fun counter(key: String): Long {
                val value = values.get(key)
                require(value is Number) { "Missing traffic counter" }
                return value.toLong().also { require(it >= 0) { "Negative traffic counter" } }
            }
            return EngineTrafficRate(counter("up"), counter("down"))
        }
    }
}

internal class EngineTrafficRegistry {
    private data class Session(val leaseId: String, val profileId: String, val rate: EngineTrafficRate? = null, val observedAtMs: Long = 0)
    private val current = AtomicReference<Session?>()
    fun begin(leaseId: String, profileId: String) { current.set(Session(leaseId, profileId)) }
    fun publish(leaseId: String, rate: EngineTrafficRate, observedAtMs: Long): Boolean {
        while (true) {
            val previous = current.get() ?: return false
            if (previous.leaseId != leaseId || observedAtMs <= previous.observedAtMs) return false
            if (current.compareAndSet(previous, previous.copy(rate = rate, observedAtMs = observedAtMs))) return true
        }
    }
    fun read(profileId: String, nowMs: Long): EngineTrafficRate? {
        val sample = current.get() ?: return null
        if (sample.profileId != profileId || nowMs - sample.observedAtMs !in 0..5_000) return null
        return sample.rate
    }
    fun clear(leaseId: String) {
        while (true) {
            val previous = current.get() ?: return
            if (previous.leaseId != leaseId || current.compareAndSet(previous, null)) return
        }
    }
}

internal object EngineTrafficState {
    val registry = EngineTrafficRegistry()
}
