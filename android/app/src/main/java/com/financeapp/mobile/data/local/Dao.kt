package com.financeapp.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import com.financeapp.mobile.util.WorkspaceOperation

@Dao
interface FinanceDao {
    @Query("""SELECT * FROM accounts WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun backupAccounts(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId): List<AccountEntity>
    @Query("""SELECT * FROM transactions WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun backupTransactions(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId): List<TransactionEntity>
    @Query("""SELECT * FROM categories WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun backupCategories(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId): List<CategoryEntity>
    @Query("""SELECT * FROM credit_cards_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun backupCards(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId): List<CreditCardEntity>
    @Query("""SELECT * FROM goals_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun backupGoals(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId): List<GoalEntity>
    @Query("""SELECT * FROM budgets_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun backupBudgets(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId): List<BudgetEntity>
    @Query("""SELECT * FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun backupGoalContributions(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId): List<GoalContributionEntity>
    @Query("""DELETE FROM accounts WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun clearAccountsForRestore(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId)

    @Query("DELETE FROM accounts WHERE userId = 0 AND workspaceId = 'default-0' AND id = :id")
    suspend fun deleteGuestAccount(id: Int)

    @Query("""DELETE FROM accounts WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :id""")
    suspend fun deleteAccountById(
        id: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("DELETE FROM workspaces_local WHERE userId = :userId AND id = :id")
    suspend fun deleteWorkspace(userId: Int, id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkspaces(items: List<WorkspaceEntity>)

    @Query("SELECT * FROM workspaces_local WHERE userId = :userId ORDER BY isDefault DESC, name COLLATE NOCASE")
    fun observeWorkspaces(userId: Int): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces_local WHERE userId = :userId")
    suspend fun workspacesForUser(userId: Int): List<WorkspaceEntity>

    @Query("""SELECT * FROM accounts WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId ORDER BY institutionName""")
    fun observeAccounts(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): Flow<List<AccountEntity>>

    @Query("""SELECT * FROM transactions WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId ORDER BY date DESC, id DESC""")
    fun observeTransactions(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): Flow<List<TransactionEntity>>

    @Query("""SELECT * FROM accounts WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE' ORDER BY id""")
    suspend fun pendingAccounts(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): List<AccountEntity>

    @Query("""SELECT * FROM categories WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId ORDER BY name COLLATE NOCASE""")
    fun observeCategories(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): Flow<List<CategoryEntity>>

    @Query("""SELECT * FROM credit_cards_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId ORDER BY bankName, lastFour""")
    fun observeCards(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): Flow<List<CreditCardEntity>>

    @Query("""SELECT * FROM goals_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId ORDER BY id DESC""")
    fun observeGoals(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): Flow<List<GoalEntity>>

    @Query("""SELECT * FROM budgets_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId ORDER BY categoryId""")
    fun observeBudgets(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): Flow<List<BudgetEntity>>

    @Query("""SELECT * FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId ORDER BY createdAt DESC, id DESC""")
    fun observeGoalContributions(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ):
        Flow<List<GoalContributionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccounts(items: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(item: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactions(items: List<TransactionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(item: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(item: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCards(items: List<CreditCardEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCard(item: CreditCardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoals(items: List<GoalEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoal(item: GoalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudgets(items: List<BudgetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudget(item: BudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoalContributions(
        items: List<GoalContributionEntity>
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoalContribution(
        item: GoalContributionEntity
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueOperation(item: PendingSyncOperationEntity)

    @Query("""SELECT * FROM categories WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE' ORDER BY id""")
    suspend fun pendingCategories(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): List<CategoryEntity>

    @Query("""SELECT * FROM credit_cards_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE' ORDER BY id""")
    suspend fun pendingCards(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): List<CreditCardEntity>

    @Query("""SELECT * FROM transactions WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE' ORDER BY id""")
    suspend fun pendingTransactions(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): List<TransactionEntity>

    @Query("""SELECT * FROM goals_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE' ORDER BY id""")
    suspend fun pendingGoals(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): List<GoalEntity>

    @Query("""SELECT * FROM budgets_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_SET' ORDER BY categoryId""")
    suspend fun pendingBudgets(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): List<BudgetEntity>

    @Query("""SELECT * FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE' ORDER BY createdAt, id""")
    suspend fun pendingGoalContributions(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ):
        List<GoalContributionEntity>

    @Query("""SELECT COALESCE(SUM(amount), 0) FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND goalId = :goalId AND syncState = 'PENDING_CREATE'""")
    suspend fun pendingContributionTotalForGoal(goalId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): Double

    @Query("""SELECT * FROM pending_sync_operations WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId ORDER BY createdAt, id""")
    suspend fun pendingOperations(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): List<PendingSyncOperationEntity>

    @Query("""
        SELECT
            (SELECT COUNT(*) FROM pending_sync_operations WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId) +
            (SELECT COUNT(*) FROM accounts WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM categories WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM credit_cards_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM transactions WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM goals_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM budgets_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_SET')
        """)
    fun observePendingSyncCount(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): Flow<Int>

    @Query("""
        SELECT
            (SELECT COUNT(*) FROM pending_sync_operations WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId) +
            (SELECT COUNT(*) FROM accounts WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM categories WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM credit_cards_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM transactions WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM goals_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE') +
            (SELECT COUNT(*) FROM budgets_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_SET')
        """)
    suspend fun pendingSyncCountNow(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): Int


    @Query("""SELECT * FROM categories WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :id LIMIT 1""")
    suspend fun categoryById(id: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): CategoryEntity?

    @Query("""SELECT * FROM categories WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun allCategoriesNow(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ):
        List<CategoryEntity>

    @Query("""SELECT * FROM accounts WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :id LIMIT 1""")
    suspend fun accountById(id: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): AccountEntity?

    @Query("""SELECT * FROM credit_cards_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :id LIMIT 1""")
    suspend fun cardById(id: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): CreditCardEntity?

    @Query("""SELECT * FROM transactions WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :id LIMIT 1""")
    suspend fun transactionById(id: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): TransactionEntity?

    @Query("""SELECT * FROM transactions WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND installmentGroup = :group ORDER BY installmentNumber, date, id""")
    suspend fun transactionsByInstallmentGroup(group: String,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): List<TransactionEntity>

    @Query("""SELECT * FROM goals_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :id LIMIT 1""")
    suspend fun goalById(id: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): GoalEntity?

    @Query("""SELECT * FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :id LIMIT 1""")
    suspend fun goalContributionById(id: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): GoalContributionEntity?

    @Query("""SELECT * FROM budgets_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND categoryId = :categoryId LIMIT 1""")
    suspend fun budgetByCategoryId(categoryId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    ): BudgetEntity?

    @Query("""UPDATE transactions SET accountId = :newAccountId WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND accountId = :oldAccountId""")
    suspend fun remapTransactionAccount(oldAccountId: Int, newAccountId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId)

    @Query("""UPDATE transactions SET categoryId = :newCategoryId WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND categoryId = :oldCategoryId""")
    suspend fun remapTransactionCategory(oldCategoryId: Int, newCategoryId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""UPDATE transactions SET cardId = :newCardId WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND cardId = :oldCardId""")
    suspend fun remapTransactionCard(oldCardId: Int, newCardId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""UPDATE goal_contributions_local SET goalId = :newGoalId WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND goalId = :oldGoalId""")
    suspend fun remapGoalContributions(oldGoalId: Int,
        newGoalId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""UPDATE budgets_local SET categoryId = :newCategoryId WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND categoryId = :oldCategoryId""")
    suspend fun remapBudgetCategory(oldCategoryId: Int, newCategoryId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""UPDATE transactions SET categoryId = NULL WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND categoryId = :categoryId""")
    suspend fun clearCategoryFromTransactions(categoryId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""UPDATE transactions SET cardId = NULL WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND cardId = :cardId""")
    suspend fun clearCardFromTransactions(cardId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM categories WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :categoryId""")
    suspend fun deleteCategoryById(categoryId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM credit_cards_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :cardId""")
    suspend fun deleteCardById(cardId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""UPDATE credit_cards_local SET active = 0 WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :cardId""")
    suspend fun archiveCard(cardId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM transactions WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :transactionId""")
    suspend fun deleteTransactionById(transactionId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM goals_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :goalId""")
    suspend fun deleteGoalById(goalId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM budgets_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND categoryId = :categoryId""")
    suspend fun deleteBudgetByCategoryId(categoryId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :id""")
    suspend fun deleteGoalContributionById(id: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND goalId = :goalId""")
    suspend fun deleteGoalContributionsByGoal(goalId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM pending_sync_operations WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND id = :operationId""")
    suspend fun deleteOperation(operationId: Long,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM pending_sync_operations WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND entityType = :entityType AND entityId = :entityId""")
    suspend fun deleteOperationsForEntity(entityType: String,
        entityId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM pending_sync_operations WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND entityType = :entityType AND entityId = :entityId AND action = :action""")
    suspend fun deleteOperationsForEntityAction(entityType: String,
        entityId: Int,
        action: String,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM accounts WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'SYNCED' AND id NOT IN (SELECT entityId FROM pending_sync_operations WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND entityType = 'ACCOUNT')""")
    suspend fun clearAccounts(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM transactions WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'SYNCED'""")
    suspend fun clearSyncedTransactions(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM categories WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'SYNCED'""")
    suspend fun clearSyncedCategories(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM credit_cards_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'SYNCED'""")
    suspend fun clearSyncedCards(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM goals_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'SYNCED'""")
    suspend fun clearSyncedGoals(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM budgets_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'SYNCED'""")
    suspend fun clearSyncedBudgets(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'SYNCED'""")
    suspend fun clearSyncedGoalContributions(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM transactions WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun clearTransactions(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM categories WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun clearCategories(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM credit_cards_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun clearCards(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM goals_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun clearGoals(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM budgets_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun clearBudgets(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM goal_contributions_local WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun clearGoalContributions(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Query("""DELETE FROM pending_sync_operations WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId""")
    suspend fun clearPendingOperations(
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    // V3.25.6.3 - descarta somente o estado de sincronizacao legado do Workspace.
    // Os dados locais permanecem visiveis; apenas deixam de bloquear a fila.
    @Query("""UPDATE accounts SET syncState = 'SYNCED' WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE'""")
    suspend fun acceptPendingAccountsAsLocal(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId)

    @Query("""UPDATE categories SET syncState = 'SYNCED' WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE'""")
    suspend fun acceptPendingCategoriesAsLocal(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId)

    @Query("""UPDATE credit_cards_local SET syncState = 'SYNCED' WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE'""")
    suspend fun acceptPendingCardsAsLocal(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId)

    @Query("""UPDATE transactions SET syncState = 'SYNCED' WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE'""")
    suspend fun acceptPendingTransactionsAsLocal(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId)

    @Query("""UPDATE goals_local SET syncState = 'SYNCED' WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE'""")
    suspend fun acceptPendingGoalsAsLocal(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId)

    @Query("""UPDATE goal_contributions_local SET syncState = 'SYNCED' WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_CREATE'""")
    suspend fun acceptPendingGoalContributionsAsLocal(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId)

    @Query("""UPDATE budgets_local SET syncState = 'SYNCED' WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND syncState = 'PENDING_SET'""")
    suspend fun acceptPendingBudgetsAsLocal(scopeUserId: Int = WorkspaceOperation.current().userId, scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId)

    @Transaction
    suspend fun replacePendingAccount(localId: Int, serverAccount: AccountEntity) {
        remapTransactionAccount(localId, serverAccount.id)
        deleteAccountById(localId)
        upsertAccount(serverAccount)
    }

    @Transaction
    suspend fun replacePendingCategory(
        localId: Int,
        serverCategory: CategoryEntity
    ) {
        remapTransactionCategory(localId, serverCategory.id)

        val localBudget = budgetByCategoryId(localId)
        if (localBudget != null) {
            deleteBudgetByCategoryId(localId)
            upsertBudget(
                localBudget.copy(
                    categoryId = serverCategory.id
                )
            )
        }

        deleteCategoryById(localId)
        upsertCategory(serverCategory)
    }

    @Query("""UPDATE pending_sync_operations SET entityId = :serverId WHERE userId = :scopeUserId AND workspaceId = :scopeWorkspaceId AND entityType = 'ATTACHMENT' AND entityId = :localId""")
    suspend fun remapPendingAttachmentTransaction(
        localId: Int,
        serverId: Int,
        scopeUserId: Int = WorkspaceOperation.current().userId,
        scopeWorkspaceId: String = WorkspaceOperation.current().workspaceId
    )

    @Transaction
    suspend fun replacePendingTransaction(
        localId: Int,
        serverTransaction: TransactionEntity
    ) {
        // Os comprovantes criados offline ficam presos ao ID local da transacao.
        // Remapeamos antes de remover a linha local para que nada seja perdido.
        remapPendingAttachmentTransaction(localId, serverTransaction.id)
        deleteTransactionById(localId)
        upsertTransaction(serverTransaction)
    }

    @Transaction
    suspend fun replacePendingGoal(
        localId: Int,
        serverGoal: GoalEntity
    ) {
        remapGoalContributions(
            localId,
            serverGoal.id
        )
        deleteGoalById(localId)
        upsertGoal(serverGoal)
    }

    @Transaction
    suspend fun replacePendingCard(
        localId: Int,
        serverCard: CreditCardEntity
    ) {
        remapTransactionCard(localId, serverCard.id)
        deleteCardById(localId)
        upsertCard(serverCard)
    }

}
