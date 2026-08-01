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
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class ArchitecturePanel : JPanel(BorderLayout()) {

    private val content = ViewportWidthPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }

    init {
        add(
            JBScrollPane(content).apply {
                border = BorderFactory.createEmptyBorder()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )
    }

    fun setArchitecture(architecture: CodebaseArchitecture?) {
        content.removeAll()
        if (architecture == null) {
            content.add(bodyText("No architecture map was returned for this focused walkthrough."))
        } else {
            addSection("System purpose")
            content.add(bodyText(architecture.systemPurpose))

            addSection("Components", topGap = 12)
            architecture.components.forEach { component ->
                content.add(buildComponent(component))
                content.add(Box.createVerticalStrut(6))
            }

            if (architecture.relationships.isNotEmpty()) {
                addSection("Relationships", topGap = 8)
                val componentNames = architecture.components.associate { it.id to it.name }
                architecture.relationships.forEach { relationship ->
                    content.add(buildRelationship(relationship, componentNames))
                    content.add(Box.createVerticalStrut(4))
                }
            }

            if (architecture.crossCuttingConcerns.isNotEmpty()) {
                addSection("Cross-cutting concerns", topGap = 8)
                content.add(bulletList(architecture.crossCuttingConcerns))
            }

            if (architecture.coverageNotes.isNotEmpty()) {
                addSection("Coverage notes", topGap = 8)
                content.add(bulletList(architecture.coverageNotes, warning = true))
            }
        }
        content.revalidate()
        content.repaint()
    }

    private fun buildComponent(component: ArchitectureComponent): JPanel {
        val panel = JPanel(BorderLayout(0, 4)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(7, 8),
            )
        }
        val title = JBLabel(component.name).apply {
            font = font.deriveFont(Font.BOLD)
        }
        val kind = JBLabel(humanize(component.kind)).apply {
            isOpaque = true
            background = JBColor(Color(235, 240, 246), Color(55, 65, 80))
            foreground = mutedForeground()
            border = JBUI.Borders.empty(1, 5)
            font = font.deriveFont(font.size - 1.5f)
        }
        panel.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(kind)
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        val details = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(bodyText(component.responsibility))
            add(Box.createVerticalStrut(4))
            add(metaText("Anchors: ${component.keyPaths.joinToString("  ·  ")}"))
            if (component.keySymbols.isNotEmpty()) {
                add(Box.createVerticalStrut(2))
                add(metaText("Key symbols: ${component.keySymbols.joinToString(", ")}"))
            }
            if (component.uncertain) {
                add(Box.createVerticalStrut(2))
                add(metaText("Some component grounding is uncertain.", warning = true))
            }
        }
        panel.add(details, BorderLayout.CENTER)
        return panel
    }

    private fun buildRelationship(
        relationship: ComponentRelationship,
        componentNames: Map<String, String>,
    ): JPanel {
        val from = componentNames[relationship.fromComponentId] ?: relationship.fromComponentId
        val to = componentNames[relationship.toComponentId] ?: relationship.toComponentId
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 4)
            add(JBLabel("$from  →  $to  ·  ${humanize(relationship.kind)}").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(Font.BOLD)
            })
            add(bodyText(relationship.description))
            val evidenceLocations = relationship.evidence.mapNotNull { evidence ->
                evidence.filePath?.let { path ->
                    val lines = evidence.startLine?.let { start ->
                        if (evidence.endLine != null && evidence.endLine != start) {
                            ":$start-${evidence.endLine}"
                        } else {
                            ":$start"
                        }
                    }.orEmpty()
                    "$path$lines"
                }
            }.distinct()
            if (evidenceLocations.isNotEmpty()) {
                add(metaText("Evidence: ${evidenceLocations.joinToString("  ·  ")}"))
            }
            if (relationship.uncertain) {
                add(metaText("Relationship is inferred or only partially grounded.", warning = true))
            }
        }
    }

    private fun addSection(title: String, topGap: Int = 0) {
        if (topGap > 0) content.add(Box.createVerticalStrut(topGap))
        content.add(JBLabel(title).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.BOLD, font.size + 1f)
            border = JBUI.Borders.emptyBottom(4)
        })
    }

    private fun bulletList(items: List<String>, warning: Boolean = false): WrappingTextArea =
        metaText(items.joinToString("\n") { "• $it" }, warning)

    private fun bodyText(value: String): WrappingTextArea = wrappingText(value).apply {
        foreground = JBColor.foreground()
    }

    private fun metaText(value: String, warning: Boolean = false): WrappingTextArea = wrappingText(value).apply {
        foreground = if (warning) {
            JBColor(Color(150, 95, 30), Color(220, 170, 90))
        } else {
            mutedForeground()
        }
        font = font.deriveFont(font.size - 1f)
    }

    private fun wrappingText(value: String): WrappingTextArea = WrappingTextArea().apply {
        text = value.trim()
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = BorderFactory.createEmptyBorder()
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    private fun humanize(value: String): String = value.replace('_', ' ').trim()

    private fun mutedForeground(): JBColor =
        JBColor(Color(110, 110, 110), Color(165, 165, 165))
}
