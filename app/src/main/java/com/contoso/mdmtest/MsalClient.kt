package com.contoso.mdmtest

import android.content.Context
import android.util.Log
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException

/**
 * Thin wrapper around ISingleAccountPublicClientApplication.
 * Centralises all MSAL calls so both MainActivity and MAMAuthCallback
 * use the same PCA instance.
 */
class MsalClient(private val context: Context) {

    // Scopes for your Copilot Studio bot's Entra app registration.
    // Format: api://<bot-app-id>/.default
    val copilotScopes = listOf("api://YOUR_COPILOT_BOT_APP_ID/.default")

    private var pca: ISingleAccountPublicClientApplication? = null

    fun init(onReady: (ISingleAccountPublicClientApplication) -> Unit, onError: (Exception) -> Unit) {
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context,
            R.raw.msal_config,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(app: ISingleAccountPublicClientApplication) {
                    pca = app
                    Log.i(App.TAG, "MSAL PCA ready")
                    onReady(app)
                }
                override fun onError(exception: MsalException) {
                    Log.e(App.TAG, "MSAL init failed: ${exception.message}")
                    onError(exception)
                }
            }
        )
    }

    /**
     * Acquire token silently for the given scopes.
     * Returns the access token string, or null on failure.
     * Used by MAMAuthCallback and for Copilot token injection.
     */
    fun acquireTokenSilent(
        scopes: List<String>,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val app = pca ?: run { onFailure(IllegalStateException("PCA not initialised")); return }

        app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount == null) {
                    onFailure(IllegalStateException("No signed-in account"))
                    return
                }
                val params = AcquireTokenSilentParameters.Builder()
                    .withScopes(scopes)
                    .forAccount(activeAccount)
                    .build()
                app.acquireTokenSilentAsync(params, object : SilentAuthenticationCallback {
                    override fun onSuccess(result: IAuthenticationResult) = onSuccess(result.accessToken)
                    override fun onError(e: MsalException) = onFailure(e)
                })
            }
            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {}
            override fun onError(exception: MsalException) = onFailure(exception)
        })
    }

    fun getPca(): ISingleAccountPublicClientApplication? = pca
}
