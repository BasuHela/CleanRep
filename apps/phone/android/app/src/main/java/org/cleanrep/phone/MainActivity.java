package org.cleanrep.phone;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Native phone companion shell. Serves the packaged https://cleanrep.phone origin and pins TV HTTPS traffic. */
public final class MainActivity extends Activity {
 private static final String TAG = "CleanRep";
 private static final int REQ_CAMERA = 1001;
 private static final String SESSION_KEY_ALIAS = "cleanrep_phone_session";
 private static final String SESSION_PREFS = "cleanrep_phone_encrypted";
 private static final String SERVER_PREFS = "cleanrep_phone_server";

 private static final class ActiveServer {
  final PhonePolicy.ServerConfig config;
  final PinnedTls tls;

  ActiveServer(PhonePolicy.ServerConfig config) {
   this.config = config;
   this.tls = config.isPinned() ? new PinnedTls(config.host(), config.fingerprint()) : null;
  }
 }

 private WebView web;
 private PermissionRequest pendingCameraRequest;
 private boolean awaitingCameraPermission = false;
 private boolean pausedForCameraPermission = false;
 private volatile ActiveServer activeServer;
 private volatile boolean destroyed = false;
 private final AtomicInteger nativeEpoch = new AtomicInteger(0);
 private final AtomicBoolean mirrorInFlight = new AtomicBoolean(false);
 private final Set<HttpURLConnection> activeConnections =
  Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());
 private final ThreadPoolExecutor apiNetwork =
  new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16));
 private final ThreadPoolExecutor mirrorNetwork =
  new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1));

 private boolean isDebuggable() {
  return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
 }

 private void stopNetwork() {
  nativeEpoch.incrementAndGet();
  apiNetwork.getQueue().clear();
  boolean removedQueuedMirror = mirrorNetwork.getQueue().poll() != null;
  mirrorNetwork.getQueue().clear();
  if (removedQueuedMirror || mirrorNetwork.getActiveCount() == 0) {
   mirrorInFlight.set(false);
  }
  for (HttpURLConnection connection : activeConnections) {
   connection.disconnect();
  }
  activeConnections.clear();
 }

 private void deliverResponse(String id, String payload, int epoch) {
  runOnUiThread(() -> {
   if (destroyed || nativeEpoch.get() != epoch || web == null) return;
   String safeId = JSONObject.quote(id);
   String safePayload = JSONObject.quote(payload).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
   web.evaluateJavascript(
    "window.dispatchEvent(new CustomEvent('cleanrep-native-response',{detail:{id:" +
    safeId + ",payload:" + safePayload + "}}));",
    null
   );
  });
 }

 @Override public void onCreate(Bundle saved) {
  super.onCreate(saved);
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  loadSavedServer();

  web = new WebView(this);
  setContentView(web);
  web.setBackgroundColor(0xff13211d);
  WebSettings settings = web.getSettings();
  settings.setJavaScriptEnabled(true);
  settings.setDomStorageEnabled(true);
  settings.setAllowFileAccess(false);
  settings.setAllowContentAccess(false);
  settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
  settings.setMediaPlaybackRequiresUserGesture(false);
  CookieManager.getInstance().setAcceptCookie(false);
  if (isDebuggable()) {
   WebView.setWebContentsDebuggingEnabled(true);
  }
  web.addJavascriptInterface(new Bridge(), "CleanRepNative");
  web.setWebViewClient(new WebViewClient() {
   @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
    return !PhonePolicy.isPackagedUrl(req.getUrl().toString());
   }

   @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
    return !PhonePolicy.isPackagedUrl(url);
   }

   @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
    String url = req.getUrl().toString();
    String path = req.getUrl().getPath();
    if (isDebuggable()) {
     Log.d(TAG, "intercept " + req.getMethod() + " " + path);
    }
    if (!PhonePolicy.isPackagedUrl(url)) return blocked();
    String asset = PhonePolicy.resolveAssetPath(path);
    if (asset == null) return blocked();
    try {
     String mime = PhonePolicy.mimeType(asset);
     InputStream stream = getAssets().open(asset);
     Map<String, String> headers = new HashMap<>();
     headers.put("Cache-Control", "no-store");
     headers.put("X-Content-Type-Options", "nosniff");
     headers.put("Content-Security-Policy", PhonePolicy.CSP);
     return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, stream);
    } catch (Exception e) {
     return blocked();
    }
   }

   @TargetApi(Build.VERSION_CODES.O)
   @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
    Log.e(TAG, "WebView renderer gone: didCrash=" + detail.didCrash() + " priorityAtExit=" + detail.rendererPriorityAtExit());
    if (view != web) return true;
    stopNetwork();
    if (view.getParent() instanceof ViewGroup) {
     ((ViewGroup) view.getParent()).removeView(view);
    }
    view.destroy();
    web = null;
    if (!destroyed) recreate();
    return true;
   }
  });
  web.setWebChromeClient(new WebChromeClient() {
   @Override public void onPermissionRequest(PermissionRequest request) {
    if (request == null) return;
    Uri origin = request.getOrigin();
    if (origin == null || !PhonePolicy.isPackagedOrigin(origin.toString())) {
     request.deny();
     return;
    }
    boolean wantsVideo = false;
    String[] resources = request.getResources();
    if (resources != null) {
     for (String res : resources) {
      if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
       wantsVideo = true;
      }
     }
    }
    if (!wantsVideo) {
     request.deny();
     return;
    }
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
     request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
     return;
    }
    if (pendingCameraRequest != null && pendingCameraRequest != request) {
     pendingCameraRequest.deny();
    }
    pendingCameraRequest = request;
    awaitingCameraPermission = true;
    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
   }

   @Override public void onPermissionRequestCanceled(PermissionRequest request) {
    if (pendingCameraRequest == request) {
     pendingCameraRequest = null;
     awaitingCameraPermission = false;
    }
   }
  });
  web.setFocusable(true);
  web.setFocusableInTouchMode(true);
  web.requestFocus();
  web.loadUrl(handleIntent(getIntent()));
 }

 @Override protected void onNewIntent(Intent intent) {
  super.onNewIntent(intent);
  setIntent(intent);
  String targetUrl = handleIntent(intent);
  if (web != null) {
   web.loadUrl(targetUrl);
  }
 }

 @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
  super.onRequestPermissionsResult(requestCode, permissions, grantResults);
  if (requestCode != REQ_CAMERA) return;
  awaitingCameraPermission = false;
  PermissionRequest req = pendingCameraRequest;
  pendingCameraRequest = null;
  if (req == null) return;
  if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
   req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
  } else {
   req.deny();
  }
 }

 private String handleIntent(Intent intent) {
  if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
   return PhonePolicy.PACKAGED_ORIGIN + "/phone";
  }
  String data = intent.getDataString();
  PhonePolicy.IntentTarget target = PhonePolicy.IntentTarget.parse(data);
  if (target.kind() == PhonePolicy.IntentTarget.Kind.PAIR) {
   PhonePolicy.ServerConfig srv = target.server();
   applyServer(srv.host(), srv.port(), srv.fingerprint());
   return target.startUrl();
  }
  if (target.kind() == PhonePolicy.IntentTarget.Kind.CHECK) {
   return target.startUrl();
  }
  if (target.kind() == PhonePolicy.IntentTarget.Kind.INVALID && isDebuggable()) {
   Log.w(TAG, "Invalid pair intent (" + target.reason() + "): " + data);
  }
  return PhonePolicy.PACKAGED_ORIGIN + "/phone";
 }

 private WebResourceResponse blocked() {
  return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", null, new ByteArrayInputStream(new byte[0]));
 }

 @Override protected void onPause() {
  if (awaitingCameraPermission || pendingCameraRequest != null) {
   pausedForCameraPermission = true;
   if (isDebuggable()) {
    Log.d(TAG, "Skipping onPause while camera permission request is pending");
   }
   super.onPause();
   return;
  }
  stopNetwork();
  if (web != null) {
   web.evaluateJavascript("document.dispatchEvent(new Event('cleanrep-native-pause'));", null);
   web.onPause();
  }
  super.onPause();
 }

 @Override protected void onResume() {
  super.onResume();
  if (pausedForCameraPermission || awaitingCameraPermission || pendingCameraRequest != null) {
   pausedForCameraPermission = false;
   awaitingCameraPermission = false;
   if (isDebuggable()) {
    Log.d(TAG, "Skipping onResume after camera permission request");
   }
   if (web != null) {
    web.requestFocus();
   }
   return;
  }
  if (web != null) {
   web.onResume();
   web.evaluateJavascript("document.dispatchEvent(new Event('cleanrep-native-resume'));", null);
   web.requestFocus();
  }
 }

 @Override protected void onDestroy() {
  destroyed = true;
  stopNetwork();
  apiNetwork.shutdownNow();
  mirrorNetwork.shutdownNow();
  if (pendingCameraRequest != null) {
   pendingCameraRequest.deny();
   pendingCameraRequest = null;
  }
  if (web != null) {
   web.removeJavascriptInterface("CleanRepNative");
   web.destroy();
  }
  super.onDestroy();
 }

 private SharedPreferences serverPrefs() {
  return getSharedPreferences(SERVER_PREFS, MODE_PRIVATE);
 }

 private SharedPreferences sessionPrefs() {
  return getSharedPreferences(SESSION_PREFS, MODE_PRIVATE);
 }

 private void loadSavedServer() {
  SharedPreferences sp = serverPrefs();
  String host = sp.getString("host", null);
  int port = sp.getInt("port", -1);
  String fp = sp.getString("fingerprint", "");
  if (host == null || port <= 0) return;
  PhonePolicy.ServerConfig cfg = PhonePolicy.ServerConfig.validate(host, port, fp);
  if (cfg != null) {
   activeServer = new ActiveServer(cfg);
  } else {
   sp.edit().clear().apply();
  }
 }

 private synchronized boolean applyServer(String host, int port, String fingerprint) {
  PhonePolicy.ServerConfig next = PhonePolicy.ServerConfig.validate(host, port, fingerprint);
  if (next == null) return false;
  ActiveServer current = activeServer;
  boolean changed = current == null || !current.config.equals(next);
  activeServer = new ActiveServer(next);
  serverPrefs().edit()
   .putString("host", next.host())
   .putInt("port", next.port())
   .putString("fingerprint", next.fingerprint())
   .apply();
  if (changed) {
   clearStoredSession();
  }
  return true;
 }

 private SecretKey encryptionKey() throws Exception {
  KeyStore store = KeyStore.getInstance("AndroidKeyStore");
  store.load(null);
  if (store.containsAlias(SESSION_KEY_ALIAS)) {
   return ((KeyStore.SecretKeyEntry) store.getEntry(SESSION_KEY_ALIAS, null)).getSecretKey();
  }
  KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
  generator.init(
   new KeyGenParameterSpec.Builder(SESSION_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .build()
  );
  return generator.generateKey();
 }

 private void clearStoredSession() {
  sessionPrefs().edit().clear().apply();
 }

 final class Bridge {
  @JavascriptInterface public String apiOrigin() {
   ActiveServer srv = activeServer;
   return srv == null ? "" : srv.config.origin();
  }

  @JavascriptInterface public boolean setServer(String host, int port, String fingerprint) {
   return applyServer(host, port, fingerprint);
  }

  @JavascriptInterface public void requestAsync(String id, String method, String path, String body, String token) {
   if (!PhonePolicy.isValidRequestId(id) || destroyed) return;
   final int epoch = nativeEpoch.get();
   if (!PhonePolicy.isValidApiRequest(method, path, body, token)) {
    deliverResponse(id, PhonePolicy.errorEnvelope("native-invalid", "rejected", null, PhonePolicy.MSG_INVALID_REQUEST), epoch);
    return;
   }
   final ActiveServer srv = activeServer;
   if (srv == null) {
    deliverResponse(id, PhonePolicy.errorEnvelope("native-unconfigured", "unavailable", null, PhonePolicy.MSG_NO_SERVER), epoch);
    return;
   }
   try {
    apiNetwork.execute(() -> {
     if (destroyed || nativeEpoch.get() != epoch) return;
     String payload = performRequest(srv, method, path, body, token);
     deliverResponse(id, payload, epoch);
    });
   } catch (RejectedExecutionException exception) {
    deliverResponse(id, PhonePolicy.errorEnvelope("native-busy", "unavailable", null, PhonePolicy.MSG_BUSY), epoch);
   }
  }

  @JavascriptInterface public void requestMirror(String id, String path, String jpegBase64, String token) {
   if (!PhonePolicy.isValidRequestId(id) || destroyed) return;
   final int epoch = nativeEpoch.get();
   if (!PhonePolicy.isValidMirrorInput(path, jpegBase64, token)) {
    deliverResponse(id, PhonePolicy.errorEnvelope("native-invalid", "rejected", null, PhonePolicy.MSG_INVALID_REQUEST), epoch);
    return;
   }
   final byte[] jpegBytes;
   try {
    jpegBytes = Base64.decode(jpegBase64, Base64.DEFAULT);
   } catch (IllegalArgumentException e) {
    deliverResponse(id, PhonePolicy.errorEnvelope("native-invalid", "rejected", null, PhonePolicy.MSG_INVALID_REQUEST), epoch);
    return;
   }
   if (!PhonePolicy.isValidMirrorJpeg(jpegBytes)) {
    deliverResponse(id, PhonePolicy.errorEnvelope("native-invalid", "rejected", null, PhonePolicy.MSG_INVALID_REQUEST), epoch);
    return;
   }
   final ActiveServer srv = activeServer;
   if (srv == null) {
    deliverResponse(id, PhonePolicy.errorEnvelope("native-unconfigured", "unavailable", null, PhonePolicy.MSG_NO_SERVER), epoch);
    return;
   }
   if (!mirrorInFlight.compareAndSet(false, true)) {
    deliverResponse(id, PhonePolicy.errorEnvelope("native-busy", "unavailable", null, PhonePolicy.MSG_BUSY), epoch);
    return;
   }
   try {
    mirrorNetwork.execute(() -> {
     try {
      if (destroyed || nativeEpoch.get() != epoch) return;
      String payload = performMirror(srv, path, jpegBytes, token);
      deliverResponse(id, payload, epoch);
     } finally {
      mirrorInFlight.set(false);
     }
    });
   } catch (RejectedExecutionException exception) {
    mirrorInFlight.set(false);
    deliverResponse(id, PhonePolicy.errorEnvelope("native-busy", "unavailable", null, PhonePolicy.MSG_BUSY), epoch);
   }
  }

  private HttpURLConnection openConnection(ActiveServer srv, String path, String method, int connectMs, int readMs, String contentType, String token) throws IOException {
   URL url = new URL(srv.config.origin() + path);
   HttpURLConnection conn = (HttpURLConnection) url.openConnection();
   activeConnections.add(conn);
   if (conn instanceof HttpsURLConnection) {
    if (srv.tls == null) throw new SecurityException("Missing TLS pin for HTTPS server");
    HttpsURLConnection https = (HttpsURLConnection) conn;
    https.setSSLSocketFactory(srv.tls.socketFactory());
    https.setHostnameVerifier(srv.tls.hostnameVerifier());
   }
   conn.setInstanceFollowRedirects(false);
   conn.setRequestMethod(method);
   conn.setConnectTimeout(connectMs);
   conn.setReadTimeout(readMs);
   conn.setRequestProperty("Content-Type", contentType);
   conn.setRequestProperty("Origin", srv.config.origin());
   if (!token.isEmpty()) {
    conn.setRequestProperty("Authorization", "Bearer " + token);
   }
   return conn;
  }

  private String readResponse(HttpURLConnection conn) throws IOException {
   int status = conn.getResponseCode();
   InputStream input = status < 400 ? conn.getInputStream() : conn.getErrorStream();
   if (input == null) throw new IOException("Empty response");
   ByteArrayOutputStream bytes = new ByteArrayOutputStream();
   byte[] buffer = new byte[4096];
   int n;
   try (InputStream stream = input) {
    while ((n = stream.read(buffer)) != -1) {
     bytes.write(buffer, 0, n);
     if (bytes.size() > PhonePolicy.API_MAX_RESPONSE_BYTES) {
      throw new IOException("Response too large");
     }
    }
   }
   return bytes.toString("UTF-8");
  }

  private String performRequest(ActiveServer srv, String method, String path, String body, String token) {
   HttpURLConnection connection = null;
   try {
    if (!PhonePolicy.isValidApiRequest(method, path, body, token)) {
     throw new SecurityException("Invalid scoped request");
    }
    connection = openConnection(srv, path, method, 5000, 10000, "application/json", token);
    if (!body.isEmpty()) {
     connection.setDoOutput(true);
     connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
    return readResponse(connection);
   } catch (Exception e) {
    String msg = PinnedTls.isPinMismatch(e) ? PhonePolicy.MSG_PIN_MISMATCH : PhonePolicy.MSG_NETWORK;
    return PhonePolicy.errorEnvelope("native-network", "unavailable", 0, msg);
   } finally {
    if (connection != null) {
     activeConnections.remove(connection);
     connection.disconnect();
    }
   }
  }

  private String performMirror(ActiveServer srv, String path, byte[] jpegBytes, String token) {
   HttpURLConnection connection = null;
   try {
    if (!PhonePolicy.isValidMirrorPath(path) || !PhonePolicy.isValidMirrorJpeg(jpegBytes)) {
     throw new SecurityException("Invalid mirror request");
    }
    connection = openConnection(srv, path, "POST", 3000, 3000, "image/jpeg", token);
    connection.setDoOutput(true);
    connection.getOutputStream().write(jpegBytes);
    return readResponse(connection);
   } catch (Exception e) {
    String msg = PinnedTls.isPinMismatch(e) ? PhonePolicy.MSG_PIN_MISMATCH : PhonePolicy.MSG_NETWORK;
    return PhonePolicy.errorEnvelope("native-network", "unavailable", 0, msg);
   } finally {
    if (connection != null) {
     activeConnections.remove(connection);
     connection.disconnect();
    }
   }
  }

  @JavascriptInterface public void saveSession(String value) {
   if (value == null || value.length() > 4096) return;
   try {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
    byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
    sessionPrefs().edit()
     .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
     .putString("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
     .apply();
   } catch (Exception e) {
    clearStoredSession();
   }
  }

  @JavascriptInterface public String loadSession() {
   try {
    SharedPreferences prefs = sessionPrefs();
    String data = prefs.getString("data", null);
    String iv = prefs.getString("iv", null);
    if (data == null || iv == null) return "";
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
    return new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8);
   } catch (Exception e) {
    clearStoredSession();
    return "";
   }
  }

  @JavascriptInterface public void clearSession() {
   clearStoredSession();
  }
 }
}
