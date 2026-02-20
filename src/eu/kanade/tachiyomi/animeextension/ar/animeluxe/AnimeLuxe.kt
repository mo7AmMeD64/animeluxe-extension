package eu.kanade.tachiyomi.animeextension.ar.animeluxe

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response

class AnimeLuxe : AnimeHttpSource() {

    override val name = "AnimeLuxe"
    
    // ⚠️ استبدل هذا الرابط برابط الـ Space الخاص بك على HuggingFace
    override val baseUrl = "https://YOUR_HUGGINGFACE_APP_URL.hf.space" 
    
    override val lang = "ar"
    
    override val supportsLatest = true

    // إعداد محلل الـ JSON
    private val json = Json { ignoreUnknownKeys = true }

    // ============================== 1. الصفحة الرئيسية ==============================
    override fun popularAnimeRequest(page: Int): Request = Request.Builder().url("$baseUrl/api/home").build()

    override fun popularAnimeParse(response: Response): AnimesPage {
        val array = json.decodeFromString<JsonArray>(response.body!!.string())
        val animeList = array.map {
            val obj = it.jsonObject
            SAnime.create().apply {
                title = obj["title"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = obj["image"]?.jsonPrimitive?.content ?: ""
                url = obj["link"]?.jsonPrimitive?.content ?: "" 
            }
        }
        return AnimesPage(animeList, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== 2. البحث ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return Request.Builder().url("$baseUrl/api/search?q=$query").build()
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== 3. تفاصيل الأنمي ==============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        return Request.Builder().url("$baseUrl/api/anime?url=${anime.url}").build()
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val obj = json.decodeFromString<JsonObject>(response.body!!.string())
        
        return SAnime.create().apply {
            title = obj["title"]?.jsonPrimitive?.content ?: ""
            description = obj["description"]?.jsonPrimitive?.content ?: ""
            thumbnail_url = obj["image"]?.jsonPrimitive?.content ?: ""
            genre = obj["genres"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content }
            
            val info = obj["info"]?.jsonObject
            author = info?.get("studio")?.jsonPrimitive?.content
            status = parseStatus(info?.get("status")?.jsonPrimitive?.content)
        }
    }

    private fun parseStatus(status: String?): Int {
        return when (status) {
            "مستمر" -> SAnime.ONGOING
            "مكتمل" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== 4. قائمة الحلقات ==============================
    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val obj = json.decodeFromString<JsonObject>(response.body!!.string())
        val episodesArray = obj["episodes"]?.jsonArray ?: return emptyList()
        
        return episodesArray.mapIndexed { index, element ->
            val epObj = element.jsonObject
            SEpisode.create().apply {
                name = epObj["title"]?.jsonPrimitive?.content ?: "الحلقة ${index + 1}"
                url = epObj["link"]?.jsonPrimitive?.content ?: ""
                // وضعنا ترتيب الحلقات ليتناسب مع التطبيق
                episode_number = (episodesArray.size - index).toFloat()
            }
        }
    }

    // ============================== 5. المشغلات والروابط المباشرة ==============================
    override fun videoListRequest(episode: SEpisode): Request {
        return Request.Builder().url("$baseUrl/api/watch?url=${episode.url}").build()
    }

    override fun videoListParse(response: Response): List<Video> {
        val obj = json.decodeFromString<JsonObject>(response.body!!.string())
        val serversArray = obj["servers"]?.jsonArray ?: return emptyList()
        
        val videoList = mutableListOf<Video>()
        
        // جلب السيرفرات، ثم إرسال كل سيرفر لـ endpoint الاستخراج الخاص بك
        for (element in serversArray) {
            val serverObj = element.jsonObject
            val name = serverObj["name"]?.jsonPrimitive?.content ?: "Server"
            val watchUrl = serverObj["url"]?.jsonPrimitive?.content ?: continue
            
            try {
                // نطلب من Hugging Face استخراج الرابط المباشر
                val extractReq = Request.Builder().url("$baseUrl/api/extract?url=$watchUrl").build()
                val extractRes = client.newCall(extractReq).execute()
                val extractObj = json.decodeFromString<JsonObject>(extractRes.body!!.string())
                
                val success = extractObj["success"]?.jsonPrimitive?.boolean ?: false
                if (success) {
                    val streamUrl = extractObj["stream_url"]?.jsonPrimitive?.content ?: continue
                    videoList.add(Video(streamUrl, name, streamUrl))
                }
            } catch (e: Exception) {
                // يتم تجاهل الخطأ في حال فشل استخراج هذا السيرفر المعين حتى يستمر في السيرفرات الأخرى
            }
        }
        return videoList
    }
}
