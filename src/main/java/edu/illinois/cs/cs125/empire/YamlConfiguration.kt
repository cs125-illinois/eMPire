package edu.illinois.cs.cs125.empire

data class EmpireStudentConfig(
        val segments: Map<String, Boolean>?,
        val checkpoint: String?,
        val useProvided: Boolean = true
)