import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParseException
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
    // one-time setup to populate local data
    populatePackageCacheIfNeeded()

    // build the dependency graph between Elm packages
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


fun populatePackageCacheIfNeeded() {
    val packageList = mapper.readValue<List<String>>(File(packageListJsonPath))
    println("Got ${packageList.size} Elm packages")

    val versionTable = makeVersionLookupTable()

    for (packageName in packageList) {
        val file = File(makeCachePath(packageName))

        if (file.exists()) {
            println("Skipping $packageName")
            continue
        }

        val version = versionTable[packageName]
        if (version == null) {
            println("Could not find $packageName in the version table. Skipping.")
            continue
        }

        println("Fetching $packageName $version")
        val mainDeps = fetchLegacyPackageManifest(makeUrl(packageName, version))
        Thread.sleep(50)
        val testDeps = fetchLegacyPackageManifest(makeUrl(packageName, version, isTests = true))
        Thread.sleep(50)
        val deps = (mainDeps + testDeps).distinct()
        val packageSummary = ElmPackageSummary(packageName, version, deps)

        file.parentFile.mkdirs()
        file.createNewFile()
        mapper.writeValue(file, packageSummary)
    }
}

private fun fetchLegacyPackageManifest(url: String): List<String> {
    val (_, _, result) = Fuel.get(url).responseString()
    val (data, error) = result
    if (error == null) {
        println("$url -> OK")
        val tree = try {
            mapper.readTree(data)
        } catch (e: JsonParseException) {
            println("$url -> EXCEPTION $e")
            return emptyList()
        }
        return tree.get("dependencies")?.fields()?.asSequence()?.map { it.key }?.toList()
                ?: emptyList()
    } else {
        println("$url -> ERROR $error")
        return emptyList()
    }
}


private fun makeVersionLookupTable(): Map<String, String> {
    return mapper.readValue<List<ElmFullPackage>>(File(fullPackageListJsonPath))
            .associateBy { it.name }
            .mapValues { (_, pkg) -> pkg.versions.first() }
}


fun buildGraph(): DirectedGraph<String, DefaultEdge> {
    val g = DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge::class.java)

    val packageSummaries = File(packageCacheRoot).walkTopDown()
            .filter { it.name == "summary.json" }
            .map { mapper.readValue<ElmPackageSummary>(it) }

    packageSummaries.forEach { g.addVertex(it.name) }

    packageSummaries.forEach { pkg ->
        pkg.dependencies.forEach {
            if (!g.containsVertex(it))
                println("Skipping missing dep from ${pkg.name} to $it")
            else
                g.addEdge(pkg.name, it)
        }
    }

    return g
}


fun makeUrl(packageName: String, version: String, isTests: Boolean = false) =
        "https://raw.githubusercontent.com/$packageName/$version/" +
                (if (isTests) "tests/" else "") + "elm-package.json"

fun makeCachePath(packageName: String) =
        "$packageCacheRoot/$packageName/summary.json"


// what we will cache on disk
data class ElmPackageSummary(
        val name: String,
        val version: String,
        val dependencies: List<String>)


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
