// فحص hook.js خارج الجهاز (Node 22): يحاكي window وsessionStorage وIBHNative ويتأكد من السلوك الأربعة.
// التشغيل: node tests/hook.test.js
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm"), assert = require("assert");
const SECRET = "f".repeat(64);
const tpl = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/hook.js"), "utf8");
assert.ok(tpl.includes("__IBH_SECRET__"));
const code = tpl.replace("__IBH_SECRET__", SECRET);

class Storage { constructor() { this.m = new Map(); } getItem(k) { return this.m.has(k) ? this.m.get(k) : null; }
  setItem(k, v) { this.m.set(k, String(v)); } removeItem(k) { this.m.delete(k); } clear() { this.m.clear(); } }

function env(origin) {
  const calls = [], sent = [];
  const listeners = {};
  const doc = { title: "كشف", visibilityState: "visible", addEventListener: (t, f) => (listeners[t] = listeners[t] || []).push(f) };
  const win = {
    location: { origin, href: origin + "/#home" },
    // الجسر الأصلي: يرفض أي استدعاء بلا السر الصحيح (كما في NativeBridge.kt)
    __ibhN: (() => { const g = s => { if (s !== SECRET) { calls.push(["REJECTED"]); return false; } return true; };
      return { tokenSet: (s, t) => g(s) && calls.push(["set", t]), tokenClear: s => g(s) && calls.push(["clear"]), print: (s, t) => g(s) && calls.push(["print", t]),
        keepScreenOn: (s, b) => g(s) && calls.push(["keep", b]), saveFile: (s, b, n, m) => g(s) && (calls.push(["save", b, n, m]), true),
        openBell: s => g(s) && calls.push(["bell"]), openAlerts: s => g(s) && calls.push(["alerts"]), setBellToken: (s, t) => g(s) && (calls.push(["bt", t]), true), appVersion: () => "1.0.1" }; })(),
    fetch: (input, init) => { sent.push([input, init]); return Promise.resolve({ ok: true }); },
    dispatchEvent: e => calls.push(["event", e.type]),
  };
  const nav = {};
  const ss = new Storage(), ls = new Storage();
  const ctx = { window: win, document: doc, navigator: nav, location: win.location, sessionStorage: ss, localStorage: ls, Storage,
    URL, Headers, Request, Promise, Event: class { constructor(t) { this.type = t; } }, TypeError, Error, String, Object };
  win.sessionStorage = ss; win.localStorage = ls;
  win.window = win; win.navigator = nav; win.document = doc;
  vm.createContext(ctx);
  return { ctx, win, doc, nav, ss, ls, calls, sent, listeners };
}

(async () => {
  // بلا جلسة: يُمسح الرمز المحفوظ (L3)
  let e0 = env("https://ibh.nafahatsy.com");
  vm.runInContext(code, e0.ctx);
  assert.deepStrictEqual(e0.calls, [["clear"]]);

  // نطاق آخر: لا شيء يُحقن
  let e = env("https://evil.example");
  vm.runInContext(code, e.ctx);
  assert.strictEqual(e.win.__ibhHook, undefined);

  e = env("https://ibh.nafahatsy.com");
  e.ss.setItem("ibh_t", "a".repeat(64)); // جلسة قائمة قبل الحقن تُنسخ فوراً
  vm.runInContext(code, e.ctx);
  vm.runInContext(code, e.ctx); // مرة ثانية لا تكرر شيئاً
  assert.deepStrictEqual(e.calls.shift(), ["set", "a".repeat(64)]);
  assert.strictEqual(e.calls.length, 0);

  // 5) واجهة المنصة: saveFile بثلاثة وسائط كما في saveBlob، والسر لا يظهر على window ولا في نص الدوال
  const api = e.win.IBHNative;
  assert.ok(api && typeof api.saveFile === "function");
  assert.strictEqual(api.saveFile("QUJD", "a.pdf", "application/pdf"), true);
  assert.deepStrictEqual(e.calls.shift(), ["save", "QUJD", "a.pdf", "application/pdf"]);
  api.openBell(); assert.deepStrictEqual(e.calls.shift(), ["bell"]);
  api.openAlerts(); assert.deepStrictEqual(e.calls.shift(), ["alerts"]);
  assert.strictEqual(api.setBellToken("bt_x"), true); assert.deepStrictEqual(e.calls.shift(), ["bt", "bt_x"]);
  assert.strictEqual(api.appVersion(), "1.0.1");
  for (const k of Object.keys(api)) assert.ok(!String(api[k]).includes(SECRET));
  assert.ok(!String(e.win.fetch).includes(SECRET) && !String(e.ctx.Storage.prototype.setItem).includes(SECRET));
  assert.ok(!JSON.stringify(Object.keys(e.win)).includes(SECRET));
  try { e.win.IBHNative = { saveFile: () => "hijack" }; } catch (x) {} // لا تُستبدل
  assert.strictEqual(e.win.IBHNative, api);
  // استدعاء الجسر الأصلي مباشرة بلا السر يُرفض
  e.win.__ibhN.tokenSet("guess", "c".repeat(64)); assert.deepStrictEqual(e.calls.shift(), ["REJECTED"]);
  assert.strictEqual(e.calls.length, 0);

  // 1) الترويسة على /api/ من النطاق نفسه فقط، مع الحفاظ على الترويسات الأصلية
  await e.win.fetch("api/login", { method: "POST", headers: { "Content-Type": "application/json", Authorization: "Bearer x" }, body: "{}" });
  let h = e.sent[0][1].headers;
  assert.strictEqual(h.get("X-IBH-App"), "1"); assert.strictEqual(h.get("Authorization"), "Bearer x"); assert.strictEqual(e.sent[0][1].method, "POST");
  e.location = e.win.location; e.win.location.href = "https://ibh.nafahatsy.com/";
  await e.win.fetch("app.js");
  assert.strictEqual(e.sent[1][1], undefined);
  await e.win.fetch("https://other.example/api/x", {});
  assert.ok(!(e.sent[2][1].headers && e.sent[2][1].headers.get && e.sent[2][1].headers.get("X-IBH-App")));

  // 2) مرآة الرمز
  e.ss.setItem("ibh_t", "b".repeat(64)); e.ls.setItem("ibh_t", "zz"); e.ss.setItem("other", "1");
  e.ss.removeItem("ibh_t"); e.ls.removeItem("ibh_t");
  assert.deepStrictEqual(e.calls.splice(0), [["set", "b".repeat(64)], ["clear"]]);
  assert.strictEqual(e.ss.getItem("other"), "1");

  // 3) الطباعة
  e.win.print();
  assert.deepStrictEqual(e.calls.splice(0), [["event", "beforeprint"], ["print", "كشف"]]);

  // 4) wakeLock
  const wl = e.nav.wakeLock; assert.ok(wl);
  const a = await wl.request("screen"), b = await wl.request("screen");
  let rel = 0; a.addEventListener("release", () => rel++);
  await a.release(); await a.release();
  assert.strictEqual(rel, 1);
  assert.deepStrictEqual(e.calls.splice(0), [["keep", true], ["keep", true], ["keep", true]]); // a سقط وb باقٍ
  await b.release();
  assert.deepStrictEqual(e.calls.splice(0), [["keep", false]]);
  await wl.request("screen");
  e.doc.visibilityState = "hidden"; e.listeners.visibilitychange.forEach(f => f());
  assert.deepStrictEqual(e.calls.splice(0), [["keep", true], ["keep", false]]);
  await assert.rejects(wl.request("screen"));
  console.log("hook.js: كل الفحوص نجحت");
})().catch(err => { console.error(err); process.exit(1); });
