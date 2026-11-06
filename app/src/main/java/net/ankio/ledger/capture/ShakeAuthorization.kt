package net.ankio.ledger.capture

import org.json.JSONObject

/** Native consent captured before pixels; never reconstructed from the current selection. */
data class ShakeAuthorization(val packageName:String,val masterGeneration:Long,val applicationGeneration:Long) {
    init {
        require(validPackage(packageName) && masterGeneration>=0 && applicationGeneration>=0)
    }
    fun encode():String=JSONObject().put("type","SHAKE").put("packageName",packageName)
        .put("masterGeneration",masterGeneration).put("applicationGeneration",applicationGeneration).toString()
    companion object {
        internal fun validPackage(value:String)=value.length in 3..255 &&
            Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)
        fun decode(value:String?):ShakeAuthorization? {
            if(value.isNullOrBlank() || value.length>1_024)return null
            return runCatching {
                val json=JSONObject(value)
                require(json.keys().asSequence().toSet()==setOf("type","packageName","masterGeneration","applicationGeneration"))
                require(json.get("type")=="SHAKE" && json.get("packageName") is String)
                fun integer(key:String):Long {
                    val raw=json.get(key)
                    require(raw is Int || raw is Long)
                    return (raw as Number).toLong().also { require(it>=0) }
                }
                ShakeAuthorization(json.getString("packageName"),integer("masterGeneration"),integer("applicationGeneration"))
            }.getOrNull()
        }
    }
}
