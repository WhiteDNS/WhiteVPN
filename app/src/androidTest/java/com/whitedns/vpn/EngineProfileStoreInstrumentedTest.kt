package com.whitedns.vpn

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EngineProfileStoreInstrumentedTest {
    @Test fun encryptedCatalogPersistsAndDeletionCannotFallBack() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val directory = File(app.cacheDir, "engine-store-test-" + suffix).apply { mkdirs() }
        val testContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = app.getSharedPreferences(name + suffix, mode)
        }
        try {
            val profile = EngineProfile(name = "test", kind = EngineKind.SSH,
                config = EngineConfig.Ssh(mapOf("host" to "example.com", "username" to "test", "password" to "unique-secret-for-storage-test")))
            val store = EngineProfileStore(testContext)
            store.save(profile); store.selectEngine(profile.id)
            val disk = File(directory, "engine-profiles.enc").readText()
            assertFalse(disk.contains("unique-secret-for-storage-test"))
            assertFalse(disk.contains("example.com"))
            val restored = EngineProfileStore(testContext)
            assertEquals(profile, restored.profile(profile.id))
            assertEquals(RouteProfileRef.Engine(profile.id), restored.selectedReference("old-sub", "old-fingerprint"))
            restored.delete(profile.id)
            assertNull(restored.profile(profile.id))
            assertEquals(RouteProfileRef.Engine(profile.id), restored.selectedReference("old-sub", "old-fingerprint"))
            restored.selectMihomo()
            assertEquals(RouteProfileRef.Mihomo("old-sub", "old-fingerprint"), restored.selectedReference("old-sub", "old-fingerprint"))
        } finally {
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
            app.getSharedPreferences("whitevpn_route" + suffix, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
