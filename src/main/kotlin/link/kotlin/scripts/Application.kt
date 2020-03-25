package link.kotlin.scripts

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import link.kotlin.scripts.model.ApplicationConfiguration
import link.kotlin.scripts.scripting.AwesomeScriptHost
import link.kotlin.scripts.utils.CopyTask
import link.kotlin.scripts.utils.createHttpClient
import link.kotlin.scripts.utils.logger
import link.kotlin.scripts.utils.measureAndLog
import java.nio.file.Files
import java.nio.file.Files.write
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import kotlin.system.exitProcess

object Application {
    private val httpClient = createHttpClient()
    private val scriptHost = AwesomeScriptHost()
    private val mapper = jacksonObjectMapper()
    private val config = ApplicationConfiguration()

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            runBlocking {
                val projectLinks = measureAndLog("links") {
                    ProjectLinks(scriptHost).getLinks()
                }

                when (args.getOrNull(0)) {
                    "true" -> readme(projectLinks)
                    else -> {
                        val articles = measureAndLog("articles") {
                            Articles(scriptHost)
                        }
                        site(projectLinks, articles)
                        readme(projectLinks)
                    }
                }

                LOGGER.info("Done, exit.")
                exitProcess(0)
            }
        } catch (t: Throwable) {
            LOGGER.error("Unhandled error", t)
            throw t
        }
    }

    private suspend fun readme(projectLinks: Links) {
        // Stars
        measureAndLog("fetching stars") {
            val stars = DefaultStarsGenerator(config, mapper, httpClient).generate(projectLinks)
            write(Paths.get("./app/LinksWithStars.json"), stars.toByteArray(), CREATE, TRUNCATE_EXISTING)
        }

        measureAndLog("generating readme") {
            val readme = DefaultReadmeGenerator().generate(projectLinks)
            write(Paths.get("./readme/README.md"), readme.toByteArray(), CREATE, TRUNCATE_EXISTING)
        }
    }

    private suspend fun site(projectLinks: Links, articles: Articles) {
        // Output folder
        measureAndLog("creating output folders") {
            if (!Files.exists(Paths.get("./dist"))) Files.createDirectory(Paths.get("./dist"))
            if (!Files.exists(Paths.get("./dist/articles"))) Files.createDirectory(Paths.get("./dist/articles"))
        }

        measureAndLog("checking links") {
            LinkChecker(httpClient).check(
                projectLinks.flatMap { category ->
                    category.subcategories.flatMap { subcategory ->
                        subcategory.links.map {
                            it.href
                        }
                    }
                }
            )
        }

        // Sitemap
        measureAndLog("generating sitemap") {
            val sitemap = DefaultSitemapGenerator(config).generate(articles.articles())
            write(Paths.get("./dist/sitemap.xml"), sitemap.toByteArray(), CREATE, TRUNCATE_EXISTING)
        }

        // Pages
        measureAndLog("generating articles") {
            DefaultPageGenerator().generate(articles.articles())
        }

        // Copy
        measureAndLog("copying assets") {
            CopyTask.copy(mapOf(
                "pages/github.css" to "./dist/github.css",
                "pages/styles.css" to "./dist/styles.css",
                "pages/highlight.pack.js" to "./dist/highlight.pack.js",
                "robots.txt" to "./dist/robots.txt",
                "awesome-kotlin.svg" to "./dist/awesome-kotlin.svg"
            ))
        }

        // RSS
        measureAndLog("generating RSS") {
            val rssGenerator = DefaultRssGenerator(articles.articles())
            val rss = rssGenerator.generate("rss.xml", 20)
            val fullRss = rssGenerator.generate("rss-full.xml", articles.articles().size)
            write(Paths.get("./dist/rss.xml"), rss.toByteArray(), CREATE, TRUNCATE_EXISTING)
            write(Paths.get("./dist/rss-full.xml"), fullRss.toByteArray(), CREATE, TRUNCATE_EXISTING)
        }

        measureAndLog("fetching latest kotlin versions") {
            val fetcher = MavenCentralVersionFetcher(httpClient)

            val versions = fetcher.getLatestVersions(listOf("1.0", "1.1", "1.2", "1.3"))
            write(Paths.get("./versions.json"), mapper.writeValueAsBytes(versions), CREATE, TRUNCATE_EXISTING)
        }
    }

    private val LOGGER = logger<Application>()
}


