package ir.persiansocks.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import java.util.HashSet;
import java.util.Set;

public final class SplitTunnelSettings {
    private SplitTunnelSettings() {}
    public static SplitTunnelPolicy load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("persiansocks", Context.MODE_PRIVATE);
        SplitTunnelPolicy.Mode mode;
        try { mode = SplitTunnelPolicy.Mode.valueOf(prefs.getString("split-mode", "ALL")); }
        catch (IllegalArgumentException e) { mode = SplitTunnelPolicy.Mode.ALL; }
        return new SplitTunnelPolicy(mode, new HashSet<>(prefs.getStringSet("split-packages", java.util.Collections.emptySet())), context.getPackageName());
    }
    public static void save(Context context, SplitTunnelPolicy.Mode mode, Set<String> packages, boolean showAll) {
        SplitTunnelPolicy clean = new SplitTunnelPolicy(mode, packages, context.getPackageName());
        context.getSharedPreferences("persiansocks", Context.MODE_PRIVATE).edit()
            .putString("split-mode", mode.name()).putStringSet("split-packages", new HashSet<>(clean.packages))
            .putBoolean("split-show-all", showAll).apply();
    }
    public static boolean installed(PackageManager manager, String name) {
        try { return manager.getApplicationInfo(name, 0).enabled; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
    public static void apply(Context context, VpnService.Builder builder) throws Exception {
        PackageManager manager = context.getPackageManager();
        (AndroidRoutes.mode(context).equals("chatgpt")?new SplitTunnelPolicy(SplitTunnelPolicy.Mode.ONLY_SELECTED,java.util.Collections.singleton("com.openai.chatgpt"),context.getPackageName()):load(context)).apply(context.getPackageName(), new SplitTunnelPolicy.Rules() {
            public boolean installed(String name) { return SplitTunnelSettings.installed(manager, name); }
            public boolean allow(String name) throws Exception {
                try { builder.addAllowedApplication(name); return true; }
                catch (PackageManager.NameNotFoundException e) { return false; }
            }
            public boolean exclude(String name) throws Exception {
                try { builder.addDisallowedApplication(name); return true; }
                catch (PackageManager.NameNotFoundException e) { return false; }
            }
        });
    }
}
