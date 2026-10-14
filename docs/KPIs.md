# Hinterlay KPIs

"Admin" = the admin panel Dashboard (Firestore `users/` + `stats/`). "Analytics" = Firebase Analytics / Console. Day buckets in `stats/` follow the app's date key (yyyyMMdd).

## App events logged
`onboarding_complete`, `photo_selected`, `export_completed`, `share_tapped`, `sticker_created`, `wallpaper_set`, `inspiration_viewed`, `inspiration_used`, `card_clicked`, `erase_used`, `perspective_used`, `purchase_completed`, `push_opt_in`.
Automatic Firebase events used: `first_open`, `session_start`, `app_remove`, `app_update`, `notification_open`.

| KPI | Definition | Where to read | Events / fields |
|---|---|---|---|
| Installs | New installs of the app | Play Console (store installs); Admin "Total installs" / "New per day" (first-run installs that opened the app) | `first_open`, `users/` docs |
| Uninstalls | Devices that removed the app | Analytics only (not in Firestore); Play Console > Statistics > Uninstalls | `app_remove` |
| DAU / WAU / MAU | Users active in last 1 / 7 / 30 days | Admin (from `users.lastSeen`); Analytics > Active users | `lastSeen`, `session_start` |
| Stickiness | DAU / MAU; 20%+ is healthy for a creative tool | Admin (computed) | |
| Retention D1 / D7 / D30 | % of a cohort returning on day N | Analytics > Retention (authoritative); Admin shows D1/D7 estimates from `firstSeen`/`lastSeen` | |
| Activation rate | New users who export in their first session | Analytics funnel: `first_open` > `photo_selected` > `export_completed` (same session) | `onboarding_complete`, `photo_selected`, `export_completed` |
| Exports per user | Exports / active users | Admin "Exports / active user"; Analytics event count per user | `export_completed`, `stats.exports` |
| Feature adoption | % of active users using a feature | Analytics event "users" counts divided by active users: stickers `sticker_created`, wallpaper `wallpaper_set`, perspective `perspective_used`, erase `erase_used`, inspiration `inspiration_viewed` / `inspiration_used`. Admin charts show daily totals. | |
| Share rate | Shares / exports | Admin (`stats.shares` / `stats.exports`); Analytics | `share_tapped` |
| Invite rate | Users who invite others / active users | Analytics (add an invite event or use `share_tapped` with a `type=invite` param if the app sends it) | `share_tapped` |
| Live card CTR | Card clicks / card impressions | Analytics | `card_clicked` |
| Purchase conversion | Buyers (ad-free / lifetime) / active users | Admin (`stats.purchases` vs MAU); Play Console > Monetize > Products; Analytics | `purchase_completed` |
| ARPU / ARPPU | Revenue / active users; revenue / paying users | Play Console (revenue), AdMob (ad revenue) divided by MAU | |
| Ad revenue / eCPM | Ad earnings | AdMob | |
| Push opt-in rate | Users who granted notifications / users | Analytics | `push_opt_in` |
| Push open rate | Notification opens / sends | Firebase Console > Messaging (reports); Analytics | `notification_open` |
| Crash-free users | % of users without a crash | Firebase Crashlytics; Play Console > Android vitals | |

## Privacy and consent
- The app stores an anonymous install id (Firebase Anonymous Auth uid), app version, SDK level, locale and counts. No name, email or contact data from users.
- Analytics, FCM and Crashlytics are data collection: declare them in the Play Console **Data safety** form (device or other IDs, app interactions, crash logs; purpose: analytics, app functionality) and describe them in the in-app privacy policy, including how users can request deletion of their install id.
- If you serve the EEA/UK, obtain consent before enabling Analytics/ad personalization (consent mode / AdMob UMP) and request the Android 13+ notification permission only with a clear in-app prompt.
- The admin owner Google account is used only for sign-in to the panel.
