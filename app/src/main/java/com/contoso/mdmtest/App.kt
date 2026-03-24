package com.contoso.mdmtest

import android.app.Application
import android.util.Log
import com.microsoft.intune.mam.client.app.MAMApplication
import com.microsoft.intune.mam.client.notification.MAMNotificationReceiverRegistry
import com.microsoft.intune.mam.policy.MAMEnrollmentManager
import com.microsoft.intune.mam.policy.notification.MAMEnrollmentNotification
import com.microsoft.intune.mam.policy.notification.MAMNotificationType

/**
 * Application class — the MAM SDK Gradle plugin rewrites this to extend MAMApplication.
 * All MAM SDK initialisation (auth callback, notification receivers) MUST happen here,
 * in onMAMCreate(), not in Activity.onCreate().
 */
class App : Application() {

    companion object {
        const val TAG = "MdmTest"
        lateinit var instance: App
            private set
    }

    // Shared MSAL wrapper — initialised lazily after onMAMCreate
    lateinit var msalClient: MsalClient

    override fun onCreate() {
        super.onCreate()
        instance = this
        msalClient = MsalClient(this)

        // Register MAM auth callback — SDK calls this whenever it needs a token
        // for the Intune MAM service (separate from your app's own token).
        MAMEnrollmentManager.getInstance().registerAuthenticationCallback(
            MAMAuthCallback(msalClient)
        )

        // Register enrollment result receiver
        MAMNotificationReceiverRegistry.instance.registerReceiver(
            { notification ->
                val n = notification as? MAMEnrollmentNotification ?: return@registerReceiver true
                Log.i(TAG, "MAM enrollment result: ${n.enrollmentResult}")
                true
            },
            MAMNotificationType.MAM_ENROLLMENT_RESULT
        )

        Log.i(TAG, "App initialised — MAM auth callback registered")
    }
}
