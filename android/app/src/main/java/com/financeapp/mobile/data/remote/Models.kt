package com.financeapp.mobile.data.remote

import com.google.gson.annotations.SerializedName

data class LoginRequest(val email: String, val password: String)
data class GoogleLoginRequest(@SerializedName("id_token") val idToken: String)
data class RefreshRequest(@SerializedName("refresh_token") val refreshToken: String)
data class RegisterRequest(
    val email: String,
    val name: String,
    val cpf: String,
    val password: String,
    @SerializedName("birth_date") val birthDate: String,
    val sex: String,
    @SerializedName("legal_accepted") val legalAccepted: Boolean = true,
    @SerializedName("terms_version") val termsVersion: String = "1.0-2026-09-20",
    @SerializedName("privacy_version") val privacyVersion: String = "1.0-2026-09-20"
)

data class RegistrationStartResponse(
    val status: String,
    val email: String,
    @SerializedName("expires_in_seconds")
    val expiresInSeconds: Int,
    @SerializedName("max_attempts")
    val maxAttempts: Int
)


data class EmailVerificationResendRequest(
    val email: String
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Int
)

data class UserDto(
    val id: Int,
    val email: String,
    val name: String?,
    val cpf: String?,
    @SerializedName("birth_date") val birthDate: String?,
    val sex: String?,
    @SerializedName("profile_photo") val profilePhoto: String? = null,
    @SerializedName("has_google_profile_photo") val hasGoogleProfilePhoto: Boolean = false
)

data class PersonalProfileUpdateRequest(
    val name: String,
    val cpf: String,
    @SerializedName("birth_date") val birthDate: String?,
    val sex: String?
)

data class ProfilePhotoUpdateRequest(@SerializedName("profile_photo") val profilePhoto: String?)

data class OpenFinanceProfileUpdateRequest(
    val name: String,
    val cpf: String
)

data class UserDataDeleteOptionsRequest(
    val transactions: Boolean = false,
    val categories: Boolean = false,
    val accounts: Boolean = false,
    val cards: Boolean = false,
    val budgets: Boolean = false,
    val goals: Boolean = false,
    @SerializedName("open_finance") val openFinance: Boolean = false,
    @SerializedName("workspace_ids") val workspaceIds: List<String> = emptyList(),
    @SerializedName("account_ids") val accountIds: List<Int> = emptyList(),
    @SerializedName("delete_account") val deleteAccount: Boolean = false
)

data class UserDataDeleteConfirmRequest(
    val code: String,
    val options: UserDataDeleteOptionsRequest
)

data class AccountDeleteConfirmRequest(
    val code: String
)

data class CategoryDto(
    val id: Int,
    val name: String,
    val icon: String?
)

data class CategoryCreateRequest(
    val name: String,
    val icon: String? = null
)

data class CategoryUpdateRequest(
    val name: String,
    val icon: String? = null
)

data class GoalDto(
    val id: Int,
    val name: String,
    @SerializedName("target_amount") val targetAmount: Double,
    @SerializedName("current_amount") val currentAmount: Double,
    @SerializedName("target_date") val targetDate: String?
)

data class GoalContributionDto(
    val id: Int,
    @SerializedName("goal_id") val goalId: Int,
    val amount: Double,
    @SerializedName("client_key") val clientKey: String,
    @SerializedName("created_at") val createdAt: String
)

data class GoalContributionCreateRequest(
    val amount: Double,
    @SerializedName("client_key") val clientKey: String
)

data class GoalUpsertRequest(
    val name: String,
    @SerializedName("target_amount") val targetAmount: Double,
    @SerializedName("current_amount") val currentAmount: Double,
    @SerializedName("target_date") val targetDate: String?
)

data class BudgetDto(
    val id: Int,
    @SerializedName("category_id") val categoryId: Int,
    val amount: Double
)

data class BudgetUpsertRequest(
    val amount: Double
)

data class CreditCardDto(
    val id: Int,
    @SerializedName("bank_name") val bankName: String,
    val brand: String,
    @SerializedName("last_four") val lastFour: String,
    val nickname: String?,
    @SerializedName("credit_limit") val creditLimit: Double? = null,
    @SerializedName("closing_day") val closingDay: Int? = null,
    @SerializedName("due_day") val dueDay: Int? = null,
    val active: Boolean = true
)

data class CreditCardCreateRequest(
    @SerializedName("bank_name") val bankName: String,
    val brand: String,
    @SerializedName("last_four") val lastFour: String,
    val nickname: String? = null,
    @SerializedName("credit_limit") val creditLimit: Double? = null,
    @SerializedName("closing_day") val closingDay: Int? = null,
    @SerializedName("due_day") val dueDay: Int? = null
)

data class CreditCardUpdateRequest(
    @SerializedName("bank_name") val bankName: String,
    val brand: String,
    @SerializedName("last_four") val lastFour: String,
    val nickname: String? = null,
    @SerializedName("credit_limit") val creditLimit: Double? = null,
    @SerializedName("closing_day") val closingDay: Int? = null,
    @SerializedName("due_day") val dueDay: Int? = null
)

data class AccountCreateRequest(
    @SerializedName("institution_name") val institutionName: String,
    @SerializedName("institution_id") val institutionId: String? = null,
    @SerializedName("account_name") val accountName: String? = null,
    @SerializedName("masked_account") val maskedAccount: String? = null,
    @SerializedName("external_account_id") val externalAccountId: String? = null
)

data class AccountUpdateRequest(
    @SerializedName("institution_name") val institutionName: String,
    @SerializedName("account_name") val accountName: String?,
    @SerializedName("masked_account") val maskedAccount: String?
)

data class BelvoWidgetTokenRequest(
    val cpf: String? = null,
    val name: String? = null
)

data class BelvoWidgetTokenResponse(
    @SerializedName("hosted_widget_url") val hostedWidgetUrl: String?
)

data class RegisterBelvoLinkRequest(
    @SerializedName("link_id") val linkId: String,
    @SerializedName("institution_id") val institutionId: String? = null,
    @SerializedName("institution_name") val institutionName: String? = null
)

data class AccountDto(
    val id: Int,
    @SerializedName("institution_name") val institutionName: String,
    @SerializedName("institution_id") val institutionId: String?,
    @SerializedName("account_name") val accountName: String?,
    @SerializedName("masked_account") val maskedAccount: String?,
    @SerializedName("external_account_id") val externalAccountId: String?,
    @SerializedName("connection_status") val connectionStatus: String = "manual",
    @SerializedName("current_balance") val currentBalance: Double? = null,
    @SerializedName("balance_updated_at") val balanceUpdatedAt: String? = null
)

data class TransactionDto(
    val id: Int,
    @SerializedName("account_id") val accountId: Int?,
    @SerializedName("category_id") val categoryId: Int?,
    @SerializedName("card_id") val cardId: Int?,
    @SerializedName("installment_group") val installmentGroup: String?,
    @SerializedName("installment_number") val installmentNumber: Int?,
    @SerializedName("installment_total") val installmentTotal: Int?,
    @SerializedName("purchase_date") val purchaseDate: String?,
    @SerializedName("external_transaction_id") val externalTransactionId: String?,
    val date: String,
    val description: String,
    val amount: Double,
    @SerializedName("transaction_type") val transactionType: String,
    val status: String,
    val source: String,
    @SerializedName("synced_at") val syncedAt: String?
)

data class TransactionCreateRequest(
    @SerializedName("account_id") val accountId: Int?,
    @SerializedName("category_id") val categoryId: Int? = null,
    @SerializedName("card_id") val cardId: Int? = null,
    @SerializedName("installment_group") val installmentGroup: String? = null,
    @SerializedName("installment_number") val installmentNumber: Int? = null,
    @SerializedName("installment_total") val installmentTotal: Int? = null,
    @SerializedName("purchase_date") val purchaseDate: String? = null,
    @SerializedName("external_transaction_id") val externalTransactionId: String? = null,
    val date: String,
    val description: String,
    val amount: Double,
    @SerializedName("transaction_type") val transactionType: String = "debit",
    val status: String = "posted",
    val source: String = "manual"
)

data class TransactionUpdateRequest(
    @SerializedName("account_id") val accountId: Int? = null,
    @SerializedName("category_id") val categoryId: Int? = null,
    @SerializedName("card_id") val cardId: Int? = null,
    @SerializedName("installment_group") val installmentGroup: String? = null,
    @SerializedName("installment_number") val installmentNumber: Int? = null,
    @SerializedName("installment_total") val installmentTotal: Int? = null,
    @SerializedName("purchase_date") val purchaseDate: String? = null,
    val date: String? = null,
    val description: String? = null,
    val amount: Double? = null,
    @SerializedName("transaction_type") val transactionType: String? = null,
    val status: String? = null
)

data class OpenFinanceConnectionDto(
    val id: Int,
    val provider: String,
    @SerializedName("institution_id") val institutionId: String?,
    @SerializedName("institution_name") val institutionName: String?,
    val status: String,
    @SerializedName("authorization_url") val authorizationUrl: String?,
    @SerializedName("expires_at") val expiresAt: String?
)

data class SyncResponse(
    val status: String?,
    val taskId: String?,
    val imported: Int?,
    val message: String?
)


data class SyncCountsDto(val accounts: Int, val transactions: Int, val categories: Int)

data class SyncSnapshotDto(
    @SerializedName("server_time") val serverTime: String,
    @SerializedName("last_full_sync_at") val lastFullSyncAt: String?,
    @SerializedName("reset_at") val resetAt: String? = null,
    val counts: SyncCountsDto,
    val accounts: List<AccountDto>,
    val transactions: List<TransactionDto>
)

data class SyncStatusDto(@SerializedName("last_full_sync_at") val lastFullSyncAt: String?)


data class WorkspaceDto(
    val id: String,
    @SerializedName("user_id") val userId: Int,
    val name: String,
    val kind: String,
    @SerializedName("is_default") val isDefault: Boolean,
    @SerializedName("archived_at") val archivedAt: String?
)


data class WorkspaceEditRequest(val name: String, val kind: String)
data class WorkspaceCreateRequest(
    val name: String, val kind: String,
    @SerializedName("client_id") val clientId: String
)


data class SecurityMessage(val message: String)
data class SecurityCodeRequest(val code: String)
data class PasswordResetRequest(val email: String)
data class PasswordResetConfirm(val email: String, val code: String, val password: String)


data class FinancialAiRequest(
    val question: String,
    val summary: Map<String, Any?>
)

data class FinancialAiResponse(
    val answer: String,
    val model: String
)


data class WorkspaceBatchDeleteRequest(val workspace_ids: List<String>)


data class TransactionAttachmentDto(
    val id: Int,
    @com.google.gson.annotations.SerializedName("transaction_id") val transactionId: Int,
    @com.google.gson.annotations.SerializedName("name") val originalName: String,
    @com.google.gson.annotations.SerializedName("content_type") val contentType: String,
    @com.google.gson.annotations.SerializedName("size_bytes") val sizeBytes: Long,
    @com.google.gson.annotations.SerializedName("created_at") val createdAt: String? = null
)
