package dev.readthat.client

import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID

internal class AppleProductAnalyticsStateStore : ProductAnalyticsStateStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override val installationId: String = defaults.stringForKey(INSTALLATION_ID)
        ?.takeIf(UUID_PATTERN::matches)
        ?: NSUUID().UUIDString.lowercase().also { value ->
            defaults.setObject(value, INSTALLATION_ID)
            defaults.synchronize()
        }

    override fun readCheckpoint(): String? = defaults.stringForKey(CHECKPOINT)

    override fun writeCheckpoint(value: String?) {
        if (value == null) defaults.removeObjectForKey(CHECKPOINT)
        else defaults.setObject(value, CHECKPOINT)
    }

    private companion object {
        const val INSTALLATION_ID = "dev.readthat.product.installation_id"
        const val CHECKPOINT = "dev.readthat.product.checkpoint_json"
        val UUID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )
    }
}
