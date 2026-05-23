/*
 * Ven's Aliucord Plugins
 * Copyright (C) 2021 Vendicated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.vendicated.aliucordplugs.themer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage

/** Alpha values matching the Firefly reference theme for full transparency. */
private const val PRIMARY_BG_ALPHA = 0x49
private const val SECONDARY_BG_ALPHA = 0x24

private val BACKGROUND_COLOR_NAMES = hashSetOf(
    *SIMPLE_BG_NAMES,
    *SIMPLE_BG_SECONDARY_NAMES,
    "statusbar",
    "input_background",
    "active_channel",
)

private val BACKGROUND_ATTR_NAMES = hashSetOf(
    *SIMPLE_BG_ATTRS,
    *SIMPLE_BG_SECONDARY_ATTRS,
    "colorBackgroundPrimary",
    "colorSurface",
    "colorBackgroundFloating",
    "colorTabsBackground",
    "colorBackgroundTertiary",
    "colorBackgroundSecondary",
    "theme_chat_spoiler_inapp_bg",
    "theme_chat_spoiler_bg",
)

object FullTransparency {
    private val themedPrimaryBackgrounds = HashSet<Int>()
    private val themedSecondaryBackgrounds = HashSet<Int>()

    private val appPanelViewIds by lazy {
        listOf(
            "widget_home_panel_center_chat",
            "widget_home_panel_left",
            "widget_home_panel_center",
            "panel_center",
            "panel_left",
            "widget_chat_list",
            "chat_list_recycler",
            "widget_chat_list_recycler",
            "chat_list_view",
        ).mapNotNull { name ->
            val id = Utils.getResId(name, "id")
            if (id != 0) id else null
        }.toSet()
    }

    fun isActive(): Boolean {
        if (AThemer.mSettings.transparencyMode != TransparencyMode.FULL) return false
        return ResourceManager.customBg != null || ResourceManager.animatedBgUri != null
    }

    fun isBackgroundResource(name: String) =
        name in BACKGROUND_COLOR_NAMES || name in BACKGROUND_ATTR_NAMES

    fun registerThemedBackground(color: Int, name: String) {
        if (Color.alpha(color) != 0xFF) return
        if (isSecondaryBackground(name)) themedSecondaryBackgrounds.add(color)
        else themedPrimaryBackgrounds.add(color)
    }

    fun clearThemedBackgrounds() {
        themedPrimaryBackgrounds.clear()
        themedSecondaryBackgrounds.clear()
    }

    /** Applied when loading theme colours — only for named background resources. */
    fun applyToNamedColor(color: Int, name: String?): Int {
        if (!isActive() || name == null || !isBackgroundResource(name)) return color
        return applyAlpha(color, isSecondaryBackground(name))
    }

    /**
     * Applied only when a [View] sets its background colour.
     * Never used for text, icons, or [ColorStateList] tints.
     */
    fun applyToViewBackground(color: Int): Int? {
        if (!isActive() || Color.alpha(color) != 0xFF) return null

        ResourceManager.getNameByColor(color)?.let { name ->
            if (!isBackgroundResource(name)) return null
            return applyAlpha(color, isSecondaryBackground(name))
        }

        when (color) {
            in themedSecondaryBackgrounds -> return applyAlpha(color, secondary = true)
            in themedPrimaryBackgrounds -> return applyAlpha(color, secondary = false)
        }
        return null
    }

    fun applyToViewBackground(drawable: ColorDrawable) {
        applyToViewBackground(drawable.color)?.let { drawable.color = it }
    }

    fun isAppPanelView(view: View): Boolean = view.id in appPanelViewIds

    /** Clears opaque home/chat panel backgrounds so wallpaper + scrim can show through. */
    fun clearAppPanelBackgrounds(root: View) {
        fun clearIfPanel(view: View) {
            if (view.id in appPanelViewIds) view.background = null
        }

        clearIfPanel(root)

        val panelCenterId = Utils.getResId("panel_center", "id")
        val panelLeftId = Utils.getResId("panel_left", "id")
        var cursor: View? = root
        while (cursor != null) {
            clearIfPanel(cursor)
            if (cursor.id == panelCenterId || cursor.id == panelLeftId) break
            cursor = cursor.parent as? View
        }

        if (root is ViewGroup) {
            val queue = ArrayDeque<View>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val view = queue.removeFirst()
                clearIfPanel(view)
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
                }
            }
        }
    }

    /**
     * Plugin settings use wallpaper on the activity root; chat uses [panelCenterId] (CHAT mode behaviour).
     */
    fun applyChatPanelWallpaper(start: View, panelCenterId: Int, chatBgId: Int, actionBarRootId: Int, setWallpaper: (ViewGroup) -> Unit) {
        if (panelCenterId == 0) {
            clearAppPanelBackgrounds(start)
            setWallpaper(findWallpaperRoot(start, actionBarRootId))
            return
        }

        var view = start
        while (view.id != panelCenterId) {
            view = view.parent as? View ?: run {
                clearAppPanelBackgrounds(start)
                setWallpaper(findWallpaperRoot(start, actionBarRootId))
                return
            }
            if (chatBgId != 0 && view.id == chatBgId) view.background = null
        }

        view.background = null
        setWallpaper(view as ViewGroup)
        clearAppPanelBackgrounds(start)
        start.post { clearAppPanelBackgrounds(start) }
    }

    /** Same visual treatment as Aliucord [SettingsPage] (not Discord's global settings hub). */
    fun shouldApplyWallpaperScrim(clazz: Class<*>, className: String): Boolean {
        if (className.startsWith("com.discord.widgets.settings.")) return false
        if (SettingsPage::class.java.isAssignableFrom(clazz)) return true
        if (className.endsWith("WidgetChatList") || className.endsWith("WidgetHome")) return true
        if (className == "com.discord.widgets.debugging.WidgetDebugging") return true
        if (className == "com.discord.widgets.search.WidgetSearch") return true
        return false
    }

    /**
     * Finds the view to attach the wallpaper to.
     * Prefers the activity [android.R.id.content] frame (reliable on Waydroid / custom ROMs),
     * then [actionBarRootId], then the topmost ancestor.
     */
    fun findWallpaperRoot(start: View, actionBarRootId: Int): ViewGroup {
        findActivityContentRoot(start)?.let { return it }

        var view: View = start
        if (actionBarRootId != 0) {
            var found = false
            var cursor: View? = start
            while (cursor != null) {
                if (cursor.id == actionBarRootId) {
                    view = cursor
                    found = true
                    break
                }
                cursor = cursor.parent as? View
            }
            if (!found) {
                while (view.parent is View) view = view.parent as View
                return view as ViewGroup
            }
        } else {
            while (view.parent is View) view = view.parent as View
        }

        while (view.parent is View) view = view.parent as View
        return view as ViewGroup
    }

    /** Parent suitable for inserting an animated wallpaper behind the UI. */
    fun findWallpaperParent(wallpaperRoot: View): ViewGroup {
        val parent = wallpaperRoot.parent as? ViewGroup
        if (parent != null) return parent
        return findActivityContentRoot(wallpaperRoot) ?: wallpaperRoot as ViewGroup
    }

    private fun findActivityContentRoot(start: View): ViewGroup? {
        val activity = start.context.findActivity() ?: Utils.appActivity
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        return decor.findViewById(android.R.id.content) ?: decor
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun isSecondaryBackground(name: String) =
        name in SIMPLE_BG_SECONDARY_NAMES ||
            name in arrayOf(
                "input_background",
                "statusbar",
                "active_channel",
                "colorBackgroundTertiary",
                "colorBackgroundSecondary",
                "theme_chat_spoiler_bg",
            )

    private fun applyAlpha(color: Int, secondary: Boolean): Int {
        if (Color.alpha(color) != 0xFF) return color
        val alpha = if (secondary) SECONDARY_BG_ALPHA else PRIMARY_BG_ALPHA
        return ColorUtils.setAlphaComponent(color, alpha)
    }
}
