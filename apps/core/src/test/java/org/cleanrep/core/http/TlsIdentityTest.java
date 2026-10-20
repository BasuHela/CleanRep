package org.cleanrep.core.http;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import org.cleanrep.core.testing.Check;
import org.cleanrep.phone.PhonePolicy;
import org.cleanrep.phone.PinnedTls;

/** C-TLS-1..10: the TV's certificate, its fingerprint and storage, and the protocol and suite choice. */
public class TlsIdentityTest {
  private static final long DAY = 24L * 3600 * 1000;
  private static final long NOW = 1792454400000L; // 20 Oct 2026, 00:00 UTC

  public void test_C_TLS_1_base64url_matches_the_jdk_encoder_without_padding() {
    Random random = new Random(7);
    for (int n = 0; n <= 70; n++) {
      byte[] data = new byte[n];
      random.nextBytes(data);
      Check.equal(Base64.getUrlEncoder().withoutPadding().encodeToString(data), Base64Url.encode(data));
    }
    Check.equal("-_8", Base64Url.encode(new byte[] {(byte) 0xfb, (byte) 0xff}));
  }

  public void test_C_TLS_2_certificate_is_a_self_signed_p256_ecdsa_v3_leaf() throws Exception {
    TlsIdentity id = TlsIdentity.generate(new SecureRandom(), NOW);
    X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(id.certificate.getEncoded()));
    cert.verify(cert.getPublicKey());
    cert.verify(id.certificate.getPublicKey());
    Check.equal(3, cert.getVersion());
    Check.equal("SHA256withECDSA", cert.getSigAlgName());
    Check.equal("1.2.840.10045.4.3.2", cert.getSigAlgOID());
    Check.equal("CN=CleanRep TV", cert.getSubjectX500Principal().getName());
    Check.equal("CN=CleanRep TV", cert.getIssuerX500Principal().getName());
    Check.isTrue(cert.getSerialNumber().signum() > 0, "positive serial");
    Check.isTrue(cert.getSerialNumber().bitLength() <= 159, "serial fits in 20 octets");
    Check.equal(-1, cert.getBasicConstraints());
    Check.isTrue(cert.getCriticalExtensionOIDs().contains("2.5.29.19"), "basicConstraints is critical");
    ECPublicKey key = (ECPublicKey) cert.getPublicKey();
    Check.equal(256, key.getParams().getCurve().getField().getFieldSize());
    Check.equal(new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16),
        ((java.security.spec.ECFieldFp) key.getParams().getCurve().getField()).getP());
    Check.equal(NOW - DAY, cert.getNotBefore().getTime());
    Check.equal(NOW + 3650 * DAY, cert.getNotAfter().getTime());
    cert.checkValidity(new java.util.Date(NOW));
    // The private key belongs to the certificate.
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(id.key);
    s.update(new byte[] {1, 2, 3});
    byte[] sig = s.sign();
    s.initVerify(cert);
    s.update(new byte[] {1, 2, 3});
    Check.isTrue(s.verify(sig), "key matches certificate");
    // Two identities differ in key and serial.
    TlsIdentity other = TlsIdentity.generate(new SecureRandom(), NOW);
    Check.isTrue(!other.certificate.getSerialNumber().equals(cert.getSerialNumber()), "random serial");
    Check.isTrue(!other.fingerprint().equals(id.fingerprint()), "new key, new fingerprint");
  }

  public void test_C_TLS_3_times_use_utctime_before_2050_and_generalizedtime_after() throws Exception {
    byte[] before = TlsIdentity.time(2524607999000L); // 2049-12-31 23:59:59 UTC
    Check.equal(0x17, before[0] & 0xff);
    Check.equal("491231235959Z", new String(before, 2, before.length - 2, StandardCharsets.US_ASCII));
    byte[] after = TlsIdentity.time(2524608000000L); // 2050-01-01 00:00:00 UTC
    Check.equal(0x18, after[0] & 0xff);
    Check.equal("20500101000000Z", new String(after, 2, after.length - 2, StandardCharsets.US_ASCII));
    // A certificate made in 2045 ends after 2050 and still parses.
    X509Certificate late = TlsIdentity.generate(new SecureRandom(), 2366841600000L).certificate;
    Check.equal(2366841600000L + 3650 * DAY, late.getNotAfter().getTime());
  }

  public void test_C_TLS_4_fingerprint_is_43_base64url_chars_and_matches_the_phone_pin() throws Exception {
    TlsIdentity id = TlsIdentity.generate(new SecureRandom(), NOW);
    Check.equal(43, id.fingerprint().length());
    Check.isTrue(id.fingerprint().matches("[A-Za-z0-9_-]{43}"), "base64url alphabet");
    Check.equal(PinnedTls.fingerprintOf(id.certificate), id.fingerprint());
    Check.isTrue(PhonePolicy.isValidFingerprint(id.fingerprint()), "the phone accepts it");
    byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(id.certificate.getEncoded());
    Check.equalBytes(digest, Base64.getUrlDecoder().decode(id.fingerprint()));
  }

  public void test_C_TLS_5_identity_is_stable_across_a_reload_from_pkcs12() throws Exception {
    File dir = Temp.dir("tls5");
    File file = new File(dir, "tv.p12");
    List<String> log = new ArrayList<>();
    TlsIdentity first = TlsIdentity.loadOrCreate(file, "pw-1".toCharArray(), new SecureRandom(), NOW, Temp.log(log));
    Check.isTrue(file.isFile(), "stored");
    Check.equal(0, log.size());
    TlsIdentity again = TlsIdentity.loadOrCreate(file, "pw-1".toCharArray(), new SecureRandom(), NOW + DAY, Temp.log(log));
    Check.equal(first.fingerprint(), again.fingerprint());
    Check.equalBytes(first.key.getEncoded(), again.key.getEncoded());
    Check.equal(0, log.size());
    java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
      ks.load(in, "pw-1".toCharArray());
    }
    Check.isTrue(ks.isKeyEntry("cleanrep-tv"), "a PKCS#12 key entry");
  }

  public void test_C_TLS_6_wrong_password_makes_a_new_identity_and_logs_one_line() throws Exception {
    File file = new File(Temp.dir("tls6"), "tv.p12");
    List<String> log = new ArrayList<>();
    TlsIdentity first = TlsIdentity.loadOrCreate(file, "right".toCharArray(), new SecureRandom(), NOW, Temp.log(log));
    TlsIdentity second = TlsIdentity.loadOrCreate(file, "wrong".toCharArray(), new SecureRandom(), NOW, Temp.log(log));
    Check.isTrue(!first.fingerprint().equals(second.fingerprint()), "new identity");
    Check.equal(1, log.size());
    Check.isTrue(log.get(0).contains("scan") && log.get(0).contains("QR"), "says the phone must scan again: " + log.get(0));
    TlsIdentity third = TlsIdentity.loadOrCreate(file, "wrong".toCharArray(), new SecureRandom(), NOW, Temp.log(log));
    Check.equal(second.fingerprint(), third.fingerprint());
    Check.equal(1, log.size());
  }

  public void test_C_TLS_7_corrupt_or_empty_file_makes_a_new_identity() throws Exception {
    File file = new File(Temp.dir("tls7"), "tv.p12");
    List<String> log = new ArrayList<>();
    TlsIdentity first = TlsIdentity.loadOrCreate(file, "pw".toCharArray(), new SecureRandom(), NOW, Temp.log(log));
    byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
    bytes[bytes.length / 2] ^= 0x5a;
    try (FileOutputStream out = new FileOutputStream(file)) {
      out.write(bytes);
    }
    TlsIdentity second = TlsIdentity.loadOrCreate(file, "pw".toCharArray(), new SecureRandom(), NOW, Temp.log(log));
    Check.isTrue(!first.fingerprint().equals(second.fingerprint()), "new identity after corruption");
    Check.equal(1, log.size());
    new FileOutputStream(file).close();
    TlsIdentity third = TlsIdentity.loadOrCreate(file, "pw".toCharArray(), new SecureRandom(), NOW, Temp.log(log));
    Check.isTrue(!third.fingerprint().equals(second.fingerprint()), "new identity after truncation");
    Check.equal(2, log.size());
    Check.equal(third.fingerprint(),
        TlsIdentity.loadOrCreate(file, "pw".toCharArray(), new SecureRandom(), NOW, Temp.log(log)).fingerprint());
  }

  public void test_C_TLS_8_saves_replace_the_file_atomically_and_ignore_the_clock() throws Exception {
    File dir = Temp.dir("tls8");
    File file = new File(dir, "tv.p12");
    List<String> log = new ArrayList<>();
    TlsIdentity first = TlsIdentity.loadOrCreate(file, "pw".toCharArray(), new SecureRandom(), NOW, Temp.log(log));
    // Eleven years on, or with a TV clock stuck in 1970, the pinned identity stays.
    Check.equal(first.fingerprint(),
        TlsIdentity.loadOrCreate(file, "pw".toCharArray(), new SecureRandom(), NOW + 4000 * DAY, Temp.log(log)).fingerprint());
    Check.equal(first.fingerprint(),
        TlsIdentity.loadOrCreate(file, "pw".toCharArray(), new SecureRandom(), 0, Temp.log(log)).fingerprint());
    TlsIdentity second = TlsIdentity.generate(new SecureRandom(), NOW);
    second.save(file, "pw".toCharArray());
    Check.equal(second.fingerprint(),
        TlsIdentity.loadOrCreate(file, "pw".toCharArray(), new SecureRandom(), NOW, Temp.log(log)).fingerprint());
    Check.equal(Arrays.asList("tv.p12"), Arrays.asList(dir.list()));
    Check.equal(0, log.size());
    Check.isTrue(file.canRead() && file.canWrite(), "owner can use it");
  }

  public void test_C_TLS_9_only_tls_1_2_and_1_3_are_enabled() {
    Check.equal(Arrays.asList("TLSv1.3", "TLSv1.2"),
        Arrays.asList(TlsIdentity.protocols(new String[] {"SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"})));
    // Android 7.1 (API 25) has no TLS 1.3.
    Check.equal(Arrays.asList("TLSv1.2"), Arrays.asList(TlsIdentity.protocols(new String[] {"SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2"})));
    Check.raises(IllegalStateException.class, () -> TlsIdentity.protocols(new String[] {"TLSv1", "TLSv1.1"}));
  }

  public void test_C_TLS_10_only_ecdhe_ecdsa_aes_gcm_suites_are_enabled() {
    String[] supported = {
      "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
      "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
      "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384",
      "TLS_CHACHA20_POLY1305_SHA256", "TLS_EMPTY_RENEGOTIATION_INFO_SCSV"
    };
    Check.equal(Arrays.asList("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"),
        Arrays.asList(TlsIdentity.suites(supported)));
    // API 25: TLS 1.2 suites only.
    Check.equal(Arrays.asList("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"),
        Arrays.asList(TlsIdentity.suites(new String[] {"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_128_GCM_SHA256"})));
    Check.raises(IllegalStateException.class, () -> TlsIdentity.suites(new String[] {"TLS_RSA_WITH_AES_128_GCM_SHA256"}));
  }
}
