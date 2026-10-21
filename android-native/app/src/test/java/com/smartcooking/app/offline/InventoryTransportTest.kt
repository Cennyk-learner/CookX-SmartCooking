package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import com.smartcooking.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import okhttp3.Interceptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

/** Exercises the same auth/interceptor/repository path as the confirmation screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InventoryTransportTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = KeyValueStore(context, "inventory-transport-${UUID.randomUUID()}")
    private val sessions = SessionManager(store)
    private val runtime = LocalRuntime(context, store, sessions)
    private val api = ApiClient(sessions, runtime)
    private val inventory = InventoryRepository(api, store)

    private fun form(name: String = "照片测试食材", quantity: String = "2") =
        InventoryForm(name = name, quantity = quantity, storageType = "冷藏", shelfLife = "5")
    private fun payload(name: String = "照片测试食材") = JsonArray(listOf(InventoryRules.serialize(form(name))))
    private fun confirmation(items: JsonArray) = jsonOf("user_id" to "lin", "confirmed" to true, "items" to items)
    private fun headers(key: String) = mapOf("Idempotency-Key" to key)

    @Test fun recognizedCandidatesCanBeConfirmedAndSurviveRestart() = runBlocking {
        val drafts = RecognitionDrafts(store)
        drafts.save("lin", listOf(DraftItem(form()), DraftItem(form("照片第二种食材", "3"))))
        val items = JsonArray(drafts.load("lin").map { InventoryRules.serialize(it.form) })
        val before = inventory.read("lin")
        val saved = inventory.save("lin", items, recognition = true)
        assertEquals(before.size + 2, saved.size)
        assertEquals(2.0, saved.single { it.name == "照片测试食材" }.quantity, 0.0)
        assertEquals(3.0, saved.single { it.name == "照片第二种食材" }.quantity, 0.0)
        assertFalse(inventory.hasPendingWrite("lin"))
        assertTrue(drafts.clear("lin"))
        val restarted = InventoryRepository(ApiClient(sessions, LocalRuntime(context, store, sessions)), store)
        assertEquals(saved, restarted.read("lin"))
        assertTrue(RecognitionDrafts(store).load("lin").isEmpty())
    }

    @Test fun manualAddEditAndDeleteKeepRevisionProtection() = runBlocking {
        val added = inventory.save("lin", payload("手动测试食材")).single { it.name == "手动测试食材" }
        val edited = inventory.save("lin", jsonOf("quantity" to 4), id = added.id, revision = added.revision)
            .single { it.id == added.id }
        assertEquals(4.0, edited.quantity, 0.0)
        val stale = runCatching { inventory.save("lin", jsonOf("quantity" to 9), id = added.id, revision = added.revision) }.exceptionOrNull()
        assertEquals(409, (stale as? ApiException)?.status)
        assertFalse(inventory.hasPendingWrite("lin"))
        inventory.delete("lin", edited.id, edited.revision)
        assertFalse(inventory.read("lin").any { it.id == edited.id })
    }

    @Test fun readsNeverReserveWriteKeysAndRetriesDoNotDuplicateStock() = runBlocking {
        val key = headers("same-write")
        api.get("/inventory", headers = key)
        assertTrue(runtime.engine.rows("commands").isEmpty())
        val body = confirmation(payload())
        val first = api.post("/inventory/confirm-recognition", body, headers = key)
        assertEquals(first, api.post("/inventory/confirm-recognition", body, headers = key))
        assertEquals("lin", api.get("/auth/session", headers = key).asObject()?.obj("user")?.str("id"))
        assertNotNull(api.get("/inventory", headers = key).asArray())
        val conflict = runCatching {
            api.post("/inventory/confirm-recognition", confirmation(payload("不同食材")), headers = key)
        }.exceptionOrNull()
        assertEquals(409, (conflict as? ApiException)?.status)
        val saved = inventory.read("lin")
        assertEquals(1, saved.count { it.name == "照片测试食材" })
        assertEquals(2.0, saved.single { it.name == "照片测试食材" }.quantity, 0.0)
        assertFalse(saved.any { it.name == "不同食材" })
        assertEquals(1, runtime.engine.rows("commands").size)
    }

    @Test fun upgradeRemovesOnlyBogusAuthReceiptsAndPreservesDraftAndRealWrite() = runBlocking {
        // Reproduce persisted state from the old interceptor, plus an unrelated completed write.
        val body = confirmation(payload("已保存食材"))
        val auth = mapOf("Authorization" to "Bearer ${sessions.session.value!!.token}")
        assertEquals(200, runtime.engine.request("POST", "/api/inventory/confirm-recognition", body,
            headers = auth + headers("completed-write")).code)
        val bogus = jsonOf("id" to "lin:old-poisoned-key", "fingerprint" to "GET:/auth/session:{}",
            "result" to jsonOf("status" to "success", "user" to jsonOf("id" to "lin")))
        val validReceipts = runtime.engine.rows("commands")
        val stockBefore = runtime.engine.rows("stock")
        runtime.engine.add("commands", bogus)
        assertTrue(store.putSync("cookx:local-database:v1", runtime.engine.db.toString()))
        val draft = listOf(DraftItem(form("未提交照片食材")))
        RecognitionDrafts(store).save("lin", draft)

        val restored = LocalRuntime(context, store, sessions)
        val restoredApi = ApiClient(sessions, restored)
        assertEquals(stockBefore, restored.engine.rows("stock"))
        assertEquals(validReceipts, restored.engine.rows("commands"))
        assertEquals(draft, RecognitionDrafts(store).load("lin"))
        restoredApi.post("/inventory/confirm-recognition", body, headers = headers("completed-write"))
        assertEquals(stockBefore, restored.engine.rows("stock"))
        restoredApi.post("/inventory/confirm-recognition", confirmation(payload("未提交照片食材")), headers = headers("old-poisoned-key"))
        assertEquals(2.0, restored.engine.stock("lin").single { it.str("name") == "未提交照片食材" }.num("quantity")!!, 0.0)
        // The repair itself is durable and repeatable on later launches.
        assertEquals(restored.engine.db, LocalRuntime(context, store, sessions).engine.db)
    }

    @Test fun lostWriteResponseCanBeVerifiedAfterRestartWithoutAddingAgain() = runBlocking {
        var dropReply = true
        val interruptedApi = ApiClient(sessions, Interceptor { chain ->
            val response = runtime.intercept(chain)
            if (dropReply && chain.request().url.encodedPath.endsWith("/confirm-recognition")) {
                dropReply = false
                response.close()
                throw IOException("Simulated response loss after commit")
            }
            response
        })
        val interrupted = InventoryRepository(interruptedApi, store)
        assertTrue(runCatching { interrupted.save("lin", payload(), recognition = true) }.isFailure)
        assertTrue(interrupted.hasPendingWrite("lin"))
        val restored = InventoryRepository(ApiClient(sessions, LocalRuntime(context, store, sessions)), store)
        val verified = restored.save("lin", null, recognition = true)
        assertEquals(1, verified.count { it.name == "照片测试食材" })
        assertEquals(2.0, verified.single { it.name == "照片测试食材" }.quantity, 0.0)
        assertFalse(restored.hasPendingWrite("lin"))
    }
}
