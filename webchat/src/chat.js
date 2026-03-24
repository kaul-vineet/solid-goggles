/**
 * chat.js — Copilot Studio chat using M365 Agents SDK CopilotStudioClient
 *
 * Loaded inside an Android WebView. The native app injects window.__authToken
 * and fires the 'authReady' event once the MAM-enrolled token is available.
 *
 * Token refresh:
 *   When the token expires, we call window.NativeBridge.onTokenExpired().
 *   The native app re-acquires silently and re-fires 'authReady'.
 */

import { CopilotStudioClient } from "@microsoft/agents-copilotstudio-client";

// ---------------------------------------------------------------------------
// Config — replace with your Copilot Studio agent details
// ---------------------------------------------------------------------------
const COPILOT_CONFIG = {
    environmentId: "YOUR_ENVIRONMENT_ID",       // e.g. "abc12345-..."
    agentIdentifier: "YOUR_AGENT_SCHEMA_NAME",  // schema name from Copilot Studio settings
    tenantId: "YOUR_TENANT_ID",
};

// ---------------------------------------------------------------------------
// UI helpers
// ---------------------------------------------------------------------------

function appendMessage(sender, text) {
    const messages = document.getElementById("messages");
    const div = document.createElement("div");
    div.style.cssText = `
        margin: 8px 0;
        padding: 8px 12px;
        border-radius: 8px;
        max-width: 85%;
        word-wrap: break-word;
        ${sender === "You"
            ? "background:#0078d4;color:#fff;margin-left:auto;"
            : "background:#f3f2f1;color:#323130;margin-right:auto;"}
    `;
    div.textContent = text;
    messages.appendChild(div);
    messages.scrollTop = messages.scrollHeight;
}

function setStatus(text, isError = false) {
    const el = document.getElementById("status");
    el.textContent = text;
    el.style.color = isError ? "#d13438" : "#605e5c";
}

function setInputEnabled(enabled) {
    document.getElementById("input").disabled = !enabled;
    document.getElementById("send").disabled = !enabled;
}

// ---------------------------------------------------------------------------
// Native bridge helpers
// ---------------------------------------------------------------------------

function notifyNativeTokenExpired() {
    if (window.NativeBridge && window.NativeBridge.onTokenExpired) {
        window.NativeBridge.onTokenExpired();
    }
}

function nativeLog(msg) {
    if (window.NativeBridge && window.NativeBridge.log) {
        window.NativeBridge.log(msg);
    }
}

// ---------------------------------------------------------------------------
// Conversation management
// ---------------------------------------------------------------------------

let conversation = null;

async function startConversation() {
    setStatus("Connecting to Copilot...");
    setInputEnabled(false);

    try {
        const client = new CopilotStudioClient({
            ...COPILOT_CONFIG,
            // getTokenAsync: called by the client on every request
            getTokenAsync: async () => {
                const token = window.__authToken;
                if (!token) {
                    nativeLog("getTokenAsync: token missing — notifying native");
                    notifyNativeTokenExpired();
                    // Return a rejected promise — the client will surface this as an error
                    throw new Error("Token not available");
                }
                return token;
            },
        });

        conversation = await client.createConversation();
        setStatus("Connected");
        setInputEnabled(true);
        nativeLog("Conversation started");

        // Greet — receive the welcome activity from the bot
        await receiveActivities();

    } catch (err) {
        nativeLog(`startConversation error: ${err.message}`);
        if (err.message?.includes("401") || err.message?.includes("token")) {
            setStatus("Session expired — refreshing...");
            notifyNativeTokenExpired();
        } else {
            setStatus(`Connection failed: ${err.message}`, true);
        }
    }
}

async function sendMessage(text) {
    if (!conversation) return;
    setInputEnabled(false);
    appendMessage("You", text);

    try {
        await conversation.sendMessage(text);
        await receiveActivities();
    } catch (err) {
        nativeLog(`sendMessage error: ${err.message}`);
        if (err.message?.includes("401")) {
            setStatus("Token expired — refreshing...");
            notifyNativeTokenExpired();
        } else {
            setStatus(`Send failed: ${err.message}`, true);
        }
    } finally {
        setInputEnabled(true);
    }
}

async function receiveActivities() {
    if (!conversation) return;
    for await (const activity of conversation.getActivities()) {
        if (activity.type === "message" && activity.text) {
            appendMessage("Copilot", activity.text);
        }
    }
}

// ---------------------------------------------------------------------------
// Event wiring
// ---------------------------------------------------------------------------

// authReady: fired by native after injecting window.__authToken
window.addEventListener("authReady", () => {
    nativeLog("authReady received — starting conversation");
    setStatus("Authenticating...");
    startConversation();
});

// authError: fired by native if auth fails entirely
window.addEventListener("authError", (e) => {
    setStatus(`Auth error: ${e.detail}`, true);
    setInputEnabled(false);
});

document.addEventListener("DOMContentLoaded", () => {
    document.getElementById("send").onclick = () => {
        const input = document.getElementById("input");
        const text = input.value.trim();
        if (!text) return;
        input.value = "";
        sendMessage(text);
    };

    document.getElementById("input").addEventListener("keydown", (e) => {
        if (e.key === "Enter") document.getElementById("send").click();
    });

    setStatus("Waiting for authentication...");
    setInputEnabled(false);

    // If the native app already injected the token before DOMContentLoaded fired
    if (window.__authToken) {
        window.dispatchEvent(new Event("authReady"));
    }
});
