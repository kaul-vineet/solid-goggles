package com.contoso.mdmtest

import android.util.Log
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.intune.mam.client.app.MAMComponents
import com.microsoft.intune.mam.policy.MAMEnrollmentManager

/**
 * MAM SDK authentication callback.
 *
 * The MAM SDK calls acquireToken() whenever it needs to communicate with the
 * Intune MAM service (enrollment, policy refresh, check-ins). This is a SEPARATE
 * token from the one your app uses for Copilot — the resource here is the
 * Intune MAM service resource, not your bot.
 *
 * CRITICAL: This callback must return synchronously (it runs on a background thread).
 * Use acquireTokenSilent() — never interactive — here.
 */
class MAMAuthCallback(private val msalClient: MsalClient) :
    MAMEnrollmentManager.MAMServiceAuthenticationCallback {

    /**
     * @param upn       User principal name (informational only — do NOT use for account lookup)
     * @param aadId     AAD Object ID (OID) — the canonical identity key, use this
     * @param resourceId The Intune MAM service resource URI the SDK needs a token for
     * @return          Access token string, or null if unavailable (SDK retries later)
     */
    override fun acquireToken(upn: String, aadId: String, resourceId: String): String? {
        Log.d(App.TAG, "MAMAuthCallback.acquireToken called — upn=$upn aadId=$aadId resource=$resourceId")

        val pca = msalClient.getPca() ?: run {
            Log.w(App.TAG, "MAMAuthCallback: PCA not ready yet — returning null (SDK will retry)")
            return null
        }

        // Look up the account by OID (aadId), not by UPN
        val account = try {
            (pca as? ISingleAccountPublicClientApplication)
                ?.currentAccount?.currentAccount
                ?.takeIf { it.id == aadId }
        } catch (e: Exception) {
            Log.e(App.TAG, "MAMAuthCallback: account lookup failed: ${e.message}")
            null
        }

        if (account == null) {
            Log.w(App.TAG, "MAMAuthCallback: no matching account for aadId=$aadId — returning null")
            return null
        }

        // Build scopes from the resourceId the SDK provides
        val scopes = listOf("$resourceId/.default")

        var resultToken: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        val params = AcquireTokenSilentParameters.Builder()
            .withScopes(scopes)
            .forAccount(account)
            .build()

        pca.acquireTokenSilentAsync(params, object : SilentAuthenticationCallback {
            override fun onSuccess(result: com.microsoft.identity.client.IAuthenticationResult) {
                resultToken = result.accessToken
                latch.countDown()
            }
            override fun onError(e: MsalException) {
                Log.e(App.TAG, "MAMAuthCallback: silent token failed: ${e.message}")
                latch.countDown()
            }
        })

        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        return resultToken
    }
}
