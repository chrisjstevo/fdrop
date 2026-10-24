import { useSearchParams } from "react-router";
import { Button, ErrorNote, Logo } from "../components/ui";

/**
 * Where the server sends a sign-in it refused. It lives outside the signed-in app, since there is no session
 * and loading the app would start another sign-in straight away.
 */
export function SignInProblemPage() {
  const [params] = useSearchParams();
  const reason = params.get("reason");
  const twoFactor = reason === "two-factor";
  return (
    <main style={{ maxWidth: 480, margin: "15vh auto 0", padding: "0 16px", display: "grid", gap: 16 }}>
      <Logo size={48} />
      <h1>Sign-in refused</h1>
      <ErrorNote>
        {twoFactor
          ? "Two-factor sign-in is required, but Keycloak did not confirm it. Ask your administrator."
          : reason === "two-factor-setup"
            ? "Your account must use two-factor sign-in, and no second factor was set up."
            : reason === "application"
              ? "Application accounts cannot sign in to the web app."
              : reason === "mixed-roles"
                ? "This account is both an application account and a person's account, which fdrop does not allow. Ask your administrator."
                : "Sign-in could not be completed."}
      </ErrorNote>
      {twoFactor && (
        <p className="muted">
          Your account must sign in with a second factor, such as a code from an authenticator app, but the sign-in
          service did not ask for one. This usually means its two-factor settings need fixing.
        </p>
      )}
      {reason === "application" && (
        <p className="muted">
          Application accounts sign in to the API with a client certificate or their password, or to SFTP. Sign in with
          your own account to use the web app.
        </p>
      )}
      {reason === "two-factor-setup" && (
        <p className="muted">Sign in again and follow the steps to set up an authenticator app.</p>
      )}
      <div>
        <Button onClick={() => window.location.assign("/auth/login?returnTo=%2F")}>Try again</Button>
      </div>
    </main>
  );
}
