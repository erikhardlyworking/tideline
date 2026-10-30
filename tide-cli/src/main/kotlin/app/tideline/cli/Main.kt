package app.tideline.cli

import app.tideline.core.*
import java.io.File
import kotlinx.serialization.json.*

/** Batch protocol for real reference tests; the app uses the same classes. */
fun main(arguments: Array<String>) {
    require(arguments.size >= 2) { "Usage: tide-cli DATA.tpk REQUESTS.json [OUTPUT.json]" }
    ModelPack(FileSource(arguments[0])).use { pack ->
        val model = TideModel(pack)
        val requests = Json.parseToJsonElement(File(arguments[1]).readText()).jsonArray
        val output = buildJsonArray {
            for (request in requests) {
                val data = request.jsonObject
                add(buildJsonObject {
                    put("id", data["id"]?.jsonPrimitive?.content ?: "")
                    val cells = data["cells"]?.jsonArray
                    val location = if (cells != null) null else model.locate(GeoPoint(data.getValue("lat").jsonPrimitive.double, data.getValue("lon").jsonPrimitive.double))
                    if (location is LocationResult.Unavailable) {
                        put("status", "unavailable"); put("reason", location.reason)
                    } else {
                        val selected = cells?.map { cell ->
                            val c = cell.jsonObject
                            val row = c.getValue("row").jsonPrimitive.int; val col = c.getValue("col").jsonPrimitive.int
                            SupportCell(row, col, pack.point(row, col), 0.0, c.getValue("weight").jsonPrimitive.double)
                        } ?: (location as LocationResult.Available).location.cells
                        val signal = model.signal(selected)
                        put("status", "available")
                        putJsonArray("heights") { for (time in data.getValue("epoch_seconds").jsonArray) add(signal.height(time.jsonPrimitive.double)) }
                        if (location is LocationResult.Available) {
                            put("method", location.location.method)
                            put("sea_lat", location.location.seaPoint.latitude)
                            put("sea_lon", location.location.seaPoint.longitude)
                            put("shore_snap_km", location.location.shoreSnapKm)
                            putJsonArray("support") { for (cell in selected) add(buildJsonObject { put("row", cell.row); put("col", cell.col); put("weight", cell.weight); put("distance_km", cell.distanceKm) }) }
                        }
                    }
                })
            }
        }
        if (arguments.size >= 3) File(arguments[2]).writeText(output.toString()) else println(output)
    }
}
