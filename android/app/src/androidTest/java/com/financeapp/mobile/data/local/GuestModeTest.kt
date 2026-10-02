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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID

class GuestModeTest {
    private fun context(): Context {
        val id=UUID.randomUUID().toString()
        return object: ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences("guest-test-$id-$name",mode)
            override fun getFilesDir():File=File(super.getCacheDir(),"guest-test-$id").apply { mkdirs() }
        }
    }
    private fun token(id:Int)="test."+Base64.encodeToString("{\"sub\":\"$id\"}".toByteArray(),Base64.URL_SAFE or Base64.NO_WRAP)+".test"

    @Test fun visitorPersistsAndManualDataNeverCallsServer()=runBlocking {
        val ctx=context();val session=SessionManager(ctx);session.startGuest()
        assertTrue(SessionManager(ctx).isGuest());assertEquals(0,session.requireWorkspace().userId)
        assertTrue(session.isLoggedIn());assertNull(session.accessToken())
        val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build()
        val api=Proxy.newProxyInstance(FinanceApi::class.java.classLoader,arrayOf(FinanceApi::class.java)){_,_,_->error("Guest must not call API")} as FinanceApi
        val repo=FinanceRepository(api,db.financeDao(),db,session,OfflineSyncTracker(),ctx)
        try {
            repo.refreshWorkspaces();repo.refresh();repo.syncPendingNow()
            repo.createGuestAccount("Carteira")
            val account=repo.accounts.first().single()
            repo.createManual(account.id,null,"Café",12.5,"debit","2026-09-06T12:00:00Z")
            assertEquals(-12.5,repo.transactions.first().single().amount,0.001)
            session.clear();session.startGuest()
            assertEquals(1,repo.transactions.first().size)
            session.save(token(7),token(7))
            assertFalse(session.isGuest());assertEquals(7,session.requireWorkspace().userId)
            assertTrue(repo.transactions.first().isEmpty())
            assertEquals(1,db.financeDao().observeTransactions(0,"default-0").first().size)
        } finally {db.close()}
    }

    @Test fun failedTransferPreservesDataAndReusesFrozenSnapshot()=runBlocking {
        val ctx=context();val session=SessionManager(ctx);session.startGuest()
        val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build()
        val sent=mutableListOf<String>()
        val api=Proxy.newProxyInstance(FinanceApi::class.java.classLoader,arrayOf(FinanceApi::class.java)){_,method,args->
            check(method.name=="importGuest");sent.add(args!![0].toString())
            @Suppress("UNCHECKED_CAST")
            val continuation=args.last() as kotlin.coroutines.Continuation<Any?>
            continuation.resumeWith(Result.failure(IOException("Offline")))
            kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
        } as FinanceApi
        val repo=FinanceRepository(api,db.financeDao(),db,session,OfflineSyncTracker(),ctx)
        try {
            repo.createGuestAccount("Manter")
            session.requestGuestTransfer();session.save(token(7),token(7))
            repeat(2) { try {repo.transferGuestData();fail("Expected failure")}catch(_:IOException){} }
            assertEquals(2,sent.size);assertEquals(sent[0],sent[1])
            assertEquals(1,db.financeDao().observeAccounts(0,"default-0").first().size)
            assertTrue(repo.guestTransferFrozen());assertTrue(session.guestTransferPending())
        } finally {db.close()}
    }
}
