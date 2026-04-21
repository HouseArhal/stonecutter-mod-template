@file:Suppress("unused")

import org.gradle.api.NamedDomainObjectContainer
import java.util.*

data class ManifestContext(
	val id: String,
	val name: String,
	val version: String,
	val group: String,
	val description: String,
	val license: String,
	val authors: List<String>,
	val contributors: List<String>,
	val homepageUrl: String,
	val sourcesUrl: String,
	val issuesUrl: String,
	val discordUrl: String,
	val minecraft: String,
	val deps: DependenciesConfig,
)

sealed class Loader(val id: String) {
	abstract val jarTask: String
	abstract val sourcesJarTask: String
	abstract val modManifestPath: String
	abstract val excludedResources: List<String>

	open val isFabricLike: Boolean = false

	abstract fun generateManifest(ctx: ManifestContext): String

	sealed class FabricLike(id: String) : Loader(id) {
		override val isFabricLike = true
		override val excludedResources = listOf(
			"META-INF/mods.toml", "META-INF/neoforge.mods.toml", "aw/*.cfg", ".cache", "pack.mcmeta"
		)

		override fun generateManifest(ctx: ManifestContext): String {
			val depBlock = buildFabricLikeDependencies(ctx.deps).takeIf { it.isNotEmpty() }?.let { ",\n$it" } ?: ""
			return """
                {
                  "schemaVersion": 1,
                  "id": "${ctx.id}",
                  "name": "${ctx.name}",
                  "version": "${ctx.version}",
                  "authors": [${ctx.authors.joinToString(", ") { "\"$it\"" }}],
                  "contributors": [${ctx.contributors.joinToString(", ") { "\"$it\"" }}],
                  "contact": {
                    "sources": "${ctx.sourcesUrl}",
                    "issues": "${ctx.issuesUrl}",
                    "homepage": "${ctx.homepageUrl}"
                  },
                  "custom": {
                    "modmenu": {
                      "links": {
                        "modmenu.discord": "${ctx.discordUrl}"
                      }
                    }
                  },
                  "description": "${ctx.description}",
                  "icon": "assets/icon.png",
                  "license": "${ctx.license}",
                  "environment": "*",
                  "accessWidener": "aw/${ctx.minecraft}.accesswidener",
                  "entrypoints": {
                    "main": ["${ctx.group}.${ctx.id}.platform.fabric.FabricEntrypoint"],
                    "client": ["${ctx.group}.${ctx.id}.platform.fabric.FabricClientEntrypoint"],
                    "fabric-datagen": ["${ctx.group}.${ctx.id}.platform.fabric.datagen.FabricDataGeneratorEntrypoint"]
                  },
                  "mixins": ["${ctx.id}.mixins.json"]$depBlock
                }
            """.trimIndent()
		}
	}

	object FabricM : FabricLike("fabricm") {
		override val jarTask = "remapJar"
		override val sourcesJarTask = "remapSourcesJar"
		override val modManifestPath = "fabric.mod.json"
	}

	object FabricO : FabricLike("fabrico") {
		override val jarTask = "remapJar"
		override val sourcesJarTask = "remapSourcesJar"
		override val modManifestPath = "fabric.mod.json"
	}


	sealed class ForgeLike(id: String) : Loader(id) {
		override val jarTask = "jar"
		override val sourcesJarTask = "sourcesJar"
		override val excludedResources = listOf(
			"fabric.mod.json", "aw/*.accesswidener", ".cache", "pack.mcmeta"
		)

		protected fun tomlBase(ctx: ManifestContext): String = """
            modLoader = "javafml"
            loaderVersion = "[2,)"
            license = "${ctx.license}"
            issueTrackerURL = "${ctx.issuesUrl}"

            [[mods]]
            modId = "${ctx.id}"
            displayName = "${ctx.name}"
            version = "${ctx.version}"
            displayURL = "${ctx.homepageUrl}"
            modUrl = "${ctx.homepageUrl}"
            logoFile = "assets/icon.png"
            authors = "${ctx.authors.joinToString(", ")}"
            logoBlur = false
            credits = "${ctx.authors.joinToString(", ")} Contributors: ${ctx.contributors.joinToString(", ")}"

            description = '''${ctx.description}'''

            [[mixins]]
            config = "${ctx.id}.mixins.json"
        """.trimIndent()
	}

	object NeoForge : ForgeLike("neoforge") {
		override val modManifestPath = "META-INF/neoforge.mods.toml"
		override val excludedResources = super.excludedResources + "META-INF/mods.toml"

		override fun generateManifest(ctx: ManifestContext): String =
			tomlBase(ctx) + "\n" + buildForgeLikeDependencies(ctx.id, ctx.deps)
	}

	object Forge : ForgeLike("forge") {
		override val modManifestPath = "META-INF/mods.toml"
		override val excludedResources = super.excludedResources + "META-INF/neoforge.mods.toml"
		val mixinConfigAttribute = "MixinConfigs"

		override fun generateManifest(ctx: ManifestContext): String =
			tomlBase(ctx) + "\n" + buildForgeLikeDependencies(ctx.id, ctx.deps)
	}

	companion object {
		fun of(id: String): Loader = when (id) {
			"fabrico" -> FabricO
			"fabricm" -> FabricM
			"neoforge" -> NeoForge
			"forge" -> Forge
			else -> error("Unknown loader: '$id'")
		}
	}
}

private fun buildFabricLikeDependencies(deps: DependenciesConfig): String = buildString {
	fun jsonGroup(name: String, container: NamedDomainObjectContainer<Dependency>): String? {
		if (container.isEmpty()) return null
		val entries = container.joinToString(",\n    ") {
			"\"${it.modid.get()}\": \"${it.fabricLikeVersionRange.get()}\""
		}
		return "  \"$name\": {\n    $entries\n  }"
	}

	val groups = listOfNotNull(
		jsonGroup("depends", deps.required),
		jsonGroup("recommends", deps.optional),
		jsonGroup("breaks", deps.incompatible)
	)
	if (groups.isNotEmpty()) append(groups.joinToString(",\n"))
}

private fun buildForgeLikeDependencies(modId: String, deps: DependenciesConfig): String = buildString {
	fun appendBlock(container: NamedDomainObjectContainer<Dependency>, type: String) {
		container.forEach {
			appendLine(
				"""
                [[dependencies.$modId]]
                modId = "${it.modid.get()}"
                side = "${it.environment.get().uppercase(Locale.getDefault())}"
                versionRange = "${it.forgeLikeVersionRange.get()}"
                mandatory = ${type == "required"}
                type = "$type"
            """.trimIndent()
			)
		}
	}
	appendBlock(deps.required, "required")
	appendBlock(deps.optional, "optional")
	appendBlock(deps.incompatible, "incompatible")
}
