package com.gathin.tvhome.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updates.onSessionResult(context, intent)
}
