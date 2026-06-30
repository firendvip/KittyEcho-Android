/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wordtaker.keyboard.ime.core

import android.content.Context
import com.wordtaker.keyboard.app.FlorisPreferenceStore
import com.wordtaker.keyboard.ime.keyboard.CurrencySet
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.lib.FlorisLocale
import com.wordtaker.keyboard.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.wordtaker.lib.kotlin.collectLatestIn

val SubtypeJsonConfig = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = false
}

/**
 * Class which acts as a high level helper for the raw implementation of subtypes in the prefs. Additionally provides
 * helper methods for the in-keyboard language switch process.
 */
class SubtypeManager(context: Context) {
    private val prefs by FlorisPreferenceStore
    private val keyboardManager by context.keyboardManager()
    private val scope = CoroutineScope(Dispatchers.Default)

    val subtypesFlow: StateFlow<List<Subtype>>
        field = MutableStateFlow(listOf())
    inline var subtypes
        get() = subtypesFlow.value
        private set(v) { subtypesFlow.value = v }

    val activeSubtypeFlow: StateFlow<Subtype>
        // Default to Chinese pinyin (not en-US) so the keyboard boots into pinyin on first
        // launch even before the async subtype list / preset flow has emitted. Without this,
        // the brief window where the enabled list is still empty would resolve active to the
        // en-US Subtype.DEFAULT and produce no Chinese candidates.
        field = MutableStateFlow(Subtype.PINYIN_DEFAULT)
    inline var activeSubtype
        get() = activeSubtypeFlow.value
        private set(v) { activeSubtypeFlow.value = v }

    /** Guards first-launch seeding so it runs at most once per process. */
    private val didSeedDefaults = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        prefs.localization.subtypes.asFlow().collectLatestIn(scope) { listRaw ->
            flogDebug { listRaw }
            val list = if (listRaw.isNotBlank()) {
                SubtypeJsonConfig.decodeFromString<List<Subtype>>(listRaw)
            } else {
                emptyList()
            }
            subtypes = list
            evaluateActiveSubtype(list)
        }
        // First-launch default: when the user has no enabled subtypes yet, seed Chinese pinyin
        // (active) plus English so the in-keyboard language switch (geo key) stays usable.
        //
        // Seeding is done eagerly from hardcoded subtypes (independent of the async subtype-
        // preset flow) so a fresh install is GUARANTEED to boot into pinyin even if the preset
        // flow is slow or never emits — the previous preset-flow-driven seeding lost this race
        // and left the keyboard on en-US with no Chinese candidates.
        seedDefaultSubtypes()

        // Reconcile the seeded Chinese subtype with the onboarding-selected keyboard style.
        // The host app constructs this manager (and thus seeds) BEFORE onboarding writes the
        // user's chosen style, so the initial seed always uses the default (qwerty pinyin).
        // Watch the style pref and, while the subtype list is still the untouched default seed
        // (exactly one Chinese + one English), swap the Chinese subtype to the chosen style so
        // the choice actually drives the keyboard. Once the user manually edits their subtype
        // list this no longer applies (we only act on the pristine two-item seed).
        reconcileStyleWithSeededSubtype()
    }

    /**
     * Keeps the seeded Chinese subtype in sync with [selectedKeyboardStyle]. Only mutates the
     * pristine first-launch seed (one zh-* subtype + one en-US subtype); never touches a
     * user-curated list. Idempotent: no write when the seeded subtype already matches the style.
     */
    private fun reconcileStyleWithSeededSubtype() {
        // React to the onboarding-selected style changing. Two jobs:
        //  1. While the subtype list is still the pristine default seed (one zh-* + one en-US),
        //     swap the Chinese subtype to the chosen style so the persisted list matches.
        //  2. Re-evaluate the active subtype so the change takes effect immediately, including
        //     the empty-list fallback path (which is style-aware in evaluateActiveSubtype). This
        //     is what makes the choice drive the keyboard even before the seed has persisted.
        prefs.internal.selectedKeyboardStyle.asFlow().collectLatestIn(scope) {
            reconcileSeededSubtype()
            evaluateActiveSubtype(subtypes)
        }
        subtypesFlow.collectLatestIn(scope) { reconcileSeededSubtype() }
    }

    /**
     * While the subtype list is still the pristine first-launch seed (one zh-* + one en-US),
     * keeps its Chinese subtype in sync with [selectedKeyboardStyle]. Never touches a
     * user-curated list. Idempotent.
     */
    private suspend fun reconcileSeededSubtype() {
        val current = subtypes
        if (current.size != 2) return
        val chinese = current.find { it.primaryLocale.language == "zh" } ?: return
        val other = current.find { it.id != chinese.id } ?: return
        val desired = Subtype.pinyinDefaultFor(prefs.internal.selectedKeyboardStyle.get())
        if (desired.equalsExcludingId(chinese)) return
        val replacement = desired.copy(id = chinese.id)
        persistNewSubtypeList(listOf(replacement, other))
        if (prefs.localization.activeSubtypeId.get() == chinese.id) {
            prefs.localization.activeSubtypeId.set(replacement.id)
        }
    }

    /**
     * Seeds the default subtype list on first launch: Chinese pinyin (set active) and English
     * (en-US) so users can switch between the two via the geo key. Uses the hardcoded
     * [Subtype.PINYIN_DEFAULT] / [Subtype.DEFAULT] so it does not depend on the async subtype-
     * preset flow. Runs at most once per process and only while the enabled subtype list is
     * still empty in prefs (re-checked just before writing to avoid clobbering a user-edited
     * list on a process restart / genuine wipe).
     */
    private fun seedDefaultSubtypes() {
        if (!didSeedDefaults.compareAndSet(false, true)) return
        scope.launch {
            // Re-read prefs immediately before writing to close a TOCTOU race with the prefs
            // flow collector above. If real data is already present (process restart, or the
            // user already has subtypes), abort without writing so we never clobber the user's
            // list. We intentionally keep the guard set on abort: releasing it would allow a
            // concurrent re-entry to pass compareAndSet again and double-seed. Seeding only ever
            // needs to happen once per process, so leaving it set is correct.
            val existingRaw = prefs.localization.subtypes.get()
            if (existingRaw.isNotBlank()) {
                return@launch
            }
            val now = System.currentTimeMillis()
            // Honor the keyboard style chosen during onboarding. All six styles map to a
            // distinct subtype via [Subtype.pinyinDefaultFor]. Note: when the host app seeds
            // before onboarding has written the chosen style, this reads the default; the
            // post-seed [reconcileStyleWithSeededSubtype] collector corrects the seeded subtype
            // once the real style is persisted.
            val style = prefs.internal.selectedKeyboardStyle.get()
            val pinyinSubtype = Subtype.pinyinDefaultFor(style).copy(id = now)
            val englishSubtype = Subtype.DEFAULT.copy(id = now + 1)
            val seeded = listOf(pinyinSubtype, englishSubtype)
            val listRaw = SubtypeJsonConfig.encodeToString(seeded)
            prefs.localization.subtypes.set(listRaw)
            prefs.localization.activeSubtypeId.set(pinyinSubtype.id)
        }
    }

    private fun persistNewSubtypeList(list: List<Subtype>) = scope.launch {
        val listRaw = SubtypeJsonConfig.encodeToString(list)
        prefs.localization.subtypes.set(listRaw)
    }

    /**
     * Gets the active subtype and returns it. If the activeSubtypeId points to a non-existent
     * subtype, this method tries to determine a new active subtype.
     *
     * @return The active subtype or null, if the subtype list is empty or no new active subtype
     *  could be determined.
     */
    private fun evaluateActiveSubtype(list: List<Subtype>) = scope.launch {
        val activeSubtypeId = prefs.localization.activeSubtypeId.get()
        // When the enabled list is still empty (first launch, before seeding has persisted),
        // fall back to the hardcoded pinyin default instead of the en-US Subtype.DEFAULT so the
        // keyboard shows Chinese candidates immediately. Do NOT persist this fallback id — that
        // would let it win over the real seeded pinyin subtype.
        if (list.isEmpty()) {
            // Honor the onboarding-selected style even before the seeded list has persisted, so
            // choosing 九宫格/双拼/五笔/笔画/手写 drives the right layout immediately. Falls back
            // to qwerty pinyin for the default style.
            activeSubtype = Subtype.pinyinDefaultFor(prefs.internal.selectedKeyboardStyle.get())
            return@launch
        }
        val subtype = list.find { it.id == activeSubtypeId } ?: list.firstOrNull() ?: Subtype.PINYIN_DEFAULT
        if (subtype.id != activeSubtypeId) {
            prefs.localization.activeSubtypeId.set(subtype.id)
        }
        activeSubtype = subtype
    }

    /**
     * Adds a given [subtype] to the subtype list, if it does not exist.
     *
     * @param subtype The subtype which should be added.
     * @return True if the subtype was added, false otherwise. A return value of false indicates
     *  that the subtype already exists.
     */
    fun addSubtype(subtype: Subtype): Boolean {
        val subtypeToAdd = subtype.copy(id = System.currentTimeMillis())
        val subtypeList = subtypes
        if (subtypeList.find { it.equalsExcludingId(subtype) } != null) {
            return false
        }
        val newSubtypeList = subtypeList + subtypeToAdd
        persistNewSubtypeList(newSubtypeList)
        return true
    }

    /**
     * Gets the currency set from the given subtype and returns it. Falls back to a default one if the subtype does not
     * exist.
     *
     * @return The currency set or a fallback.
     */
    fun getCurrencySet(subtypeToSearch: Subtype): CurrencySet {
        return keyboardManager.resources.currencySets.value[subtypeToSearch.currencySet] ?: CurrencySet.Fallback
    }

    /**
     * Gets a subtype by the given [id].
     *
     * @param id The id of the subtype you want to get.
     * @return The subtype or null, if no matching subtype could be found.
     */
    fun getSubtypeById(id: Long): Subtype? {
        val subtypeList = subtypes
        return subtypeList.find { it.id == id }
    }

    /**
     * Gets the default system subtype for a given [locale].
     *
     * @param locale The locale of the default system subtype to get.
     * @return The default system locale or null, if no matching default system subtype could be
     *  found.
     */
    fun getSubtypePresetForLocale(locale: FlorisLocale): SubtypePreset? {
        val presets = keyboardManager.resources.subtypePresets.value
        return presets.find { it.locale == locale } ?: presets.find { it.locale.language == locale.language }
    }

    /**
     * Modifies an existing subtype with the newly provided details. In order to determine which
     * subtype should be updated, the id must be the same.
     *
     * @param subtypeToModify The subtype with the new details but same id.
     */
    fun modifySubtypeWithSameId(subtypeToModify: Subtype) {
        val subtypeList = subtypes
        val index = subtypeList.indexOfFirst { subtypeToModify.id == it.id }
        if (index >= 0 && index < subtypeList.size) {
            val newSubtypeList = subtypeList.mapIndexed { n, subtype ->
                if (n == index) {
                    subtypeToModify
                } else {
                    subtype
                }
            }
            persistNewSubtypeList(newSubtypeList)
        }
    }

    /**
     * Removes a given [subtypeToRemove]. Nothing happens if the given [subtypeToRemove] does not
     * exist.
     *
     * @param subtypeToRemove The subtype which should be removed.
     */
    fun removeSubtype(subtypeToRemove: Subtype) {
        val subtypeList = subtypes
        val indexToRemove = subtypeList.indexOf(subtypeToRemove)
        if (indexToRemove in subtypeList.indices) {
            val newSubtypeList = subtypeList.mapIndexedNotNull { n, subtype ->
                if (n != indexToRemove) {
                    subtype
                } else {
                    null
                }
            }
            persistNewSubtypeList(newSubtypeList)
            evaluateActiveSubtype(newSubtypeList)
        }
    }

    /**
     * Switch to the previous subtype in the subtype list if possible.
     */
    fun switchToPrevSubtype() = scope.launch {
        val subtypeList = subtypes
        if (subtypeList.isEmpty()) return@launch
        val cachedActiveSubtype = activeSubtype
        var triggerNextSubtype = false
        // Fall back to a real list member (not the en-US Subtype.DEFAULT). When the current
        // active is the transient PINYIN_DEFAULT (id=-2, not in the list) no element matches
        // cachedActiveSubtype, so without this we'd jump to en-US and persist id=-1.
        var newActiveSubtype: Subtype = subtypeList.first()
        for (subtype in subtypeList.asReversed()) {
            if (triggerNextSubtype) {
                triggerNextSubtype = false
                newActiveSubtype = subtype
            } else if (subtype == cachedActiveSubtype) {
                triggerNextSubtype = true
            }
        }
        if (triggerNextSubtype) {
            newActiveSubtype = subtypeList.last()
        }
        prefs.localization.activeSubtypeId.set(newActiveSubtype.id)
        activeSubtype = newActiveSubtype
    }

    /**
     * Switch to the next subtype in the subtype list if possible.
     */
    fun switchToNextSubtype() = scope.launch {
        val subtypeList = subtypes
        if (subtypeList.isEmpty()) return@launch
        val cachedActiveSubtype = activeSubtype
        var triggerNextSubtype = false
        // Fall back to a real list member (not the en-US Subtype.DEFAULT) — see switchToPrevSubtype.
        var newActiveSubtype: Subtype = subtypeList.first()
        for (subtype in subtypeList) {
            if (triggerNextSubtype) {
                triggerNextSubtype = false
                newActiveSubtype = subtype
            } else if (subtype == cachedActiveSubtype) {
                triggerNextSubtype = true
            }
        }
        if (triggerNextSubtype) {
            newActiveSubtype = subtypeList.first()
        }
        prefs.localization.activeSubtypeId.set(newActiveSubtype.id)
        activeSubtype = newActiveSubtype
    }

    fun switchToSubtypeById(id: Long) = scope.launch {
        if (subtypes.any { it.id == id }) {
            activeSubtype = getSubtypeById(id)!!
            prefs.localization.activeSubtypeId.set(id)
        }
    }
}
