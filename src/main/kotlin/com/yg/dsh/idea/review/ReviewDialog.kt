package com.yg.dsh.idea.review

import com.yg.dsh.idea.i18n.I18nBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel

class ReviewDialog(private val project: Project) : com.intellij.openapi.ui.DialogWrapper(project, true) {

    private val snapshot = SnapshotManager(project)
    private val manager = ReviewManager(project, snapshot)
    private val model = DefaultListModel<ReviewItem>()
    private val list = JBList(model)

    init {
        title = I18nBundle.message("review.title")
        init()
        refresh()
    }

    override fun createCenterPanel(): JPanel {
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is ReviewItem) {
                    text = value.label
                    value.change?.let { change ->
                        foreground = when (change.type) {
                            SnapshotDiff.ChangeType.MODIFIED -> java.awt.Color(200, 130, 0)
                            SnapshotDiff.ChangeType.NEW -> java.awt.Color(0, 130, 60)
                            SnapshotDiff.ChangeType.DELETED -> java.awt.Color(180, 60, 60)
                        }
                    }
                }
                return c
            }
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(buildActions(), BorderLayout.SOUTH)
        }
    }

    private fun buildActions(): JPanel = JPanel().apply {
        layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT)
        add(JButton(I18nBundle.message("review.diff")).apply { addActionListener { showDiff() } })
        add(JButton(I18nBundle.message("review.restore")).apply { addActionListener { restoreSelected() } })
        add(JButton(I18nBundle.message("review.restoreAll")).apply { addActionListener { restoreAll() } })
        add(JButton(I18nBundle.message("review.ignore")).apply { addActionListener { ignoreSelected() } })
        add(JButton(I18nBundle.message("review.rebaseline")).apply { addActionListener { rebaseline() } })
    }

    /**
     * 全量刷新（基线构建 + VFS 刷新 + 全项目 MD5 对比）在后台线程执行，
     * 模态进度条可取消；EDT 只做结果渲染。大项目上不再冻结 UI。
     */
    private fun refresh() {
        // Runnable 版进度 API 返回 Unit，结果经持有者带出（后台线程写、EDT 在 join 后读）。
        var computed: List<SnapshotDiff.Change> = emptyList()
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    manager.refreshVfs()
                    ApplicationManager.getApplication()
                        .runReadAction(Computable { snapshot.buildIfAbsent() })
                    computed = manager.refreshChanges()
                },
                I18nBundle.message("review.title"),
                true,
                project,
                null,
            )
        } catch (_: ProcessCanceledException) {
            return
        } catch (e: Exception) {
            LOG.warn("review refresh failed", e)
        }
        model.clear()
        for (c in computed) model.addElement(ReviewItem.of(c))
        if (computed.isEmpty()) {
            model.addElement(ReviewItem.noChanges())
        }
        list.selectedIndex = if (model.size() > 0) 0 else -1
    }

    private fun showDiff() {
        selectedChange()?.let { manager.showDiff(it) }
    }

    private fun restoreSelected() {
        val c = selectedChange() ?: return
        if (manager.restoreFile(c)) {
            model.removeElementAt(list.selectedIndex)
            com.intellij.openapi.ui.Messages.showInfoMessage(project, I18nBundle.message("review.restored", c.relativePath), I18nBundle.message("review.title"))
        } else {
            com.intellij.openapi.ui.Messages.showErrorDialog(project, I18nBundle.message("review.restoreFailed", c.relativePath), I18nBundle.message("review.title"))
        }
    }

    private fun restoreAll() {
        val changes = (0 until model.size()).mapNotNull { model.get(it)?.change }
        if (changes.isEmpty()) return
        val choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            I18nBundle.message("review.restoreAll.confirm", changes.size),
            I18nBundle.message("review.title"),
            null,
        )
        if (choice != com.intellij.openapi.ui.Messages.YES) return
        val ok = manager.restoreAll(changes)
        com.intellij.openapi.ui.Messages.showInfoMessage(project, I18nBundle.message("review.restoredAll", ok), I18nBundle.message("review.title"))
        refresh()
    }

    private fun ignoreSelected() {
        val c = selectedChange() ?: return
        manager.ignoreChange(c)
        model.removeElementAt(list.selectedIndex)
    }

    private fun rebaseline() {
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    manager.refreshVfs()
                    ApplicationManager.getApplication()
                        .runReadAction(Computable { manager.rebuildBaseline() })
                },
                I18nBundle.message("review.title"),
                false,
                project,
                null,
            )
        } catch (e: Exception) {
            LOG.warn("rebaseline failed", e)
        }
        com.intellij.openapi.ui.Messages.showInfoMessage(project, I18nBundle.message("review.rebaselined"), I18nBundle.message("review.title"))
        refresh()
    }

    private fun selectedChange(): SnapshotDiff.Change? {
        val idx = list.selectedIndex
        if (idx < 0 || idx >= model.size()) return null
        return model.get(idx)?.change
    }

    private data class ReviewItem(val change: SnapshotDiff.Change?, val label: String) {
        companion object {
            fun of(change: SnapshotDiff.Change): ReviewItem {
                val prefix = when (change.type) {
                    SnapshotDiff.ChangeType.MODIFIED -> "[M] "
                    SnapshotDiff.ChangeType.NEW -> "[+] "
                    SnapshotDiff.ChangeType.DELETED -> "[-] "
                }
                return ReviewItem(change, prefix + change.relativePath)
            }
            fun noChanges(): ReviewItem = ReviewItem(null, I18nBundle.message("review.noChanges"))
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ReviewDialog::class.java)
    }
}