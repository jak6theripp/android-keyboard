package org.futo.inputmethod.latin.uix.actions

import android.widget.Toast
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action

/**
 * Toolbar slots that exist on the Samsung keyboard and are implemented in Phase 5.
 * Until then they are placeholders so the toolbar order is already the final one.
 */
val AiReplyAction = Action(
    icon = R.drawable.sparkles,
    name = R.string.action_ai_reply_title,
    simplePressImpl = { manager, _ ->
        Toast.makeText(manager.getContext(), R.string.action_not_built_yet, Toast.LENGTH_SHORT).show()
    },
    windowImpl = null,
)

val TranslateAction = Action(
    icon = R.drawable.translate,
    name = R.string.action_translate_title,
    simplePressImpl = { manager, _ ->
        Toast.makeText(manager.getContext(), R.string.action_not_built_yet, Toast.LENGTH_SHORT).show()
    },
    windowImpl = null,
)
