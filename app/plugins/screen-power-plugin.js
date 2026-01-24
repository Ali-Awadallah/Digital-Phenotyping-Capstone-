// Expo config plugin to add an Android BroadcastReceiver for screen on/off events
// and generate the native Java file during prebuild.

const { withAndroidManifest, AndroidConfig, withDangerousMod } = require('expo/config-plugins');
const fs = require('fs');
const path = require('path');

const RECEIVER_CLASS = 'ScreenEventsReceiver';
const RELATIVE_CLASS_NAME = `.${RECEIVER_CLASS}`;

function addReceiverToManifest(androidManifest) {
  const app = AndroidConfig.Manifest.getMainApplicationOrThrow(androidManifest);
  app.receiver = app.receiver ?? [];

  const exists = app.receiver.some((r) => r.$ && r.$['android:name'] === RELATIVE_CLASS_NAME);
  if (exists) return androidManifest;

  app.receiver.push({
    $: {
      'android:name': RELATIVE_CLASS_NAME,
      'android:exported': 'false',
    },
    'intent-filter': [
      {
        action: [
          { $: { 'android:name': 'android.intent.action.SCREEN_ON' } },
          { $: { 'android:name': 'android.intent.action.SCREEN_OFF' } },
          { $: { 'android:name': 'android.intent.action.USER_PRESENT' } },
        ],
      },
    ],
  });

  return androidManifest;
}

function javaForPackage(pkg) {
  return `package ${pkg};

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;

public class ${RECEIVER_CLASS} extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || intent.getAction() == null) return;
    String action = intent.getAction();
    String evt = null;
    if (Intent.ACTION_SCREEN_ON.equals(action)) evt = "SCREEN_ON";
    else if (Intent.ACTION_SCREEN_OFF.equals(action)) evt = "SCREEN_OFF";
    else if (Intent.ACTION_USER_PRESENT.equals(action)) evt = "USER_PRESENT";
    if (evt == null) return;

    long ts = System.currentTimeMillis();
    File dir = context.getFilesDir();
    File log = new File(dir, "screen-events.log");

    try (FileWriter writer = new FileWriter(log, true)) {
      JSONObject obj = new JSONObject();
      obj.put("ts", ts);
      obj.put("event", evt);
      writer.write(obj.toString());
      writer.write("\\n");
    } catch (Exception ignored) {}
  }
}
`;
}

const withScreenPowerPlugin = (config) => {
  // Inject manifest receiver (no need to know package here)
  config = withAndroidManifest(config, (config) => {
    config.modResults = addReceiverToManifest(config.modResults);
    return config;
  });

  // Generate the Java BroadcastReceiver
  config = withDangerousMod(config, ['android', async (conf) => {
    const projectRoot = conf.modRequest.projectRoot;
    let pkg = (conf.android && conf.android.package) || null;
    if (!pkg) {
      try {
        const appJsonPath = path.join(projectRoot, 'app.json');
        const raw = fs.readFileSync(appJsonPath, 'utf8');
        const parsed = JSON.parse(raw);
        pkg = parsed && parsed.expo && parsed.expo.android && parsed.expo.android.package ? parsed.expo.android.package : null;
      } catch (_) {
        // ignore
      }
    }
    if (!pkg) {
      throw new Error('Android package not found in config. Please set expo.android.package in app.json');
    }
    const androidRoot = conf.modRequest.platformProjectRoot; // <project>/android
    const javaSrcDir = path.join(androidRoot, 'app', 'src', 'main', 'java');
    const pkgPath = pkg.split('.').join(path.sep);
    const outDir = path.join(javaSrcDir, pkgPath);
    const outFile = path.join(outDir, `${RECEIVER_CLASS}.java`);
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(outFile, javaForPackage(pkg), 'utf8');
    return conf;
  }]);

  return config;
};

module.exports = withScreenPowerPlugin;
