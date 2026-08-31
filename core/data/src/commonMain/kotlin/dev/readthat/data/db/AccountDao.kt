package dev.readthat.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    suspend fun active(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :accountId")
    fun observe(accountId: String): Flow<AccountEntity?>

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("UPDATE accounts SET isActive = 0")
    suspend fun deactivateAll()

    @Transaction
    suspend fun activate(account: AccountEntity) {
        deactivateAll()
        upsert(account.copy(isActive = true))
    }
}
