set -eu
p="$RUNNER_TEMP/test-clip"
mkdir -p "$p/classes"; cd "$p"
sdk=$(find "$ANDROID_HOME/platforms" -name android.jar | sort -V | tail -1)
bt=$(dirname "$(find "$ANDROID_HOME/build-tools" -name apksigner | sort -V | tail -1)")
cat > AndroidManifest.xml <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ci.vscreen.clip"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="30"/><application android:debuggable="true" android:label="Smoke link check"><activity android:name=".Check" android:exported="true"/></application></manifest>
XML
cat > Check.java <<'JAVA'
package ci.vscreen.clip;
import android.app.Activity;
import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
public class Check extends Activity {
  @Override public void onCreate(Bundle b) { super.onCreate(b); setContentView(new TextView(this)); }
  @Override public void onWindowFocusChanged(boolean has) {
    super.onWindowFocusChanged(has);
    if (!has) return;
    getWindow().getDecorView().postDelayed(() -> {
      ClipData c=getSystemService(ClipboardManager.class).getPrimaryClip();
      if (c==null || c.getItemCount()!=1 || c.getItemAt(0).getText()==null) return;
      Uri u=Uri.parse(c.getItemAt(0).getText().toString());
      if (!"http".equals(u.getScheme()) || !"headscale.smoke.test".equals(u.getHost()) || u.getPort()!=18081 ||
          u.getPath()==null || !u.getPath().matches("/register/[A-Za-z0-9_-]+")) return;
      try(FileOutputStream f=openFileOutput("code",MODE_PRIVATE)) { f.write(u.getLastPathSegment().getBytes(StandardCharsets.US_ASCII)); }
      catch(Exception e) { throw new RuntimeException(e); }
    },500);
  }
}
JAVA
javac -source 8 -target 8 -classpath "$sdk" -d classes Check.java
"$bt/d8" --lib "$sdk" --min-api 30 --output . classes/ci/vscreen/clip/Check.class
"$bt/aapt2" link -I "$sdk" --manifest AndroidManifest.xml -o clip.apk
zip -q clip.apk classes.dex
keytool -genkeypair -keystore clip.jks -storepass temporary -keypass temporary -alias smoke -dname CN=Smoke -keyalg RSA -validity 3
"$bt/apksigner" sign --ks clip.jks --ks-pass pass:temporary --key-pass pass:temporary clip.apk
adb install clip.apk
