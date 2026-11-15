package pl.home.channeldeck

data class Group(val id: String, val name: String, val count: Int, val region: String = "")
/** Everything before the "|" in a group name, e.g. UK, IE, PL. */
data class Region(val id: String, val name: String, val groups: Int, val channels: Int)
data class Channel(val id: String, val name: String, val groupId: String, val num: Int, val icon: String, val epgId: String)
/** start/stop are real epoch seconds (server clock offset already removed). */
data class Prog(val title: String, val desc: String, val start: Long, val stop: Long)
