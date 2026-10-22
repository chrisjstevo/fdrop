import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, del, signInWithTwoFactor, startAccountAction } from "../api/client";
import type { Me, SecondFactor } from "../api/types";
import { formatDate } from "../util/format";
import { Badge, Button, Dialog, DialogActions, ErrorNote, Notice, RowMenu } from "./ui";
import s from "../pages/pages.module.css";

/**
 * An API error, or, when the server wants a two-factor sign-in first, a notice with a button that signs in
 * again with the second factor and comes back here.
 */
export function ProblemNote({ error }: { error: Error | null | undefined }) {
  if (error instanceof ApiError && error.needsTwoFactor) {
    return (
      <Notice>
        <p>{error.message}. You signed in with your password only.</p>
        <Button variant="primary" onClick={signInWithTwoFactor}>
          Sign in with your second factor
        </Button>
      </Notice>
    );
  }
  return <ErrorNote>{error?.message}</ErrorNote>;
}

const KIND_NAMES: Record<SecondFactor["kind"], string> = {
  otp: "Authenticator app",
  "security-key": "Security key or passkey",
  "recovery-codes": "Recovery codes",
};

/** The Account page's two-factor card: the user's factors, and buttons to add or remove them. */
export function TwoFactorSection({ me }: { me: Me }) {
  const client = useQueryClient();
  const [removing, setRemoving] = useState<SecondFactor>();
  const t = me.twoFactor;
  const factors = t.factors ?? [];
  const real = factors.filter((f) => f.kind !== "recovery-codes");
  const hasOtp = factors.some((f) => f.kind === "otp");
  const hasCodes = factors.some((f) => f.kind === "recovery-codes");
  const remove = useMutation({
    mutationFn: (id: string) => del(`/api/v1/me/two-factor/${encodeURIComponent(id)}`),
    onSuccess: () => {
      setRemoving(undefined);
      void client.invalidateQueries({ queryKey: ["me"] });
    },
  });

  let summary: string;
  if (real.length > 0) {
    summary = "Two-factor sign-in is on: after your password, Keycloak asks for one of these.";
  } else if (t.required) {
    summary = "Your account must use two-factor sign-in. Set up an authenticator app or a security key.";
  } else {
    summary = "Two-factor sign-in is off. Add an authenticator app or a security key to turn it on.";
  }

  return (
    <section className={s.section} aria-labelledby="two-factor-heading">
      <h2 id="two-factor-heading">Two-factor sign-in</h2>
      <p>{summary}</p>
      {me.roles.standard && real.length > 0 && (
        <p className="muted">
          Over SFTP, log in with an SSH key
          {t.sftpCodes && hasOtp ? ", or with your password and a code from your authenticator app" : ""}.
        </p>
      )}
      {t.factors === null && <ErrorNote>Your second factors could not be loaded from Keycloak. Try again later.</ErrorNote>}
      {factors.length > 0 && (
        <ul className={s.cards} style={{ display: "block" }}>
          {factors.map((f) => (
            <li key={f.id} className={s.card}>
              <div className={s.cardBody}>
                <div className={s.cardTitle}>
                  {KIND_NAMES[f.kind]}
                  {f.label && f.label.toLowerCase() !== KIND_NAMES[f.kind].toLowerCase() && f.label !== f.kind && (
                    <span className="muted"> · {f.label}</span>
                  )}
                </div>
                <div className={s.cardMeta}>
                  {f.createdAt && <span>Added {formatDate(f.createdAt)}</span>}
                  {f.remaining !== undefined && f.remaining !== null && (
                    <Badge kind={f.remaining > 2 ? "ok" : "warn"}>{f.remaining} left</Badge>
                  )}
                </div>
              </div>
              <RowMenu
                label={`Actions for ${KIND_NAMES[f.kind]}`}
                actions={[{ label: "Remove", danger: true, onSelect: () => setRemoving(f) }]}
              />
            </li>
          ))}
        </ul>
      )}
      <div className={s.actions} style={{ flexWrap: "wrap" }}>
        {!hasOtp && (
          <Button variant={real.length === 0 ? "primary" : "secondary"} onClick={() => startAccountAction("configure-totp")}>
            Set up authenticator app
          </Button>
        )}
        <Button onClick={() => startAccountAction("register-security-key")}>Add security key or passkey</Button>
        {real.length > 0 && (
          <Button onClick={() => startAccountAction("recovery-codes")}>
            {hasCodes ? "New recovery codes" : "Generate recovery codes"}
          </Button>
        )}
      </div>
      {removing && (
        <Dialog
          open
          onOpenChange={(o) => !o && setRemoving(undefined)}
          title={`Remove ${KIND_NAMES[removing.kind].toLowerCase()}?`}
          description={
            removing.kind === "recovery-codes"
              ? "Your unused recovery codes stop working."
              : "You can no longer use it to sign in."
          }
        >
          {!t.thisSession ? (
            <ProblemNote error={new ApiError(403, "Removing a factor needs a two-factor sign-in", "two-factor-required")} />
          ) : (
            <ProblemNote error={remove.error} />
          )}
          <DialogActions>
            <Button onClick={() => setRemoving(undefined)}>Cancel</Button>
            <Button
              variant="danger"
              disabled={!t.thisSession || remove.isPending}
              onClick={() => remove.mutate(removing.id)}
            >
              Remove
            </Button>
          </DialogActions>
        </Dialog>
      )}
    </section>
  );
}
