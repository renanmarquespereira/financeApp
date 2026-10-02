package com.financeapp.mobile.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financeapp.mobile.data.remote.FinanceApi
import com.financeapp.mobile.data.repository.FinanceRepository
import com.financeapp.mobile.sync.OfflineSyncTracker
import com.financeapp.mobile.util.SessionManager
import com.financeapp.mobile.util.WorkspaceOperation
import com.financeapp.mobile.util.WorkspaceScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID

class WorkspaceSelectionTest {
    @Test fun refreshPurgesDeletedWorkspaceButPreservesOtherScopes() = runBlocking {
        val context = isolatedContext()
        val session = SessionManager(context)
        session.save(token(7), token(7))
        session.activateWorkspace(WorkspaceScope(7, "deleted"))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val api = Proxy.newProxyInstance(FinanceApi::class.java.classLoader, arrayOf(FinanceApi::class.java)) { _, method, _ ->
            check(method.name == "workspaces")
            listOf(com.financeapp.mobile.data.remote.WorkspaceDto("default-7", 7, "Pessoal", "personal", true, null))
        } as FinanceApi
        val repo = FinanceRepository(api, db.financeDao(), db, session, OfflineSyncTracker(), context)
        try {
            db.financeDao().upsertWorkspaces(listOf(
                WorkspaceEntity(7, "default-7", "Pessoal", "personal", true, null),
                WorkspaceEntity(7, "deleted", "Excluído", "business", false, null),
                WorkspaceEntity(8, "default-8", "Outro usuário", "personal", true, null)))
            db.financeDao().upsertCategory(CategoryEntity(1,"Manter",null,userId=7,workspaceId="default-7"))
            db.financeDao().upsertCategory(CategoryEntity(1,"Apagar",null,userId=7,workspaceId="deleted"))
            db.financeDao().upsertCategory(CategoryEntity(1,"Outro",null,userId=8,workspaceId="default-8"))
            repo.refreshWorkspaces()
            assertEquals("default-7",session.requireWorkspace().workspaceId)
            assertEquals(1,db.financeDao().workspacesForUser(7).size)
            assertEquals(1,db.financeDao().workspacesForUser(8).size)
            assertNull(db.financeDao().categoryById(1,7,"deleted"))
            assertEquals("Manter",db.financeDao().categoryById(1,7,"default-7")!!.name)
            assertEquals("Outro",db.financeDao().categoryById(1,8,"default-8")!!.name)
            repo.withWorkspace(WorkspaceScope(7,"deleted")) { repo.syncPendingNow() }
        } finally { db.close() }
    }
    private fun isolatedContext(): Context {
        val suffix = UUID.randomUUID().toString()
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("test-$suffix-$name", mode)
        }
    }
    private fun token(id: Int) = "test." + Base64.encodeToString("{\"sub\":\"$id\"}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP) + ".test"

    @Test fun selectionPersistsPerUserAndSurvivesRefresh() {
        val context = isolatedContext()
        val session = SessionManager(context)
        session.save(token(7), token(7))
        session.activateWorkspace(WorkspaceScope(7, "company"))
        session.save(token(7), token(7))
        assertEquals("company", SessionManager(context).requireWorkspace().workspaceId)
        session.clear()
        assertNull(session.workspaceScope.value)
        session.save(token(8), token(8))
        assertEquals("default-8", session.requireWorkspace().workspaceId)
        session.save(token(7), token(7))
        assertEquals("company", session.requireWorkspace().workspaceId)
    }

    @Test fun localSwitchDoesNotWaitForNetworkAndDoesNotMoveOldOperation() = runBlocking {
        val context = isolatedContext()
        val session = SessionManager(context)
        session.save(token(7), token(7))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val api = Proxy.newProxyInstance(FinanceApi::class.java.classLoader, arrayOf(FinanceApi::class.java)) { _, _, _ ->
            error("Offline selection must not call the API")
        } as FinanceApi
        val repo = FinanceRepository(api, db.financeDao(), db, session, OfflineSyncTracker(), context)
        try {
            db.financeDao().upsertWorkspaces(listOf(
                WorkspaceEntity(7, "default-7", "Pessoal", "personal", true, null),
                WorkspaceEntity(7, "company", "Empresa", "business", false, null)
            ))
            WorkspaceOperation.run(WorkspaceOperation(WorkspaceScope(7, "default-7"), null)) {
                db.financeDao().upsertCategory(CategoryEntity(-1, "Pessoal", null, "PENDING_CREATE"))
            }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = async {
                repo.withWorkspace {
                    started.complete(Unit)
                    release.await()
                    assertEquals("default-7", WorkspaceOperation.current().workspaceId)
                    assertEquals("Pessoal", db.financeDao().categoryById(-1)!!.name)
                }
            }
            started.await()
            withTimeout(2_000) { repo.selectWorkspace("company") }
            assertEquals("company", session.requireWorkspace().workspaceId)
            assertTrue(repo.categoriesFlow.first().isEmpty())
            release.complete(Unit)
            old.await()
            try {
                repo.archiveWorkspace("default-7")
                fail("Default workspace cannot be archived")
            } catch (_: IllegalStateException) { }
        } finally { db.close() }
    }
}
