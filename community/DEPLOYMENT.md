# Isolated deployment and external prerequisites

This package has not been publicly deployed. It must remain on loopback until the following concrete prerequisites are complete. No existing business project, database, hostname, key, OAuth client or tunnel may be reused.

## Google sign-in

The signed-in Google Cloud browser was inspected and a new no-organisation project named **CragLight Community**, project ID **craglight-community**, was prepared. The Create action was rejected by automatic approval review because it creates a persistent account resource/quota relationship. No project was created; no alternative creation route was attempted. The parent task will present the exact action for approval after the local implementation is reviewable.

After that action is authorised:

1. Create the independent Google Cloud project without linking a billing account. Configure Google Auth Platform branding for CragLight with an actual monitored support contact and the published privacy/terms URLs. Do not claim the old product's identity or use its consent screen.
2. Create an OAuth **Web application** client to supply the backend audience/client ID. The native ID-token exchange implemented here does not require a client secret or an invented redirect URI. Configure only actual web origins if a web sign-in client is later added.
3. Create an OAuth **Android** client for `org.craglight.app`, using the SHA-1 fingerprint of CragLight's fresh release signing certificate. Obtain it from the signed APK with `apksigner verify --print-certs`; add a separate debug client only if needed. If Play App Signing is enabled later, configure its actual certificate too.
4. Put the real **web client ID** in the Android Credential Manager server-client-ID setting and `CRAGLIGHT_GOOGLE_CLIENT_ID`. Set a server-provided challenge nonce on the native Google request. Do not store the returned ID token beyond the exchange. Configure consent-screen audience/test users and production status truthfully; satisfy Google's branding/policy requirements rather than assuming unrestricted login works.
5. Verify a real sign-in end to end on the signed app, wrong audience/nonce rejection, logout/revocation, Google-photo opt-in, export and deletion against the actual HTTPS deployment. Local mocked authentication tests are not evidence that this external configuration is working.

Google documents [Android Credential Manager sign-in](https://developer.android.com/identity/sign-in/credential-manager-siwg), [server ID-token verification](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token), and [OAuth production policy requirements](https://developers.google.com/identity/protocols/oauth2/production-readiness/policy-compliance).

## Durable zero-new-service-cost hosting

The existing always-on Windows machine can run this isolated process and persistent `.state` database without buying a compute service. That is a deployment option, not a completed production host: it still requires a deliberately approved dedicated process identity/startup task, backup/restore policy, monitoring, security updates, power/connectivity and a stable HTTPS ingress. No scheduled task or Windows service has been installed by this work.

A dedicated named Cloudflare Tunnel can connect only to `http://127.0.0.1:8765`. Its public application route requires a domain/hostname under the account's control, and a new CragLight tunnel, isolated credentials and route. A non-business CragLight hostname has not been supplied or verified. The official Wrangler identity check returned **not authenticated**. No existing domain/zone, account token, cloudflared service or tunnel was borrowed. Do not change an existing cloudflared Windows service: use an independent executable/config/process for CragLight after approval.

Cloudflare's [named-tunnel instructions](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/) describe the hostname route. Its [Quick Tunnels documentation](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) labels that facility for testing/development; a random temporary address is not a durable launch solution. A new domain purchase is not authorised, and a business subdomain violates this task's separation rule. A supplied independent hostname plus verified free account setup is therefore a real prerequisite, not a fabricated URL.

Cloudflare Workers/D1/KV have [free quotas](https://developers.cloudflare.com/workers/platform/pricing/), [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/) and [KV limits](https://developers.cloudflare.com/kv/platform/limits/). They are not a drop-in host for this Python image-decoding service. R2 has usage-based [pricing](https://developers.cloudflare.com/r2/pricing/); no paid plan, billing enrollment or unbounded storage was enabled. Free-tier labels alone do not establish this account's zero-cost eligibility. No static GitHub Pages endpoint is presented as a writable API.

For the reviewed loopback tunnel/proxy deployment, use `start-private.ps1 -TrustLoopbackProxy` only after confirming the dedicated proxy overwrites/sets the real client forwarding header. This enables Uvicorn proxy handling for exactly `127.0.0.1`, never `*` or arbitrary Internet peers. Keep the origin bound to loopback. Without that setting, anonymous limits conservatively group all requests arriving from the local proxy; that is unsuitable for launch. Verify two distinct external clients receive independent limits and that a spoofed forwarded header from an untrusted peer is ignored. The service hashes the effective address into a short-lived action/window bucket; it does not persist the raw address in that table. Host/proxy logging is a separate policy decision.

## Launch evidence still required

Before public use: approve the specific isolated account resources; verify the real HTTPS hostname/certificate and login clients; install/start the dedicated process and daily retention job; restore-test protected backups; assign a moderator and arrange independent review for any moderator-authored submissions; publish a private contact route and final controller/privacy details; complete the relevant UK privacy and online-safety assessments; and test actual signed-app upload, review, shared-photo amendment, reporting and deletion. See [UGC_POLICY.md](UGC_POLICY.md). This list records remaining work; the implementation and local checks are complete independently of those external actions.
