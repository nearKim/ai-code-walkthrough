package com.github.nearkim.aicodewalkthrough.toolwindow.cards

import com.github.nearkim.aicodewalkthrough.model.ArchitectureComponent
import com.github.nearkim.aicodewalkthrough.model.CodebaseArchitecture
import com.github.nearkim.aicodewalkthrough.model.ComponentRelationship
import com.github.nearkim.aicodewalkthrough.toolwindow.layout.ViewportWidthPanel
import com.github.nearkim.aicodewalkthrough.toolwindow.layout.WrappingTextArea
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

private const val MAP_RELATIONSHIP_LIMIT = 6

class ArchitecturePanel : JPanel(BorderLayout()) {

    private val content = ViewportWidthPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }

    init {
        add(JBScrollPane(content).apply {
            border = BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })
    }

    fun setArchitecture(architecture: CodebaseArchitecture?) {
        content.removeAll()
        if (architecture == null) {
            content.add(text("No architecture map was returned for this focused walkthrough."))
        } else {
            val names = architecture.components.associate { it.id to it.name }

            addSection("System purpose")
            content.add(text(architecture.systemPurpose))

            if (architecture.relationships.isNotEmpty()) {
                addSection("System map")
                content.add(text(systemMap(architecture.relationships, names)))
            }

            if (architecture.components.isNotEmpty()) {
                addSection("Explore a component")
                content.add(componentExplorer(architecture.components))
            }

            if (architecture.relationships.isNotEmpty()) {
                addSection("Inspect a relationship")
                content.add(relationshipExplorer(architecture.relationships, names))
            }

            if (architecture.crossCuttingConcerns.isNotEmpty()) {
                addSection("Cross-cutting concerns")
                content.add(text(architecture.crossCuttingConcerns.joinToString("\n") { "• $it" }, muted = true))
            }

            if (architecture.coverageNotes.isNotEmpty()) {
                addSection("Coverage notes")
                content.add(text(architecture.coverageNotes.joinToString("\n") { "• $it" }, warning = true))
            }
        }
        content.revalidate()
        content.repaint()
    }

    private fun systemMap(
        relationships: List<ComponentRelationship>,
        names: Map<String, String>,
    ): String = buildList {
        relationships.take(MAP_RELATIONSHIP_LIMIT).forEach { relationship ->
            add(
                "${names[relationship.fromComponentId]} " +
                    "──${humanize(relationship.kind)}──▶ ${names[relationship.toComponentId]}",
            )
        }
        if (relationships.size > MAP_RELATIONSHIP_LIMIT) {
            add("+${relationships.size - MAP_RELATIONSHIP_LIMIT} more relationships")
        }
    }.joinToString("\n")

    private fun componentExplorer(components: List<ArchitectureComponent>): JPanel {
        val detail = detail(componentText(components.first()))
        val selector = selector(components.map { "${it.name} · ${humanize(it.kind)}" })
        selector.addActionListener { detail.text = componentText(components[selector.selectedIndex]) }
        return explorer(selector, detail)
    }

    private fun relationshipExplorer(
        relationships: List<ComponentRelationship>,
        names: Map<String, String>,
    ): JPanel {
        fun label(relationship: ComponentRelationship) =
            "${names[relationship.fromComponentId]} → ${names[relationship.toComponentId]}"

        val detail = detail(relationshipText(relationships.first(), names))
        val selector = selector(relationships.map(::label))
        selector.addActionListener {
            detail.text = relationshipText(relationships[selector.selectedIndex], names)
        }
        return explorer(selector, detail)
    }

    private fun componentText(component: ArchitectureComponent): String = buildList {
        add(component.responsibility)
        if (component.keyPaths.isNotEmpty()) add("Anchors: ${component.keyPaths.joinToString(" · ")}")
        if (component.keySymbols.isNotEmpty()) add("Key symbols: ${component.keySymbols.joinToString(", ")}")
        if (component.uncertain) add("Grounding is uncertain.")
    }.joinToString("\n")

    private fun relationshipText(
        relationship: ComponentRelationship,
        names: Map<String, String>,
    ): String = buildList {
        add("${names[relationship.fromComponentId]} ──${humanize(relationship.kind)}──▶ ${names[relationship.toComponentId]}")
        add(relationship.description)
        val evidence = relationship.evidence.mapNotNull { item ->
            item.filePath?.let { path ->
                val lines = item.startLine?.let { start ->
                    if (item.endLine != null && item.endLine != start) ":$start-${item.endLine}" else ":$start"
                }.orEmpty()
                "$path$lines"
            }
        }
        if (evidence.isNotEmpty()) add("Evidence: ${evidence.joinToString(" · ")}")
        if (relationship.uncertain) add("Grounding is uncertain.")
    }.joinToString("\n")

    private fun selector(items: List<String>): JComboBox<String> = JComboBox(items.toTypedArray()).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun explorer(selector: JComboBox<String>, detail: WrappingTextArea): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        add(selector)
        add(Box.createVerticalStrut(4))
        add(detail)
    }

    private fun detail(value: String): WrappingTextArea = text(value, muted = true).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(6, 8),
        )
    }

    private fun addSection(title: String) {
        if (content.componentCount > 0) content.add(Box.createVerticalStrut(12))
        content.add(JBLabel(title).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.BOLD, font.size + 1f)
            border = JBUI.Borders.emptyBottom(4)
        })
    }

    private fun text(value: String, muted: Boolean = false, warning: Boolean = false): WrappingTextArea =
        WrappingTextArea().apply {
            text = value
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            foreground = when {
                warning -> JBColor(Color(150, 95, 30), Color(220, 170, 90))
                muted -> JBColor(Color(110, 110, 110), Color(165, 165, 165))
                else -> JBColor.foreground()
            }
        }

    private fun humanize(value: String): String = value.replace('_', ' ')
}
