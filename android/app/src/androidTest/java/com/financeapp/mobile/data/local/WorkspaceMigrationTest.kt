package com.financeapp.mobile.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financeapp.mobile.util.WorkspaceOperation
import com.financeapp.mobile.util.WorkspaceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun migrated(owner: Int?): AppDatabase {
        val name = "v39-test-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("CREATE TABLE accounts (id INTEGER NOT NULL, institutionName TEXT NOT NULL, institutionId TEXT, accountName TEXT, maskedAccount TEXT, externalAccountId TEXT, connectionStatus TEXT NOT NULL, currentBalance REAL, balanceUpdatedAt TEXT, PRIMARY KEY(id))")
            legacy.execSQL("CREATE TABLE categories (id INTEGER NOT NULL, name TEXT NOT NULL, icon TEXT, syncState TEXT NOT NULL, PRIMARY KEY(id))")
            legacy.execSQL("CREATE TABLE credit_cards_local (id INTEGER NOT NULL, bankName TEXT NOT NULL, brand TEXT NOT NULL, lastFour TEXT NOT NULL, nickname TEXT, active INTEGER NOT NULL, syncState TEXT NOT NULL, PRIMARY KEY(id))")
            legacy.execSQL("CREATE TABLE transactions (id INTEGER NOT NULL, accountId INTEGER, categoryId INTEGER, cardId INTEGER, installmentGroup TEXT, installmentNumber INTEGER, installmentTotal INTEGER, purchaseDate TEXT, date TEXT NOT NULL, description TEXT NOT NULL, amount REAL NOT NULL, transactionType TEXT NOT NULL, status TEXT NOT NULL, source TEXT NOT NULL, externalTransactionId TEXT, syncState TEXT NOT NULL, PRIMARY KEY(id))")
            legacy.execSQL("CREATE TABLE goals_local (id INTEGER NOT NULL, name TEXT NOT NULL, targetAmount REAL NOT NULL, currentAmount REAL NOT NULL, targetDate TEXT, syncState TEXT NOT NULL, PRIMARY KEY(id))")
            legacy.execSQL("CREATE TABLE budgets_local (categoryId INTEGER NOT NULL, serverId INTEGER, amount REAL NOT NULL, syncState TEXT NOT NULL, PRIMARY KEY(categoryId))")
            legacy.execSQL("CREATE TABLE pending_sync_operations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entityType TEXT NOT NULL, action TEXT NOT NULL, entityId INTEGER NOT NULL, payload TEXT, createdAt INTEGER NOT NULL)")
            legacy.execSQL("CREATE TABLE goal_contributions_local (id INTEGER NOT NULL, goalId INTEGER NOT NULL, amount REAL NOT NULL, createdAt TEXT NOT NULL, clientKey TEXT NOT NULL, syncState TEXT NOT NULL, PRIMARY KEY(id))")
            legacy.execSQL("INSERT INTO categories VALUES (-11, 'Offline', NULL, 'PENDING_CREATE')")
            legacy.execSQL("INSERT INTO transactions (id, accountId, categoryId, date, description, amount, transactionType, status, source, externalTransactionId, syncState) VALUES (-12, NULL, -11, '2026-09-01', 'Pendente', -25.5, 'debit', 'posted', 'manual', NULL, 'PENDING_CREATE')")
            legacy.execSQL("INSERT INTO pending_sync_operations VALUES (8, 'TRANSACTION', 'DELETE', 99, NULL, 123456)")
            legacy.version = 11
        }
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(WorkspaceMigration(owner)).build().also {
                // Forces Room's complete schema comparison, not just SQL execution.
                it.openHelper.writableDatabase
            }
    }

    @Test fun migrationPreservesQueueAndNegativeIds() = runBlocking {
        val db = migrated(7)
        try {
            WorkspaceOperation.run(WorkspaceOperation(WorkspaceScope(7, "default-7"), null)) {
                val dao = db.financeDao()
                assertEquals("Offline", dao.categoryById(-11)!!.name)
                assertEquals(-25.5, dao.transactionById(-12)!!.amount, 0.0)
                assertEquals(-11, dao.transactionById(-12)!!.categoryId)
                assertEquals(8L, dao.pendingOperations().single().id)
                assertEquals(3, dao.pendingSyncCountNow())
            }
        } finally { db.close() }
    }

    @Test fun partitionedIdsDeletesCountsAndRemaps() = runBlocking {
        val db = migrated(7)
        try {
            val dao = db.financeDao()
            WorkspaceOperation.run(WorkspaceOperation(WorkspaceScope(7, "company"), null)) {
                dao.upsertCategory(CategoryEntity(-11, "Empresa", null, "PENDING_CREATE"))
                assertEquals(1, dao.pendingSyncCountNow())
                dao.replacePendingCategory(-11, CategoryEntity(22, "Empresa", null))
                dao.clearPendingOperations()
                assertEquals(22, dao.observeCategories().first().single().id)
            }
            WorkspaceOperation.run(WorkspaceOperation(WorkspaceScope(7, "default-7"), null)) {
                assertEquals("Offline", dao.categoryById(-11)!!.name)
                assertEquals(-11, dao.transactionById(-12)!!.categoryId)
                assertEquals(1, dao.pendingOperations().size)
            }
            WorkspaceOperation.run(WorkspaceOperation(WorkspaceScope(8, "default-8"), null)) {
                assertNull(dao.categoryById(-11))
                assertEquals(0, dao.pendingSyncCountNow())
                dao.clearTransactions()
            }
        } finally { db.close() }
    }

    @Test fun missingLegacyIdentityNeverAssignsDataToNextLogin() = runBlocking {
        val db = migrated(null)
        try {
            WorkspaceOperation.run(WorkspaceOperation(WorkspaceScope(7, "default-7"), null)) {
                assertEquals(0, db.financeDao().pendingSyncCountNow())
            }
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM pending_sync_operations WHERE userId=0 AND workspaceId='legacy-unassigned'").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally { db.close() }
    }

    @Test fun operationContextSurvivesDispatcherChanges() = runBlocking {
        val op = WorkspaceOperation(WorkspaceScope(7, "company"), "captured-token")
        WorkspaceOperation.run(op) {
            withContext(Dispatchers.Default) {
                assertEquals("company", WorkspaceOperation.current().workspaceId)
                assertEquals("Bearer captured-token", WorkspaceOperation.current().authorization)
            }
        }
        assertNull(WorkspaceOperation.currentOrNull())
    }
}
