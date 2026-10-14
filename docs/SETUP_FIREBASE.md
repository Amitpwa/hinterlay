# Firebase setup for Hinterlay

Follow in order. Everything admin-related lives in `admin/`.

## 1. Create the project
1. Go to https://console.firebase.google.com > **Add project** > name it `hinterlay` (Google Analytics: **enabled**).
2. Note the **Project ID** (Project settings > General).

## 2. Add the Android apps
Project settings > **Your apps** > Android icon. Add **two** apps:
- `com.hinterlay.app` (release)
- `com.hinterlay.app.debug` (debug)

Download `google-services.json` (it contains both clients once both apps exist; re-download after adding the second) and place it at `app/google-services.json`. Do not commit it if the repo is public.

### SHA fingerprints (needed for Google sign-in and some Firebase features)
- Debug key: `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android`
- Release key: same command with your release keystore and alias (or Play Console > App integrity > App signing for the Play-managed key).
- Add the **SHA-1 and SHA-256** of each under Project settings > Your apps > the matching app > **Add fingerprint**. Re-download `google-services.json` afterwards.

## 3. Enable services (Build menu)
- **Authentication** > Sign-in method: enable **Anonymous** (used by the app as install id) and **Google** (used by the admin panel; set a support email).
  Authentication > Settings > Authorized domains: make sure `<project-id>.web.app` is listed (it is by default).
- **Firestore Database** > Create database > production mode > pick a region close to your users.
- **Storage** > Get started > production mode. (Storage may require upgrading to Blaze in newer projects.)
- **Cloud Messaging**: enabled by default. The app subscribes to topic `all`.

## 4. Register the web admin app
Project settings > Your apps > **Web** (`</>`) > nickname `hinterlay-admin` (no hosting wizard needed). Copy the `firebaseConfig` values.

## 5. Set OWNER_EMAIL (4 places - they must match exactly)
Use the Google account you will sign in with (lowercase).
1. `admin/app.js` - `OWNER_EMAIL` and the `firebaseConfig` object (replace every `REPLACE_ME`).
2. `admin/firestore.rules` - `OWNER_EMAIL()` function.
3. `admin/storage.rules` - `OWNER_EMAIL()` function.
4. `admin/functions/index.js` - `OWNER_EMAIL` constant.

Also put your project id in `admin/.firebaserc`.

## 6. Deploy
Install Node 20+ and the CLI once: `npm install -g firebase-tools`. Then:

```
cd admin
firebase login
firebase deploy --only firestore:rules,storage
firebase deploy --only hosting
```
(`firebase deploy --only storage` deploys the Storage rules.)

Functions (needs the **Blaze** pay-as-you-go plan; usage for a small app is normally within the free tier, set a budget alert):
```
cd functions
npm install
cd ..
firebase deploy --only functions
```
The admin panel is then at `https://<project-id>.web.app`.

Firestore will ask you to create composite/single-field indexes only if a query needs one; the console error contains a link that creates it in one click.

## 7. Send your first push
**Without functions (works on the free plan):** Firebase Console > Engage > **Messaging** > New campaign > Notifications. Enter title/text, **Target: Topic `all`**. Under *Additional options > Custom data* add keys `title`, `body`, `action` (e.g. `create`, `inspire`, `stickers`, `support`, `wallpaper`, or `url:https://...`) and `imageUrl` with the same values. Send.

**With the admin panel:** open the Push tab, fill the form, Send. The function sends the same notification and data keys.

## 8. Verify
- Open the admin site, sign in with the owner account: dashboard loads. A different Google account must see "not authorized" and be denied by the rules.
- Run the app: a doc appears in Firestore `users/<uid>` and `stats/<yyyyMMdd>` counters increase.
- Create an inspiration and a live card in the panel; both appear in the app.
- Firebase Console > Analytics > **DebugView** shows events while the debug app runs with `adb shell setprop debug.firebase.analytics.app com.hinterlay.app.debug`.
- Firestore > Rules > **Rules playground**: simulate an unauthenticated read of `users/x` (must be denied) and a signed-in write to `stats/20260101` with `exports: 5` (must be denied).

Uninstalls (`app_remove`) appear in Analytics only after Analytics collects data (up to ~24h); Play Console shows store installs.
