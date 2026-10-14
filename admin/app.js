// Hinterlay owner-only admin. Firebase JS modular SDK (CDN), no build step.
import { initializeApp } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js";
import { getAuth, GoogleAuthProvider, signInWithPopup, signOut, onAuthStateChanged }
  from "https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js";
import { getFirestore, collection, doc, addDoc, setDoc, updateDoc, deleteDoc, getDocs, query, where,
  orderBy, getCountFromServer, documentId, Timestamp, serverTimestamp }
  from "https://www.gstatic.com/firebasejs/11.0.2/firebase-firestore.js";
import { getStorage, ref as sRef, uploadBytes, getDownloadURL, deleteObject }
  from "https://www.gstatic.com/firebasejs/11.0.2/firebase-storage.js";
import { getFunctions, httpsCallable } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-functions.js";

/* ===================== CONFIGURE ME ===================== */
// Only this Google account may use the panel (also enforced in firestore.rules,
// storage.rules and functions/index.js - keep all four in sync).
const OWNER_EMAIL = "REPLACE_ME@example.com";

// Firebase console > Project settings > Your apps > Web app > SDK config.
const firebaseConfig = {
  apiKey: "REPLACE_ME",
  authDomain: "REPLACE_ME.firebaseapp.com",
  projectId: "REPLACE_ME",
  storageBucket: "REPLACE_ME.appspot.com",
  messagingSenderId: "REPLACE_ME",
  appId: "REPLACE_ME",
};
/* ======================================================== */

const FONTS = ["SANS","SERIF","CONDENSED","MONO","BEBAS","ANTON","BANGERS","LILITA","LUCKIEST","CHEWY","BUNGEE","BOWLBY","RIGHTEOUS","ABRIL","SHRIKHAND","LOBSTER","PACIFICO","MARKER","PIXEL","ARCHIVO","ALFA","SIGMAR","PASSION","YESEVA","KAUSHAN","CAVEAT","RYE","BLACKOPS","FASCINATE","MONOTON","BUNGEE_SHADE","RUBIK_MONO"];
const CASES = ["AS_TYPED","UPPER","LOWER","TITLE"];
const ACTIONS = [["", "(none)"],["create","Create"],["inspire","Inspire"],["stickers","Stickers"],["support","Support"],["wallpaper","Wallpaper"],["url","Open URL..."]];

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;" }[c]));
const DAY = 86400000;

let app, auth, db, storage, functions;
let demo = false; // sample-data preview, used before Firebase is configured
let charts = {};
let cardCache = new Map();

/* ---------- init ---------- */
const configured = !JSON.stringify(firebaseConfig).includes("REPLACE_ME") && !OWNER_EMAIL.includes("REPLACE_ME");
const pid = firebaseConfig.projectId;
$("lnkAnalytics").href = `https://console.firebase.google.com/project/${pid}/analytics`;
$("lnkCrash").href = `https://console.firebase.google.com/project/${pid}/crashlytics`;
$("lnkMsg").href = `https://console.firebase.google.com/project/${pid}/messaging`;

fillSelect($("inspFont"), FONTS.map((f) => [f, f]));
fillSelect($("inspCase"), CASES.map((c) => [c, c]));
fillSelect($("cardAction"), ACTIONS);
fillSelect($("pushAction"), ACTIONS);

function fillSelect(el, opts) { el.innerHTML = opts.map(([v, l]) => `<option value="${esc(v)}">${esc(l)}</option>`).join(""); }

function toast(msg, isErr = false) {
  const t = $("toast");
  t.textContent = msg; t.className = "toast" + (isErr ? " err" : ""); t.hidden = false;
  clearTimeout(toast.t); toast.t = setTimeout(() => (t.hidden = true), isErr ? 7000 : 3000);
}
const errMsg = (e) => (e && (e.message || e.code)) || String(e);

function showGate(msg, canSignOut = false) {
  $("shell").hidden = true; $("gate").hidden = false;
  $("gateMsg").textContent = msg;
  $("signInBtn").hidden = canSignOut; $("gateSignOutBtn").hidden = !canSignOut;
}

if (!configured) {
  showGate("Not configured yet: set OWNER_EMAIL and firebaseConfig in admin/app.js (see docs/SETUP_FIREBASE.md). Meanwhile you can look around with sample data.");
  $("signInBtn").disabled = true;
  $("demoBtn").hidden = false;
  $("demoBtn").onclick = startDemo;
} else {
  app = initializeApp(firebaseConfig);
  auth = getAuth(app); db = getFirestore(app); storage = getStorage(app); functions = getFunctions(app);

  $("signInBtn").onclick = async () => {
    try { await signInWithPopup(auth, new GoogleAuthProvider()); } catch (e) { toast("Sign-in failed: " + errMsg(e), true); }
  };
  $("signOutBtn").onclick = $("gateSignOutBtn").onclick = () => signOut(auth);

  onAuthStateChanged(auth, (user) => {
    if (!user) return showGate("Owner-only console. Sign in with the owner Google account.");
    // UI check only; the real enforcement is in the security rules and the Cloud Function.
    if (user.email !== OWNER_EMAIL || !user.emailVerified) {
      return showGate(`${user.email || "This account"} is not authorized.`, true);
    }
    $("gate").hidden = true; $("shell").hidden = false;
    $("userEmail").textContent = user.email;
    loadDashboard(); loadInspirations(); loadCards(); loadSubmissions();
  });
}

/* ---------- tabs ---------- */
document.querySelectorAll(".tab").forEach((b) => (b.onclick = () => {
  document.querySelectorAll(".tab").forEach((x) => x.classList.toggle("active", x === b));
  document.querySelectorAll(".panel").forEach((p) => (p.hidden = p.id !== "tab-" + b.dataset.tab));
}));

/* ---------- dashboard ---------- */
const dayKey = (d) => `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, "0")}${String(d.getDate()).padStart(2, "0")}`;
const dayLabel = (d) => `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const startOfDay = (ms) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); };
const pct = (a, b) => (b > 0 ? (100 * a / b).toFixed(1) + "%" : "n/a");

$("refreshBtn").onclick = loadDashboard;

async function loadDashboard() {
  $("dashStatus").textContent = "Loading...";
  try {
    const now = Date.now();
    const today0 = startOfDay(now);
    const since = Timestamp.fromMillis(today0 - 29 * DAY);

    const [totalSnap, newSnap, activeSnap, statSnap] = await Promise.all([
      getCountFromServer(collection(db, "users")),
      getDocs(query(collection(db, "users"), where("firstSeen", ">=", since))),
      getDocs(query(collection(db, "users"), where("lastSeen", ">=", since))),
      getDocs(query(collection(db, "stats"), where(documentId(), ">=", dayKey(new Date(today0 - 29 * DAY))))),
    ]);

    const days = [];
    for (let i = 29; i >= 0; i--) days.push(new Date(today0 - i * DAY));

    // new users per day (from firstSeen)
    const newByDay = Object.fromEntries(days.map((d) => [dayKey(d), 0]));
    const cohort = [];
    newSnap.forEach((s) => {
      const u = s.data(); const f = u.firstSeen?.toMillis?.(); if (!f) return;
      const k = dayKey(new Date(f)); if (k in newByDay) newByDay[k]++;
      cohort.push({ f, l: u.lastSeen?.toMillis?.() ?? f });
    });

    // DAU / WAU / MAU from lastSeen (last 24h / 7d / 30d rolling)
    let dau = 0, wau = 0, mau = 0;
    activeSnap.forEach((s) => {
      const l = s.data().lastSeen?.toMillis?.(); if (!l) return;
      const age = now - l;
      if (age <= 30 * DAY) mau++;
      if (age <= 7 * DAY) wau++;
      if (age <= DAY) dau++;
    });

    // retention estimates
    const ret = (n) => {
      const eligible = cohort.filter((c) => now - c.f >= n * DAY);
      const kept = eligible.filter((c) => c.l - c.f >= n * DAY);
      return { kept: kept.length, total: eligible.length };
    };
    const d1 = ret(1), d7 = ret(7);

    // stats
    const stats = {};
    statSnap.forEach((s) => (stats[s.id] = s.data()));
    const series = (f) => days.map((d) => stats[dayKey(d)]?.[f] ?? 0);
    const sum = (arr) => arr.reduce((a, b) => a + b, 0);
    const exportsArr = series("exports");
    const todayStat = stats[dayKey(new Date(today0))] || {};

    const kpis = [
      ["Total installs", totalSnap.data().count, "users docs"],
      ["New today", newByDay[dayKey(new Date(today0))], "first seen"],
      ["New (30d)", sum(Object.values(newByDay)), ""],
      ["DAU", dau, "last 24h"],
      ["WAU", wau, "last 7d"],
      ["MAU", mau, "last 30d"],
      ["Stickiness", pct(dau, mau), "DAU / MAU"],
      ["D1 retention", pct(d1.kept, d1.total), `est., n=${d1.total}`],
      ["D7 retention", pct(d7.kept, d7.total), `est., n=${d7.total}`],
      ["Exports today", todayStat.exports ?? 0, ""],
      ["Exports (30d)", sum(exportsArr), ""],
      ["Exports / active user", mau ? (sum(exportsArr) / mau).toFixed(2) : "n/a", "30d exports / MAU"],
      ["Opens (30d)", sum(series("opens")), ""],
      ["Purchases (30d)", sum(series("purchases")), ""],
    ];
    $("kpis").innerHTML = kpis.map(([l, v, s]) => `<div class="kpi"><div class="l">${esc(l)}</div><div class="v">${esc(v)}</div><div class="s">${esc(s)}</div></div>`).join("");

    const labels = days.map(dayLabel);
    drawChart("chNew", "bar", labels, [["New users", days.map((d) => newByDay[dayKey(d)])]]);
    drawChart("chOpens", "line", labels, [["Opens", series("opens")], ["Exports", exportsArr]]);
    drawChart("chFeat", "line", labels, [["Stickers", series("stickers")], ["Wallpapers", series("wallpapers")], ["Shares", series("shares")]]);
    drawChart("chMon", "bar", labels, [["Inspiration uses", series("inspirationUses")], ["Purchases", series("purchases")]]);

    $("dashStatus").textContent = `Updated ${new Date().toLocaleTimeString()}.`;
  } catch (e) {
    console.error(e);
    $("dashStatus").textContent = "Could not load dashboard: " + errMsg(e) + " (if it mentions an index, open the link in the console message to create it).";
  }
}

const PALETTE = ["#5b4bff", "#12b886", "#f59f00", "#e64980"];
function drawChart(id, type, labels, sets) {
  if (typeof Chart === "undefined") return;
  charts[id]?.destroy();
  charts[id] = new Chart($(id), {
    type,
    data: { labels, datasets: sets.map(([label, data], i) => ({ label, data, backgroundColor: PALETTE[i % 4], borderColor: PALETTE[i % 4], tension: 0.25, pointRadius: 2 })) },
    options: { responsive: true, maintainAspectRatio: false, interaction: { mode: "index", intersect: false },
      scales: { y: { beginAtZero: true, ticks: { precision: 0 } } }, plugins: { legend: { display: sets.length > 1 } } },
  });
}

/* ---------- upload helper ---------- */
async function uploadImage(file, folder) {
  if (!file) return null;
  if (!file.type.startsWith("image/")) throw new Error("File must be an image.");
  if (file.size >= 10 * 1024 * 1024) throw new Error("Image must be smaller than 10 MB.");
  const safe = file.name.replace(/[^\w.\-]+/g, "_");
  const r = sRef(storage, `${folder}/${Date.now()}_${safe}`);
  await uploadBytes(r, file, { contentType: file.type });
  return getDownloadURL(r);
}
async function deleteImageByUrl(url) {
  if (!url) return;
  try { await deleteObject(sRef(storage, url)); } catch (e) { console.warn("image delete skipped", e); }
}

/* ---------- inspirations ---------- */
$("inspForm").onsubmit = async (ev) => {
  ev.preventDefault(); if (demo) return toast("Preview mode: connect Firebase to save changes.", true);
  const btn = $("inspSave"); btn.disabled = true; $("inspMsg").textContent = "Saving...";
  try {
    const imageUrl = await uploadImage($("inspFile").files[0], "inspirations");
    const tags = $("inspTags").value.split(",").map((t) => t.trim()).filter(Boolean);
    await addDoc(collection(db, "inspirations"), {
      title: $("inspTitle").value.trim(),
      caption: $("inspCaption").value.trim(),
      imageUrl,
      tags,
      preset: {
        text: $("inspText").value.trim(),
        font: $("inspFont").value,
        color: $("inspColor").value.toUpperCase(),
        bubble: $("inspBubble").checked,
        textCase: $("inspCase").value,
        behindSubject: $("inspBehind").checked,
      },
      createdAt: serverTimestamp(),
      active: $("inspActive").checked,
    });
    $("inspForm").reset(); $("inspMsg").textContent = "";
    toast("Inspiration published."); loadInspirations();
  } catch (e) { $("inspMsg").textContent = ""; toast("Save failed: " + errMsg(e), true); }
  btn.disabled = false;
};

async function loadInspirations() {
  const box = $("inspList"); box.innerHTML = '<span class="muted">Loading...</span>';
  try {
    const snap = await getDocs(query(collection(db, "inspirations"), orderBy("createdAt", "desc")));
    if (snap.empty) { box.innerHTML = '<span class="muted">No inspirations yet.</span>'; return; }
    box.innerHTML = "";
    snap.forEach((d) => {
      const x = d.data(), p = x.preset || {};
      const el = document.createElement("div"); el.className = "item";
      el.innerHTML = `${x.imageUrl ? `<img src="${esc(x.imageUrl)}" alt="" loading="lazy">` : '<div class="ph"></div>'}
        <div class="meta"><b>${esc(x.title)}</b><span>${esc(x.caption)}</span><br>
        <span class="badge">${esc(p.font)}</span><span class="badge">${esc(p.textCase)}</span>${(x.tags || []).map((t) => `<span class="badge">#${esc(t)}</span>`).join("")}</div>
        <div class="actions"><label class="check"><input type="checkbox" ${x.active ? "checked" : ""}> Active</label><button class="btn small danger">Delete</button></div>`;
      el.querySelector("input").onchange = (e) => toggleActive("inspirations", d.id, e.target);
      el.querySelector("button").onclick = async () => {
        if (!confirm(`Delete "${x.title}"? This cannot be undone.`)) return;
        try { await deleteDoc(doc(db, "inspirations", d.id)); await deleteImageByUrl(x.imageUrl); toast("Deleted."); loadInspirations(); }
        catch (e) { toast("Delete failed: " + errMsg(e), true); }
      };
      box.appendChild(el);
    });
  } catch (e) { box.innerHTML = `<span class="muted">Could not load: ${esc(errMsg(e))}</span>`; }
}

async function toggleActive(col, id, input) {
  const want = input.checked;
  try { await updateDoc(doc(db, col, id), { active: want }); toast(want ? "Activated." : "Deactivated."); }
  catch (e) { input.checked = !want; toast("Update failed: " + errMsg(e), true); }
}

/* ---------- submissions ---------- */
$("subRefresh").onclick = () => loadSubmissions();

// Turns the app's saved text layer (meta.json) into an Inspire preset, or null when it cannot be read.
function presetFromMeta(metaText, fallbackTitle) {
  try {
    const L = (JSON.parse(metaText).layers || [])[0];
    if (!L) return null;
    const hex = "#" + ((L.color >>> 0) & 0xFFFFFF).toString(16).padStart(6, "0").toUpperCase();
    return { text: L.text || fallbackTitle, font: L.font || "SANS", color: hex, bubble: !!L.bubble, textCase: L.textCase || "AS_TYPED", behindSubject: L.behindSubject !== false };
  } catch (e) { return null; }
}

async function loadSubmissions() {
  const box = $("subList");
  if (demo) { box.innerHTML = '<span class="muted">Preview mode: nothing to review.</span>'; return; }
  box.innerHTML = '<span class="muted">Loading...</span>';
  try {
    const snap = await getDocs(query(collection(db, "submissions"), where("status", "==", "pending")));
    if (snap.empty) { box.innerHTML = '<span class="muted">Nothing waiting for review.</span>'; return; }
    const rows = snap.docs.map((d) => ({ id: d.id, ...d.data() })).sort((a, b) => (b.createdAt?.seconds || 0) - (a.createdAt?.seconds || 0));
    box.innerHTML = "";
    for (const x of rows) {
      let url = "";
      try { url = await getDownloadURL(sRef(storage, x.imagePath)); } catch (e) { console.warn("image missing", e); }
      const el = document.createElement("div"); el.className = "item";
      el.innerHTML = `${url ? `<a href="${esc(url)}" target="_blank" rel="noopener"><img src="${esc(url)}" alt="" loading="lazy"></a>` : '<div class="ph"></div>'}
        <div class="meta"><b>${esc(x.caption || "(no caption)")}</b>
        <span>${x.handle ? "Credit: " + esc(x.handle) : "No credit name"} &middot; app ${esc(x.appVersion)}</span><br>
        ${x.contactOk && x.email ? `<span class="badge">Open to contact</span> <a href="mailto:${esc(x.email)}">${esc(x.email)}</a>` : '<span class="badge">No contact</span>'}</div>
        <div class="actions"><button class="btn small primary" data-a="feature">Feature in Inspire</button><button class="btn small" data-a="ok">Approve only</button><button class="btn small danger" data-a="no">Reject</button></div>`;
      const setStatus = async (status) => { await updateDoc(doc(db, "submissions", x.id), { status }); loadSubmissions(); };
      el.querySelector('[data-a="ok"]').onclick = async () => { try { await setStatus("approved"); toast("Approved."); } catch (e) { toast("Failed: " + errMsg(e), true); } };
      el.querySelector('[data-a="no"]').onclick = async () => {
        if (!confirm("Reject this entry?")) return;
        try { await setStatus("rejected"); toast("Rejected."); } catch (e) { toast("Failed: " + errMsg(e), true); }
      };
      el.querySelector('[data-a="feature"]').onclick = async () => {
        if (!url) return toast("The image could not be loaded.", true);
        try {
          const title = x.caption || "Community pick";
          await addDoc(collection(db, "inspirations"), {
            title, caption: x.handle ? "By " + x.handle : "", imageUrl: url, tags: ["Community"],
            preset: presetFromMeta(x.meta, title) || { text: title, font: "SANS", color: "#FFFFFF", bubble: false, textCase: "AS_TYPED", behindSubject: true },
            createdAt: serverTimestamp(), active: true, fromSubmission: x.id,
          });
          await setStatus("featured");
          toast("Featured in Inspire."); loadInspirations();
        } catch (e) { toast("Failed: " + errMsg(e), true); }
      };
      box.appendChild(el);
    }
  } catch (e) { box.innerHTML = `<span class="muted">Could not load: ${esc(errMsg(e))}</span>`; }
}

/* ---------- cards ---------- */
const toLocalInput = (ms) => { const d = new Date(ms - new Date(ms).getTimezoneOffset() * 60000); return d.toISOString().slice(0, 16); };

function resetCardForm() {
  $("cardForm").reset(); $("cardId").value = ""; $("cardFormTitle").textContent = "New card"; $("cardMsg").textContent = "";
}
$("cardReset").onclick = resetCardForm;

$("cardForm").onsubmit = async (ev) => {
  ev.preventDefault(); if (demo) return toast("Preview mode: connect Firebase to save changes.", true);
  const btn = $("cardSave"); btn.disabled = true; $("cardMsg").textContent = "Saving...";
  try {
    const existing = cardCache.get($("cardId").value);
    let imageUrl = $("cardImage").value.trim() || null;
    const uploaded = await uploadImage($("cardFile").files[0], "cards");
    if (uploaded) imageUrl = uploaded;

    let ctaAction = $("cardAction").value || null;
    if (ctaAction === "url") {
      const u = $("cardUrl").value.trim();
      if (!/^https:\/\/\S+$/.test(u)) throw new Error("Enter an https:// URL for the button action.");
      ctaAction = "url:" + u;
    }
    const exp = $("cardExpires").value;
    const data = {
      title: $("cardTitle").value.trim(),
      body: $("cardBody").value.trim(),
      imageUrl,
      ctaLabel: $("cardCta").value.trim() || null,
      ctaAction,
      priority: Number($("cardPriority").value) || 0,
      active: $("cardActive").checked,
      expiresAt: exp ? Timestamp.fromDate(new Date(exp)) : null,
    };
    if (data.ctaLabel && !data.ctaAction) throw new Error("A button label needs a button action.");
    if (existing) await updateDoc(doc(db, "cards", $("cardId").value), data);
    else await addDoc(collection(db, "cards"), { ...data, createdAt: serverTimestamp() });
    resetCardForm(); toast("Card saved."); loadCards();
  } catch (e) { $("cardMsg").textContent = ""; toast("Save failed: " + errMsg(e), true); }
  btn.disabled = false;
};

function editCard(id) {
  const c = cardCache.get(id); if (!c) return;
  $("cardId").value = id; $("cardFormTitle").textContent = "Edit card";
  $("cardTitle").value = c.title || ""; $("cardBody").value = c.body || ""; $("cardImage").value = c.imageUrl || "";
  $("cardCta").value = c.ctaLabel || ""; $("cardPriority").value = c.priority ?? 0; $("cardActive").checked = !!c.active;
  const a = c.ctaAction || "";
  if (a.startsWith("url:")) { $("cardAction").value = "url"; $("cardUrl").value = a.slice(4); } else { $("cardAction").value = a; $("cardUrl").value = ""; }
  $("cardExpires").value = c.expiresAt ? toLocalInput(c.expiresAt.toMillis()) : "";
  $("cardForm").scrollIntoView({ behavior: "smooth" });
}

async function loadCards() {
  const box = $("cardList"); box.innerHTML = '<span class="muted">Loading...</span>';
  try {
    const snap = await getDocs(query(collection(db, "cards"), orderBy("priority", "desc")));
    cardCache = new Map();
    if (snap.empty) { box.innerHTML = '<span class="muted">No cards yet.</span>'; return; }
    box.innerHTML = "";
    snap.forEach((d) => {
      const c = d.data(); cardCache.set(d.id, c);
      const exp = c.expiresAt?.toMillis?.();
      const expired = exp && exp < Date.now();
      const el = document.createElement("div"); el.className = "item";
      el.innerHTML = `${c.imageUrl ? `<img src="${esc(c.imageUrl)}" alt="" loading="lazy">` : '<div class="ph"></div>'}
        <div class="meta"><b>${esc(c.title)}</b><span>${esc(c.body)}</span><br>
        <span class="badge">priority ${esc(c.priority)}</span>${c.ctaAction ? `<span class="badge">${esc(c.ctaLabel || "")} &rarr; ${esc(c.ctaAction)}</span>` : ""}
        ${exp ? `<span class="badge ${expired ? "" : "on"}">${expired ? "expired" : "expires"} ${esc(new Date(exp).toLocaleString())}</span>` : ""}</div>
        <div class="actions"><label class="check"><input type="checkbox" ${c.active ? "checked" : ""}> Active</label><button class="btn small" data-a="edit">Edit</button><button class="btn small danger" data-a="del">Delete</button></div>`;
      el.querySelector("input").onchange = (e) => toggleActive("cards", d.id, e.target);
      el.querySelector('[data-a="edit"]').onclick = () => editCard(d.id);
      el.querySelector('[data-a="del"]').onclick = async () => {
        if (!confirm(`Delete card "${c.title}"?`)) return;
        try { await deleteDoc(doc(db, "cards", d.id)); toast("Deleted."); loadCards(); } catch (e) { toast("Delete failed: " + errMsg(e), true); }
      };
      box.appendChild(el);
    });
  } catch (e) { box.innerHTML = `<span class="muted">Could not load: ${esc(errMsg(e))}</span>`; }
}

/* ---------- push ---------- */
$("pushForm").onsubmit = async (ev) => {
  ev.preventDefault(); if (demo) return toast("Preview mode: connect Firebase to save changes.", true);
  let action = $("pushAction").value;
  if (action === "url") {
    const u = $("pushUrl").value.trim();
    if (!/^https:\/\/\S+$/.test(u)) return toast("Enter an https:// URL for the action.", true);
    action = "url:" + u;
  }
  const title = $("pushTitle").value.trim(), body = $("pushBody").value.trim();
  if (!confirm(`Send to ALL users?\n\n${title}\n${body}`)) return;
  const btn = $("pushSend"); btn.disabled = true; $("pushMsg").textContent = "Sending...";
  try {
    const res = await httpsCallable(functions, "sendPush")({ title, body, action, imageUrl: $("pushImage").value.trim() });
    $("pushMsg").textContent = "Sent. Message id: " + res.data.messageId;
    toast("Push sent.");
  } catch (e) {
    $("pushMsg").textContent = "";
    const notDeployed = e?.code === "functions/not-found" || e?.code === "functions/internal" && /not.?found|404/i.test(e.message || "");
    toast(notDeployed ? "sendPush is not deployed yet. Use Firebase Console > Messaging (topic all)." : "Push failed: " + errMsg(e), true);
  }
  btn.disabled = false;
};

/* ---------- preview mode (sample data, no Firebase) ---------- */
function startDemo() {
  demo = true;
  $("gate").hidden = true; $("shell").hidden = false;
  $("userEmail").textContent = "Preview (sample data)";
  const banner = document.createElement("div");
  banner.className = "note";
  banner.innerHTML = "<strong>Preview mode.</strong> Everything below is sample data so you can see how the console will look. Nothing is saved. Connect Firebase (docs/SETUP_FIREBASE.md) to use it for real.";
  document.querySelector("main.content").prepend(banner);

  const today0 = startOfDay(Date.now());
  const days = Array.from({ length: 30 }, (_, i) => new Date(today0 - (29 - i) * DAY));
  const wave = (i, base, amp, seed) => Math.max(0, Math.round(base + amp * Math.sin(i / 3 + seed) + i * base * 0.02));
  const series = (base, amp, seed) => days.map((_, i) => wave(i, base, amp, seed));
  const newUsers = series(22, 8, 1);
  const opens = series(140, 40, 2);
  const exportsArr = series(60, 20, 3);
  const purch = days.map((_, i) => (i % 6 === 0 ? 1 : 0));
  const sum = (a) => a.reduce((x, y) => x + y, 0);
  const total = sum(newUsers) + 1200, mau = 780, wau = 410, dau = 160;
  const kpis = [
    ["Total installs", total, "sample"], ["New today", newUsers[29], ""], ["New (30d)", sum(newUsers), ""],
    ["DAU", dau, "last 24h"], ["WAU", wau, "last 7d"], ["MAU", mau, "last 30d"],
    ["Stickiness", pct(dau, mau), "DAU / MAU"], ["D1 retention", "41.0%", "est."], ["D7 retention", "18.5%", "est."],
    ["Exports today", exportsArr[29], ""], ["Exports (30d)", sum(exportsArr), ""],
    ["Exports / active user", (sum(exportsArr) / mau).toFixed(2), "30d exports / MAU"],
    ["Opens (30d)", sum(opens), ""], ["Purchases (30d)", sum(purch), ""],
  ];
  $("kpis").innerHTML = kpis.map(([l, v, s]) => `<div class="kpi"><div class="l">${esc(l)}</div><div class="v">${esc(v)}</div><div class="s">${esc(s)}</div></div>`).join("");
  const labels = days.map(dayLabel);
  drawChart("chNew", "bar", labels, [["New users", newUsers]]);
  drawChart("chOpens", "line", labels, [["Opens", opens], ["Exports", exportsArr]]);
  drawChart("chFeat", "line", labels, [["Stickers", series(18, 7, 4)], ["Wallpapers", series(9, 4, 5)], ["Shares", series(25, 9, 6)]]);
  drawChart("chMon", "bar", labels, [["Inspiration uses", series(30, 10, 7)], ["Purchases", purch]]);
  $("dashStatus").textContent = "Sample data.";

  const sampleInsp = [
    ["Wanderlust", "Big travel energy behind the mountain.", "BEBAS", "UPPER", ["travel"]],
    ["Pop bubble", "Glossy, inflated type that stands out.", "LILITA", "AS_TYPED", ["fun", "bubble"]],
    ["Golden hour", "Warm script for sunset portraits.", "PACIFICO", "LOWER", ["portrait"]],
  ];
  $("inspList").innerHTML = sampleInsp.map(([t, c, f, k, tags]) => `<div class="item"><div class="ph"></div>
    <div class="meta"><b>${esc(t)}</b><span>${esc(c)}</span><br><span class="badge">${esc(f)}</span><span class="badge">${esc(k)}</span>${tags.map((x) => `<span class="badge">#${esc(x)}</span>`).join("")}</div>
    <div class="actions"><label class="check"><input type="checkbox" checked disabled> Active</label></div></div>`).join("");
  $("cardList").innerHTML = `<div class="item"><div class="ph"></div><div class="meta"><b>Tuck text behind anything</b><span>Try the new Erase brush to hide just part of a word.</span><br><span class="badge">priority 10</span><span class="badge">create</span></div>
    <div class="actions"><label class="check"><input type="checkbox" checked disabled> Active</label></div></div>`;
}

// ?demo opens the preview directly. Runs last so every helper above is initialised.
if (!configured && new URLSearchParams(location.search).has("demo")) startDemo();
