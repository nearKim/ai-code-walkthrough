package com.github.nearkim.aicodewalkthrough.util

import com.github.nearkim.aicodewalkthrough.model.ArchitectureComponent
import com.github.nearkim.aicodewalkthrough.model.CodebaseArchitecture
import com.github.nearkim.aicodewalkthrough.model.ComponentRelationship
import com.github.nearkim.aicodewalkthrough.model.DiagramSection
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import java.util.Locale

/** A self-contained, source-grounded technical reference generated from a validated flow map. */
object FlowMapTechnicalHtmlExporter {

    fun build(
        question: String?,
        flowMap: FlowMap,
        metadata: ResponseMetadata?,
    ): String = buildString {
        appendLine("<!doctype html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        appendLine("<title>${escape(flowMap.architecture?.systemName ?: question ?: "Code walkthrough")}</title>")
        appendStyle()
        appendLine("</head>")
        appendLine("<body>")
        appendLine("<header class=\"document-header\">")
        appendLine("<p class=\"eyebrow\">Grounded technical reference</p>")
        appendLine("<h1>${escape(flowMap.architecture?.systemName ?: question ?: "Code walkthrough")}</h1>")
        appendLine("<p class=\"summary\">${escape(flowMap.summary)}</p>")
        question?.takeIf(String::isNotBlank)?.let {
            appendLine("<p class=\"question\"><strong>Question:</strong> ${escape(it)}</p>")
        }
        appendLine("</header>")
        appendNavigation(flowMap)
        appendLine("<main>")
        appendSystemOverview(flowMap.architecture)
        appendComponentMap(flowMap.architecture)
        appendFeatureWalkthroughs(flowMap)
        appendGrounding(flowMap, metadata)
        appendLine("</main>")
        appendLine("</body>")
        appendLine("</html>")
    }

    private fun StringBuilder.appendStyle() {
        appendLine("""
            <style>
              :root { color-scheme: light; font-family: Arial, Helvetica, sans-serif; color: #17212b; background: #f7f8fa; }
              * { box-sizing: border-box; }
              body { margin: 0; line-height: 1.5; }
              .document-header, main, .section-nav { max-width: 1120px; margin: 0 auto; padding-left: 28px; padding-right: 28px; }
              .document-header { padding-top: 52px; padding-bottom: 28px; border-bottom: 1px solid #aebbc7; }
              .eyebrow { margin: 0 0 8px; color: #176b87; font-family: "Courier New", monospace; font-size: 12px; font-weight: 700; text-transform: uppercase; }
              h1, h2, h3, p { margin-top: 0; }
              h1 { margin-bottom: 12px; font-size: 34px; line-height: 1.15; }
              h2 { font-size: 22px; line-height: 1.25; }
              h3 { font-size: 15px; }
              .summary { max-width: 760px; font-size: 18px; }
              .question, .muted { color: #52616f; }
              .section-nav { position: sticky; top: 0; z-index: 2; max-width: none; padding-top: 10px; padding-bottom: 10px; border-bottom: 1px solid #aebbc7; background: #f7f8fa; }
              .section-nav-inner { max-width: 1064px; margin: 0 auto; display: flex; gap: 16px; overflow-x: auto; }
              .section-nav a { color: #176b87; font-family: "Courier New", monospace; font-size: 12px; text-decoration: none; white-space: nowrap; }
              main { padding-top: 32px; padding-bottom: 64px; }
              section { scroll-margin-top: 56px; margin-bottom: 48px; }
              .diagram-frame { overflow-x: auto; border: 1px solid #aebbc7; background: #fff; padding: 14px; }
              .diagram-frame svg { display: block; min-width: 720px; max-width: 100%; height: auto; }
              .diagram-node { fill: #fff; stroke: #176b87; stroke-width: 1.5; }
              .diagram-node.external { stroke: #8a5f00; }
              .diagram-edge { stroke: #176b87; stroke-width: 1.5; fill: none; }
              .diagram-edge-label, .diagram-node-label { font-family: "Courier New", monospace; font-size: 12px; fill: #17212b; }
              .diagram-edge-label { fill: #52616f; }
              .component-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 12px; }
              .component-card, .walkthrough-card { border: 1px solid #aebbc7; background: #fff; padding: 16px; }
              .component-card h3, .walkthrough-card h3 { margin-bottom: 6px; }
              .component-card code, .code-stop code { font-family: "Courier New", monospace; font-size: 12px; }
              .code-stops { margin: 18px 0 0; padding: 0; list-style: none; display: grid; gap: 8px; }
              .code-stop { border-left: 3px solid #176b87; padding: 8px 12px; background: #fff; }
              .code-stop strong { display: block; }
              .section-summary { max-width: 760px; }
              .metadata { border-top: 1px solid #aebbc7; padding-top: 22px; }
              .metadata ul { padding-left: 18px; }
              @media print { .section-nav { position: static; } body { background: #fff; } .document-header, main { max-width: none; } }
              @media (max-width: 640px) { .document-header, main { padding-left: 16px; padding-right: 16px; } .document-header { padding-top: 32px; } h1 { font-size: 28px; } }
            </style>
        """.trimIndent())
    }

    private fun StringBuilder.appendNavigation(flowMap: FlowMap) {
        val sections = featureSections(flowMap)
        appendLine("<nav class=\"section-nav\" aria-label=\"Technical reference sections\"><div class=\"section-nav-inner\">")
        appendLine("<a href=\"#system-overview\">System overview</a>")
        appendLine("<a href=\"#component-map\">Component map</a>")
        sections.forEachIndexed { index, section ->
            appendLine("<a href=\"#feature-$index\">${escape(section.title)}</a>")
        }
        appendLine("<a href=\"#grounding\">Grounding</a>")
        appendLine("</div></nav>")
    }

    private fun StringBuilder.appendSystemOverview(architecture: CodebaseArchitecture?) {
        appendLine("<section id=\"system-overview\">")
        appendLine("<p class=\"eyebrow\">01 / System overview</p>")
        appendLine("<h2>System overview</h2>")
        if (architecture == null) {
            appendLine("<p class=\"muted\">No validated architecture was returned for this walkthrough.</p>")
        } else {
            appendLine("<p>${escape(architecture.systemPurpose)}</p>")
            appendRelationshipDiagram(
                components = architecture.components,
                relationships = architecture.relationships,
                componentIds = architecture.components.mapTo(mutableSetOf()) { it.id },
            )
        }
        appendLine("</section>")
    }

    private fun StringBuilder.appendComponentMap(architecture: CodebaseArchitecture?) {
        appendLine("<section id=\"component-map\">")
        appendLine("<p class=\"eyebrow\">02 / Component map</p>")
        appendLine("<h2>Component map</h2>")
        if (architecture == null || architecture.components.isEmpty()) {
            appendLine("<p class=\"muted\">No validated components were returned.</p>")
        } else {
            appendLine("<div class=\"component-grid\">")
            architecture.components.forEach { component -> appendComponentCard(component) }
            appendLine("</div>")
        }
        appendLine("</section>")
    }

    private fun StringBuilder.appendComponentCard(component: ArchitectureComponent) {
        appendLine("<article class=\"component-card\">")
        appendLine("<p class=\"eyebrow\">${escape(component.kind.replace('_', ' '))}</p>")
        appendLine("<h3>${escape(component.name)}</h3>")
        appendLine("<p>${escape(component.responsibility)}</p>")
        component.keyPaths.takeIf { it.isNotEmpty() }?.let { paths ->
            appendLine("<p><strong>Anchors:</strong> ${paths.joinToString("<br>") { "<code>${escape(it)}</code>" }}</p>")
        }
        appendLine("</article>")
    }

    private fun StringBuilder.appendFeatureWalkthroughs(flowMap: FlowMap) {
        val architecture = flowMap.architecture
        val components = architecture?.components.orEmpty()
        val componentById = components.associateBy { it.id }
        val stepById = flowMap.steps.associateBy { it.id }
        featureSections(flowMap).forEachIndexed { index, section ->
            val sectionComponentIds = section.componentIds.toSet()
            val sectionRelationships = architecture?.relationships.orEmpty().filter {
                it.fromComponentId in sectionComponentIds || it.toComponentId in sectionComponentIds
            }
            appendLine("<section id=\"feature-$index\">")
            appendLine("<p class=\"eyebrow\">${String.format(Locale.ROOT, "%02d", index + 3)} / Feature walkthrough</p>")
            appendLine("<h2>${escape(section.title)}</h2>")
            section.summary?.takeIf(String::isNotBlank)?.let { appendLine("<p class=\"section-summary\">${escape(it)}</p>") }
            val sectionComponents = section.componentIds.mapNotNull(componentById::get)
            if (sectionComponents.isNotEmpty()) {
                appendRelationshipDiagram(sectionComponents, sectionRelationships, sectionComponentIds)
            }
            appendLine("<div class=\"walkthrough-card\">")
            appendLine("<h3>Validated code stops</h3>")
            val steps = section.stepIds.mapNotNull(stepById::get)
            if (steps.isEmpty()) {
                appendLine("<p class=\"muted\">This view has no independently navigable code stops.</p>")
            } else {
                appendLine("<ol class=\"code-stops\">")
                steps.forEach { step -> appendCodeStop(step) }
                appendLine("</ol>")
            }
            appendLine("</div>")
            appendLine("</section>")
        }
    }

    /** Each relationship receives its own row, so arrows cannot pass through unrelated nodes. */
    private fun StringBuilder.appendRelationshipDiagram(
        components: List<ArchitectureComponent>,
        relationships: List<ComponentRelationship>,
        componentIds: Set<String>,
    ) {
        val componentById = components.associateBy { it.id }
        val rows = relationships.filter { relationship ->
            relationship.fromComponentId in componentIds || relationship.toComponentId in componentIds
        }
        val height = maxOf(128, 46 + maxOf(rows.size, components.size).coerceAtLeast(1) * 94)
        appendLine("<div class=\"diagram-frame\">")
        appendLine("<svg role=\"img\" aria-label=\"Grounded component relationships\" viewBox=\"0 0 900 $height\" xmlns=\"http://www.w3.org/2000/svg\">")
        appendLine("<defs><marker id=\"arrow\" markerWidth=\"8\" markerHeight=\"8\" refX=\"7\" refY=\"4\" orient=\"auto\"><path d=\"M0,0 L8,4 L0,8 z\" fill=\"#176b87\"/></marker></defs>")
        if (rows.isEmpty()) {
            components.forEachIndexed { index, component ->
                val y = 26 + index * 84
                appendNode("node-${index}", component.name, 300, y, external = false)
            }
        } else {
            rows.forEachIndexed { index, relationship ->
                val y = 30 + index * 94
                val source = componentById[relationship.fromComponentId]
                val target = componentById[relationship.toComponentId]
                appendNode("from-$index", source?.name ?: relationship.fromComponentId, 24, y, source == null)
                appendLine("<line class=\"diagram-edge\" x1=\"284\" y1=\"${y + 26}\" x2=\"616\" y2=\"${y + 26}\" marker-end=\"url(#arrow)\"/>")
                appendLine("<text class=\"diagram-edge-label\" x=\"450\" y=\"${y + 16}\" text-anchor=\"middle\">${escape(shorten(relationship.kind.replace('_', ' '), 30))}</text>")
                appendNode("to-$index", target?.name ?: relationship.toComponentId, 640, y, target == null)
            }
        }
        appendLine("</svg>")
        appendLine("</div>")
    }

    private fun StringBuilder.appendNode(id: String, label: String, x: Int, y: Int, external: Boolean) {
        val externalClass = if (external) " external" else ""
        appendLine("<g id=\"$id\"><rect class=\"diagram-node$externalClass\" x=\"$x\" y=\"$y\" width=\"260\" height=\"52\" rx=\"2\"/><text class=\"diagram-node-label\" x=\"${x + 130}\" y=\"${y + 31}\" text-anchor=\"middle\">${escape(shorten(label, 32))}</text></g>")
    }

    private fun StringBuilder.appendCodeStop(step: FlowStep) {
        appendLine("<li class=\"code-stop\">")
        appendLine("<strong>${escape(step.title)}</strong>")
        appendLine("<code>${escape(step.filePath)}:${step.startLine}-${step.endLine}</code>")
        step.symbol?.takeIf(String::isNotBlank)?.let { appendLine("<div><code>${escape(it)}</code></div>") }
        appendLine("<span>${escape(step.explanation)}</span>")
        appendLine("</li>")
    }

    private fun StringBuilder.appendGrounding(flowMap: FlowMap, metadata: ResponseMetadata?) {
        appendLine("<section id=\"grounding\" class=\"metadata\">")
        appendLine("<p class=\"eyebrow\">Grounding</p>")
        appendLine("<h2>Coverage and provenance</h2>")
        flowMap.architecture?.coverageNotes?.takeIf { it.isNotEmpty() }?.let { notes ->
            appendLine("<ul>")
            notes.forEach { appendLine("<li>${escape(it)}</li>") }
            appendLine("</ul>")
        } ?: appendLine("<p class=\"muted\">No additional coverage notes were returned.</p>")
        metadata?.let {
            appendLine("<p class=\"muted\">${it.stepCount} validated stops across ${it.fileCount} files in ${formatDuration(it.durationMs)}.</p>")
        }
        appendLine("</section>")
    }

    private fun documentedSections(flowMap: FlowMap): List<DiagramSection> =
        flowMap.diagramSections.ifEmpty {
            flowMap.learningPath.map { stage ->
                DiagramSection(
                    id = stage.id,
                    title = stage.title,
                    summary = stage.goal,
                    componentIds = stage.componentIds,
                    stepIds = stage.stepIds,
                )
            }.ifEmpty {
                listOf(
                    DiagramSection(
                        id = "walkthrough",
                        title = "Walkthrough",
                        summary = flowMap.summary,
                        componentIds = flowMap.architecture?.components?.map { it.id }.orEmpty(),
                        stepIds = flowMap.steps.filterNot { it.broken }.map { it.id },
                    ),
                )
            }
        }

    private fun featureSections(flowMap: FlowMap): List<DiagramSection> =
        documentedSections(flowMap).filterNot { it.id in RESERVED_SECTION_IDS }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

    private fun shorten(value: String, length: Int): String =
        value.replace(Regex("\\s+"), " ").trim().let { compact ->
            if (compact.length <= length) compact else compact.take(length - 3) + "..."
        }

    private fun formatDuration(milliseconds: Long): String = "%.1fs".format(Locale.ROOT, milliseconds / 1_000.0)

    private val RESERVED_SECTION_IDS = setOf("system-overview", "component-map")
}
