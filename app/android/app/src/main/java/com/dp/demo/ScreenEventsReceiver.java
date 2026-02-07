package com.dp.demo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;

public class ScreenEventsReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || intent.getAction() == null)
      return;
    String action = intent.getAction();
    String evt = null;
    String state = null;
    if (Intent.ACTION_SCREEN_ON.equals(action)) {
      evt = "SCREEN_ON";
      state = "ON";
    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
      evt = "SCREEN_OFF";
      state = "OFF";
    } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
      evt = "USER_PRESENT";
      state = "ON";
    }
    if (evt == null)
      return;

    long ts = System.currentTimeMillis();
    File dir = context.getFilesDir();
    // if a sentinel file exists, skip logging
    File disabled = new File(dir, "screen-events.disabled");
    if (disabled.exists())
      return;
    File log = new File(dir, "screen-events.log");

    try (FileWriter writer = new FileWriter(log, true)) {
      JSONObject obj = new JSONObject();
      obj.put("ts", ts);
      obj.put("event", evt);
      writer.write(obj.toString());
      writer.write("\n");
    } catch (Exception ignored) {
    }

    // Send to backend API
    try {
      BackendAPIClient.INSTANCE.sendScreenEvent(context, ts, state);
    } catch (Exception ignored) {
    }
  }
}
