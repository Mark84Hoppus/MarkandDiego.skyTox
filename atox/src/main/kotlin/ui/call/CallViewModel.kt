// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.call

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ltd.evilcorp.atox.ProximityScreenOff
import ltd.evilcorp.atox.ui.NotificationHelper
import ltd.evilcorp.core.vo.Contact
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.CallManager
import ltd.evilcorp.domain.feature.ContactManager

class CallViewModel @Inject constructor(
    private val scope: CoroutineScope,
    private val callManager: CallManager,
    private val notificationHelper: NotificationHelper,
    private val contactManager: ContactManager,
    private val proximityScreenOff: ProximityScreenOff,
) : ViewModel() {
    private var publicKey = PublicKey("")

    val contact: LiveData<Contact> by lazy {
        contactManager.get(publicKey).asLiveData()
    }

    fun setActiveContact(pk: PublicKey) {
        publicKey = pk
    }

    fun startCall(requestVideo: Boolean = false) {
        callManager.startCall(publicKey, requestVideo)
        scope.launch { notificationHelper.showOngoingCallNotification(contactManager.get(publicKey).first()) }
    }

    fun acceptIncomingCall() = startCall()

    fun endCall() = scope.launch {
        callManager.endCall(publicKey)
        notificationHelper.dismissCallNotification(publicKey)
    }

    fun startSendingAudio() = callManager.startSendingAudio()
    fun stopSendingAudio() = callManager.stopSendingAudio()
    fun sendVideoFrame(width: Int, height: Int, y: ByteArray, u: ByteArray, v: ByteArray) =
        callManager.sendVideoFrame(width, height, y, u, v)
    fun setLocalVideoEnabled(enabled: Boolean) = callManager.setLocalVideoEnabled(enabled)

    fun toggleSpeakerphone() {
        speakerphoneOn = !speakerphoneOn
        if (speakerphoneOn) {
            proximityScreenOff.release()
        } else {
            proximityScreenOff.acquire()
        }
    }

    val inCall = callManager.inCall
    val pendingCalls = callManager.pendingCalls
    val sendingAudio = callManager.sendingAudio
    val incomingVideoFrame = callManager.incomingVideoFrame
    val localVideoEnabled = callManager.localVideoEnabled
    val outgoingVideoHeight = callManager.outgoingVideoHeight

    fun hasPendingCall() = callManager.pendingCalls.value.any { it.publicKey == publicKey.string() }

    var speakerphoneOn by callManager::speakerphoneOn
}
