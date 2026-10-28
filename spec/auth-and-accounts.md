# Authentication, users and application accounts

[fdrop specification](../SPEC.md)

## Authentication and authorisation

One `AuthService` serves both protocols and delegates identity to Keycloak over OpenID Connect, so a user has the same identity, roles, home and quota over HTTP and SFTP. fdrop stores no passwords.

- **Identity provider:** a Keycloak 26 realm named `fdrop`. Users, passwords, password policy, brute-force detection, second factors and any federation (LDAP, social login) are configured in Keycloak, not in fdrop. fdrop decides who must use two-factor authentication and checks that they did (see Two-factor authentication).
- **Roles:** three realm roles, `fdrop-user` (standard), `fdrop-admin` (admin) and `fdrop-app` (application account), read from the token's `realm_access.roles`. `fdrop-app` is granted through the `fdrop-applications` group rather than assigned directly. What each allows is defined in Users and roles and Application accounts below.
- **User id:** the token's `sub` is the stable user id; `preferred_username` is for display and for picking folder members. On first login fdrop creates a `user` row (sub, username, quota) in the metadata DB for quota, SSH keys and folder membership. Usernames are unique in Keycloak, so when a new `sub` signs in with a username an older row still holds (an account deleted and recreated in Keycloak, or a dev realm imported again with new ids), the older row is renamed `<username>~<first 8 characters of its sub>`, so lookups by username find the current account.
- **Library:** Nimbus OAuth 2.0 SDK with OpenID Connect plus `nimbus-jose-jwt`. No Keycloak-specific adapter (those are deprecated), so fdrop stays a plain OIDC client.

### Production Keycloak

Production uses an existing Keycloak server; fdrop does not ship, deploy or operate Keycloak. The bundled Keycloak exists only in the dev container, tests and the local demo compose file.

- The Keycloak administrators create the `fdrop-web`, `fdrop-sftp` and `fdrop-api` clients, the `fdrop-user`, `fdrop-admin` and `fdrop-app` roles, and the `fdrop-applications` group with `fdrop-app` as its role mapping, in a realm of their choice. The dev `fdrop-realm.json` serves as the reference for client settings, minus its users and secrets.
- Admin user management needs the `fdrop-sftp` service account to hold `manage-users` and `query-groups` in that realm (the latter to find `fdrop-applications` and add application accounts to it). Where users come from a read-only source (LDAP federation, another team's policy), set `auth.userManagement.enabled: false`; the admin screens then show users read-only and creation and deletion happen in Keycloak.
- Realm and role names are not hard-coded: fdrop reads the issuer URL, client ids and role names from config (`auth.oidc.*`), so it can join an existing realm whose roles are named differently.
- For two-factor authentication the realm needs: a browser flow with level of authentication 2 (OTP form and WebAuthn as alternatives), the `fdrop-web` ACR mapping `2fa` → 2, Keycloak's conditional OTP step in the direct grant flow used by `fdrop-sftp` (the built-in `direct grant` flow has it), and an OTP policy of TOTP, 6 digits, 30 s. Recovery authentication codes are optional. The dev realm is the reference.
- Client secrets come from environment variables or a mounted secret file, never from `fdrop.yaml` in the image.
- Production redirect and back-channel logout URLs use the public fdrop hostname over HTTPS, and the issuer must be the Keycloak URL as browsers see it.
- fdrop needs outbound HTTPS to Keycloak for discovery, JWKS, token exchange, password grants (SFTP) and the Admin REST API; `/readyz` reports Keycloak unreachable as degraded, not down, so logged-in sessions keep working.

### Keycloak clients

| Client | Type | Used for |
| --- | --- | --- |
| `fdrop-web` | Confidential, standard flow (authorization code + PKCE S256) | Browser login through the fdrop server |
| `fdrop-sftp` | Confidential, direct access grants + service account (`view-users`, `manage-users`, `query-users`, `query-groups`) | Checking SFTP and application passwords and user status; creating and deleting users and application accounts for admins |
| `fdrop-api` | Audience only (no login); scripts use their own client-credentials clients with an `fdrop-api` audience mapper | Scripts and automation |

### Browser login (backend-for-frontend)

1. The React app calls `GET /api/v1/me`; a `401` makes it navigate to `GET /auth/login`.
2. fdrop redirects to Keycloak's authorize endpoint with `state`, `nonce` and a PKCE challenge.
3. Keycloak redirects back to `/auth/callback`. fdrop exchanges the code with its client secret, validates the ID token, stores the access and refresh tokens in a server-side session, and sets an opaque session cookie (`HttpOnly`, `Secure`, `SameSite=Lax` so the redirect back carries it).
4. Tokens never reach the browser. fdrop refreshes the access token before expiry; a failed refresh ends the session.
5. `POST /auth/logout` clears the session and redirects to Keycloak's end-session endpoint. Keycloak calls `POST /auth/backchannel-logout` to end fdrop sessions when a user is logged out or disabled centrally.

### API access

Scripts send `Authorization: Bearer <JWT>`. fdrop validates the signature against the realm's JWKS (cached, refetched on an unknown `kid`), plus `iss`, `exp`, and that `aud` contains `fdrop-api`. No introspection call per request.

### SFTP

- **Password:** MINA's `PasswordAuthenticator` performs a password grant against `fdrop-sftp`; a token holding `fdrop-user`, or `fdrop-app` for an application account allowed to use a password, means success. Results are not cached, so Keycloak's brute-force lockout applies. Users who need two factors log in with `keyboard-interactive` (password and code) or with keys; see Two-factor authentication.
- **Public key:** keys are stored in fdrop's metadata DB, not in Keycloak (decided for v1), keyed by `sub` and managed in the web UI after login. On key login fdrop checks via the Keycloak Admin REST API (the `fdrop-sftp` service account) that the user is still enabled and holds `fdrop-user` (or `fdrop-app` for an application account); the answer is cached for 5 min. Application accounts' keys are managed by admins, since they have no web UI.

### Two-factor authentication

Keycloak asks for and checks the second factor; fdrop decides who must have one and refuses any session where Keycloak does not confirm it. fdrop never sees or stores a factor's secret.

- **Factors.** Time-based one-time codes from an authenticator app (TOTP, RFC 6238: 6 digits, 30 s period, SHA-1 so every common app works), and WebAuthn security keys or passkeys. When the realm enables Keycloak's recovery authentication codes, a user can print a set of one-use codes for a lost device. SMS and email codes are not supported.
- **Who needs it.** `auth.twoFactor.require` is `none`, `admins` (the default) or `all`. With `admins`, every holder of `fdrop-admin` must sign in with two factors, and standard users may turn it on for themselves from the Account page. With `all`, every user must. A user who has set up a factor is always asked for it, whatever the policy. Application accounts are never covered and never asked for a second factor, whatever the policy; see Application accounts.
- **How fdrop knows.** The realm's browser flow uses Keycloak's levels of authentication: level 1 is the password, level 2 is the password plus a code or security key. The `fdrop-web` client maps the ACR value `2fa` (`auth.twoFactor.acr`) to level 2, so Keycloak puts `acr: "2fa"` in the ID token only when a second factor was used.
- **Step-up at login.** After the callback fdrop works out whether this user needs two factors: the policy and the user's roles, or a factor the user has set up (read through the Admin REST API and cached with the user's status for 5 min). If they do and the ID token's `acr` is not `2fa`, fdrop sends the browser straight back to Keycloak with `acr_values=2fa`. Keycloak's SSO session means only the second factor is asked for. A user who must have two factors but has none set up is first sent to Keycloak's `CONFIGURE_TOTP` action to set up an authenticator app, then back through the step-up, before any fdrop session exists. Cancelling the setup ends on a "Sign-in refused" page.
- **Refusal.** If the token still lacks `acr: "2fa"` after the step-up, for example because a production realm is missing the level 2 flow, fdrop creates no session and redirects to `/sign-in-problem`, a page outside the signed-in app that says "Two-factor sign-in is required, but Keycloak did not confirm it. Ask your administrator." The refusal is audited and counted in `fdrop_two_factor_total{result="refused"}`. This check is fdrop's own, so a misconfigured realm cannot quietly turn two-factor off.
- **The session remembers.** The server-side session records that it was signed in with two factors. `GET /api/v1/me` reports `twoFactor: { required, thisSession, otp, securityKeys, recoveryCodesLeft }` so the UI can show the status.
- **Account page.** A Two-factor card lists the user's factors with when each was added, and offers "Set up authenticator app", "Add security key or passkey" and "Generate recovery codes". Each button goes through `GET /auth/login?action=…`, which starts a Keycloak application-initiated action (`kc_action=CONFIGURE_TOTP`, `webauthn-register`, `CONFIGURE_RECOVERY_AUTHN_CODES`) and returns to the Account page. Removing a factor goes through fdrop (`DELETE /api/v1/me/two-factor/{credentialId}`, which calls the Admin REST API) and needs a two-factor session; fdrop refuses to remove the last factor of a user who must have one.
- **Admin reset.** An admin can choose "Reset two-factor" for a user who has lost their device. fdrop deletes that user's OTP, WebAuthn and recovery-code credentials through the Admin REST API, then ends their browser and SFTP sessions. If the user must have two factors, they enrol again at their next sign-in. Admins cannot reset their own factors, and every reset is audited with the acting admin.
- **Admin actions need it.** Under `admins` or `all`, the admin API refuses any session that did not sign in with two factors (`403`, problem type `two-factor-required`), even if Keycloak's own policy changed after the session started.
- **SFTP with keys.** An SSH key is something the user has, so key login counts as two-factor and needs no code. Users who must have two factors can only add keys from a two-factor browser session.
- **SFTP with a password.** For users who need two factors, fdrop turns off the plain `password` method and offers `keyboard-interactive` instead: it prompts `Password:` and then `Verification code:` and sends both to Keycloak's direct grant (`password` plus the `totp` parameter). The realm's direct grant flow must include Keycloak's conditional OTP step so the code is actually checked; Keycloak's built-in `direct grant` flow does. Security keys and recovery codes cannot be used over SFTP, so users with only a security key use SSH keys. `auth.twoFactor.sftpPasswordWithCode: false` turns this off and leaves keys as the only SFTP login for those users.
- **Scripts.** Bearer tokens from client-credentials clients belong to service accounts, not people, and are not affected. Systems that need their own files, quota and folder memberships use application accounts instead. A bearer token issued to a person who needs two factors must carry `acr: "2fa"`.
- **Brute force.** Keycloak's brute-force detection counts wrong codes the same as wrong passwords, over the browser and SFTP. fdrop does not retry a failed code.

## Users and roles

fdrop has three roles: **standard** (`fdrop-user`) works with files and creates folders; **admin** (`fdrop-admin`) creates and deletes users and application accounts; **application** (`fdrop-app`, through the `fdrop-applications` group) is for other systems, not people. Standard and admin are additive, so an admin who also uploads files holds both; the dev `admin` user does. Application never combines with the other two.

| Action | Standard | Admin only | Application |
| --- | --- | --- | --- |
| Log in over HTTP and SFTP | Yes | Needs `fdrop-user` too | HTTP API (mTLS or Basic) and SFTP; not the web UI |
| Second factor required | Per `auth.twoFactor.require` | Per `auth.twoFactor.require` | Never |
| Upload, download, rename, delete in own private area (`/home`) | Yes | — | Yes |
| Create ordinary sub-folders in `/home` and inside shared folders | Yes | — | Yes, with write permission |
| Be added as a member of shared and forward folders | Yes | — | Yes |
| Create shared folders, manage their members | Yes (owner) | — | No |
| Create forward folders sending to one or more assigned destinations | Yes (owner) | — | No |
| Set, change and clear file expiries where they can write | Yes | — | Yes |
| Search names and contents of files they can open | Yes | — (no wider reach) | Yes, through the API |
| Set own home default expiry; a shared folder's default and maximum expiry | Yes (home; folders they own) | — | No (admin sets its home default) |
| Manage own SSH keys | Yes | — | No (admin manages them) |
| Set up and remove own second factors | Yes | — | No |
| List users, create users, delete users | No | Yes | No |
| Create and manage application accounts and their credentials | No | Yes | No |
| Reset another user's two-factor | No | Yes | No |
| Read, search and export the audit log | No | Yes (two-factor session) | No |
| Define forward destinations (server, directory, keys) and assign them to users | No | Yes | No |

The admin role grants no access to other users' files: an admin sees only their own home and the folders they own or belong to.

### Creating a user

- Admin screen form: username, email, first and last name, role (standard, or standard + admin). Application accounts are created on their own screen; see Application accounts.
- fdrop calls the Keycloak Admin REST API: create the user, assign the realm roles, and set a temporary password shown once to the admin (Keycloak forces a change at first login). When the realm has SMTP configured, fdrop instead triggers Keycloak's execute-actions email (`UPDATE_PASSWORD`) and no password is shown.
- Usernames follow Keycloak's rules; a duplicate returns `409`.
- If the two-factor policy covers the new user, Keycloak takes them through setting up a factor at their first sign-in.
- The new user's fdrop row and empty `/home` are created at their first login, so users created directly in Keycloak work the same way.

### Deleting a user

- The admin confirms by typing the username. An admin cannot delete themselves, and fdrop refuses to delete the last user holding `fdrop-admin`.
- In order: Keycloak logout of all the user's sessions (which also ends fdrop sessions through back-channel logout), drop live SFTP sessions, delete the Keycloak user, then in fdrop delete their SSH keys, folder memberships, destination assignments, open upload sessions and `/home` contents.
- Folders the user owned are not deleted: ownership passes to the admin who performed the delete, who can then reassign or delete them. Pending forward jobs in those folders keep running.
- Every create and delete is written to the audit log with the acting admin.

## Application accounts

An **application account** is a non-person account for another system (a batch job, an ERP export, a partner integration) that sends or collects files without anyone signing in. Admins create them; they authenticate with a **client certificate (mTLS)** or a **username and password**, never need a second factor, and are added to shared and forward folders exactly like standard users.

### Identity and Keycloak group

- An application account is a Keycloak user in the realm group **`fdrop-applications`** (`auth.applications.group`). The group carries the realm role **`fdrop-app`** (`auth.oidc.appRole`), so fdrop reads it from `realm_access.roles` like the other two roles, and Keycloak administrators manage application accounts by group membership.
- `fdrop-app` cannot be combined with `fdrop-user` or `fdrop-admin`. fdrop refuses to sign in an account that holds `fdrop-app` together with either, and audits the refusal, so a person's account cannot be turned into an unattended one by accident or the reverse.
- The account's `sub` is its fdrop id, as for people. Its fdrop `user` row has `kind = APPLICATION` and is created by fdrop when an admin creates the account in fdrop, or at the first password login of an account added to the group directly in Keycloak.
- Each account has a name (the Keycloak username, e.g. `erp-export`), a description, and a **responsible person**: a standard user who is named in the warnings fdrop raises when the account's password or certificates are about to expire, and who is shown on the admin screen.

### Authentication

Application accounts use the HTTP API and SFTP. They cannot use the browser UI: fdrop ends `/auth/callback` for an `fdrop-app` token on the "Sign-in refused" page with "Application accounts cannot sign in to the web app", and creates no session.

| Protocol | mTLS (certificate) | Username and password |
| --- | --- | --- |
| HTTP API | TLS client certificate on the mTLS listener (`http.mtls.port`, default 8444) | HTTP Basic over TLS on the normal HTTP port |
| SFTP | Not applicable: SSH uses public keys, the SSH equivalent of mTLS (below) | SSH `password` method |

**mTLS over HTTP.**

- A separate listener on `http.mtls.port` requires a client certificate (`ClientAuth.REQUIRE`) and serves only `/api/v1`. Browsers never meet a certificate prompt on the main port, and the mTLS port can sit behind a TCP load balancer with TLS passthrough. The handshake sends no list of acceptable CAs, since some clients (Java's among them) then refuse to offer a pinned self-signed certificate; the decision is made per request instead.
- The certificate must chain to a CA in `auth.applications.mtls.trustedCas` (PEM bundle, hot-reloaded) and be within its validity period. Revocation is checked against CRLs from `auth.applications.mtls.crls` when set; OCSP is not used.
- The certificate is matched to an account in one of two ways, chosen per certificate: **pinned**, where the certificate's SHA-256 fingerprint is registered on the account (a self-signed certificate is allowed in this mode and needs no trusted CA), or **by subject**, where a SAN (DNS, URI or email) or the full subject DN registered on the account must match, so a CA can reissue the certificate without an admin change. Subject DNs are compared in canonical form with their parts in any order, so `CN=erp,O=Example` as Java prints it, `O=Example, CN=erp` and OpenSSL's `/CN=erp/O=Example` are the same subject.
- A certificate that matches no account, or matches a disabled one, gets `401` with no hint which check failed.
- Behind a TLS-terminating proxy, fdrop can instead read the client certificate from a header (`auth.applications.mtls.proxyHeader`, e.g. `X-Forwarded-Client-Cert`), believed only from `http.trustedProxies` and ignored from anyone else.
- An account may hold several certificates at once, so a new one can be added before the old one expires and then retired, with no downtime.

**Username and password.**

- HTTP Basic is accepted only for application accounts; a standard user's Basic credentials are refused with `401`, since people use the browser or bearer tokens. Every fdrop listener is HTTPS (see Security), so a password never crosses the network in clear on its way to fdrop.
- fdrop checks the password with a direct grant against `fdrop-sftp`, the same as SFTP passwords. To avoid a Keycloak call per chunk of a large upload, a successful check is cached for 5 minutes under an HMAC of username and password with a per-process random key; failures are never cached, so Keycloak's brute-force lockout still applies.
- Over SFTP, application accounts use the plain `password` method; `keyboard-interactive` is never offered to them.
- Passwords are generated by fdrop (32 random characters) and shown once to the admin; admins cannot type one in. A password expires after `auth.applications.passwordMaxAge` (default 365 days, `0` = never); expiry is enforced by fdrop, since Keycloak's password policy applies to people too.

**SSH keys.** Over SFTP an account may also log in with public keys or OpenSSH user certificates registered by an admin, checked the same way as for standard users. This is the SFTP counterpart of mTLS.

Each account has its own set of **allowed methods** (any of mTLS, password, SSH key); a method not enabled for the account is refused even with valid credentials. On every certificate or key login fdrop checks through the Admin REST API that the Keycloak user is enabled, still in `fdrop-applications` and holds `fdrop-app`, cached for 5 minutes as for standard users.

**Source addresses.** An account can carry an optional list of allowed client addresses (CIDRs). When set, logins from anywhere else are refused for every method, using the real client IP (`X-Forwarded-For` or PROXY protocol from trusted proxies).

### No two-factor authentication

- Application accounts are outside `auth.twoFactor.require`, whatever its value, and fdrop never steps them up or asks for a code.
- They cannot set up second factors, since they have no Account page, and fdrop removes any OTP or WebAuthn credential it finds on an `fdrop-app` user (audited), so Keycloak's conditional OTP step in the direct grant never applies to them.
- What replaces the second factor: the strongest method allowed for each account, generated passwords, optional source address limits, expiry of passwords and certificates, and an audit trail.

### Folders and files

- Each application account has its own `/home` and quota (`limits.defaultAppQuota`, default 10 GiB), with the same virtual tree over HTTP and SFTP as a standard user.
- Owners add application accounts to **shared and forward folders** as members, read-only or read-write, exactly as they add people. `GET /api/v1/users?search=` returns both kinds, each marked `kind: user | application` and shown with an "App" badge in the member picker.
- Application accounts are members only (decided): they cannot create shared or forward folders, folder ownership cannot be transferred to them, and they are never assigned forward destinations, so every managed folder has a person accountable for it.
- As members they upload, download, create sub-folders, rename and delete with the permission they were given. Removing an application account from a folder takes effect on its next request, including open SFTP sessions.
- Files an application account writes into a forward folder are forwarded like anyone else's, and appear in its My uploads records (`GET /api/v1/me/uploads`).

### Managing application accounts

An **Application accounts** admin screen, separate from Users, lists each account with its responsible person, allowed methods, last login, and the nearest password or certificate expiry.

- **Create:** name, description, responsible person, allowed methods and optional source addresses. fdrop creates the Keycloak user with no required actions, a placeholder first and last name and a placeholder address that can never receive mail (`<name>@applications.invalid`, marked verified), since Keycloak's default user profile otherwise marks the user "not fully set up" and refuses its password grants; it then adds it to `fdrop-applications`, creates the fdrop row and `/home`, and, if password login is allowed, generates and shows the password once.
- **Credentials:** regenerate the password (the old one stops working at once); add a certificate by uploading the PEM (pinned) or entering the expected subject or SAN (by subject); add SSH public keys; retire any of them. Expiry dates are shown, and 30 and 7 days (`auth.applications.expiryWarnings`) before a password or certificate expires fdrop logs a warning and writes an `app.credential.expiring` audit entry naming the account and its responsible person, `fdrop_app_credentials_expiring` counts it, and the `FdropAppCredentialExpiring` alert fires. fdrop sends no email itself; route the alert to the responsible people.
- **Disable / enable:** disabling sets the Keycloak user disabled and drops the account's live SFTP sessions, keeping its files, memberships and credentials.
- **Delete:** as for users (typed confirmation), removing the Keycloak user, credentials, memberships, open upload sessions and `/home` contents. It owns no folders, so nothing passes to the admin.
- Accounts can also be created in Keycloak by adding a user to `fdrop-applications`, which is the only way when `auth.userManagement.enabled` is `false`. Such an account appears on the admin screen after its first password login, with all methods allowed and no source limits; until an admin opens it there it has no certificates or SSH keys, so password is the only method that can succeed. Admins manage certificates, SSH keys, allowed methods and source addresses in fdrop either way, since these hold no Keycloak data.
- Every change is audited with the acting admin. Application logins are audited with the account, method (`mtls`, `password`, `publickey`), certificate or key fingerprint, client IP and client (`User-Agent`, or the SSH client version). An HTTP client authenticates on every request, chunks included, so the checks run every time but the login is recorded (audit line, last sign-in, `fdrop_app_auth_total`) once per five minutes for the same account, method, address and credential. Every refusal is recorded.

### Metadata

- `user(sub, username, kind USER|APPLICATION, quota, home_default_expiry, …)`
- `app_account(sub, description, responsible_sub, allowed_methods, allowed_sources, password_set_at, created_by, created_at, last_login_at)`
- `app_client_cert(id, sub, match PINNED|SUBJECT, sha256, subject, san, not_after, state ACTIVE|RETIRED, created_at)`
- Application SSH keys use the existing SSH key table, keyed by `sub`.
