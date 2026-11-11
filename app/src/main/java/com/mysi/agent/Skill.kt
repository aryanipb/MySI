package com.mysi.agent

import android.content.Context

/**
 * A named group of tools the model can be given for a request. Only the skills that match a
 * request are loaded, so the model never has to hold every tool in its small context window.
 *
 * [description] is one plain sentence the router and the model use to decide relevance,
 * [keywords] are extra trigger words for the router, and [instructions] are short rules added to
 * the system prompt only while the skill is loaded.
 */
class Skill(
    val name: String,
    val description: String,
    val keywords: List<String> = emptyList(),
    val instructions: String = "",
    val tools: (Context) -> List<ToolDef>,
)
