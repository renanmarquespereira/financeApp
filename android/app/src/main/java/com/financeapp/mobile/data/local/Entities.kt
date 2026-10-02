package com.financeapp.mobile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.financeapp.mobile.util.WorkspaceOperation

@Entity(tableName = "accounts", primaryKeys = ["userId", "workspaceId", "id"])
data class AccountEntity(
    val id: Int,
    val institutionName: String,
    val institutionId: String?,
    val accountName: String?,
    val maskedAccount: String?,
    val externalAccountId: String?,
    val connectionStatus: String = "manual",
    val currentBalance: Double? = null,
    val balanceUpdatedAt: String? = null,
    val syncState: String = "SYNCED",
    val userId: Int = WorkspaceOperation.current().userId,
    val workspaceId: String = WorkspaceOperation.current().workspaceId
)

@Entity(tableName = "categories", primaryKeys = ["userId", "workspaceId", "id"])
data class CategoryEntity(
    val id: Int,
    val name: String,
    val icon: String?,
    val syncState: String = "SYNCED",
    val userId: Int = WorkspaceOperation.current().userId,
    val workspaceId: String = WorkspaceOperation.current().workspaceId
)

@Entity(tableName = "credit_cards_local", primaryKeys = ["userId", "workspaceId", "id"])
data class CreditCardEntity(
    val id: Int,
    val bankName: String,
    val brand: String,
    val lastFour: String,
    val nickname: String?,
    val creditLimit: Double? = null,
    val closingDay: Int? = null,
    val dueDay: Int? = null,
    val active: Boolean = true,
    val syncState: String = "SYNCED",
    val userId: Int = WorkspaceOperation.current().userId,
    val workspaceId: String = WorkspaceOperation.current().workspaceId
)

@Entity(tableName = "transactions", primaryKeys = ["userId", "workspaceId", "id"])
data class TransactionEntity(
    val id: Int,
    val accountId: Int?,
    val categoryId: Int?,
    val cardId: Int? = null,
    val installmentGroup: String? = null,
    val installmentNumber: Int? = null,
    val installmentTotal: Int? = null,
    val purchaseDate: String? = null,
    val date: String,
    val description: String,
    val amount: Double,
    val transactionType: String,
    val status: String,
    val source: String,
    val externalTransactionId: String?,
    val syncState: String = "SYNCED",
    val userId: Int = WorkspaceOperation.current().userId,
    val workspaceId: String = WorkspaceOperation.current().workspaceId
)

@Entity(tableName = "goals_local", primaryKeys = ["userId", "workspaceId", "id"])
data class GoalEntity(
    val id: Int,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double,
    val targetDate: String?,
    val syncState: String = "SYNCED",
    val userId: Int = WorkspaceOperation.current().userId,
    val workspaceId: String = WorkspaceOperation.current().workspaceId
)

@Entity(tableName = "budgets_local", primaryKeys = ["userId", "workspaceId", "categoryId"])
data class BudgetEntity(
    val categoryId: Int,
    val serverId: Int?,
    val amount: Double,
    val syncState: String = "SYNCED",
    val userId: Int = WorkspaceOperation.current().userId,
    val workspaceId: String = WorkspaceOperation.current().workspaceId
)

@Entity(tableName = "pending_sync_operations")
data class PendingSyncOperationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val entityType: String,
    val action: String,
    val entityId: Int,
    val payload: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val userId: Int = WorkspaceOperation.current().userId,
    val workspaceId: String = WorkspaceOperation.current().workspaceId
)


@Entity(tableName = "goal_contributions_local", primaryKeys = ["userId", "workspaceId", "id"])
data class GoalContributionEntity(
    val id: Int,
    val goalId: Int,
    val amount: Double,
    val createdAt: String,
    val clientKey: String,
    val syncState: String = "SYNCED",
    val userId: Int = WorkspaceOperation.current().userId,
    val workspaceId: String = WorkspaceOperation.current().workspaceId
)


@Entity(tableName = "workspaces_local", primaryKeys = ["userId", "id"])
data class WorkspaceEntity(
    val userId: Int,
    val id: String,
    val name: String,
    val kind: String,
    val isDefault: Boolean,
    val archivedAt: String?
)
