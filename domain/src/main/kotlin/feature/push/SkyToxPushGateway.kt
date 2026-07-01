// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature.push

import ltd.evilcorp.core.vo.PublicKey

interface SkyToxPushGateway {
    fun refreshTokenAndShare()
    fun shareOwnToken(publicKey: PublicKey)
    fun rememberFriendToken(publicKey: String, packet: ByteArray): Boolean
    fun wake(publicKey: PublicKey, reason: String = "message")
}
