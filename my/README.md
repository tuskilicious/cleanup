# My Mi TV (MiTV-AXSO0, Android 9)

The setup on this TV, to put back after a reset or a firmware update. Set `TV_HOST` to the TV's address and switch
USB debugging on first.

| What | Apply | Undo |
|---|---|---|
| Debloat list (from `examples/xiaomi-mitv-axso0`, keeping Assistant, Chromecast and FanCode) | `tools/disable.sh my/debloat.txt` | `tools/restore.sh my/debloat.txt` |
| Launcher, with its background helper for power-on Home | `HOME_KEY_SERVICE=1 launcher/build-and-install.sh` | `tools/restore.sh my/debloat.txt` |
| Theme: 14 Unsplash photos, six columns, glass dock, app glow, Diwali until 12 Nov | `tools/theme.sh my/theme` | `tools/theme.sh --reset` |
| Add-ons: Sleep Timer, Quiet Hours (35 % cap 20:00 to 07:00), Headphones, Brightness, Sources, Photos | each one's `install.sh` or `build-and-install.sh` in `addons/` | `--uninstall` |
| Photos screensaver, the same 14 pictures | `addons/photo-screensaver/sync.sh my/theme/wallpapers` | `sync.sh --off` |
| Animations at 0.5x | `tools/tv.py 'settings put global window_animation_scale 0.5'` (and `transition_`, `animator_duration_`) | the same with `1.0` |

Reinstalling the launcher while its helper is on can trip an Android 9 accessibility bug that restarts the
system: switch the helpers off first (`settings delete secure enabled_accessibility_services`), install, then put
the list back.

Firmware updates are off (`com.xiaomi.mitv.updateservice` in the list); re-enable it to check for one.
