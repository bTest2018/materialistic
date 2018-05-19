/*
 * Copyright (c) 2018 Ha Duy Trung
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.hidroh.materialistic.data

import android.accounts.Account
import android.accounts.AccountManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.*
import android.graphics.BitmapFactory
import android.os.*
import android.support.annotation.RequiresApi
import android.support.annotation.UiThread
import android.support.annotation.VisibleForTesting
import android.support.v4.app.NotificationCompat
import android.text.format.DateUtils
import android.webkit.WebView
import io.github.hidroh.materialistic.BuildConfig
import io.github.hidroh.materialistic.ItemActivity
import io.github.hidroh.materialistic.Preferences
import io.github.hidroh.materialistic.R
import io.github.hidroh.materialistic.ktx.getItemUri
import io.github.hidroh.materialistic.ktx.setChannel
import io.github.hidroh.materialistic.widget.AdBlockWebViewClient
import io.github.hidroh.materialistic.widget.CacheableWebView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject

class KtSyncDelegate @Inject constructor(
    private val context: Context,
    factory: RestServiceFactory,
    private val readabilityClient: ReadabilityClient) {

  private val handler = Handler(Looper.getMainLooper())
  private val sharedPreferences: SharedPreferences
  private val hnRestService: HackerNewsClient.RestService
  private val notificationManager: NotificationManager
  private val notificationBuilder: NotificationCompat.Builder
  private var listener: ProgressListener? = null
  private var job: Job? = null
  private var syncProgress: SyncProgress? = null
  private var webView: CacheableWebView? = null

  init {
    sharedPreferences = context.getSharedPreferences(
        "${context.packageName}$SYNC_PREFERENCES_FILE", Context.MODE_PRIVATE)
    hnRestService = factory.create(HackerNewsClient.BASE_API_URL,
        HackerNewsClient.RestService::class.java,
        { command ->
          Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
          command!!.run()
        })
    notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
        as NotificationManager
    notificationBuilder = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
        .setChannel(context, CHANNEL_DOWNLOAD, context.getString(R.string.notification_channel_downloads))
        .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        .setSmallIcon(R.drawable.ic_notification)
        .setGroup(NOTIFICATION_GROUP_KEY)
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
  }

  fun subscribe(listener: ProgressListener) {
    this.listener = listener
  }

  fun performSync(job: Job) {
    // assume that connection wouldn't change until we finish syncing
    this.job = job
    if (job.id.isEmpty()) {
      syncDeferredItems()
    } else {
      handler.sendMessageDelayed(Message.obtain(handler, { stopSync() }).apply { what = job.id.toInt() },
          DateUtils.MINUTE_IN_MILLIS)
      syncProgress = SyncProgress(job)
      sync(job.id)
    }
  }

  fun stopSync() {
    if (job == null) return
    job!!.connectionEnabled = false
    notificationManager.cancel(job!!.id.toInt())
    handler.removeMessages(job!!.id.toInt())
  }

  private fun sync(itemId: String) {
    println(job!!.connectionEnabled)
    if (!job!!.connectionEnabled) {
      defer(itemId)
      return
    }
    val cachedItem = getFromCache(itemId)
    if (cachedItem != null) {
      sync(cachedItem)
    } else {
      updateProgress()
      hnRestService.networkItem(itemId).enqueue(object : Callback<HackerNewsItem> {
        override fun onResponse(call: Call<HackerNewsItem>?, response: Response<HackerNewsItem>?) {
          response!!.body()?.run { sync(this) }
        }

        override fun onFailure(call: Call<HackerNewsItem>?, t: Throwable?) = notifyItem(itemId)
      })
    }
  }

  private fun syncDeferredItems() {
    sharedPreferences.all.keys.forEach {
      scheduleSync(context, Job(it,
        connectionEnabled = Preferences.Offline.currentConnectionEnabled(context),
        readabilityEnabled = Preferences.Offline.isReadabilityEnabled(context),
        articleEnabled = Preferences.Offline.isArticleEnabled(context),
        commentsEnabled = Preferences.Offline.isCommentsEnabled(context),
        notificationEnabled = false))
    }
  }

  private fun sync(item: HackerNewsItem) {
    sharedPreferences.edit().remove(item.id).apply()
    notifyItem(item.id, item)
    syncReadability(item)
    syncArticle(item)
    syncChildren(item)
  }

  private fun syncReadability(item: HackerNewsItem) {
    if (job!!.readabilityEnabled && item.isStoryType) {
      readabilityClient.parse(item.id, item.rawUrl, { notifyReadability() })
    }
  }

  private fun syncArticle(item: HackerNewsItem) {
    if (job!!.articleEnabled && item.isStoryType && !item.url.isNullOrEmpty()) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        loadArticle(item)
      } else {
        context.startService(Intent(context, WebCacheService::class.java)
            .putExtra(WebCacheService.EXTRA_URL, item.url))
        notifyArticle(100)
      }
    }
  }

  private fun loadArticle(item: HackerNewsItem) {
    notifyArticle(0)
    webView = CacheableWebView(context)
    with(webView!!) {
      webViewClient = AdBlockWebViewClient(Preferences.adBlockEnabled(context))
      webChromeClient = object : CacheableWebView.ArchiveClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
          super.onProgressChanged(view, newProgress)
          notifyArticle(newProgress)
        }
      }
      loadUrl(item.url)
    }
  }

  private fun syncChildren(item: HackerNewsItem) {
    if (job!!.commentsEnabled) {
      item.kids?.forEach { sync(it.toString()) }
    }
  }

  private fun defer(itemId: String) {
    sharedPreferences.edit().putBoolean(itemId, true).apply()
  }

  private fun getFromCache(itemId: String) = try {
    hnRestService.cachedItem(itemId).execute().body()
  } catch (e: IOException) {
    null
  }

  private fun notifyItem(id: String, item: HackerNewsItem? = null) {
    syncProgress!!.finishItem(id, item,
        kidsEnabled = job!!.commentsEnabled && job!!.connectionEnabled,
        readabilityEnabled = job!!.readabilityEnabled && job!!.connectionEnabled)
    updateProgress()
  }

  private fun notifyReadability() {
    syncProgress!!.finishReadability()
    updateProgress()
  }

  @VisibleForTesting
  fun notifyArticle(newProgress: Int) {
    syncProgress!!.updateArticle(newProgress, 100)
    updateProgress()
  }

  private fun updateProgress() = if (syncProgress!!.progress >= syncProgress!!.max) {
    finish()
  } else {
    showProgress()
  }

  private fun finish() {
    listener?.onDone(job!!.id)
    listener = null
    stopSync()
  }

  private fun showProgress() {
    notificationManager.notify(job!!.id.toInt(), notificationBuilder
        .setContentTitle(syncProgress!!.title)
        .setContentText(context.getString(R.string.download_in_progress))
        .setContentIntent(getItemActivity(job!!.id))
        .setOnlyAlertOnce(true)
        .setProgress(syncProgress!!.max, syncProgress!!.progress, false)
        .setSortKey(job!!.id)
        .build())
  }

  private fun getItemActivity(itemId: String) = PendingIntent.getActivity(context, 0,
      Intent(Intent.ACTION_VIEW)
          .setData(itemId.getItemUri())
          .putExtra(ItemActivity.EXTRA_CACHE_MODE, ItemManager.MODE_CACHE)
          .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_ONE_SHOT)

  companion object {
    private const val SYNC_PREFERENCES_FILE = "_syncpreferences"
    private const val CHANNEL_DOWNLOAD = "downloads"
    private const val NOTIFICATION_GROUP_KEY = "group"
    private const val SYNC_ACCOUNT_NAME = "Materialistic"

    private const val EXTRA_ID = "extra:id"
    private const val EXTRA_CONNECTION_ENABLED = "extra:connectionEnabled"
    private const val EXTRA_READABILITY_ENABLED = "extra:readabilityEnabled"
    private const val EXTRA_ARTICLE_ENABLED = "extra:articleEnabled"
    private const val EXTRA_COMMENTS_ENABLED = "extra:commentsEnabled"
    private const val EXTRA_NOTIFICATION_ENABLED = "extra:notificationEnabled"

    @UiThread
    fun scheduleSync(context: Context, job: Job) {
      if (!Preferences.Offline.isEnabled(context)) return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !job.id.isEmpty()) {
        val builder = JobInfo.Builder(job.id.toInt(),
            ComponentName(context.packageName, ItemSyncJobService::class.java.name)).apply {
          setRequiredNetworkType(if (Preferences.Offline.isWifiOnly(context))
            JobInfo.NETWORK_TYPE_UNMETERED
          else
            JobInfo.NETWORK_TYPE_ANY)
          setExtras(job.toPersistableBundle())
          if (Preferences.Offline.currentConnectionEnabled(context)) {
            setOverrideDeadline(0)
          }
        }
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(builder.build())
      } else {
        val accounts = AccountManager.get(context).getAccountsByType(BuildConfig.APPLICATION_ID)
        val syncAccount = if (accounts.isEmpty()) {
          Account(SYNC_ACCOUNT_NAME, BuildConfig.APPLICATION_ID).apply {
            AccountManager.get(context).addAccountExplicitly(this, null, null)
          }
        } else {
          accounts[0]
        }
        ContentResolver.requestSync(syncAccount,
            SyncContentProvider.PROVIDER_AUTHORITY,
            Bundle(job.toBundle()).apply { putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) })
      }
    }
  }

  interface ProgressListener {
    fun onDone(token: String)
  }

  data class Job internal constructor(
        internal val id: String,
        internal var connectionEnabled: Boolean,
        internal val readabilityEnabled: Boolean,
        internal val articleEnabled: Boolean,
        internal val commentsEnabled: Boolean,
        private val notificationEnabled: Boolean) {

    constructor(context: Context, itemId: String? = null) : this(itemId ?: "",
        connectionEnabled = Preferences.Offline.currentConnectionEnabled(context),
        readabilityEnabled = Preferences.Offline.isReadabilityEnabled(context),
        articleEnabled = Preferences.Offline.isArticleEnabled(context),
        commentsEnabled = Preferences.Offline.isCommentsEnabled(context),
        notificationEnabled = Preferences.Offline.isNotificationEnabled(context)
    )

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    constructor(bundle: PersistableBundle) : this(
        bundle.getString(EXTRA_ID) ?: "",
        bundle.getInt(EXTRA_CONNECTION_ENABLED) == 1,
        bundle.getInt(EXTRA_READABILITY_ENABLED) == 1,
        bundle.getInt(EXTRA_ARTICLE_ENABLED) == 1,
        bundle.getInt(EXTRA_COMMENTS_ENABLED) == 1,
        bundle.getInt(EXTRA_NOTIFICATION_ENABLED) == 1)

    constructor(bundle: Bundle) : this(
        bundle.getString(EXTRA_ID) ?: "",
        bundle.getInt(EXTRA_CONNECTION_ENABLED) == 1,
        bundle.getInt(EXTRA_READABILITY_ENABLED) == 1,
        bundle.getInt(EXTRA_ARTICLE_ENABLED) == 1,
        bundle.getInt(EXTRA_COMMENTS_ENABLED) == 1,
        bundle.getInt(EXTRA_NOTIFICATION_ENABLED) == 1)

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun toPersistableBundle() = PersistableBundle().apply {
      putString(EXTRA_ID, id)
      putInt(EXTRA_CONNECTION_ENABLED, if (connectionEnabled) 1 else 0)
      putInt(EXTRA_CONNECTION_ENABLED, if (readabilityEnabled) 1 else 0)
      putInt(EXTRA_ARTICLE_ENABLED, if (articleEnabled) 1 else 0)
      putInt(EXTRA_COMMENTS_ENABLED, if (commentsEnabled) 1 else 0)
      putInt(EXTRA_NOTIFICATION_ENABLED, if (notificationEnabled) 1 else 0)
    }

    fun toBundle() = Bundle().apply {
      putString(EXTRA_ID, id)
      putInt(EXTRA_CONNECTION_ENABLED, if (connectionEnabled) 1 else 0)
      putInt(EXTRA_CONNECTION_ENABLED, if (readabilityEnabled) 1 else 0)
      putInt(EXTRA_ARTICLE_ENABLED, if (articleEnabled) 1 else 0)
      putInt(EXTRA_COMMENTS_ENABLED, if (commentsEnabled) 1 else 0)
      putInt(EXTRA_NOTIFICATION_ENABLED, if (notificationEnabled) 1 else 0)
    }
  }

  private class SyncProgress(job: Job) {
    private val id: String = job.id
    private var self: Boolean? = null
    private var totalKids: Int = if (job.commentsEnabled) 1 else 0
    private var finishedKids: Int = 0
    private var webProgress: Int = 0
    private var maxWebProgress: Int = if (job.articleEnabled) 100 else 0
    private var readability: Boolean? = if (job.readabilityEnabled) false else null

    internal var title: String? = null
      private set

    internal val max
      get() = 1 + totalKids + if (readability != null) 1 else 0 + maxWebProgress

    internal val progress
      get() = if (self != null) 1 else 0 + finishedKids + if (readability == true) 1 else 0 + webProgress

    fun finishItem(id: String, item: HackerNewsItem?, kidsEnabled: Boolean, readabilityEnabled: Boolean) =
        if (this.id == id) {
          finishSelf(item, kidsEnabled, readabilityEnabled)
        } else {
          finishKid()
        }

    fun finishReadability() {
      readability = true
    }

    fun updateArticle(webProgress: Int, maxWebProgress: Int) {
      this.webProgress = webProgress
      this.maxWebProgress = maxWebProgress
    }

    private fun finishSelf(item: HackerNewsItem?, kidsEnabled: Boolean, readabilityEnabled: Boolean) {
      self = item != null
      title = item?.title
      totalKids = if (kidsEnabled) item?.kids?.size ?: 0 else 0
      readability = if (readabilityEnabled) false else null
    }

    private fun finishKid() {
      finishedKids++
    }
  }
}
