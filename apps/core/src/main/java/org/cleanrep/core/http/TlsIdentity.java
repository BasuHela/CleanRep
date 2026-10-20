package org.cleanrep.core.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The TV's own TLS identity for the 8443 listener (ARCHITECTURE.md): an EC P-256 key and a self-signed
 * X.509 v3 certificate, made here in plain Java with a small DER writer (no BouncyCastle), kept as PKCS#12.
 * The phone app pins {@link #fingerprint()}, so the certificate needs no CA, host name or chain.
 * Every call here exists on Android 7.1 (API 25).
 */
public final class TlsIdentity {
  static final String ALIAS = "cleanrep-tv";
  private static final long DAY_MILLIS = 24L * 3600 * 1000;
  private static final long VALID_DAYS = 3650;
  private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
  // TLS 1.3 names no key exchange or certificate type; with an EC certificate these are ECDHE-ECDSA too.
  private static final String[] SUITES = {
    "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384",
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"
  };

  public final PrivateKey key;
  public final X509Certificate certificate;
  private final String fingerprint;
  private SSLSocketFactory socketFactory;
  private String[] protocols;
  private String[] suites;

  private TlsIdentity(PrivateKey key, X509Certificate certificate) throws GeneralSecurityException {
    this.key = key;
    this.certificate = certificate;
    this.fingerprint = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
  }

  /** SHA-256 of the DER certificate as 43 base64url characters: what the QR code and pairing link carry. */
  public String fingerprint() {
    return fingerprint;
  }

  /** A new key pair and certificate, valid from a day before `nowMillis` for ten years. */
  public static TlsIdentity generate(SecureRandom random, long nowMillis) throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"), random);
    KeyPair pair = generator.generateKeyPair();
    byte[] serial = new byte[16];
    random.nextBytes(serial);
    serial[0] = (byte) ((serial[0] & 0x7f) | 0x40); // positive, and never zero
    long start = nowMillis / 1000 * 1000 - DAY_MILLIS;
    byte[] algorithm = der(0x30, oid("1.2.840.10045.4.3.2")); // ecdsa-with-SHA256, no parameters
    byte[] name = der(0x30, der(0x31, der(0x30, oid("2.5.4.3"), der(0x0c, "CleanRep TV".getBytes(StandardCharsets.UTF_8)))));
    byte[] basicConstraints = der(0x30, oid("2.5.29.19"), der(0x01, new byte[] {(byte) 0xff}), der(0x04, der(0x30)));
    byte[] tbs = der(0x30,
        der(0xa0, der(0x02, new byte[] {2})), // v3
        der(0x02, new BigInteger(serial).toByteArray()),
        algorithm,
        name,
        der(0x30, time(start), time(start + (VALID_DAYS + 1) * DAY_MILLIS)),
        name,
        pair.getPublic().getEncoded(), // SubjectPublicKeyInfo
        der(0xa3, der(0x30, basicConstraints)));
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(pair.getPrivate(), random);
    signer.update(tbs);
    byte[] signature = signer.sign();
    byte[] bits = new byte[signature.length + 1]; // BIT STRING: no unused bits
    System.arraycopy(signature, 0, bits, 1, signature.length);
    byte[] encoded = der(0x30, tbs, algorithm, der(0x03, bits));
    X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(encoded));
    certificate.verify(pair.getPublic());
    return new TlsIdentity(pair.getPrivate(), certificate);
  }

  /**
   * The identity stored in `file`, or a new one (then stored) when the file is missing, unreadable or opened with
   * another password. A replaced identity means a new fingerprint, so the log line says so. Validity dates are not
   * checked: the phone pins the certificate rather than trusting dates, and a TV clock that is wrong at boot must
   * not rotate the identity.
   */
  public static TlsIdentity loadOrCreate(File file, char[] password, SecureRandom random, long nowMillis, Log log)
      throws IOException, GeneralSecurityException {
    if (file.exists()) {
      try {
        return load(file, password);
      } catch (IOException | GeneralSecurityException | RuntimeException e) {
        TlsIdentity fresh = generate(random, nowMillis);
        fresh.save(file, password);
        log.line("CleanRep: the TV certificate in " + file.getName() + " could not be used (" + e.getClass().getSimpleName()
            + "); made a new one, fingerprint " + fresh.fingerprint() + ". Phones must scan the TV's QR code again.", null);
        return fresh;
      }
    }
    TlsIdentity fresh = generate(random, nowMillis);
    fresh.save(file, password);
    return fresh;
  }

  private static TlsIdentity load(File file, char[] password) throws IOException, GeneralSecurityException {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(file)) {
      store.load(in, password);
    }
    Key key = store.getKey(ALIAS, password);
    Certificate certificate = store.getCertificate(ALIAS);
    if (!(key instanceof PrivateKey) || !(certificate instanceof X509Certificate)) {
      throw new GeneralSecurityException("no " + ALIAS + " key entry");
    }
    X509Certificate x509 = (X509Certificate) certificate;
    // The key must belong to the certificate, or every handshake would fail later.
    byte[] probe = "cleanrep".getBytes(StandardCharsets.US_ASCII);
    Signature signature = Signature.getInstance("SHA256withECDSA");
    signature.initSign((PrivateKey) key);
    signature.update(probe);
    byte[] signed = signature.sign();
    signature.initVerify(x509.getPublicKey());
    signature.update(probe);
    if (!signature.verify(signed)) throw new GeneralSecurityException("key does not match certificate");
    return new TlsIdentity((PrivateKey) key, x509);
  }

  /** Writes the PKCS#12 file atomically: a temporary file in the same folder, synced, then renamed over the old one. */
  public void save(File file, char[] password) throws IOException, GeneralSecurityException {
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setKeyEntry(ALIAS, key, password, new Certificate[] {certificate});
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    store.store(bytes, password);
    File dir = file.getAbsoluteFile().getParentFile();
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
    File temp = File.createTempFile(file.getName(), ".tmp", dir);
    try {
      restrict(temp);
      try (FileOutputStream out = new FileOutputStream(temp)) {
        out.write(bytes.toByteArray());
        out.getFD().sync();
      }
      if (!temp.renameTo(file)) throw new IOException("cannot replace " + file);
    } finally {
      if (temp.exists() && !temp.delete()) temp.deleteOnExit();
    }
  }

  /** Owner-only access (0600); false where the platform refused. On Android the app's files folder is private anyway. */
  public static boolean restrict(File file) {
    boolean ok = file.setReadable(false, false) & file.setReadable(true, true);
    ok &= file.setWritable(false, false) & file.setWritable(true, true);
    return ok & file.setExecutable(false, false);
  }

  /** The supported protocols we allow, newest first: TLS 1.3 and 1.2 only. */
  static String[] protocols(String[] supported) {
    return pick(PROTOCOLS, supported, "TLS 1.2");
  }

  /** The supported cipher suites we allow: AES-GCM with ECDHE and the EC certificate. */
  static String[] suites(String[] supported) {
    return pick(SUITES, supported, "ECDHE-ECDSA AES-GCM");
  }

  private static String[] pick(String[] wanted, String[] supported, String what) {
    List<String> have = Arrays.asList(supported);
    List<String> out = new ArrayList<>();
    for (String w : wanted) if (have.contains(w)) out.add(w);
    if (out.isEmpty()) throw new IllegalStateException("this platform has no " + what);
    return out.toArray(new String[0]);
  }

  /**
   * Wraps an accepted plain socket in server-mode TLS with our protocols and suites, and no client certificate.
   * The handshake runs on first use. Keeping the plain socket lets the server close it outright on a deadline,
   * which an SSLSocket's own close (it writes close_notify) can't promise.
   */
  public synchronized SSLSocket wrap(Socket plain) throws IOException {
    if (socketFactory == null) {
      try {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        char[] password = "cleanrep".toCharArray(); // in memory only
        store.setKeyEntry(ALIAS, key, password, new Certificate[] {certificate});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, new SecureRandom());
        SSLParameters supported = context.getSupportedSSLParameters();
        protocols = protocols(supported.getProtocols());
        suites = suites(supported.getCipherSuites());
        socketFactory = context.getSocketFactory();
      } catch (GeneralSecurityException e) {
        throw new IOException("TLS setup failed", e);
      }
    }
    SSLSocket socket = (SSLSocket) socketFactory.createSocket(plain, plain.getInetAddress().getHostAddress(), plain.getPort(), true);
    socket.setUseClientMode(false);
    socket.setNeedClientAuth(false);
    socket.setWantClientAuth(false);
    socket.setEnabledProtocols(protocols);
    socket.setEnabledCipherSuites(suites);
    return socket;
  }

  // DER (ITU-T X.690), just what the certificate needs.

  static byte[] der(int tag, byte[]... parts) {
    int length = 0;
    for (byte[] p : parts) length += p.length;
    ByteArrayOutputStream out = new ByteArrayOutputStream(length + 6);
    out.write(tag);
    if (length < 0x80) {
      out.write(length);
    } else {
      int n = length > 0xffffff ? 4 : length > 0xffff ? 3 : length > 0xff ? 2 : 1;
      out.write(0x80 | n);
      for (int i = n - 1; i >= 0; i--) out.write(length >>> (8 * i));
    }
    for (byte[] p : parts) out.write(p, 0, p.length);
    return out.toByteArray();
  }

  static byte[] oid(String dotted) {
    String[] arcs = dotted.split("\\.");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(Integer.parseInt(arcs[0]) * 40 + Integer.parseInt(arcs[1]));
    for (int i = 2; i < arcs.length; i++) {
      long value = Long.parseLong(arcs[i]);
      int groups = 1;
      while ((value >>> (7 * groups)) != 0) groups++;
      for (int g = groups - 1; g >= 0; g--) out.write((int) ((value >>> (7 * g)) & 0x7f) | (g > 0 ? 0x80 : 0));
    }
    return der(0x06, out.toByteArray());
  }

  /** RFC 5280 §4.1.2.5: UTCTime through 2049, GeneralizedTime from 2050. */
  static byte[] time(long millis) {
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
    calendar.setTimeInMillis(millis);
    boolean utc = calendar.get(Calendar.YEAR) < 2050;
    SimpleDateFormat format = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.ROOT);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return der(utc ? 0x17 : 0x18, format.format(new Date(millis)).getBytes(StandardCharsets.US_ASCII));
  }
}
