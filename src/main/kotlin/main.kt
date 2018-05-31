import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.httpDownload
import com.github.kittinunf.fuel.httpGet
import org.jgrapht.DirectedGraph
import org.jgrapht.graph.DefaultDirectedGraph
import org.jgrapht.graph.DefaultEdge
import java.io.File

val mapper = ObjectMapper().registerKotlinModule()


fun main(args: Array<String>) {
    populatePackageCache()
}



fun populatePackageCache() {
    val packageList = mapper.readValue<List<String>>(File("/Users/keith/Desktop/elm18_packages.json"))
    println("Got ${packageList.size} Elm packages")

    for (packageName in packageList) {
        val file = File(makeCachePath(packageName))
        if (!file.exists()) {
            println("Fetching $packageName")
            val (_, response, result) = Fuel.get(makeUrl(packageName)).responseString()
            val (data, error) = result
            if (error == null) {
                println("$packageName -> OK")
                file.parentFile.mkdirs()
                file.createNewFile()
                file.writeText(data!!)
            } else {
                println("$packageName -> ERROR $error")
            }

            Thread.sleep(1000)
        } else {
            println("Skipping $packageName")
        }
    }
}


fun buildGraph(): DirectedGraph<String, DefaultEdge> {
    val g = DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge::class.java)



    return g
}



fun makeUrl(pkgName: String) =
        "https://raw.githubusercontent.com/$pkgName/master/elm-package.json"

fun makeCachePath(pkgName: String) =
        "/Users/keith/dev/elm-blockage/elmPackageCache/$pkgName/elm-package.json"
