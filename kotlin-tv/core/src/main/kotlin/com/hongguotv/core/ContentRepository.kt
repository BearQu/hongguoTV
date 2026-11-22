// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val devices = PlayerDeviceSource(http)
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicRanking(page)
        val data=router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // The website search does not constrain content type. Never use it for comics.
        if(type==ContentType.COMIC) return appSearch(keyword,page,type)
        try { val result=appSearch(keyword,page,type); if(result.items.isNotEmpty() || !result.hasMore) return result } catch (e: Exception) { if(e is SearchSessionExpiredException) throw e; /* Website fallback for short dramas only. */ }
        val data=router("$site/search/${Signer.encode(keyword)}?page=$page")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items=valid(rows.objects().map(::card))
        return CatalogPage(items,items.isNotEmpty() && page<100)
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=VendorConstants.device.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        val data=JSONObject(text(signed.url,signed))
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        // The player endpoints reject the legacy embedded device id (error 110001),
        // so resolve via freshly registered devices like fqapp does, re-registering
        // once when upstream rejects the signature. The old fixed-device call
        // stays as a last-resort fallback.
        for (attempt in 0..2) {
            try { return playerStream(id, maxQuality) }
            catch (e: DeviceRejectedException) { devices.invalidate() }
            catch (e: Exception) { break }
        }
        return legacyStream(id, maxQuality)
    }
    private fun playerStream(id: String, maxQuality: Int): StreamInfo {
        val device = devices.obtain()
        val rows = mutableListOf<PlayerVariant>()
        var rejected = false
        var sawResponse = false
        var sawEmpty = false
        var transportFailure: Exception? = null
        val cookieHeader = if (device.cookies.isBlank()) emptyMap() else mapOf("Cookie" to device.cookies)
        val phoenixHeaders = mapOf("User-Agent" to VendorConstants.VIDEO_UA, "Accept" to "application/json; charset=utf-8,application/x-protobuf", "Content-Type" to "application/json", "x-xs-from-web" to "0", "sdk-version" to "2") + cookieHeader
        // The official red-novel app calls /multi_video_model/:dr_scene/v1/ with
        // dr_scene=default; that variant returns a full model where the old paths
        // answer HTTP 200 with an empty body. Try it first.
        val requests = listOf(
            Triple(PlayerDeviceSource.substituteDevice(PlayerDeviceSource.PHOENIX_DEFAULT_URL, device), PlayerDeviceSource.phoenixBody(id), phoenixHeaders),
            Triple(PlayerDeviceSource.substituteDevice(PlayerDeviceSource.PHOENIX_URL, device), PlayerDeviceSource.phoenixBody(id), phoenixHeaders),
            Triple(PlayerDeviceSource.substituteDevice(PlayerDeviceSource.PHOENIX_SINGLE_URL, device), PlayerDeviceSource.shortplayBody(id), phoenixHeaders),
            Triple(PlayerDeviceSource.substituteDevice(PlayerDeviceSource.SHORTPLAY_URL, device), PlayerDeviceSource.shortplayBody(id), mapOf("User-Agent" to "com.dragon.read", "Content-Type" to "application/json; charset=utf-8") + cookieHeader),
        )
        for ((url, body, headers) in requests) {
            // Upstream intermittently short-circuits signing with a blank 200;
            // one immediate retry on the same endpoint usually recovers.
            for (attempt in 0..1) {
                try {
                    val signed = PlayerSigner.headers(url.substringAfter('?', ""), body)
                    val text = fetch(url, headers + signed, body)
                    if (text.isBlank()) { sawEmpty = true; continue }
                    sawResponse = true
                    val data = JSONObject(text)
                    val code = data.optInt("code", data.optInt("Code", 0))
                    if (code == 10001 || code == 10002 || code == 110001) throw DeviceRejectedException("播放接口拒绝该设备")
                    for (model in StreamModels.models(data.optJSONObject("data") ?: JSONObject())) {
                        rows += StreamModels.listRows(model.optJSONObject("video_list"), StreamModels.keySeed(model))
                        rows += StreamModels.arrayRows(model.optJSONArray("video_list"))
                        val fallbackUrl = StreamModels.fallbackApiUrl(model)
                        if (fallbackUrl.startsWith("https://")) runCatching {
                            val fallback = JSONObject(fetch(fallbackUrl, mapOf("User-Agent" to PlayerDeviceSource.FALLBACK_USER_AGENT, "Accept-Language" to "zh-CN,zh;q=0.9"), null))
                            val info = fallback.optJSONObject("video_info")?.optJSONObject("data")
                            rows += StreamModels.listRows(info?.optJSONObject("video_list"), info?.str("key_seed")?.takeIf { it.isNotEmpty() }?.let(StreamModels::lenientB64))
                        }
                    }
                    break
                } catch (e: DeviceRejectedException) { rejected = true; break }
                catch (e: Exception) { transportFailure = e }
            }
        }
        // Official App also exposes the model via GET /reading/bookapi/video_tab/...;
        // use it as one more fallback when the POST variants are short-circuited.
        if (!rejected) runCatching { rows += tabVideoRows(id, device) }
        val chosen = StreamModels.pick(rows, maxQuality)
            ?: run {
                if (rejected) throw DeviceRejectedException("播放设备已被上游拒绝")
                if (!sawResponse) throw transportFailure ?: IOException("播放接口暂不可用")
                throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
            }
        val quality = if (chosen.quality > 0) "${chosen.quality}P" + (if (chosen.quality > maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(chosen.url, chosen.key, quality)
    }
    private fun tabVideoRows(id: String, device: PlayerDevice): List<PlayerVariant> {
        val values = VendorConstants.device.toMutableMap()
        values["device_id"] = device.deviceId
        values["iid"] = device.installId
        values.putAll(mapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "",
        ))
        val cookieHeader = if (device.cookies.isBlank()) emptyMap() else mapOf("Cookie" to device.cookies)
        val signed = signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/", values)
        val text = fetch(signed.url, signed.headers + cookieHeader, signed.body)
        if (text.isBlank()) return emptyList()
        val root = JSONObject(text)
        if (root.optInt("code", root.optInt("Code", -1)) != 0) return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()
        val container = data.optJSONObject("video_model_data") ?: data
        val row = container.optJSONObject(id)
            ?: container.keys().asSequence().mapNotNull { container.optJSONObject(it) }.firstOrNull()
            ?: return emptyList()
        val model = row.optJSONObject("video_model") ?: JSONObject(row.str("video_model"))
        val seed = StreamModels.keySeed(model)
        val rows = StreamModels.listRows(model.optJSONObject("video_list"), seed) +
            StreamModels.arrayRows(model.optJSONArray("video_list"))
        // Upstream 0.16.13: when the tab model is empty or exposes byteVC2 only,
        // the same identity on the player endpoint (level 1) can supply HEVC.
        // Keep byteVC2 as a selectable fallback via local transcode below.
        if (rows.isNotEmpty() && rows.none { it.byteVc2 }) return rows
        val playerRows = runCatching { playerModelRows(id, device) }.getOrNull().orEmpty()
        val compatible = playerRows.filter { !it.byteVc2 }
        return rows + (compatible.ifEmpty { playerRows })
    }

    /** level-1 player endpoint from upstream 0.16.13, for the byteVC2-only fallback.
     *  Mirrors MobilePlayback.ladder: level-1 biz_param in a gzip body, dr_scene=player. */
    private fun playerModelRows(id: String, device: PlayerDevice): List<PlayerVariant> {
        val values = VendorConstants.device.toMutableMap()
        values["device_id"] = device.deviceId
        values["iid"] = device.installId
        values["_rticket"] = clock().toString()
        val payload = JSONObject()
            .put("dr_scene", "player")
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 1)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", false)
                .put("video_platform", 1024))
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(id)))
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val body = output.toByteArray()
        val signed = signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1/", values, body)
        val headers = signed.headers + mapOf("Content-Encoding" to "gzip") +
            if (device.cookies.isBlank()) emptyMap() else mapOf("Cookie" to device.cookies)
        val text = fetch(signed.url, headers, signed.body)
        if (text.isBlank()) return emptyList()
        val root = JSONObject(text)
        if (root.optInt("code", root.optInt("Code", -1)) != 0) return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()
        return StreamModels.models(data).flatMap { model ->
            val seed = StreamModels.keySeed(model)
            StreamModels.listRows(model.optJSONObject("video_list"), seed) +
                StreamModels.arrayRows(model.optJSONArray("video_list"))
        }
    }

    private fun fetch(url: String, headers: Map<String, String>, body: ByteArray?): String {
        val request = Request.Builder().url(url).header("Accept-Language", "zh-CN,zh;q=0.9")
        headers.forEach { (k, v) -> request.header(k, v) }
        body?.let { request.post(it.toRequestBody("application/json; charset=UTF-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (response.code == 401 || response.code == 403) throw DeviceRejectedException("播放请求被拒绝（HTTP ${response.code}）")
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val bodyStream = response.body ?: throw IOException("内容响应为空")
            if (bodyStream.contentLength() > 16 * 1024 * 1024) throw IOException("内容响应过大")
            val input = bodyStream.byteStream(); val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() <= 16 * 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
            if (out.size() > 16 * 1024 * 1024) throw IOException("内容响应过大")
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }
    private fun legacyStream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/",VendorConstants.device,payload)
        val rawResponse=runCatching { text(signed.url,signed) }.getOrNull()?.takeIf { it.isNotBlank() } ?: throw IOException("播放接口暂不可用")
        val data=runCatching { JSONObject(rawResponse) }.getOrNull() ?: throw IOException("播放响应格式异常")
        if(data.optInt("Code",data.optInt("code",0))!=0) throw IOException("播放接口暂不可用")
        val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val entry=map.optJSONObject(id) ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull() ?: throw IOException("播放信息为空")
        val model=entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // byteVC2 is now a low-priority but selectable variant; the app transcodes
        // it locally via TranscodingVideoDataSource. Keep it in the list, ranked
        // below hard-decodable renditions, but drop rows with no playable URL.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }
            .filter { it.second.str("main_url").isNotEmpty() }
            .map { (k,o) ->
                val q=qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } })
                val codec=o.str("codec_type").lowercase()
                val score=if (codec.contains("bytevc2") || codec.contains("bvc2")) q-100000 else q
                Triple(score,k,o)
            }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
    }
    companion object {
        /** Where the app persists the registered player device (preferences store). */
        @Volatile var deviceStore: PlayerDeviceStore? = null
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
