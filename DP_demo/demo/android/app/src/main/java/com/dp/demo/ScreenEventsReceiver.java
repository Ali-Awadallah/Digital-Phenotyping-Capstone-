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
      writer.write("\n");
    } catch (Exception ignored) {}
  }
}
