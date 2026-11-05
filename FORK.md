# jotterknocker development

Based on https://github.com/mstcgalis/scratchpad-launcher, merged through upstream 2026.10.5.

The package/namespace and preference filenames retain their original identities so existing installations, notes, backups and home slots survive updates. The APK uses the fork signing key from the private backup; never commit signing credentials. The displayed app and repository name is jotterknocker.

Read-mode touches do not reach EditText. Only a confirmed tap enters editing. Vertical drag/fling uses OverScroller and VelocityTracker without reformatting the note. Horizontal gestures retain upstream's note pages/swipe-app behavior. A passive rounded scrollbar is drawn inside the text view, capped at 56dp, with a 12dp minimum and a 180ms fade. Native editing/selection and accessibility remain available.

The app has no EXPAND_STATUS_BAR permission or launcher swipe-down action. Android still handles the top-edge notification gesture.

Upstream synchronization is manual, as selected by the repository owner. Review overlapping changes, preserve the fork features, and run checks before publishing. The release workflow tests touch interactions on Android emulators before building an unsigned release for local signing with the existing private key.
