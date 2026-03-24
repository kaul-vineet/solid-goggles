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

## Copilot Studio Setup
*Configure your agent so the Android app can talk to it*

This section covers everything you need to do inside Copilot Studio before the app can connect.

---

### Step CS-1 — Create or open your agent

- Go to `https://copilotstudio.microsoft.com`
- Sign in with your tenant admin or maker account
- Either open an existing agent or click **+ Create** → **New agent**
- Give it a name e.g. `MDM Test Agent`

---

### Step CS-2 — Enable Microsoft (AAD) authentication

This is the most important step. The Android app gets a token from Entra ID and passes it to the agent. For that to work, the agent must be configured to accept AAD tokens.

- Inside your agent click **Settings** (top right gear icon)
- Click **Security** in the left panel
- Click **Authentication**
- Select **Authenticate with Microsoft**
- Click **Save**

> If you leave authentication as "No authentication" the app token will be ignored and anyone can talk to the agent — do not do this for a real test.

After saving you will see two values appear on this screen — copy both and save in Notepad:
- **App ID** (this is the bot's Entra app registration ID — used in `MsalClient.kt`)
- **Tenant ID** (should match your tenant)

---

### Step CS-3 — Note the Environment ID and Schema name

These go into `webchat/src/chat.js`.

- Still in **Settings** → click the **About** tab
- Copy and save:
  - **Environment ID** (a GUID like `abc12345-0000-...`)
  - **Schema name** (something like `cr123_mdmTestAgent`)

---

### Step CS-4 — Configure the agent's scope in Entra

The Android app requests a token with scope `api://<bot-app-id>/.default`. For Entra to honour this, the bot's app registration must expose that scope.

- Go to `https://entra.microsoft.com`
- **Applications** → **App registrations** → search for the bot's App ID (from Step CS-2)
- Click **Expose an API** in the left menu
- If no Application ID URI is set, click **Add** next to it — accept the default `api://<app-id>`
- Click **+ Add a scope**
  - Scope name: `user_impersonation`
  - Who can consent: **Admins and users**
  - Display name / description: fill in anything e.g. `Access Copilot agent`
  - Click **Add scope**
- Now go back to **your MDM Test App** registration (the one you created in Step 3 of Part A)
  - **API permissions** → **+ Add a permission** → **My APIs**
  - Find the bot app → tick `user_impersonation` → **Add permissions**
  - Click **Grant admin consent**

---

### Step CS-5 — Add a topic to test with

So you have something to talk to when you run the app:

- In your agent click **Topics** → **+ Add a topic** → **From blank**
- Name it `Hello`
- In the **Trigger phrases** box add: `hi`, `hello`, `test`
- Add a **Message** node with text: `Hi! I am working. The MAM flow succeeded.`
- Click **Save**

---

### Step CS-6 — Publish the agent

The agent must be published before the Android app can reach it. Unpublished agents are only accessible inside Copilot Studio itself.

- Click **Publish** (top right)
- Click **Publish** again on the confirmation screen
- Wait ~1 minute for publishing to complete

---

### Step CS-7 — Enable the Direct Line channel

The M365 `CopilotStudioClient` connects via the Direct Line channel.

- In your agent go to **Settings** → **Channels**
- Find **Mobile app** or **Direct Line** → click to open
- Make sure it is **enabled**
- No extra config needed — the SDK handles the connection using the token you provide

---

### Step CS-8 — Verify the full config checklist

Before building the Android app confirm you have all four values:

| Value | Where to find it | Goes into |
|---|---|---|
| Bot App ID | Copilot Studio → Settings → Security → Authentication | `MsalClient.kt` copilotScopes |
| Environment ID | Copilot Studio → Settings → About | `webchat/src/chat.js` |
| Schema name | Copilot Studio → Settings → About | `webchat/src/chat.js` |
| Tenant ID | Entra → Directory (tenant) ID | `msal_config.json` + `chat.js` |

---

### How the connection works end to end

```
Android app
  → MSAL acquires token with scope api://<bot-app-id>/.default
  → token injected into WebView as window.__authToken
  → CopilotStudioClient.createConversation() called
      → sends token in Authorization header to Copilot Studio
      → Copilot Studio validates token against Entra
      → conversation started
  → sendMessage("hi") → agent replies with "Hi! I am working..."
```

---

## Step-by-step testing guide (with a real device and tenant)

This walks through the complete setup from scratch.

---

### Part A — Entra ID Setup
*Give your app an identity card*

**Step 1 — Open Entra admin center**

Go to `https://entra.microsoft.com` and sign in with your tenant admin account.

**Step 2 — Create an app registration**

- Left menu → **Applications** → **App registrations**
- Click **+ New registration**
- Name: `MDM Test App`
- Supported account types: `Accounts in this organizational directory only`
- Redirect URI: leave blank for now
- Click **Register**

**Step 3 — Copy the important IDs**

On the registration page, copy and save in Notepad:
- **Application (client) ID**
- **Directory (tenant) ID**

**Step 4 — Add API permissions**

- Click **API permissions** in the left menu
- Click **+ Add a permission** → **APIs my organization uses**
- Search for `Microsoft Mobile Application Management`
- Select **Delegated permissions** → tick `DeviceManagementManagedApps.ReadWrite`
- Click **Add permissions**
- Click **Grant admin consent for [your tenant]** → Yes

**Step 5 — Find your Copilot bot app ID**

- Go to `https://copilotstudio.microsoft.com`
- Open your agent → **Settings** → **Security** → **Authentication**
- Confirm it says **Authenticate with Microsoft**
- Copy the **App ID** shown — save in Notepad

---

### Part B — Intune Setup
*Tell Intune to protect your app*

**Step 6 — Open Intune admin center**

Go to `https://intune.microsoft.com` and sign in with your admin account.

**Step 7 — Create an App Protection Policy**

- Click **Apps** → **App protection policies** → **+ Create policy** → **Android**
- Name: `MDM Test Policy`
- Click **Next**
- On the **Apps** page → click **+ Select custom apps**
- Enter package name: `com.contoso.mdmtest` → Add
- Click **Next** through remaining pages (leave defaults)
- On **Assignments** page → **+ Add groups** → add your test user or group
- Click **Next** → **Create**

> Wait 15–30 minutes after creating the policy before testing. Intune needs time to activate it.

---

### Part C — Build and run the app

**Step 8 — Install Android Studio**

Download from `https://developer.android.com/studio` and install it like any normal Windows app.

**Step 9 — Open the project**

- Open Android Studio → click **Open**
- Navigate to: `C:\Users\vineetkaul\OneDrive - Microsoft\POWER CAT\BFL\mdm-project`
- Click OK and wait for "Gradle sync finished" in the bottom bar

**Step 10 — Get your app's signature**

Open the **Terminal** tab at the bottom of Android Studio and run:

```bash
keytool -exportcert -alias androiddebugkey \
  -keystore "%USERPROFILE%\.android\debug.keystore" \
  -storepass android | openssl sha1 -binary | openssl base64
```

You'll get a short string like `abc123XYZ==` — copy it and save in Notepad.

**Step 11 — Fill in `msal_config.json`**

In Android Studio open: `app > src > main > res > raw > msal_config.json`

```json
{
  "client_id": "← Application (client) ID from Step 3",
  "redirect_uri": "msauth://com.contoso.mdmtest/← signature from Step 10",
  "broker_redirect_uri_registered": true,
  "client_capabilities": ["ProtApp"],
  "account_mode": "SINGLE",
  "authorities": [{
    "type": "AAD",
    "audience": {
      "type": "AzureADMyOrg",
      "tenant_id": "← Directory (tenant) ID from Step 3"
    }
  }]
}
```

**Step 12 — Fill in the Copilot scope**

Open: `app > src > main > java > com > contoso > mdmtest > MsalClient.kt`

Find and update:
```kotlin
val copilotScopes = listOf("api://YOUR_COPILOT_BOT_APP_ID/.default")
```
Replace `YOUR_COPILOT_BOT_APP_ID` with the bot App ID from Step 5.

**Step 13 — Fill in the chat config**

Open: `webchat > src > chat.js`

Find and update:
```js
const COPILOT_CONFIG = {
    environmentId: "← from Copilot Studio > Settings > About",
    agentIdentifier: "← schema name from Copilot Studio > Settings > About",
    tenantId: "← Directory (tenant) ID from Step 3",
};
```

**Step 14 — Update AndroidManifest with the signature**

Open: `app > src > main > AndroidManifest.xml`

Find this line:
```xml
android:path="/YOUR_BASE64_ENCODED_PACKAGE_SIGNATURE"
```
Replace `YOUR_BASE64_ENCODED_PACKAGE_SIGNATURE` with your signature from Step 10. Keep the `/` before it.

**Step 15 — Add the redirect URI to Entra**

Back in Entra → your app registration → **Authentication**:
- Click **+ Add a platform** → **Android**
- Package name: `com.contoso.mdmtest`
- Signature hash: paste signature from Step 10
- Click **Configure**

**Step 16 — Build the chat bundle**

In Android Studio's Terminal tab:
```bash
cd webchat
npm install
npm run build
```

This creates `app/src/main/assets/chat.bundle.js`. You must do this before running the app.

**Step 17 — Prepare your Android device**

- Connect the device to your PC via USB
- On the device: **Settings** → **About phone** → tap **Build number** 7 times (unlocks Developer Options)
- **Settings** → **Developer options** → turn on **USB debugging**
- A prompt appears on the device asking to allow your PC — tap **Allow**

**Step 18 — Install Company Portal on the device**

On the device:
- Open **Play Store** → search **Intune Company Portal** → Install
- You do not need to sign into it — just having it installed is enough

**Step 19 — Run the app**

In Android Studio:
- Your device should appear in the device dropdown at the top (next to the green play button)
- Click the green **▶ Run** button
- The app builds and installs on your device automatically

**Step 20 — Test the flow**

When the app opens on the device:
1. A Microsoft login screen appears → sign in with your test user
2. If the Intune policy is active, Company Portal opens briefly for remediation — let it complete
3. The chat screen appears — type a message and the Copilot agent should respond

**If something goes wrong:** in Android Studio click the **Logcat** tab at the bottom, filter by `MdmTest` — every step in the app is logged there.

---

## References

- [Intune MAM SDK Android — Phase 2 (MSAL)](https://learn.microsoft.com/en-us/intune/intune-service/developer/app-sdk-android-phase2)
- [Intune MAM SDK Android — Phase 4 (Enrollment)](https://learn.microsoft.com/en-us/mem/intune/developer/app-sdk-android-phase4)
- [Intune MAM SDK Android — Phase 7 (App Protection CA)](https://learn.microsoft.com/en-us/intune/intune-service/developer/app-sdk-android-phase7)
- [M365 Agents SDK — CopilotStudioClient](https://github.com/microsoft/agents)
- [MAM SDK Android GitHub](https://github.com/microsoftconnect/ms-intune-app-sdk-android)
