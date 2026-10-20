package org.cleanrep.phone;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Pure-Java leaf-certificate SHA-256 pinning using only javax.net.ssl and java.security.
 * Never falls back to the system trust store and never accepts unpinned certificates.
 */
public final class PinnedTls {
 public static final class PinMismatchException extends CertificateException {
  public PinMismatchException(String message) {
   super(message);
  }
 }

 private final String host;
 private final String fingerprint;
 private final byte[] expectedDigest;
 private final X509TrustManager trustManager;
 private final HostnameVerifier hostnameVerifier;
 private final SSLSocketFactory socketFactory;

 public PinnedTls(String host, String fingerprint) {
  if (!PhonePolicy.isAllowedHost(host)) {
   throw new IllegalArgumentException("Disallowed host: " + host);
  }
  if (!PhonePolicy.isValidFingerprint(fingerprint)) {
   throw new IllegalArgumentException("Invalid certificate fingerprint");
  }
  this.host = host;
  this.fingerprint = fingerprint;
  this.expectedDigest = Base64.getUrlDecoder().decode(fingerprint);
  final byte[] expectedFpBytes = fingerprint.getBytes(StandardCharsets.US_ASCII);
  final byte[] expectedHostBytes = host.getBytes(StandardCharsets.UTF_8);

  this.trustManager = new X509ExtendedTrustManager() {
   @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    throw new CertificateException("Client certificates are not supported");
   }

   @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
    throw new CertificateException("Client certificates are not supported");
   }

   @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
    throw new CertificateException("Client certificates are not supported");
   }

   @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    if (chain == null || chain.length == 0 || chain[0] == null) {
     throw new CertificateException("Missing server certificate chain");
    }
    if (authType == null || authType.isEmpty()) {
     throw new CertificateException("Missing TLS authType");
    }
    byte[] der = chain[0].getEncoded();
    byte[] actualDigest = sha256(der);
    String actualFp = Base64.getUrlEncoder().withoutPadding().encodeToString(actualDigest);
    boolean digestOk = MessageDigest.isEqual(actualDigest, expectedDigest);
    boolean fpOk = MessageDigest.isEqual(actualFp.getBytes(StandardCharsets.US_ASCII), expectedFpBytes);
    if (!digestOk || !fpOk) {
     throw new PinMismatchException("Server certificate does not match pinned fingerprint");
    }
   }

   @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
    checkServerTrusted(chain, authType);
   }

   @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
    checkServerTrusted(chain, authType);
   }

   @Override public X509Certificate[] getAcceptedIssuers() {
    return new X509Certificate[0];
   }
  };

  this.hostnameVerifier = (hostname, session) -> {
   if (hostname == null || session == null) return false;
   return MessageDigest.isEqual(hostname.getBytes(StandardCharsets.UTF_8), expectedHostBytes);
  };

  try {
   SSLContext context = SSLContext.getInstance("TLS");
   context.init(null, new TrustManager[]{this.trustManager}, new SecureRandom());
   this.socketFactory = context.getSocketFactory();
  } catch (GeneralSecurityException e) {
   throw new IllegalStateException("Failed to initialize pinned SSLContext", e);
  }
 }

 public String host() { return host; }
 public String fingerprint() { return fingerprint; }
 public X509TrustManager trustManager() { return trustManager; }
 public HostnameVerifier hostnameVerifier() { return hostnameVerifier; }
 public SSLSocketFactory socketFactory() { return socketFactory; }

 public static String fingerprintOf(X509Certificate cert) throws CertificateEncodingException {
  if (cert == null) throw new IllegalArgumentException("Certificate is null");
  return fingerprintOfDer(cert.getEncoded());
 }

 public static String fingerprintOfDer(byte[] der) {
  if (der == null || der.length == 0) throw new IllegalArgumentException("DER bytes are empty");
  return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(der));
 }

 public static boolean isPinMismatch(Throwable error) {
  Throwable current = error;
  while (current != null) {
   if (current instanceof PinMismatchException ||
       current instanceof CertificateException ||
       current instanceof SSLPeerUnverifiedException) {
    return true;
   }
   String msg = current.getMessage();
   if (msg != null && msg.toLowerCase( java.util.Locale.ROOT ).contains("hostname")) {
    return true;
   }
   current = current.getCause();
  }
  return false;
 }

 private static byte[] sha256(byte[] input) {
  try {
   return MessageDigest.getInstance("SHA-256").digest(input);
  } catch (NoSuchAlgorithmException e) {
   throw new IllegalStateException("SHA-256 unavailable", e);
  }
 }
}
