package com.financeapp.mobile.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.financeapp.mobile.BuildConfig
import com.financeapp.mobile.data.local.WorkspaceMigration
import com.financeapp.mobile.data.local.AppDatabase
import com.financeapp.mobile.data.local.FinanceDao
import com.financeapp.mobile.data.remote.FinanceApi
import com.financeapp.mobile.util.SessionManager
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_5_6 =
        object : Migration(5, 6) {
            override fun migrate(
                database: SupportSQLiteDatabase
            ) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS goals_local (
                        id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        targetAmount REAL NOT NULL,
                        currentAmount REAL NOT NULL,
                        targetDate TEXT,
                        syncState TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS budgets_local (
                        categoryId INTEGER NOT NULL,
                        serverId INTEGER,
                        amount REAL NOT NULL,
                        syncState TEXT NOT NULL,
                        PRIMARY KEY(categoryId)
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_sync_operations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        entityType TEXT NOT NULL,
                        action TEXT NOT NULL,
                        entityId INTEGER NOT NULL,
                        payload TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

    private val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override fun migrate(
                database: SupportSQLiteDatabase
            ) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS goal_contributions_local (
                        id INTEGER NOT NULL,
                        goalId INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        createdAt TEXT NOT NULL,
                        clientKey TEXT NOT NULL,
                        syncState TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
            }
        }

    private val MIGRATION_7_8 =
        object : Migration(7, 8) {
            override fun migrate(
                database: SupportSQLiteDatabase
            ) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS credit_cards_local (
                        id INTEGER NOT NULL,
                        bankName TEXT NOT NULL,
                        brand TEXT NOT NULL,
                        lastFour TEXT NOT NULL,
                        nickname TEXT,
                        syncState TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    "ALTER TABLE transactions ADD COLUMN cardId INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE transactions ADD COLUMN installmentGroup TEXT"
                )
                database.execSQL(
                    "ALTER TABLE transactions ADD COLUMN installmentNumber INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE transactions ADD COLUMN installmentTotal INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE transactions ADD COLUMN purchaseDate TEXT"
                )
            }
        }

    private val MIGRATION_8_9 =
        object : Migration(8, 9) {
            override fun migrate(
                database: SupportSQLiteDatabase
            ) {
                var hasActiveColumn = false

                database.query(
                    "PRAGMA table_info(credit_cards_local)"
                ).use { cursor ->
                    val nameIndex =
                        cursor.getColumnIndex("name")

                    while (
                        nameIndex >= 0 &&
                        cursor.moveToNext()
                    ) {
                        if (
                            cursor.getString(nameIndex) ==
                            "active"
                        ) {
                            hasActiveColumn = true
                            break
                        }
                    }
                }

                if (!hasActiveColumn) {
                    database.execSQL(
                        "ALTER TABLE credit_cards_local " +
                            "ADD COLUMN active INTEGER " +
                            "NOT NULL DEFAULT 1"
                    )
                }
            }
        }

    private val MIGRATION_9_10 =
        object : Migration(9, 10) {
            override fun migrate(
                database: SupportSQLiteDatabase
            ) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS credit_cards_local_new (
                        id INTEGER NOT NULL,
                        bankName TEXT NOT NULL,
                        brand TEXT NOT NULL,
                        lastFour TEXT NOT NULL,
                        nickname TEXT,
                        active INTEGER NOT NULL,
                        syncState TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    INSERT INTO credit_cards_local_new (
                        id,
                        bankName,
                        brand,
                        lastFour,
                        nickname,
                        active,
                        syncState
                    )
                    SELECT
                        id,
                        bankName,
                        brand,
                        lastFour,
                        nickname,
                        active,
                        syncState
                    FROM credit_cards_local
                    """.trimIndent()
                )

                database.execSQL(
                    "DROP TABLE credit_cards_local"
                )

                database.execSQL(
                    "ALTER TABLE credit_cards_local_new " +
                        "RENAME TO credit_cards_local"
                )
            }
        }

    private val MIGRATION_10_11 =
        object : Migration(10, 11) {
            override fun migrate(
                database: SupportSQLiteDatabase
            ) {
                database.execSQL(
                    "ALTER TABLE accounts " +
                        "ADD COLUMN currentBalance REAL"
                )

                database.execSQL(
                    "ALTER TABLE accounts " +
                        "ADD COLUMN balanceUpdatedAt TEXT"
                )
            }
        }

    private val MIGRATION_12_13 =
        object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE credit_cards_local ADD COLUMN creditLimit REAL")
                database.execSQL("ALTER TABLE credit_cards_local ADD COLUMN closingDay INTEGER")
                database.execSQL("ALTER TABLE credit_cards_local ADD COLUMN dueDay INTEGER")
            }
        }

    private val MIGRATION_13_14 =
        object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE accounts ADD COLUMN syncState TEXT NOT NULL DEFAULT 'SYNCED'")
            }
        }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context, session: SessionManager): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "finance.db")
            .addMigrations(
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                WorkspaceMigration(session.localUserId()),
                MIGRATION_12_13,
                MIGRATION_13_14
            )
            .build()

    @Provides
    fun provideDao(db: AppDatabase): FinanceDao = db.financeDao()

    @Provides
    @Singleton
    fun provideOkHttp(session: SessionManager): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            if (session.isGuest() || com.financeapp.mobile.util.WorkspaceOperation.currentOrNull()?.userId == 0) {
                throw java.io.IOException("Modo visitante: dados salvos . Crie uma conta para usar os serviços online.")
            }
            val token = session.accessToken()
            val request = chain.request().newBuilder().apply {
                if (!token.isNullOrBlank() && chain.request().header("Authorization") == null) {
                    header("Authorization", "Bearer $token")
                }
            }.build()
            chain.proceed(request)
        }

        val logging = HttpLoggingInterceptor().apply {
            // Passwords and confirmation codes must never be written to Logcat.
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            /*
             * Os valores padrão do OkHttp são relativamente curtos para um
             * backend local / Open Finance. Login, biometria (refresh token),
             * sincronização e primeira carga podem levar mais tempo.
             */
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideApi(client: OkHttpClient): FinanceApi {
        val gson = GsonBuilder().create()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(FinanceApi::class.java)
    }
}
