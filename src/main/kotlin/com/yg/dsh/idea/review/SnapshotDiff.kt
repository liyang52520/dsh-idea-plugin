package com.yg.dsh.idea.review

object SnapshotDiff {

    enum class ChangeType { MODIFIED, NEW, DELETED }

    data class Change(
        val type: ChangeType,
        val relativePath: String,
        val baselineMd5: String?,
        val currentMd5: String?,
    )

    fun diff(baseline: Map<String, String>, currentMd5: Map<String, String>): List<Change> {
        val changes = mutableListOf<Change>()
        for ((path, bMd5) in baseline) {
            val cMd5 = currentMd5[path]
            when {
                cMd5 == null -> changes.add(Change(ChangeType.DELETED, path, bMd5, null))
                cMd5 != bMd5 -> changes.add(Change(ChangeType.MODIFIED, path, bMd5, cMd5))
            }
        }
        for ((path, cMd5) in currentMd5) {
            if (!baseline.containsKey(path)) {
                changes.add(Change(ChangeType.NEW, path, null, cMd5))
            }
        }
        return changes.sortedWith(compareBy({ it.type.ordinal }, { it.relativePath }))
    }
}