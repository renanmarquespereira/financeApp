package com.financeapp.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        CreditCardEntity::class,
        TransactionEntity::class,
        CategoryEntity::class,
        GoalEntity::class,
        BudgetEntity::class,
        PendingSyncOperationEntity::class,
        GoalContributionEntity::class,
        WorkspaceEntity::class
    ],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao
}
