// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

/** Rollback switches for the phased radio rebuild. Hardware-gated phases stay off by default. */
internal object RadioFeatureFlags {
    const val RADIO_V2_ENABLED = true
    const val CODEC_OPUS_ENABLED = true
    const val JITTER_V2_ENABLED = false
    const val DETAILED_METRICS_ENABLED = true
    const val STATIC_NOISE_ENABLED = true
    const val PTT_WATCHDOG_ENABLED = true
    const val AUDIO_WATCHDOG_ENABLED = true
    const val WIFI_LINK_WATCHDOG_ENABLED = true
    const val WATCHDOG_ACTIVE_RECOVERY_ENABLED = true
    const val WATCHDOG_DETAILED_EVENTS_ENABLED = true
}
