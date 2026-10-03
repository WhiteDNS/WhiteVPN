package com.whitedns.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object EngineProfileCodec {
    fun encode(profile: EngineProfile): JSONObject = JSONObject()
        .put("version", profile.version).put("id", profile.id).put("name", profile.name)
        .put("kind", profile.kind.wireName).put("settings", JSONObject(profile.config.settings))
    fun decode(json: JSONObject): EngineProfile {
        val kind = EngineKind.from(json.getString("kind"))
        val config = json.getJSONObject("settings")
        val settings = config.keys().asSequence().associateWith { key -> config.getString(key) }
        return EngineProfile(json.getString("id"), json.getString("name"), kind,
            EngineConfig.of(kind, settings), json.getInt("version")).also(EngineProfileValidation::validate)
    }
}

/** The entire engine catalog is encrypted; credentials never enter subscription YAML or preferences. */
class EngineProfileStore(context: Context) {
    private val context = context.applicationContext
    private val file = AtomicFile(File(this.context.noBackupFilesDir, "engine-profiles.enc"))
    private val prefs = this.context.getSharedPreferences("whitevpn_route", Context.MODE_PRIVATE)
    fun profiles(): List<EngineProfile> = synchronized(LOCK) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized emptyList()
        val envelope = JSONObject(String(file.readFully(), Charsets.UTF_8))
        require(envelope.getInt("version") == 1) { "Unsupported encrypted catalog version" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
        cipher.updateAAD(AAD)
        val rows = JSONArray(String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
        List(rows.length()) { EngineProfileCodec.decode(rows.getJSONObject(it)) }
    }
    fun profile(id: String): EngineProfile? = profiles().firstOrNull { it.id == id }
    fun save(profile: EngineProfile) = synchronized(LOCK) {
        EngineProfileValidation.validate(profile)
        val all = profiles().filterNot { it.id == profile.id } + profile
        persist(all)
    }
    fun delete(id: String) = synchronized(LOCK) {
        persist(profiles().filterNot { it.id == id })
        // Keep the selected identity. A deleted selected profile must fail preflight, not fall back.
    }
    fun selectedEngineId(): String? = prefs.getString("engine", null)
    fun selectEngine(id: String) { require(profile(id) != null); check(prefs.edit().putString("engine", id).commit()) }
    fun selectMihomo() { check(prefs.edit().remove("engine").commit()) }
    fun selectedReference(subscriptionId: String, fingerprint: String): RouteProfileRef =
        RouteSelectionMigration.read(selectedEngineId(), subscriptionId, fingerprint)
    fun rememberPlatformProfile(id: String?) {
        check(prefs.edit().apply { if (id == null) remove("platform") else putString("platform", id) }.commit())
    }
    fun platformProfileId(): String? = prefs.getString("platform", null)
    private fun persist(profiles: List<EngineProfile>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(AAD)
        val encoded = JSONArray().apply { profiles.forEach { put(EngineProfileCodec.encode(it)) } }.toString().toByteArray()
        val envelope = JSONObject().put("version", 1)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(encoded), Base64.NO_WRAP)).toString().toByteArray()
        val stream = file.startWrite()
        try { stream.write(envelope); file.finishWrite(stream) } catch (error: Throwable) { file.failWrite(stream); throw error }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    companion object {
        private val LOCK = Any()
        private const val ALIAS = "whitevpn.engine.catalog.v1"
        private val AAD = "WhiteVPN engine catalog v1".toByteArray()
    }
}
