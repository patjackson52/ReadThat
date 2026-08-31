package dev.readthat.client

import android.content.Context
import java.util.UUID

internal class AndroidProductAnalyticsStateStore(context: Context) : ProductAnalyticsStateStore {
    private val preferences = context.applicationContext
        .getSharedPreferences("product_analytics_v1", Context.MODE_PRIVATE)

    override val installationId: String = preferences.getString(INSTALLATION_ID, null)
        ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        ?: UUID.randomUUID().toString().also { value ->
            preferences.edit().putString(INSTALLATION_ID, value).commit()
        }

    override fun readCheckpoint(): String? = preferences.getString(CHECKPOINT, null)

    override fun writeCheckpoint(value: String?) {
        preferences.edit().apply {
            if (value == null) remove(CHECKPOINT) else putString(CHECKPOINT, value)
        }.apply()
    }

    private companion object {
        const val INSTALLATION_ID = "installation_id"
        const val CHECKPOINT = "checkpoint_json"
    }
}
