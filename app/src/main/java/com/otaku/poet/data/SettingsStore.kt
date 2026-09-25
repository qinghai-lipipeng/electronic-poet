package com.otaku.poet.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "poet_settings")

/** 持久化设置：当前词库包与生成参数。 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val ACTIVE_PACK = stringPreferencesKey("active_pack_path")
        val PARAGRAPHS = intPreferencesKey("paragraphs")
        val LINES = intPreferencesKey("lines_per_paragraph")
        val RHYME = booleanPreferencesKey("rhyme")
        val RHYME_ID = stringPreferencesKey("rhyme_id")
        val PER_PARAGRAPH_RHYME = booleanPreferencesKey("per_paragraph_rhyme")
        val EVERY_LINE = booleanPreferencesKey("every_line")
        val MAKE_TITLE = booleanPreferencesKey("make_title")
        val SEED = longPreferencesKey("seed")
        val SHOW_IN_UI = booleanPreferencesKey("show_in_ui")
        val MEMORY_LIMIT_PERCENT = intPreferencesKey("memory_limit_percent")
    }

    data class Saved(
        val activePackPath: String?,
        val params: GenParams,
    )

    val flow: Flow<Saved> = context.dataStore.data.map { p ->
        Saved(
            activePackPath = p[Keys.ACTIVE_PACK],
            params = GenParams(
                paragraphs = p[Keys.PARAGRAPHS] ?: 3,
                linesPerParagraph = p[Keys.LINES] ?: 4,
                rhyme = p[Keys.RHYME] ?: false,
                rhymeId = p[Keys.RHYME_ID],
                perParagraphRhyme = p[Keys.PER_PARAGRAPH_RHYME] ?: true,
                everyLine = p[Keys.EVERY_LINE] ?: true,
                makeTitle = p[Keys.MAKE_TITLE] ?: true,
                seed = p[Keys.SEED],
                showInUi = p[Keys.SHOW_IN_UI] ?: false,
                memoryLimitPercent = (p[Keys.MEMORY_LIMIT_PERCENT] ?: 60).coerceIn(1, 100),
            ),
        )
    }

    suspend fun setActivePack(path: String?) {
        context.dataStore.edit { it[Keys.ACTIVE_PACK] = path ?: "" }
    }

    suspend fun saveParams(params: GenParams) {
        context.dataStore.edit { p ->
            p[Keys.PARAGRAPHS] = params.paragraphs
            p[Keys.LINES] = params.linesPerParagraph
            p[Keys.RHYME] = params.rhyme
            if (params.rhymeId == null) p.remove(Keys.RHYME_ID) else p[Keys.RHYME_ID] = params.rhymeId
            p[Keys.PER_PARAGRAPH_RHYME] = params.perParagraphRhyme
            p[Keys.EVERY_LINE] = params.everyLine
            p[Keys.MAKE_TITLE] = params.makeTitle
            params.seed?.let { p[Keys.SEED] = it }
            p[Keys.SHOW_IN_UI] = params.showInUi
            p[Keys.MEMORY_LIMIT_PERCENT] = params.memoryLimitPercent.coerceIn(1, 100)
        }
    }
}
