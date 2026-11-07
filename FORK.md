# jotterknocker development

Based on https://github.com/mstcgalis/scratchpad-launcher, merged through upstream 2026.10.5.

The package/namespace and preference filenames retain their original identities so existing installations, notes, backups and home slots survive updates. The APK uses the fork signing key from the private backup; never commit signing credentials. The displayed app and repository name is jotterknocker.

Read-mode touches do not reach EditText. Only a confirmed tap enters editing. Vertical drag/fling uses OverScroller and VelocityTracker without reformatting the note. Horizontal gestures retain upstream's note pages/swipe-app behavior. A passive rounded scrollbar is drawn inside the text view, capped at 56dp, with a 12dp minimum and a 180ms fade. Native editing/selection and accessibility remain available.

The app has no EXPAND_STATUS_BAR permission or launcher swipe-down action. Android still handles the top-edge notification gesture.

Home content uses a shared 6dp start inset and no end inset. The note starts 4dp below actual system bars/cutouts; page indicators reserve space only when enabled. Selecting a 0dp bottom gap also removes the last row's label padding and aligns its visible glyphs to the bottom edge. Required system navigation space and visible setup prompts remain protected.

Upstream synchronization is manual, as selected by the repository owner. Review overlapping changes, preserve the fork features, and run checks before publishing. The release workflow tests touch interactions on Android emulators before building an unsigned release for local signing with the existing private key.

The home notification shade is a native monochrome view with event-driven media controls. The notification-listener/media-session design was informed by gezimos/inkOS (GPLv3), inspected at e22f06a48755449a7a53ceb499b66fc4a64ff52b. No KompaktX source is incorporated. Notification contents stay in memory. No utility toggles or network permissions are added. A separate optional accessibility service intercepts only the top edge while home is focused; it cannot retrieve screen contents or remap hardware buttons. The original lock accessibility service stays separate.

Calendar notifications retain their original content and action PendingIntents, including Fossify Calendar Snooze and task actions. Calendar remains responsible for scheduling/delivering reminders. CI installs the official checksum-pinned Fossify Calendar 1.12.1 APK on Android 9, creates real events, waits for their alarms, and checks opening and snoozing through the launcher.
