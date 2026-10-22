package io.fdrop.core.auth;

import io.fdrop.core.FakeDirectory;
import io.fdrop.core.TestSupport;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwoFactorServiceTest {

    private static TwoFactorService service(String require, FakeDirectory dir) {
        FdropConfig.TwoFactor cfg = new FdropConfig.TwoFactor();
        cfg.require = require;
        return new TwoFactorService(cfg, dir);
    }

    @Test
    void policyDecidesWhoMustUseTwoFactor() {
        Principal alice = TestSupport.user("alice");
        Principal root = TestSupport.admin("root");
        Principal adminOnly = new Principal("sub-x", "x", null, false, true);

        TwoFactorService none = service("none", new FakeDirectory());
        assertThat(none.policyRequires(root)).isFalse();

        TwoFactorService admins = service("admins", new FakeDirectory());
        assertThat(admins.policyRequires(root)).isTrue();
        assertThat(admins.policyRequires(adminOnly)).isTrue();
        assertThat(admins.policyRequires(alice)).isFalse();

        TwoFactorService all = service("ALL", new FakeDirectory());
        assertThat(all.policyRequires(alice)).isTrue();

        assertThatThrownBy(() -> service("sometimes", new FakeDirectory()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("none, admins or all");
    }

    @Test
    void aUserWhoSetUpAFactorIsAlwaysAskedForIt() {
        FakeDirectory dir = new FakeDirectory();
        TwoFactorService tf = service("none", dir);
        Principal alice = TestSupport.user("alice");
        assertThat(tf.needed(alice)).isFalse();

        dir.withOtp("alice");
        tf.invalidate(alice.sub());
        assertThat(tf.needed(alice)).isTrue();
        assertThat(tf.hasOtp(alice.sub())).isTrue();
        assertThat(tf.factors(alice.sub())).extracting(TwoFactorService.Factor::kind).containsExactly("otp");
    }

    @Test
    void removingAFactorNeedsATwoFactorSessionAndKeepsTheLastRequiredOne() {
        FakeDirectory dir = new FakeDirectory().withOtp("root").withSecurityKey("root").withOtp("alice");
        TwoFactorService tf = service("admins", dir);
        Principal root = TestSupport.admin("root");

        assertThatThrownBy(() -> tf.removeOwn(root, false, "otp-1"))
                .isInstanceOfSatisfying(FdropException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.type()).isEqualTo(TwoFactorService.PROBLEM_TYPE);
                });

        tf.removeOwn(root, true, "otp-1");
        assertThat(tf.factors(root.sub())).extracting(TwoFactorService.Factor::kind).containsExactly("security-key");
        assertThatThrownBy(() -> tf.removeOwn(root, true, "webauthn-2"))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(409));

        // a standard user under "admins" turned it on themselves and may turn it off again
        Principal alice = TestSupport.user("alice");
        tf.removeOwn(alice, true, "otp-1");
        assertThat(tf.needed(alice)).isFalse();
        assertThatThrownBy(() -> tf.removeOwn(alice, true, "otp-1"))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void resetRemovesEveryFactor() {
        FakeDirectory dir = new FakeDirectory().withOtp("bob").withSecurityKey("bob");
        TwoFactorService tf = service("all", dir);
        assertThat(tf.removeAll("sub-bob")).isEqualTo(2);
        assertThat(tf.hasFactor("sub-bob")).isFalse();
        assertThat(tf.needed(TestSupport.user("bob"))).isTrue();
    }

    @Test
    void readsKeycloakCredentialTypesAndRecoveryCodesLeft() {
        assertThat(TwoFactorService.kind("otp")).isEqualTo("otp");
        assertThat(TwoFactorService.kind("webauthn-passwordless")).isEqualTo("security-key");
        assertThat(TwoFactorService.kind("recovery-authn-codes")).isEqualTo("recovery-codes");
        assertThat(TwoFactorService.kind("password")).isNull();
        assertThat(TwoFactorService.remainingCodes("{\"hashIterations\":1,\"totalCodes\":12,\"remainingCodes\":7}"))
                .isEqualTo(7);
        assertThat(TwoFactorService.remainingCodes("not json")).isNull();
        assertThat(new TwoFactorService(new FdropConfig.TwoFactor(), new FakeDirectory()).isTwoFactorAcr("2fa"))
                .isTrue();
    }
}
