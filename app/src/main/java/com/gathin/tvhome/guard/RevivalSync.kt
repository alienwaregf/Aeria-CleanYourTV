package com.gathin.tvhome.guard

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

object RevivalSync {
    const val TAG = "AerialGuard"
    private const val AUTHORITY = "settings"

    fun ensure(context: Context) {
        if (!GuardSetup.isUserUnlocked(context)) return
        runCatching {
            val accountType = context.packageName
            val accounts = AccountManager.get(context)
            val account = accounts.getAccountsByType(accountType).firstOrNull()
                ?: Account("Aerial", accountType).also {
                    if (!accounts.addAccountExplicitly(it, null, null)) return
                    Log.i(TAG, "added the account that lets the sync framework revive Aerial")
                }
            if (ContentResolver.getIsSyncable(account, AUTHORITY) <= 0) ContentResolver.setIsSyncable(account, AUTHORITY, 1)
            if (!ContentResolver.getSyncAutomatically(account, AUTHORITY)) ContentResolver.setSyncAutomatically(account, AUTHORITY, true)
            if (!ContentResolver.getMasterSyncAutomatically()) Log.w(TAG, "auto-sync is off on this TV; the sync framework cannot revive Aerial")
        }.onFailure { Log.w(TAG, "could not set up the sync revival", it) }
    }
}

class RevivalSyncService : Service() {
    private val adapter by lazy { Adapter(applicationContext) }

    override fun onBind(intent: Intent): IBinder = adapter.syncAdapterBinder

    private class Adapter(context: Context) : AbstractThreadedSyncAdapter(context, false) {
        override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, result: SyncResult) {
            if (!GuardSetup.isUserUnlocked(context)) return
            GuardSetup.ensureAdb(context)
            val turnedOn = GuardSetup.ensureEnabled(context)
            if (HomeGuardService.isRunning) return
            val now = SystemClock.elapsedRealtime()
            if (turnedOn) {
                Log.i(RevivalSync.TAG, "revived by the sync framework")
                GuardSetup.noteRevival(context)
            } else {
                if (now - lastStalledRebindAt < STALLED_REBIND_GAP_MS) return
                lastStalledRebindAt = now
                Log.i(RevivalSync.TAG, "sync framework: Home Guard enabled but not bound")
            }
            Handler(Looper.getMainLooper()).postDelayed({ GuardSetup.rebindIfStalled(context) }, 5_000)
        }
    }

    private companion object {
        const val STALLED_REBIND_GAP_MS = 60_000L
        @Volatile var lastStalledRebindAt = Long.MIN_VALUE / 2
    }
}

class RevivalAccountService : Service() {
    private val authenticator by lazy { Authenticator(this) }

    override fun onBind(intent: Intent): IBinder = authenticator.iBinder

    private class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
        override fun editProperties(response: AccountAuthenticatorResponse, accountType: String) = null
        override fun addAccount(response: AccountAuthenticatorResponse, accountType: String, authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle) = null
        override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, options: Bundle?) = null
        override fun getAuthToken(response: AccountAuthenticatorResponse, account: Account, authTokenType: String, options: Bundle) = null
        override fun getAuthTokenLabel(authTokenType: String) = null
        override fun updateCredentials(response: AccountAuthenticatorResponse, account: Account, authTokenType: String?, options: Bundle?) = null
        override fun hasFeatures(response: AccountAuthenticatorResponse, account: Account, features: Array<out String>) =
            Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
