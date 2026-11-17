package io.github.sarukibrowser.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/**
 * v1.8: restarts Saruki after an import, so every part (tabs, settings, phone pairing) starts from the imported data.
 * Runs in its own process (":restart"): it ends the main process, opens Saruki again, then ends itself.
 */
public final class Restart extends Activity {
    private static final String PID = "pid";

    static void now(Activity from) {
        Intent i = new Intent(from, Restart.class);
        i.putExtra(PID, Process.myPid());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        from.startActivity(i);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pid = getIntent().getIntExtra(PID, -1);
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid);
        Context c = getApplicationContext();
        Intent main = new Intent(c, BrowserActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(main);
        finish();
        Runtime.getRuntime().exit(0);
    }
}
