package eu.kanade.tachiyomi.animeextension.zh.iyf

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

open class PairSelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
    val key: String,
) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected
        get() = options[state].second
}

class TypeFilter(options: List<Pair<String, String>> = DEFAULT_TYPES) :
    PairSelectFilter("类型", options, "cid")

class RegionFilter(options: List<Pair<String, String>> = DEFAULT_REGIONS) :
    PairSelectFilter("地区", options, "region")

class LangFilter(options: List<Pair<String, String>> = DEFAULT_LANG) :
    PairSelectFilter("语言", options, "language")

class YearFilter(options: List<Pair<String, String>> = DEFAULT_YEAR) :
    PairSelectFilter("年份", options, "year")

class QualityFilter(options: List<Pair<String, String>> = DEFAULT_QUALITY) :
    PairSelectFilter("画质", options, "vipResource")

class StatusFilter(options: List<Pair<String, String>> = DEFAULT_STATUS) :
    PairSelectFilter("状态", options, "isserial")

class SortFilter(private val options: List<Pair<String, String>> = DEFAULT_SORT) :
    AnimeFilter.Sort("排序", options.map { it.first }.toTypedArray(), Selection(0, false)) {
    val desc
        get() = if (state?.ascending == true) {
            0
        } else {
            1
        }
    val orderBy
        get() = options[state?.index ?: 0].second
}

//region default filter config
private val DEFAULT_TYPES = listOf(
    "全部板块" to "0,1",
    "动漫" to "0,1,6",
    "电影" to "0,1,3",
    "电视剧" to "0,1,4",
    "综艺" to "0,1,5",
    "体育" to "0,1,95",
    "纪录片" to "0,1,7",
    "动漫-热血" to "0,1,6,46",
    "动漫-格斗" to "0,1,6,47",
    "动漫-机战" to "0,1,6,48",
    "动漫-少女" to "0,1,6,49",
    "动漫-竞技" to "0,1,6,51",
    "动漫-科幻" to "0,1,6,52",
    "动漫-魔幻" to "0,1,6,53",
    "动漫-爆笑" to "0,1,6,54",
    "动漫-推理" to "0,1,6,55",
    "动漫-冒险" to "0,1,6,121",
    "动漫-恋爱" to "0,1,6,120",
    "动漫-校园" to "0,1,6,119",
    "动漫-治愈" to "0,1,6,118",
    "动漫-泡面" to "0,1,6,117",
    "动漫-穿越" to "0,1,6,116",
    "动漫-灵异" to "0,1,6,56",
    "动漫-耽美" to "0,1,6,122",
    "动漫-剧场版" to "0,1,6,57",
    "动漫-其它" to "0,1,6,58",
    "电影-喜剧" to "0,1,3,19",
    "电影-爱情" to "0,1,3,20",
    "电影-动作" to "0,1,3,21",
    "电影-犯罪" to "0,1,3,22",
    "电影-科幻" to "0,1,3,23",
    "电影-奇幻" to "0,1,3,24",
    "电影-冒险" to "0,1,3,25",
    "电影-灾难" to "0,1,3,26",
    "电影-恐怖" to "0,1,3,123",
    "电影-惊悚" to "0,1,3,27",
    "电影-剧情" to "0,1,3,28",
    "电影-战争" to "0,1,3,29",
    "电影-歌舞" to "0,1,3,30",
    "电影-经典" to "0,1,3,31",
    "电影-悬疑" to "0,1,3,32",
    "电影-动画" to "0,1,3,113",
    "电影-同性" to "0,1,3,124",
    "电影-网络电影" to "0,1,3,125",
    "电视剧-偶像" to "0,1,4,129",
    "电视剧-爱情" to "0,1,4,146",
    "电视剧-言情" to "0,1,4,127",
    "电视剧-古装" to "0,1,4,126",
    "电视剧-历史" to "0,1,4,141",
    "电视剧-玄幻" to "0,1,4,142",
    "电视剧-谍战" to "0,1,4,136",
    "电视剧-历险" to "0,1,4,143",
    "电视剧-都市" to "0,1,4,132",
    "电视剧-科幻" to "0,1,4,144",
    "电视剧-军旅" to "0,1,4,135",
    "电视剧-喜剧" to "0,1,4,133",
    "电视剧-武侠" to "0,1,4,128",
    "电视剧-江湖" to "0,1,4,145",
    "电视剧-罪案" to "0,1,4,138",
    "电视剧-青春" to "0,1,4,131",
    "电视剧-家庭" to "0,1,4,130",
    "电视剧-战争" to "0,1,4,134",
    "电视剧-悬疑" to "0,1,4,137",
    "电视剧-穿越" to "0,1,4,139",
    "电视剧-宫廷" to "0,1,4,140",
    "电视剧-神话" to "0,1,4,147",
    "电视剧-商战" to "0,1,4,148",
    "电视剧-警匪" to "0,1,4,149",
    "电视剧-动作" to "0,1,4,150",
    "电视剧-惊悚" to "0,1,4,151",
    "电视剧-剧情" to "0,1,4,152",
    "电视剧-同性" to "0,1,4,153",
    "电视剧-奇幻" to "0,1,4,154",
    "综艺-真人秀" to "0,1,5,39",
    "综艺-选秀" to "0,1,5,38",
    "综艺-网综" to "0,1,5,94",
    "综艺-脱口秀" to "0,1,5,43",
    "综艺-搞笑" to "0,1,5,40",
    "综艺-竞技" to "0,1,5,91",
    "综艺-情感" to "0,1,5,33",
    "综艺-访谈" to "0,1,5,34",
    "综艺-演唱会" to "0,1,5,44",
    "综艺-晚会" to "0,1,5,92",
    "综艺-其它" to "0,1,5,45",
    "体育-奥运" to "0,1,95,99",
    "体育-综合" to "0,1,95,98",
    "体育-篮球" to "0,1,95,97",
    "体育-足球" to "0,1,95,96",
    "纪录片-文化" to "0,1,7,50",
    "纪录片-探索" to "0,1,7,59",
    "纪录片-军事" to "0,1,7,60",
    "纪录片-解密" to "0,1,7,61",
    "纪录片-科技" to "0,1,7,62",
    "纪录片-历史" to "0,1,7,63",
    "纪录片-人物" to "0,1,7,64",
    "纪录片-自然" to "0,1,7,66",
    "纪录片-其它" to "0,1,7,67",
)
private val DEFAULT_REGIONS = listOf(
    "全部地区" to "",
    "大陆" to "大陆",
    "香港" to "香港",
    "台湾" to "台湾",
    "日本" to "日本",
    "韩国" to "韩国",
    "欧美" to "欧美",
    "英国" to "英国",
    "泰国" to "泰国",
    "其它" to "其它",
)
private val DEFAULT_LANG = listOf(
    "全部语言" to "",
    "国语" to "国语",
    "粤语" to "粤语",
    "英语" to "英语",
    "韩语" to "韩语",
    "日语" to "日语",
    "西班牙语" to "西班牙语",
    "法语" to "法语",
    "德语" to "德语",
    "意大利语" to "意大利语",
    "泰国语" to "泰国语",
    "其它" to "其它",
)
private val DEFAULT_YEAR = listOf(
    "全部年份" to "",
    "今年" to "今年",
    "去年" to "去年",
    "更早" to "更早",
    "90年代" to "90年代",
    "80年代" to "80年代",
    "怀旧" to "怀旧",
)
private val DEFAULT_STATUS = listOf(
    "全部" to "-1",
    "全集" to "0",
    "连载中" to "1",
)
private val DEFAULT_QUALITY = listOf(
    "全部画质" to "",
    "4K" to "4K",
    "1080P" to "1080P",
    "900P" to "900P",
    "720P" to "720P",
)
private val DEFAULT_SORT = listOf(
    "添加时间" to "0",
    "更新时间" to "1",
    "人气高低" to "2",
    "评分高低" to "3",
)
//endregion
