package com.yg.dsh.idea.ui

import com.yg.dsh.idea.util.TextUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JCEF 浏览器生命周期。
 *
 * 注意：本类是插件中**唯一**的页面 JS 注入点，且仅剩一个用途——
 * [injectToComposer]（Ctrl+Alt+K 草稿注入）。自动发送走后端 RPC
 *（[com.yg.dsh.idea.runtime.DshApiClient.sendMessage]），locale/欢迎声明
 * 走 settings.yaml 持久化，均不经过页面脚本。
 */
class BrowserManager : Disposable {

    companion object {
        private val LOG = Logger.getInstance(BrowserManager::class.java)
    }

    @Volatile private var browser: JBCefBrowser? = null
    private val disposed = AtomicBoolean(false)

    fun loadUrl(url: String): JBCefBrowser {
        if (disposed.get()) {
            LOG.warn("loadUrl called after dispose")
            return JBCefBrowser()
        }
        val b = synchronized(this) {
            browser ?: JBCefBrowser().also { browser = it }
        }
        try {
            b.loadURL(url)
        } catch (e: Throwable) {
            LOG.warn("JCEF loadURL failed", e)
        }
        return b
    }

    private fun executeInPage(script: String): Boolean {
        if (disposed.get()) return false
        val b = browser ?: return false
        val cef = try { b.cefBrowser } catch (_: Throwable) { return false }
        return try {
            val pageUrl = runCatching { cef.url }.getOrNull() ?: "about:blank"
            cef.executeJavaScript(script, pageUrl, 0)
            true
        } catch (e: Throwable) {
            LOG.warn("JCEF executeJavaScript failed", e)
            false
        }
    }

    /**
     * 草稿式注入（Ctrl+Alt+K）：只填输入框、**不提交**，用户继续补充后自己发送。
     *
     * 自动发送（一键解释日志）不走这里而走后端 RPC——草稿只存在于网页前端内存，
     * 后端没有 draft 接口，因此填草稿必须走 DOM。加固点：
     * - textarea：在**光标处**插入（用户可能已打了一半字），不覆盖已有内容；
     * - 词边界保护：DSH 前端只在 `@` 前是空白/行首时才把 `@path` 识别为文件提及（蓝色高亮）。
     *   若光标前一个字符非空白，自动补一个空格再插入；光标后紧跟非空白时同样补空格收尾；
     * - 降级链：textarea → contenteditable/[role=textbox]（前端改版换组件时不至于全瞎）；
     * - 命令式插入后派发 input，让 React 受控状态自然更新（不反向控 value，不触发布局/输入回归）。
     */
    fun injectToComposer(text: String): Boolean {
        if (disposed.get()) return false
        val json = TextUtils.escapeJs(text)
        val script = """
            (() => {
              const deadline = Date.now() + 8000;
              const text = $json;
              const setNativeValue = (el, value) => {
                const desc = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
                if (desc && desc.set) desc.set.call(el, value); else el.value = value;
              };
              const pad = (payload) => {
                const before = payload.before, after = payload.after, body = payload.body;
                const lead = (before && !/\s/.test(before)) ? ' ' : '';
                const tail = (after && !/\s/.test(after)) ? ' ' : '';
                return lead + body + tail;
              };
              const intoTextarea = (ta) => {
                const start = ta.selectionStart != null ? ta.selectionStart : ta.value.length;
                const end = ta.selectionEnd != null ? ta.selectionEnd : ta.value.length;
                const before = start > 0 ? ta.value.charAt(start - 1) : '';
                const after = end < ta.value.length ? ta.value.charAt(end) : '';
                const payload = pad({ before: before, after: after, body: text });
                setNativeValue(ta, ta.value.slice(0, start) + payload + ta.value.slice(end));
                ta.dispatchEvent(new Event('input', { bubbles: true }));
                const pos = start + payload.length;
                try { ta.setSelectionRange(pos, pos); } catch (e) {}
                ta.focus();
              };
              const intoContentEditable = (el) => {
                el.focus();
                const sel = window.getSelection();
                if (sel && sel.rangeCount && el.contains(sel.getRangeAt(0).commonAncestorContainer)) {
                  const range = sel.getRangeAt(0);
                  const sc = range.startContainer, ec = range.endContainer;
                  const before = (sc.nodeType === 3 && range.startOffset > 0)
                    ? String(sc.textContent).charAt(range.startOffset - 1) : '';
                  const after = (ec.nodeType === 3 && range.endOffset < String(ec.textContent || '').length)
                    ? String(ec.textContent).charAt(range.endOffset) : '';
                  const payload = pad({ before: before, after: after, body: text });
                  range.deleteContents();
                  range.insertNode(document.createTextNode(payload));
                  range.collapse(false);
                  sel.removeAllRanges(); sel.addRange(range);
                } else {
                  const last = (el.textContent || '').slice(-1);
                  el.textContent = (el.textContent || '') + ((last && !/\s/.test(last)) ? ' ' : '') + text;
                }
                el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
                el.focus();
              };
              const tryInject = () => {
                const ta = document.querySelector('textarea');
                if (ta) { intoTextarea(ta); return; }
                const ce = document.querySelector('[contenteditable="true"], [role="textbox"]');
                if (ce) { intoContentEditable(ce); return; }
                if (Date.now() < deadline) setTimeout(tryInject, 300);
              };
              tryInject();
            })();
        """.trimIndent()
        return executeInPage(script)
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        browser?.dispose()
        browser = null
    }
}
