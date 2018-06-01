import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.kittinunf.fuel.Fuel
import org.jgrapht.DirectedGraph
import org.jgrapht.graph.DefaultDirectedGraph
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.EdgeReversedGraph
import org.jgrapht.traverse.DepthFirstIterator
import java.io.File

val mapper = ObjectMapper().registerKotlinModule()

// a place to store/cache the `elm-package.json` files retrieved from Github
val packageCacheRoot = "/Users/keith/dev/elm-deps-graph/elmPackageCache"

// obtained from http://package.elm-lang.org/new-packages
val packageListJsonPath = "/Users/keith/Desktop/elm18_packages.json"

// obtained from http://package.elm-lang.org/all-packages
val fullPackageListJsonPath = "/Users/keith/Desktop/elm_all_packages.json"


fun main(args: Array<String>) {
    if (false) {
        // one-time setup to populate local data
        populatePackageCache()
    }

    val dependencyGraph = buildGraph()

    // reverse the graph so that we can walk the dependencies of each package upwards
    val rg = EdgeReversedGraph(dependencyGraph)

    // for each package, collect the names of each package that depend upon it
    val acc = mutableMapOf<String, Set<String>>()
    for (packageName in rg.vertexSet()) {
        acc[packageName] = DepthFirstIterator(rg, packageName).asSequence()
                .filterNot { it == packageName }
                .toSet()
    }

    // finally rank the packages based on the number of other packages that depend on it
    println("| Package  | # of Packages that Depend on it |\n" +
            "| -------- | ------------------------------- |")
    acc.mapValues { it.value.size }
            .toList().sortedByDescending { it.second }
            .filter { it.second != 0 }
            .forEach { println("| ${it.first} | ${it.second} |") }
}


fun populatePackageCache() {
    val packageList = mapper.readValue<List<String>>(File(packageListJsonPath))
    println("Got ${packageList.size} Elm packages")

    val versionTable = makeVersionLookupTable()

    for (packageName in packageList) {
        val file = File(makeCachePath(packageName))
        if (!file.exists()) {
            val version = versionTable[packageName]!!
            println("Fetching $packageName $version")
            val (_, response, result) = Fuel.get(makeUrl(packageName, version)).responseString()
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


private fun makeVersionLookupTable(): Map<String, String> {
    return mapper.readValue<List<ElmFullPackage>>(File(fullPackageListJsonPath))
            .associateBy { it.name }
            .mapValues { (_, pkg) -> pkg.versions.first() }
}


fun buildGraph(): DirectedGraph<String, DefaultEdge> {
    val g = DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge::class.java)

    val packageTable = File(packageCacheRoot).walkTopDown()
            .filter { it.name == "elm-package.json" }
            .map {
                val pkg = mapper.readValue<ElmPackage>(it)
                val packageName = it.toRelativeString(File(packageCacheRoot))
                        .removeSuffix("/elm-package.json")
                packageName to pkg
            }
            .toMap()

    packageTable.forEach { (packageName, _) -> g.addVertex(packageName) }

    packageTable.forEach { (packageName, pkg) ->
        pkg.dependencies.keys.forEach {
            if (!g.containsVertex(it))
                println("Skipping missing dep from $packageName to $it")
            else
                g.addEdge(packageName, it)
        }
    }

    return g
}


fun makeUrl(packageName: String, version: String) =
        "https://raw.githubusercontent.com/$packageName/$version/elm-package.json"

fun makeCachePath(packageName: String) =
        "$packageCacheRoot/$packageName/elm-package.json"


// from `new-packages` API call
@JsonIgnoreProperties(ignoreUnknown = true)
data class ElmPackage(
        val version: String,
        val repository: String,
        val dependencies: Map<String, String>
)


// from `all-packages` API call
@JsonIgnoreProperties(ignoreUnknown = true)
data class ElmFullPackage(
        val name: String,
        val versions: List<String>
)
