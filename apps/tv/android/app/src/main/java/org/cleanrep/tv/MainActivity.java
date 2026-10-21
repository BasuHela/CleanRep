package org.cleanrep.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.CookieManager;
import android.annotation.TargetApi;
import android.util.Log;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Native TV shell. Only this APK's packaged origin receives the scoped API/session bridge. */
public final class MainActivity extends Activity {
 private static final String PACKAGED_ORIGIN = "https://cleanrep.local";
 private WebView web;
 private final AtomicInteger nativeEpoch=new AtomicInteger(0);
 private volatile boolean destroyed=false;
 private final Set<HttpURLConnection> activeConnections=java.util.Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection,Boolean>());
 private final ThreadPoolExecutor network=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(16));
 private void stopNetwork(){nativeEpoch.incrementAndGet();network.getQueue().clear();for(HttpURLConnection connection:activeConnections)connection.disconnect();activeConnections.clear();}
 private void deliverResponse(String id,String payload,int epoch){
  runOnUiThread(()->{if(destroyed||nativeEpoch.get()!=epoch||web==null)return;String safeId=JSONObject.quote(id);String safePayload=JSONObject.quote(payload).replace("\u2028","\\u2028").replace("\u2029","\\u2029");web.evaluateJavascript("window.dispatchEvent(new CustomEvent('cleanrep-native-response',{detail:{id:"+safeId+",payload:"+safePayload+"}}));",null);});
 }
 @Override public void onCreate(Bundle saved) {
  super.onCreate(saved);
  web=new WebView(this); setContentView(web);
  web.setBackgroundColor(0xff13211d);
  web.getSettings().setJavaScriptEnabled(true);
  web.getSettings().setDomStorageEnabled(true);
  // The TV UI is laid out on a fixed 1920x1080 CSS canvas (see tv/viewport.ts); honor its viewport width and fit it to the screen.
  web.getSettings().setUseWideViewPort(true);
  web.getSettings().setLoadWithOverviewMode(true);
  web.getSettings().setSupportZoom(false);
  web.getSettings().setAllowFileAccess(false);
  web.getSettings().setAllowContentAccess(false);
  web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
  web.getSettings().setMediaPlaybackRequiresUserGesture(false);
  CookieManager.getInstance().setAcceptCookie(false);
  if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true);
  web.addJavascriptInterface(new Bridge(),"CleanRepNative");
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){return !isPackaged(req.getUrl().toString());}
   @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !isPackaged(url);}
   @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){
    if(!isPackaged(req.getUrl().toString()))return blocked();
    String path=req.getUrl().getPath();
    if(path==null||path.contains("..")||path.startsWith("/cleanrep-home/"))return blocked();
    if("GET".equals(req.getMethod())&&req.getUrl().getQuery()==null&&path.matches("/v1/sessions/[A-Za-z0-9_-]+/mirror")){
     HttpURLConnection conn=null;
     try{
      Map<String,String> reqHeaders=req.getRequestHeaders();String auth=reqHeaders!=null?reqHeaders.get("Authorization"):null;
      if(auth==null||auth.length()>260)return blocked();
      conn=(HttpURLConnection)new URL(BuildConfig.API_ORIGIN+path).openConnection();activeConnections.add(conn);conn.setInstanceFollowRedirects(false);conn.setRequestMethod("GET");conn.setConnectTimeout(3000);conn.setReadTimeout(3000);conn.setRequestProperty("Origin",BuildConfig.API_ORIGIN);conn.setRequestProperty("Authorization",auth);
      int status=conn.getResponseCode();Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("X-Content-Type-Options","nosniff");
      if(status==204)return new WebResourceResponse("text/plain","UTF-8",204,"No Content",headers,new ByteArrayInputStream(new byte[0]));
      if(status!=200)return blocked();
      ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;try(InputStream in=conn.getInputStream()){while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>160000)return blocked();}}
      return new WebResourceResponse("image/jpeg",null,200,"OK",headers,new ByteArrayInputStream(out.toByteArray()));
     }catch(Exception e){return blocked();}finally{if(conn!=null){activeConnections.remove(conn);conn.disconnect();}}
    }
    String asset=(path.equals("/")||path.equals("/tv"))?"index.html":path.substring(1);
    try {String mime=asset.endsWith(".js")||asset.endsWith(".mjs")?"text/javascript":asset.endsWith(".css")?"text/css":asset.endsWith(".mp4")?"video/mp4":asset.endsWith(".png")?"image/png":asset.endsWith(".jpg")?"image/jpeg":asset.endsWith(".svg")?"image/svg+xml":asset.endsWith(".wasm")?"application/wasm":asset.endsWith(".woff2")?"font/woff2":asset.endsWith(".html")?"text/html":"application/octet-stream";
     InputStream stream=getAssets().open(asset);Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("X-Content-Type-Options","nosniff");headers.put("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self'; media-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'");return new WebResourceResponse(mime,"UTF-8",200,"OK",headers,stream);
    }catch(Exception e){return blocked();}
   }
   // Without this override Android kills the whole app when the WebView renderer dies (a crash, or the
   // low-memory killer while the camera view decodes frames). Rebuild the screen; the page rejoins its saved session.
   @TargetApi(Build.VERSION_CODES.O)
   @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){
    Log.e("CleanRep","WebView renderer gone: didCrash="+detail.didCrash()+" priorityAtExit="+detail.rendererPriorityAtExit());
    if(view!=web)return true;
    stopNetwork();
    if(view.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)view.getParent()).removeView(view);
    view.destroy();web=null;
    if(!destroyed)recreate();
    return true;
   }
  });
  web.setFocusable(true);web.setFocusableInTouchMode(true);web.requestFocus();
  web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
  // The page's first API call goes to this app's own server, so load it once the listeners are up (or failed).
  // On the emulator only, `am start --es cleanrep.lan_host <laptop-ip>` names the relay address phones dial (HomeServer).
  final String lanHost=getIntent().getStringExtra(HomeServer.LAN_HOST_EXTRA);
  final WebView page=web;new Thread(()->{HomeServer.rememberLanHost(this,lanHost);HomeServer.start(this);runOnUiThread(()->{if(!destroyed&&web==page)page.loadUrl(PACKAGED_ORIGIN+"/tv");});},"cleanrep-home-start").start();
 }
 private boolean isPackaged(String url){return url.equals(PACKAGED_ORIGIN)||url.startsWith(PACKAGED_ORIGIN+"/");}
 private WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",null,new ByteArrayInputStream(new byte[0]));}
 @Override public boolean dispatchKeyEvent(KeyEvent event){
  String key=null;switch(event.getKeyCode()){
   case KeyEvent.KEYCODE_DPAD_UP:key="ArrowUp";break;case KeyEvent.KEYCODE_DPAD_DOWN:key="ArrowDown";break;
   case KeyEvent.KEYCODE_DPAD_LEFT:key="ArrowLeft";break;case KeyEvent.KEYCODE_DPAD_RIGHT:key="ArrowRight";break;
   case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:key="Enter";break;case KeyEvent.KEYCODE_BACK:key="Escape";break;
   case KeyEvent.KEYCODE_MENU:key="ContextMenu";break;
   case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:key="Escape";break;
  }
  if(key!=null){if(web!=null&&event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:"+JSONObject.quote(key)+",bubbles:true}));",null);}return true;}
  return super.dispatchKeyEvent(event);
 }
 @Override protected void onPause(){stopNetwork();if(web!=null){web.evaluateJavascript("document.dispatchEvent(new Event('cleanrep-native-pause'));",null);web.onPause();}super.onPause();}
 @Override protected void onResume(){super.onResume();if(web!=null){web.onResume();web.evaluateJavascript("document.dispatchEvent(new Event('cleanrep-native-resume'));",null);web.requestFocus();}}
 @Override protected void onDestroy(){destroyed=true;stopNetwork();network.shutdownNow();if(web!=null){web.removeJavascriptInterface("CleanRepNative");web.destroy();}
  // recreate() (renderer crash) keeps the home server; only a real finish stops it.
  if(isFinishing())new Thread(HomeServer::stop,"cleanrep-home-stop").start();
  super.onDestroy();}
 final class Bridge {
  @JavascriptInterface public String apiOrigin(){return BuildConfig.API_ORIGIN;}
  @JavascriptInterface public String serverPlace(){return "tv";}
  @JavascriptInterface public String tlsFingerprint(){return HomeServer.fingerprint();}
  // Back on a top-level screen leaves the app (dispatchKeyEvent forwards every Back press to the page).
  @JavascriptInterface public void exitApp(){runOnUiThread(()->{if(!destroyed)finish();});}
  @JavascriptInterface public void requestAsync(String id,String method,String path,String body,String token){
   if(id==null||!id.matches("[A-Za-z0-9_-]{1,128}")||destroyed)return;
   final int epoch=nativeEpoch.get();
   if(method==null||path==null||body==null||token==null||(!method.equals("GET")&&!method.equals("POST")&&!method.equals("DELETE"))||!path.matches("/v1/[A-Za-z0-9_/-]+")||body.length()>8192||token.length()>200){deliverResponse(id,"{\"request_id\":\"native-invalid\",\"status\":\"rejected\",\"state_version\":null,\"data\":{},\"evidence\":[],\"error\":{\"message\":\"The TV rejected an invalid scoped request.\"}}",epoch);return;}
   try {network.execute(()->{if(destroyed||nativeEpoch.get()!=epoch)return;String payload=performRequest(method,path,body,token);deliverResponse(id,payload,epoch);});}
   catch(RejectedExecutionException exception){deliverResponse(id,"{\"request_id\":\"native-busy\",\"status\":\"unavailable\",\"state_version\":null,\"data\":{},\"evidence\":[],\"error\":{\"message\":\"The TV is busy reconnecting. Retry when ready.\"}}",epoch);}
  }
  private String performRequest(String method,String path,String body,String token){
   HttpURLConnection connection=null;
   try {
    if((!method.equals("GET")&&!method.equals("POST")&&!method.equals("DELETE"))||!path.matches("/v1/[A-Za-z0-9_/-]+")||body.length()>8192||token.length()>200)throw new SecurityException("Invalid scoped request");
    URL url=new URL(BuildConfig.API_ORIGIN+path);connection=(HttpURLConnection)url.openConnection();activeConnections.add(connection);connection.setInstanceFollowRedirects(false);connection.setRequestMethod(method);connection.setConnectTimeout(5000);connection.setReadTimeout(10000);connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Origin",BuildConfig.API_ORIGIN);if(!token.isEmpty())connection.setRequestProperty("Authorization","Bearer "+token);
    if(!body.isEmpty()){connection.setDoOutput(true);connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));}
    int status=connection.getResponseCode();InputStream input=status<400?connection.getInputStream():connection.getErrorStream();if(input==null)throw new java.io.IOException("Empty response");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;try(InputStream stream=input){while((n=stream.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>262144)throw new java.io.IOException("Response too large");}}
    return bytes.toString("UTF-8");
   }catch(Exception e){return "{\"request_id\":\"native-network\",\"status\":\"unavailable\",\"state_version\":0,\"data\":{},\"evidence\":[],\"error\":{\"message\":\"CleanRep's server on this TV isn't answering. Close CleanRep and open it again.\"}}";}
   finally{if(connection!=null){activeConnections.remove(connection);connection.disconnect();}}
  }
  private SharedPreferences prefs(){return getSharedPreferences("cleanrep_encrypted",MODE_PRIVATE);}
  private SecretKey encryptionKey() throws Exception {KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias("cleanrep_session"))return ((KeyStore.SecretKeyEntry)store.getEntry("cleanrep_session",null)).getSecretKey();KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder("cleanrep_session",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();}
  @JavascriptInterface public void saveSession(String value){if(value.length()>4096)return;try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,encryptionKey());byte[] ciphertext=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));prefs().edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).putString("data",Base64.encodeToString(ciphertext,Base64.NO_WRAP)).apply();}catch(Exception e){clearSession();}}
  @JavascriptInterface public String loadSession(){try{String data=prefs().getString("data",null),iv=prefs().getString("iv",null);if(data==null||iv==null)return "";Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,encryptionKey(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));return new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8);}catch(Exception e){clearSession();return "";}}
  @JavascriptInterface public void clearSession(){prefs().edit().clear().apply();}
 }
}
