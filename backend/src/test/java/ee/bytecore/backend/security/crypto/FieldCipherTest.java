package ee.bytecore.backend.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FieldCipherTest {
    private static final String K1 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String K2 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void roundTripsAndDoesNotLeakPlaintext() {
        FieldCipher cipher = FieldCipher.fromKeyRing("v1:" + K1, null);
        String sealed = cipher.encrypt("Tartu mnt 1");
        assertThat(sealed).startsWith("enc:v1:").doesNotContain("Tartu");
        assertThat(cipher.encrypt("Tartu mnt 1")).isNotEqualTo(sealed);
        assertThat(cipher.decrypt(sealed)).isEqualTo("Tartu mnt 1");
    }

    @Test
    void legacyPlaintextIsReadableAndFlaggedForBackfill() {
        FieldCipher cipher = FieldCipher.fromKeyRing("v1:" + K1, null);
        assertThat(cipher.decrypt("plain")).isEqualTo("plain");
        assertThat(cipher.needsReencryption("plain")).isTrue();
        assertThat(cipher.needsReencryption(cipher.encrypt("x"))).isFalse();
    }

    @Test
    void rotationDecryptsOldKeyAndEncryptsWithActiveKey() {
        String old = FieldCipher.fromKeyRing("v1:" + K1, null).encrypt("secret");
        FieldCipher rotated = FieldCipher.fromKeyRing("v1:" + K1 + ",v2:" + K2, "v2");
        assertThat(rotated.decrypt(old)).isEqualTo("secret");
        assertThat(rotated.needsReencryption(old)).isTrue();
        assertThat(rotated.encrypt("secret")).startsWith("enc:v2:");
    }

    @Test
    void tamperingAndMissingKeysFailClosed() {
        FieldCipher cipher = FieldCipher.fromKeyRing("v1:" + K1, null);
        String sealed = cipher.encrypt("secret");
        String tampered = sealed.substring(0, sealed.length() - 2) + (sealed.endsWith("A=") ? "B=" : "A=");
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FieldCipher.fromKeyRing("v2:" + K2, null).decrypt(sealed))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FieldCipher.fromKeyRing("", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FieldCipher.fromKeyRing("v1:AAAA", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void userEmailIndexIsVersionedNormalizedAndUsesADedicatedKey() {
        FieldCipher cipher = FieldCipher.fromKeyRing("v1:" + K1, null);
        String index = cipher.userEmailIndex("Ada@Example.test");
        assertThat(index).startsWith(FieldCipher.USER_EMAIL_INDEX_VERSION + ":").doesNotContain("ada", "example");
        assertThat(cipher.userEmailIndex("  ada@example.TEST ")).isEqualTo(index);
        assertThat(cipher.userEmailIndex("bob@example.test")).isNotEqualTo(index);
        assertThat(index).doesNotContain(cipher.blindIndex("ada@example.test"));
        assertThat(FieldCipher.fromKeyRing("v1:" + K2, null).userEmailIndex("ada@example.test"))
                .isNotEqualTo(index);
        assertThat(cipher.userEmailIndex(null)).isNull();
    }

    @Test
    void userEmailIndexSurvivesActiveKeyRotationWhileTheFirstKeyIsKept() {
        String before = FieldCipher.fromKeyRing("v1:" + K1, null).userEmailIndex("ada@example.test");
        FieldCipher rotated = FieldCipher.fromKeyRing("v1:" + K1 + ",v2:" + K2, "v2");
        assertThat(rotated.userEmailIndex("ada@example.test")).isEqualTo(before);
    }
}
