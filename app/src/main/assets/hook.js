/* تطبيق المعهد (الغلاف الأصلي): يُحقن في صفحات https://ibh.nafahatsy.com وحدها بعد بدء كل صفحة. لا يغيّر منطق المنصة، بل:
   1) يضيف ترويسة X-IBH-App: 1 لطلبات /api/ من النطاق نفسه، فيصدر الدخول «جلسة جهاز التطبيق» (auth.php، الدفعة 27).
   2) ينسخ رمز الجلسة (sessionStorage «ibh_t») إلى التخزين الأصلي المشفّر عند الدخول، ويمسحه عند الخروج أو انتهاء الجلسة.
   3) window.print: الطباعة الأصلية (WebView لا يطبع وحده)، مع beforeprint ثم afterprint عند انتهاء نافذة الطباعة.
   4) navigator.wakeLock (غير موجود في WebView): بديل يُبقي الشاشة مضاءة أصلياً ما دام قفل واحد فعّالاً (صفحة الجرس ووضع العرض).
   5) window.IBHNative: واجهة JS للمنصة (saveFile كما تستدعيه saveBlob، وprint، وopenBell، وsetBellToken، وappVersion).
   الجسر الأصلي الحقيقي اسمه __ibhN، وكل دالة فيه تغيّر شيئاً تشترط سرّاً عشوائياً يولّده التطبيق عند كل تشغيل (S أدناه):
   يُكتب في نص هذا الملف قبل حقنه، ويبقى داخل هذه الدالة لا على window، فلا يستطيع سكربت آخر استدعاء الجسر مباشرة.
   مكتوب بصيغة قديمة (ES5) فيعمل على أي WebView، ولا يُحقن مرتين. */
(function () {
  if (window.__ibhHook) return;
  var R = window.__ibhN, S = "__IBH_SECRET__";
  if (!R || location.origin !== "https://ibh.nafahatsy.com") return;
  window.__ibhHook = 1;
  var N = {
    tokenSet: function (t) { return R.tokenSet(S, t); },
    tokenClear: function () { return R.tokenClear(S); },
    print: function (t) { return R.print(S, t); },
    keepScreenOn: function (b) { return R.keepScreenOn(S, !!b); }
  };

  /* 5) واجهة المنصة */
  try {
    var api = {
      saveFile: function (b64, name, mime) { return R.saveFile(S, String(b64), String(name || ""), String(mime || "")); },
      print: function (t) { window.print(); },
      openBell: function () { return R.openBell(S); },
      setBellToken: function (t) { return R.setBellToken(S, String(t || "")); },
      appVersion: function () { return R.appVersion(); }
    };
    Object.freeze(api);
    Object.defineProperty(window, "IBHNative", { value: api, writable: false, configurable: false, enumerable: false });
  } catch (e) {}

  /* 1) ترويسة التطبيق */
  try {
    var of = window.fetch;
    if (typeof of === "function") {
      window.fetch = function (input, init) {
        try {
          var raw = typeof input === "string" ? input : (input && input.url) ? input.url : String(input);
          var u = new URL(raw, location.href);
          if (u.origin === location.origin && u.pathname.indexOf("/api/") === 0) {
            var o = {}, k;
            if (init) for (k in init) o[k] = init[k];
            var h = new Headers(o.headers || (typeof Request !== "undefined" && input instanceof Request ? input.headers : undefined));
            h.set("X-IBH-App", "1");
            o.headers = h;
            init = o;
          }
        } catch (e) {}
        return of.call(this, input, init);
      };
    }
  } catch (e) {}

  /* 2) مرآة رمز الجلسة */
  try {
    var SP = Storage.prototype, si = SP.setItem, ri = SP.removeItem, cl = SP.clear;
    var isS = function (s) { try { return s === window.sessionStorage; } catch (e) { return false; } };
    SP.setItem = function (k, v) {
      var r = si.apply(this, arguments);
      try { if (k === "ibh_t" && isS(this)) N.tokenSet(String(v)); } catch (e) {}
      return r;
    };
    SP.removeItem = function (k) {
      var r = ri.apply(this, arguments);
      try { if (k === "ibh_t" && isS(this)) N.tokenClear(); } catch (e) {}
      return r;
    };
    SP.clear = function () {
      var r = cl.apply(this, arguments);
      try { if (isS(this)) N.tokenClear(); } catch (e) {}
      return r;
    };
    var cur = sessionStorage.getItem("ibh_t");
    if (cur) N.tokenSet(cur); else N.tokenClear();
  } catch (e) {}

  /* 3) الطباعة */
  try {
    window.print = function () {
      try { window.dispatchEvent(new Event("beforeprint")); } catch (e) {}
      try { N.print(String(document.title || "")); } catch (e) { try { window.dispatchEvent(new Event("afterprint")); } catch (x) {} }
    };
  } catch (e) {}

  /* 4) إبقاء الشاشة مضاءة */
  try {
    if (!("wakeLock" in navigator)) {
      var locks = [];
      var sync = function () { try { N.keepScreenOn(locks.length > 0); } catch (e) {} };
      var mk = function () {
        var ls = [];
        var s = {
          type: "screen", released: false, onrelease: null,
          addEventListener: function (t, f) { if (t === "release" && typeof f === "function") ls.push(f); },
          removeEventListener: function (t, f) { var i = ls.indexOf(f); if (i >= 0) ls.splice(i, 1); },
          release: function () {
            if (s.released) return Promise.resolve();
            s.released = true;
            var i = locks.indexOf(s); if (i >= 0) locks.splice(i, 1);
            sync();
            var ev = { type: "release", target: s };
            for (var j = 0; j < ls.length; j++) { try { ls[j].call(s, ev); } catch (e) {} }
            if (typeof s.onrelease === "function") { try { s.onrelease(ev); } catch (e) {} }
            return Promise.resolve();
          }
        };
        return s;
      };
      var WL = {
        request: function (type) {
          return new Promise(function (res, rej) {
            if (type && type !== "screen") { rej(new TypeError("wakeLock type")); return; }
            if (document.visibilityState !== "visible") { rej(new Error("NotAllowedError")); return; }
            var s = mk(); locks.push(s); sync(); res(s);
          });
        }
      };
      Object.defineProperty(navigator, "wakeLock", { value: WL, configurable: true });
      /* كالمتصفح: يسقط القفل حين تختفي الصفحة، والمنصة تطلبه من جديد عند عودتها (bellLock) */
      document.addEventListener("visibilitychange", function () {
        if (document.visibilityState !== "visible") { var c = locks.slice(); for (var i = 0; i < c.length; i++) c[i].release(); }
      });
    }
  } catch (e) {}
})();
