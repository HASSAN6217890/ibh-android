package com.nafahatsy.ibh

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File

/**
 * النافذة الوحيدة: WebView يفتح المنصة الحية https://ibh.nafahatsy.com/ ولا يتنقل إلا داخلها؛ والروابط الخارجية
 * (واتساب، الهاتف، المواقع الأخرى) تُفتح في تطبيقاتها. الجسور: IBHNative (NativeBridge.kt) وhook.js.
 */
class MainActivity : Activity() {

    lateinit var store: SecureStore
    private lateinit var bridge: NativeBridge
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var splash: View
    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView

    /** هل الصفحة الحالية من نطاق المعهد؟ يقرؤه الجسر من خيطه */
    @Volatile var originOk = false

    private val ui = Handler(Looper.getMainLooper())
    private var hookJs = ""
    private var target = Cfg.HOME
    private var lastUrl: String? = null
    private var clearHistoryAfter = false
    private var mainLoaded = false
    private var splashAt = 0L
    private var errorShown = false

    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var fileParams: WebChromeClient.FileChooserParams? = null
    private var captureFile: File? = null
    private var captureUri: Uri? = null
    private var pendingPerm: PermissionRequest? = null
    private var pendingSave: Triple<ByteArray, String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        bridge = NativeBridge(this)
        // السر يُكتب داخل نص hook.js قبل حقنه؛ إن غاب موضعه لا يُحقن شيء (فلا جسر يعمل بلا سر)
        val tpl = UiKit.readAsset(this, "hook.js")
        hookJs = if (tpl.contains(SECRET_SLOT)) tpl.replace(SECRET_SLOT, bridge.secret) else ""
        buildUi(savedInstanceState == null)
        setupWeb()

        val saved = savedInstanceState?.getString(KEY_URL)
        target = widgetTarget(intent) ?: if (Cfg.isOurs(saved)) saved!! else Cfg.HOME
        boot()
        checkWebViewVersion()
        registerBack()
        if (ACTION_BELL == intent?.action) openBell()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (ACTION_BELL == intent?.action) openBell()
        val w = widgetTarget(intent) ?: return
        // ضغطة في أداة الإدارة والتطبيق مفتوح: تغيّر العنوان داخل المنصة (hashchange)، أو تُعاد المحاولة إن كانت صفحة الخطأ ظاهرة
        target = w
        if (mainLoaded && !errorShown && originOk) web.loadUrl(w) else retry()
    }

    /** مسار من أداة الإدارة (#day، #tasks…) بعد التحقق من القائمة المسموحة؛ وإلا null. بلا مسار: الصفحة الرئيسية */
    private fun widgetTarget(i: Intent?): String? {
        if (i == null || ACTION_OPEN != i.action) return null
        val r = i.getStringExtra(EXTRA_ROUTE)
        return if (Cfg.routeOk(r)) Cfg.ORIGIN + "/" + r else null
    }

    // ---------------------------------------------------------------- الواجهة

    private fun buildUi(coldStart: Boolean) {
        root = FrameLayout(this)
        root.setBackgroundColor(Cfg.GREEN_800)
        val content = FrameLayout(this)
        content.setBackgroundColor(Cfg.CREAM)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        web = WebView(this)
        content.addView(web, FrameLayout.LayoutParams(-1, -1))

        errorView = buildError()
        errorView.visibility = View.GONE
        content.addView(errorView, FrameLayout.LayoutParams(-1, -1))

        splash = buildSplash()
        content.addView(splash, FrameLayout.LayoutParams(-1, -1))
        if (!coldStart) splash.visibility = View.GONE
        splashAt = System.currentTimeMillis()
        ui.postDelayed({ hideSplash() }, 7000)

        setContentView(root)
        applyBars()
    }

    /** الشاشة الأولى: شعار المعهد وتحته «بإشراف مؤسسة الأحباب الإنسانية» */
    private fun buildSplash(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundColor(Cfg.CREAM)
        box.isClickable = true
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.splash_institute)
        logo.contentDescription = getString(R.string.app_name)
        box.addView(logo, LinearLayout.LayoutParams(Cfg.dp(this, 220), Cfg.dp(this, 220)))
        val sp = View(this)
        box.addView(sp, LinearLayout.LayoutParams(1, Cfg.dp(this, 36)))
        val ahbab = ImageView(this)
        ahbab.setImageResource(R.drawable.splash_ahbab)
        ahbab.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        box.addView(ahbab, LinearLayout.LayoutParams(Cfg.dp(this, 64), Cfg.dp(this, 64)))
        val line = TextView(this)
        line.text = getString(R.string.supervised_by)
        line.setTextColor(Cfg.GREEN_800)
        line.textSize = 16f
        line.gravity = Gravity.CENTER
        line.setPadding(Cfg.dp(this, 24), Cfg.dp(this, 8), Cfg.dp(this, 24), 0)
        box.addView(line, LinearLayout.LayoutParams(-2, -2))
        return box
    }

    private fun hideSplash() {
        if (splash.visibility != View.VISIBLE) return
        val wait = 800 - (System.currentTimeMillis() - splashAt)
        if (wait > 0) { ui.postDelayed({ hideSplash() }, wait); return }
        splash.animate().alpha(0f).setDuration(250).withEndAction { splash.visibility = View.GONE }.start()
    }

    /** صفحة «لا اتصال» أصلية بالعربية مع «أعد المحاولة» */
    private fun buildError(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundColor(Cfg.CREAM)
        box.isClickable = true
        val pad = Cfg.dp(this, 28)
        box.setPadding(pad, pad, pad, pad)
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.splash_institute)
        logo.alpha = 0.9f
        logo.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        box.addView(logo, LinearLayout.LayoutParams(Cfg.dp(this, 120), Cfg.dp(this, 120)))
        errorTitle = TextView(this)
        errorTitle.setTextColor(Cfg.INK)
        errorTitle.textSize = 20f
        errorTitle.setTypeface(errorTitle.typeface, Typeface.BOLD)
        errorTitle.gravity = Gravity.CENTER
        errorTitle.setPadding(0, Cfg.dp(this, 20), 0, Cfg.dp(this, 8))
        box.addView(errorTitle, LinearLayout.LayoutParams(-2, -2))
        errorText = TextView(this)
        errorText.setTextColor(Cfg.MUTED)
        errorText.textSize = 15f
        errorText.gravity = Gravity.CENTER
        errorText.setLineSpacing(0f, 1.3f)
        box.addView(errorText, LinearLayout.LayoutParams(-2, -2))
        val retry = Button(this)
        retry.text = getString(R.string.retry)
        retry.setTextColor(Color.WHITE)
        retry.textSize = 16f
        retry.isAllCaps = false
        retry.background = UiKit.pill(Cfg.GREEN_800, Cfg.dp(this, 24))
        retry.setPadding(Cfg.dp(this, 32), 0, Cfg.dp(this, 32), 0)
        retry.setOnClickListener { retry() }
        val lp = LinearLayout.LayoutParams(-2, Cfg.dp(this, 48))
        lp.topMargin = Cfg.dp(this, 24)
        box.addView(retry, lp)
        val bell = TextView(this)
        bell.text = getString(R.string.bell_open)
        bell.setTextColor(Cfg.GREEN_800)
        bell.textSize = 14f
        bell.setPadding(Cfg.dp(this, 12), Cfg.dp(this, 18), Cfg.dp(this, 12), Cfg.dp(this, 12))
        bell.setOnClickListener { openBell() }
        box.addView(bell, LinearLayout.LayoutParams(-2, -2))
        return box
    }

    private fun showError(server: Boolean) {
        errorShown = true
        errorTitle.text = getString(if (server) R.string.err_server_t else R.string.err_net_t)
        errorText.text = getString(if (server) R.string.err_server else R.string.err_net)
        errorView.visibility = View.VISIBLE
        hideSplash()
    }

    private fun retry() {
        errorShown = false
        errorView.visibility = View.GONE
        boot()
    }

    /** أشرطة النظام بلون المعهد، وحواف الشاشة ولوحة المفاتيح تُحسب يدوياً (أندرويد 15 يفرض الرسم حتى الحواف) */
    @Suppress("DEPRECATION")
    private fun applyBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.insetsController?.setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            root.setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        } else {
            window.statusBarColor = Cfg.GREEN_800
            window.navigationBarColor = Cfg.GREEN_800
        }
    }

    // ---------------------------------------------------------------- WebView

    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        // لا تحميل لروابط content:// داخل الصفحات. رفع الملفات لا يتأثر: نتيجة onShowFileChooser (من المعرض، أو من
        // CaptureProvider للكاميرا) يقرؤها WebView في عملية التطبيق نفسها عبر ContentResolver عند الرفع، لا كتحميل رابط في الصفحة؛
        // وCaptureProvider غير مُصدَّر لكنه من التطبيق نفسه فيُقرأ بلا منح إذن. (يُتحقق منه على جهاز في المرحلة ج)
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setGeolocationEnabled(false)
        s.userAgentString = s.userAgentString + " IBHApp/" + Cfg.versionName(this)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.isVerticalScrollBarEnabled = true
        // الاسم الأصلي __ibhN؛ وwindow.IBHNative تبنيه hook.js فوقه وتضيف السر
        web.addJavascriptInterface(bridge, "__ibhN")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
    }

    /**
     * الإقلاع: إن كان رمز جلسة محفوظاً يُفتح أولاً عنوان داخلي على نطاق المعهد (لا يطلب الشبكة، يُجاب من التطبيق نفسه)،
     * فيُكتب الرمز في sessionStorage («ibh_t») ثم تُفتح المنصة، فتقرؤه app.js كأن الجلسة لم تنقطع.
     */
    private fun boot() {
        mainLoaded = false
        val tok = store.get(SecureStore.SESSION)
        if (tok != null && NativeBridge.TOKEN_RE.matcher(tok).matches()) web.loadUrl(Cfg.BOOT_URL)
        else web.loadUrl(target)
    }

    private fun inject(view: WebView, url: String?) {
        if (hookJs.isEmpty() || !Cfg.isOurs(url) || (url ?: "").startsWith(Cfg.BOOT_URL)) return
        view.evaluateJavascript(hookJs, null)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (Cfg.isOurs(u.toString())) return false
            val web = "http" == u.scheme || "https" == u.scheme
            // مواقع أخرى: تُفتح في المتصفح عند التنقل أو الضغط. أنواع الروابط الأخرى (tel: وwhatsapp: …) بضغطة المستخدم فقط
            if (if (web) request.isForMainFrame || request.hasGesture() else request.hasGesture()) openOutside(u)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.url.toString().startsWith(Cfg.BOOT_URL)) {
                val r = WebResourceResponse("text/html", "utf-8",
                    ByteArrayInputStream("<!doctype html><html lang=\"ar\" dir=\"rtl\"><meta charset=\"utf-8\"><title>.</title></html>".toByteArray(Charsets.UTF_8)))
                val h = HashMap<String, String>()
                h["Cache-Control"] = "no-store"
                h["Content-Security-Policy"] = "default-src 'none'"
                r.responseHeaders = h
                return r
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            originOk = Cfg.isOurs(url)
            setKeepOn(false)
            if (url != null && !originOk && url != "about:blank") {
                view.stopLoading()
                val pu = Uri.parse(url)
                if ("https" == pu.scheme || "http" == pu.scheme) openOutside(pu)
                if (view.canGoBack()) view.goBack() else view.loadUrl(Cfg.HOME)
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) { inject(view, url) }

        override fun onPageFinished(view: WebView, url: String?) {
            val u = url ?: ""
            if (u.startsWith(Cfg.BOOT_URL)) {
                val tok = store.get(SecureStore.SESSION)
                val js = if (tok != null && NativeBridge.TOKEN_RE.matcher(tok).matches())
                    "try{sessionStorage.setItem('ibh_t','$tok')}catch(e){}" else "0"
                view.evaluateJavascript(js) {
                    clearHistoryAfter = true
                    view.loadUrl(target)
                }
                return
            }
            inject(view, url)
            if (Cfg.isOurs(u)) {
                if (clearHistoryAfter) { clearHistoryAfter = false; view.clearHistory() }
                if (!errorShown) { mainLoaded = true; hideSplash() }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (Cfg.isOurs(url) && !(url ?: "").startsWith(Cfg.BOOT_URL)) { lastUrl = url; target = url!! }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showError(false)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, r: WebResourceResponse) {
            if (request.isForMainFrame && r.statusCode >= 500) showError(true)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            showError(false)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // انهار محرّك الصفحة (ذاكرة قليلة): يُعاد بناء النافذة بدل إغلاق التطبيق، والجلسة محفوظة
            try { (view.parent as? ViewGroup)?.removeView(view); view.destroy() } catch (e: Exception) {}
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = cb
            fileParams = params
            val wantsImage = acceptsImages(params.acceptTypes)
            if (wantsImage && hasCameraApp()) {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchChooser(true)
                else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM_FILE)
            } else launchChooser(false)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // getUserMedia (قارئ الرموز): الكاميرا وحدها، ولنطاق المعهد وحده
            val res = request.resources
            if (!Cfg.isOurs(request.origin.toString()) || !res.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) { request.deny(); return }
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            } else {
                pendingPerm?.deny()
                pendingPerm = request
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM_MEDIA)
            }
        }
    }

    // ---------------------------------------------------------------- الرفع والكاميرا

    private fun acceptsImages(types: Array<String>?): Boolean {
        if (types == null || types.isEmpty()) return true
        var any = false
        for (raw in types) for (p in UiKit.splitOn(raw, ',')) {
            val t = p.trim().lowercase(java.util.Locale.ROOT)
            if (t.isEmpty()) continue
            any = true
            if (t.startsWith("image/") || t == "*/*" || t == ".jpg" || t == ".jpeg" || t == ".png" || t == ".webp" || t == ".heic") return true
        }
        return !any
    }

    private fun mimesOf(types: Array<String>?): Array<String> {
        val out = ArrayList<String>()
        if (types != null) for (raw in types) for (p in UiKit.splitOn(raw, ',')) {
            val t = p.trim().lowercase(java.util.Locale.ROOT)
            if (t.isEmpty()) continue
            val m = if (t.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.substring(1)) else t
            if (m != null && m.contains("/") && !out.contains(m)) out.add(m)
        }
        return out.toTypedArray()
    }

    private fun hasCameraApp(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(packageManager) != null

    private fun launchChooser(withCamera: Boolean) {
        val p = fileParams
        if (p == null) { fileCb?.onReceiveValue(null); fileCb = null; return }
        // يُصفّى المنتقي بالنوع للصور وPDF فقط؛ أنواع مثل CSV وJSON وxlsx تُسمّى على الهواتف بأسماء مختلفة فتُخفى ملفاتها خطأً،
        // فتُعرض كل الملفات، والمنصة تتحقق من النوع بعد الاختيار
        var mimes = mimesOf(p.acceptTypes)
        for (m in mimes) if (!m.startsWith("image/") && m != "application/pdf") { mimes = arrayOf(); break }
        val pick = Intent(Intent.ACTION_GET_CONTENT)
        pick.addCategory(Intent.CATEGORY_OPENABLE)
        if (mimes.size == 1) pick.type = mimes[0]
        else { pick.type = "*/*"; if (mimes.isNotEmpty()) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimes) }
        if (p.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        var cam: Intent? = null
        captureFile = null; captureUri = null
        if (withCamera) {
            try {
                val pair = CaptureProvider.newCapture(this)
                captureFile = pair.first; captureUri = pair.second
                cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                cam.putExtra(MediaStore.EXTRA_OUTPUT, pair.second)
                cam.clipData = ClipData.newRawUri("", pair.second)
                cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { cam = null }
        }
        val launch: Intent = if (cam != null && p.isCaptureEnabled) cam else {
            val ch = Intent.createChooser(pick, getString(R.string.choose_file))
            if (cam != null) ch.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
            ch
        }
        try { startActivityForResult(launch, REQ_FILE) } catch (e: ActivityNotFoundException) {
            fileCb?.onReceiveValue(null); fileCb = null
            toast(getString(R.string.no_picker))
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_FILE) { super.onActivityResult(requestCode, resultCode, data); return }
        val cb = fileCb; fileCb = null
        var res: Array<Uri>? = null
        if (resultCode == RESULT_OK) {
            val cf = captureFile; val cu = captureUri
            if (cf != null && cu != null && cf.length() > 0) res = arrayOf(cu)
            else if (data != null) {
                val clip = data.clipData
                if (clip != null && clip.itemCount > 0) {
                    val l = ArrayList<Uri>()
                    for (i in 0 until clip.itemCount) { val x = clip.getItemAt(i).uri; if (x != null && x != cu) l.add(x) }
                    if (l.isNotEmpty()) res = l.toTypedArray()
                }
                if (res == null && data.data != null && data.data != cu) res = arrayOf(data.data!!)
            }
        }
        if (res == null) captureFile?.delete()
        cb?.onReceiveValue(res)
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_CAM_FILE -> launchChooser(granted)
            REQ_CAM_MEDIA -> {
                val r = pendingPerm; pendingPerm = null
                if (r != null) { if (granted) r.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else r.deny() }
            }
            REQ_WRITE -> {
                val s = pendingSave; pendingSave = null
                if (s != null) { if (granted) doSave(s.first, s.second, s.third) else toast(getString(R.string.save_denied)) }
            }
        }
    }

    // ---------------------------------------------------------------- التنزيل والطباعة والشاشة

    fun saveDownload(bytes: ByteArray, name: String, mime: String) {
        if (FileSaver.needsLegacyPermission() && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSave = Triple(bytes, name, mime)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE)
            return
        }
        doSave(bytes, name, mime)
    }

    private fun doSave(bytes: ByteArray, name: String, mime: String) {
        Thread {
            try {
                FileSaver.save(this, bytes, name, mime) { uri -> runOnUiThread { savedDialog(name, uri, mime) } }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.save_failed)) }
            }
        }.start()
    }

    private fun savedDialog(name: String, uri: Uri?, mime: String) {
        if (isFinishing) return
        val b = AlertDialog.Builder(this)
            .setTitle(R.string.saved_t)
            .setMessage(getString(R.string.saved_in_downloads) + "\n" + name)
            .setPositiveButton(R.string.ok, null)
        if (uri != null) b.setNeutralButton(R.string.open) { _, _ ->
            try {
                val v = Intent(Intent.ACTION_VIEW)
                v.setDataAndType(uri, mime)
                v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(v)
            } catch (e: Exception) { toast(getString(R.string.no_viewer)) }
        }
        b.show()
    }

    fun printPage(title: String) {
        val pm = getSystemService(PRINT_SERVICE) as? PrintManager
        val name = if (title.isBlank()) getString(R.string.app_name) else title
        if (pm == null) { afterPrint(); return }
        val inner = web.createPrintDocumentAdapter(name)
        // نافذة الطباعة تقرأ الصفحة بأنماط @media print ما دامت أصناف الطباعة على الصفحة؛ تُزال عند afterprint بعد إغلاق النافذة
        val wrap = object : PrintDocumentAdapter() {
            override fun onStart() = inner.onStart()
            override fun onLayout(o: PrintAttributes?, n: PrintAttributes, c: CancellationSignal?, cb: LayoutResultCallback, x: Bundle?) =
                inner.onLayout(o, n, c, cb, x)
            override fun onWrite(p: Array<out PageRange>, d: ParcelFileDescriptor, c: CancellationSignal?, cb: WriteResultCallback) =
                inner.onWrite(p, d, c, cb)
            override fun onFinish() { inner.onFinish(); afterPrint() }
        }
        try { pm.print(name, wrap, PrintAttributes.Builder().build()) } catch (e: Exception) { afterPrint(); toast(getString(R.string.print_failed)) }
    }

    private fun afterPrint() {
        web.evaluateJavascript("try{window.dispatchEvent(new Event('afterprint'))}catch(e){}", null)
    }

    fun setKeepOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun openBell() {
        startActivity(Intent(this, BellActivity::class.java))
    }

    // ---------------------------------------------------------------- مساعدات

    private fun openOutside(u: Uri) {
        val scheme = (u.scheme ?: "").lowercase(java.util.Locale.ROOT)
        val i: Intent = when (scheme) {
            "tel" -> Intent(Intent.ACTION_DIAL, u)
            "http", "https", "mailto", "sms", "smsto", "whatsapp", "geo" -> Intent(Intent.ACTION_VIEW, u)
            else -> return
        }
        i.addCategory(Intent.CATEGORY_BROWSABLE)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: ActivityNotFoundException) { toast(getString(R.string.no_app)) }
    }

    fun toastOnUi(msg: String) = runOnUiThread { toast(msg) }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** WebView أقدم من 80 لا يشغّل المنصة (app.js): تنبيه بتحديث «Android System WebView» */
    private fun checkWebViewVersion() {
        if (Build.VERSION.SDK_INT < 26) return
        val pi = try { WebView.getCurrentWebViewPackage() } catch (e: Exception) { null } ?: return
        val major = UiKit.splitOn(pi.versionName ?: "", '.')[0].toIntOrNull() ?: return
        if (major >= Cfg.MIN_WEBVIEW_MAJOR) return
        AlertDialog.Builder(this)
            .setTitle(R.string.old_webview_t)
            .setMessage(R.string.old_webview)
            .setPositiveButton(R.string.update) { _, _ ->
                openOutside(Uri.parse("https://play.google.com/store/apps/details?id=" + pi.packageName))
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (Cfg.isOurs(lastUrl)) outState.putString(KEY_URL, lastUrl)
    }

    override fun onPause() { super.onPause(); web.onPause() }
    // بعد العمل في المنصة (تثبيت حصة، إغلاق مهمة): تُحدَّث أداة الإدارة إن كانت مضافة
    override fun onStop() { super.onStop(); try { WidgetData.poke(this) } catch (e: Exception) {} }
    override fun onResume() { super.onResume(); web.onResume() }

    /**
     * الرجوع: من أندرويد 13 عبر OnBackInvokedCallback، لأن إيماءة الرجوع (الرجوع التنبؤي، مفعّل افتراضاً للتطبيقات
     * التي تستهدف 36 فما فوق على أندرويد 16+) لا تستدعي onBackPressed فتُغلق التطبيق بدل الرجوع داخل المنصة.
     * onBackPressed يبقى لأندرويد 12 فما دون.
     */
    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                android.window.OnBackInvokedCallback { handleBack() })
        }
    }

    private fun handleBack() {
        if (errorView.visibility == View.VISIBLE) { finish(); return }
        if (web.canGoBack()) web.goBack() else finish()
    }

    @Deprecated("Activity API")
    @Suppress("GestureBackNavigation")
    override fun onBackPressed() = handleBack()

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        fileCb?.onReceiveValue(null); fileCb = null
        pendingPerm?.deny(); pendingPerm = null
        try { (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_BELL = "com.nafahatsy.ibh.BELL"
        /** فتح من أداة الإدارة؛ المسار في EXTRA_ROUTE (يُتحقق منه بـ Cfg.routeOk) */
        const val ACTION_OPEN = "com.nafahatsy.ibh.OPEN"
        const val EXTRA_ROUTE = "route"
        private const val SECRET_SLOT = "__IBH_SECRET__"
        private const val KEY_URL = "url"
        private const val REQ_FILE = 11
        private const val REQ_CAM_FILE = 12
        private const val REQ_CAM_MEDIA = 13
        private const val REQ_WRITE = 14
    }
}
