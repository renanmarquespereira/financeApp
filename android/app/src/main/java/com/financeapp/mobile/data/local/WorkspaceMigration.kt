package com.financeapp.mobile.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class WorkspaceMigration(private val legacyUserId: Int?) : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No account token? Keep rows under an inaccessible owner, never guess a new owner.
        val ownerId = legacyUserId ?: 0
        val workspaceId = if (ownerId > 0) "default-$ownerId" else "legacy-unassigned"
        db.execSQL("CREATE TABLE workspaces_local (userId INTEGER NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, isDefault INTEGER NOT NULL, archivedAt TEXT, PRIMARY KEY(userId, id))")
        db.execSQL("INSERT INTO workspaces_local VALUES (?, ?, 'Pessoal', 'personal', 1, NULL)", arrayOf<Any>(ownerId, workspaceId))
        db.execSQL("CREATE TABLE accounts_v39 (id INTEGER NOT NULL, institutionName TEXT NOT NULL, institutionId TEXT, accountName TEXT, maskedAccount TEXT, externalAccountId TEXT, connectionStatus TEXT NOT NULL, currentBalance REAL, balanceUpdatedAt TEXT, userId INTEGER NOT NULL, workspaceId TEXT NOT NULL, PRIMARY KEY(userId, workspaceId, id))")
        db.execSQL("INSERT INTO accounts_v39 (id, institutionName, institutionId, accountName, maskedAccount, externalAccountId, connectionStatus, currentBalance, balanceUpdatedAt, userId, workspaceId) SELECT id, institutionName, institutionId, accountName, maskedAccount, externalAccountId, connectionStatus, currentBalance, balanceUpdatedAt, ?, ? FROM accounts", arrayOf<Any>(ownerId, workspaceId))
        db.execSQL("DROP TABLE accounts")
        db.execSQL("ALTER TABLE accounts_v39 RENAME TO accounts")
        db.execSQL("CREATE TABLE categories_v39 (id INTEGER NOT NULL, name TEXT NOT NULL, icon TEXT, syncState TEXT NOT NULL, userId INTEGER NOT NULL, workspaceId TEXT NOT NULL, PRIMARY KEY(userId, workspaceId, id))")
        db.execSQL("INSERT INTO categories_v39 (id, name, icon, syncState, userId, workspaceId) SELECT id, name, icon, syncState, ?, ? FROM categories", arrayOf<Any>(ownerId, workspaceId))
        db.execSQL("DROP TABLE categories")
        db.execSQL("ALTER TABLE categories_v39 RENAME TO categories")
        db.execSQL("CREATE TABLE credit_cards_local_v39 (id INTEGER NOT NULL, bankName TEXT NOT NULL, brand TEXT NOT NULL, lastFour TEXT NOT NULL, nickname TEXT, active INTEGER NOT NULL, syncState TEXT NOT NULL, userId INTEGER NOT NULL, workspaceId TEXT NOT NULL, PRIMARY KEY(userId, workspaceId, id))")
        db.execSQL("INSERT INTO credit_cards_local_v39 (id, bankName, brand, lastFour, nickname, active, syncState, userId, workspaceId) SELECT id, bankName, brand, lastFour, nickname, active, syncState, ?, ? FROM credit_cards_local", arrayOf<Any>(ownerId, workspaceId))
        db.execSQL("DROP TABLE credit_cards_local")
        db.execSQL("ALTER TABLE credit_cards_local_v39 RENAME TO credit_cards_local")
        db.execSQL("CREATE TABLE transactions_v39 (id INTEGER NOT NULL, accountId INTEGER, categoryId INTEGER, cardId INTEGER, installmentGroup TEXT, installmentNumber INTEGER, installmentTotal INTEGER, purchaseDate TEXT, date TEXT NOT NULL, description TEXT NOT NULL, amount REAL NOT NULL, transactionType TEXT NOT NULL, status TEXT NOT NULL, source TEXT NOT NULL, externalTransactionId TEXT, syncState TEXT NOT NULL, userId INTEGER NOT NULL, workspaceId TEXT NOT NULL, PRIMARY KEY(userId, workspaceId, id))")
        db.execSQL("INSERT INTO transactions_v39 (id, accountId, categoryId, cardId, installmentGroup, installmentNumber, installmentTotal, purchaseDate, date, description, amount, transactionType, status, source, externalTransactionId, syncState, userId, workspaceId) SELECT id, accountId, categoryId, cardId, installmentGroup, installmentNumber, installmentTotal, purchaseDate, date, description, amount, transactionType, status, source, externalTransactionId, syncState, ?, ? FROM transactions", arrayOf<Any>(ownerId, workspaceId))
        db.execSQL("DROP TABLE transactions")
        db.execSQL("ALTER TABLE transactions_v39 RENAME TO transactions")
        db.execSQL("CREATE TABLE goals_local_v39 (id INTEGER NOT NULL, name TEXT NOT NULL, targetAmount REAL NOT NULL, currentAmount REAL NOT NULL, targetDate TEXT, syncState TEXT NOT NULL, userId INTEGER NOT NULL, workspaceId TEXT NOT NULL, PRIMARY KEY(userId, workspaceId, id))")
        db.execSQL("INSERT INTO goals_local_v39 (id, name, targetAmount, currentAmount, targetDate, syncState, userId, workspaceId) SELECT id, name, targetAmount, currentAmount, targetDate, syncState, ?, ? FROM goals_local", arrayOf<Any>(ownerId, workspaceId))
        db.execSQL("DROP TABLE goals_local")
        db.execSQL("ALTER TABLE goals_local_v39 RENAME TO goals_local")
        db.execSQL("CREATE TABLE budgets_local_v39 (categoryId INTEGER NOT NULL, serverId INTEGER, amount REAL NOT NULL, syncState TEXT NOT NULL, userId INTEGER NOT NULL, workspaceId TEXT NOT NULL, PRIMARY KEY(userId, workspaceId, categoryId))")
        db.execSQL("INSERT INTO budgets_local_v39 (categoryId, serverId, amount, syncState, userId, workspaceId) SELECT categoryId, serverId, amount, syncState, ?, ? FROM budgets_local", arrayOf<Any>(ownerId, workspaceId))
        db.execSQL("DROP TABLE budgets_local")
        db.execSQL("ALTER TABLE budgets_local_v39 RENAME TO budgets_local")
        db.execSQL("CREATE TABLE pending_sync_operations_v39 (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entityType TEXT NOT NULL, action TEXT NOT NULL, entityId INTEGER NOT NULL, payload TEXT, createdAt INTEGER NOT NULL, userId INTEGER NOT NULL, workspaceId TEXT NOT NULL)")
        db.execSQL("INSERT INTO pending_sync_operations_v39 (id, entityType, action, entityId, payload, createdAt, userId, workspaceId) SELECT id, entityType, action, entityId, payload, createdAt, ?, ? FROM pending_sync_operations", arrayOf<Any>(ownerId, workspaceId))
        db.execSQL("DROP TABLE pending_sync_operations")
        db.execSQL("ALTER TABLE pending_sync_operations_v39 RENAME TO pending_sync_operations")
        db.execSQL("CREATE TABLE goal_contributions_local_v39 (id INTEGER NOT NULL, goalId INTEGER NOT NULL, amount REAL NOT NULL, createdAt TEXT NOT NULL, clientKey TEXT NOT NULL, syncState TEXT NOT NULL, userId INTEGER NOT NULL, workspaceId TEXT NOT NULL, PRIMARY KEY(userId, workspaceId, id))")
        db.execSQL("INSERT INTO goal_contributions_local_v39 (id, goalId, amount, createdAt, clientKey, syncState, userId, workspaceId) SELECT id, goalId, amount, createdAt, clientKey, syncState, ?, ? FROM goal_contributions_local", arrayOf<Any>(ownerId, workspaceId))
        db.execSQL("DROP TABLE goal_contributions_local")
        db.execSQL("ALTER TABLE goal_contributions_local_v39 RENAME TO goal_contributions_local")
    }
}
