# Android 0.28.2 — source dialog buttons

The three protocol checkboxes previously inherited RTL layout while their ASCII labels resolved LTR, leaving the mark and label visually separated. Each is now one bounded 48dp checkable target with a fixed internal LTR mark/label pair, a selected background and an Android CheckBox accessibility state. The outer row follows the chosen UI language, with spacing between targets. Multi-selection, stored protocol bitmasks and TLS-only restrictions are unchanged.

Save and Cancel are explicit equal-width themed buttons inside the dialog. Save keeps validation failures open; Cancel dismisses without saving. The URL editor is LTR with a 14sp hint and text. Shared text buttons clear native tint and elevation animation to preserve their custom background.

Validation: offline Java/resource APK build and signed APK integrity audit passed; 520 bilingual resource checks passed. No Android device or emulator is available in this session, so visual validation on the user's Samsung phone remains pending.
