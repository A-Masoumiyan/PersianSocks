package ir.persiansocks.app;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable snapshot for one VPN connection. Empty allow lists must never become full tunnelling. */
public final class SplitTunnelPolicy {
    public enum Mode { ALL, ONLY_SELECTED, EXCEPT_SELECTED }
    public interface Rules {
        boolean installed(String name);
        boolean allow(String name) throws Exception;
        boolean exclude(String name) throws Exception;
    }
    public final Mode mode;
    public final Set<String> packages;
    public SplitTunnelPolicy(Mode mode, Collection<String> packages, String self) {
        this.mode = mode;
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (packages != null) for (String name : packages)
            if (name != null && !name.trim().isEmpty() && !name.equals(self)) clean.add(name);
        this.packages = java.util.Collections.unmodifiableSet(clean);
    }
    public int apply(String self, Rules rules) throws Exception {
        if (mode == Mode.ALL) return 0;
        int applied = 0;
        for (String name : packages) {
            if (!rules.installed(name)) continue;
            if (mode == Mode.ONLY_SELECTED ? rules.allow(name) : rules.exclude(name)) applied++;
        }
        if (mode == Mode.ONLY_SELECTED) {
            if (applied == 0) throw new IllegalArgumentException("برای حالت «فقط انتخاب‌شده‌ها»، حداقل یک برنامهٔ نصب‌شده انتخاب کن.");
            // Internal tunnel verification remains possible; scan sockets are protected separately.
            if (!rules.allow(self)) throw new IllegalStateException("دسترسی آزمون داخلی VPN برقرار نشد");
        }
        return applied;
    }
    public static boolean visible(boolean system, boolean updatedSystem, boolean showAll) {
        return showAll || !(system || updatedSystem);
    }
}
