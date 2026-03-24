package com.contoso.mdmtest

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalIntuneAppProtectionPolicyRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import com.microsoft.intune.mam.client.app.MAMComplianceManager
import com.microsoft.intune.mam.policy.MAMEnrollmentManager
import com.microsoft.intune.mam.policy.notification.MAMComplianceNotification
import com.microsoft.intune.mam.policy.notification.MAMEnrollmentNotification
import com.microsoft.intune.mam.policy.notification.MAMNotificationType
import com.microsoft.intune.mam.client.notification.MAMNotificationReceiverRegistry

/**
 * Single Activity — handles the entire auth + MAM enrollment + WebView flow.
 *
 * Flow:
 *  1. MSAL PCA initialises
 *  2. Interactive login (first run) or silent login (subsequent runs)
 *  3a. Happy path: token acquired → inject into WebView → chat starts
 *  3b. CA gate: MsalIntuneAppProtectionPolicyRequiredException thrown
 *      → remediateCompliance() → wait ENROLLMENT_SUCCEEDED
 *      → retry acquireTokenSilent() → inject token → chat starts
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val msalClient get() = App.instance.msalClient

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        configureWebView()

        msalClient.init(
            onReady = { startAuthFlow(it) },
            onError = { showError("MSAL init failed: ${it.message}") }
        )
    }

    // -------------------------------------------------------------------------
    // WebView setup
    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.webViewClient = WebViewClient()

        // Register JS bridge — JS calls window.NativeBridge.onTokenExpired() etc.
        webView.addJavascriptInterface(
            TokenBridge(onTokenExpired = { refreshToken() }),
            "NativeBridge"
        )

        // Load the chat UI from assets — the bundle is built by the webchat project
        webView.loadUrl("file:///android_asset/index.html")
    }

    // -------------------------------------------------------------------------
    // Auth flow — Step 1: attempt silent, fall back to interactive
    // -------------------------------------------------------------------------

    private fun startAuthFlow(pca: ISingleAccountPublicClientApplication) {
        pca.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount != null) {
                    acquireTokenSilent()
                } else {
                    acquireTokenInteractive(pca)
                }
            }
            override fun onAccountChanged(prior: IAccount?, current: IAccount?) {
                if (current != null) acquireTokenSilent() else acquireTokenInteractive(pca)
            }
            override fun onError(e: MsalException) = showError("Account check failed: ${e.message}")
        })
    }

    // -------------------------------------------------------------------------
    // Auth flow — Step 2a: interactive login (first run)
    // -------------------------------------------------------------------------

    private fun acquireTokenInteractive(pca: ISingleAccountPublicClientApplication) {
        val params = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(this)
            .withScopes(msalClient.copilotScopes)
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    Log.i(App.TAG, "Interactive login succeeded — account: ${result.account.username}")
                    // Register with MAM after successful login
                    MAMEnrollmentManager.getInstance().registerAccountForMAM(
                        result.account.username,
                        result.account.id,        // OID
                        result.account.tenantId ?: ""
                    )
                    onTokenReady(result.accessToken)
                }
                override fun onError(e: MsalException) = handleMsalError(e)
                override fun onCancel() = showError("Login cancelled")
            })
            .build()
        pca.acquireToken(params)
    }

    // -------------------------------------------------------------------------
    // Auth flow — Step 2b: silent token (subsequent runs / refresh)
    // -------------------------------------------------------------------------

    fun acquireTokenSilent() {
        msalClient.acquireTokenSilent(
            scopes = msalClient.copilotScopes,
            onSuccess = { token -> onTokenReady(token) },
            onFailure = { e ->
                if (e is MsalException) handleMsalError(e)
                else showError("Silent auth failed: ${e.message}")
            }
        )
    }

    // -------------------------------------------------------------------------
    // Auth flow — Step 3: token refresh (called by TokenBridge from JS)
    // -------------------------------------------------------------------------

    private fun refreshToken() {
        Log.i(App.TAG, "Refreshing token for WebView")
        runOnUiThread { acquireTokenSilent() }
    }

    // -------------------------------------------------------------------------
    // CA flow — handles MsalIntuneAppProtectionPolicyRequiredException
    // -------------------------------------------------------------------------

    private fun handleMsalError(e: MsalException) {
        when (e) {
            is MsalIntuneAppProtectionPolicyRequiredException -> {
                Log.i(App.TAG, "CA policy required — starting MAM remediation")
                startMamRemediation(e)
            }
            is MsalUserCancelException -> showError("Login cancelled")
            else -> showError("Auth error: ${e.message}")
        }
    }

    private fun startMamRemediation(e: MsalIntuneAppProtectionPolicyRequiredException) {
        // Step 1: register to receive ENROLLMENT_SUCCEEDED notification
        MAMNotificationReceiverRegistry.instance.registerReceiver(
            { notification ->
                val n = notification as? MAMEnrollmentNotification ?: return@registerReceiver true
                Log.i(App.TAG, "MAM enrollment result: ${n.enrollmentResult}")
                if (n.enrollmentResult == MAMEnrollmentManager.Result.ENROLLMENT_SUCCEEDED) {
                    Log.i(App.TAG, "Enrollment succeeded — retrying silent token acquisition")
                    // Step 3: retry token ONLY after ENROLLMENT_SUCCEEDED
                    runOnUiThread { acquireTokenSilent() }
                } else {
                    showError("MAM enrollment failed: ${n.enrollmentResult}")
                }
                true
            },
            MAMNotificationType.MAM_ENROLLMENT_RESULT
        )

        // Also register for COMPLIANCE_STATUS (remediateCompliance path)
        MAMNotificationReceiverRegistry.instance.registerReceiver(
            { notification ->
                val n = notification as? MAMComplianceNotification ?: return@registerReceiver true
                Log.i(App.TAG, "MAM compliance status: ${n.complianceStatus}")
                when (n.complianceStatus) {
                    com.microsoft.intune.mam.policy.MAMCAComplianceStatus.COMPLIANT -> {
                        Log.i(App.TAG, "Compliance = COMPLIANT — retrying silent token")
                        runOnUiThread { acquireTokenSilent() }
                    }
                    else -> showError("Compliance not met: ${n.complianceStatus}")
                }
                true
            },
            MAMNotificationType.COMPLIANCE_STATUS
        )

        // Step 2: call remediateCompliance() with exact values from the exception
        // DO NOT construct these manually — mismatch = enrollment against wrong identity
        MAMComplianceManager.instance.remediateCompliance(
            e.accountUpn,
            e.accountUserId,   // OID — canonical identity key
            e.tenantId,
            e.authorityUrl,
            true               // showUX — let Company Portal show its UI
        )
    }

    // -------------------------------------------------------------------------
    // Token ready — inject into WebView
    // -------------------------------------------------------------------------

    private fun onTokenReady(token: String) {
        Log.i(App.TAG, "Token ready — injecting into WebView")
        runOnUiThread {
            // Escape single quotes to avoid JS injection issues
            val safe = token.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript(
                "window.__authToken = '$safe'; window.dispatchEvent(new Event('authReady'));",
                null
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun showError(message: String) {
        Log.e(App.TAG, message)
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            // Surface the error inside the WebView too so it's visible during testing
            val safe = message.replace("'", "\\'")
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('authError', { detail: '$safe' }));",
                null
            )
        }
    }
}
