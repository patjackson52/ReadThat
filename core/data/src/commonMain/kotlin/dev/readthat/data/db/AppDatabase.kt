package dev.readthat.data.db

import androidx.room3.ConstructedBy
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * **L2 — the on-disk tier, and the source of truth for paging.**
 *
 * Note what is *not* here: no in-memory list of groups. Paging 3 reads from
 * this database via a Room [androidx.paging.PagingSource], so there is exactly
 * one place the feed lives. The previous version of this sample kept the whole
 * feed in a `MutableStateFlow` and hand-rolled the cursor loop — which worked,
 * but meant the list did not survive process death and every write had to
 * re-derive the entire visible feed.
 */
@Database(
    entities = [
        GroupEntity::class,
        ItemStateEntity::class,
        RemoteKeyEntity::class,
        PendingVoteEntity::class,
        SyncMetadataEntity::class,
        AccountEntity::class,
        PendingPostEntity::class,
        PendingSubredditEntity::class,
        PendingPerformanceEventEntity::class,
        PendingProductAnalyticsEventEntity::class,
        SubredditEntity::class,
        AppSettingsEntity::class,
        SearchResultEntity::class,
        SearchRemoteKeyEntity::class,
        SearchSnapshotEntity::class,
        SearchRecentEntity::class,
        CommunityMembershipEntity::class,
        CommunityVisitEntity::class,
        CommunityVisitMutationEntity::class,
        CommunityDrawerSyncEntity::class,
        PendingCommunityMembershipEntity::class,
        MediaPostContentEntity::class,
        MediaFeedEntryEntity::class,
        MediaFeedRemoteKeyEntity::class,
        CachedDocumentEntity::class,
    ],
    version = 18,
    exportSchema = false,
)
@ConstructedBy(AppDatabaseConstructor::class)
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun accountDao(): AccountDao
    abstract fun postOutboxDao(): PostOutboxDao
    abstract fun subredditOutboxDao(): SubredditOutboxDao
    abstract fun performanceOutboxDao(): PerformanceOutboxDao
    abstract fun productAnalyticsOutboxDao(): ProductAnalyticsOutboxDao
    abstract fun subredditDao(): SubredditDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun searchDao(): SearchDao
    abstract fun communityDrawerDao(): CommunityDrawerDao
    abstract fun communityDetailDao(): CommunityDetailDao
    abstract fun mediaFeedDao(): MediaFeedDao
    abstract fun cachedDocumentDao(): CachedDocumentDao

    companion object {
        internal const val NAME = "readthat.db"

        /** Preserve cached pages, cursors, and optimistic vote state from v2. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    "ALTER TABLE item_state ADD COLUMN downvoted INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Scope every personalized row by account and feed. Existing unscoped
         * rows are retained under an unreachable legacy account rather than ever
         * being shown to the next person who signs in.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS feed_groups_new (
                        accountId TEXT NOT NULL,
                        feedId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        sortIndex INTEGER NOT NULL,
                        payloadJson TEXT NOT NULL,
                        payloadVersion INTEGER NOT NULL,
                        PRIMARY KEY(accountId, feedId, groupId)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    """
                    INSERT INTO feed_groups_new
                        (accountId, feedId, groupId, sortIndex, payloadJson, payloadVersion)
                    SELECT '${CacheScope.LEGACY_ACCOUNT_ID}', '${CacheScope.HOME_FEED_ID}',
                           groupId, sortIndex, payloadJson, payloadVersion
                    FROM feed_groups
                    """.trimIndent(),
                )
                db.executeSQL("DROP TABLE feed_groups")
                db.executeSQL("ALTER TABLE feed_groups_new RENAME TO feed_groups")

                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS item_state_new (
                        accountId TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        likeCount INTEGER NOT NULL,
                        liked INTEGER NOT NULL,
                        downvoted INTEGER NOT NULL,
                        PRIMARY KEY(accountId, itemId)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    """
                    INSERT INTO item_state_new (accountId, itemId, likeCount, liked, downvoted)
                    SELECT '${CacheScope.LEGACY_ACCOUNT_ID}', itemId, likeCount, liked, downvoted
                    FROM item_state
                    """.trimIndent(),
                )
                db.executeSQL("DROP TABLE item_state")
                db.executeSQL("ALTER TABLE item_state_new RENAME TO item_state")

                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS remote_keys_new (
                        accountId TEXT NOT NULL,
                        feedId TEXT NOT NULL,
                        nextCursor TEXT,
                        PRIMARY KEY(accountId, feedId)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    """
                    INSERT INTO remote_keys_new (accountId, feedId, nextCursor)
                    SELECT '${CacheScope.LEGACY_ACCOUNT_ID}', feedId, nextCursor FROM remote_keys
                    """.trimIndent(),
                )
                db.executeSQL("DROP TABLE remote_keys")
                db.executeSQL("ALTER TABLE remote_keys_new RENAME TO remote_keys")

                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS vote_outbox_new (
                        accountId TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        mutationId TEXT NOT NULL,
                        value INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, itemId)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    """
                    INSERT INTO vote_outbox_new (accountId, itemId, mutationId, value, createdAt)
                    SELECT '${CacheScope.LEGACY_ACCOUNT_ID}', itemId, mutationId, value, createdAt
                    FROM vote_outbox
                    """.trimIndent(),
                )
                db.executeSQL("DROP TABLE vote_outbox")
                db.executeSQL("ALTER TABLE vote_outbox_new RENAME TO vote_outbox")

                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        accountId TEXT NOT NULL,
                        scopeKey TEXT NOT NULL,
                        lastSuccessfulSyncAt INTEGER NOT NULL,
                        validator TEXT,
                        PRIMARY KEY(accountId, scopeKey)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS accounts (
                        id TEXT NOT NULL PRIMARY KEY,
                        username TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        bio TEXT NOT NULL,
                        avatarUrl TEXT,
                        karma INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastAuthenticatedAt INTEGER NOT NULL,
                        isActive INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS post_outbox (
                        mutationId TEXT NOT NULL PRIMARY KEY,
                        accountId TEXT NOT NULL,
                        subreddit TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        linkUrl TEXT NOT NULL,
                        localPath TEXT,
                        contentType TEXT,
                        byteSize INTEGER,
                        width INTEGER,
                        height INTEGER,
                        durationSeconds INTEGER,
                        mediaId TEXT,
                        state TEXT NOT NULL,
                        remotePostId TEXT,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.executeSQL("CREATE INDEX IF NOT EXISTS index_post_outbox_accountId ON post_outbox(accountId)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subreddits (
                        accountId TEXT NOT NULL,
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        description TEXT NOT NULL,
                        accessType TEXT NOT NULL,
                        viewerRole TEXT,
                        subscriberCount INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, name)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_settings (
                        id TEXT NOT NULL PRIMARY KEY,
                        darkTheme INTEGER NOT NULL,
                        compactPosts INTEGER NOT NULL,
                        autoplayVideo INTEGER NOT NULL,
                        autoplayOnMetered INTEGER NOT NULL,
                        reduceDataOnMetered INTEGER NOT NULL,
                        reduceAnimations INTEGER NOT NULL,
                        blurMatureMedia INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Make the feed position an enforced per-account/per-feed invariant.
         *
         * Older builds used COUNT(*) as the next append position. If a dynamic
         * ranked page overlapped a prior page, an upsert could move an existing
         * row and leave two rows with the same sortIndex. Normalize those rows
         * deterministically before installing the unique index.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TEMP TABLE feed_positions_v8 (
                        accountId TEXT NOT NULL,
                        feedId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        newSortIndex INTEGER NOT NULL,
                        PRIMARY KEY(accountId, feedId, groupId)
                    )
                    """.trimIndent(),
                )
                // Materialize the mapping first. A correlated UPDATE could see
                // positions already rewritten earlier in that same statement.
                db.executeSQL(
                    """
                    INSERT INTO feed_positions_v8 (accountId, feedId, groupId, newSortIndex)
                    SELECT current.accountId, current.feedId, current.groupId, (
                        SELECT COUNT(*)
                        FROM feed_groups AS ordered
                        WHERE ordered.accountId = current.accountId
                          AND ordered.feedId = current.feedId
                          AND (
                              ordered.sortIndex < current.sortIndex OR
                              (ordered.sortIndex = current.sortIndex AND ordered.groupId < current.groupId)
                          )
                    )
                    FROM feed_groups AS current
                    """.trimIndent(),
                )
                db.executeSQL(
                    """
                    UPDATE feed_groups
                    SET sortIndex = (
                        SELECT mapped.newSortIndex
                        FROM feed_positions_v8 AS mapped
                        WHERE mapped.accountId = feed_groups.accountId
                          AND mapped.feedId = feed_groups.feedId
                          AND mapped.groupId = feed_groups.groupId
                    )
                    """.trimIndent(),
                )
                db.executeSQL("DROP TABLE feed_positions_v8")
                db.executeSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_feed_groups_accountId_feedId_sortIndex
                    ON feed_groups(accountId, feedId, sortIndex)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS performance_outbox (
                        id TEXT NOT NULL PRIMARY KEY,
                        payloadJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_performance_outbox_createdAt " +
                        "ON performance_outbox(createdAt)",
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subreddit_outbox (
                        mutationId TEXT NOT NULL PRIMARY KEY,
                        accountId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        description TEXT NOT NULL,
                        accessType TEXT NOT NULL,
                        state TEXT NOT NULL,
                        remoteSubredditId TEXT,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_subreddit_outbox_accountId_name " +
                        "ON subreddit_outbox(accountId, name)",
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS search_results (
                        accountId TEXT NOT NULL,
                        queryKey TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        sortIndex INTEGER NOT NULL,
                        payloadJson TEXT NOT NULL,
                        cachedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, queryKey, itemId)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_search_results_accountId_queryKey_sortIndex
                    ON search_results(accountId, queryKey, sortIndex)
                    """.trimIndent(),
                )
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS search_remote_keys (
                        accountId TEXT NOT NULL,
                        queryKey TEXT NOT NULL,
                        nextCursor TEXT,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, queryKey)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS search_snapshots (
                        accountId TEXT NOT NULL,
                        queryKey TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        cachedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, queryKey)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS search_recent (
                        accountId TEXT NOT NULL,
                        normalizedQuery TEXT NOT NULL,
                        query TEXT NOT NULL,
                        searchedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, normalizedQuery)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS community_memberships (
                        accountId TEXT NOT NULL,
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        accessType TEXT NOT NULL,
                        viewerRole TEXT NOT NULL,
                        source TEXT NOT NULL,
                        syncedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, name)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_community_memberships_accountId_source_name " +
                        "ON community_memberships(accountId, source, name)",
                )
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS community_visits (
                        accountId TEXT NOT NULL,
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        visitedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, name)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_community_visits_accountId_visitedAt " +
                        "ON community_visits(accountId, visitedAt)",
                )
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS community_visit_outbox (
                        mutationId TEXT NOT NULL PRIMARY KEY,
                        accountId TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        name TEXT,
                        occurredAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_community_visit_outbox_accountId_createdAt " +
                        "ON community_visit_outbox(accountId, createdAt)",
                )
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS community_drawer_sync (
                        accountId TEXT NOT NULL PRIMARY KEY,
                        validator TEXT,
                        lastSuccessfulSyncAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS product_analytics_outbox (
                        id TEXT NOT NULL PRIMARY KEY,
                        installationId TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        accountId TEXT,
                        payloadJson TEXT NOT NULL,
                        dedupeKey TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_product_analytics_outbox_createdAt " +
                        "ON product_analytics_outbox(createdAt)",
                )
                db.executeSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_product_analytics_outbox_dedupeKey " +
                        "ON product_analytics_outbox(dedupeKey)",
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL("ALTER TABLE subreddits ADD COLUMN avatarUrl TEXT")
                db.executeSQL("ALTER TABLE subreddits ADD COLUMN rulesJson TEXT NOT NULL DEFAULT '[]'")
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS community_membership_outbox (
                        accountId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        mutationId TEXT NOT NULL,
                        desiredJoined INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, name)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_community_membership_outbox_accountId_createdAt " +
                        "ON community_membership_outbox(accountId, createdAt)",
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_post_content (
                        accountId TEXT NOT NULL,
                        postId TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, postId)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_post_content_accountId_updatedAt " +
                        "ON media_post_content(accountId, updatedAt)",
                )
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_feed_entries (
                        accountId TEXT NOT NULL,
                        feedId TEXT NOT NULL,
                        postId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(accountId, feedId, postId)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_media_feed_entries_accountId_feedId_position " +
                        "ON media_feed_entries(accountId, feedId, position)",
                )
                db.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_feed_entries_accountId_postId " +
                        "ON media_feed_entries(accountId, postId)",
                )
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_feed_remote_keys (
                        accountId TEXT NOT NULL,
                        feedId TEXT NOT NULL,
                        nextCursor TEXT,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountId, feedId)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    "ALTER TABLE post_outbox ADD COLUMN mediaItemsJson TEXT NOT NULL DEFAULT '[]'",
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL("ALTER TABLE post_outbox ADD COLUMN flairId TEXT")
                db.executeSQL("ALTER TABLE post_outbox ADD COLUMN flairText TEXT")
                db.executeSQL("ALTER TABLE post_outbox ADD COLUMN flairBackgroundColor TEXT")
                db.executeSQL("ALTER TABLE post_outbox ADD COLUMN flairTextColor TEXT")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override suspend fun migrate(db: SQLiteConnection) {
                db.executeSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_documents (
                        accountId TEXT NOT NULL,
                        cacheKey TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        validator TEXT,
                        PRIMARY KEY(accountId, cacheKey)
                    )
                    """.trimIndent(),
                )
                db.executeSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_documents_accountId_updatedAt " +
                        "ON cached_documents(accountId, updatedAt)",
                )
            }
        }

        internal val migrations: Array<Migration>
            get() = arrayOf(
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
            )
    }
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Applies one identical driver, migration chain, and coroutine policy on Android and iOS. */
fun buildAppDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.Default)
    .addMigrations(*AppDatabase.migrations)
    // Only the pre-outbox development schema may be recreated. Versions 2+
    // can contain durable writes and must always follow an explicit migration.
    .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
    .build()
