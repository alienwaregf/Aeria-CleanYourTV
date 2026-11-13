package com.gathin.tvhome.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Where the install session of a self-compile reinstall reports back (Updates.reinstallSelf). */
class UpdateResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updates.onSessionResult(context, intent)
}
