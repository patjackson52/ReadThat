package dev.readthat.comments.data.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.PostHeader
import dev.readthat.comments.domain.PostMedia

/** L2 contract. Tests use the no-op default; Android uses [RoomCommentsCache]. */
interface CommentsLocalCache {
    suspend fun readTree(accountId: String, postId: String, rootCommentId: String?): CommentTree?
    suspend fun writeTree(accountId: String, tree: CommentTree, rootCommentId: String?)
    suspend fun readHeader(accountId: String, postId: String): PostHeader?
    suspend fun writeHeader(accountId: String, header: PostHeader)

    data object None : CommentsLocalCache {
        override suspend fun readTree(accountId: String, postId: String, rootCommentId: String?) = null
        override suspend fun writeTree(accountId: String, tree: CommentTree, rootCommentId: String?) = Unit
        override suspend fun readHeader(accountId: String, postId: String) = null
        override suspend fun writeHeader(accountId: String, header: PostHeader) = Unit
    }
}

@Entity(tableName = "comment_threads", primaryKeys = ["accountId", "postId", "threadKey"])
internal data class CommentThreadEntity(
    val accountId: String,
    val postId: String,
    val threadKey: String,
    val requestedCount: Int,
    val requestedDepth: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "comment_nodes",
    primaryKeys = ["accountId", "postId", "threadKey", "nodeId"],
    indices = [Index(value = ["accountId", "postId", "threadKey", "sortIndex"], unique = true)],
)
internal data class CommentNodeEntity(
    val accountId: String,
    val postId: String,
    val threadKey: String,
    val nodeId: String,
    val parentId: String?,
    val sortIndex: Int,
    val kind: Int,
    val author: String?,
    val authorDisplayName: String?,
    val authorAvatarUrl: String?,
    val isEdited: Boolean,
    val body: String?,
    val score: Int,
    val viewerVote: Int,
    val createdAgoMin: Int,
    val remainingCount: Int,
    val childIds: String,
)

@Entity(tableName = "post_headers", primaryKeys = ["accountId", "postId"])
internal data class PostHeaderEntity(
    val accountId: String,
    val postId: String,
    val title: String,
    val author: String,
    val subreddit: String,
    val score: Int,
    val commentCount: Int,
    val body: String?,
    val viewerVote: Int,
    val kind: String,
    val linkUrl: String?,
    val mediaPlaceholderColor: Long?,
    val mediaAspectRatio: Float?,
    val mediaIsVideo: Boolean?,
    val mediaDurationSeconds: Int?,
    val mediaUrl: String?,
    val mediaAltText: String?,
    val mediaHlsUrl: String?,
    val mediaDashUrl: String?,
    val mediaPosterUrl: String?,
    val mediaFallbackUrl: String?,
    val mediaDeliveryStatus: String?,
    val mediaProcessingProgress: Int?,
    val updatedAt: Long,
)

internal data class CachedThread(
    val metadata: CommentThreadEntity,
    val nodes: List<CommentNodeEntity>,
)

internal data class CachedThreadKey(
    val postId: String,
    val threadKey: String,
)

@Dao
internal interface CommentsCacheDao {
    @Query("SELECT * FROM comment_threads WHERE accountId = :accountId AND postId = :postId AND threadKey = :threadKey")
    suspend fun thread(accountId: String, postId: String, threadKey: String): CommentThreadEntity?

    @Query("SELECT * FROM comment_nodes WHERE accountId = :accountId AND postId = :postId AND threadKey = :threadKey ORDER BY sortIndex ASC")
    suspend fun nodes(accountId: String, postId: String, threadKey: String): List<CommentNodeEntity>

    @Query("UPDATE comment_threads SET updatedAt = :now WHERE accountId = :accountId AND postId = :postId AND threadKey = :threadKey")
    suspend fun touchThread(accountId: String, postId: String, threadKey: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putThread(thread: CommentThreadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNodes(nodes: List<CommentNodeEntity>)

    @Query("DELETE FROM comment_nodes WHERE accountId = :accountId AND postId = :postId AND threadKey = :threadKey")
    suspend fun clearNodes(accountId: String, postId: String, threadKey: String)

    @Query(
        """
        SELECT postId, threadKey FROM comment_threads
        WHERE accountId = :accountId
        ORDER BY updatedAt DESC, postId DESC, threadKey DESC
        LIMIT -1 OFFSET :keep
        """,
    )
    suspend fun overflowThreads(accountId: String, keep: Int): List<CachedThreadKey>

    @Query("DELETE FROM comment_threads WHERE accountId = :accountId AND postId = :postId AND threadKey = :threadKey")
    suspend fun deleteThread(accountId: String, postId: String, threadKey: String)

    @Query("SELECT * FROM post_headers WHERE accountId = :accountId AND postId = :postId")
    suspend fun header(accountId: String, postId: String): PostHeaderEntity?

    @Query("UPDATE post_headers SET updatedAt = :now WHERE accountId = :accountId AND postId = :postId")
    suspend fun touchHeader(accountId: String, postId: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHeader(header: PostHeaderEntity)

    @Query(
        """
        DELETE FROM post_headers
        WHERE accountId = :accountId AND postId IN (
            SELECT postId FROM post_headers
            WHERE accountId = :accountId
            ORDER BY updatedAt DESC, postId DESC
            LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun pruneHeaders(accountId: String, keep: Int)

    @androidx.room.Transaction
    suspend fun pruneThreads(accountId: String, keep: Int) {
        overflowThreads(accountId, keep).forEach { stale ->
            clearNodes(accountId, stale.postId, stale.threadKey)
            deleteThread(accountId, stale.postId, stale.threadKey)
        }
    }
}

@Database(
    entities = [CommentThreadEntity::class, CommentNodeEntity::class, PostHeaderEntity::class],
    version = 2,
    exportSchema = false,
)
internal abstract class CommentsCacheDatabase : RoomDatabase() {
    abstract fun dao(): CommentsCacheDao

    companion object {
        @Volatile private var instance: CommentsCacheDatabase? = null

        fun get(context: Context): CommentsCacheDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CommentsCacheDatabase::class.java,
                "comments_cache.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comment_nodes ADD COLUMN authorDisplayName TEXT")
                db.execSQL("ALTER TABLE comment_nodes ADD COLUMN authorAvatarUrl TEXT")
                db.execSQL("ALTER TABLE comment_nodes ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

class RoomCommentsCache(context: Context) : CommentsLocalCache {
    private val db = CommentsCacheDatabase.get(context)
    private val dao = db.dao()

    override suspend fun readTree(accountId: String, postId: String, rootCommentId: String?): CommentTree? =
        db.withTransaction {
            val key = threadKey(rootCommentId)
            val metadata = dao.thread(accountId, postId, key) ?: return@withTransaction null
            val rows = dao.nodes(accountId, postId, key)
            dao.touchThread(accountId, postId, key, System.currentTimeMillis())
            rows.toTree(metadata)
        }

    override suspend fun writeTree(accountId: String, tree: CommentTree, rootCommentId: String?) {
        val key = threadKey(rootCommentId)
        db.withTransaction {
            dao.clearNodes(accountId, tree.postId, key)
            dao.putNodes(tree.toEntities(accountId, key))
            dao.putThread(CommentThreadEntity(
                accountId = accountId,
                postId = tree.postId,
                threadKey = key,
                requestedCount = tree.requestedCount,
                requestedDepth = tree.requestedDepth,
                updatedAt = System.currentTimeMillis(),
            ))
            dao.pruneThreads(accountId, MAX_DISK_THREADS_PER_ACCOUNT)
        }
    }

    override suspend fun readHeader(accountId: String, postId: String): PostHeader? =
        db.withTransaction {
            dao.header(accountId, postId)?.also {
                dao.touchHeader(accountId, postId, System.currentTimeMillis())
            }?.toDomain()
        }

    override suspend fun writeHeader(accountId: String, header: PostHeader) {
        db.withTransaction {
            dao.putHeader(header.toEntity(accountId))
            dao.pruneHeaders(accountId, MAX_DISK_HEADERS_PER_ACCOUNT)
        }
    }
}

private const val ROOT_THREAD = "__root__"
private const val KIND_COMMENT = 0
private const val KIND_LOAD_MORE = 1
private const val CHILD_SEPARATOR = '\u001f'
private const val MAX_DISK_THREADS_PER_ACCOUNT = 100
private const val MAX_DISK_HEADERS_PER_ACCOUNT = 200

private fun threadKey(rootCommentId: String?) = rootCommentId ?: ROOT_THREAD

/** Pre-order, iterative encoding avoids stack overflow on adversarial threads. */
private fun CommentTree.toEntities(accountId: String, threadKey: String): List<CommentNodeEntity> {
    val result = ArrayList<CommentNodeEntity>()
    val stack = ArrayDeque<Pair<CommentNode, String?>>()
    roots.asReversed().forEach { stack.addLast(it to null) }
    while (stack.isNotEmpty()) {
        val (node, parentId) = stack.removeLast()
        val index = result.size
        result += when (node) {
            is CommentNode.Comment -> CommentNodeEntity(
                accountId, postId, threadKey, node.id, parentId, index, KIND_COMMENT,
                node.author, node.authorDisplayName, node.authorAvatarUrl, node.isEdited,
                node.body, node.score, node.viewerVote, node.createdAgoMin,
                0, "",
            )
            is CommentNode.LoadMore -> CommentNodeEntity(
                accountId, postId, threadKey, node.id, parentId, index, KIND_LOAD_MORE,
                null, null, null, false, null, 0, 0, 0, node.remainingCount,
                node.childIds.joinToString(CHILD_SEPARATOR.toString()),
            )
        }
        if (node is CommentNode.Comment) {
            node.children.asReversed().forEach { stack.addLast(it to node.id) }
        }
    }
    return result
}

/** Bottom-up, iterative decoding has O(n) time and does not recurse. */
private fun List<CommentNodeEntity>.toTree(metadata: CommentThreadEntity): CommentTree {
    val children = HashMap<String?, ArrayDeque<CommentNode>>()
    asReversed().forEach { row ->
        val node = if (row.kind == KIND_COMMENT) {
            CommentNode.Comment(
                id = row.nodeId,
                author = requireNotNull(row.author),
                body = requireNotNull(row.body),
                score = row.score,
                viewerVote = row.viewerVote,
                createdAgoMin = row.createdAgoMin,
                authorDisplayName = row.authorDisplayName ?: requireNotNull(row.author),
                authorAvatarUrl = row.authorAvatarUrl,
                isEdited = row.isEdited,
                children = children.remove(row.nodeId)?.toList().orEmpty(),
            )
        } else {
            CommentNode.LoadMore(
                id = row.nodeId,
                parentId = row.parentId,
                remainingCount = row.remainingCount,
                childIds = row.childIds.takeIf(String::isNotEmpty)
                    ?.split(CHILD_SEPARATOR)
                    .orEmpty(),
            )
        }
        children.getOrPut(row.parentId) { ArrayDeque() }.addFirst(node)
    }
    return CommentTree(
        postId = metadata.postId,
        roots = children[null]?.toList().orEmpty(),
        requestedCount = metadata.requestedCount,
        requestedDepth = metadata.requestedDepth,
    )
}

private fun PostHeader.toEntity(accountId: String) = PostHeaderEntity(
    accountId, postId, title, author, subreddit, score, commentCount, body, viewerVote, kind, linkUrl,
    media?.placeholderColor, media?.aspectRatio, media?.isVideo, media?.durationSeconds,
    media?.url, media?.altText, media?.hlsUrl, media?.dashUrl, media?.posterUrl,
    media?.fallbackUrl, media?.deliveryStatus, media?.processingProgress,
    System.currentTimeMillis(),
)

private fun PostHeaderEntity.toDomain() = PostHeader(
    postId, title, author, subreddit, score, commentCount, body,
    mediaPlaceholderColor?.let {
        PostMedia(
            it,
            requireNotNull(mediaAspectRatio),
            mediaIsVideo == true,
            mediaDurationSeconds,
            mediaUrl,
            mediaAltText.orEmpty(),
            mediaHlsUrl,
            mediaDashUrl,
            mediaPosterUrl,
            mediaFallbackUrl,
            mediaDeliveryStatus ?: "not_applicable",
            mediaProcessingProgress ?: 0,
        )
    },
    viewerVote, kind, linkUrl,
)
