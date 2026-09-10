package com.ep.donwnloader

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/** 临时诊断页：裸 WebView 触摸对照实验（无任何附加逻辑），测完删除。 */
class TestWebViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.loadData(
            "<html><body style='margin:20px'>" +
            "<input id='t' style='height:60px;font-size:24px;width:90%' placeholder='tap me'>" +
            "<div id='r' style='font-size:34px;padding:20px;color:#c00'>NO-FOCUS</div>" +
            "<script>document.getElementById('t').addEventListener('focus',function(){" +
            "document.getElementById('r').textContent='FOCUSED-OK';});</script>" +
            "</body></html>", "text/html", "utf-8")
        setContentView(wv)
    }
}
