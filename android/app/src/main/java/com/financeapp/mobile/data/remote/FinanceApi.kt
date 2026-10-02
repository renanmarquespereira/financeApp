package com.financeapp.mobile.data.remote

import com.financeapp.mobile.util.WorkspaceOperation
import retrofit2.http.Header
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Multipart
import retrofit2.http.Part
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response

interface FinanceApi {
    @GET("transactions/{id}/attachments")
    suspend fun transactionAttachments(
        @Path("id") transactionId: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): List<TransactionAttachmentDto>

    @Multipart
    @POST("transactions/{id}/attachments")
    suspend fun uploadTransactionAttachment(
        @Path("id") transactionId: Int,
        @Part file: MultipartBody.Part,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): TransactionAttachmentDto

    @GET("transactions/{id}/attachments/{attachmentId}/download")
    suspend fun downloadTransactionAttachment(
        @Path("id") transactionId: Int,
        @Path("attachmentId") attachmentId: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Response<ResponseBody>

    @DELETE("transactions/{id}/attachments/{attachmentId}")
    suspend fun deleteTransactionAttachment(
        @Path("id") transactionId: Int,
        @Path("attachmentId") attachmentId: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    )

    @POST("guest-import")
    suspend fun importGuest(@Body body: com.google.gson.JsonObject,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization): WorkspaceDto

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: PasswordResetRequest): SecurityMessage
    @POST("auth/reset-password")
    suspend fun resetPassword(@Body body: PasswordResetConfirm): SecurityMessage
    @POST("workspaces/batch-delete-code")
    suspend fun workspaceBatchDeleteCode(@Body body: WorkspaceBatchDeleteRequest,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization): SecurityMessage
    @POST("workspaces/batch-confirm-delete")
    suspend fun workspaceBatchDeleteConfirm(@Body body: WorkspaceBatchDeleteRequest, @retrofit2.http.Query("code") code: String,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization): SecurityMessage

    @POST("workspaces/{id}/delete-code")
    suspend fun workspaceDeleteCode(@Path("id") id: String,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization): SecurityMessage
    @POST("workspaces/{id}/confirm-delete")
    suspend fun workspaceDeleteConfirm(@Path("id") id: String, @Body body: SecurityCodeRequest,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization): SecurityMessage

    @POST("workspaces")
    suspend fun createWorkspace(@Body body: WorkspaceCreateRequest,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization): WorkspaceDto

    @PATCH("workspaces/{id}")
    suspend fun editWorkspace(@Path("id") id: String, @Body body: WorkspaceEditRequest,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization): WorkspaceDto

    @POST("workspaces/{id}/archive")
    suspend fun archiveWorkspace(@Path("id") id: String,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization): WorkspaceDto

    @POST("workspaces/{id}/restore")
    suspend fun restoreWorkspace(@Path("id") id: String,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization): WorkspaceDto

    @GET("workspaces")
    suspend fun workspaces(
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): List<WorkspaceDto>

    @GET("health")
    suspend fun health():
        Map<String, Any?>

    @POST("sync/import-local")
    suspend fun importLocalSnapshot(
        @Body body: com.google.gson.JsonObject,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): com.google.gson.JsonObject

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @POST("auth/google")
    suspend fun googleLogin(@Body request: GoogleLoginRequest): TokenResponse

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): TokenResponse

    @POST("auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): RegistrationStartResponse


    @POST("auth/resend-verification")
    suspend fun resendVerification(
        @Body request:
            EmailVerificationResendRequest
    ): RegistrationStartResponse

    @GET("users/me")
    suspend fun me(): UserDto

    @PATCH("users/me/personal")
    suspend fun updatePersonalProfile(
        @Body request: PersonalProfileUpdateRequest
    ): UserDto

    @PATCH("users/me/profile-photo")
    suspend fun updateProfilePhoto(
        @Body request: ProfilePhotoUpdateRequest
    ): UserDto

    @POST("users/me/profile-photo/google")
    suspend fun useGoogleProfilePhoto(): UserDto

    @PATCH("users/me/open-finance-profile")
    suspend fun updateOpenFinanceProfile(
        @Body request:
            OpenFinanceProfileUpdateRequest
    ): UserDto

    @POST("accounts")
    suspend fun createAccount(
        @Body request: AccountCreateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): AccountDto

    @GET("accounts")
    suspend fun accounts(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): List<AccountDto>

    @GET("cards")
    suspend fun cards(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): List<CreditCardDto>

    @POST("cards")
    suspend fun createCard(@Body request: CreditCardCreateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): CreditCardDto

    @PATCH("cards/{id}")
    suspend fun updateCard(@Path("id") id: Int,
        @Body request: CreditCardUpdateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): CreditCardDto

    @DELETE("cards/{id}")
    suspend fun deleteCard(@Path("id") id: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @PATCH("accounts/{id}")
    suspend fun updateAccount(@Path("id") id: Int,
        @Body request: AccountUpdateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): AccountDto

    @POST("accounts/{id}/disconnect")
    suspend fun disconnectAccount(@Path("id") id: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @POST("accounts/{id}/reconnect")
    suspend fun reconnectAccount(@Path("id") id: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @POST("accounts/{id}/delete-code")
    suspend fun requestAccountDeleteCode(@Path("id") id: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @POST("accounts/{id}/confirm-delete")
    suspend fun confirmAccountDelete(@Path("id") id: Int,
        @Body request: AccountDeleteConfirmRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @POST("user-data/delete-code")
    suspend fun requestDeleteAllUserDataCode(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ):
        Map<String, Any?>

    @POST("user-data/confirm-delete-all")
    suspend fun confirmDeleteAllUserData(@Body request: UserDataDeleteConfirmRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @GET("categories")
    suspend fun categories(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): List<CategoryDto>

    @POST("categories")
    suspend fun createCategory(@Body request: CategoryCreateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): CategoryDto

    @PATCH("categories/{id}")
    suspend fun updateCategory(@Path("id") id: Int,
        @Body request: CategoryUpdateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): CategoryDto

    @DELETE("categories/{id}")
    suspend fun deleteCategory(@Path("id") id: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @GET("goals")
    suspend fun goals(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): List<GoalDto>

    @POST("goals")
    suspend fun createGoal(@Body request: GoalUpsertRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): GoalDto

    @PUT("goals/{id}")
    suspend fun updateGoal(@Path("id") id: Int,
        @Body request: GoalUpsertRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): GoalDto

    @DELETE("goals/{id}")
    suspend fun deleteGoal(@Path("id") id: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @GET("goals/{id}/contributions")
    suspend fun goalContributions(@Path("id") id: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): List<GoalContributionDto>

    @POST("goals/{id}/contributions")
    suspend fun addGoalContribution(@Path("id") id: Int,
        @Body request: GoalContributionCreateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): GoalContributionDto

    @DELETE("goals/{goalId}/contributions/{contributionId}")
    suspend fun deleteGoalContribution(@Path("goalId") goalId: Int,
        @Path("contributionId") contributionId: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @GET("budgets")
    suspend fun budgets(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): List<BudgetDto>

    @PUT("budgets/{categoryId}")
    suspend fun setBudget(@Path("categoryId") categoryId: Int,
        @Body request: BudgetUpsertRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): BudgetDto

    @DELETE("budgets/{categoryId}")
    suspend fun deleteBudget(@Path("categoryId") categoryId: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @GET("transactions")
    suspend fun transactions(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): List<TransactionDto>

    @GET("sync/snapshot")
    suspend fun syncSnapshot(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): SyncSnapshotDto

    @GET("sync/status")
    suspend fun syncStatus(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): SyncStatusDto

    @POST("transactions")
    suspend fun createTransaction(@Body request: TransactionCreateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): TransactionDto

    @PATCH("transactions/{id}")
    suspend fun updateTransaction(@Path("id") id: Int,
        @Body request: TransactionUpdateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): TransactionDto

    @DELETE("transactions/{id}")
    suspend fun deleteTransaction(@Path("id") id: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @POST("openfinance/belvo/widget-token")
    suspend fun belvoWidgetToken(@Body request: BelvoWidgetTokenRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): BelvoWidgetTokenResponse

    @POST("openfinance/belvo/register-link")
    suspend fun registerBelvoLink(@Body request: RegisterBelvoLinkRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): OpenFinanceConnectionDto

    @GET("openfinance/connections")
    suspend fun connections(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): List<OpenFinanceConnectionDto>

    @POST("openfinance/connections/{id}/sync")
    suspend fun syncConnection(@Path("id") id: Int,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>


    @GET("sync/forecast-state")
    suspend fun forecastStateSync(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @GET("forecast-state")
    suspend fun forecastState(
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @PUT("sync/forecast-state")
    suspend fun saveForecastStateSync(
        @Body request: ForecastStateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @PUT("forecast-state")
    suspend fun saveForecastState(
        @Body request: ForecastStateRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): Map<String, Any?>

    @POST("financial-ai/ask")
    suspend fun askFinancialAi(
        @Body request: FinancialAiRequest,
        @Header("X-Workspace-Id") workspaceId: String = WorkspaceOperation.current().workspaceId,
        @Header("Authorization") authorization: String = WorkspaceOperation.current().authorization
    ): FinancialAiResponse

}


data class ForecastStateRequest(val payload: Map<String, Any?>)
