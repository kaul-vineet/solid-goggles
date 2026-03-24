# MAM Test — Intune SDK + CopilotStudioClient on Android

Tests the end-to-end flow:
**Intune MAM SDK enrollment → AAD token with MAM compliance → CopilotStudioClient (M365 Agents SDK) → Copilot Studio agent**

---

## Architecture

```
Android Native App (Kotlin · single Activity)
│
├── MSAL (brokered auth via Company Portal)
│   └── Handles MsalIntuneAppProtectionPolicyRequiredException
│       └── MAMComplianceManager.remediateCompliance()
│           └── Waits for ENROLLMENT_SUCCEEDED
│               └── Retries acquireTokenSilent() → token now passes CA gate
│
└── WebView (loads assets/index.html + chat.bundle.js)
    ├── window.__authToken injected by native after token ready
    ├── CopilotStudioClient (@microsoft/agents-copilotstudio-client)
    │   └── getTokenAsync: () => window.__authToken
    └── window.NativeBridge.onTokenExpired() → triggers native token refresh
```

**Why a WebView for the chat UI?**
The M365 Agents SDK `CopilotStudioClient` is a JavaScript/TypeScript library. Running it in a WebView is the simplest path that avoids a full React Native setup while keeping the native layer in control of authentication.

---

## Prerequisites

### 1. Entra ID App Registration

Create an app registration in [Entra admin center](https://entra.microsoft.com):

- **Supported account types:** Accounts in this org directory only
- **Redirect URI:** `Android — msauth://com.contoso.mdmtest/<base64-signature>`
- **API permissions:**
  - `Microsoft Mobile Application Management` → `DeviceManagementManagedApps.ReadWrite` (Delegated) — **required for MAM enrollment**
  - `api://<your-copilot-bot-app-id>/.default` (Delegated) — scope for Copilot token
- **Grant admin consent** for both

Get your base64 signature:
```bash
keytool -exportcert -alias androiddebugkey \
  -keystore ~/.android/debug.keystore -storepass android \
  | openssl sha1 -binary | openssl base64
```

### 2. Intune App Protection Policy

In [Intune admin center](https://intune.microsoft.com) → Apps → App protection policies:

- Platform: **Android**
- Target apps: add your app's package name `com.contoso.mdmtest` (custom LOB app)
- Assign to: the test user / group
- Allow at minimum default policy settings (data protection, access requirements)

Policy changes take up to **15 minutes** to propagate. Newly targeted users may take up to 24 hours to appear in reports.

### 3. Intune MAM SDK Maven access

The MAM SDK is distributed via Microsoft's Azure DevOps Maven feed. Request access at:
https://github.com/microsoftconnect/ms-intune-app-sdk-android

Set environment variables before building:
```bash
export INTUNE_MAVEN_USER="your-ado-username"
export INTUNE_MAVEN_TOKEN="your-ado-pat"
```

Alternatively, download the AAR from GitHub Releases and add it as a local file dependency.

### 4. Copilot Studio Agent

In [Copilot Studio](https://copilotstudio.microsoft.com):
- Publish your agent
- Settings → Security → Authentication → **Authenticate with Microsoft** (AAD)
- Note the **Environment ID** and **Agent schema name**

### 5. Device

- Android device or emulator (API 26+)
- **Company Portal installed** (not required to be signed in — just installed)
- Microsoft Authenticator recommended (but not required for MAM-WE)

---

## Configuration

### 1. `app/src/main/res/raw/msal_config.json`

```json
{
  "client_id": "YOUR_ENTRA_APP_CLIENT_ID",
  "redirect_uri": "msauth://com.contoso.mdmtest/YOUR_BASE64_SIGNATURE",
  "broker_redirect_uri_registered": true,
  "client_capabilities": ["ProtApp"],
  "account_mode": "SINGLE",
  "authorities": [{
    "type": "AAD",
    "audience": { "type": "AzureADMyOrg", "tenant_id": "YOUR_TENANT_ID" }
  }]
}
```

`client_capabilities: ["ProtApp"]` is mandatory — without it Entra ID will not return the `protection_policy_required` sub-error and the CA flow never triggers.

### 2. `app/src/main/java/.../MsalClient.kt`

```kotlin
val copilotScopes = listOf("api://YOUR_COPILOT_BOT_APP_ID/.default")
```

### 3. `webchat/src/chat.js`

```js
const COPILOT_CONFIG = {
    environmentId: "YOUR_ENVIRONMENT_ID",
    agentIdentifier: "YOUR_AGENT_SCHEMA_NAME",
    tenantId: "YOUR_TENANT_ID",
};
```

### 4. `app/src/main/AndroidManifest.xml`

Update the `BrowserTabActivity` intent filter `android:path` with your base64 signature.

---

## Build

### Step 1 — Build the JS bundle

```bash
cd webchat
npm install
npm run build
# Outputs: app/src/main/assets/chat.bundle.js
```

### Step 2 — Build the Android app

Open the project root in Android Studio, or:

```bash
./gradlew assembleDebug
```

The Intune MAM Gradle plugin runs automatically during the build and rewrites Android base classes (`Activity` → `MAMActivity` etc.). This is normal — no manual class changes are needed.

---

## Running the flow

1. Install Company Portal on the device (Play Store)
2. Install the debug APK
3. Launch the app — interactive MSAL login appears
4. Sign in with a user targeted by the Intune App Protection Policy

**Happy path (no CA policy):**
```
Login → registerAccountForMAM() → ENROLLMENT_SUCCEEDED
→ token injected into WebView → chat starts
```

**CA policy path:**
```
Login → MsalIntuneAppProtectionPolicyRequiredException thrown
→ remediateCompliance() called
→ Company Portal shows remediation UI (if showUX=true)
→ ENROLLMENT_SUCCEEDED received
→ acquireTokenSilent() retried → token passes CA gate
→ token injected into WebView → chat starts
```

---

## Troubleshooting

### "enrollment id missing"

| Check | Where |
|---|---|
| `client_capabilities: ["ProtApp"]` in msal_config.json? | `res/raw/msal_config.json` |
| `broker_redirect_uri_registered: true`? | Same file |
| `DeviceManagementManagedApps.ReadWrite` granted in Entra? | Entra → App registrations → API permissions |
| App package name in Intune App Protection Policy? | Intune → App protection policies → Apps |
| Company Portal installed on device? | Device |
| `aadId` parameter in `remediateCompliance()` = OID from exception? | `MainActivity.kt` `startMamRemediation()` — already correct in scaffold |
| Previous account unregistered on sign-out? | Call `MAMEnrollmentManager.getInstance().unregisterAccountForMAM(upn, aadId)` |

Check Android logcat filter `MdmTest` — all key steps log with this tag.

### `AUTHORIZATION_NEEDED` enrollment result

`MAMAuthCallback.acquireToken()` returned null. Check:
- Is the OID (`aadId`) from the exception matching the signed-in MSAL account?
- Is `MsalClient.getPca()` returning a non-null PCA at callback time?

### Chat UI stuck on "Waiting for authentication..."

The `authReady` event was not dispatched. Check logcat for "Token ready — injecting into WebView".

### `MsalIntuneAppProtectionPolicyRequiredException` never thrown

- Confirm `client_capabilities: ["ProtApp"]` is present
- Confirm the CA policy is **On** (not Report-only) in Entra → Conditional Access
- Confirm the CA grant is **Require app protection policy**, not "Require compliant device"

### Policy propagation delay

If you just assigned the App Protection Policy to the user, wait 15–30 minutes and retry. The Intune service must propagate the assignment before enrollment succeeds.

---

## Project structure

```
mdm-project/
├── build.gradle                    Root build — AGP + Kotlin + MAM plugin versions
├── settings.gradle                 Module list + Maven repos (including Intune feed)
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── app/
│   ├── build.gradle                App-level build
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── index.html          Chat UI shell
│       │   └── chat.bundle.js      Built by webchat/ (gitignored until built)
│       ├── res/
│       │   ├── layout/activity_main.xml
│       │   └── raw/msal_config.json
│       └── java/com/contoso/mdmtest/
│           ├── App.kt              Application — MAM SDK init, auth callback registration
│           ├── MainActivity.kt     Auth flow + CA remediation + WebView token injection
│           ├── MsalClient.kt       MSAL PCA wrapper (shared between Activity + MAMAuthCallback)
│           ├── MAMAuthCallback.kt  MAM SDK token callback (synchronous, background thread)
│           └── TokenBridge.kt      JS→Native bridge (token refresh, logging)
└── webchat/
    ├── package.json                npm project — esbuild bundles into assets/
    └── src/
        └── chat.js                 CopilotStudioClient chat logic
```

---

## Key design decisions

| Decision | Reason |
|---|---|
| Single Activity, no Compose | Simplest testable structure — no nav graph, no state hoisting |
| WebView for chat UI | CopilotStudioClient is a JS library — WebView avoids React Native overhead |
| Token injected via `evaluateJavascript` | No persistent storage of token; fresh on every native re-acquisition |
| `acquireTokenSilent` retry only after `ENROLLMENT_SUCCEEDED` | Retrying earlier = enrollment ID not yet in broker store = CA still fails |
| `remediateCompliance()` params from exception, not hardcoded | OID/UPN mismatch is the #1 cause of "enrollment id missing" |
| `CountDownLatch` in MAMAuthCallback | Callback runs on background thread; MSAL silent acquisition is async — latch bridges the gap |

---

## References

- [Intune MAM SDK Android — Phase 2 (MSAL)](https://learn.microsoft.com/en-us/intune/intune-service/developer/app-sdk-android-phase2)
- [Intune MAM SDK Android — Phase 4 (Enrollment)](https://learn.microsoft.com/en-us/mem/intune/developer/app-sdk-android-phase4)
- [Intune MAM SDK Android — Phase 7 (App Protection CA)](https://learn.microsoft.com/en-us/intune/intune-service/developer/app-sdk-android-phase7)
- [M365 Agents SDK — CopilotStudioClient](https://github.com/microsoft/agents)
- [MAM SDK Android GitHub](https://github.com/microsoftconnect/ms-intune-app-sdk-android)
