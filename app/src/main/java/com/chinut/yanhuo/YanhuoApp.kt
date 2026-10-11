package com.chinut.yanhuo

import android.app.Application
import android.content.Context
import com.chinut.yanhuo.core.Http
import com.chinut.yanhuo.core.Prefs
import com.chinut.yanhuo.data.SourceRegistry
import com.chinut.yanhuo.work.UpdateNotifier

/**
 * App 入口。
 *
 * 这里是**唯一**保存 application context 的地方：数据层大量是 `object`（单例），
 * 拿不到 context，所以统一从这里取，而不是到处传參数。
 */
class YanhuoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Http.init(this)
        SourceRegistry.init()
        UpdateNotifier.ensureChannel(this)
    }

    companion object {
        @Volatile
        private var instance: YanhuoApp? = null

        /** 数据层获取 context。未初始化时会抛异常，属于编程错误，早暴露更好。 */
        fun ctx(): Context = requireNotNull(instance) { "YanhuoApp 还没有初始化" }

        fun app(): YanhuoApp = requireNotNull(instance) { "YanhuoApp 还没有初始化" }
    }
}
