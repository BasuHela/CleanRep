package org.cleanrep.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.cleanrep.core.CoreConfig;
import org.cleanrep.core.CleanRepApi;
import org.cleanrep.core.http.HomeAssets;
import org.cleanrep.core.http.Listeners;
import org.cleanrep.core.http.StaticSource;
import org.cleanrep.core.http.TlsIdentity;

/**
 * The session server inside the TV app (ARCHITECTURE.md): the Java core behind two listeners on all interfaces,
 * 8105 (HTTP: the web build, the phone APK, and this app's own loopback API calls) and 8443 (HTTPS, which the phone
 * app pins). One per process, so recreate() after a renderer crash keeps it; MainActivity stops it when it finishes.
 */
final class HomeServer {
  static final int HTTP_PORT = 8105;
  static final int HTTPS_PORT = 8443;
  private static final String KEY_ALIAS = "cleanrep_tls_password";
  private static final org.cleanrep.core.http.Log LOG = (message, error) -> {
    if (error == null) Log.w("CleanRep", message);
    else Log.e("CleanRep", message, error);
  };
  private static CleanRepApi api;
  private static Listeners listeners;
  private static volatile String fingerprint = "";

  private HomeServer() {}

  /** Starts the server once per process; later calls return at once. Blocks (key and store work), so call it off the UI thread. */
  static synchronized boolean start(Context context) {
    if (listeners != null) return true;
    Context app = context.getApplicationContext();
    AssetManager assets = app.getAssets();
    Listeners bound = null;
    CleanRepApi core = null;
    try {
      TlsIdentity tls = TlsIdentity.loadOrCreate(new File(app.getFilesDir(), "tv-identity.p12"), password(app),
          new SecureRandom(), System.currentTimeMillis(), LOG);
      bound = Listeners.bind(InetAddress.getByName("0.0.0.0"), HTTP_PORT, tls, HTTPS_PORT);
      String address = Listeners.lanAddress();
      CoreConfig config = CoreConfig.builder(new File(app.getFilesDir(), "home-data"), HomeAssets.resources(assets::open))
          .origin("http://127.0.0.1:" + HTTP_PORT)
          .extraOrigins(Listeners.phoneOrigins(address, HTTPS_PORT))
          .phoneUrl(Listeners.phoneUrl(address, HTTP_PORT))
          .random(new SecureRandom())
          .build();
      core = new CleanRepApi(config);
      core.recover();
      bound.start(core, new Assets(assets), LOG);
      api = core;
      listeners = bound;
      fingerprint = tls.fingerprint();
      if (BuildConfig.DEBUG) {
        Log.i("CleanRep", "CleanRep: home server ready, phone link " + Listeners.pairLinkTemplate(address, HTTPS_PORT, fingerprint));
      }
      return true;
    } catch (Exception e) {
      Log.e("CleanRep", "CleanRep: home server could not start", e);
      if (bound != null) bound.close();
      if (core != null) core.close();
      return false;
    }
  }

  /** The certificate's pin for the pairing QR code, or "" when the server is not running. */
  static String fingerprint() {
    return fingerprint;
  }

  /** Stops the listeners, then closes the store. Blocks for up to a few seconds, so call it off the UI thread. */
  static synchronized void stop() {
    fingerprint = "";
    if (listeners != null) listeners.close();
    if (api != null) api.close();
    listeners = null;
    api = null;
  }

  /** The PKCS#12 password: random, kept encrypted under an Android Keystore AES-GCM key, as MainActivity keeps the session. */
  private static char[] password(Context app) throws Exception {
    SharedPreferences prefs = app.getSharedPreferences("cleanrep_home", Context.MODE_PRIVATE);
    String data = prefs.getString("data", null);
    String iv = prefs.getString("iv", null);
    if (data != null && iv != null) {
      try {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8).toCharArray();
      } catch (Exception e) {
        // A new password means a new certificate (TlsIdentity logs that phones must scan again).
        Log.w("CleanRep", "CleanRep: the TV certificate password could not be read (" + e.getClass().getSimpleName() + "); making a new one");
      }
    }
    byte[] random = new byte[24];
    new SecureRandom().nextBytes(random);
    String password = Base64.encodeToString(random, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    byte[] sealed = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
    boolean saved = prefs.edit().putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
        .putString("data", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit();
    if (!saved) throw new IOException("cannot store the TV certificate password");
    return password.toCharArray();
  }

  private static SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return generator.generateKey();
  }

  /**
   * The web build and the phone APK in this APK's assets, for the 8105 listener. A fresh AssetInputStream's
   * available() is the asset's whole size, stored or compressed, so no file is read just to learn its length.
   */
  private static final class Assets implements StaticSource {
    private final AssetManager assets;
    private final Map<String, Long> lengths = new ConcurrentHashMap<>();

    Assets(AssetManager assets) {
      this.assets = assets;
    }

    @Override
    public long length(String name) throws IOException {
      Long known = lengths.get(name);
      if (known != null) return known;
      long length;
      try (InputStream in = assets.open(name, AssetManager.ACCESS_STREAMING)) {
        length = in.available();
      } catch (FileNotFoundException e) {
        return -1; // misses aren't cached, so request names can't grow the map
      }
      lengths.put(name, length);
      return length;
    }

    @Override
    public InputStream open(String name) throws IOException {
      return assets.open(name, AssetManager.ACCESS_STREAMING);
    }
  }
}
